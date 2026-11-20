# RoboDesk Hardware & Wiring Specification

## 1. System Overview & Core Controller

The hardware architecture is driven by a 30-pin ESP32 Dev Module (ESP32-D0WD-V3) running dual-core FreeRTOS firmware. The board coordinates peripheral rendering via I2C and bidirectional digital audio streaming across dual dedicated I2S buses.

### Core Peripherals

* **Microcontroller:** ESP32 Dev Module (30-Pin, 240 MHz, 4MB Flash, ~320KB SRAM)
* **Display:** 0.96-inch SSD1306 monochrome OLED (128x64 pixels, I2C address `0x3C`)
* **Microphone (Audio In):** INMP441 Omnidirectional MEMS microphone (Digital I2S, Port 0 RX)
* **Audio Out (DAC/Amp):** MAX98357A Mono Class-D amplifier module (Digital I2S, Port 1 TX)
* **Speaker:** 4Ω to 8Ω, 2W–3W micro dynamic speaker unit

---

## 2. Complete Master Wiring Matrix

The wiring layout is optimized for clean breadboard routing, separating 3.3V logic peripherals (OLED and Microphone) on one side and 5V power peripherals (Speaker Amplifier) on the other.

| Module | Pin Label | ESP32 GPIO / Pin | Electrical Net / Signal | Voltage / Level | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **SSD1306 OLED** | VCC | **3V3** | Power (3.3V Rail) | 3.3V DC | Connect strictly to 3.3V rail (never 5V) |
| | GND | **GND** | Ground | 0V | Common system ground |
| | SDA | **GPIO 22** | I2C Data (SDA) | 3.3V Logic | Hardware pull-ups present on module |
| | SCL | **GPIO 21** | I2C Clock (SCL) | 3.3V Logic | Clocked at 400 kHz (`Wire.setClock`) |
| **INMP441 Mic** | VDD | **3V3** | Power (3.3V Rail) | 3.3V DC | Requires clean, decoupled 3.3V |
| | GND | **GND** | Ground | 0V | Common system ground |
| | L/R | **GND** | Channel Select | 0V | Pull to GND for Left Channel select |
| | WS | **GPIO 19** | I2S0 Word Select (WS/LRCK) | 3.3V Logic | Frame clock (16 kHz) |
| | SCK | **GPIO 18** | I2S0 Continuous Clock (SCK/BCLK) | 3.3V Logic | Bit clock line |
| | SD | **GPIO 23** | I2S0 Serial Data (SD) | 3.3V Logic | Serial PCM audio input to ESP32 |
| **MAX98357A Amp** | VIN | **5V / VIN** | Power (USB 5V Rail) | 5.0V DC | 5V provides up to 3.2W output (3.3V is underpowered) |
| | GND | **GND** | Ground | 0V | Common system ground |
| | LRC | **GPIO 27** | I2S1 Word Select (LRC/WS) | 3.3V Logic | Left/Right frame clock |
| | BCLK | **GPIO 26** | I2S1 Bit Clock (BCLK) | 3.3V Logic | Bit clock line |
| | DIN | **GPIO 25** | I2S1 Serial Data (DIN) | 3.3V Logic | Audio PCM data stream from ESP32 |
| | GAIN | **GND / NC** | Gain Setting | — | Leave floating for 9 dB; tie to GND for 12 dB |
| | SD_MODE | **Unconnected** | Mode / Shutdown | — | Leave unconnected for (L + R) / 2 mix |
| **Speaker** | + | **OUT+** | Balanced Audio Output | Differential | Connect directly to speaker positive terminal |
| | - | **OUT-** | Balanced Audio Output | Differential | Never connect OUT- to GND or chassis |

---

## 3. Subsystem Hookup Details

### 3.1 Display Interface (SSD1306 I2C)

* **Bus:** Hardware I2C configured on custom pins (`SDA = GPIO 22`, `SCL = GPIO 21`).
* **Power Supply:** Connect to the ESP32 `3V3` pin.
* **Pull-up Resistors:** Most breakout boards feature onboard 4.7 kΩ pull-ups to 3.3V. External pull-ups are not required unless using excessively long jumper wires (> 15 cm).

```text
   ESP32-D0WD-V3                     SSD1306 OLED
 ┌───────────────┐                 ┌───────────────┐
 │          3V3  ├─────────────────┤ VCC           │
 │          GND  ├─────────────────┤ GND           │
 │       GPIO22  ├─────────────────┤ SDA           │
 │       GPIO21  ├─────────────────┤ SCL           │
 └───────────────┘                 └───────────────┘
```

---

### 3.2 Microphone Interface (INMP441 I2S0 RX)

