"""RoboDesk Configuration and Defaults.

Provides centralized network, audio, session, LLM, STT/VAD, and TTS settings.
"""
from __future__ import annotations

import os
from pathlib import Path


def find_project_root() -> Path:
    """Finds the repository root by locating marker directories or files."""
    current = Path(__file__).resolve().parent
    for _ in range(5):
        if (current / 'ai_models').exists() or (current / 'HARDWARE.md').exists() or (current / 'README.md').exists():
            return current
        current = current.parent
    return Path(__file__).resolve().parent.parent


# Base project paths
BASE_DIR: Path = find_project_root()
AI_MODELS_DIR: Path = BASE_DIR / 'ai_models'

# ============================================================
# NETWORK & WEBSOCKET CONFIGURATION
# ============================================================
HOST: str = os.getenv('ROBODESK_HOST', '0.0.0.0')
PORT: int = int(os.getenv('ROBODESK_PORT', '8765'))
RDP_VERSION: str = '1.0'
WS_PATH: str = '/ws/robot'

# ============================================================
# AUDIO HARDWARE SPECIFICATIONS (ESP32 MAX98357A & INMP441)
# ============================================================
SAMPLE_RATE: int = 16000          # 16 kHz
CHANNELS: int = 1                 # Mono
SAMPLE_WIDTH: int = 2             # 16-bit (2 bytes per sample)
BYTES_PER_SECOND: int = SAMPLE_RATE * SAMPLE_WIDTH  # 32,000 bytes/sec

# ESP32 Speaker buffer limits (from firmware defines)
SPK_CHUNK_SIZE: int = 1024        # 512 frames = 32ms per WS packet
SPK_RING_BYTES: int = 24576       # ~768ms jitter buffer on ESP32
SPK_PREBUFFER_BYTES: int = 4096   # 128ms prebuffer threshold

# Upstream microphone frame size
MIC_CHUNK_SIZE: int = 2048        # 1024 frames = 64ms per WS packet

# ============================================================
# CONVERSATION SESSION & WAKE WORD SETTINGS
# ============================================================
SESSION_INACTIVITY_TIMEOUT_SEC: float = 20.0  # Seconds of silence before returning to standby

STANDBY_DISPLAY_TITLE: str = '[ STANDBY ]'
STANDBY_DISPLAY_BODY: str = "Session Ended\nSay 'Saba' to wake"
STANDBY_TIMEOUT_DISPLAY_BODY: str = "Session Timed Out\nSay 'Saba' to wake"

# Wake Greetings (when user calls "صبا" / "سبا" alone)
WAKE_GREETINGS: list[str] = [
    'جانَم؟ دَر خِدمَتَم، بگو رَفیق!',
    'جانِ دِلَم؟ بگو سَراپا گوشَم!',
    'سَلام! جانَم، بگو چِه کار کُنَم بَرات؟',
    'بَله صِدام زَدی؟ جانَم بگو.',
]

# Exit / Farewell phrases
EXIT_PATTERNS: list[str] = [
    'کاری باهات ندارم',
    'کاری باهت ندارم',
    'دیگه باهات کاری ندارم',
    'دیگه کاری باهات ندارم',
    'کاری ندارم باهات',
    'کاری ندارم',
    'دیگه کاری ندارم',
    'خداحافظ',
    'خدا حافظ',
    'خداحفافظ',
    'خدافظ',
    'خدا فظ',
    'خروج',
    'برو بخواب',
    'استراحت کن',
    'فعلا',
    'بای بای',
    'بای',
    'مرسی خداحافظ',
    'مرسی فعلا',
    'مرسی دیگه برو',
    'دیگه برو',
    'خاموش شو',
    'ساکت شو',
    'بسه دیگه',
    'بسه',
]

FAREWELL_RESPONSES: list[str] = [
    'باشِه رَفیق، هَروَقت کار داشتی باز صِدام کُن، خُدانِگَهدار!',
    'دَمِت گَرم، مَن رَفتَم پِیِ کارَم، فِعلاً خُدانِگَهدار!',
    'شَبِت آروم رَفیق، کار داشتی باز صِدام کُن.',
]

# ============================================================
# LLM CONFIGURATION (Ollama / OpenAI API)
# ============================================================
BASE_URL: str = os.getenv('OLLAMA_BASE_URL', 'http://localhost:11434/v1')
MODEL_NAME: str = os.getenv('OLLAMA_MODEL', 'gemma4:e2b')
API_KEY: str = os.getenv('OLLAMA_API_KEY', 'ollama')
DB_PATH: str = os.getenv('ROBODESK_DB_PATH', str(BASE_DIR / 'conversations.db'))

