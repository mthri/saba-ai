# RoboDesk Protocol (RDP) Specification — Version 1.0

## 1. Overview & Transport Architecture

RoboDesk Protocol (RDP) is a lightweight, bidirectional, RPC and event-driven communication standard designed for desk companion robots. It decouples the embedded hardware (ESP32) from the host brain (Python server, mobile application, or cloud gateway).

### 1.1 Physical & Transport Layer

* **Transport:** WebSocket over standard TCP/IP (`ws://<host>:<port>/ws/robot`).


* **Default Port:** `8765`.


* **Framing Mechanism:**
* **Text Frames (UTF-8):** Structured JSON messages following the RDP Envelope specification (RPC calls, RPC responses, asynchronous telemetry, and events).
* **Binary Frames:** Raw PCM audio streams (Linear PCM, 16,000 Hz, 16-bit Signed Little-Endian, Mono).





```
                     ┌─────────────────────────────────────────┐
                     │            WebSocket Stream             │
                     └────┬───────────────────────────────┬────┘
                          │                               │
            Text Frames (JSON Envelope)          Binary Frames (PCM)
                          │                               │
            ┌─────────────┴─────────────┐         ┌───────┴───────┐
            │ - RPC Requests (req)      │         │ Upstream:     │
            │ - RPC Responses (res)     │         │ Mic -> Host   │
            │ - Async Events (event)    │         │               │
            │ - System Telemetry        │         │ Downstream:   │
            └───────────────────────────┘         │ Host -> Spk   │
                                                  └───────────────┘

```

---

## 2. Audio Pipeline & Concurrency Rules

### 2.1 Audio Specifications

* **Format:** Linear PCM (Uncompressed).


* **Bit Depth:** 16-bit Signed Integer (`int16_t`), Little-Endian.


* **Channels:** 1 (Mono).


* **Sample Rate:** 16,000 Hz (16 kHz).


* **Upstream Chunks (Mic $\rightarrow$ Host):** 2048 bytes (~64 ms / 1024 samples per frame).


* **Downstream Chunks (Host $\rightarrow$ Speaker):** 512 to 2048 bytes (16 ms to 64 ms per frame).



### 2.2 Auto-Mute & Echo Avoidance (Half-Duplex Default)

To eliminate acoustic feedback loops between the on-board speaker (MAX98357A) and microphone (INMP441) on compact desk chassis:

1. **Playback Suppression:** Whenever the ESP32 speaker jitter buffer contains active audio data or I2S TX is active (`spkPlaying == true`), the microphone capture pipeline drops incoming audio frames and suppresses upstream binary network frames.


2. **Immediate Barge-In (`audio.abort`):** When the host detects a user voice interrupt via wake-word or VAD, it sends an `audio.abort` RPC request. The ESP32 immediately flushes its 24 KB jitter buffer, stops I2S TX DMA, resets audio amplitude levels, and un-mutes microphone streaming.


3. **End of Stream (`audio.eos`):** Following the transmission of the final binary audio packet, the host sends `audio.eos`. The robot drains the remaining buffered PCM data, returns the expression to idle, and restores mic streaming.



---

## 3. Display Subsystem & Full-Screen Text Mode

The display is an OLED display with 128x64 resolution (SSD1306) driven via I2C at 400 kHz.

### 3.1 Display Modes

1. **Procedural Face Mode (Default):**
* Renders parametric eye animations (blinking, saccadic eye movements, pupil morphing, emotion states) at ~25 FPS.




2. **Full-Screen Text Mode:**
* Temporarily pauses eye rendering and overrides the display buffer.
* Displays high-contrast system notifications, user transcripts, answers, or network diagnostics across the entire 128x64 canvas.
* Supports configurable timeout (`timeout_ms`). When the timer expires (or on explicit dismissal), the display engine resumes the current face expression automatically.



---

## 4. JSON Message Specification (Envelope)

All textual frames must conform to a standardized JSON schema containing protocol version (`v`) and frame kind (`kind`).

### 4.1 Frame Types

* `"req"`: Request issued by client/host expecting a correlated response.
* `"res"`: Response returned to acknowledge and answer a `"req"`.
* `"event"`: Unidirectional notification or telemetry event (fire-and-forget).

