"""Unit tests for audio processing and WAV file utilities."""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import numpy as np

from server.utils.audio import (
    float_to_pcm16_bytes,
    generate_melody_pcm,
    load_pcm_from_wav,
    normalize_speech_audio,
    resample_24k_to_16k,
    save_pcm_to_wav,
    trim_silence,
)


class TestAudioUtils(unittest.TestCase):
    """Test suite verifying audio operations and conversion routines."""

    def test_float_to_pcm16_bytes(self) -> None:
        samples = np.array([0.0, 1.0, -1.0, 0.5, -0.5], dtype=np.float32)
        pcm = float_to_pcm16_bytes(samples)
        self.assertEqual(len(pcm), len(samples) * 2)

        # Unpack and verify bounds
        int16_arr = np.frombuffer(pcm, dtype=np.int16)
        self.assertEqual(int16_arr[0], 0)
        self.assertEqual(int16_arr[1], 32767)
        self.assertEqual(int16_arr[2], -32767)

    def test_resample_24k_to_16k(self) -> None:
        # 1 second of 24kHz audio = 24000 samples
        audio_24k = np.sin(2 * np.pi * 440 * np.linspace(0, 1, 24000, dtype=np.float32))
        audio_16k = resample_24k_to_16k(audio_24k)
        # Should resample to 16000 samples (ratio 2/3)
        self.assertEqual(len(audio_16k), 16000)

    def test_trim_silence(self) -> None:
        tone = np.ones(2400, dtype=np.float32) * 0.5
        silence = np.zeros(4800, dtype=np.float32)
        audio = np.concatenate([tone, silence])
        trimmed = trim_silence(audio, sample_rate=24000)
        self.assertLess(len(trimmed), len(audio))

    def test_generate_melody_pcm(self) -> None:
        pcm = generate_melody_pcm()
        self.assertGreater(len(pcm), 0)
        # Verify 16-bit alignment
        self.assertEqual(len(pcm) % 2, 0)

    def test_wav_roundtrip(self) -> None:
        original_pcm = generate_melody_pcm()
        with tempfile.NamedTemporaryFile(suffix='.wav', delete=False) as tmp:
            tmp_path = Path(tmp.name)

        try:
            save_pcm_to_wav(tmp_path, original_pcm, sample_rate=16000, channels=1, sample_width=2)
            loaded_pcm = load_pcm_from_wav(tmp_path, expected_sample_rate=16000)
            self.assertEqual(original_pcm, loaded_pcm)
        finally:
            if tmp_path.exists():
                tmp_path.unlink()

    def test_load_pcm_from_wav_resample_stereo(self) -> None:
        import soundfile as sf
        # Generate 44.1kHz stereo test signal (1 second)
        sr = 44100
        t = np.linspace(0, 1, sr, endpoint=False, dtype=np.float32)
        stereo_signal = np.stack([np.sin(2 * np.pi * 440 * t), np.sin(2 * np.pi * 880 * t)], axis=-1)

        with tempfile.NamedTemporaryFile(suffix='.wav', delete=False) as tmp:
            tmp_path = Path(tmp.name)

        try:
            sf.write(str(tmp_path), stereo_signal, sr)
            pcm = load_pcm_from_wav(tmp_path, expected_sample_rate=16000)
            self.assertGreater(len(pcm), 0)
            # 1 second of 16kHz 16-bit mono = 16000 samples * 2 bytes = 32000 bytes
            self.assertEqual(len(pcm), 32000)
        finally:
            if tmp_path.exists():
                tmp_path.unlink()

    def test_normalize_speech_audio(self) -> None:
        # 1. Quiet audio gets boosted towards target RMS
        t = np.linspace(0, 1, 16000, endpoint=False, dtype=np.float32)
        quiet_signal = np.sin(2 * np.pi * 300 * t) * 0.05
        norm = normalize_speech_audio(quiet_signal, target_speech_rms=0.22, max_peak=0.95)
        self.assertGreater(float(np.max(np.abs(norm))), float(np.max(np.abs(quiet_signal))))
        self.assertLessEqual(float(np.max(np.abs(norm))), 0.95)

        # 2. Loud signal with spike is smoothly limited under max_peak
        spike_signal = np.sin(2 * np.pi * 300 * t) * 0.8
        spike_signal[500] = 1.5
        norm_spike = normalize_speech_audio(spike_signal, target_speech_rms=0.22, max_peak=0.95)
        self.assertLessEqual(float(np.max(np.abs(norm_spike))), 0.95)

        # 3. Empty and near-zero signals
        empty = normalize_speech_audio(np.zeros(0, dtype=np.float32))
        self.assertEqual(len(empty), 0)

        silence = normalize_speech_audio(np.full(100, 1e-7, dtype=np.float32))
        self.assertLess(float(np.max(np.abs(silence))), 1e-4)


if __name__ == '__main__':
    unittest.main()
