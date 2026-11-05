"""Draws Convx's Windows icon and installer pictures from the app's own monogram.

Run from the repository root: python3 desktop/packaging/make_icon.py

The monogram is the outline in convx-monogram.svg, copied from the phone app's app_icons/convx_LOGO.svg,
so every size is drawn from the vector rather than scaled from a picture. It's coloured as Convx's
crimson icon: a crimson that deepens towards the bottom, on a near-black tile with a crimson tint.

Writes:
  desktop/src/main/resources/convx.png, convx.ico and app-icon/<size>.png (the window and taskbar)
  desktop/packaging/windows/dialog.bmp and banner.bmp (the MSI's own dialogs)
Then run installer/windows/make_art.py for the setup window, which reads the monogram from here too.
"""
import re
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
SVG = Path(__file__).resolve().parent / "convx-monogram.svg"
RES = ROOT / "desktop" / "src" / "main" / "resources"
WIN = ROOT / "desktop" / "packaging" / "windows"

# Sampled from the crimson Convx icon: the top of the mark, its bottom, and the tile behind it.
MARK_TOP = (0xBE, 0x16, 0x60)
MARK_BOTTOM = (0x6E, 0x12, 0x3E)
TILE_TOP = (0x0E, 0x02, 0x07)
TILE_BOTTOM = (0x09, 0x07, 0x08)
SS = 8  # supersampling for edges


def outline():
    d = re.search(r'\sd="([^"]+)"', SVG.read_text()).group(1)
    nums = [float(n) for n in re.findall(r"-?\d+(?:\.\d+)?", d)]
    return list(zip(nums[0::2], nums[1::2]))


PTS = outline()
X0, Y0 = min(p[0] for p in PTS), min(p[1] for p in PTS)
X1, Y1 = max(p[0] for p in PTS), max(p[1] for p in PTS)


def monogram_mask(height):
    """The monogram's coverage, [height] pixels tall, cropped to its own bounds."""
    k = height * SS / (Y1 - Y0)
    w = int(np.ceil((X1 - X0) * k)) + 2 * SS
    h = int(np.ceil((Y1 - Y0) * k)) + 2 * SS
    im = Image.new("L", (w, h), 0)
    ImageDraw.Draw(im).polygon([((x - X0) * k + SS, (y - Y0) * k + SS) for x, y in PTS], fill=255)
    return im.resize((max(1, round(w / SS)), max(1, round(h / SS))), Image.LANCZOS)


def vertical(size, top, bottom):
    w, h = size
    t = np.linspace(0, 1, h)[:, None, None]
    arr = np.array(top, np.float32) * (1 - t) + np.array(bottom, np.float32) * t
    return Image.fromarray(np.repeat(arr, w, axis=1).clip(0, 255).astype(np.uint8), "RGB")


def monogram(height, depth=1.0):
    """The coloured monogram on transparency. [depth] below 1 deepens the bottom less, for small sizes."""
    a = monogram_mask(height)
    bottom = tuple(t + (b - t) * depth for t, b in zip(MARK_TOP, MARK_BOTTOM))
    img = vertical(a.size, MARK_TOP, bottom).convert("RGBA")
    img.putalpha(a)
    return img


def rounded_mask(size, inset, radius):
    big = Image.new("L", (size * SS, size * SS), 0)
    ImageDraw.Draw(big).rounded_rectangle(
        [inset * SS, inset * SS, (size - inset) * SS - 1, (size - inset) * SS - 1], radius=radius * SS, fill=255)
    return big.resize((size, size), Image.LANCZOS)


def icon(size):
    """A rounded tile with the monogram. Small sizes fill the square and give the mark more room."""
    small = size <= 32
    inset = 0 if small else round(size * 0.028)
    tile_side = size - 2 * inset
    radius = tile_side * (0.2 if small else 0.225)
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    tile = vertical((size, size), TILE_TOP, TILE_BOTTOM).convert("RGBA")
    tile.putalpha(rounded_mask(size, inset, radius))
    img.alpha_composite(tile)
    # At taskbar sizes the mark is a few pixels thick, so it keeps more of its brightness at the bottom.
    m = monogram(round(tile_side * (0.74 if small else 0.62)), depth=0.5 if small else 1.0)
    img.alpha_composite(m, ((size - m.width) // 2, (size - m.height) // 2))
    return img


def write_icons():
    sizes = [16, 20, 24, 32, 40, 48, 64, 256]
    (RES / "app-icon").mkdir(exist_ok=True)
    for s in sizes:
        icon(s).save(RES / "app-icon" / f"{s}.png", optimize=True)
    icon(512).save(RES / "convx.png", optimize=True)
    # Each size drawn on its own, not scaled from the largest.
    frames = [icon(s) for s in sizes]
    frames[-1].save(RES / "convx.ico", format="ICO", sizes=[(s, s) for s in sizes], append_images=frames[:-1])


def glow(size, centre, radius, colour, strength):
    w, h = size
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    d = np.sqrt((xx - centre[0]) ** 2 + (yy - centre[1]) ** 2) / radius
    a = (np.clip(1 - d, 0, 1) ** 2 * strength)[:, :, None]
    return a * np.array(colour, np.float32)


def write_msi_art():
    """The MSI's own dialogs draw black text on the right, so the art keeps that side white."""
    # Welcome / finish dialog: a black panel on the left with the monogram and a crimson glow.
    w, h, panel = 493, 312, 164
    arr = np.full((h, w, 3), 255, np.float32)
    dark = glow((panel, h), (panel / 2, h / 2 - 10), 150, MARK_TOP, 0.35)
    arr[:, :panel] = dark
    img = Image.fromarray(arr.clip(0, 255).astype(np.uint8), "RGB")
    m = monogram(104)
    img.paste(m, ((panel - m.width) // 2, (h - m.height) // 2 - 10), m)
    ImageDraw.Draw(img).rectangle([panel, 0, panel + 1, h], fill=MARK_TOP)
    img.save(WIN / "dialog.bmp")
    # Banner across the top of the other pages: white, with the icon at the right.
    bw, bh = 493, 58
    banner = Image.new("RGB", (bw, bh), (255, 255, 255))
    ic = icon(44)
    banner.paste(ic, (bw - ic.width - 8, (bh - ic.height) // 2), ic)
    banner.save(WIN / "banner.bmp")


if __name__ == "__main__":
    write_icons()
    write_msi_art()
    print("Wrote the icon and the MSI pictures")
