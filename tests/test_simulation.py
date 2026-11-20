"""End-to-end integration test verifying Wake-Word ('صبا/سبا'), Active Conversation,
and OLED Standby Notification upon exit using a simulated robot client."""
from __future__ import annotations

import asyncio
import json

import websockets

from server.ai.tts import PocketTTSEngine
from server.config import HOST, PORT, WS_PATH


async def run_simulation() -> None:
    uri = f'ws://{HOST}:{PORT}{WS_PATH}'
    print(f'[*] Connecting simulated robot to {uri}...')

    tts = PocketTTSEngine()

    async with websockets.connect(uri) as ws:
        print('[+] Connected to server!')

        # 1. Send system.hello
        hello_envelope = {
            'v': '1.0',
            'kind': 'event',
            'topic': 'system.hello',
            'data': {
                'device_id': 'robo-desk-sim-02',
                'firmware': 'rtos-1.0.0-sim',
                'hardware': {
                    'chip': 'ESP32-SIM',
                    'display': 'ssd1306_128x64',
                    'audio_in': 'inmp441_pcm16_16khz',
                    'audio_out': 'max98357a_pcm16_16khz',
                },
            },
        }
        await ws.send(json.dumps(hello_envelope))
        print('[+] Sent system.hello event.')

        # Track robot state
        active_face = 'normal'
        display_text_seen: list[dict[str, str]] = []
        rx_spk_bytes = 0
        eos_count = 0
        mic_streaming = False

        async def rx_loop() -> None:
            nonlocal active_face, rx_spk_bytes, eos_count, mic_streaming
            try:
                async for message in ws:
                    if isinstance(message, str):
                        msg = json.loads(message)
                        kind = msg.get('kind')
                        req_id = msg.get('id')
                        method = msg.get('method')
                        params = msg.get('params', {})

                        if kind == 'req':
                            if method == 'audio.set_mic_capture':
                                mic_streaming = params.get('stream', False)
                            elif method == 'face.set_expression':
                                active_face = params.get('name')
                                print(f'[ROBOT OLED FACE] -> {active_face}')
                            elif method == 'display.show_text':
                                display_text_seen.append(params)
                                print(f'[ROBOT OLED TEXT] -> Title: "{params.get("title")}" | Body: "{params.get("body")}"')
                            elif method == 'audio.eos':
                                eos_count += 1
                                print(f'[ROBOT AUDIO EOS] -> EOS #{eos_count}')

                            res = {'v': '1.0', 'kind': 'res', 'id': req_id, 'status': 'ok', 'result': {}}
                            await ws.send(json.dumps(res))

                    elif isinstance(message, bytes):
                        rx_spk_bytes += len(message)
            except asyncio.CancelledError:
                pass

        rx_task = asyncio.create_task(rx_loop())

        # Helper to stream a speech phrase to the server as if spoken by user
        async def send_user_speech(phrase: str) -> None:
            print(f'\n[SIM USER SAYS]: "{phrase}"')
            pcm = tts.synthesize_sentence_to_16k_pcm(phrase)
            chunk_size = 2048
            for off in range(0, len(pcm), chunk_size):
                await ws.send(pcm[off : off + chunk_size])
                await asyncio.sleep(0.064)
            # Send silence to trigger VAD
            silence = b'\x00' * 2048
            for _ in range(16):
                await ws.send(silence)
                await asyncio.sleep(0.064)

        # Wait for initialization in STANDBY mode (sleepy face)
        await asyncio.sleep(1.5)
        print(f'[+] Initial face state: {active_face} (Expected: sleepy)')
        assert active_face == 'sleepy', f'Expected initial face "sleepy", got "{active_face}"'

        # --- TEST 1: Speak without wake word while in STANDBY ---
        print('\n=== TEST 1: User speaks without wake-word in STANDBY ===')
        bytes_before = rx_spk_bytes
        await send_user_speech('امروز چند شنبه است؟')
        await asyncio.sleep(2.0)
        # Verify no audio response was generated
        print(f'[+] Speaker bytes received during test 1: {rx_spk_bytes - bytes_before} (Expected: 0)')
        assert rx_spk_bytes - bytes_before == 0, 'Server responded without wake-word!'
        print('[PASS] Test 1 passed: Ignored conversation without "صبا/سبا" in standby.')

        # --- TEST 2: User addresses robot with wake-word alone ("سلام صبا") ---
        print('\n=== TEST 2: User says wake-word alone: "سلام صبا" ===')
        prev_eos = eos_count
        await send_user_speech('سلام صبا')

        # Wait for greeting playback ("بله صدام زدی؟ جانم بگو.")
        for _ in range(50):
            if eos_count > prev_eos:
                break
            await asyncio.sleep(0.2)

        print(f'[+] Speaker bytes received: {rx_spk_bytes}')
        assert eos_count > prev_eos, 'Server did not answer standalone wake-word greeting'
        assert active_face == 'normal', f'Expected active face "normal", got "{active_face}"'
        print('[PASS] Test 2 passed: Robot woke up and spoke greeting, remained in ACTIVE_LISTENING!')

        # --- TEST 3: Follow-up question in active conversation (without "صبا") ---
        print('\n=== TEST 3: Follow-up query in active session: "یه شعر کوتاه بگو" ===')
        prev_eos = eos_count
        await send_user_speech('یه شعر کوتاه بگو')

        for _ in range(50):
            if eos_count > prev_eos:
                break
            await asyncio.sleep(0.2)

        assert eos_count > prev_eos, 'Server failed to respond to follow-up query in active session'
        print('[PASS] Test 3 passed: Responded in active conversation without needing wake-word!')

        # --- TEST 4: Exit phrase and OLED Standby Notification ---
        print('\n=== TEST 4: User says exit phrase: "دیگه باهات کاری ندارم خداحافظ" ===')
        prev_eos = eos_count
        await send_user_speech('دیگه باهات کاری ندارم خداحافظ')

        for _ in range(50):
            if eos_count > prev_eos and len(display_text_seen) > 0:
                break
            await asyncio.sleep(0.2)

        print(f'[+] OLED text messages received: {len(display_text_seen)}')
        assert len(display_text_seen) > 0, 'No standby OLED notification was sent to robot!'
        last_disp = display_text_seen[-1]
        print(f'[+] Notification Title: {last_disp.get("title")}')
        print(f'[+] Notification Body: {last_disp.get("body")}')
        assert '[ STANDBY ]' in last_disp.get('title', '')
        print(f'[+] Face expression after farewell: {active_face} (Expected: sleepy)')
        assert active_face == 'sleepy', 'Face was not set to sleepy after farewell'
        print('[PASS] Test 4 passed: Farewell spoken, OLED standby message displayed, face set to sleepy!')

        rx_task.cancel()
        print('\n============================================================')
        print('  ALL TESTS PASSED: Wake-Word, Active Session, Animations,  ')
        print('  and Standby OLED Notification verified 100% successfully! ')
        print('============================================================\n')


if __name__ == '__main__':
    asyncio.run(run_simulation())
