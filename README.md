# Saba AI (RoboDesk) 🤖🎙️

**Saba AI (RoboDesk)** is an intelligent, voice-interactive desk companion robot. It combines custom ESP32 dual-core FreeRTOS embedded firmware with two interchangeable, fully supported host AI brain implementations:
1. **Python Host Brain (Desktop / Linux / Server):** Local/offline-capable pipeline with Shenava ASR (NeMo), Ollama/OpenAI LLM, Pocket-TTS (Persian synthesis), Silero VAD, SQLite conversation persistence, and server-side Persian/Arabic OLED typography rendering.
2. **Android Host App (Mobile / Tablet):** Native modern Android application (Jetpack Compose) featuring an embedded RFC-6455 WebSocket host server, on-device Silero VAD & Sherpa-ONNX wake-word engine, direct multimodal audio-to-LLM pipeline (Gemini / OpenAI), Walkie-Talkie mode, live robot microphone eavesdropping, interactive 2D eye gaze control, and live hardware diagnostics.

Both hosts run the unified **RoboDesk Protocol (RDP v1.0)** over WebSockets (`ws://<HOST_IP>:8765/ws/robot`), allowing the ESP32 robot to connect seamlessly to either a computer or an Android smartphone without any firmware modifications!

---

## 🇮🇷 خلاصه معرفی پروژه (Persian Overview)

**صبا (Saba AI / RoboDesk)** یک ربات دستیار رومیزی هوشمند و تعاملی صوتی است. بخش سخت‌افزاری بر پایه میکروکنترلر دوهسته‌ای ESP32 و سیستم‌عامل بی‌درنگ FreeRTOS پیاده‌سازی شده و از طریق وب‌سوکت دوطرفه با هسته هوش مصنوعی (Host Brain) ارتباط برقرار می‌کند.

پروژه صبا از دو نوع میزبان (Host) قدرتمند پشتیبانی می‌کند:
1. **سرور پایتون (Python Host Brain):** مناسب اجرا روی لینوکس، ویندوز و سیستم‌های رومیزی، دارای پایپ‌لاین بومی تشخیص گفتار فارسی (Shenava ASR)، مدل‌های زبانی محلی (Ollama) یا ابری، موتور سنتز صدای فارسی (Pocket-TTS)، سیستم ذخیره‌سازی تاریخچه مکالمات در SQLite و ماژول رندر اختصاصی فونت‌های فارسی روی نمایشگر OLED با بیت‌مپ مونوکروم.
2. **اپلیکیشن اندروید (Android Host Companion App):** پیاده‌سازی مدرن با Jetpack Compose که مستقیماً روی گوشی همراه اجرا شده و یک سرور وب‌سوکت RFC-6455 داخلی روی پورت ۸۷۶۵ بالا می‌آورد. این اپلیکیشن مجهز به موتور تشخیص کلمه کلیدی «صبا» (Wake-Word) و Silero VAD کاملاً آفلاین و On-Device، اتصال مستقیم صوت به مدل‌های چندحالته (مانند Gemini 2.5 Flash و GPT-4o-Audio)، قابلیت واکی‌تاکی زنده، شنود زنده میکروفون ربات روی اسپیکر گوشی، تاچ‌پد تعاملی هدایت نگاه چشم‌ها و پنل عیب‌یابی و تله‌متری کامل سخت‌افزار است.

---

## 🌟 Key Features

### 1. Embedded Robot Firmware (ESP32)
- **Procedural Facial Animation Engine:**
  - 0.96" SSD1306 128x64 OLED display driven over I2C at 400 kHz.
  - Procedural eye states (`normal`, `happy`, `surprised`, `sleepy`, `thinking`, `talking`, `singing`).
  - Smooth 2D gaze tracking (X/Y coordinates), automatic natural blinking, and full-screen notification overlays.
- **Bidirectional Digital Audio Pipeline:**
  - **Audio Input (Mic):** INMP441 MEMS omnidirectional digital microphone running over I2S0 (16 kHz, 16-bit mono PCM).
  - **Audio Output (Speaker):** MAX98357A Class-D digital amplifier running over I2S1 (16 kHz, 16-bit mono PCM).
  - **Acoustic Management:** Automatic half-duplex microphone suppression during audio playback and instant barge-in support (`audio.abort`).
