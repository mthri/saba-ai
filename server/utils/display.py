"""Server-side OLED Display Renderer for Persian/Arabic Typography and 1-bit Monochrome Bitmaps."""
from __future__ import annotations

import base64
import os
import re
from pathlib import Path
from typing import Tuple

from PIL import Image, ImageDraw, ImageFont

PERSIAN_ARABIC_REGEX = re.compile(r'[\u0600-\u06FF\u0750-\u077F\uFB50-\uFDFF\uFE70-\uFEFF]')

# Ordered font candidates: prioritize high-quality Persian fonts
CANDIDATE_FONT_PATHS = [
    Path('/home/amir/.local/share/fonts/Vazirmatn-Regular.ttf'),
    Path('/home/amir/.local/share/fonts/Vazirmatn-Medium.ttf'),
    Path('/home/amir/.local/share/fonts/pcalendarFonts/Vazirmatn-Regular.ttf'),
    Path('/usr/share/fonts/truetype/noto/NotoNaskhArabicUI-Regular.ttf'),
    Path('/usr/share/fonts/truetype/noto/NotoSansArabicUI-Regular.ttf'),
    Path('/usr/share/fonts/truetype/noto/NotoNaskhArabic-Regular.ttf'),
    Path('/usr/share/fonts/truetype/noto/NotoSansArabic-Regular.ttf'),
    Path('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'),
]


def is_persian_text(text: str) -> bool:
    """Check if the string contains Persian or Arabic characters."""
    return bool(PERSIAN_ARABIC_REGEX.search(text))


def get_default_font_path() -> Path | None:
    """Find the best available Persian/Arabic TrueType font on the host system."""
    for font_path in CANDIDATE_FONT_PATHS:
        if font_path.is_file():
            return font_path

    # Check fontconfig cache or common directories if none of the above matched
    common_dirs = [
        Path.home() / '.local/share/fonts',
        Path.home() / '.fonts',
        Path('/usr/share/fonts'),
    ]
    for directory in common_dirs:
        if directory.is_dir():
            for ttf in directory.glob('**/*Vazir*.ttf'):
                if ttf.is_file():
                    return ttf
            for ttf in directory.glob('**/*Noto*Arabic*.ttf'):
                if ttf.is_file():
                    return ttf

    return None


def get_font(font_path: Path | str | None, size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    """Load an ImageFont with the requested point size."""
    path = Path(font_path) if font_path else get_default_font_path()
    if path and path.is_file():
        try:
            return ImageFont.truetype(str(path), size)
        except Exception:
            pass
    return ImageFont.load_default()


def render_persian_text_to_bitmap(
    body: str,
    title: str = '',
    width: int = 128,
    height: int = 64,
    font_size: int = 11,
    title_font_size: int = 10,
    font_path: Path | str | None = None,
) -> bytes:
    """
    Render Persian/Arabic (or mixed bilingual) text to a 1-bit monochrome bitmap
    for 128x64 SSD1306 OLED displays.
    
    Returns 1024 bytes (128x64 / 8) packed MSB-first, directly compatible with Adafruit_GFX.
    """
    img = Image.new('1', (width, height), 0)
    draw = ImageDraw.Draw(img)

    f_body = get_font(font_path, font_size)
    f_title = get_font(font_path, title_font_size)

    top_y = 2
    if title.strip():
        # Render title right-to-left at top right
        clean_title = title.strip()
        direction = 'rtl' if is_persian_text(clean_title) else 'ltr'
        anchor_x = width - 2 if direction == 'rtl' else 2
        anchor = 'ra' if direction == 'rtl' else 'la'

        draw.text(
            (anchor_x, top_y),
            clean_title,
            fill=1,
            font=f_title,
            anchor=anchor,
            direction=direction,
        )
        draw.line([(0, top_y + 12), (width - 1, top_y + 12)], fill=1)
        top_y += 15

    # Word wrapping for body
    is_rtl = is_persian_text(body)
    direction = 'rtl' if is_rtl else 'ltr'
    anchor_x = width - 2 if is_rtl else 2
    anchor = 'ra' if is_rtl else 'la'

    max_line_width = width - 4
    lines: list[str] = []

    # Handle explicit newlines first
    paragraphs = body.split('\n')
    for para in paragraphs:
        words = para.split()
        if not words:
            lines.append('')
            continue

        current_line: list[str] = []
        for word in words:
            test_line = ' '.join(current_line + [word])
            bbox = draw.textbbox((0, 0), test_line, font=f_body, direction=direction)
            w = bbox[2] - bbox[0]
            if w > max_line_width and current_line:
                lines.append(' '.join(current_line))
                current_line = [word]
            else:
                current_line.append(word)
        if current_line:
            lines.append(' '.join(current_line))

    # Calculate line spacing based on font size
    line_spacing = font_size + 2
    max_lines = max(1, (height - top_y) // line_spacing)

    # Render lines
    y = top_y
    for line in lines[:max_lines]:
        if line:
            draw.text(
                (anchor_x, y),
                line,
                fill=1,
                font=f_body,
                anchor=anchor,
                direction=direction,
            )
        y += line_spacing

    return img.tobytes()


def bitmap_to_base64(bitmap_bytes: bytes) -> str:
    """Encode bitmap bytes to base64 ASCII string."""
    return base64.b64encode(bitmap_bytes).decode('ascii')