* **Bus:** Dedicated I2S Controller 0 configured in Master Receive mode (`I2S_NUM_0`).
* **Channel Configuration:** `L/R` pin must be wired to **GND** to assign the microphone to the Left channel, matching the firmware driver format `I2S_CHANNEL_FMT_ONLY_LEFT`.
* **Voltage Rating:** Absolute maximum $V_{DD}$ is 3.6V. Never wire this pin to 5V.

```text
   ESP32-D0WD-V3                      INMP441 MEMS
 ┌───────────────┐                 ┌───────────────┐
 │          3V3  ├─────────────────┤ VDD           │
 │          GND  ├───┬─────────────┤ GND           │
 │               │   └─────────────┤ L/R (Left Ch) │
 │       GPIO19  ├─────────────────┤ WS            │
 │       GPIO18  ├─────────────────┤ SCK           │
 │       GPIO23  ├─────────────────┤ SD            │
 └───────────────┘                 └───────────────┘
```

---

### 3.3 Audio Amplifier Interface (MAX98357A I2S1 TX)

* **Bus:** Dedicated I2S Controller 1 configured in Master Transmit mode (`I2S_NUM_1`).
* **Power Supply:** Connect `VIN` to the ESP32 `VIN` / `5V` pin (direct power from USB). Driving the amplifier from 3.3V will degrade audio output, causing severe clipping at volume peaks.
* **Speaker Terminals:** The outputs (`+` and `-`) form a filterless Class-D bridge-tied load (BTL). **Never tie either speaker terminal to ground or to each other.**

```text
   ESP32-D0WD-V3                     MAX98357A DAC/AMP
 ┌───────────────┐                 ┌─────────────────┐
 │      VIN (5V) ├─────────────────┤ VIN             │       ┌─────────┐
 │          GND  ├─────────────────┤ GND        OUT+ ├───────┤ Speaker │
 │       GPIO27  ├─────────────────┤ LRC        OUT- ├───────┤ 4Ω / 3W │
 │       GPIO26  ├─────────────────┤ BCLK            │       └─────────┘
 │       GPIO25  ├─────────────────┤ DIN             │
 └───────────────┘                 └─────────────────┘
```

---

## 4. Power Architecture & Electrical Protection

```text
  5V USB Input 
       │
       ├───> [ ESP32 VIN ] ───> [ Onboard 3.3V LDO ] ──┬───> ESP32 Chip & Wi-Fi
       │                                               ├───> SSD1306 OLED
       │                                               └───> INMP441 Mic
       │
       └───> [ MAX98357A VIN ] ───> Class-D Stage ────> Speaker (+/-)
                 ▲
          [100µF-470µF Cap]
```

### 4.1 Transient Current & Brownout Prevention

* **Current Spikes:** When Wi-Fi transmission bursts coincide with high speaker output, transient current draws can exceed 600 mA – 800 mA.
* **Firmware Mitigation:** The firmware disables the hardware brownout detector in `setup()` via `WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG, 0)`.
* **Hardware Decoupling:** To prevent system resets and audio crackle, place an electrolytic capacitor (100 µF to 470 µF, 10V or higher) directly across `VIN` and `GND` near the MAX98357A module.
* **Host USB Supply:** Power the assembly via a direct USB 3.0 port (900 mA capability) or an external 5V 2A USB wall adapter. Avoid unpowered USB 2.0 hubs.

### 4.2 Noise Isolation & Grounding

* **Common Ground:** Maintain a single solid common ground reference between all modules.
* **I2S Crosstalk:** Keep digital audio signal runs (GPIO 19, 18, 23, 27, 26, 25) under 12 cm to minimize clock line ringing and high-frequency interference on the microphone input.
* **Acoustic Separation:** Isolate the INMP441 microphone physically from the speaker housing using soft silicone, foam, or gasket material to reduce chassis vibration transmission.

---

## 5. Pre-Power Verification Checklist

1. **Polarity Check:** Confirm that `VIN` and `3V3` nets are nowhere cross-connected. Connecting 5V to the INMP441 or SSD1306 will permanently damage the components.
2. **Channel Pull-Down:** Verify that pin `L/R` of the INMP441 is tied directly to `GND`.
3. **Floating Speaker Output:** Ensure the speaker terminals are isolated from the chassis and do not touch any system ground net.
4. **I2C Pull-Up Voltage:** Confirm that the OLED display operates strictly from the 3.3V rail to prevent 5V leakage into ESP32 GPIO 21 and 22.
5. **Cold Resistance Measurement:** Using a digital multimeter in resistance mode across `3V3` to `GND` and `VIN` to `GND`, ensure there are no short circuits (reading must be in the kΩ range or climbing as capacitors charge) before plugging in the USB cable.
