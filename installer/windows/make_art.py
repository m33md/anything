"""Draws the pictures for Convx's setup window (installer/windows/theme).

Run from the repository root: python3 installer/windows/make_art.py

The setup window is 560 x 360 at 100% scaling. Everything the setup draws over sits on pure black,
so every picture here is opaque on black and nothing depends on how Windows blends transparency.
Pictures that must stay sharp come in 1x, 2x and 3x; Burn picks the closest for the screen's
scaling. The window background is 1x only (Burn stretches it), so it holds only soft shapes.
"""
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "installer" / "windows" / "theme"
FONT = ROOT / "desktop" / "src" / "main" / "resources" / "fonts" / "plus_jakarta_sans.ttf"
ICON = ROOT / "desktop" / "src" / "main" / "resources" / "convx.png"

W, H = 560, 360
RED = (0xFA, 0x2D, 0x48)
BLACK = (0, 0, 0)
SCALES = (1, 2, 3)


def save(img, name, scale):
    suffix = "" if scale == 1 else f"@{scale}x"
    img.convert("RGB").save(OUT / f"{name}{suffix}.png", optimize=True)


def mark_alpha():
    """The Convx mark's coverage, from the app icon (red mark on a black tile)."""
    src = np.asarray(Image.open(ICON).convert("RGBA")).astype(np.float32)
    alpha = np.clip(src[:, :, 0] / 250.0, 0, 1) * (src[:, :, 3] / 255.0)
    ys, xs = np.nonzero(alpha > 0.03)
    crop = alpha[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    return Image.fromarray((crop * 255).astype(np.uint8), "L")


def mark(size, scale):
    px = size * scale
    a = mark_alpha()
    w, h = a.size
    k = px / max(w, h)
    a = a.resize((max(1, round(w * k)), max(1, round(h * k))), Image.LANCZOS)
    img = Image.new("RGB", (px, px), BLACK)
    img.paste(Image.new("RGB", a.size, RED), ((px - a.size[0]) // 2, (px - a.size[1]) // 2), a)
    return img


def wordmark(scale):
    """ "Convx" in the app's font, Plus Jakarta Sans ExtraBold, white."""
    w, h = 132, 34
    font = ImageFont.truetype(str(FONT), 30 * scale)
    font.set_variation_by_name("ExtraBold")
    img = Image.new("RGB", (w * scale, h * scale), BLACK)
    d = ImageDraw.Draw(img)
    d.text((0, h * scale // 2), "Convx", font=font, fill=(255, 255, 255), anchor="lm")
    return img


def pill(w, h, color, scale, ss=4):
    S = scale * ss
    img = Image.new("RGB", (w * S, h * S), BLACK)
    ImageDraw.Draw(img).rounded_rectangle((0, 0, w * S - 1, h * S - 1), radius=h * S // 2, fill=color)
    return img.resize((w * scale, h * scale), Image.LANCZOS)


def close_glyph(size, scale, color, ring=None, ss=4):
    S = size * scale * ss
    img = Image.new("RGB", (S, S), BLACK)
    d = ImageDraw.Draw(img)
    if ring:
        d.ellipse((0, 0, S - 1, S - 1), fill=ring)
    c, r = S / 2, S * 0.17
    width = max(1, round(1.6 * scale * ss))
    d.line((c - r, c - r, c + r, c + r), fill=color, width=width)
    d.line((c - r, c + r, c + r, c - r), fill=color, width=width)
    return img.resize((size * scale, size * scale), Image.LANCZOS)


def background():
    """Black, with a soft red glow and faint vinyl grooves at the right, away from every control."""
    # One pixel wider and taller than the window: Burn wants the picture larger than the window.
    bw, bh = W + 2, H + 2
    yy, xx = np.mgrid[0:bh, 0:bw].astype(np.float32)
    cx, cy = 492.0, 252.0
    r = np.hypot(xx - cx, yy - cy)
    glow = np.clip(1 - r / 175.0, 0, 1) ** 2.2 * 0.55
    grooves = np.zeros_like(r)
    for radius in range(44, 170, 13):
        grooves += np.exp(-((r - radius) ** 2) / (2 * 1.1 ** 2)) * 0.07 * np.clip(1 - radius / 175.0, 0.25, 1)
    rgb = np.zeros((bh, bw, 3), np.float32)
    for i, c in enumerate(RED):
        rgb[:, :, i] = glow * c
    rgb += (grooves * 255)[:, :, None]
    # A hairline edge, as the window has no frame of its own.
    edge = np.zeros((bh, bw), bool)
    edge[0, :W] = edge[H - 1, :W] = True
    edge[:H, 0] = edge[:H, W - 1] = True
    rgb[edge] = np.maximum(rgb[edge], 46)
    return Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8), "RGB")


def progress(scale):
    """Burn's progress picture: 4 columns (left end, done, to do, right end), stretched to fit."""
    h = 6 * scale
    img = Image.new("RGB", (4, h), BLACK)
    for x, color in enumerate((RED, RED, (0x2C, 0x2C, 0x2E), (0x2C, 0x2C, 0x2E))):
        for y in range(h):
            img.putpixel((x, y), color)
    return img


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    background().save(OUT / "background.png", optimize=True)
    for s in SCALES:
        save(mark(52, s), "mark", s)
        save(wordmark(s), "wordmark", s)
        save(pill(156, 44, RED, s), "primary", s)
        save(pill(156, 44, (0xFF, 0x4D, 0x66), s), "primary-hover", s)
        save(pill(156, 44, (0xD6, 0x1F, 0x3A), s), "primary-pressed", s)
        save(pill(120, 44, (0x1C, 0x1C, 0x1E), s), "secondary", s)
        save(pill(120, 44, (0x2C, 0x2C, 0x2E), s), "secondary-hover", s)
        save(pill(120, 44, (0x3A, 0x3A, 0x3C), s), "secondary-pressed", s)
        save(close_glyph(28, s, (0x8E, 0x8E, 0x93)), "close", s)
        save(close_glyph(28, s, (0xFF, 0xFF, 0xFF), ring=(0x2C, 0x2C, 0x2E)), "close-hover", s)
        save(close_glyph(28, s, (0xFF, 0xFF, 0xFF), ring=(0x3A, 0x3A, 0x3C)), "close-pressed", s)
        save(progress(s), "progress", s)


if __name__ == "__main__":
    main()
