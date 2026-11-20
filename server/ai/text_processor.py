"""Persian Natural Language Processing & Text Formatting.

Provides Persian text normalization for speech synthesis, Wake-Word detection ('صبا' / 'سبا'),
conversation exit recognition, and OLED facial expression tag parsing.
"""
from __future__ import annotations

import re
import unicodedata
from typing import Final

from server.config import EXIT_PATTERNS

# ============================================================================
# FACIAL EMOTION TAG EXTRACTION
# ============================================================================

FACE_TAG_REGEX: Final[re.Pattern[str]] = re.compile(
    r'\[face:(normal|happy|surprised|sleepy|thinking|talking)\]',
    re.IGNORECASE,
)


def extract_face_tag(text: str) -> tuple[str | None, str]:
    """Extract face expression tag from text/LLM output and return (emotion, cleaned_text)."""
    match = FACE_TAG_REGEX.search(text)
    if match:
        emotion = match.group(1).lower()
        cleaned = FACE_TAG_REGEX.sub('', text).strip()
        return emotion, cleaned
    return None, text


# ============================================================================
# WAKE WORD & EXIT PHRASE DETECTION
# ============================================================================

def detect_wake_word(text: str) -> tuple[bool, str]:
    """Detect if the user is addressing the robot by name ('صبا' / 'سبا' / 'صباح' / 'ثبا').

    Returns:
        tuple[bool, str]: (is_addressed, remaining_query_or_empty)
    """
    normalized = text.replace('ي', 'ی').replace('ك', 'ک').replace('\u200c', ' ')
    normalized = ' '.join(normalized.split()).strip()

    # Pattern 1: Wake word alone or with greetings/honorifics (e.g. "صبا", "سلام صبا", "هی سبا جون")
    m_alone = re.match(
        r'^(?:سلام\s+|هی\s+|الو\s+)?(?:ملکه\s+)?(?:صبا|سبا|صباح|ثبا)(?:\s+خانم|\s+خانوم|\s+جون|\s+جان)?[\s،.!؟:]*$',
        normalized,
        re.IGNORECASE,
    )
    if m_alone:
        return True, ''

    # Pattern 2: Wake word at the start followed by a query (e.g. "صبا ساعت چنده؟")
    m_query = re.match(
        r'^(?:سلام\s+|هی\s+|الو\s+)?(?:ملکه\s+)?(?:صبا|سبا|صباح|ثبا)(?:\s+خانم|\s+خانوم|\s+جون|\s+جان)?(?:\s*[\s،.!؟:]\s*|\s+)(.+)$',
        normalized,
        re.IGNORECASE,
    )
    if m_query:
        return True, m_query.group(1).strip()

    # Pattern 3: Wake word anywhere in the utterance
    tokens = [re.sub(r'[^\w\s]', '', w) for w in normalized.split()]
    if any(w in ('صبا', 'سبا', 'صباح', 'ثبا') for w in tokens):
        cleaned = re.sub(
            r'(?:سلام\s+|هی\s+|الو\s+)?(?:ملکه\s+)?(?:صبا|سبا|صباح|ثبا)(?:\s+خانم|\s+خانوم|\s+جون|\s+جان)?',
            '',
            normalized,
            flags=re.IGNORECASE,
        ).strip()
        cleaned = re.sub(r'^[،.!؟:\s]+', '', cleaned).strip()
        return True, cleaned

    return False, normalized


def is_exit_phrase(text: str) -> bool:
    """Determine whether user input expresses intent to stop or exit the conversation."""
    normalized = text.replace('ي', 'ی').replace('ك', 'ک').replace('\u200c', ' ')
    normalized = ' '.join(normalized.split()).lower()
    compact = normalized.replace(' ', '')

    for pattern in EXIT_PATTERNS:
        clean_p = pattern.replace(' ', '')
        if pattern in normalized or clean_p in compact:
            return True
    return False


# ============================================================================
# PERSIAN TEXT NORMALIZATION (FOR TTS & SPEECH PROCESSING)
# ============================================================================

ZWNJ: Final[str] = '‌'
PERSIAN_LETTERS: Final[str] = 'آابپتثجچحخدذرزژسشصضطظعغفقکگلمنوهیئ'
ALIGNER_ALPHABET: Final[set[str]] = set(PERSIAN_LETTERS + ZWNJ)
KEPT_PUNCT: Final[str] = '.،؛؟!:'
ALLOWED_CHARS: Final[set[str]] = ALIGNER_ALPHABET | set(KEPT_PUNCT) | {' '}

CHAR_MAP: Final[dict[str, str]] = {
    'ي': 'ی',
    'ى': 'ی',
    'ۍ': 'ی',
    'ې': 'ی',
    'ك': 'ک',
    'ڪ': 'ک',
    'ة': 'ه',
    'ۀ': 'ه',
    'أ': 'ا',
    'إ': 'ا',
    'ٱ': 'ا',
    'ٲ': 'ا',
    'ٳ': 'ا',
    'ؤ': 'و',
    'ۂ': 'ه',
    'ۃ': 'ه',
    'ء': '',
    'ـ': '',
}

DIACRITICS_REGEX: Final[re.Pattern[str]] = re.compile(r'[ً-ٰٕۖ-ۭٖ-ٟ]')
INVISIBLES_REGEX: Final[re.Pattern[str]] = re.compile(r'[​‍‎‏‪-‮⁦-⁩﻿­]')

DIGIT_MAP: Final[dict[str, str]] = {chr(0x06F0 + i): str(i) for i in range(10)}
DIGIT_MAP.update({chr(0x0660 + i): str(i) for i in range(10)})