### 4.2 Schema Structures

#### Request (`req`)

```json
{
  "v": "1.0",
  "kind": "req",
  "id": "req-string-id",
  "method": "namespace.action",
  "params": {}
}

```

#### Response (`res`)

```json
{
  "v": "1.0",
  "kind": "res",
  "id": "req-string-id",
  "status": "ok",
  "result": {},
  "error": null
}

```

#### Error Response (`res` with error)

```json
{
  "v": "1.0",
  "kind": "res",
  "id": "req-string-id",
  "status": "error",
  "result": null,
  "error": {
    "code": 400,
    "message": "Invalid parameters"
  }
}

```

#### Asynchronous Event (`event`)

```json
{
  "v": "1.0",
  "kind": "event",
  "topic": "namespace.signal",
  "data": {}
}

```

---

## 5. Method & Event Catalog

### 5.1 System Subsystem (`system.*`)

#### `event: system.hello`

Transmitted by the robot immediately upon establishing a WebSocket connection.

```json
{
  "v": "1.0",
  "kind": "event",
  "topic": "system.hello",
  "data": {
    "device_id": "robo-desk-01",
    "firmware": "rtos-1.0.0",
    "hardware": {
      "chip": "ESP32-D0WD-V3",
      "display": "ssd1306_128x64",
      "audio_in": "inmp441_pcm16_16khz",
      "audio_out": "max98357a_pcm16_16khz",
      "sensors": ["mpu6050", "hcsr04"]
    }
  }
}

```

#### `req: system.get_capabilities`

Queries the hardware peripherals, active configurations, and attached sensors.

* **Request:**
```json
{
  "v": "1.0",
  "kind": "req",
  "id": "sys-01",
  "method": "system.get_capabilities",
  "params": {}
}

```


* **Response:**
```json
{
  "v": "1.0",
  "kind": "res",
  "id": "sys-01",
  "status": "ok",
  "result": {
    "device_id": "robo-desk-01",
    "modules": {
      "mic": { "present": true, "enabled": true, "rate": 16000 },
      "speaker": { "present": true, "enabled": true, "rate": 16000 },
      "display": { "present": true, "enabled": true, "driver": "ssd1306" },
      "ultrasonic": { "present": true, "enabled": false },
      "gyro": { "present": true, "enabled": false }
    }
  },
  "error": null
}

```



#### `req: system.ping`

Checks logical link liveness.

* **Request:**
```json
{
  "v": "1.0",
  "kind": "req",
  "id": "ping-10",
  "method": "system.ping",
  "params": {}
}

```


* **Response:**
```json
{
  "v": "1.0",
  "kind": "res",
  "id": "ping-10",
  "status": "ok",
  "result": { "timestamp": 1729839401 },
  "error": null
}

```



#### `req: system.reboot`

Triggers software restart on the microcontroller.

* **Request:**
```json
{
  "v": "1.0",
  "kind": "req",
  "id": "reb-01",
  "method": "system.reboot",
  "params": { "delay_ms": 500 }
}

```



---

### 5.2 Hardware Power & Task Control (`hardware.*`)

#### `req: hardware.set_power_state`

Enables or suspends internal FreeRTOS worker tasks and peripheral clocks to reduce power, memory pressure, and interrupt overhead without tearing down memory rings.

* **Parameters:**
* `module` (string): `"mic"` | `"speaker"` | `"display"` | `"sensors"`
* `enabled` (boolean): `true` to resume processing, `false` to suspend.


* **Request:**
```json
{
  "v": "1.0",
  "kind": "req",
  "id": "pwr-01",
  "method": "hardware.set_power_state",
  "params": {
    "module": "mic",
    "enabled": false
  }
}

```


* **Response:**
```json
{
  "v": "1.0",
  "kind": "res",
  "id": "pwr-01",
  "status": "ok",
  "result": { "module": "mic", "state": "suspended" },
  "error": null
}

```



---

### 5.3 Face & Animation Engine (`face.*`)

#### `req: face.set_expression`

Sets the procedural facial emotion.

* **Parameters:**
* `name` (string): `"normal"` | `"happy"` | `"surprised"` | `"sleepy"` | `"thinking"` | `"talking"` | `"singing"`


