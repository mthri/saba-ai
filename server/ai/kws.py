"""Offline Streaming Keyword Spotter (Wake-Word) Engine powered by Sherpa-ONNX."""
from __future__ import annotations

import logging
from pathlib import Path
from typing import TYPE_CHECKING

import numpy as np

from server.config import (
    KWS_MAX_ACTIVE_PATHS,
    KWS_MODEL_DIR,
    KWS_NUM_THREADS,
    KWS_SCORE,
    KWS_THRESHOLD,
    SAMPLE_RATE,
)

logger = logging.getLogger(__name__)

try:
    import sherpa_onnx

    SHERPA_ONNX_AVAILABLE = True
except ImportError:
    sherpa_onnx = None  # type: ignore[assignment]
    SHERPA_ONNX_AVAILABLE = False


class SherpaKWSManager:
    """Manages real-time streaming wake-word detection using Sherpa-ONNX Zipformer int8 model."""

    def __init__(
        self,
        model_dir: Path = KWS_MODEL_DIR,
        sample_rate: int = SAMPLE_RATE,
        score: float = KWS_SCORE,
        threshold: float = KWS_THRESHOLD,
        max_active_paths: int = KWS_MAX_ACTIVE_PATHS,
        num_threads: int = KWS_NUM_THREADS,
    ) -> None:
        self.model_dir = Path(model_dir).resolve()
        self.sample_rate = sample_rate
        self.score = score
        self.threshold = threshold
        self.max_active_paths = max_active_paths
        self.num_threads = num_threads

        self.spotter: sherpa_onnx.KeywordSpotter | None = None
        self._stream: sherpa_onnx.OnlineStream | None = None
        self._is_ready = False

        self._initialize()

    @property
    def is_available(self) -> bool:
        return self._is_ready and self.spotter is not None

    def _initialize(self) -> None:
        if not SHERPA_ONNX_AVAILABLE:
            logger.warning('[-] sherpa-onnx is not installed. Wake-word KWS will be unavailable.')
            return

        encoder_path = self.model_dir / 'encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx'
        decoder_path = self.model_dir / 'decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx'
        joiner_path = self.model_dir / 'joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx'
        tokens_path = self.model_dir / 'tokens.txt'
        keywords_path = self.model_dir / 'keywords.txt'

        required_files = [encoder_path, decoder_path, joiner_path, tokens_path, keywords_path]
        for f in required_files:
            if not f.exists():
                logger.warning(f'[-] KWS model file missing: {f}. Wake-word KWS will be unavailable.')
                return

        try:
            print(f'[*] Loading Sherpa-ONNX Wake-Word model from {self.model_dir.name}...')
            self.spotter = sherpa_onnx.KeywordSpotter(
                tokens=str(tokens_path),
                encoder=str(encoder_path),
                decoder=str(decoder_path),
                joiner=str(joiner_path),
                num_threads=self.num_threads,
                keywords_file=str(keywords_path),
                max_active_paths=self.max_active_paths,
                keywords_score=self.score,
                keywords_threshold=self.threshold,
                num_trailing_blanks=1,
            )
            self._stream = self.spotter.create_stream()
            self._is_ready = True
            print('[+] Sherpa-ONNX KWS Engine initialized successfully (Wake-word: "صبا" / "Saba").')
        except Exception as exc:
            logger.error(f'[-] Failed to initialize Sherpa-ONNX KeywordSpotter: {exc}')
            self.spotter = None
            self._stream = None
            self._is_ready = False

    def reset(self) -> None:
        """Reset the internal streaming state for a new wake-word detection session."""
        if self.spotter and self._stream:
            try:
                self.spotter.reset_stream(self._stream)
            except Exception:
                pass

    def process_pcm_chunk(self, pcm_bytes: bytes) -> str | None:
        """Process incoming 16-bit little-endian mono PCM chunk.

        Returns the detected keyword name (e.g. 'Saba') if spotted, else None.
        """
        if not self.is_available or not pcm_bytes or self.spotter is None or self._stream is None:
            return None

        try:
            samples = np.frombuffer(pcm_bytes, dtype=np.int16).astype(np.float32) / 32768.0
            self._stream.accept_waveform(self.sample_rate, samples)

            while self.spotter.is_ready(self._stream):
                self.spotter.decode_stream(self._stream)
                result = self.spotter.get_result(self._stream)
                detected = result.strip() if isinstance(result, str) else ''
                if detected:
                    self.spotter.reset_stream(self._stream)
                    return detected

            return None
        except Exception as exc:
            logger.debug(f'[-] Error in KWS process_pcm_chunk: {exc}')
            return None
