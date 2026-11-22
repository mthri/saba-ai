"""Pocket TTS speech synthesis and streaming for RoboDesk."""
from __future__ import annotations

import asyncio
import concurrent.futures
import re
import tempfile
from collections.abc import Callable, Generator, Iterable
from pathlib import Path
from typing import Final

import numpy as np
import sphn
import yaml
from pocket_tts.models.tts_model import TTSModel
from pocket_tts.utils.config import Config

from server.ai.text_processor import normalize_persian_text
from server.config import (
    TTS_CHUNK_PAUSE_SECONDS,
    TTS_EOS_THRESHOLD,
    TTS_JOIN_SECONDS,
    TTS_MAX_PEAK,
    TTS_MAX_TOKENS,
    TTS_MIN_TOKENS,
    TTS_MODEL_DIR,
    TTS_NORMALIZE_AUDIO,
    TTS_SAMPLE_RATE,
    TTS_TARGET_SPEECH_RMS,
    TTS_TEMPERATURE,
    TTS_VOICE_PATH,
    USE_CUDA_TTS,
)
from server.utils.audio import (
    float_to_pcm16_bytes,
    normalize_speech_audio,
    resample_24k_to_16k,
    trim_silence,
)

HARD_BREAK: Final[re.Pattern[str]] = re.compile(r'(?<=[.!؟?])\s+')
SOFT_BREAK: Final[re.Pattern[str]] = re.compile(r'(?<=[،؛:])\s+')
HARD_PUNCT: Final[tuple[str, ...]] = ('.', '!', '؟', '?')
BREAK_CHARS: Final[tuple[str, ...]] = ('.', '!', '؟', '?', '،', '؛', ':')


def split_text(
    text: str,
    count_tokens: Callable[[str], int],
    max_tokens: int = TTS_MAX_TOKENS,
    keep_punct_boundaries: bool = False,
    min_tokens: int = TTS_MIN_TOKENS,
) -> list[str]:
    """Split text into chunks of at most max_tokens without leaving tiny under-tokenized chunks."""
    text = ' '.join(text.split()).strip()
    if not text:
        return []

    def fits(s: str) -> bool:
        return count_tokens(s) <= max_tokens

    if fits(text):
        return [text]

    def split_by(pattern: re.Pattern[str], piece: str) -> list[str]:
        parts = [part.strip() for part in pattern.split(piece) if part.strip()]
        return parts if len(parts) > 1 else []

    def recurse(piece: str) -> list[str]:
        if fits(piece):
            return [piece]

        # First try sentence boundaries, then clause boundaries.
        for pattern in (HARD_BREAK, SOFT_BREAK):
            parts = split_by(pattern, piece)
            if parts:
                return [chunk for part in parts for chunk in recurse(part)]

        # Last resort: split by words.
        output: list[str] = []
        current = ''
        for word in piece.split():
            trial = f'{current} {word}'.strip() if current else word
            if current and not fits(trial):
                output.append(current)
                current = word
            else:
                current = trial
        if current:
            output.append(current)
        return output

    if keep_punct_boundaries:
        pieces = [text]
        for pattern in (HARD_BREAK, SOFT_BREAK):
            pieces = [chunk for piece in pieces for chunk in (split_by(pattern, piece) or [piece])]
        parts = [chunk for piece in pieces for chunk in recurse(piece)]
    else:
        parts = recurse(text)

    # Merge neighbouring chunks whenever possible to ensure no chunk is under min_tokens
    merged: list[str] = []
    for chunk in parts:
        if not merged:
            merged.append(chunk)
            continue

        combined = f'{merged[-1]} {chunk}'
        chunk_under_min = count_tokens(chunk) < min_tokens
        prev_under_min = count_tokens(merged[-1]) < min_tokens
        not_ending_punct = not merged[-1].rstrip().endswith(BREAK_CHARS)

        if (chunk_under_min or prev_under_min or not_ending_punct) and fits(combined):
            merged[-1] = combined
        elif fits(combined) and count_tokens(combined) <= 18:
            merged[-1] = combined
        else:
            merged.append(chunk)

    return merged