* `duration_ms` (integer, optional): Revert back to `"normal"` after this duration (0 = persist).




* **Request:**
```json
{
  "v": "1.0",
  "kind": "req",
  "id": "face-01",
  "method": "face.set_expression",
  "params": {
    "name": "happy",
    "duration_ms": 4000
  }
}

```


* **Response:**
```json
{
  "v": "1.0",
  "kind": "res",
  "id": "face-01",
  "status": "ok",
  "result": { "active_expression": "happy" },
  "error": null
}

```



#### `req: face.set_gaze`

Directs eye gaze offsets explicitly.

* **Parameters:**
* `x` (float): Normalized horizontal offset between `-1.0` (left) and `1.0` (right).


* `y` (float): Normalized vertical offset between `-1.0` (up) and `1.0` (down).


* `hold_ms` (integer, optional): Milliseconds before autonomous saccade resumes.




* **Request:**
```json
{
  "v": "1.0",
  "kind": "req",
  "id": "gaze-01",
  "method": "face.set_gaze",
  "params": {
    "x": 0.85,
    "y": -0.20,
    "hold_ms": 2500
  }
}

```



#### `req: face.blink`

Triggers an immediate forced eye blink animation.

* **Request:**
```json
{
  "v": "1.0",
  "kind": "req",
  "id": "blink-01",
  "method": "face.blink",
  "params": {}
}

```



---

### 5.4 Full-Screen Text Display (`display.*`)

#### `req: display.show_text`

Overrides the facial rendering engine to display multi-line ASCII/English text across the entire 128x64 screen area.

* **Parameters:**
* `title` (string, optional): Header line drawn at the top.
* `body` (string): Text content (newline `\n` characters supported for auto-wrap).
* `font_size` (integer): `1` (6x8 font, up to 21 chars/line) or `2` (12x16 font, up to 10 chars/line).
* `timeout_ms` (integer): Auto-return to face engine after this duration. If `0`, screen remains until `display.clear_text` is called.


* **Request:**
```json
{
  "v": "1.0",
  "kind": "req",
  "id": "disp-01",
  "method": "display.show_text",
  "params": {
    "title": "[ WEATHER ]",
    "body": "Tehran: 22C\nSunny & Clear",
    "font_size": 1,
    "timeout_ms": 5000
  }
}

```


* **Response:**
```json
{
  "v": "1.0",
  "kind": "res",
  "id": "disp-01",
  "status": "ok",
  "result": { "screen_state": "text_active" },
  "error": null
}

```



#### `req: display.clear_text`

Dismisses the active full-screen text display and restores the face animation engine immediately.

* **Request:**
```json
{
  "v": "1.0",
  "kind": "req",
  "id": "disp-02",
  "method": "display.clear_text",
  "params": {}
}
```

#### `req: display.show_bitmap`

Displays a 1-bit monochrome 128x64 bitmap (1024 bytes) rendered by the host server (e.g. Persian/Arabic text, icons, QR codes).

* **Parameters:**
* `bitmap_base64` (string, optional): Base64-encoded 1024 bytes of 1-bit bitmap data.
* `bitmap_hex` (string, optional): Hex-encoded 2048 chars of 1-bit bitmap data.
* `timeout_ms` (integer): Auto-return to face engine after this duration. If `0`, screen remains until `display.clear_bitmap` is called.

* **Request:**
```json
{
  "v": "1.0",
  "kind": "req",
  "id": "disp-03",
  "method": "display.show_bitmap",
  "params": {
    "bitmap_base64": "<1024 bytes base64>",
    "timeout_ms": 5000
  }
}
```

#### `req: display.clear_bitmap`

Dismisses the active full-screen bitmap display and restores the face animation engine immediately.

```



---

### 5.5 Audio Subsystem (`audio.*`)

#### `req: audio.set_mic_capture`

Enables or disables continuous microphone sampling and binary streaming to the host.

* **Parameters:**
* `stream` (boolean): `true` to begin streaming upstream binary frames, `false` to silence.




* **Request:**
```json
{
  "v": "1.0",
  "kind": "req",
  "id": "aud-01",
  "method": "audio.set_mic_capture",
  "params": {
    "stream": true
  }
}