- **RoboDesk Protocol (RDP v1.0):**
  - High-performance, multiplexed communication standard running over WebSockets (`ws://<host>:8765/ws/robot`).
  - Text frames handle JSON-RPC requests, responses, and real-time sensor/heap telemetry.
  - Binary frames carry uncompressed linear PCM audio streams in both upstream and downstream directions.
- **Captive Portal Wi-Fi Provisioning:**
  - Built-in fallback SoftAP mode (`RoboDesk-Setup`) with a web interface to configure Wi-Fi credentials and server endpoint without re-flashing.

### 2. Android Mobile Host App (`android/`)
- **Embedded RFC-6455 WebSocket Server:** Listens on `:8765/ws/robot` and accepts the ESP32 connection directly on the local Wi-Fi or phone hotspot.
- **On-Device Edge AI:**
  - **Offline Wake-Word Detection:** Sherpa-ONNX streaming KWS engine detecting "صبا" (Saba) locally with zero cloud latency.
  - **Silero VAD:** Real-time on-device voice activity detection to segment user commands cleanly.
- **Direct Multimodal Audio-to-LLM:**
  - Streams recorded 16 kHz PCM wrapped as WAV directly to OpenAI-compatible `/chat/completions` with `input_audio` (e.g. Gemini 2.5 Flash or GPT-4o-Audio), eliminating separate STT latency and cost.
- **High-Quality TTS & Cloud Synthesis:**
  - OpenAI-compatible `/audio/speech` client (supports MP3, WAV, PCM formats and custom voices/endpoints).
- **Interactive Robot Control Panel Tab:**
  - **Walkie-Talkie Mode:** Hold button to stream live microphone audio from the phone directly to the robot speaker in real-time.
  - **Live Robot Mic Eavesdropping:** Listen to the robot's INMP441 microphone audio in real time directly on the phone's loudspeaker.
  - **Facial Expression Triggers:** Fast buttons to set expressions (`normal`, `happy`, `surprised`, `sleepy`, `thinking`, `talking`, `singing`).
  - **2D Gaze Touchpad:** Real-time 2D drag pad to position the robot's eyes smoothly across X/Y coordinates (-1.0 to 1.0).
  - **OLED Text Sender:** Send title and body messages to the robot display.
  - **Hardware Controls:** Master volume slider, tone synthesizer, mic stream switch, audio barge-in abort, and robot reboot.
  - **Telemetry Dashboard:** Live Wi-Fi RSSI (dBm), free heap (KB), speaker ring buffer usage & underrun count, mic drops, and audio RMS levels.
- **Background Foreground Service:** `SabaHostService` runs stably in the background with a persistent system notification and microphone lock.

### 3. Python Host Brain (`server/`)
- **Integrated Persian AI Pipeline:**
  - **ASR (Speech-to-Text):** `Shenava-Koochik` (`Reza2kn/Shenava-Koochik-v1.0` via NeMo/PyTorch).
  - **Voice Activity Detection:** Integrated Silero VAD for low-latency streaming speech segmentation.
  - **Conversational Intelligence:** LLM engine supporting local Ollama (`gemma`, etc.) and OpenAI-compatible endpoints with conversational memory.
  - **TTS (Text-to-Speech):** High-quality Persian voice generation using `Pocket-TTS` and `Gooya-Bozorg`.
- **Server-Side Persian/Arabic OLED Typography Renderer (`server/utils/display.py`):**
  - Solves the embedded hardware limitation where Adafruit_GFX lacks Persian/Arabic font shaping, ligatures, and RTL layout.
  - Uses Pillow (PIL) and TrueType Persian fonts (e.g., Vazirmatn / Noto Sans Arabic) to render crisp 1-bit monochrome 128x64 bitmaps (1024 bytes) streamed over RDP via `display.show_bitmap`.
