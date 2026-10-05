"""Tiny helper for the emulator test: find on-screen text through uiautomator and tap it."""
import re
import subprocess
import sys
import time


def adb(*args):
    return subprocess.run(["adb", *args], capture_output=True, text=True).stdout


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    for m in re.finditer(r"<node [^>]*>", xml):
        node = m.group(0)
        text = (re.search(r' text="([^"]*)"', node) or [None, ""])[1]
        desc = (re.search(r' content-desc="([^"]*)"', node) or [None, ""])[1]
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if b:
            x1, y1, x2, y2 = map(int, b.groups())
            yield text, desc, ((x1 + x2) // 2, (y1 + y2) // 2)


def tap(target, tries=8, swipe=True):
    for i in range(tries):
        for text, desc, (x, y) in nodes():
            if target in text or target in desc:
                print(f"tap {target!r} at {x},{y}")
                adb("shell", "input", "tap", str(x), str(y))
                return True
        if swipe and i >= 1:
            adb("shell", "input", "swipe", "540", "1700", "540", "900", "400")
        time.sleep(2)
    print(f"NOT FOUND: {target!r}")
    return False


if __name__ == "__main__":
    ok = tap(sys.argv[1], swipe=len(sys.argv) < 3)
    sys.exit(0 if ok else 1)