class PocketTTSEngine:
    """Persian neural text-to-speech engine producing 16kHz PCM for the ESP32 speaker."""

    def __init__(
        self,
        model_dir: Path = TTS_MODEL_DIR,
        voice_path: Path = TTS_VOICE_PATH,
        use_cuda: bool = USE_CUDA_TTS,
    ) -> None:
        self.model_dir = Path(model_dir).resolve()
        self.voice_path = Path(voice_path).resolve()
        self.use_cuda = use_cuda
        self._verify_files()

        print(f'[*] Initializing Pocket TTS engine from {self.model_dir}...')
        config_path = self.model_dir / 'farsi.yaml'
        with open(config_path, 'r', encoding='utf-8') as f:
            cfg_dict = yaml.safe_load(f)

        weights_path = Path(cfg_dict['weights_path'])
        if not weights_path.is_absolute():
            cfg_dict['weights_path'] = str((self.model_dir / weights_path).resolve())

        tok_path = Path(cfg_dict['flow_lm']['lookup_table']['tokenizer_path'])
        if not tok_path.is_absolute():
            cfg_dict['flow_lm']['lookup_table']['tokenizer_path'] = str((self.model_dir / tok_path).resolve())

        pydantic_config = Config(**cfg_dict)
        self.model = TTSModel._from_pydantic_config_with_weights(
            pydantic_config,
            temp=TTS_TEMPERATURE,
            sampler_decode_steps=1,
            noise_clamp=None,
            eos_threshold=TTS_EOS_THRESHOLD,
            origin=config_path,
        )

        self.tokenizer = self.model.flow_lm.conditioner.tokenizer.sp
        self.prompt_state = None
        self.set_voice(self.voice_path)

        self._executor = concurrent.futures.ThreadPoolExecutor(max_workers=1, thread_name_prefix='tts_worker')

    def _verify_files(self) -> None:
        required = ['farsi.yaml', 'model.safetensors', 'tokenizer.model']
        missing = [f for f in required if not (self.model_dir / f).exists()]
        if missing:
            raise FileNotFoundError(f'Missing required Pocket TTS files in {self.model_dir}: {", ".join(missing)}')

    def set_voice(self, voice_path: Path | str, max_sec: float = 5.0) -> None:
        resolved = Path(voice_path).expanduser().resolve()
        if not resolved.exists():
            if (self.model_dir / voice_path).exists():
                resolved = (self.model_dir / voice_path).resolve()
            else:
                raise FileNotFoundError(f'Voice prompt file not found: {voice_path}')

        voice_file = str(resolved)
        try:
            ref_wav, _ = sphn.read(voice_file)
            self._ref_audio_24k = ref_wav[0].astype(np.float32)
        except Exception:
            self._ref_audio_24k = None

        if max_sec > 0:
            try:
                wav, sr = sphn.read(voice_file)
                keep_samples = int(max_sec * sr)
                if wav.shape[-1] > keep_samples:
                    temp_dir = Path(tempfile.mkdtemp())
                    trimmed_path = temp_dir / 'voice_prompt.wav'
                    sphn.write_wav(str(trimmed_path), wav.mean(axis=0)[:keep_samples].astype('float32'), int(sr))
                    voice_file = str(trimmed_path)
            except Exception:
                pass

        self.prompt_state = self.model.get_state_for_audio_prompt(voice_file)

    def check_prompt_leakage(self, audio_24k: np.ndarray, threshold: float = 0.15) -> bool:
        """Detect whether synthesized audio erroneously repeated the reference voice prompt."""
        if self._ref_audio_24k is None or len(audio_24k) < 2400 or len(self._ref_audio_24k) < 2400:
            return False
        try:
            from scipy.signal import correlate

            a = (audio_24k - audio_24k.mean()) / (audio_24k.std() + 1e-6)
            r = (self._ref_audio_24k - self._ref_audio_24k.mean()) / (
                self._ref_audio_24k.std() * len(self._ref_audio_24k) + 1e-6
            )
            if len(a) < len(r):
                corr = correlate(r, a, mode='valid')
            else:
                corr = correlate(a, r, mode='valid')
            return float(corr.max()) > threshold
        except Exception:
            return False

    def synthesize_sentence_to_16k_pcm(self, text: str) -> bytes:
        """Synthesize text, downsample to 16kHz, and return 16-bit PCM bytes."""
        normalized = normalize_persian_text(text)
        if not normalized:
            return b''

        chunks = split_text(
            normalized,
            count_tokens=lambda s: len(self.tokenizer.encode(s)),
            max_tokens=TTS_MAX_TOKENS,
            min_tokens=TTS_MIN_TOKENS,
        )
        if not chunks:
            return b''

        pause_samples = int(TTS_SAMPLE_RATE * TTS_CHUNK_PAUSE_SECONDS)
        pause = np.zeros(pause_samples, dtype=np.float32)

        join_samples = int(TTS_SAMPLE_RATE * TTS_JOIN_SECONDS)
        join = np.zeros(join_samples, dtype=np.float32)

        audio_parts: list[np.ndarray] = []

        for index, chunk in enumerate(chunks):
            # If isolated chunk is under 7 tokens, pad with safe conversational context to prevent model EOS collapse
            tok_count = len(self.tokenizer.encode(chunk))
            chunk_to_gen = chunk
            if tok_count < 7:
                chunk_to_gen = f'بسیار خب، {chunk}'

            raw_audio = self.model.generate_audio(self.prompt_state, chunk_to_gen, frames_after_eos=0)
            chunk_audio = np.asarray(
                raw_audio.cpu().numpy() if hasattr(raw_audio, 'cpu') else raw_audio,
                dtype=np.float32,
            ).reshape(-1)

            # Safeguard: if audio leaked the reference prompt voice, regenerate with safe prefix or discard
            if self.check_prompt_leakage(chunk_audio):
                print(f"[!] Warning: Prompt voice leakage detected on '{chunk}'. Regenerating with safe context...")
                safe_prompt = f'بسیار خب، {chunk}'
                raw_retry = self.model.generate_audio(self.prompt_state, safe_prompt, frames_after_eos=0)
                retry_audio = np.asarray(
                    raw_retry.cpu().numpy() if hasattr(raw_retry, 'cpu') else raw_retry,
                    dtype=np.float32,
                ).reshape(-1)
                if not self.check_prompt_leakage(retry_audio):
                    chunk_audio = retry_audio
                else:
                    print(f"[-] Dropping corrupted prompt audio for chunk '{chunk}'.")
                    continue

            chunk_audio = trim_silence(chunk_audio, TTS_SAMPLE_RATE)

            fade_len = min(240, len(chunk_audio))
            if fade_len > 0:
                chunk_audio[-fade_len:] *= np.linspace(1.0, 0.0, fade_len, dtype=np.float32)

            audio_parts.append(chunk_audio)

            if index < len(chunks) - 1:
                if chunk.rstrip().endswith(BREAK_CHARS):
                    audio_parts.append(pause)
                else:
                    audio_parts.append(join)

        if not audio_parts:
            return b''

        full_24k = np.concatenate(audio_parts)
        full_16k = resample_24k_to_16k(full_24k)
        if TTS_NORMALIZE_AUDIO:
            full_16k = normalize_speech_audio(
                full_16k,
                target_speech_rms=TTS_TARGET_SPEECH_RMS,
                max_peak=TTS_MAX_PEAK,
            )
        return float_to_pcm16_bytes(full_16k)

    async def async_synthesize_sentence(self, text: str) -> bytes:
        """Asynchronously synthesize a sentence into 16kHz PCM bytes."""
        loop = asyncio.get_running_loop()
        return await loop.run_in_executor(self._executor, self.synthesize_sentence_to_16k_pcm, text)


def stream_sentences(token_generator: Iterable[str], min_words: int = 4) -> Generator[str, None, None]:
    """Buffer tokens and yield complete sentences as punctuation marks arrive, avoiding tiny 1-2 word fragments."""
    delimiters = {'.', '!', '؟', '?', '\n'}
    buffer = ''
    for token in token_generator:
        buffer += token
        found_idx = -1
        for i, ch in enumerate(buffer):
            if ch in delimiters:
                candidate = buffer[: i + 1].strip()
                if len(candidate.split()) >= min_words:
                    found_idx = i
                    break

        if found_idx != -1:
            sentence = buffer[: found_idx + 1].strip()
            buffer = buffer[found_idx + 1 :]
            if sentence:
                yield sentence

    if buffer.strip():
        yield buffer.strip()
