"""Builds the launcher foreground and the release icon from the rendered companion.

The adaptive icon used to be a hand-drawn vector of the old purple face. The character is now real
rendered art, so the icon has to be the same face or the app promises one thing on the home screen
and shows another once it is open.

Two rules the layout obeys, both from Android itself rather than taste:
  * the baby's disc fits inside the 66dp safe zone of the 108dp canvas, because a launcher is free to
    mask everything outside it and a cropped hand looks like a broken icon, not a design choice;
  * the disc keeps the same feathered circular edge the in-app sprites use, so the icon and the
    bubble read as one object instead of two styles that happen to share a character.

Run from the repository root:  python tools/launcher-icon.py
"""
import glob
import sys

from PIL import Image, ImageDraw, ImageFilter

SIDE = 640          # foreground canvas
DISC = int(SIDE * 0.61)   # 66/108 of the canvas is the adaptive-icon safe zone
FEATHER = 10
SOURCE = "vibe_images/companion-idle_*.png"
FOREGROUND = "app/src/main/res/drawable-nodpi/ic_launcher_foreground.png"
RELEASE_ICON = "docs/assets/yourbubble-icon.png"
BACKDROP = [(5, 3, 8), (10, 7, 16), (22, 10, 30), (42, 15, 34)]


def source():
    found = sorted(glob.glob(SOURCE), key=lambda p: p.split("_")[-2])
    if not found:
        sys.exit(f"no render matches {SOURCE}")
    return found[-1]


def circular_alpha(diameter, feather):
    mask = Image.new("L", (diameter, diameter), 0)
    ImageDraw.Draw(mask).ellipse(
        [feather, feather, diameter - feather, diameter - feather], fill=255
    )
    return mask.filter(ImageFilter.GaussianBlur(feather / 2))


def baby(size):
    art = Image.open(source()).convert("RGB").resize((size, size), Image.LANCZOS)
    art.putalpha(circular_alpha(size, FEATHER))
    return art


def foreground():
    layer = Image.new("RGBA", (SIDE, SIDE), (0, 0, 0, 0))
    art = baby(DISC)
    layer.paste(art, ((SIDE - DISC) // 2, (SIDE - DISC) // 2), art)
    ring = ImageDraw.Draw(layer)
    inset = (SIDE - DISC) // 2 - 3
    ring.ellipse(
        [inset, inset, SIDE - inset, SIDE - inset], outline=(139, 92, 246, 120), width=4
    )
    layer.save(FOREGROUND)
    return FOREGROUND


def release_icon():
    """The GitHub Release asset: a flat square, so it needs its own backdrop baked in."""
    size = 512
    base = Image.new("RGB", (size, size), BACKDROP[0])
    draw = ImageDraw.Draw(base)
    for step, colour in enumerate(BACKDROP[1:], start=1):
        draw.rectangle([0, size * step // len(BACKDROP), size, size], fill=colour)
    base = base.filter(ImageFilter.GaussianBlur(size / 8))
    draw = ImageDraw.Draw(base)
    centre = size // 2
    for radius, colour in ((size // 3, (139, 92, 246)), (size // 5, (122, 31, 61))):
        glow = Image.new("L", (size, size), 0)
        ImageDraw.Draw(glow).ellipse(
            [centre - radius, centre - radius, centre + radius, centre + radius], fill=70
        )
        base = Image.composite(
            Image.new("RGB", (size, size), colour), base, glow.filter(ImageFilter.GaussianBlur(radius / 2))
        )
    art = baby(int(size * 0.72))
    base.paste(art, (centre - art.width // 2, centre - art.height // 2), art)
    base.save(RELEASE_ICON)
    return RELEASE_ICON


if __name__ == "__main__":
    print("wrote", foreground())
    print("wrote", release_icon())
