#!/usr/bin/env python3
"""Builds the store listing images (F-Droid / IzzyOnDroid) in fastlane/metadata/android/<locale>/images/.

- phoneScreenshots/: 1080x1920 (9:16) frames with a caption, made from the raw device
  screenshots in tools/store/screenshots/<locale>/ (raw captures are 1080x2400).
- featureGraphic.png: 1024x500 banner.

Raw screenshots come from a debug build with demo data (see README, "Demo data").

Usage: python tools/store/make_store_images.py [--font path/to/Bold.ttf]
Requires Pillow.
"""
from __future__ import annotations

import argparse
import shutil
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]
RAW = ROOT / "tools" / "store" / "screenshots"
OUT = ROOT / "fastlane" / "metadata" / "android"
ICON = OUT / "en-US" / "images" / "icon.png"

BRAND = (0x1A, 0x7F, 0x37)
BRAND_DARK = (0x0E, 0x52, 0x22)
BACKGROUND = (0xF6, 0xFB, 0xF1)
ON_BACKGROUND = (0x18, 0x1D, 0x17)
ON_SURFACE_VARIANT = (0x40, 0x4A, 0x3E)

# (raw file, caption) in listing order. 6_strength is left out to keep the listing at 8 screenshots.
SCREENS = {
    "en-US": [
        ("1_today.png", "Every dose of the day, in order"),
        ("2_notification.png", "Reminders that go off on time"),
        ("3_meds.png", "All your medicines in one place"),
        ("4_detail.png", "Track stock and get refill reminders"),
        ("5_progress.png", "See your history and fix any day"),
        ("7_appearance.png", "Recognize each pill at a glance"),
        ("8_times.png", "Doses spread evenly through the day"),
        ("9_settings.png", "Backups and app lock, no account"),
    ],
    "pt-BR": [
        ("1_today.png", "Todas as doses do dia, em ordem"),
        ("2_notification.png", "Lembretes que tocam na hora certa"),
        ("3_meds.png", "Todos os seus remédios num só lugar"),
        ("4_detail.png", "Controle o estoque e saiba quando repor"),
        ("5_progress.png", "Veja o histórico e corrija qualquer dia"),
        ("7_appearance.png", "Reconheça cada comprimido de relance"),
        ("8_times.png", "Doses distribuídas ao longo do dia"),
        ("9_settings.png", "Backup e bloqueio, sem conta nem login"),
    ],
}

TAGLINE = {
    "en-US": "Medication reminders that go off on time",
    "pt-BR": "Lembretes de remédios que tocam na hora certa",
}

FONT_CANDIDATES = [
    "C:/Windows/Fonts/segoeuib.ttf",
    "/usr/share/fonts/truetype/roboto/unhinted/RobotoTTF/Roboto-Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
]


def find_font(explicit: str | None) -> str:
    for candidate in ([explicit] if explicit else []) + FONT_CANDIDATES:
        if candidate and Path(candidate).is_file():
            return candidate
    raise SystemExit("No bold TrueType font found; pass --font path/to/Bold.ttf")


def vertical_gradient(size: tuple[int, int], top: tuple, bottom: tuple) -> Image.Image:
    w, h = size
    column = Image.new("RGB", (1, h))
    for y in range(h):
        t = y / (h - 1)
        column.putpixel((0, y), tuple(round(a + (b - a) * t) for a, b in zip(top, bottom)))
    return column.resize((w, h))


def wrap(draw: ImageDraw.ImageDraw, text: str, font, max_width: int) -> list[str]:
    """One line if it fits, otherwise the two-line split with the most even widths."""
    if draw.textlength(text, font=font) <= max_width:
        return [text]
    words = text.split()
    splits = [(" ".join(words[:i]), " ".join(words[i:])) for i in range(1, len(words))]
    return list(min(splits, key=lambda s: max(draw.textlength(line, font=font) for line in s)))


def rounded(image: Image.Image, radius: int) -> Image.Image:
    mask = Image.new("L", image.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, *image.size), radius, fill=255)
    out = image.convert("RGBA")
    out.putalpha(mask)
    return out


def paste_with_shadow(canvas: Image.Image, image: Image.Image, xy: tuple[int, int], blur: int, opacity: int):
    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    alpha = image.getchannel("A").point(lambda a: a * opacity // 255)
    shadow.paste(Image.new("RGBA", image.size, (0, 0, 0, 255)), (xy[0], xy[1] + blur // 2), alpha)
    canvas.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(blur)))
    canvas.alpha_composite(image, xy)


def screenshot(raw: Path, caption: str, font_path: str) -> Image.Image:
    w, h = 1080, 1920
    canvas = vertical_gradient((w, h), BRAND, BRAND_DARK).convert("RGBA")
    draw = ImageDraw.Draw(canvas)

    font = ImageFont.truetype(font_path, 66)
    lines = wrap(draw, caption, font, w - 2 * 90)
    line_height = 84
    top = 120 + (2 - len(lines)) * line_height // 2
    for i, line in enumerate(lines):
        draw.text((w / 2, top + i * line_height), line, font=font, fill="white", anchor="mt")

    shot_h = 1500
    shot = Image.open(raw).convert("RGB")
    shot = shot.resize((round(shot.width * shot_h / shot.height), shot_h), Image.LANCZOS)
    shot = rounded(shot, 44)
    paste_with_shadow(canvas, shot, ((w - shot.width) // 2, h - shot_h - 70), blur=28, opacity=110)
    return canvas.convert("RGB")


def feature_graphic(locale: str, font_path: str) -> Image.Image:
    w, h = 1024, 500
    canvas = Image.new("RGBA", (w, h), BACKGROUND + (255,))
    draw = ImageDraw.Draw(canvas)
    # Soft brand-colored disc behind the icon.
    draw.ellipse((-140, -120, 520, 620), fill=(0xE4, 0xF2, 0xDA, 255))

    icon = Image.open(ICON).convert("RGBA").resize((300, 300), Image.LANCZOS)
    paste_with_shadow(canvas, icon, (90, (h - 300) // 2), blur=22, opacity=90)

    x = 450
    title = ImageFont.truetype(font_path, 118)
    draw.text((x, 245), "Medwi", font=title, fill=BRAND, anchor="ls")
    tagline_font = ImageFont.truetype(font_path, 36)
    for i, line in enumerate(wrap(draw, TAGLINE[locale], tagline_font, w - x - 50)):
        draw.text((x, 305 + i * 48), line, font=tagline_font, fill=ON_SURFACE_VARIANT, anchor="ls")
    return canvas.convert("RGB")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--font", help="bold .ttf used for captions and the feature graphic")
    font_path = find_font(parser.parse_args().font)

    for locale, screens in SCREENS.items():
        images = OUT / locale / "images"
        target = images / "phoneScreenshots"
        shutil.rmtree(target, ignore_errors=True)
        target.mkdir(parents=True)
        for index, (name, caption) in enumerate(screens, start=1):
            stem = name.split("_", 1)[1]
            screenshot(RAW / locale / name, caption, font_path).save(target / f"{index}_{stem}", optimize=True)
        feature_graphic(locale, font_path).save(images / "featureGraphic.png", optimize=True)
        print(f"{locale}: {len(screens)} screenshots + featureGraphic.png")


if __name__ == "__main__":
    main()
