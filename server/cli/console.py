"""Interactive command line interface (CLI) for RoboDesk RDP v1.0."""
from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path
from typing import TYPE_CHECKING, Any

from server.utils.audio import generate_melody_pcm, load_pcm_from_wav, save_pcm_to_wav
from server.utils.display import is_persian_text

if TYPE_CHECKING:
    from server.core.server import RoboDeskHostServer


def print_cli_menu() -> None:
    """Print the interactive RDP v1.0 host command menu."""
    print('\n==================== RoboDesk Conversational Host CLI ====================')
    print('  help                         : Show this command reference')
    print('  auto <on|off>                : Toggle autonomous voice assistant')
    print('  wake                         : Wake up robot assistant into active conversation')
    print('  sleep                        : Put robot assistant to standby mode (shows OLED text)')
    print('  say <text>                   : Synthesize and stream Persian text to robot')
    print('  face <normal|happy|surprised|sleepy|thinking|talking|singing> [duration_ms]')
    print('  gaze <x> <y> [hold_ms]       : Direct eye gaze (-1.0 to 1.0)')
    print('  blink                        : Trigger immediate blink')
    print('  text <title> | <body> [ms]   : Show full-screen text on OLED')
    print('  clear_text                   : Dismiss full-screen text')
    print('  mic <on|off>                 : Enable/disable mic streaming')
    print('  volume <0.0-1.0>             : Set speaker master volume')
    print('  tone <freq> [ms] [vol]       : Synthesize tone on robot')
    print('  stop | abort                 : Stop active audio/song playback or barge-in abort')
    print('  play_synth                   : Stream synthesized PCM melody to robot (singing face)')
    print('  play_wav <path.wav>          : Stream a WAV file to robot (normal face)')
    print('  play_song <path.wav>         : Play music/song with singing face & music notes animation')
    print('  record <seconds> [out.wav]   : Record robot mic to WAV file')
    print('  telemetry <on|off>           : Toggle live telemetry console logs')
    print('  caps                         : Query system.get_capabilities')
    print('  ping                         : Send system.ping')
    print('  reboot [delay_ms]            : Reboot robot')
    print('  exit | quit                  : Shut down host server and exit')
    print('=========================================================================\n')


