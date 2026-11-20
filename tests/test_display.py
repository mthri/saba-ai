"""Unit tests for server-side OLED Persian text rendering and bitmap encoding."""
from __future__ import annotations

import base64
import unittest

from server.utils.display import (
    bitmap_to_base64,
    get_default_font_path,
    is_persian_text,
    render_persian_text_to_bitmap,
)


class TestDisplayRenderer(unittest.TestCase):
    def test_is_persian_text(self) -> None:
        self.assertTrue(is_persian_text('سلام صبا'))
        self.assertTrue(is_persian_text('متن تستی ۱۲۳'))
        self.assertFalse(is_persian_text('Hello Robot 123!'))
        self.assertFalse(is_persian_text('1234567890'))

    def test_get_default_font_path(self) -> None:
        font_path = get_default_font_path()
        self.assertIsNotNone(font_path)
        self.assertTrue(font_path.is_file())

    def test_render_persian_text_to_bitmap(self) -> None:
        bmp = render_persian_text_to_bitmap(
            body='صبا روبات رومیزی هوشمند',
            title='[ پیام صبا ]',
            width=128,
            height=64,
            font_size=11,
        )
        self.assertEqual(len(bmp), 1024)
        # Verify it is not completely blank (contains some set bits)
        self.assertTrue(any(b != 0 for b in bmp))

    def test_render_multiline_wrapping(self) -> None:
        long_persian = (
            'این یک متن طولانی برای تست عملکرد خط‌شکنی خودکار و رندر دقیق کاراکترهای فارسی است '
            'که باید بدون خطا رندر شود.'
        )
        bmp = render_persian_text_to_bitmap(body=long_persian, width=128, height=64)
        self.assertEqual(len(bmp), 1024)

    def test_bitmap_to_base64(self) -> None:
        raw = b'\xAA' * 1024
        b64 = bitmap_to_base64(raw)
        decoded = base64.b64decode(b64)
        self.assertEqual(decoded, raw)


if __name__ == '__main__':
    unittest.main()
