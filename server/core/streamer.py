"""Paced audio streamer with ESP32 MAX98357A jitter buffer flow control and barge-in."""
from __future__ import annotations

import asyncio
import time
from typing import TYPE_CHECKING

from server.config import (
    BYTES_PER_SECOND,
    SPK_CHUNK_SIZE,
    SPK_PREBUFFER_BYTES,
)

if TYPE_CHECKING:
    from server.core.session import RobotSession


class PacedAudioStreamer:
    """Manages paced streaming of 16kHz PCM audio to the robot speaker."""

    def __init__(self, session: RobotSession, chunk_size: int = SPK_CHUNK_SIZE) -> None:
        self.session = session
        self.chunk_size = chunk_size
        self._is_streaming = False
        self._abort_requested = False
        self._bytes_sent = 0
        self._stream_start_time = 0.0
        self._prefill_bytes = SPK_PREBUFFER_BYTES + 2048  # ~6KB pre-fill

    @property
    def is_streaming(self) -> bool:
        return self._is_streaming

    def reset(self) -> None:
        """Reset streamer state for a new conversational turn."""
        self._is_streaming = False
        self._abort_requested = False
        self._bytes_sent = 0
        self._stream_start_time = 0.0

    async def abort(self) -> None:
        """Request immediate speaker barge-in abort."""
        self._abort_requested = True
        self._is_streaming = False
        try:
            print('[AUDIO] Barge-in requested -> sending audio.abort to robot...')
            await self.session.send_request('audio.abort', timeout=2.0)
        except Exception as exc:
            print(f'[-] Warning: audio.abort RPC failed: {exc}')

    async def stream_pcm_bytes(self, pcm_bytes: bytes) -> None:
        """Stream a block of 16kHz 16-bit PCM bytes with strict rate limiting."""
        if not pcm_bytes or self._abort_requested:
            return

        if not self._is_streaming:
            self._is_streaming = True
            self._bytes_sent = 0
            self._stream_start_time = time.monotonic()

        total = len(pcm_bytes)
        for offset in range(0, total, self.chunk_size):
            if self._abort_requested:
                break

            chunk = pcm_bytes[offset : offset + self.chunk_size]

            # Enforce pacing based on total bytes transmitted
            if self._bytes_sent >= self._prefill_bytes:
                # Regular real-time pacing
                target_elapsed = (self._bytes_sent - self._prefill_bytes) / float(BYTES_PER_SECOND)
                now_elapsed = time.monotonic() - (self._stream_start_time + 0.15)
                delay = target_elapsed - now_elapsed
                if delay > 0.002:
                    await asyncio.sleep(min(delay, 0.1))
            else:
                # Pre-filling initial ESP32 jitter buffer
                await asyncio.sleep(0.005)

            try:
                await self.session.websocket.send(chunk)
                self._bytes_sent += len(chunk)
            except Exception as exc:
                print(f'[-] Error sending audio chunk to robot: {exc}')
                self._abort_requested = True
                break

    async def finish_playback(self) -> None:
        """Inform robot of end-of-stream and await buffer drainage."""
        if not self._is_streaming or self._abort_requested:
            self.reset()
            return

        try:
            print('[AUDIO TX] Stream complete -> sending audio.eos to robot...')
            await self.session.send_request('audio.eos', timeout=3.0)

            # Calculate remaining audio buffered on ESP32 to wait for playback completion
            # The ESP32 holds at most SPK_RING_BYTES (24KB = 768ms)
            estimated_buffered = min(self._bytes_sent, 8192)
            drain_time = (estimated_buffered / float(BYTES_PER_SECOND)) + 0.15
            await asyncio.sleep(drain_time)

        except Exception as exc:
            print(f'[-] Warning: audio.eos failed or timed out: {exc}')
        finally:
            self.reset()
