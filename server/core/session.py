"""Robot session state management and RDP v1.0 protocol RPC correlation."""
from __future__ import annotations

import asyncio
import base64
import json
import uuid
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any

from server.config import (
    BYTES_PER_SECOND,
    RDP_VERSION,
    SPK_CHUNK_SIZE,
)
from server.utils.display import render_persian_text_to_bitmap

if TYPE_CHECKING:
    from server.ai.assistant import RoboDeskAssistant


@dataclass
class RobotSession:
    """Represents an active ESP32 robot connection over WebSocket."""

    websocket: Any
    device_id: str = 'unknown'
    firmware: str = 'unknown'
    hardware: dict[str, Any] = field(default_factory=dict)
    pending_requests: dict[str, asyncio.Future[dict[str, Any]]] = field(default_factory=dict)
    recording_buffer: bytearray = field(default_factory=bytearray)
    is_recording: bool = False
    rx_audio_bytes: int = 0
    last_telemetry: dict[str, Any] = field(default_factory=dict)
    show_telemetry_logs: bool = False
    assistant: RoboDeskAssistant | None = None
    audio_task: asyncio.Task[None] | None = None
    is_playing_audio: bool = False
    _playback_abort_event: asyncio.Event = field(default_factory=asyncio.Event)

    async def send_request(
        self,
        method: str,
        params: dict[str, Any] | None = None,
        timeout: float = 4.0,
    ) -> dict[str, Any]:
        """Send an RDP v1.0 RPC request and await the correlated response."""
        req_id = f'req-{uuid.uuid4().hex[:8]}'
        envelope = {
            'v': RDP_VERSION,
            'kind': 'req',
            'id': req_id,
            'method': method,
            'params': params or {},
        }

        loop = asyncio.get_running_loop()
        future: asyncio.Future[dict[str, Any]] = loop.create_future()
        self.pending_requests[req_id] = future

        try:
            await self.websocket.send(json.dumps(envelope))
            return await asyncio.wait_for(future, timeout=timeout)
        finally:
            self.pending_requests.pop(req_id, None)

    async def show_screen_bitmap(
        self,
        bitmap_bytes: bytes,
        timeout_ms: int = 5000,
    ) -> dict[str, Any]:
        """Send a 128x64 1-bit monochrome bitmap (1024 bytes) to be displayed on the robot OLED."""
        b64 = base64.b64encode(bitmap_bytes).decode('ascii')
        return await self.send_request(
            'display.show_bitmap',
            {
                'bitmap_base64': b64,
                'timeout_ms': timeout_ms,
            },
        )

    async def show_persian_text(
        self,
        body: str,
        title: str = '',
        font_size: int = 11,
        timeout_ms: int = 5000,
    ) -> dict[str, Any]:
        """Render Persian/Arabic text to a 128x64 bitmap in Python and display on robot OLED."""
        bmp = render_persian_text_to_bitmap(
            body=body,
            title=title,
            font_size=font_size,
        )
        return await self.show_screen_bitmap(bmp, timeout_ms=timeout_ms)

    async def stop_audio_playback(self) -> None:
        """Immediately stop any active audio streaming or speech playback."""
        self._playback_abort_event.set()
        if self.audio_task and not self.audio_task.done() and self.audio_task != asyncio.current_task():
            try:
                await asyncio.wait_for(asyncio.shield(self.audio_task), timeout=0.08)
            except (asyncio.TimeoutError, asyncio.CancelledError):
                self.audio_task.cancel()
                try:
                    await self.audio_task
                except (asyncio.CancelledError, Exception):
                    pass
            self.audio_task = None

        if self.assistant:
            try:
                await self.assistant.abort()
            except Exception:
                pass
        else:
            try:
                await self.send_request('audio.abort', timeout=2.0)
            except Exception:
                pass

        self.is_playing_audio = False

    async def stream_pcm_to_speaker(
        self,
        pcm_data: bytes,
        chunk_size: int = SPK_CHUNK_SIZE,
        expression: str = 'normal',
        restore_on_finish: bool = True,
    ) -> bool:
        """Stream raw 16kHz 16-bit mono PCM bytes to the robot speaker with paced flow control, expressions, and barge-in."""
        # Stop any previous streaming task if active
        if self.is_playing_audio:
            self._playback_abort_event.set()
            if self.audio_task and self.audio_task != asyncio.current_task():
                self.audio_task.cancel()
                try:
                    await self.audio_task
                except (asyncio.CancelledError, Exception):
                    pass
                self.audio_task = None

        self._playback_abort_event.clear()
        self.is_playing_audio = True

        prev_assistant_state = None
        if self.assistant:
            prev_assistant_state = self.assistant.state
            try:
                await self.assistant.stop_listening()
            except Exception:
                pass

        # Switch face expression (e.g. normal or singing)
        if expression:
            try:
                await self.send_request('face.set_expression', {'name': expression, 'duration_ms': 0})
            except Exception as exc:
                print(f'[-] Warning setting face expression: {exc}')

        chunk_duration = chunk_size / float(BYTES_PER_SECOND)
        total_bytes = len(pcm_data)
        print(f'[AUDIO TX] Streaming {total_bytes} bytes ({total_bytes / BYTES_PER_SECOND:.2f}s) to speaker (Face: {expression})...')

        try:
            # Initial burst to fill ESP32 pre-buffer (4KB - 6KB)
            prefill = min(total_bytes, 6144)
            for offset in range(0, prefill, chunk_size):
                if self._playback_abort_event.is_set():
                    break
                chunk = pcm_data[offset : offset + chunk_size]
                await self.websocket.send(chunk)
                await asyncio.sleep(0.005)

            # Steady-state pacing for remaining audio
            for offset in range(prefill, total_bytes, chunk_size):
                if self._playback_abort_event.is_set():
                    break
                chunk = pcm_data[offset : offset + chunk_size]
                await self.websocket.send(chunk)
                await asyncio.sleep(chunk_duration * 0.95)

            if self._playback_abort_event.is_set():
                print('[AUDIO TX] Playback stopped by user.')
                try:
                    await self.send_request('audio.abort', timeout=2.0)
                except Exception:
                    pass
                return False

            await self.send_request('audio.eos', timeout=3.0)
            buffered_time = min(total_bytes, 8192) / float(BYTES_PER_SECOND) + 0.15
            await asyncio.sleep(buffered_time)
            print('[AUDIO TX] Stream complete (audio.eos sent).')
            return True

        except asyncio.CancelledError:
            print('[AUDIO TX] Playback cancelled.')
            try:
                await self.send_request('audio.abort', timeout=1.5)
            except Exception:
                pass
            return False

        except Exception as exc:
            print(f'[AUDIO TX] Stream error: {exc}')
            try:
                await self.send_request('audio.abort', timeout=1.5)
            except Exception:
                pass
            return False

        finally:
            self.is_playing_audio = False
            if restore_on_finish:
                try:
                    if self.assistant:
                        if prev_assistant_state == 'standby':
                            await self.send_request('face.set_expression', {'name': 'sleepy', 'duration_ms': 0})
                            await self.assistant.start_listening(mode='standby')
                        else:
                            await self.send_request('face.set_expression', {'name': 'normal', 'duration_ms': 0})
                            await self.assistant.start_listening(mode='active_listening')
                    else:
                        await self.send_request('face.set_expression', {'name': 'normal', 'duration_ms': 0})
                except Exception:
                    pass