```



#### `req: audio.abort`

Executes an immediate audio barge-in: flushes speaker jitter buffers, halts active I2S TX DMA, resets playback states, and re-enables microphone capture.

* **Request:**
```json
{
  "v": "1.0",
  "kind": "req",
  "id": "aud-02",
  "method": "audio.abort",
  "params": {}
}

```


* **Response:**
```json
{
  "v": "1.0",
  "kind": "res",
  "id": "aud-02",
  "status": "ok",
  "result": { "drained_bytes": 12288, "spk_state": "idle" },
  "error": null
}

```



#### `req: audio.eos`

Informs the robot that the host has transmitted all binary audio packets for the current utterance. The robot drains any remaining PCM data buffered in the jitter queue before transitioning to idle.

* **Request:**
```json
{
  "v": "1.0",
  "kind": "req",
  "id": "aud-03",
  "method": "audio.eos",
  "params": {}
}

```



#### `req: audio.set_volume`

Configures software amplification factor applied to incoming PCM samples before DMA output.

* **Parameters:**
* `level` (float): Master volume from `0.0` (mute) to `1.0` (100%).


* **Request:**
```json
{
  "v": "1.0",
  "kind": "req",
  "id": "vol-01",
  "method": "audio.set_volume",
  "params": {
    "level": 0.80
  }
}

```



#### `req: audio.play_tone`

Triggers synthetic tone synthesis directly inside the speaker task.

* **Parameters:**
* `freq` (float): Frequency in Hertz.


* `duration_ms` (integer): Duration in milliseconds.


* `volume` (float, optional): Volume factor between `0.0` and `1.0` (default: 0.25).




* **Request:**
```json
{
  "v": "1.0",
  "kind": "req",
  "id": "tone-01",
  "method": "audio.play_tone",
  "params": {
    "freq": 1046.5,
    "duration_ms": 150,
    "volume": 0.3
  }
}

```



---

### 5.6 Telemetry & Sensors (`sensor.*`)

#### `event: sensor.telemetry`

Transmitted periodically (e.g., every 2000 ms) by the robot to report link performance, buffer metrics, and hardware health.

```json
{
  "v": "1.0",
  "kind": "event",
  "topic": "sensor.telemetry",
  "data": {
    "free_heap": 178240,
    "wifi_rssi": -58,
    "spk_buffered_bytes": 0,
    "spk_underruns": 0,
    "mic_drops": 0,
    "mic_level": 0.02,
    "spk_level": 0.00
  }
}

```

#### `event: sensor.trigger`

Transmitted immediately when physical interaction thresholds are reached (e.g., MPU6050 bump/tilt or HC-SR04 proximity trigger).

```json
{
  "v": "1.0",
  "kind": "event",
  "topic": "sensor.trigger",
  "data": {
    "sensor": "mpu6050",
    "type": "shake",
    "intensity": 2.85
  }
}

```

---

## 6. End-to-End Conversation Flow Diagram

```text
Host (Python / Edge Core)                        Robot (ESP32 Firmware)
        │                                                  │
        │ <─── WebSocket Connection Established ────────── │
        │ <─── event: system.hello ─────────────────────── │
        │                                                  │
        │ ─── req: audio.set_mic_capture (stream: true) ─> │
        │ <── res: ok ──────────────────────────────────── │
        │                                                  │
        │ <── [Binary PCM Audio: Mic 64ms chunks] ──────── │
        │     (User speaks: "What time is it?")            │
        │                                                  │
[VAD triggers speech end]                                  │
[STT -> LLM generates reply]                               │
        │                                                  │
        │ ─── req: face.set_expression ("thinking") ─────> │
        │ ─── req: face.set_expression ("happy") ────────> │
        │                                                  │
        │ ─── [Binary PCM Audio: Spk Chunks] ────────────> │
        │     (Mic stream automatically auto-muted)        │
        │                                                  │
        │ ─── req: audio.eos ────────────────────────────> │
        │ <── res: ok ──────────────────────────────────── │
        │     (Playback completes; Mic auto-unmuted)       │
        │                                                  │
```