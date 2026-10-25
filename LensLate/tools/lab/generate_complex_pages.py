#!/usr/bin/env python3
"""Generate hard LensLate test pages: extreme ink, textures and typefaces.

The pages are deterministic. They are deliberately difficult: text colour
changes smoothly across glyphs, backgrounds are noisy gradients with bokeh,
some small print sits on low-contrast stains, one page is defocused, one is
washed out by a highlight, one is almost black-on-black, one is burned through
by a 99% glare, and one mixes five typefaces on a single poster. These are the
cases that expose colour-transfer, font-matching and background-restoration
mistakes.

Usage:
    python tools/lab/generate_complex_pages.py
    python tools/lab/generate_complex_pages.py --out tools/lab/pages

Fonts are taken from the Windows font directory by default; override with
LENSLATE_FONT_DIR. The generated JPEGs are checked into the repository so the
fixtures stay stable even on machines without these fonts.
"""

from __future__ import annotations

import argparse
import math
import os
import random
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

FONT_DIR = Path(os.environ.get("LENSLATE_FONT_DIR", Path(os.environ.get("WINDIR", "C:/Windows")) / "Fonts"))
ARIAL = FONT_DIR / "arial.ttf"
ARIAL_BOLD = FONT_DIR / "arialbd.ttf"
ARIAL_ITALIC = FONT_DIR / "ariali.ttf"
ARIAL_BLACK = FONT_DIR / "ariblk.ttf"
GEORGIA = FONT_DIR / "georgia.ttf"
GEORGIA_BOLD = FONT_DIR / "georgiab.ttf"
GEORGIA_ITALIC = FONT_DIR / "georgiai.ttf"
CONSOLAS_BOLD = FONT_DIR / "consolab.ttf"
IMPACT = FONT_DIR / "impact.ttf"
SEGOE_LIGHT = FONT_DIR / "segoeuil.ttf"
YU_GOTHIC = FONT_DIR / "YuGothR.ttc"
YU_GOTHIC_BOLD = FONT_DIR / "YuGothB.ttc"

Rgb = tuple[int, int, int]
Stops = list[tuple[float, Rgb]]


def font(path: Path, size: int) -> ImageFont.FreeTypeFont:
    if not path.exists():
        raise SystemExit(f"Font not found: {path}. Set LENSLATE_FONT_DIR or edit the constants.")
    return ImageFont.truetype(str(path), size)


def sample_stops(stops: Stops, t: float) -> Rgb:
    if t <= stops[0][0]:
        return stops[0][1]
    for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
        if t <= p1:
            f = (t - p0) / max(1e-6, p1 - p0)
            return tuple(round(c0[i] + (c1[i] - c0[i]) * f) for i in range(3))  # type: ignore[return-value]
    return stops[-1][1]


def gradient_image(size: tuple[int, int], stops: Stops, axis: str = "x") -> Image.Image:
    w, h = size
    if axis == "y":
        strip = Image.new("RGB", (1, h))
        pixels = strip.load()
        for y in range(h):
            pixels[0, y] = sample_stops(stops, y / max(1, h - 1))
    else:
        strip = Image.new("RGB", (w, 1))
        pixels = strip.load()
        for x in range(w):
            pixels[x, 0] = sample_stops(stops, x / max(1, w - 1))
    return strip.resize(size, Image.Resampling.BILINEAR)