async def cli_loop(server: RoboDeskHostServer, ws_server: Any = None) -> None:
    """Interactive terminal loop for issuing RDP v1.0 RPC calls and AI triggers."""
    if ws_server:
        server.ws_server = ws_server
    loop = asyncio.get_running_loop()
    print_cli_menu()

    while True:
        try:
            raw_line = await loop.run_in_executor(None, input, 'RoboDesk> ')
        except EOFError:
            if sys.stdin and sys.stdin.isatty():
                print('\n[*] Exiting RoboDesk host CLI...')
                await server.shutdown()
                break
            await asyncio.sleep(3600)
            continue
        except KeyboardInterrupt:
            if server.active_session and server.active_session.is_playing_audio:
                print('\n[*] Interrupted by user -> Stopping audio playback...')
                await server.active_session.stop_audio_playback()
                continue
            print('\n[*] Interrupted. Exiting...')
            await server.shutdown()
            break

        line = raw_line.strip()
        if not line:
            continue

        parts = line.split()
        cmd = parts[0].lower()

        if cmd == 'help':
            print_cli_menu()
            continue

        if cmd in ('exit', 'quit', 'q', 'خروج'):
            print('\n[*] Shutting down RoboDesk server...')
            await server.shutdown()
            break

        session = server.active_session
        if not session:
            print('[!] No robot currently connected.')
            continue

        try:
            if cmd == 'auto' and len(parts) >= 2:
                enabled = parts[1].lower() in ('on', 'true', '1')
                if session.assistant:
                    session.assistant.auto_chat_enabled = enabled
                    print(f'[*] Autonomous voice chat: {enabled}')
                    if enabled:
                        await session.assistant.wake_up(greet=False)
                    else:
                        await session.assistant.stop_listening()

            elif cmd == 'wake':
                if session.assistant:
                    await session.assistant.wake_up(greet=True)

            elif cmd == 'sleep':
                if session.assistant:
                    await session.assistant.enter_standby(reason='manual_command')

            elif cmd == 'say' and len(parts) >= 2:
                text_to_say = line[4:].strip()
                if session.assistant:
                    prev_state = session.assistant.state
                    await session.assistant.speak_phrase(text_to_say, emotion='happy')
                    resume_mode = prev_state if prev_state in ('standby', 'active_listening') else 'active_listening'
                    await session.assistant.start_listening(mode=resume_mode)

            elif cmd == 'caps':
                res = await session.send_request('system.get_capabilities')
                print(json.dumps(res, indent=2))

            elif cmd == 'ping':
                res = await session.send_request('system.ping')
                print(json.dumps(res, indent=2))

            elif cmd == 'reboot':
                delay_ms = int(parts[1]) if len(parts) > 1 else 500
                res = await session.send_request('system.reboot', {'delay_ms': delay_ms})
                print(json.dumps(res, indent=2))

            elif cmd == 'face' and len(parts) >= 2:
                name = parts[1].lower()
                dur = int(parts[2]) if len(parts) > 2 else 0
                res = await session.send_request(
                    'face.set_expression',
                    {'name': name, 'duration_ms': dur},
                )
                print(json.dumps(res, indent=2))

            elif cmd == 'gaze' and len(parts) >= 3:
                gx = float(parts[1])
                gy = float(parts[2])
                hold = int(parts[3]) if len(parts) > 3 else 0
                res = await session.send_request(
                    'face.set_gaze',
                    {'x': gx, 'y': gy, 'hold_ms': hold},
                )
                print(json.dumps(res, indent=2))

            elif cmd == 'blink':
                res = await session.send_request('face.blink')
                print(json.dumps(res, indent=2))

            elif cmd in ('text', 'text_fa'):
                rest = line[len(cmd):].strip()
                if '|' in rest:
                    title_part, body_part = rest.split('|', 1)
                else:
                    title_part = '[ اعلان ]' if is_persian_text(rest) else '[ NOTICE ]'
                    body_part = rest

                body_tokens = body_part.strip().rsplit(' ', 1)
                timeout_ms = 5000
                if len(body_tokens) == 2 and body_tokens[1].isdigit():
                    body_clean = body_tokens[0]
                    timeout_ms = int(body_tokens[1])
                else:
                    body_clean = body_part.strip()

                body_clean = body_clean.replace('\\n', '\n')
                if is_persian_text(body_clean) or is_persian_text(title_part) or cmd == 'text_fa':
                    res = await session.show_persian_text(
                        body=body_clean,
                        title=title_part.strip(),
                        timeout_ms=timeout_ms,
                    )
                else:
                    res = await session.send_request(
                        'display.show_text',
                        {
                            'title': title_part.strip(),
                            'body': body_clean,
                            'font_size': 1,
                            'timeout_ms': timeout_ms,
                        },
                    )
                print(json.dumps(res, indent=2))

            elif cmd == 'clear_text':
                res = await session.send_request('display.clear_text')
                print(json.dumps(res, indent=2))

            elif cmd == 'mic' and len(parts) >= 2:
                stream = parts[1].lower() in ('on', 'true', '1')
                res = await session.send_request('audio.set_mic_capture', {'stream': stream})
                print(json.dumps(res, indent=2))

            elif cmd == 'volume' and len(parts) >= 2:
                level = float(parts[1])
                res = await session.send_request('audio.set_volume', {'level': level})
                print(json.dumps(res, indent=2))

            elif cmd == 'tone' and len(parts) >= 2:
                freq = float(parts[1])
                dur = int(parts[2]) if len(parts) > 2 else 180
                vol = float(parts[3]) if len(parts) > 3 else 0.25
                res = await session.send_request(
                    'audio.play_tone',
                    {'freq': freq, 'duration_ms': dur, 'volume': vol},
                )
                print(json.dumps(res, indent=2))

            elif cmd in ('stop', 'stop_audio', 'stop_song', 'stop_music', 'abort', 'قطع'):
                if session.is_playing_audio:
                    print('[*] Stopping audio playback...')
                    await session.stop_audio_playback()
                    print('[+] Audio playback stopped.')
                elif session.assistant:
                    await session.assistant.abort()
                    print('[*] Assistant speech aborted.')
                else:
                    res = await session.send_request('audio.abort')
                    print(json.dumps(res, indent=2))

            elif cmd == 'play_synth':
                pcm = generate_melody_pcm()
                print('[*] Playing melody with singing face animation...')
                print("[*] (Type 'stop' or 'abort' or press Ctrl+C to cancel playback)")
                session.audio_task = asyncio.create_task(
                    session.stream_pcm_to_speaker(pcm, expression='singing')
                )

            elif cmd == 'play_wav' and len(parts) >= 2:
                file_arg = ' '.join(parts[1:]).strip()
                wav_path = Path(file_arg)
                if not wav_path.is_file():
                    print(f'[!] File not found: {wav_path}')
                    continue
                try:
                    pcm = load_pcm_from_wav(wav_path)
                except Exception as exc:
                    print(f'[!] Error loading audio file: {exc}')
                    continue

                print(f'[*] Playing audio: {wav_path.name} (Face: normal)...')
                print("[*] (Type 'stop' or 'abort' or press Ctrl+C to cancel playback)")
                session.audio_task = asyncio.create_task(
                    session.stream_pcm_to_speaker(pcm, expression='normal')
                )

            elif cmd in ('play_song', 'play_music', 'sing') and len(parts) >= 2:
                file_arg = ' '.join(parts[1:]).strip()
                wav_path = Path(file_arg)
                if not wav_path.is_file():
                    print(f'[!] File not found: {wav_path}')
                    continue
                try:
                    pcm = load_pcm_from_wav(wav_path)
                except Exception as exc:
                    print(f'[!] Error loading audio file: {exc}')
                    continue

                print(f'[*] Playing music/song: {wav_path.name} with singing & musical notes animation...')
                print("[*] (Type 'stop' or 'abort' or press Ctrl+C to cancel playback)")
                session.audio_task = asyncio.create_task(
                    session.stream_pcm_to_speaker(pcm, expression='singing')
                )

            elif cmd == 'record':
                seconds = float(parts[1]) if len(parts) > 1 else 3.0
                out_file = Path(parts[2]) if len(parts) > 2 else Path('captured_mic.wav')

                session.recording_buffer.clear()
                session.is_recording = True
                await session.send_request('audio.set_mic_capture', {'stream': True})
                print(f'[*] Recording from robot microphone for {seconds:.1f}s...')
                await asyncio.sleep(seconds)
                await session.send_request('audio.set_mic_capture', {'stream': False})
                session.is_recording = False

                captured = bytes(session.recording_buffer)
                save_pcm_to_wav(out_file, captured)
                print(f'[+] Saved {len(captured)} bytes to {out_file.resolve()}')

            elif cmd == 'telemetry' and len(parts) >= 2:
                session.show_telemetry_logs = parts[1].lower() in ('on', 'true', '1')
                print(f'[*] Live telemetry logging: {session.show_telemetry_logs}')

            else:
                print('[!] Invalid command or missing arguments. Type "help".')

        except asyncio.TimeoutError:
            print('[!] RPC request timed out waiting for robot response.')
        except Exception as exc:
            print(f'[!] Command execution error: {exc}')