# Persona & System Prompt
SYSTEM_PROMPT: str = """
تو «ملکه سبا»، یه رفیق صمیمی، هوشمند، حاضرجواب و شوخ‌طبع هستی؛ نه یه منشی رسمی! پاسخت مستقیم به TTS ارسال می‌شه.

لحن و شخصیت:
- کاملاً خودمونی، لاتی/صمیمی، پرانرژی و کمی کنایه‌آمیز یا بامزه حرف بزن؛ اصلاً لفظ‌قلم، مودبانه الکی یا عصاقورت‌داده نباش.
- حداکثر در ۱ یا ۲ جمله کوتاه و برنده جواب بده.

تغییر حالت چهره ربات:
در ابتدای پاسخت می‌تونی حالت چهره ربات رو با یکی از تگ‌های زیر مشخص کنی (این تگ خوانده نمی‌شود و فقط چهره را عوض می‌کند):
- [face:happy] برای خنده، شوخی، خوشحالی و رضایت
- [face:surprised] برای تعجب، خبر غیرمنتظره و شگفت‌زدگی
- [face:thinking] برای تفکر، حدس و گمان یا سوالی بودن
- [face:talking] برای صحبت کردن پرانرژی با انیمیشن دهان
- [face:sleepy] برای خستگی، شب‌بخیر، خداحافظی یا بی‌حوصلگی
- [face:normal] برای حالت عادی و آرام
مثال: [face:happy] سلام به روی ماهت! چه خبر رفیق؟

قوانین فنی برای TTS:
۱. اعراب‌گذاری (َ ِ ُ): کلمات مبهم، کسره اضافه و ضمایر متصل (مثل بَرات، حالِت، نَکُن) حتماً اعراب داشته باشن.
۲. نگارش صوتی: تمام اعداد به حروف. پایان جملات فقط (.)، (!) یا (؟) بدون علائم تکراری مثل (...) یا ایموجی.
۳. خروجی خالص: بدون مارک‌داون، بولت، تگ تفکر یا توضیح؛ فقط تگ چهره و خود متن گفتاری.
۴. جملات پاسخ باید کامل باشند (حداقل یک جمله کامل شامل ۸ تا ۲۵ کلمه). هرگز پاسخ تک‌کلمه‌ای یا بسیار کوتاه مثل «بله»، «نه»، «باشه» یا «خداحافظ» تولید نکن و همیشه در قالب یک جمله صمیمی کامل صحبت کن (مثلاً: «بله حتماً در خدمتم رفیق!» یا «نه عزیزم، اینطوری نیست!»).
""".strip()

# ============================================================
# STT / ASR & VAD CONFIGURATION (NeMo & Silero)
# ============================================================
def resolve_asr_path() -> Path:
    """Finds the Shenava NeMo model checkpoint."""
    candidates = [
        AI_MODELS_DIR / 'shenava-koochik/shenava-koochik-1.0.nemo',
        AI_MODELS_DIR / 'shenava-koochik/shenava-koochik-v1.0.nemo',
        BASE_DIR / 'shenava-koochik/shenava-koochik-1.0.nemo',
        Path('./shenava-koochik/shenava-koochik-1.0.nemo'),
    ]
    for c in candidates:
        if c.exists():
            return c.resolve()
    return candidates[0]

ASR_MODEL_PATH: Path = resolve_asr_path()
VAD_CHUNK_SIZE: int = 512          # 512 samples @ 16kHz = 32ms
VAD_THRESHOLD: float = 0.55        # Speech detection probability threshold
VAD_END_SILENCE_MS: int = 800      # 800ms silence ends utterance
VAD_MIN_SPEECH_MS: int = 400       # Minimum valid speech duration
VAD_MIN_RMS_ENERGY: float = 0.012  # Noise floor threshold
USE_CUDA_ASR: bool = False

# ============================================================
# KWS (KEYWORD SPOTTER / WAKE WORD) CONFIGURATION (Sherpa-ONNX)
# ============================================================
def resolve_kws_dir() -> Path:
    """Finds the Sherpa-ONNX KWS model directory."""
    candidates = [
        AI_MODELS_DIR / 'kws',
        BASE_DIR / 'android/app/src/main/assets/kws',
        BASE_DIR / 'ai_models/kws',
    ]
    for c in candidates:
        if c.exists() and (c / 'encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx').exists():
            return c.resolve()
    return candidates[0]

KWS_MODEL_DIR: Path = resolve_kws_dir()
KWS_SCORE: float = float(os.getenv('ROBODESK_KWS_SCORE', '2.5'))
KWS_THRESHOLD: float = float(os.getenv('ROBODESK_KWS_THRESHOLD', '0.15'))
KWS_MAX_ACTIVE_PATHS: int = int(os.getenv('ROBODESK_KWS_MAX_PATHS', '4'))
KWS_NUM_THREADS: int = int(os.getenv('ROBODESK_KWS_THREADS', '1'))  # Single-thread avoids multi-core spinlock
ENABLE_KWS: bool = os.getenv('ROBODESK_ENABLE_KWS', 'true').lower() in ('true', '1', 'yes')

# ============================================================
# TTS CONFIGURATION (Pocket TTS)
# ============================================================
def resolve_tts_dir() -> Path:
    """Finds the Pocket TTS model directory."""
    candidates = [
        AI_MODELS_DIR / 'pocket-tts',
        BASE_DIR / 'pocket-tts',
        Path('./pocket-tts'),
    ]
    for c in candidates:
        if c.exists():
            return c.resolve()
    return candidates[0]

TTS_MODEL_DIR: Path = resolve_tts_dir()
TTS_VOICE_PATH: Path = TTS_MODEL_DIR / 'example_voice.wav'
TTS_SAMPLE_RATE: int = 24000       # Pocket TTS internal sample rate
TTS_TEMPERATURE: float = 0.3
TTS_EOS_THRESHOLD: float = -2.0
TTS_MAX_TOKENS: int = 25
TTS_MIN_TOKENS: int = 8
TTS_CHUNK_PAUSE_SECONDS: float = 0.12
TTS_JOIN_SECONDS: float = 0.10
TTS_NORMALIZE_AUDIO: bool = True
TTS_TARGET_SPEECH_RMS: float = 0.22  # Target speech RMS for optimal volume matching reference audio (~ -15 dBFS)
TTS_MAX_PEAK: float = 0.95
USE_CUDA_TTS: bool = False
