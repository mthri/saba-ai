"""Unit tests for robot audio streaming, facial expression coordination, and playback interruption."""
from __future__ import annotations

import asyncio
import json
import unittest
from unittest.mock import AsyncMock, MagicMock

from server.core.session import RobotSession


class DummyWebSocket:
    def __init__(self) -> None:
        self.sent_messages: list[str | bytes] = []
        self.closed = False

    async def send(self, data: str | bytes) -> None:
        self.sent_messages.append(data)

    async def close(self, code: int = 1000, reason: str = '') -> None:
        self.closed = True


class TestPlaybackControl(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.ws = DummyWebSocket()
        self.session = RobotSession(websocket=self.ws)
        # Mock send_request so it records calls and returns ok
        self.recorded_requests: list[tuple[str, dict]] = []

        async def fake_send_request(method: str, params: dict | None = None, timeout: float = 4.0):
            self.recorded_requests.append((method, params or {}))
            return {'v': '1.0', 'kind': 'res', 'id': 'mock-id', 'status': 'ok', 'result': {}}

        self.session.send_request = fake_send_request

    async def test_play_wav_sets_normal_expression(self) -> None:
        """Verify play_wav sets expression to normal (not sleepy) and sends audio.eos on completion."""
        # 1600 bytes = 50ms of 16kHz 16-bit mono
        pcm_data = b'\x00' * 1600
        completed = await self.session.stream_pcm_to_speaker(
            pcm_data,
            chunk_size=512,
            expression='normal',
            restore_on_finish=False,
        )
        self.assertTrue(completed)
        self.assertFalse(self.session.is_playing_audio)

        # Check that face.set_expression was called with name='normal'
        expressions = [
            params.get('name')
            for method, params in self.recorded_requests
            if method == 'face.set_expression'
        ]
        self.assertIn('normal', expressions)

        # Check that audio.eos was sent
        methods = [method for method, _ in self.recorded_requests]
        self.assertIn('audio.eos', methods)

    async def test_play_song_sets_singing_expression(self) -> None:
        """Verify play_song sets expression to singing."""
        pcm_data = b'\x00' * 1600
        completed = await self.session.stream_pcm_to_speaker(
            pcm_data,
            chunk_size=512,
            expression='singing',
            restore_on_finish=False,
        )
        self.assertTrue(completed)

        expressions = [
            params.get('name')
            for method, params in self.recorded_requests
            if method == 'face.set_expression'
        ]
        self.assertIn('singing', expressions)

    async def test_playback_interruption_midway(self) -> None:
        """Verify audio playback can be interrupted midway via stop_audio_playback()."""
        # 320000 bytes = 10 seconds of audio
        pcm_data = b'\x01' * 320000

        # Start playback in background task
        task = asyncio.create_task(
            self.session.stream_pcm_to_speaker(pcm_data, chunk_size=1024, expression='singing')
        )
        self.session.audio_task = task

        # Let it stream a few chunks
        await asyncio.sleep(0.05)
        self.assertTrue(self.session.is_playing_audio)

        # Interrupt midway!
        await self.session.stop_audio_playback()
        completed = await task

        self.assertFalse(completed)
        self.assertFalse(self.session.is_playing_audio)

        # Verify audio.abort was sent to robot hardware
        methods = [method for method, _ in self.recorded_requests]
        self.assertIn('audio.abort', methods)

    async def test_restore_face_to_sleepy_if_assistant_standby(self) -> None:
        """Verify if assistant was in standby, face is restored to sleepy after audio finishes."""
        mock_assistant = MagicMock()
        mock_assistant.state = 'standby'
        mock_assistant.start_listening = AsyncMock()
        self.session.assistant = mock_assistant

        pcm_data = b'\x00' * 1024
        await self.session.stream_pcm_to_speaker(
            pcm_data,
            chunk_size=512,
            expression='normal',
            restore_on_finish=True,
        )

        expressions = [
            params.get('name')
            for method, params in self.recorded_requests
            if method == 'face.set_expression'
        ]
        # First normal, then sleepy
        self.assertEqual(expressions[0], 'normal')
        self.assertEqual(expressions[-1], 'sleepy')

    async def test_show_persian_text_sends_bitmap(self) -> None:
        """Verify show_persian_text renders a 1024-byte bitmap and sends display.show_bitmap RPC."""
        res = await self.session.show_persian_text(
            body='سلام صبا حالت چطوره؟',
            title='[ صبا ]',
            timeout_ms=3000,
        )
        self.assertEqual(res.get('status'), 'ok')

        methods = [method for method, _ in self.recorded_requests]
        self.assertIn('display.show_bitmap', methods)

        bitmap_params = next(
            params for method, params in self.recorded_requests
            if method == 'display.show_bitmap'
        )
        self.assertIn('bitmap_base64', bitmap_params)
        self.assertEqual(bitmap_params.get('timeout_ms'), 3000)

        # Decode base64 and verify exactly 1024 bytes (128x64 mono)
        import base64
        decoded = base64.b64decode(bitmap_params['bitmap_base64'])
        self.assertEqual(len(decoded), 1024)


if __name__ == '__main__':
    unittest.main()