def background(size: tuple[int, int], stops: Stops, seed: int, bokeh: bool, streaks: bool) -> Image.Image:
    w, h = size
    base = gradient_image(size, stops, axis="x")

    # Low-frequency mottling plus fine grain: a photo-like surface rather than
    # a clean digital gradient.
    small = (max(2, w // 28), max(2, h // 28))
    mottle = Image.effect_noise(small, 95).resize(size, Image.Resampling.BICUBIC)
    mottle = mottle.filter(ImageFilter.GaussianBlur(w * 0.006))
    mottle_rgb = Image.merge("RGB", (mottle, mottle, mottle))
    base = Image.blend(base, ImageChops.overlay(base, mottle_rgb), 0.35)

    grain = Image.effect_noise(size, 30).convert("L")
    grain_rgb = Image.merge("RGB", (grain, grain, grain))
    base = Image.blend(base, grain_rgb, 0.045)

    if bokeh:
        glow = Image.new("RGBA", size, (0, 0, 0, 0))
        draw = ImageDraw.Draw(glow)
        rnd = random.Random(seed + 1)
        palette = [
            (255, 176, 92),
            (86, 220, 207),
            (236, 86, 168),
            (132, 126, 255),
            (255, 226, 150),
        ]
        for _ in range(16):
            x = rnd.randint(-w // 8, w + w // 8)
            y = rnd.randint(-h // 8, h + h // 8)
            radius = rnd.randint(w // 24, w // 7)
            color = rnd.choice(palette)
            draw.ellipse(
                (x - radius, y - radius, x + radius, y + radius),
                fill=color + (rnd.randint(18, 46),),
            )
        glow = glow.filter(ImageFilter.GaussianBlur(w * 0.022))
        base = Image.alpha_composite(base.convert("RGBA"), glow).convert("RGB")

    if streaks:
        streak = Image.new("RGBA", size, (0, 0, 0, 0))
        draw = ImageDraw.Draw(streak)
        rnd = random.Random(seed + 2)
        for _ in range(5):
            x = rnd.randint(-w // 3, w)
            width = rnd.randint(w // 40, w // 12)
            draw.polygon(
                [(x, 0), (x + width, 0), (x + width + h * 0.6, h), (x + h * 0.6, h)],
                fill=(255, 244, 220, rnd.randint(10, 26)),
            )
        streak = streak.filter(ImageFilter.GaussianBlur(w * 0.02))
        base = Image.alpha_composite(base.convert("RGBA"), streak).convert("RGB")

    # Vignette keeps the eye (and text) around the centre.
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).ellipse((-w * 0.18, -h * 0.18, w * 1.18, h * 1.18), fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(w * 0.09))
    base = Image.composite(base, Image.new("RGB", size, (0, 0, 0)), mask)
    return base


def text_mask(
    size: tuple[int, int],
    xy: tuple[int, int],
    text: str,
    typeface: ImageFont.FreeTypeFont,
    spacing: int = 0,
    tracking: int = 0,
) -> Image.Image:
    mask = Image.new("L", size, 0)
    if tracking:
        x, y = xy
        for line in text.split("\n"):
            cursor = x
            for character in line:
                ImageDraw.Draw(mask).text((cursor, y), character, font=typeface, fill=255)
                cursor += round(typeface.getlength(character)) + tracking
            y += typeface.size + spacing
        return mask
    ImageDraw.Draw(mask).multiline_text(
        xy,
        text,
        font=typeface,
        fill=255,
        spacing=spacing,
        align="left",
    )
    return mask


def draw_text(
    base: Image.Image,
    mask: Image.Image,
    stops: Stops,
    axis: str = "x",
    shadow: tuple[int, int, int, float] | None = (6, 9, 12, 0.62),
    glow: tuple[Rgb, float, int] | None = None,
) -> None:
    size = base.size
    if glow:
        color, strength, blur = glow
        glow_mask = mask.filter(ImageFilter.GaussianBlur(blur)).point(
            lambda value: min(255, round(value * strength))
        )
        layer = Image.new("RGB", size, (0, 0, 0))
        layer.paste(Image.new("RGB", size, color), (0, 0), glow_mask)
        base.paste(ImageChops.add(base, layer))
    if shadow:
        dx, dy, blur, opacity = shadow
        blurred = mask.filter(ImageFilter.GaussianBlur(blur)).point(
            lambda value: min(255, round(value * opacity))
        )
        shifted = Image.new("L", size, 0)
        shifted.paste(blurred, (dx, dy))
        base.paste(Image.new("RGB", size, (8, 6, 10)), (0, 0), shifted)
    fill = gradient_image(size, stops, axis=axis)
    base.paste(fill, (0, 0), mask)


def wrap(text: str, typeface: ImageFont.FreeTypeFont, width: int) -> list[str]:
    lines: list[str] = []
    for paragraph in text.split("\n"):
        current = ""
        for word in paragraph.split():
            candidate = f"{current} {word}".strip()
            if not current or typeface.getlength(candidate) <= width:
                current = candidate
            else:
                lines.append(current)
                current = word
        lines.append(current)
    return lines


def translucent_panel(
    base: Image.Image,
    box: tuple[int, int, int, int],
    color: tuple[int, int, int],
    opacity: int,
    radius: int = 28,
    blur: int = 0,
) -> None:
    layer = Image.new("RGBA", base.size, (0, 0, 0, 0))
    ImageDraw.Draw(layer).rounded_rectangle(box, radius=radius, fill=color + (opacity,))
    if blur:
        layer = layer.filter(ImageFilter.GaussianBlur(blur))
    base.paste(Image.alpha_composite(base.convert("RGBA"), layer).convert("RGB"))


def glare_mask(
    size: tuple[int, int],
    center: tuple[int, int],
    radii: tuple[int, int],
    strength: float,
    blur: int,
) -> Image.Image:
    mask = Image.new("L", size, 0)
    cx, cy = center
    rx, ry = radii
    ImageDraw.Draw(mask).ellipse((cx - rx, cy - ry, cx + rx, cy + ry), fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(blur))
    return mask.point(lambda value: min(255, round(value * strength)))


def poster_ru(out: Path, seed: int) -> Image.Image:
    size = (1200, 1600)
    page = background(
        size,
        [
            (0.0, (16, 30, 42)),
            (0.42, (58, 22, 52)),
            (0.78, (128, 58, 36)),
            (1.0, (24, 48, 56)),
        ],
        seed,
        bokeh=True,
        streaks=True,
    )

    draw_text(
        page,
        text_mask(size, (86, 132), "СЕЗОН 2026", font(ARIAL_BOLD, 38), tracking=9),
        [(0.0, (255, 226, 150)), (1.0, (120, 240, 224))],
        shadow=(3, 4, 8, 0.5),
    )

    headline = text_mask(
        size,
        (72, 240),
        "ЛЕТНИЙ\nФЕСТИВАЛЬ",
        font(ARIAL_BOLD, 148),
        spacing=6,
    )
    draw_text(
        page,
        headline,
        [
            (0.0, (255, 205, 86)),
            (0.38, (255, 106, 92)),
            (0.7, (238, 70, 156)),
            (1.0, (126, 82, 255)),
        ],
        shadow=(8, 11, 18, 0.7),
        glow=((255, 110, 120), 0.28, 26),
    )

    draw_text(
        page,
        text_mask(size, (84, 640), "музыка · еда · искусство", font(ARIAL, 52)),
        [(0.0, (196, 255, 238)), (0.55, (255, 255, 245)), (1.0, (142, 224, 255))],
        shadow=(4, 5, 9, 0.55),
    )
    draw_text(
        page,
        text_mask(size, (84, 724), "12—14 ИЮЛЯ  •  ПАРК ГОРЬКОГО", font(ARIAL_BOLD, 34)),
        [(0.0, (255, 224, 150)), (1.0, (255, 126, 100))],
        shadow=(3, 4, 7, 0.55),
    )

    translucent_panel(page, (74, 840, 1126, 1236), (10, 16, 24), 150, radius=34, blur=2)
    body_font = font(ARIAL, 31)
    body_text = (
        "Три дня живой музыки, уличной еды и современного искусства.\n"
        "Сцена у воды, ночной маркет и кинопоказ под открытым небом.\n"
        "Вход по билетам, дети до семи лет — бесплатно.\n"
        "Программа обновляется каждый день на сайте фестиваля."
    )
    body_lines = wrap(body_text, body_font, 980)
    body = "\n".join(body_lines)
    draw_text(
        page,
        text_mask(size, (116, 884), body, body_font, spacing=18),
        [
            (0.0, (246, 238, 224)),
            (0.45, (220, 232, 246)),
            (0.75, (238, 220, 236)),
            (1.0, (206, 214, 255)),
        ],
        shadow=(2, 3, 5, 0.4),
    )

    draw_text(
        page,
        text_mask(size, (84, 1300), "LIVE MUSIC  •  20:00", font(ARIAL_BOLD, 30)),
        [(0.0, (174, 255, 238)), (1.0, (255, 255, 255))],
        shadow=(3, 4, 8, 0.6),
    )

    draw_text(
        page,
        text_mask(size, (84, 1460), "БИЛЕТЫ НА САЙТЕ FESTIVAL.RU", font(ARIAL, 26), tracking=3),
        [(0.0, (176, 190, 186)), (1.0, (140, 170, 168))],
        shadow=None,
    )

    badge = Image.new("RGBA", size, (0, 0, 0, 0))
    badge_draw = ImageDraw.Draw(badge)
    badge_draw.ellipse((936, 1286, 1104, 1454), outline=(255, 214, 140, 190), width=4)
    badge_draw.ellipse((958, 1308, 1082, 1432), outline=(120, 224, 214, 140), width=2)
    page.paste(Image.alpha_composite(page.convert("RGBA"), badge).convert("RGB"))
    draw_text(
        page,
        text_mask(size, (984, 1348), "2026", font(ARIAL_BOLD, 40)),
        [(0.0, (255, 232, 168)), (1.0, (140, 238, 226))],
        shadow=(2, 3, 6, 0.5),
    )
    return page


def document_en(out: Path, seed: int) -> Image.Image:
    size = (1200, 1600)
    page = background(
        size,
        [
            (0.0, (232, 226, 210)),
            (0.5, (246, 241, 226)),
            (1.0, (208, 214, 210)),
        ],
        seed,
        bokeh=False,
        streaks=True,
    )
    # Paper fibres and a couple of soft stains.
    stains = Image.new("RGBA", size, (0, 0, 0, 0))
    stain_draw = ImageDraw.Draw(stains)
    for x, y, radius, color, alpha in (
        (940, 260, 160, (196, 168, 112), 30),
        (240, 1180, 210, (138, 158, 152), 24),
        (1060, 1320, 130, (176, 128, 96), 22),
    ):
        stain_draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=color + (alpha,))
    stains = stains.filter(ImageFilter.GaussianBlur(48))
    page = Image.alpha_composite(page.convert("RGBA"), stains).convert("RGB")
    # Soft paper edge shadow.
    edge = Image.new("L", size, 0)
    ImageDraw.Draw(edge).rectangle((28, 24, 1172, 1576), fill=255)
    edge = edge.filter(ImageFilter.GaussianBlur(28))
    page.paste(Image.new("RGB", size, (90, 82, 68)), (0, 0), ImageChops.invert(edge).point(lambda v: v // 3))

    draw_text(
        page,
        text_mask(size, (108, 128), "Northwind Field Notes", font(GEORGIA_BOLD, 66)),
        [(0.0, (20, 58, 42)), (0.55, (10, 86, 94)), (1.0, (22, 42, 92))],
        shadow=None,
    )
    draw_text(
        page,
        text_mask(size, (112, 214), "Seasonal observations from the coastal station", font(ARIAL, 27)),
        [(0.0, (88, 92, 84)), (1.0, (78, 98, 102))],
        shadow=None,
    )

    prefix_font = font(ARIAL_BOLD, 32)
    prefix = "IMPORTANT:"
    prefix_x, prefix_y = 112, 292
    draw_text(
        page,
        text_mask(size, (prefix_x, prefix_y), prefix, prefix_font),
        [(0.0, (198, 28, 42)), (0.5, (226, 46, 74)), (1.0, (152, 20, 58))],
        shadow=None,
    )
    rest_x = prefix_x + round(prefix_font.getlength(prefix)) + 12
    draw_text(
        page,
        text_mask(size, (rest_x, prefix_y), "read before deployment", font(ARIAL, 32)),
        [(0.0, (46, 48, 52)), (1.0, (72, 64, 58))],
        shadow=None,
    )

    body_font = font(ARIAL, 28)
    body_text = (
        "The station logged a steady shift in wind direction after the equinox. "
        "Surface temperatures remained within the expected range, while the "
        "southern channel cleared earlier than in previous years.\n\n"
        "Two independent readings were taken at each marker. Where they "
        "disagreed, the crew repeated the measurement at first light and kept "
        "the median value. The raw notes are archived in the field cabinet."
    )
    lines = wrap(body_text, body_font, 930)
    draw_text(
        page,
        text_mask(size, (112, 356), "\n".join(lines), body_font, spacing=15),
        [
            (0.0, (34, 44, 58)),
            (0.45, (42, 54, 76)),
            (0.8, (58, 46, 68)),
            (1.0, (66, 42, 54)),
        ],
        shadow=None,
    )

    table_font = font(ARIAL, 26)
    table_bold = font(ARIAL_BOLD, 26)
    table_top = 880
    draw_text(
        page,
        text_mask(size, (116, table_top), "SITE", table_bold),
        [(0.0, (32, 48, 52)), (1.0, (48, 52, 62))],
        shadow=None,
    )
    draw_text(
        page,
        text_mask(size, (470, table_top), "READING", table_bold),
        [(0.0, (32, 48, 52)), (1.0, (48, 52, 62))],
        shadow=None,
    )
    draw_text(
        page,
        text_mask(size, (860, table_top), "STATUS", table_bold),
        [(0.0, (32, 48, 52)), (1.0, (48, 52, 62))],
        shadow=None,
    )
    rows = (
        ("N-01", "14.8 °C", "stable"),
        ("N-02", "15.1 °C", "rising"),
        ("N-03", "14.6 °C", "stable"),
        ("S-07", "16.2 °C", "cleared"),
    )
    grid = ImageDraw.Draw(page)
    grid.line((112, table_top + 42, 1088, table_top + 42), fill=(118, 112, 100), width=2)
    for index, (site, reading, status) in enumerate(rows):
        y = table_top + 64 + index * 52
        for x, value, typeface in (
            (116, site, table_font),
            (470, reading, table_font),
            (860, status, table_font),
        ):
            draw_text(
                page,
                text_mask(size, (x, y), value, typeface),
                [(0.0, (54, 58, 62)), (1.0, (62, 58, 70))],
                shadow=None,
            )
        grid.line((112, y + 38, 1088, y + 38), fill=(202, 196, 182), width=1)

    draw_text(
        page,
        text_mask(size, (112, 1268), "Модель ЬХ—4200  ·  Серия А  ·  2026", font(ARIAL, 21)),
        [(0.0, (118, 112, 102)), (1.0, (136, 128, 116))],
        shadow=None,
    )
    draw_text(
        page,
        text_mask(size, (1010, 1560), "17", font(ARIAL, 24)),
        [(0.0, (120, 116, 108)), (1.0, (140, 132, 120))],
        shadow=None,
    )
    return page


def sign_ja(out: Path, seed: int) -> Image.Image:
    size = (1600, 1200)
    page = background(
        size,
        [
            (0.0, (8, 10, 24)),
            (0.45, (22, 12, 38)),
            (0.75, (42, 16, 32)),
            (1.0, (8, 18, 30)),
        ],
        seed,
        bokeh=True,
        streaks=True,
    )
    # Dark shop-sign panel, softly lit.
    translucent_panel(page, (92, 148, 1268, 900), (6, 8, 16), 168, radius=42, blur=3)
    neon_edge = Image.new("RGBA", size, (0, 0, 0, 0))
    ImageDraw.Draw(neon_edge).rounded_rectangle(
        (92, 148, 1268, 900), radius=42, outline=(255, 86, 140, 115), width=3
    )
    neon_edge = neon_edge.filter(ImageFilter.GaussianBlur(7))
    page = Image.alpha_composite(page.convert("RGBA"), neon_edge).convert("RGB")

    draw_text(
        page,
        text_mask(size, (156, 208), "なつのさいでん", font(YU_GOTHIC, 44), tracking=8),
        [(0.0, (154, 246, 255)), (1.0, (255, 196, 236))],
        shadow=(2, 3, 6, 0.5),
    )
    headline = text_mask(size, (128, 296), "夏の祭典", font(YU_GOTHIC_BOLD, 196), tracking=18)
    draw_text(
        page,
        headline,
        [
            (0.0, (255, 60, 110)),
            (0.32, (255, 112, 96)),
            (0.62, (255, 186, 108)),
            (0.85, (255, 228, 168)),
            (1.0, (255, 248, 226)),
        ],
        axis="x",
        shadow=(8, 12, 20, 0.72),
        glow=((255, 76, 132), 0.42, 30),
    )
    draw_text(
        page,
        text_mask(size, (144, 596), "SUMMER FESTIVAL 2026", font(ARIAL_BOLD, 48), tracking=5),
        [(0.0, (132, 238, 255)), (0.5, (214, 244, 255)), (1.0, (255, 216, 158))],
        shadow=(3, 5, 9, 0.55),
    )
    draw_text(
        page,
        text_mask(size, (144, 700), "東京・代々木公園  ·  8.12—8.14", font(YU_GOTHIC, 34)),
        [(0.0, (196, 204, 220)), (1.0, (238, 216, 206))],
        shadow=(2, 3, 6, 0.55),
    )
    draw_text(
        page,
        text_mask(size, (144, 790), "入場無料  ·  OPEN 18:00", font(YU_GOTHIC_BOLD, 30)),
        [(0.0, (156, 250, 232)), (1.0, (255, 246, 206))],
        shadow=(2, 3, 6, 0.5),
    )

    # Vertical kana column on the right: white-to-warm drift on a dark field.
    vertical = ["入", "場", "無", "料"]
    vertical_font = font(YU_GOTHIC_BOLD, 86)
    y = 240
    for index, character in enumerate(vertical):
        draw_text(
            page,
            text_mask(size, (1380, y), character, vertical_font),
            [
                (0.0, (255, 248, 230)),
                (0.6, (255, 214, 146)),
                (1.0, (255, 150, 130)),
            ],
            axis="y",
            shadow=(4, 6, 10, 0.62),
        )
        y += 118 + (6 if index == 1 else 0)

    # Rotated badge: angle transfer and brightness on a busier patch.
    badge = Image.new("RGBA", (430, 180), (0, 0, 0, 0))
    badge_mask = Image.new("L", badge.size, 0)
    ImageDraw.Draw(badge_mask).text(
        (24, 34),
        "LIVE",
        font=font(ARIAL_BOLD, 92),
        fill=255,
    )
    badge_fill = gradient_image(
        badge.size,
        [(0.0, (120, 246, 255)), (0.55, (255, 255, 255)), (1.0, (255, 176, 112))],
    )
    badge.paste(badge_fill, (0, 0), badge_mask)
    badge = badge.rotate(-11, expand=True, resample=Image.Resampling.BICUBIC)
    page.paste(badge, (1240, 940), badge)

    # A logo-like mark with no real words; it must not produce phantom text.
    mark = Image.new("RGBA", size, (0, 0, 0, 0))
    mark_draw = ImageDraw.Draw(mark)
    mark_draw.ellipse((1270, 650, 1450, 830), outline=(255, 214, 150, 160), width=4)
    mark_draw.arc((1290, 670, 1430, 810), 200, 340, fill=(120, 240, 226, 190), width=8)
    mark_draw.arc((1300, 680, 1420, 800), 20, 160, fill=(255, 120, 160, 150), width=5)
    page.paste(Image.alpha_composite(page.convert("RGBA"), mark).convert("RGB"))
    return page


def blur_ru(out: Path, seed: int) -> Image.Image:
    size = (1200, 1600)
    page = background(
        size,
        [
            (0.0, (18, 24, 34)),
            (0.45, (44, 28, 46)),
            (0.8, (86, 44, 32)),
            (1.0, (20, 30, 40)),
        ],
        seed,
        bokeh=True,
        streaks=True,
    )
    draw_text(
        page,
        text_mask(size, (78, 152), "НОЧНОЙ\nМАРКЕТ", font(ARIAL_BOLD, 132), spacing=4),
        [
            (0.0, (255, 214, 132)),
            (0.45, (255, 116, 96)),
            (1.0, (176, 132, 255)),
        ],
        shadow=(6, 8, 16, 0.62),
    )
    draw_text(
        page,
        text_mask(size, (84, 486), "еда · музыка · ремесло", font(ARIAL, 46)),
        [(0.0, (160, 248, 226)), (0.55, (238, 244, 255)), (1.0, (255, 198, 168))],
        shadow=(3, 4, 8, 0.5),
    )
    body_font = font(ARIAL, 30)
    body_text = (
        "Снимок сделан на бегу: резкость потеряна, штрихи расплылись, "
        "а подсветка вытянула цвет из середины букв.\n"
        "Строка ниже проверяет, удержится ли градиент на замыленных глифах "
        "и не перепутается ли он с тёплым фоном.\n"
        "Второй абзац нужен, чтобы у символов было меньше полностью "
        "покрытых пикселей.\n"
        "Проверка мелкого кегля: билеты, время и адрес площадки."
    )
    draw_text(
        page,
        text_mask(size, (84, 590), "\n".join(wrap(body_text, body_font, 980)), body_font, spacing=16),
        [
            (0.0, (244, 236, 220)),
            (0.4, (212, 228, 248)),
            (0.75, (236, 214, 236)),
            (1.0, (198, 208, 255)),
        ],
        shadow=(2, 3, 6, 0.42),
    )
    draw_text(
        page,
        text_mask(size, (84, 982), "12—14 ИЮЛЯ · ВХОД СВОБОДНЫЙ", font(ARIAL_BOLD, 27), tracking=2),
        [(0.0, (176, 246, 226)), (1.0, (255, 226, 178))],
        shadow=None,
    )
    # Defocus plus sensor grain: text and surface lose sharpness together,
    # exactly what a handheld photo in low light looks like.
    page = page.filter(ImageFilter.GaussianBlur(2.6))
    grain = Image.effect_noise(size, 26).convert("L")
    page = Image.blend(page, Image.merge("RGB", (grain, grain, grain)), 0.035)
    return page


def overexposure_ru(out: Path, seed: int) -> Image.Image:
    size = (1200, 1600)
    page = background(
        size,
        [
            (0.0, (58, 70, 62)),
            (0.5, (128, 112, 78)),
            (1.0, (72, 58, 52)),
        ],
        seed,
        bokeh=True,
        streaks=True,
    )
    draw_text(
        page,
        text_mask(size, (84, 140), "ЛЕТО В ГОРОДЕ", font(ARIAL_BOLD, 92)),
        [
            (0.0, (92, 116, 64)),
            (0.5, (128, 84, 48)),
            (1.0, (72, 96, 104)),
        ],
        shadow=(3, 4, 8, 0.4),
    )
    draw_text(
        page,
        text_mask(size, (88, 268), "открытая сцена у воды", font(ARIAL, 40)),
        [(0.0, (86, 112, 88)), (1.0, (110, 92, 74))],
        shadow=None,
    )
    body_font = font(ARIAL, 31)
    body_text = (
        "Солнце бьёт в объектив: часть надписи выжигается до белого, "
        "и от чернил остаётся только слабый след.\n"
        "Между строк всё ещё виден градиент, но его насыщенность падает "
        "вместе с контрастом.\n"
        "Начало программы в 18:00, сбор гостей за час до открытия.\n"
        "Адрес площадки указан в билете и на сайте фестиваля."
    )
    draw_text(
        page,
        text_mask(size, (88, 360), "\n".join(wrap(body_text, body_font, 970)), body_font, spacing=17),
        [
            (0.0, (58, 66, 52)),
            (0.4, (76, 62, 40)),
            (0.8, (48, 58, 68)),
            (1.0, (66, 48, 44)),
        ],
        shadow=None,
    )
    draw_text(
        page,
        text_mask(size, (88, 700), "ВХОД ПО РЕГИСТРАЦИИ", font(ARIAL_BOLD, 32), tracking=3),
        [(0.0, (52, 66, 58)), (1.0, (70, 52, 44))],
        shadow=None,
    )

    page = Image.composite(
        Image.new("RGB", size, (255, 253, 246)),
        page,
        glare_mask(size, (700, 380), (520, 300), 0.86, 150),
    )
    page = Image.composite(
        Image.new("RGB", size, (255, 255, 250)),
        page,
        glare_mask(size, (860, 520), (210, 150), 0.97, 90),
    )
    return page


def low_contrast_en(out: Path, seed: int) -> Image.Image:
    size = (1200, 1600)
    page = background(
        size,
        [
            (0.0, (148, 146, 136)),
            (0.5, (178, 172, 154)),
            (1.0, (136, 148, 144)),
        ],
        seed,
        bokeh=False,
        streaks=True,
    )
    draw_text(
        page,
        text_mask(size, (104, 154), "Field Notes", font(GEORGIA_BOLD, 84)),
        [(0.0, (132, 134, 124)), (0.55, (146, 140, 128)), (1.0, (124, 132, 132))],
        shadow=None,
    )
    draw_text(
        page,
        text_mask(size, (108, 280), "Coastal station · autumn log", font(ARIAL, 34)),
        [(0.0, (142, 142, 132)), (1.0, (134, 140, 136))],
        shadow=None,
    )
    body_font = font(ARIAL, 29)
    body_text = (
        "The ink here sits only a few steps away from the paper. A careless "
        "sampler will drift into the background and repaint the line in the "
        "wrong shade.\n"
        "Readings were taken at dusk, when the light flattened every edge and "
        "the page turned almost uniform.\n"
        "The second column keeps a slightly darker tone so the difference is "
        "visible in a side-by-side comparison.\n"
        "Archive copy · box 14 · shelf 3"
    )
    draw_text(
        page,
        text_mask(size, (108, 360), "\n".join(wrap(body_text, body_font, 960)), body_font, spacing=16),
        [
            (0.0, (140, 138, 128)),
            (0.35, (130, 136, 130)),
            (0.7, (148, 140, 130)),
            (1.0, (128, 134, 136)),
        ],
        shadow=None,
    )
    draw_text(
        page,
        text_mask(size, (108, 760), "CONTROL LINE · DARKER INK", font(ARIAL_BOLD, 26), tracking=2),
        [(0.0, (84, 90, 88)), (1.0, (72, 82, 86))],
        shadow=None,
    )
    return page


def dark_ink_ru(out: Path, seed: int) -> Image.Image:
    size = (1200, 1600)
    page = background(
        size,
        [
            (0.0, (8, 9, 13)),
            (0.48, (20, 18, 24)),
            (0.82, (26, 20, 18)),
            (1.0, (9, 10, 14)),
        ],
        seed,
        bokeh=True,
        streaks=True,
    )
    # Cream paper band with true near-black ink: dark text on a bright surface.
    band = gradient_image(
        (size[0], 440),
        [(0.0, (250, 245, 233)), (0.55, (240, 233, 216)), (1.0, (222, 214, 198))],
    )
    page.paste(band, (0, 0))
    draw_text(
        page,
        text_mask(size, (78, 74), "ТЁМНЫЙ ЗАЛ", font(GEORGIA_BOLD, 108)),
        [(0.0, (3, 4, 7)), (0.55, (12, 11, 15)), (1.0, (30, 20, 14))],
        shadow=None,
    )
    draw_text(
        page,
        text_mask(size, (84, 220), "акустика · свет · тишина", font(GEORGIA_ITALIC, 40)),
        [(0.0, (18, 20, 24)), (1.0, (36, 28, 22))],
        shadow=None,
    )
    draw_text(
        page,
        text_mask(size, (84, 300), "12 ОКТЯБРЯ · 20:00", font(ARIAL_BOLD, 32), tracking=4),
        [(0.0, (6, 7, 9)), (1.0, (32, 28, 24))],
        shadow=None,
    )
    draw_text(
        page,
        text_mask(size, (84, 366), "вход строго по спискам", font(ARIAL_ITALIC, 27)),
        [(0.0, (64, 60, 54)), (1.0, (44, 44, 46))],
        shadow=None,
    )

    # Near-black-on-black panel: the hardest dark ink on a textured field.
    translucent_panel(page, (64, 520, 1136, 1210), (7, 8, 11), 214, radius=38, blur=5)
    draw_text(
        page,
        text_mask(size, (104, 578), "ПОЛНАЯ\nТЕМНОТА", font(ARIAL_BLACK, 104), spacing=4),
        [(0.0, (32, 36, 46)), (0.5, (18, 20, 27)), (1.0, (40, 32, 40))],
        shadow=None,
    )
    body_font = font(ARIAL, 31)
    body_text = (
        "Чернила почти не отличаются от подложки: контраст держится "
        "на нескольких уровнях яркости.\n"
        "Проверка идёт по всей строке: оттенок медленно уходит из "
        "холодного серого в тёплый уголь.\n"
        "Если перевод потеряет этот сдвиг, он станет заметно светлее "
        "или темнее оригинала.\n"
        "Бар на втором этаже, стойка три, вход со двора."
    )
    draw_text(
        page,
        text_mask(size, (104, 860), "\n".join(wrap(body_text, body_font, 960)), body_font, spacing=12),
        [
            (0.0, (48, 52, 62)),
            (0.4, (38, 42, 52)),
            (0.75, (46, 40, 46)),
            (1.0, (34, 34, 42)),
        ],
        shadow=None,
    )
    draw_text(
        page,
        text_mask(size, (104, 1162), "ТИХИЙ БАР · СТОЙКА 3", font(ARIAL_BOLD, 30), tracking=3),
        [(0.0, (58, 12, 26)), (1.0, (34, 8, 20))],
        shadow=None,
    )
    draw_text(
        page,
        text_mask(size, (84, 1300), "ОДИН ВЕЧЕР · ОДНА СЦЕНА", font(ARIAL_BOLD, 34), tracking=3),
        [(0.0, (150, 42, 62)), (1.0, (92, 28, 60))],
        shadow=None,
    )
    draw_text(
        page,
        text_mask(size, (84, 1400), "после концерта — открытый микрофон", font(ARIAL, 28)),
        [(0.0, (86, 88, 96)), (1.0, (66, 70, 80))],
        shadow=None,
    )
    return page


def overexposure_extreme_ru(out: Path, seed: int) -> Image.Image:
    size = (1200, 1600)
    page = background(
        size,
        [
            (0.0, (34, 30, 26)),
            (0.5, (74, 62, 48)),
            (1.0, (22, 24, 30)),
        ],
        seed,
        bokeh=True,
        streaks=True,
    )
    draw_text(
        page,
        text_mask(size, (76, 130), "СОЛНЦЕ\nВ ЛИЦО", font(ARIAL_BOLD, 128), spacing=8),
        [
            (0.0, (226, 38, 26)),
            (0.45, (255, 128, 34)),
            (0.8, (255, 196, 96)),
            (1.0, (232, 46, 96)),
        ],
        shadow=(4, 6, 12, 0.35),
    )
    draw_text(
        page,
        text_mask(size, (84, 452), "ярмарка · сцена · свет", font(ARIAL, 46)),
        [(0.0, (255, 120, 92)), (1.0, (120, 150, 255))],
        shadow=(2, 3, 6, 0.3),
    )
    body_font = font(ARIAL, 31)
    body_text = (
        "Снимок против солнца: правый край кадра выжигается почти до "
        "белого, и от насыщенных чернил остаётся бледный след.\n"
        "Левая часть строки сохраняет градиент, центральная уходит в "
        "пересвет вместе с фоном.\n"
        "Начало программы в 18:00, сбор гостей за час до открытия.\n"
        "Адрес площадки указан в билете и на сайте фестиваля."
    )
    draw_text(
        page,
        text_mask(size, (84, 548), "\n".join(wrap(body_text, body_font, 986)), body_font, spacing=17),
        [(0.0, (46, 34, 28)), (0.5, (72, 40, 24)), (1.0, (40, 44, 54))],
        shadow=None,
    )
    # White-on-white block, already pale before the glare hits it.
    translucent_panel(page, (84, 850, 1116, 1176), (250, 245, 234), 255, radius=30, blur=1)
    draw_text(
        page,
        text_mask(size, (116, 892), "БЕЛЫЙ ТЕКСТ\nНА БЕЛОМ", font(ARIAL_BLACK, 82), spacing=6),
        [(0.0, (255, 253, 246)), (0.55, (255, 248, 232)), (1.0, (252, 240, 214))],
        shadow=(2, 4, 9, 0.30),
    )
    draw_text(
        page,
        text_mask(size, (116, 1102), "ВХОД ПО РЕГИСТРАЦИИ", font(ARIAL_BOLD, 30), tracking=3),
        [(0.0, (255, 252, 242)), (1.0, (250, 238, 214))],
        shadow=(1, 2, 5, 0.28),
    )
    draw_text(
        page,
        text_mask(size, (84, 1260), "ВЕЧЕРНЯЯ ПРОГРАММА · 18:00", font(ARIAL_BOLD, 32), tracking=3),
        [(0.0, (150, 72, 40)), (1.0, (96, 52, 44))],
        shadow=None,
    )
    page = Image.composite(
        Image.new("RGB", size, (255, 252, 244)),
        page,
        glare_mask(size, (760, 430), (560, 360), 0.94, 170),
    )
    page = Image.composite(
        Image.new("RGB", size, (255, 255, 250)),
        page,
        glare_mask(size, (880, 640), (330, 260), 0.99, 110),
    )
    return page


def multifont_ru(out: Path, seed: int) -> Image.Image:
    size = (1200, 1600)
    page = background(
        size,
        [
            (0.0, (10, 20, 32)),
            (0.55, (28, 38, 44)),
            (1.0, (12, 16, 24)),
        ],
        seed,
        bokeh=True,
        streaks=True,
    )
    draw_text(
        page,
        text_mask(size, (66, 58), "ТИПОГРАФИКА", font(IMPACT, 128), tracking=2),
        [(0.0, (255, 214, 132)), (0.5, (255, 120, 96)), (1.0, (214, 120, 255))],
        shadow=(7, 9, 16, 0.62),
    )
    draw_text(
        page,
        text_mask(size, (72, 222), "пять шрифтов в одном плакате", font(SEGOE_LIGHT, 38)),
        [(0.0, (208, 226, 236)), (1.0, (238, 218, 202))],
        shadow=(2, 3, 6, 0.4),
    )
    draw_text(
        page,
        text_mask(size, (72, 292), "СЕРИФ · ГРОТЕСК · МОНО · АКЦИДЕНЦИЯ · СВЕТЛЫЙ", font(ARIAL, 24), tracking=2),
        [(0.0, (150, 190, 206)), (1.0, (196, 170, 160))],
        shadow=None,
    )

    # Paper panel: each block below keeps its own typeface.
    paper = gradient_image(
        (1080, 1000),
        [(0.0, (248, 243, 231)), (0.55, (240, 233, 217)), (1.0, (226, 218, 202))],
    )
    page.paste(paper, (60, 540))
    edge = Image.new("RGBA", size, (0, 0, 0, 0))
    ImageDraw.Draw(edge).rectangle((60, 540, 1140, 1540), outline=(80, 66, 52, 70), width=2)
    page.paste(Image.alpha_composite(page.convert("RGBA"), edge).convert("RGB"))

    draw_text(
        page,
        text_mask(size, (108, 600), "Сезон открытых дверей", font(GEORGIA, 64)),
        [(0.0, (14, 46, 34)), (0.55, (16, 64, 74)), (1.0, (20, 40, 84))],
        shadow=None,
    )
    draw_text(
        page,
        text_mask(size, (112, 710), "программа на сентябрь", font(GEORGIA_ITALIC, 40)),
        [(0.0, (72, 44, 30)), (1.0, (38, 52, 66))],
        shadow=None,
    )
    body_font = font(ARIAL, 28)
    body_text = (
        "Три площадки, один билет и перерыв на кофе. "
        "Лекции идут параллельно, поэтому выбирайте заранее.\n"
        "Волонтёры подскажут, как пройти к мастерским, "
        "и помогут с регистрацией на месте."
    )
    draw_text(
        page,
        text_mask(size, (112, 810), "\n".join(wrap(body_text, body_font, 960)), body_font, spacing=15),
        [(0.0, (30, 38, 50)), (0.5, (44, 44, 42)), (1.0, (58, 38, 36))],
        shadow=None,
    )
    draw_text(
        page,
        text_mask(size, (112, 1060), "ЦЕНА 2 400 ₽", font(CONSOLAS_BOLD, 46), tracking=4),
        [(0.0, (116, 16, 26)), (1.0, (62, 10, 22))],
        shadow=None,
    )
    draw_text(
        page,
        text_mask(size, (820, 1072), "код A-17", font(CONSOLAS_BOLD, 26)),
        [(0.0, (64, 66, 70)), (1.0, (44, 48, 54))],
        shadow=None,
    )
    draw_text(
        page,
        text_mask(size, (112, 1140), "только по записи", font(SEGOE_LIGHT, 38)),
        [(0.0, (82, 86, 92)), (1.0, (62, 70, 80))],
        shadow=None,
    )
    draw_text(
        page,
        text_mask(size, (112, 1230), "ВАЖНО", font(ARIAL_BLACK, 42), tracking=2),
        [(0.0, (150, 20, 38)), (1.0, (98, 12, 34))],
        shadow=None,
    )
    draw_text(
        page,
        text_mask(size, (112, 1290), "приходите заранее: вход закроют за десять минут", font(ARIAL, 28)),
        [(0.0, (40, 42, 48)), (1.0, (66, 46, 40))],
        shadow=None,
    )
    draw_text(
        page,
        text_mask(size, (112, 1370), "фотографии и детали — на сайте фестиваля", font(ARIAL_ITALIC, 27)),
        [(0.0, (92, 88, 82)), (1.0, (76, 82, 88))],
        shadow=None,
    )
    draw_text(
        page,
        text_mask(size, (112, 1440), "12 ОКТЯБРЯ · 19:00 · ДОМ КУЛЬТУРЫ", font(ARIAL_BOLD, 30), tracking=2),
        [(0.0, (22, 40, 54)), (1.0, (34, 30, 44))],
        shadow=None,
    )
    return page


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=Path("tools/lab/pages"))
    parser.add_argument("--seed", type=int, default=20261024)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    pages = (
        ("01_poster_ru_gradient.jpg", poster_ru, args.seed),
        ("02_document_en_gradient.jpg", document_en, args.seed + 1),
        ("03_sign_ja_gradient.jpg", sign_ja, args.seed + 2),
        ("04_blur_ru.jpg", blur_ru, args.seed + 3),
        ("05_overexposure_ru.jpg", overexposure_ru, args.seed + 4),
        ("06_low_contrast_en.jpg", low_contrast_en, args.seed + 5),
        ("07_dark_ink_ru.jpg", dark_ink_ru, args.seed + 6),
        ("08_overexposure_extreme_ru.jpg", overexposure_extreme_ru, args.seed + 7),
        ("09_multifont_ru.jpg", multifont_ru, args.seed + 8),
    )
    for name, builder, seed in pages:
        page = builder(args.out / name, seed)
        path = args.out / name
        page.convert("RGB").save(path, "JPEG", quality=88, subsampling=0, optimize=True)
        print(f"{path}  {path.stat().st_size // 1024} KiB  {page.width}x{page.height}")


if __name__ == "__main__":
    main()
