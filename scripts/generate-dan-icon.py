#!/usr/bin/env python3
"""Cute round 旦: 日 is two stacked windows, 一 is a longer bar below — not 口+line."""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path("/workspace")
PEACH = (255, 107, 87, 255)
CREAM = (255, 246, 240, 255)
WINDOW = (255, 184, 172, 255)
GLOSS = (255, 255, 255, 42)


def rr(draw, box, radius, fill=None, outline=None, width=1):
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def make(size: int) -> Image.Image:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    pad = size * 0.02
    d.ellipse((pad, pad, size - pad, size - pad), fill=PEACH)
    hi = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    hd = ImageDraw.Draw(hi)
    hd.ellipse((size * 0.16, size * 0.06, size * 0.84, size * 0.38), fill=GLOSS)
    img = Image.alpha_composite(img, hi)
    d = ImageDraw.Draw(img)

    # 日 — two stacked windows, thick cream frame, peach-pink cells so it cannot read as 口
    stroke = max(5, int(size * 0.078))
    left, right = size * 0.30, size * 0.70
    top, bot = size * 0.12, size * 0.58
    mid = (top + bot) / 2
    radius = max(3, int(size * 0.045))
    rr(d, (left, top, right, bot), radius, outline=CREAM, width=stroke)
    # inner cells
    inset = stroke * 0.72
    cell_gap = stroke * 0.55
    rr(
        d,
        (left + inset, top + inset, right - inset, mid - cell_gap),
        max(2, radius - 2),
        fill=WINDOW,
    )
    rr(
        d,
        (left + inset, mid + cell_gap, right - inset, bot - inset),
        max(2, radius - 2),
        fill=WINDOW,
    )
    # middle bar of 日
    bar_h = stroke
    d.rectangle((left, mid - bar_h / 2, right, mid + bar_h / 2), fill=CREAM)

    # 一 — longer, round, clearly detached from 日
    y1 = size * 0.74
    h1 = max(6, int(size * 0.085))
    x0, x1 = size * 0.18, size * 0.82
    rr(d, (x0, y1 - h1 / 2, x1, y1 + h1 / 2), h1 / 2, fill=CREAM)
    return img


def save_mipmap(img512: Image.Image, density: str, px: int) -> None:
    dest = ROOT / f"android/app/src/main/res/mipmap-{density}"
    dest.mkdir(parents=True, exist_ok=True)
    resized = img512.resize((px, px), Image.Resampling.LANCZOS)
    resized.save(dest / "ic_launcher.png")
    resized.save(dest / "ic_launcher_round.png")


def main() -> None:
    big = make(1024)
    img512 = big.resize((512, 512), Image.Resampling.LANCZOS)
    (ROOT / "artifacts").mkdir(exist_ok=True)
    (ROOT / "public").mkdir(exist_ok=True)
    img512.save(ROOT / "artifacts/danzhi-icon-512.png")
    img512.save(ROOT / "public/icon-512.png")
    big.resize((192, 192), Image.Resampling.LANCZOS).save(ROOT / "public/icon-192.png")
    for density, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        save_mipmap(img512, density, px)
    print("icons written")


if __name__ == "__main__":
    main()