- **Flow-Controlled Audio Streaming (`PacedAudioStreamer`):**
  - Paced 1024-byte (32ms) chunk streaming matching the ESP32 ring buffer capacity (24 KB ring buffer, 4 KB prebuffer threshold) to prevent buffer overflows, under-runs, and audio glitches.
- **Database Persistence (`server/db/database.py`):**
  - Peewee SQLite database (`conversations.db`) storing sessions, user/assistant dialogue turns, and token counts.
- **Rich Interactive CLI Console (`server/cli/console.py`):**
  - Real-time terminal shell with full command suite for voice autonomy, audio streaming, OLED display, gaze positioning, and hardware diagnostics.

---

## 📐 System Architecture

```text
 ┌────────────────────────────────────────────────────────────────────────┐
 │                           RoboDesk Hardware                            │
 │                                                                        │
 │       [INMP441 Mic]              [SSD1306 OLED]       [MAX98357A Amp]  │
 │             │ I2S0 RX                  │ I2C                ▲ I2S1 TX  │
 │             ▼                          ▼                    │          │
 │      ┌──────────────────────────────────────────────────────────┐      │
 │      │           ESP32 Dual-Core Dev Module (FreeRTOS)          │      │
 │      │         - Core 0: I2S DMA Audio Buffers & Mute Ctrl      │      │
 │      │         - Core 1: OLED Face Engine & WebSockets          │      │
 │      └─────────────────────────────┬────────────────────────────┘      │
 └────────────────────────────────────┼───────────────────────────────────┘
                                      │
                         WebSocket (RDP v1.0)
                   JSON-RPC (Text) + PCM Audio (Binary)
                   Default Endpoint: ws://<HOST_IP>:8765/ws/robot
                                      │
           ┌──────────────────────────┴──────────────────────────┐
           │                                                     │
           ▼                                                     ▼
┌──────────────────────────────────────┐  ┌──────────────────────────────────────┐
│       Host Option A: Python Brain    │  │       Host Option B: Android App     │
│                                      │  │                                      │
│  ┌───────────────┐ ┌──────────────┐  │  │  ┌───────────────┐ ┌──────────────┐  │
│  │  Shenava ASR  │ │ Ollama / LLM │  │  │  │ On-Device KWS │ │ Direct Audio │  │
│  │ (Silero VAD)  │ │   Engine     │  │  │  │ (Sherpa-ONNX) │ │     LLM      │  │
│  └───────┬───────┘ └──────┬───────┘  │  │  └───────┬───────┘ └──────┬───────┘  │
│          ▼                ▼          │  │          ▼                ▼          │
│  ┌───────────────┐ ┌──────────────┐  │  │  ┌───────────────┐ ┌──────────────┐  │
│  │  Pocket TTS   │ │  OLED Bitmap │  │  │  │  Cloud / API  │ │ Walkie-Talkie│  │
│  │(Persian Voice)│ │Typography Rnd│  │  │  │   TTS Engine  │ │ & Mic Listen │  │
│  └───────────────┘ └──────────────┘  │  │  └───────────────┘ └──────────────┘  │
│                                      │  │                                      │
│  Interactive CLI & SQLite DB         │  │  Jetpack Compose UI & Service        │
└──────────────────────────────────────┘  └──────────────────────────────────────┘
```

---

## 🔌 Hardware Connections Summary

| Module | Pin Label | ESP32 GPIO | Bus / Signal | Description |
| :--- | :--- | :--- | :--- | :--- |
| **SSD1306 OLED** | SDA | **GPIO 22** | I2C Data | 128x64 Monochrome Display |
| | SCL | **GPIO 21** | I2C Clock | Clocked at 400 kHz |
| **INMP441 Mic** | WS | **GPIO 19** | I2S0 Word Select | Frame clock (16 kHz) |
| | SCK | **GPIO 18** | I2S0 Clock | Continuous bit clock |
| | SD | **GPIO 23** | I2S0 Serial Data | Mic audio input to ESP32 |
| | L/R | GND | Channel Select | Left channel (tied to GND) |
| **MAX98357A Amp** | LRC | **GPIO 27** | I2S1 Word Select | Frame clock |
| | BCLK | **GPIO 26** | I2S1 Bit Clock | Bit clock |
| | DIN | **GPIO 25** | I2S1 Serial Data | Audio data output from ESP32 |
| | VIN | 5V / VIN | Power (5V Rail) | Clean 5V power supply |

