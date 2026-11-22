"""RoboDesk RDP v1.0 Server & Interactive AI Host Entry Point."""
from __future__ import annotations

import asyncio
import sys
from pathlib import Path

import websockets

# Ensure project root is in sys.path
_ROOT = Path(__file__).resolve().parent.parent
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from server.ai.asr import ShenavaASRManager
from server.ai.llm import warmup_llm
from server.ai.tts import PocketTTSEngine
from server.cli.console import cli_loop
from server.config import HOST, PORT, WS_PATH
from server.core.server import RoboDeskHostServer


async def main() -> None:
    print('===========================================================')
    print('        RoboDesk RDP v1.0 Interactive AI Server           ')
    print('===========================================================')

    # Preload and warm up AI models
    print('[*] Loading Sherpa-ONNX Wake-Word (KWS) engine...')
    from server.ai.kws import SherpaKWSManager
    _ = SherpaKWSManager()

    print('[*] Loading NeMo ASR and Silero VAD models...')
    asr_mgr = ShenavaASRManager()

    print('[*] Loading Pocket TTS speech synthesis model...')
    tts_engine = PocketTTSEngine()

    print('[*] Pre-warming LLM model...')
    warmup_llm()

    host_server = RoboDeskHostServer(HOST, PORT, asr_mgr=asr_mgr, tts_engine=tts_engine)
    server = await websockets.serve(host_server.ws_handler, HOST, PORT, max_size=2**20)
    host_server.ws_server = server

    print(f'\n=== RoboDesk RDP Server listening on ws://{HOST}:{PORT}{WS_PATH} ===\n')
    await asyncio.gather(server.wait_closed(), cli_loop(host_server, server))


if __name__ == '__main__':
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print('\nServer terminated.')