PUNCT_MAP: Final[dict[str, str]] = {
    ',': '،',
    '?': '؟',
    '!': '.',
    ';': '؛',
    '٬': '',
    '٫': '.',
    '…': '.',
    '»': ' ',
    '«': ' ',
    '“': ' ',
    '”': ' ',
    '‘': ' ',
    '’': ' ',
    '"': ' ',
    "'": ' ',
    '`': ' ',
    '(': ' ',
    ')': ' ',
    '[': ' ',
    ']': ' ',
    '{': ' ',
    '}': ' ',
    '–': ' ',
    '—': ' ',
    '−': ' ',
    '-': ' ',
    '‐': ' ',
    '/': ' ',
    '\\': ' ',
    '*': ' ',
    '_': ' ',
    '|': ' ',
    '×': ' ',
    '=': ' ',
    '+': ' ',
    '،': '،',
    '؛': '؛',
    '؟': '؟',
}

_ONES = ['', 'یک', 'دو', 'سه', 'چهار', 'پنج', 'شش', 'هفت', 'هشت', 'نه']
_TEENS = ['ده', 'یازده', 'دوازده', 'سیزده', 'چهارده', 'پانزده', 'شانزده', 'هفده', 'هجده', 'نوزده']
_TENS = ['', '', 'بیست', 'سی', 'چهل', 'پنجاه', 'شصت', 'هفتاد', 'هشتاد', 'نود']
_HUNDREDS = ['', 'صد', 'دویست', 'سیصد', 'چهارصد', 'پانصد', 'ششصد', 'هفتصد', 'هشتصد', 'نهصد']
_SCALES = [(10**12, 'تریلیون'), (10**9, 'میلیارد'), (10**6, 'میلیون'), (10**3, 'هزار')]
_FRACTION_UNITS = {1: 'دهم', 2: 'صدم', 3: 'هزارم'}
_JOIN = ' و '
MAX_NUMBER_DIGITS = 12


def _under_thousand(n: int) -> str:
    parts: list[str] = []
    if n >= 100:
        parts.append(_HUNDREDS[n // 100])
        n %= 100
    if n >= 20:
        parts.append(_TENS[n // 10])
        n %= 10
    if 10 <= n < 20:
        parts.append(_TEENS[n - 10])
        n = 0
    if n > 0:
        parts.append(_ONES[n])
    return _JOIN.join(parts)


def number_to_words(n: int) -> str:
    """Convert a non-negative integer into Persian words."""
    if n == 0:
        return 'صفر'
    parts: list[str] = []
    for value, name in _SCALES:
        if n >= value:
            count = n // value
            n %= value
            head = '' if (count == 1 and value == 10**3) else _under_thousand(count) + ' '
            parts.append(f'{head}{name}'.strip())
    if n > 0:
        parts.append(_under_thousand(n))
    return _JOIN.join(parts)


def _digits_one_by_one(digits: str) -> str:
    return ' '.join('صفر' if d == '0' else _ONES[int(d)] for d in digits)


def _number_token_to_words(match: re.Match[str]) -> str:
    whole, frac = match.group(1), match.group(2)
    if len(whole) > MAX_NUMBER_DIGITS or (whole.startswith('0') and len(whole) > 1):
        return (
            ' ' + _digits_one_by_one(whole) + (' ' + _digits_one_by_one(frac) if frac else '') + ' '
        )
    whole = whole.lstrip('0') or '0'
    words = number_to_words(int(whole))
    if frac:
        frac = frac.rstrip('0')
        if not frac:
            return ' ' + words + ' '
        unit = _FRACTION_UNITS.get(len(frac))
        if unit:
            words += ' ممیز ' + number_to_words(int(frac)) + ' ' + unit
        else:
            words += ' ممیز ' + _digits_one_by_one(frac)
    return ' ' + words + ' '


_NUMBER_RE: Final[re.Pattern[str]] = re.compile(r'(\d+)(?:[.](\d+))?')


def numbers_to_words(text: str) -> str:
    """Replace all digit runs with their spoken Persian words."""
    text = re.sub(r'(?<=\d),(?=\d\d\d\b)', '', text)
    return _NUMBER_RE.sub(_number_token_to_words, text)


def normalize_persian_text(text: str, *, spell_numbers: bool = True) -> str:
    """Fold text onto canonical Persian spelling for TTS synthesis."""
    text = unicodedata.normalize('NFC', text)
    text = INVISIBLES_REGEX.sub('', text)
    text = DIACRITICS_REGEX.sub('', text)
    text = text.translate(str.maketrans(CHAR_MAP))
    text = text.translate(str.maketrans(DIGIT_MAP))
    text = text.replace('٬', '').replace('٫', '.')
    text = re.sub(r'[٪%]', ' درصد ', text)
    if spell_numbers:
        text = numbers_to_words(text)
    text = text.translate(str.maketrans(PUNCT_MAP))
    text = ''.join(c if c in ALLOWED_CHARS else ' ' for c in text)
    text = re.sub(rf'{ZWNJ}+', ZWNJ, text)
    text = re.sub(rf'\s*{ZWNJ}\s*', lambda m: ZWNJ if ' ' not in m.group(0) else ' ', text)
    text = re.sub(rf'(?<![{PERSIAN_LETTERS}]){ZWNJ}|{ZWNJ}(?![{PERSIAN_LETTERS}])', '', text)
    text = re.sub(rf'\s+([{re.escape(KEPT_PUNCT)}])', r'\1', text)
    text = re.sub(rf'([{re.escape(KEPT_PUNCT)}])\1+', r'\1', text)
    text = re.sub(r'\s+', ' ', text).strip()
    return text.lstrip(KEPT_PUNCT + ' ')
