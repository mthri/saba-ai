"""RoboDesk Autonomous Voice Assistant Coordinator with Wake-Word & Session Management."""
from __future__ import annotations

import asyncio
import random
from typing import TYPE_CHECKING

import numpy as np

from server.ai.llm import ChatManager
from server.ai.text_processor import detect_wake_word, extract_face_tag, is_exit_phrase
from server.ai.tts import PocketTTSEngine, stream_sentences
from server.config import (
    ENABLE_KWS,
    FAREWELL_RESPONSES,
    SESSION_INACTIVITY_TIMEOUT_SEC,
    STANDBY_DISPLAY_BODY,
    STANDBY_DISPLAY_TITLE,
    STANDBY_TIMEOUT_DISPLAY_BODY,
    WAKE_GREETINGS,
)
from server.core.streamer import PacedAudioStreamer
from server.utils.display import is_persian_text

if TYPE_CHECKING:
    from server.ai.asr import ShenavaASRManager
    from server.ai.kws import SherpaKWSManager
    from server.core.session import RobotSession


class AssistantState:
    STANDBY = 'standby'                       # Sleeping / Standby: Only responds to "صبا" / "سبا"
    ACTIVE_LISTENING = 'active_listening'     # In active conversation: Responds to every utterance
    PROCESSING_STT = 'processing_stt'         # Transcribing speech with NeMo
    PROCESSING_LLM = 'processing_llm'         # Generating response with LLM (Thinking face)
    SPEAKING = 'speaking'                     # Playing TTS audio on robot speaker (Talking face)


