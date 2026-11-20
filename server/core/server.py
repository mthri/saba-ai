"""RoboDesk RDP v1.0 WebSocket Host Server."""
from __future__ import annotations

import asyncio
import json
from typing import TYPE_CHECKING, Any

import websockets

from server.config import HOST, PORT, WS_PATH
from server.core.session import RobotSession

if TYPE_CHECKING:
    from server.ai.asr import ShenavaASRManager
    from server.ai.assistant import RoboDeskAssistant
    from server.ai.tts import PocketTTSEngine


class RoboDeskHostServer:
    """Core RDP v1.0 WebSocket Server managing connected robot sessions and AI pipelines."""

    def __init__(
        self,
        host: str = HOST,
        port: int = PORT,
        asr_mgr: ShenavaASRManager | None = None,
        tts_engine: PocketTTSEngine | None = None,
    ) -> None:
        self.host = host
        self.port = port
        self.asr_mgr = asr_mgr
        self.tts_engine = tts_engine
        self.active_session: RobotSession | None = None
        self.ws_server: Any = None

    async def shutdown(self) -> None:
        """Gracefully shut down active robot sessions and the WebSocket server."""
        if self.active_session:
            try:
                await self.active_session.stop_audio_playback()
            except Exception:
                pass
            if self.active_session.assistant:
                try:
                    await self.active_session.assistant.abort()
                except Exception:
                    pass
            if self.active_session.websocket:
                try:
                    await self.active_session.websocket.close(code=1000, reason='Host shutting down')
                except Exception:
                    pass

        if self.ws_server:
            self.ws_server.close()
            if hasattr(self.ws_server, 'connections'):
                for conn in list(self.ws_server.connections):
                    try:
                        res = conn.close()
                        if asyncio.iscoroutine(res):
                            await res
                    except Exception:
                        pass

    async def handle_text_frame(self, session: RobotSession, raw_text: str) -> None:
        """Process incoming JSON text frames (events and RPC responses)."""
        try:
            msg = json.loads(raw_text)
        except json.JSONDecodeError:
            print(f'[!] Malformed JSON received: {raw_text}')
            return

        kind = msg.get('kind')

        if kind == 'res':
            req_id = msg.get('id', '')
            future = session.pending_requests.get(req_id)
            if future and not future.done():
                future.set_result(msg)
            return

        if kind == 'event':
            topic = msg.get('topic', '')
            data = msg.get('data', {})

            if topic == 'system.hello':
                session.device_id = data.get('device_id', 'unknown')
                session.firmware = data.get('firmware', 'unknown')
                session.hardware = data.get('hardware', {})
                print(
                    f'\n[EVENT] system.hello -> ID: {session.device_id} | '
                    f'FW: {session.firmware} | HW: {json.dumps(session.hardware)}'
                )
                if session.assistant:
                    asyncio.create_task(session.assistant.initialize())

            elif topic == 'sensor.telemetry':
                session.last_telemetry = data
                if session.show_telemetry_logs:
                    print(
                        f'[TELEMETRY] Heap: {data.get("free_heap")}B | '
                        f'RSSI: {data.get("wifi_rssi")}dBm | '
                        f'SpkBuf: {data.get("spk_buffered_bytes")}B | '
                        f'MicLvl: {data.get("mic_level")} | '
                        f'SpkLvl: {data.get("spk_level")}'
                    )

            elif topic == 'sensor.trigger':
                print(f'\n[EVENT] sensor.trigger -> {json.dumps(data)}')
                sensor = data.get('sensor')
                trigger_type = data.get('type')
                intensity = float(data.get('intensity', 1.0))
                if sensor == 'mpu6050' and trigger_type == 'shake' and session.assistant:
                    asyncio.create_task(session.assistant.handle_shake_trigger(intensity))
            else:
                print(f'\n[EVENT] {topic} -> {json.dumps(data)}')

    async def handle_binary_frame(self, session: RobotSession, pcm_chunk: bytes) -> None:
        """Process incoming raw PCM microphone frames from the robot."""
        session.rx_audio_bytes += len(pcm_chunk)
        if session.is_recording:
            session.recording_buffer.extend(pcm_chunk)

        # Feed real-time streaming VAD and assistant speech pipeline
        if session.assistant:
            session.assistant.feed_mic_audio(pcm_chunk)

    async def ws_handler(self, websocket: Any, path: str = '') -> None:
        """Handle client connections across legacy and modern websockets libraries."""
        actual_path = path or getattr(websocket, 'path', None)
        if not actual_path and hasattr(websocket, 'request'):
            actual_path = getattr(websocket.request, 'path', None)
        actual_path = actual_path or '/'

        if actual_path not in (WS_PATH, '/ws/robot', '/'):
            await websocket.close(code=1008, reason='Invalid path')
            return

        client_ip = 'unknown'
        if hasattr(websocket, 'remote_address') and websocket.remote_address:
            client_ip = websocket.remote_address[0]

        session = RobotSession(websocket=websocket)
        self.active_session = session

        # Attach interactive assistant coordinator
        if self.asr_mgr and self.tts_engine:
            from server.ai.assistant import RoboDeskAssistant

            session.assistant = RoboDeskAssistant(
                session=session,
                asr_mgr=self.asr_mgr,
                tts_engine=self.tts_engine,
            )

        print(f'\n[+] Robot connected from {client_ip} (path: {actual_path})')

        try:
            async for message in websocket:
                if isinstance(message, str):
                    await self.handle_text_frame(session, message)
                elif isinstance(message, bytes):
                    await self.handle_binary_frame(session, message)
        except websockets.ConnectionClosed:
            pass
        finally:
            for fut in session.pending_requests.values():
                if not fut.done():
                    fut.cancel()
            if self.active_session is session:
                self.active_session = None
            print(f'\n[-] Robot disconnected: {client_ip}')
