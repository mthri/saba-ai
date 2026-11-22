"""RoboDesk AI pipelines: Speech Recognition (ASR), Text-to-Speech (TTS), LLM & NLP."""
from server.ai.asr import DropNeMoWarnings, ShenavaASRManager, StreamingVAD, silence_nemo_loggers
from server.ai.assistant import AssistantState, RoboDeskAssistant
from server.ai.llm import ChatManager, warmup_llm
from server.ai.text_processor import (
    detect_wake_word,
    extract_face_tag,
    is_exit_phrase,
    normalize_persian_text,
    number_to_words,
    numbers_to_words,
)
from server.ai.tts import PocketTTSEngine, split_text, stream_sentences

__all__ = [
    'AssistantState',
    'ChatManager',
    'DropNeMoWarnings',
    'PocketTTSEngine',
    'RoboDeskAssistant',
    'ShenavaASRManager',
    'StreamingVAD',
    'detect_wake_word',
    'extract_face_tag',
    'is_exit_phrase',
    'normalize_persian_text',
    'number_to_words',
    'numbers_to_words',
    'silence_nemo_loggers',
    'split_text',
    'stream_sentences',
    'warmup_llm',
]
