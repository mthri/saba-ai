"""Saba AI (RoboDesk) Server Package.

A modular, real-time AI and robotics server supporting FreeRTOS ESP32 communication,
Persian ASR, procedural OLED animation, conversational LLM, and neural TTS.
"""
from server.ai.asr import ShenavaASRManager, StreamingVAD
from server.ai.assistant import AssistantState, RoboDeskAssistant
from server.ai.llm import ChatManager, warmup_llm
from server.ai.tts import PocketTTSEngine
from server.core.server import RoboDeskHostServer
from server.core.session import RobotSession
from server.core.streamer import PacedAudioStreamer

__version__ = '1.0.0'

__all__ = [
    'AssistantState',
    'ChatManager',
    'PacedAudioStreamer',
    'PocketTTSEngine',
    'RoboDeskAssistant',
    'RoboDeskHostServer',
    'RobotSession',
    'ShenavaASRManager',
    'StreamingVAD',
    '__version__',
    'warmup_llm',
]
