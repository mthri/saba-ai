"""Audio processing utilities: format conversions, resampling, tone/melody generation, and WAV I/O."""
from __future__ import annotations

import math
import struct
import wave
from pathlib import Path

import numpy as np
import scipy.signal

from server.config import (
    CHANNELS,
    SAMPLE_RATE,
    SAMPLE_WIDTH,
    TTS_SAMPLE_RATE,
)


def resample_24k_to_16k(audio_24k: np.ndarray) -> np.ndarray:
    """Resample float32 audio from 24,000 Hz to 16,000 Hz (ratio 2/3)."""
    if len(audio_24k) == 0:
        return np.zeros(0, dtype=np.float32)
    return scipy.signal.resample_poly(audio_24k, 2, 3).astype(np.float32)


def float_to_pcm16_bytes(audio: np.ndarray) -> bytes:
    """Convert float32 waveform [-1.0, 1.0] to 16-bit signed little-endian PCM bytes."""
    clipped = np.clip(audio, -1.0, 1.0)
    return (clipped * 32767.0).astype('<i2').tobytes()


def trim_silence(
    audio: np.ndarray,
    sample_rate: int = TTS_SAMPLE_RATE,
    threshold_db: float = -38.0,
) -> np.ndarray:
    """Trim trailing silence from synthesized audio."""
    chunk_size = int(sample_rate * 0.02)
    if len(audio) < chunk_size:
        return audio

    threshold = 10.0 ** (threshold_db / 20.0)
    end = len(audio)

    while end >= chunk_size:
        rms = float(np.sqrt(np.mean(audio[end - chunk_size:end] ** 2)))
        if rms > threshold:
            break
        end -= chunk_size

    margin = int(sample_rate * 0.06)
    cutoff = min(end + margin, len(audio))
    return audio[:cutoff]


def generate_melody_pcm(sample_rate: int = SAMPLE_RATE) -> bytes:
    """Synthesize a 16kHz 16-bit mono test melody."""
    notes = [(523.25, 0.18), (659.25, 0.18), (783.99, 0.18), (1046.50, 0.35)]
    samples = bytearray()

    for freq, duration in notes:
        total_frames = int(sample_rate * duration)
        fade_frames = int(sample_rate * 0.01)
        for i in range(total_frames):
            env = 1.0
            if i < fade_frames:
                env = i / fade_frames
            elif total_frames - i < fade_frames:
                env = (total_frames - i) / fade_frames

            val = int(math.sin(2.0 * math.pi * freq * (i / sample_rate)) * 12000 * env)
            samples.extend(struct.pack('<h', val))

    return bytes(samples)


def save_pcm_to_wav(
    filepath: Path | str,
    pcm_bytes: bytes,
    sample_rate: int = SAMPLE_RATE,
    channels: int = CHANNELS,
    sample_width: int = SAMPLE_WIDTH,
) -> None:
    """Save raw 16-bit mono PCM data into a standard WAV file."""
    with wave.open(str(filepath), 'wb') as wf:
        wf.setnchannels(channels)
        wf.setsampwidth(sample_width)
        wf.setframerate(sample_rate)
        wf.writeframes(pcm_bytes)


def load_pcm_from_wav(filepath: Path | str, expected_sample_rate: int = SAMPLE_RATE) -> bytes:
    """Load raw PCM frames from a WAV file, automatically converting/resampling to 16-bit mono PCM if needed."""
    try:
        with wave.open(str(filepath), 'rb') as wf:
            if (
                wf.getnchannels() == 1
                and wf.getsampwidth() == 2
                and wf.getframerate() == expected_sample_rate
            ):
                return wf.readframes(wf.getnframes())
    except Exception:
        pass

    import soundfile as sf

    data, sr = sf.read(str(filepath), dtype='float32')
    if data.ndim > 1:
        data = np.mean(data, axis=1)

    if sr != expected_sample_rate:
        num_target_samples = int(round(len(data) * float(expected_sample_rate) / float(sr)))
        data = scipy.signal.resample(data, num_target_samples).astype(np.float32)

    return float_to_pcm16_bytes(data)
