"""
Builds the floating companion's art from the rendered source images in vibe_images/.

Why a script instead of dropping files into res/: the sources are 1024 px renders on a flat dark
field, and a bubble is 96 dp. Shipping them as they come out of the generator would add ~9 MB to the
APK and put a hard-edged dark square on top of the user's white documents app. This trims each frame
to one size and multiplies a feathered circular alpha into it, so what ships is a round badge whose
rim already fades out and whose total weight is under a megabyte.

Run from the repository root:  python tools/companion-sprites.py
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
SOURCE_DIR = ROOT / "vibe_images"
OUT_DIR = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"

SIDE = 384          # the bubble is 96 dp; 384 px covers a 3.5x density screen with headroom
FEATHER = 14        # px of soft fade at the rim, so the disc never reads as a pasted circle
POSES = ["idle", "listening", "thinking", "happy", "worried", "sleepy", "surprised"]


def source_for(pose: str) -> Path:
    candidates = sorted(SOURCE_DIR.glob(f"companion-{pose}_*.png"))
    if not candidates:
        raise SystemExit(f"no source render for pose '{pose}' in {SOURCE_DIR}")
    return candidates[-1]


def circular_alpha(size: int, feather: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    inset = feather
    draw.ellipse([inset, inset, size - inset, size - inset], fill=255)
    return mask.filter(ImageFilter.GaussianBlur(radius=feather / 2))


def build(pose: str) -> None:
    src = source_for(pose)
    art = Image.open(src).convert("RGB").resize((SIDE, SIDE), Image.LANCZOS)
    art.putalpha(circular_alpha(SIDE, FEATHER))
    out = OUT_DIR / f"companion_{pose}.png"
    art.save(out, optimize=True)
    print(f"{out.relative_to(ROOT)}  {out.stat().st_size // 1024} KB  <- {src.name}")


if __name__ == "__main__":
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for pose in POSES:
        build(pose)