class RoboDeskAssistant:
    """Manages the full voice cycle: Robot Mic -> Streaming VAD -> NeMo ASR -> LLM -> Pocket TTS -> Robot Spk."""

    def __init__(
        self,
        session: RobotSession,
        asr_mgr: ShenavaASRManager,
        tts_engine: PocketTTSEngine,
        auto_start: bool = True,
    ) -> None:
        self.session = session
        self.asr = asr_mgr
        self.tts = tts_engine
        self.streamer = PacedAudioStreamer(session)
        self.chat = ChatManager(title=f'Robot-{session.device_id}')

        self.state = AssistantState.STANDBY
        self.auto_chat_enabled = True
        self._current_task: asyncio.Task[None] | None = None
        self._inactivity_task: asyncio.Task[None] | None = None

        self.vad = self.asr.create_streaming_vad(on_speech_start=self._on_speech_started)

        # On-device Sherpa-ONNX streaming Wake-Word engine
        self.kws: SherpaKWSManager | None = None
        if ENABLE_KWS:
            try:
                from server.ai.kws import SherpaKWSManager

                kws_mgr = SherpaKWSManager()
                if kws_mgr.is_available:
                    self.kws = kws_mgr
            except Exception as exc:
                print(f'[-] Warning: Could not initialize Sherpa-ONNX KWS: {exc}')

        self._kws_detected_during_utterance: bool = False

    def _on_speech_started(self) -> None:
        if self.state in (AssistantState.STANDBY, AssistantState.ACTIVE_LISTENING):
            print('\n[*] User started speaking...')
            self._cancel_inactivity_timer()

    async def initialize(self) -> None:
        """Called once robot session is established."""
        print(f'[*] Initializing Voice Assistant for robot {self.session.device_id}...')
        await asyncio.sleep(0.5)

        # Start in standby mode, waiting for wake word "صبا"
        self.state = AssistantState.STANDBY
        print('[*] Robot ready in STANDBY mode. Say "صبا" or "سبا" to activate.\n')
        try:
            await self.session.send_request('face.set_expression', {'name': 'sleepy', 'duration_ms': 0})
            await self.session.send_request('audio.set_mic_capture', {'stream': True})
        except Exception as exc:
            print(f'[-] Initialization error: {exc}')

    def _cancel_inactivity_timer(self) -> None:
        if self._inactivity_task and not self._inactivity_task.done():
            self._inactivity_task.cancel()
            self._inactivity_task = None

    def _reset_inactivity_timer(self) -> None:
        """Reset the timer that returns the robot to standby if no speech is detected."""
        self._cancel_inactivity_timer()
        self._inactivity_task = asyncio.create_task(self._inactivity_timeout_worker())

    async def _inactivity_timeout_worker(self) -> None:
        try:
            await asyncio.sleep(SESSION_INACTIVITY_TIMEOUT_SEC)
            if self.state == AssistantState.ACTIVE_LISTENING:
                print(f'\n[*] Inactivity limit ({SESSION_INACTIVITY_TIMEOUT_SEC:.0f}s) reached. Entering STANDBY mode.')
                await self.enter_standby(reason='timeout')
        except asyncio.CancelledError:
            pass

    async def enter_standby(self, reason: str = 'user_exit') -> None:
        """Transition assistant from active conversation to standby/sleep."""
        self._cancel_inactivity_timer()
        self.state = AssistantState.STANDBY
        self._kws_detected_during_utterance = False
        if self.kws:
            self.kws.reset()

        # Show notification on OLED display so the user knows Saba is in standby
        body_text = STANDBY_TIMEOUT_DISPLAY_BODY if reason == 'timeout' else STANDBY_DISPLAY_BODY
        try:
            if is_persian_text(body_text) or is_persian_text(STANDBY_DISPLAY_TITLE):
                await self.session.show_persian_text(
                    body=body_text,
                    title=STANDBY_DISPLAY_TITLE,
                    timeout_ms=4500,
                )
            else:
                await self.session.send_request(
                    'display.show_text',
                    {
                        'title': STANDBY_DISPLAY_TITLE,
                        'body': body_text,
                        'font_size': 1,
                        'timeout_ms': 4500,
                    },
                )
            await self.session.send_request('face.set_expression', {'name': 'sleepy', 'duration_ms': 0})
            await self.session.send_request('audio.set_mic_capture', {'stream': True})
        except Exception as exc:
            print(f'[-] Error setting standby state: {exc}')

        print('[*] Robot entered STANDBY. Say "صبا" or "سبا" or shake to activate.\n')

    async def wake_up(self, greet: bool = True) -> None:
        """Wake up the assistant into active conversation mode."""
        self._cancel_inactivity_timer()
        self.state = AssistantState.ACTIVE_LISTENING

        print('[*] Assistant awakened! Active conversation session started.')
        try:
            await self.session.send_request('display.clear_text')
            await self.session.send_request('face.set_expression', {'name': 'happy', 'duration_ms': 1500})
        except Exception:
            pass

        if greet:
            greeting_text = random.choice(WAKE_GREETINGS)
            await self.speak_phrase(greeting_text, emotion='talking')

        self.state = AssistantState.ACTIVE_LISTENING
        await self.start_listening(mode=AssistantState.ACTIVE_LISTENING)
        self._reset_inactivity_timer()

    async def start_listening(self, mode: str | None = None) -> None:
        """Put robot into listening mode with microphone streaming active."""
        if not self.auto_chat_enabled:
            return

        if mode is not None:
            self.state = mode
        elif self.state == AssistantState.SPEAKING:
            self.state = AssistantState.ACTIVE_LISTENING

        self.vad.reset()
        if self.state == AssistantState.STANDBY and self.kws:
            self.kws.reset()
            self._kws_detected_during_utterance = False
        listening_state_name = 'ACTIVE_LISTENING' if self.state == AssistantState.ACTIVE_LISTENING else 'STANDBY'
        face_name = 'normal' if self.state == AssistantState.ACTIVE_LISTENING else 'sleepy'

        print(f'[*] Listening (Mode: {listening_state_name})...')
        try:
            await self.session.send_request('face.set_expression', {'name': face_name, 'duration_ms': 0})
            await self.session.send_request('audio.set_mic_capture', {'stream': True})
        except Exception as exc:
            print(f'[-] Error enabling mic capture: {exc}')

    async def stop_listening(self) -> None:
        """Stop listening and mute microphone streaming."""
        try:
            await self.session.send_request('audio.set_mic_capture', {'stream': False})
        except Exception as exc:
            print(f'[-] Error disabling mic capture: {exc}')

    async def _show_wake_feedback(self) -> None:
        """Immediate visual/haptic response when KWS spots wake word."""
        try:
            await self.session.send_request('display.clear_text')
            await self.session.send_request('face.set_expression', {'name': 'happy', 'duration_ms': 1200})
        except Exception:
            pass

    def feed_mic_audio(self, pcm_chunk: bytes) -> None:
        """Process incoming raw PCM chunks from the robot microphone."""
        if not self.auto_chat_enabled:
            return

        # Only process audio when listening in standby or active conversation
        if self.state not in (AssistantState.STANDBY, AssistantState.ACTIVE_LISTENING):
            return

        # In STANDBY mode, stream chunk into lightweight Sherpa-ONNX KWS
        if self.state == AssistantState.STANDBY and self.kws and self.kws.is_available:
            detected = self.kws.process_pcm_chunk(pcm_chunk)
            if detected:
                print(f'[+] Sherpa-ONNX KWS spotted wake-word "{detected}"!')
                self._kws_detected_during_utterance = True
                asyncio.create_task(self._show_wake_feedback())

        utterance = self.vad.process_pcm_chunk(pcm_chunk)
        if utterance is not None:
            if self._current_task and not self._current_task.done():
                self._current_task.cancel()
            self._current_task = asyncio.create_task(self.handle_utterance(utterance))

    async def handle_utterance(self, audio_data: np.ndarray) -> None:
        """Handle captured speech utterance through Wake-word check, ASR, LLM, and TTS."""
        previous_state = self.state
        was_kws_spotted = self._kws_detected_during_utterance
        self._kws_detected_during_utterance = False

        # In STANDBY mode with KWS active:
        # If KWS did NOT spot the wake-word during this utterance, skip heavy ASR entirely!
        if previous_state == AssistantState.STANDBY and self.kws and self.kws.is_available and not was_kws_spotted:
            print('[-] [STANDBY] Speech ignored by Sherpa-ONNX KWS (Wake word "صبا" not detected). NeMo ASR skipped.')
            try:
                await self.session.send_request('face.blink')
            except Exception:
                pass
            return

        self.state = AssistantState.PROCESSING_STT
        await self.stop_listening()

        print('[*] Processing speech (VAD end of utterance detected)...')

        # 1. Transcribe audio via NeMo ASR
        user_text = await self.asr.transcribe(audio_data)
        if not user_text or not user_text.strip():
            print('[-] Silence or unrecognized speech. Resuming.')
            if previous_state == AssistantState.STANDBY and was_kws_spotted:
                # User called "صبا" alone and paused; speak friendly greeting!
                greeting = random.choice(WAKE_GREETINGS)
                await self.speak_phrase(greeting, emotion='talking')
                self.state = AssistantState.ACTIVE_LISTENING
                await self.start_listening(mode=AssistantState.ACTIVE_LISTENING)
                self._reset_inactivity_timer()
                return

            self.state = previous_state
            await self.start_listening()
            if self.state == AssistantState.ACTIVE_LISTENING:
                self._reset_inactivity_timer()
            return

        print(f'\n[User]: {user_text}')

        # 2. Check Wake Word if currently in STANDBY mode
        if previous_state == AssistantState.STANDBY:
            is_addressed, query = detect_wake_word(user_text)
            if not was_kws_spotted and not is_addressed:
                print(f'[-] [STANDBY] Speech ignored (Wake word "صبا/سبا" not addressed): "{user_text}"')
                self.state = AssistantState.STANDBY
                # Subtle blink to show sound detected without speaking
                try:
                    await self.session.send_request('face.blink')
                except Exception:
                    pass
                await self.start_listening()
                return

            print('[+] Wake-word confirmed! Activating Saba AI...')
            self.state = AssistantState.ACTIVE_LISTENING

            # If user said wake-word alone (e.g. "صبا" or "سلام صبا"):
            if not query:
                greeting = random.choice(WAKE_GREETINGS)
                await self.speak_phrase(greeting, emotion='talking')
                self.state = AssistantState.ACTIVE_LISTENING
                await self.start_listening(mode=AssistantState.ACTIVE_LISTENING)
                self._reset_inactivity_timer()
                return

            # User called Saba and asked a query in the same sentence (e.g. "صبا ساعت چنده؟")
            user_text = query

        # 3. Check for Exit Phrases in Active Conversation
        if is_exit_phrase(user_text):
            print('[*] Exit command detected. Ending conversation session.')
            farewell = random.choice(FAREWELL_RESPONSES)
            await self.speak_phrase(farewell, emotion='talking')
            await self.enter_standby(reason='user_exit')
            return

        # 4. In Active Conversation: Query LLM and Stream Response
        self.state = AssistantState.PROCESSING_LLM
        self._cancel_inactivity_timer()

        # Set face to animated thinking state
        print('[*] Thinking... (animated thinking expression & thought dots active)')
        await self.session.send_request('face.set_expression', {'name': 'thinking', 'duration_ms': 0})

        loop = asyncio.get_running_loop()

        def run_llm_stream():
            return list(self.chat.ask_stream(user_text))

        try:
            tokens = await loop.run_in_executor(None, run_llm_stream)
        except Exception as exc:
            print(f'[-] LLM query error: {exc}')
            await self.speak_phrase('شَرمَنده، پَردازِش پاسُخ با خَطا روبرو شُد.', emotion='surprised')
            self.state = AssistantState.ACTIVE_LISTENING
            await self.start_listening(mode=AssistantState.ACTIVE_LISTENING)
            self._reset_inactivity_timer()
            return

        # 5. Stream response sentences through TTS & ESP32 speaker
        await self.stream_response_tokens(tokens)

        # 6. Return to listening and restart inactivity timer
        await asyncio.sleep(0.1)
        self.state = AssistantState.ACTIVE_LISTENING
        await self.start_listening(mode=AssistantState.ACTIVE_LISTENING)
        self._reset_inactivity_timer()

    async def stream_response_tokens(self, tokens: list[str]) -> None:
        """Stream sentence chunks into Pocket TTS, resample, and transmit to robot speaker."""
        self.state = AssistantState.SPEAKING
        self.streamer.reset()

        # Extract facial emotion tag if present
        full_text = ''.join(tokens)
        emotion, cleaned_full_text = extract_face_tag(full_text)

        active_emotion = emotion or 'talking'
        print(f'[*] Robot face expression set to: {active_emotion}')
        await self.session.send_request('face.set_expression', {'name': active_emotion, 'duration_ms': 0})

        # Break text into individual sentences for low-latency streaming
        sentences = list(stream_sentences([cleaned_full_text]))
        if not sentences and cleaned_full_text.strip():
            sentences = [cleaned_full_text.strip()]

        print('[Assistant]: ', end='', flush=True)

        for sentence in sentences:
            sent = sentence.strip()
            if not sent:
                continue

            print(f'{sent} ', end='', flush=True)

            # Synthesize sentence to 16kHz PCM
            pcm_bytes = await self.tts.async_synthesize_sentence(sent)
            if not pcm_bytes:
                continue

            # Stream with flow control to ESP32 speaker
            await self.streamer.stream_pcm_bytes(pcm_bytes)

        print()

        # Finish playback and wait for ESP32 ring buffer to drain
        await self.streamer.finish_playback()

    async def speak_phrase(self, text: str, emotion: str = 'talking') -> None:
        """Helper to speak a standalone phrase with animated mouth & face."""
        self.state = AssistantState.SPEAKING
        await self.stop_listening()
        await self.session.send_request('face.set_expression', {'name': emotion, 'duration_ms': 0})

        print(f'[Robot]: {text}')
        pcm_bytes = await self.tts.async_synthesize_sentence(text)
        if pcm_bytes:
            self.streamer.reset()
            await self.streamer.stream_pcm_bytes(pcm_bytes)
            await self.streamer.finish_playback()

    async def handle_shake_trigger(self, intensity: float) -> None:
        """Handle hardware accelerometer shake event."""
        print(f'\n[*] Shake detected! Intensity: {intensity:.2f}')
        if self.state == AssistantState.STANDBY:
            await self.wake_up(greet=True)
        elif self.state == AssistantState.SPEAKING:
            # Shake acts as barge-in interrupt
            print('[*] Shake detected during speech -> Aborting audio playback.')
            await self.abort()
        else:
            await self.session.send_request('face.set_expression', {'name': 'surprised', 'duration_ms': 1500})
            await self.session.send_request('face.blink')

    async def abort(self) -> None:
        """Abort any ongoing playback and return to listening."""
        if self._current_task and not self._current_task.done():
            self._current_task.cancel()
        await self.streamer.abort()
        if self.state == AssistantState.ACTIVE_LISTENING:
            await self.start_listening()
            self._reset_inactivity_timer()
        else:
            await self.enter_standby(reason='abort')