> 📖 For full electrical schematics, power decoupling guidelines, and audio layout recommendations, see [HARDWARE.md](HARDWARE.md).

---

## 📂 Project Structure

```text
saba-ai/
├── README.md               # Project overview and documentation
├── HARDWARE.md             # Complete wiring matrix, electrical design & pinouts
├── PROTOCOL.md             # RoboDesk Protocol (RDP v1.0) specification
├── requirements.txt        # Python dependency manifest
├── pyproject.toml          # Modern Python packaging configuration
├── firmware/
│   └── esp32/
│       └── esp32.ino       # Dual-core FreeRTOS ESP32 firmware (I2S DMA, OLED face, RDP v1.0)
├── android/                # Native Android Mobile Host Application (Jetpack Compose)
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/ai/saba/app/
│   │   │   │   ├── MainActivity.kt        # Compose host activity & permissions
│   │   │   │   ├── service/
│   │   │   │   │   └── SabaHostService.kt # Foreground service & notification controller
│   │   │   │   ├── protocol/
│   │   │   │   │   └── RdpWebSocketServer.kt # RFC-6455 WebSocket Server & RDP v1.0 router
│   │   │   │   ├── ai/
│   │   │   │   │   ├── SabaVoiceCoordinator.kt # Voice state machine & Sherpa KWS/VAD
│   │   │   │   │   └── OpenAiPipelineClient.kt # Direct audio-to-LLM & cloud TTS
│   │   │   │   ├── config/
│   │   │   │   │   └── SabaSettings.kt    # SharedPreferences persistent config store
│   │   │   │   ├── audio/
│   │   │   │   │   └── AudioUtils.kt      # PCM16/WAV encoders & audio meters
│   │   │   │   └── ui/
│   │   │   │       ├── AssistantTab.kt    # Assistant status, chat tester & live logs
│   │   │   │       ├── RobotPanelTab.kt   # Walkie-talkie, mic listen, 2D gaze, OLED & telemetry
│   │   │   │       └── SettingsTab.kt     # Model API keys, endpoints & tuning
│   │   │   ├── assets/
│   │   │   │   ├── vad/silero_vad.onnx    # On-device Silero VAD model
│   │   │   │   └── kws/                   # On-device Sherpa-ONNX "صبا" wake-word model
│   │   │   └── AndroidManifest.xml
│   │   └── libs/
│   │       └── sherpa-onnx-2.0.1.aar      # Embedded on-device ASR/KWS native library
│   └── build.gradle.kts
├── server/                 # Modernized Saba AI Python Server Package
│   ├── config.py           # Centralized configuration (Network, Audio, Models, Prompts)
│   ├── main.py             # Server launcher & async orchestrator
│   ├── core/               # Communication & protocol layer
│   │   ├── session.py      # RobotSession: RDP v1.0 state, RPC futures & telemetry
│   │   ├── server.py       # RoboDeskHostServer: WebSocket listener & event router
│   │   └── streamer.py     # PacedAudioStreamer: Flow control to ESP32 jitter buffer
│   ├── ai/                 # AI Engine pipelines
│   │   ├── assistant.py    # RoboDeskAssistant: Voice conversation state machine
│   │   ├── asr.py          # Shenava ASR & Silero Streaming VAD
│   │   ├── tts.py          # Pocket TTS Persian speech synthesis
│   │   ├── llm.py          # LLM engine (Ollama/OpenAI) with Queen Saba persona
│   │   └── text_processor.py # Persian text normalizer, wake-word & intent detection
│   ├── db/                 # Database persistence layer
│   │   └── database.py     # Peewee SQLite models (Conversation, Message)
│   ├── utils/              # Audio & typography utility functions
│   │   ├── audio.py        # PCM/WAV conversion, resamplers, melody generator
│   │   └── display.py      # Server-side Persian/Arabic typography bitmap renderer
│   └── cli/                # Interactive terminal interface
│       └── console.py      # Full-featured interactive CLI shell & command handler
├── tests/                  # Automated unit and integration test suite
│   ├── test_nlp.py         # Unit tests for text normalization, wake-word, face tags
│   ├── test_audio_utils.py # Unit tests for resampling, tone generation & WAV I/O
│   ├── test_database.py    # Unit tests for conversation & message persistence
│   ├── test_display.py     # Unit tests for Persian bitmap typography rendering
│   ├── test_playback.py    # Unit tests for paced audio streaming & barge-in abort
│   └── test_simulation.py  # Full WebSocket end-to-end simulated robot test
├── python_server/          # Backward-compatibility shims for existing workflows
├── arduino/
│   └── main.ino            # Arduino IDE legacy sketch path
└── ai_models/              # Local AI weights (Shenava ASR & Pocket TTS)
```

