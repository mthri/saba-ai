"""Unit tests for Sherpa-ONNX streaming Wake-Word (KWS) detection."""
from __future__ import annotations

import unittest
from pathlib import Path

import numpy as np

from server.ai.kws import SHERPA_ONNX_AVAILABLE, SherpaKWSManager
from server.config import KWS_MODEL_DIR


@unittest.skipUnless(SHERPA_ONNX_AVAILABLE, 'sherpa-onnx is not installed')
class TestSherpaKWS(unittest.TestCase):
    """Test suite verifying Sherpa-ONNX streaming KWS engine."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.kws = SherpaKWSManager(model_dir=KWS_MODEL_DIR, num_threads=1)

    def test_kws_initialization(self) -> None:
        self.assertTrue(self.kws.is_available)
        self.assertIsNotNone(self.kws.spotter)
        self.assertIsNotNone(self.kws._stream)

    def test_empty_pcm_returns_none(self) -> None:
        self.assertIsNone(self.kws.process_pcm_chunk(b''))

    def test_silence_returns_none(self) -> None:
        self.kws.reset()
        silence_chunk = b'\x00' * 1024  # 512 samples of 16-bit PCM silence
        for _ in range(10):
            res = self.kws.process_pcm_chunk(silence_chunk)
            self.assertIsNone(res)

    def test_detect_saba_wake_word(self) -> None:
        self.kws.reset()
        from server.ai.tts import PocketTTSEngine

        tts = PocketTTSEngine()
        pcm = tts.synthesize_sentence_to_16k_pcm('صبا')
        pcm_with_silence = pcm + (b'\x00' * 16000)

        chunk_size = 1024
        detected = []
        for i in range(0, len(pcm_with_silence), chunk_size):
            chunk = pcm_with_silence[i : i + chunk_size]
            res = self.kws.process_pcm_chunk(chunk)
            if res:
                detected.append(res)

        self.assertIn('Saba', detected)


if __name__ == '__main__':
    unittest.main()
