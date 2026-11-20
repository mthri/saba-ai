"""Unit tests for Persian NLP, text normalization, wake word, and intent detection."""
from __future__ import annotations

import unittest

from server.ai.text_processor import (
    detect_wake_word,
    extract_face_tag,
    is_exit_phrase,
    normalize_persian_text,
    number_to_words,
)


class TestPersianNLP(unittest.TestCase):
    """Test suite verifying Persian text processing correctness."""

    def test_number_to_words(self) -> None:
        self.assertEqual(number_to_words(0), 'صفر')
        self.assertEqual(number_to_words(5), 'پنج')
        self.assertEqual(number_to_words(14), 'چهارده')
        self.assertEqual(number_to_words(42), 'چهل و دو')
        self.assertEqual(number_to_words(100), 'صد')
        self.assertEqual(number_to_words(1402), 'هزار و چهارصد و دو')

    def test_normalize_persian_text(self) -> None:
        # Arabic character folding
        raw = 'سلام عليک، چطوری؟'
        norm = normalize_persian_text(raw)
        self.assertIn('ی', norm)
        self.assertNotIn('ي', norm)

        # Numbers to words in text
        text_with_digits = 'ساعت ۱۲ و ۳۰ دقیقه است.'
        norm_digits = normalize_persian_text(text_with_digits)
        self.assertIn('دوازده', norm_digits)
        self.assertIn('سی', norm_digits)

    def test_detect_wake_word_standalone(self) -> None:
        # Standalone wake word variations
        addressed, query = detect_wake_word('صبا')
        self.assertTrue(addressed)
        self.assertEqual(query, '')

        addressed, query = detect_wake_word('سلام صبا')
        self.assertTrue(addressed)
        self.assertEqual(query, '')

        addressed, query = detect_wake_word('هی سبا جون')
        self.assertTrue(addressed)
        self.assertEqual(query, '')

    def test_detect_wake_word_with_query(self) -> None:
        addressed, query = detect_wake_word('سلام صبا ساعت چنده؟')
        self.assertTrue(addressed)
        self.assertEqual(query, 'ساعت چنده؟')

        addressed, query = detect_wake_word('صبا یه جوک بگو')
        self.assertTrue(addressed)
        self.assertEqual(query, 'یه جوک بگو')

    def test_detect_wake_word_absent(self) -> None:
        addressed, _ = detect_wake_word('امروز هوا چطوره؟')
        self.assertFalse(addressed)

        addressed, _ = detect_wake_word('سلام به همه')
        self.assertFalse(addressed)

    def test_is_exit_phrase(self) -> None:
        self.assertTrue(is_exit_phrase('خداحافظ'))
        self.assertTrue(is_exit_phrase('دیگه باهات کاری ندارم'))
        self.assertTrue(is_exit_phrase('برو بخواب'))
        self.assertTrue(is_exit_phrase('فعلا مرسی'))
        self.assertFalse(is_exit_phrase('می‌خوام یک کتاب بخونم'))
        self.assertFalse(is_exit_phrase('یک جوک دیگه بگو'))

    def test_extract_face_tag(self) -> None:
        emotion, cleaned = extract_face_tag('[face:happy] سلام رفیق!')
        self.assertEqual(emotion, 'happy')
        self.assertEqual(cleaned, 'سلام رفیق!')

        emotion, cleaned = extract_face_tag('[face:thinking] بذار ببینم...')
        self.assertEqual(emotion, 'thinking')
        self.assertEqual(cleaned, 'بذار ببینم...')

        emotion, cleaned = extract_face_tag('متن بدون تگ چهره')
        self.assertIsNone(emotion)
        self.assertEqual(cleaned, 'متن بدون تگ چهره')


if __name__ == '__main__':
    unittest.main()