---

## 🚀 Quick Start

### 1. Flash the ESP32 Firmware
1. Open [`firmware/esp32/esp32.ino`](firmware/esp32/esp32.ino) in the Arduino IDE or PlatformIO.
2. Install the required Arduino libraries:
   - `WebSockets` (by Markus Sattler)
   - `ArduinoJson` (v6+)
   - `Adafruit SSD1306` & `Adafruit GFX Library`
3. Select board: **ESP32 Dev Module**.
4. Flash the code to the ESP32.
5. On initial boot, connect your phone or laptop to the Wi-Fi AP **`RoboDesk-Setup`** (password: `12345678`) and configure your local Wi-Fi and host server endpoint (`ws://<HOST_IP>:8765/ws/robot`).

---

### 2. Option A: Run the Python Host Server

1. **Activate the Python virtual environment:**
   ```bash
   source .venv/bin/activate
   ```
2. **Launch the RDP v1.0 host server:**
   ```bash
   python server/main.py
   ```
3. **Interactive CLI Command Reference:**
   ```text
   auto <on|off>                : Toggle autonomous voice assistant
   wake                         : Wake up robot assistant into active conversation
   sleep                        : Put robot assistant to standby mode (shows OLED text)
   say <text>                   : Synthesize and stream Persian text to robot via TTS
   face <expression> [ms]       : Set face (normal, happy, surprised, sleepy, thinking, talking, singing)
   gaze <x> <y> [hold_ms]       : Direct eye gaze coordinates (-1.0 to 1.0)
   blink                        : Trigger immediate natural eye blink
   text <title> | <body> [ms]   : Display full-screen text overlay on OLED
   clear_text                   : Dismiss full-screen text overlay
   mic <on|off>                 : Enable/disable microphone streaming
   volume <0.0-1.0>             : Set speaker master volume
   tone <freq> [ms] [vol]       : Synthesize tone on robot hardware
   stop | abort                 : Stop active audio playback or barge-in abort
   play_synth                   : Stream synthesized PCM melody (singing face)
   play_wav <path.wav>          : Stream a WAV audio file
   play_song <path.wav>         : Play music track with singing face & musical notes animation
   record <seconds> [out.wav]   : Record robot microphone audio to WAV file
   telemetry <on|off>           : Toggle live telemetry console stream
   caps                         : Query system capabilities (system.get_capabilities)
   ping                         : Send system.ping to measure latency
   reboot [delay_ms]            : Reboot robot
   exit | quit                  : Shut down host server and exit
   ```

---

### 3. Option B: Run the Android Host App

1. **Build and Install:**
   - Open the [`android/`](android/) project in Android Studio (Giraffe / Iguana / Koala or newer), or build via command-line:
     ```bash
     cd android
     ./gradlew assembleDebug
     ```
   - The compiled APK is generated at:
     ```text
     android/app/build/outputs/apk/debug/app-debug.apk
     ```
   - Install the APK on your Android device (Android 8.0+ / API 26+).
2. **Permissions:**
   - Launch the app and grant the required **Microphone** and **Notifications** permissions.
3. **Connecting the Robot:**
   - Ensure the Android phone and the ESP32 are connected to the same Wi-Fi network (or turn on the phone's Wi-Fi Hotspot and connect ESP32 to it).
   - The app displays its local IP at the top of the screen (e.g. `ws://192.168.43.1:8765/ws/robot`).
   - If needed, connect to `RoboDesk-Setup` AP on the ESP32 and save this endpoint.
4. **App Features & Tabs:**
   - **Assistant Tab:** Live connection state, on-device Silero VAD speech meter, Sherpa-ONNX wake-word trigger ("صبا"), conversation history, and real-time logs.
   - **Robot Panel Tab:**
     - **Walkie-Talkie:** Hold the button to speak into the phone's microphone; your voice is transmitted instantly to the robot's speaker!
     - **Listen to Robot Mic:** Tap to hear what the robot hears through its INMP441 microphone directly on your phone's speaker.
     - **2D Gaze Touchpad:** Move the robot's eyes by dragging your finger across the interactive 2D pad.
     - **Expressions & OLED:** Test facial animations and send text overlays.
     - **Telemetry & Health:** Monitor live Wi-Fi signal (RSSI), free heap memory, buffer status, and mic drop rates.
   - **Settings Tab:** Configure custom OpenAI / Gemini API keys, LLM models (`gemini-2.5-flash`, `gpt-4o-audio-preview`), TTS endpoints, system prompt, and VAD sensitivity.

---

### 4. Run the Automated Tests

The repository includes a comprehensive unit and integration test suite covering NLP normalizers, audio conversion, database persistence, Persian OLED typography, audio streaming pacing, and a full end-to-end WebSocket robot simulation:

```bash
source .venv/bin/activate
python -m unittest discover -s tests -p "test_*.py"
```

---

## 🎨 Persian OLED Typography Engine (`server/utils/display.py`)

Microcontroller graphics libraries such as Adafruit_GFX only support standard 7-bit ASCII Latin characters. They cannot render Persian or Arabic script because:
1. Persian is written **Right-to-Left (RTL)**.
2. Persian characters are **context-sensitive (cursive/ligatures)**, meaning their visual shapes change depending on their position in a word (isolated, initial, medial, final).

Saba AI solves this cleanly with a **Server-Side Bitmap Typography Renderer**:
- The Python server takes arbitrary Persian/Arabic text and uses Pillow (PIL) along with system TrueType fonts (such as **Vazirmatn** or **Noto Sans Arabic**) to shape and rasterize the text.
- It produces a compact **1024-byte 1-bit monochrome bitmap** (128x64 pixels, 1 bit per pixel, packed MSB-first).
- The bitmap is base64-encoded and sent over WebSocket via the RDP method `display.show_bitmap`.
- The ESP32's SSD1306 OLED driver draws the raw bitmap directly into its frame buffer using `drawBitmap()`, rendering beautiful, crisp Persian typography on the robot!

---

## 📜 Communication Protocol (RDP v1.0)

RoboDesk uses **RDP v1.0** over WebSockets. Text frames carry standard JSON-RPC 2.0-style envelopes, while binary frames carry raw linear 16 kHz 16-bit mono PCM audio in both directions.

For the complete technical specification of JSON-RPC methods (`face.set_expression`, `audio.set_mic_capture`, `audio.abort`, `display.show_bitmap`, `system.reboot`), telemetry schemas, and binary audio framing, see [PROTOCOL.md](PROTOCOL.md).

---

## 📄 License

This project is licensed under the **Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International License (CC BY-NC-SA 4.0)**.

- **Attribution (BY):** You must give appropriate credit, provide a link to the license, and indicate if changes were made.
- **NonCommercial (NC):** You may not use the material for commercial purposes (selling the robot, proprietary commercial hardware products, or paid cloud services without authorization).
- **ShareAlike (SA):** If you remix, transform, or build upon the material, you must distribute your contributions under the same license as the original.

See [LICENSE](LICENSE) for the full legal text. For commercial licensing inquiries or proprietary enterprise permissions, please contact the project maintainers.
