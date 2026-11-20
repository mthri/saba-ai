// Target: ESP32 Dev Module (30-Pin) | Core: esp32 by Espressif
// Protocol: RoboDesk Protocol (RDP) v1.0
// Architecture: Dual-Core FreeRTOS (Audio I2S RX/TX + OLED Face Engine + WebSocket RPC)

#include <Arduino.h>
#include <Wire.h>
#include <WiFi.h>
#include <WebServer.h>
#include <Preferences.h>
#include <WebSocketsClient.h>
#include <ArduinoJson.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <driver/i2s.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "freertos/ringbuf.h"
#include "soc/soc.h"
#include "soc/rtc_cntl_reg.h"

#define FIRMWARE_VERSION "rtos-1.0.0"
#define RDP_VERSION      "1.0"

// --- Default Provisioning AP Credentials ---
const char* AP_SSID = "RoboDesk-Setup";
const char* AP_PASS = "12345678";

// --- Hardware Pin Definitions (Optimized Breadboard Layout) ---
// Side 1 (3.3V Rail): OLED SSD1306
#define PIN_OLED_SDA    22
#define PIN_OLED_SCL    21

// Side 1 (3.3V Rail): INMP441 Microphone (I2S Port 0 RX)
#define I2S_MIC_PORT    I2S_NUM_0
#define PIN_MIC_WS      19
#define PIN_MIC_SCK     18
#define PIN_MIC_SD      23

// Side 2 (5V VIN Rail): MAX98357A Speaker Amplifier (I2S Port 1 TX)
#define I2S_SPK_PORT    I2S_NUM_1
#define PIN_SPK_LRC     27
#define PIN_SPK_BCLK    26
#define PIN_SPK_DIN     25

// --- Audio & Buffer Tuning ---
#define SAMPLE_RATE           16000
#define MIC_SHIFT             13        // 32-bit I2S word -> 16-bit PCM
#define MIC_READ_SAMPLES      256       // 16ms per DMA read in micTask
#define MIC_RING_BYTES        16384     // ~512ms ring buffer
#define MIC_SEND_BYTES        2048      // 64ms binary frame to host
#define SPK_RING_BYTES        24576     // ~768ms jitter buffer
#define SPK_CHUNK_FRAMES      256       // 16ms per I2S write
#define SPK_PREBUFFER_BYTES   4096      // 128ms pre-buffer threshold
#define SPK_TAIL_FLUSH_MS     60        // auto-flush tail timeout
#define SPK_MUTE_HOLD_MS      150       // hold mic muted after last speaker write
#define TONE_FADE_FRAMES      64        // 4ms envelope fade to prevent clicks
#define TELEMETRY_INTERVAL_MS 2000      // Periodic telemetry interval

// ============================================================================
// Persistent Configuration Manager (NVS)
// ============================================================================
struct RobotConfig {
    bool configured   = false;
    String wifiSsid   = "";
    String wifiPass   = "";
    String hostIp     = "192.168.1.100";
    uint16_t hostPort = 8765;
    String deviceId   = "robo-desk-01";
};

class ConfigManager {
private:
    Preferences prefs;
    const char* ns = "robocfg";

public:
    RobotConfig data;

    void load() {
        prefs.begin(ns, true);
        data.configured = prefs.getBool("cfg_ok", false);
        data.wifiSsid   = prefs.getString("ssid", "");
        data.wifiPass   = prefs.getString("pass", "");
        data.hostIp     = prefs.getString("host_ip", "192.168.1.100");
        data.hostPort   = prefs.getUShort("host_port", 8765);
        data.deviceId   = prefs.getString("dev_id", "robo-desk-01");
        prefs.end();
    }

    void save(const String& ssid, const String& pass, const String& ip, uint16_t port, const String& devId) {
        prefs.begin(ns, false);
        prefs.putBool("cfg_ok", true);
        prefs.putString("ssid", ssid);
        prefs.putString("pass", pass);
        prefs.putString("host_ip", ip);
        prefs.putUShort("host_port", port);
        prefs.putString("dev_id", devId);
        prefs.end();
        load();
    }

    void clear() {
        prefs.begin(ns, false);
        prefs.clear();
        prefs.end();
        load();
    }
};

// ============================================================================
// Audio Hardware Abstraction Layer (RTOS + Half-Duplex Auto-Mute)
// ============================================================================
struct ToneCmd {
    float freq;
    uint32_t durMs;
    float volume;
};

class AudioHal {
public:
    bool micReady = false;
    bool spkReady = false;

    volatile bool micPowerEnabled = true;
    volatile bool spkPowerEnabled = true;

    RingbufHandle_t micRing   = nullptr;
    RingbufHandle_t spkRing   = nullptr;
    QueueHandle_t   toneQueue = nullptr;

    volatile float    masterVolume   = 0.85f;
    volatile float    micLevel       = 0.0f;
    volatile float    spkLevel       = 0.0f;
    volatile bool     capture        = false;
    volatile bool     spkPlaying     = false;
    volatile bool     eosSignaled    = false;
    volatile uint32_t lastSpkRxMs    = 0;
    volatile uint32_t lastSpkWriteMs = 0;

    volatile uint32_t micDrops     = 0;
    volatile uint32_t spkDrops     = 0;
    volatile uint32_t spkUnderruns = 0;

    TaskHandle_t micTaskHandle = nullptr;
    TaskHandle_t spkTaskHandle = nullptr;

    bool begin() {
        micRing   = xRingbufferCreate(MIC_RING_BYTES, RINGBUF_TYPE_BYTEBUF);
        spkRing   = xRingbufferCreate(SPK_RING_BYTES, RINGBUF_TYPE_BYTEBUF);
        toneQueue = xQueueCreate(8, sizeof(ToneCmd));
        if (!micRing || !spkRing || !toneQueue) {
            Serial.println("[AUDIO] Failed to allocate ring buffers / queue!");
            return false;
        }

        micReady = initMicrophone();
        spkReady = initSpeaker();

        if (micReady) {
            xTaskCreatePinnedToCore(micTaskEntry, "micTask", 4096, this, 6, &micTaskHandle, 1);
        }
        if (spkReady) {
            xTaskCreatePinnedToCore(spkTaskEntry, "spkTask", 4096, this, 5, &spkTaskHandle, 1);
        }
        return micReady && spkReady;
    }

    bool isSpeakerActive() const {
        if (!spkPowerEnabled) return false;
        return spkPlaying || (speakerBufferedBytesConst() > 0) || ((millis() - lastSpkWriteMs) < SPK_MUTE_HOLD_MS);
    }

    size_t speakerBufferedBytesConst() const {
        if (!spkRing) return 0;
        size_t freeSz = xRingbufferGetCurFreeSize(spkRing);
        return (freeSz >= SPK_RING_BYTES) ? 0 : (SPK_RING_BYTES - freeSz);
    }

    size_t speakerBufferedBytes() {
        return speakerBufferedBytesConst();
    }

    bool setMicPower(bool enable) {
        if (!micReady) return false;
        if (enable == micPowerEnabled) return true;
        if (!enable) {
            micPowerEnabled = false;
            flushMic();
            i2s_stop(I2S_MIC_PORT);
            micLevel = 0.0f;
        } else {
            i2s_start(I2S_MIC_PORT);
            flushMic();
            micPowerEnabled = true;
        }
        return true;
    }

    bool setSpkPower(bool enable) {
        if (!spkReady) return false;
        if (enable == spkPowerEnabled) return true;
        if (!enable) {
            spkPowerEnabled = false;
            abortPlayback();
            i2s_stop(I2S_SPK_PORT);
        } else {
            i2s_start(I2S_SPK_PORT);
            spkPowerEnabled = true;
        }
        return true;
    }

    void flushMic() {
        if (!micRing) return;
        size_t n = 0;
        void* p;
        while ((p = xRingbufferReceiveUpTo(micRing, &n, 0, 4096)) != nullptr) {
            vRingbufferReturnItem(micRing, p);
        }
    }

    void setCapture(bool enable) {
        if (enable) flushMic();
        capture = enable;
    }

    void setVolume(float level) {
        masterVolume = constrain(level, 0.0f, 1.0f);
    }

    void notifyEos() {
        eosSignaled = true;
    }

    void enqueueSpeakerPcm(const uint8_t* data, size_t len) {
        if (!spkReady || !spkPowerEnabled || !spkRing) return;
        len &= ~((size_t)1);
        if (len == 0) return;
        lastSpkRxMs = millis();
        if (xRingbufferSend(spkRing, data, len, 0) != pdTRUE) {
            spkDrops++;
        }
    }

    void enqueueSpeakerPcmBlocking(const uint8_t* data, size_t len) {
        if (!spkReady || !spkPowerEnabled) return;
        size_t off = 0;
        while (off < len) {
            size_t n = min((size_t)1024, len - off);
            lastSpkRxMs = millis();
            if (xRingbufferSend(spkRing, data + off, n, pdMS_TO_TICKS(200)) == pdTRUE) {
                off += n;
            }
        }
    }

    size_t abortPlayback() {
        if (!spkReady) return 0;
        size_t drained = 0;
        size_t n = 0;
        void* p;
        while ((p = xRingbufferReceiveUpTo(spkRing, &n, 0, 4096)) != nullptr) {
            drained += n;
            vRingbufferReturnItem(spkRing, p);
        }
        xQueueReset(toneQueue);
        i2s_zero_dma_buffer(I2S_SPK_PORT);
        spkPlaying  = false;
        eosSignaled = false;
        spkLevel    = 0.0f;
        return drained;
    }

    void waitSpeakerIdle(uint32_t timeoutMs) {
        uint32_t t0 = millis();
        delay(40);
        while (millis() - t0 < timeoutMs) {
            if (!spkPlaying && speakerBufferedBytes() == 0 && uxQueueMessagesWaiting(toneQueue) == 0) break;
            delay(10);
        }
    }

    void playTone(float freqHz, uint32_t durationMs, float volume = 0.25f) {
        if (!spkReady || !spkPowerEnabled || !toneQueue) return;
        if (durationMs > 3000) durationMs = 3000;
        ToneCmd t = { freqHz, durationMs, volume };
        xQueueSend(toneQueue, &t, 0);
    }

    void playBootChime() {
        playTone(880.0f, 80, 0.25f);
        playTone(0.0f, 25, 0.0f);
        playTone(1318.5f, 120, 0.25f);
    }

    void playConfigModeChime() {
        playTone(659.25f, 90, 0.25f);
        playTone(0.0f, 30, 0.0f);
        playTone(523.25f, 140, 0.25f);
    }

    bool runEchoTest(bool restoreCaptureTo) {
        if (!micReady || !spkReady || !micPowerEnabled || !spkPowerEnabled) return false;

        const size_t needBytes = SAMPLE_RATE * sizeof(int16_t) * 1;
        uint8_t* rec = (uint8_t*)malloc(needBytes);
        if (!rec) {
            Serial.println("[ERROR] Heap allocation failed!");
            return false;
        }

        Serial.println("[AUDIO] Recording 1 second... Speak now!");
        setCapture(true);
        size_t got = 0;
        uint32_t t0 = millis();
        while (got < needBytes && (millis() - t0) < 3000) {
            size_t n = 0;
            uint8_t* p = (uint8_t*)xRingbufferReceiveUpTo(
                micRing, &n, pdMS_TO_TICKS(50), min((size_t)2048, needBytes - got));
            if (p) {
                memcpy(rec + got, p, n);
                got += n;
                vRingbufferReturnItem(micRing, p);
            }
        }
        setCapture(restoreCaptureTo);

        Serial.printf("[AUDIO] Captured %u bytes. Playing back...\n", (unsigned)got);
        enqueueSpeakerPcmBlocking(rec, got);
        notifyEos();
        waitSpeakerIdle(3000);
        free(rec);
        Serial.println("[AUDIO] Echo test complete.");
        return true;
    }

private:
    static void micTaskEntry(void* arg) { static_cast<AudioHal*>(arg)->micLoop(); }
    static void spkTaskEntry(void* arg) { static_cast<AudioHal*>(arg)->spkLoop(); }

    void micLoop() {
        static int32_t raw[MIC_READ_SAMPLES];
        static int16_t pcm[MIC_READ_SAMPLES];
        float dcPrevIn = 0.0f, dcPrevOut = 0.0f;
        int warmup = 8;

        for (;;) {
            if (!micPowerEnabled) {
                vTaskDelay(pdMS_TO_TICKS(40));
                continue;
            }

            size_t bytesRead = 0;
            esp_err_t err = i2s_read(I2S_MIC_PORT, raw, sizeof(raw), &bytesRead, pdMS_TO_TICKS(100));
            if (err != ESP_OK || bytesRead == 0) continue;

            size_t count = bytesRead / sizeof(int32_t);
            if (warmup > 0) { warmup--; continue; }

            if (isSpeakerActive()) {
                micLevel = 0.0f;
                continue;
            }

            int64_t sumSquares = 0;
            for (size_t i = 0; i < count; i++) {
                float x = (float)(raw[i] >> MIC_SHIFT);
                float y = x - dcPrevIn + 0.995f * dcPrevOut;
                dcPrevIn = x;
                dcPrevOut = y;
                int32_t v = (int32_t)y;
                if (v > 32767) v = 32767;
                if (v < -32767) v = -32767;
                pcm[i] = (int16_t)v;
                sumSquares += (int32_t)pcm[i] * pcm[i];
            }

            float rms = sqrtf((float)sumSquares / (float)count);
            micLevel = constrain(rms / 10000.0f, 0.0f, 1.0f);

            if (capture) {
                if (xRingbufferSend(micRing, pcm, count * sizeof(int16_t), 0) != pdTRUE) {
                    micDrops++;
                }
            }
        }
    }

    void spkLoop() {
        static int16_t stereo[SPK_CHUNK_FRAMES * 2];
        bool playing = false;

        ToneCmd tone;
        bool toneActive = false;
        uint32_t toneTotal = 0, toneLeft = 0;
        float phase = 0.0f, phaseInc = 0.0f, amp = 0.0f;
        bool toneSilent = false;

        for (;;) {
            if (!spkPowerEnabled) {
                spkPlaying = false;
                spkLevel = 0.0f;
                vTaskDelay(pdMS_TO_TICKS(40));
                continue;
            }

            if (toneActive) {
                spkPlaying = true;
                size_t frames = min((size_t)toneLeft, (size_t)SPK_CHUNK_FRAMES);
                uint32_t startPos = toneTotal - toneLeft;
                int64_sumSq:
                int64_t sumSq = 0;
                float volScale = masterVolume;
                for (size_t i = 0; i < frames; i++) {
                    float s = 0.0f;
                    if (!toneSilent) {
                        uint32_t pos = startPos + i;
                        uint32_t rem = toneTotal - pos;
                        float env = 1.0f;
                        if (pos < TONE_FADE_FRAMES) env = (float)pos / TONE_FADE_FRAMES;
                        if (rem < TONE_FADE_FRAMES) env = fminf(env, (float)rem / TONE_FADE_FRAMES);
                        s = sinf(phase) * amp * env * volScale;
                        phase += phaseInc;
                        if (phase >= 2.0f * PI) phase -= 2.0f * PI;
                    }
                    int16_t v = (int16_t)constrain(s, -32767.0f, 32767.0f);
                    stereo[i * 2]     = v;
                    stereo[i * 2 + 1] = v;
                    sumSq += (int32_t)v * v;
                }
                size_t written = 0;
                i2s_write(I2S_SPK_PORT, stereo, frames * 4, &written, portMAX_DELAY);
                lastSpkWriteMs = millis();
                spkLevel = constrain(sqrtf((float)sumSq / frames) / 10000.0f, 0.0f, 1.0f);
                toneLeft -= frames;
                if (toneLeft == 0) {
                    toneActive = false;
                    spkPlaying = false;
                    spkLevel   = 0.0f;
                }
                continue;
            }

            if (!playing) {
                size_t buffered = speakerBufferedBytes();
                bool enough = buffered >= SPK_PREBUFFER_BYTES;
                bool tail   = buffered > 0 && (eosSignaled || (millis() - lastSpkRxMs) > SPK_TAIL_FLUSH_MS);
                if (enough || tail) {
                    playing = true;
                } else {
                    if (buffered == 0) eosSignaled = false;
                    spkPlaying = false;
                    spkLevel   = 0.0f;
                    if (xQueueReceive(toneQueue, &tone, pdMS_TO_TICKS(5)) == pdTRUE) {
                        toneTotal  = toneLeft = (SAMPLE_RATE * tone.durMs) / 1000;
                        toneSilent = (tone.freq < 20.0f);
                        phase      = 0.0f;
                        phaseInc   = (2.0f * PI * tone.freq) / SAMPLE_RATE;
                        amp        = constrain(tone.volume, 0.0f, 1.0f) * 14000.0f;
                        toneActive = (toneLeft > 0);
                    }
                    continue;
                }
            }

            spkPlaying = true;
            size_t got = 0;
            uint8_t* p = (uint8_t*)xRingbufferReceiveUpTo(
                spkRing, &got, pdMS_TO_TICKS(30), SPK_CHUNK_FRAMES * sizeof(int16_t));
            if (!p) {
                playing = false;
                if (speakerBufferedBytes() == 0) {
                    eosSignaled = false;
                    spkPlaying  = false;
                } else {
                    spkUnderruns++;
                }
                continue;
            }

            got &= ~((size_t)1);
            size_t frames = got / 2;
            const int16_t* mono = reinterpret_cast<const int16_t*>(p);
            int64_t sumSq = 0;
            float vol = masterVolume;

            for (size_t i = 0; i < frames; i++) {
                int32_t scaled = (int32_t)((float)mono[i] * vol);
                int16_t s = (int16_t)constrain(scaled, -32767, 32767);
                stereo[i * 2]     = s;
                stereo[i * 2 + 1] = s;
                sumSq += (int32_t)s * s;
            }
            vRingbufferReturnItem(spkRing, p);

            size_t written = 0;
            i2s_write(I2S_SPK_PORT, stereo, frames * 4, &written, portMAX_DELAY);
            lastSpkWriteMs = millis();
            if (frames > 0) {
                spkLevel = constrain(sqrtf((float)sumSq / frames) / 10000.0f, 0.0f, 1.0f);
            }
        }
    }

    bool initMicrophone() {
        i2s_config_t micConfig = {
            .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX),
            .sample_rate = SAMPLE_RATE,
            .bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT,
            .channel_format = I2S_CHANNEL_FMT_ONLY_LEFT,
            .communication_format = I2S_COMM_FORMAT_STAND_I2S,
            .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
            .dma_buf_count = 8,
            .dma_buf_len = 256,
            .use_apll = false,
            .tx_desc_auto_clear = false,
            .fixed_mclk = 0
        };

        i2s_pin_config_t micPins = {
            .mck_io_num = I2S_PIN_NO_CHANGE,
            .bck_io_num = PIN_MIC_SCK,
            .ws_io_num = PIN_MIC_WS,
            .data_out_num = I2S_PIN_NO_CHANGE,
            .data_in_num = PIN_MIC_SD
        };

        if (i2s_driver_install(I2S_MIC_PORT, &micConfig, 0, NULL) != ESP_OK) return false;
        if (i2s_set_pin(I2S_MIC_PORT, &micPins) != ESP_OK) return false;
        return true;
    }

    bool initSpeaker() {
        i2s_config_t spkConfig = {
            .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX),
            .sample_rate = SAMPLE_RATE,
            .bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT,
            .channel_format = I2S_CHANNEL_FMT_RIGHT_LEFT,
            .communication_format = I2S_COMM_FORMAT_STAND_I2S,
            .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
            .dma_buf_count = 6,
            .dma_buf_len = 256,
            .use_apll = false,
            .tx_desc_auto_clear = true,
            .fixed_mclk = 0
        };

        i2s_pin_config_t spkPins = {
            .mck_io_num = I2S_PIN_NO_CHANGE,
            .bck_io_num = PIN_SPK_BCLK,
            .ws_io_num = PIN_SPK_LRC,
            .data_out_num = PIN_SPK_DIN,
            .data_in_num = I2S_PIN_NO_CHANGE
        };

        if (i2s_driver_install(I2S_SPK_PORT, &spkConfig, 0, NULL) != ESP_OK) return false;
        if (i2s_set_pin(I2S_SPK_PORT, &spkPins) != ESP_OK) return false;
        return true;
    }
};

// ============================================================================
// Hardware Presence Probes
// ============================================================================
bool checkI2CDevice(uint8_t address) {
    Wire.beginTransmission(address);
    return (Wire.endTransmission() == 0);
}

bool isGyroPresent() {
    return checkI2CDevice(0x68) || checkI2CDevice(0x69);
}

bool isUltrasonicPresent() {
    #if defined(PIN_US_TRIG) && defined(PIN_US_ECHO)
        pinMode(PIN_US_TRIG, OUTPUT);
        pinMode(PIN_US_ECHO, INPUT);
        digitalWrite(PIN_US_TRIG, LOW);
        delayMicroseconds(2);
        digitalWrite(PIN_US_TRIG, HIGH);
        delayMicroseconds(10);
        digitalWrite(PIN_US_TRIG, LOW);
        long duration = pulseIn(PIN_US_ECHO, HIGH, 10000);
        return (duration > 0);
    #else
        return false;
    #endif
}

// ============================================================================
// Procedural Face & Large Text Engine (SSD1306 Yellow-Top Eyebrows + Blue Eyes)
// ============================================================================
enum class Expression {
    NORMAL,
    HAPPY,
    SURPRISED,
    SLEEPY,
    THINKING,
    TALKING,
    SINGING
};

class FaceEngine {
private:
    Adafruit_SSD1306 display;
    SemaphoreHandle_t drawMutex = nullptr;
    volatile bool screenLocked  = false;

    volatile bool textModeActive  = false;
    String textTitle              = "";
    String textBody               = "";
    uint8_t textFontSize          = 2;
    uint32_t textExpireMs         = 0;

    volatile bool bitmapModeActive = false;
    uint8_t currentBitmap[1024];
    uint32_t bitmapExpireMs        = 0;

    uint32_t expExpireMs          = 0;
    uint32_t gazeExpireMs         = 0;

    String localIpStr = "";

    const float baseLeftX     = 38.0f;
    const float baseRightX    = 90.0f;
    const float baseY         = 40.0f;
    const float defaultWidth  = 30.0f;
    const float defaultHeight = 34.0f;

    float curOffsetX = 0.0f;
    float curOffsetY = 0.0f;
    float curWidth   = 30.0f;
    float curHeight  = 34.0f;

    volatile float targetOffsetX = 0.0f;
    volatile float targetOffsetY = 0.0f;
    volatile float targetWidth   = 30.0f;
    volatile float targetHeight  = 34.0f;

    volatile float currentAudioLevel  = 0.0f;
    volatile bool isSpeakerPlaying    = false;
    float smoothedAudioLevel          = 0.0f;
    volatile bool isNetworkConnected  = false;
    volatile bool isStreamingMic      = false;
    volatile bool autonomousGaze      = true;
    volatile Expression currentExp    = Expression::NORMAL;

    volatile bool isBlinking   = false;
    uint32_t lastBlinkTime     = 0;
    uint32_t nextBlinkInterval = 3500;
    uint32_t blinkStartTime    = 0;
    const uint32_t blinkDuration = 140;

    uint32_t lastSaccadeTime     = 0;
    uint32_t nextSaccadeInterval = 2500;

    float lerp(float start, float end, float factor) {
        return start + (end - start) * factor;
    }

    void updateAutonomousBehavior(uint32_t now) {
        if (expExpireMs > 0 && (int32_t)(now - expExpireMs) >= 0) {
            expExpireMs = 0;
            applyExpression(Expression::NORMAL);
        }

        if (!autonomousGaze && gazeExpireMs > 0 && (int32_t)(now - gazeExpireMs) >= 0) {
            gazeExpireMs   = 0;
            autonomousGaze = true;
        }

        if (!isBlinking && currentExp != Expression::SLEEPY && (now - lastBlinkTime > nextBlinkInterval)) {
            isBlinking        = true;
            blinkStartTime    = now;
            lastBlinkTime     = now;
            nextBlinkInterval = random(2500, 6000);
        }

        if (isBlinking && (now - blinkStartTime > blinkDuration)) {
            isBlinking = false;
        }

        if (autonomousGaze && currentExp == Expression::NORMAL && (now - lastSaccadeTime > nextSaccadeInterval)) {
            lastSaccadeTime     = now;
            nextSaccadeInterval = random(1800, 4500);
            if (random(0, 10) < 4) {
                targetOffsetX = 0.0f;
                targetOffsetY = 0.0f;
            } else {
                targetOffsetX = (float)random(-8, 9);
                targetOffsetY = (float)random(-3, 4);
            }
        }

        if (currentExp == Expression::THINKING) {
            // Thoughtful dynamic saccades & drifting gaze
            targetOffsetX = -7.0f + 4.5f * sinf((float)now * 0.003f);
            targetOffsetY = -3.5f + 1.8f * cosf((float)now * 0.002f);
        } else if (currentExp == Expression::TALKING) {
            // Lively speaking eye movement
            targetOffsetX = 2.0f * sinf((float)now * 0.004f);
            targetOffsetY = 1.0f * cosf((float)now * 0.003f);
        } else if (currentExp == Expression::SINGING) {
            // Joyful melodic sway: head/eyes rhythmically swaying to the rhythm of the music
            targetOffsetX = 4.5f * sinf((float)now * 0.005f);
            targetOffsetY = 2.0f * fabsf(sinf((float)now * 0.005f)) - 1.0f;
        }
    }

    void drawThickLine(int16_t x0, int16_t y0, int16_t x1, int16_t y1, uint8_t thickness) {
        for (int8_t i = 0; i < thickness; i++) {
            display.drawLine(x0, y0 + i, x1, y1 + i, SSD1306_WHITE);
        }
    }

    void drawEyebrows(int16_t lx, int16_t rx) {
        const int16_t bw = 15;

        switch (currentExp) {
            case Expression::NORMAL:
                drawThickLine(lx - bw, 9, lx + bw, 9, 3);
                drawThickLine(rx - bw, 9, rx + bw, 9, 3);
                break;

            case Expression::HAPPY:
                drawThickLine(lx - bw, 12, lx, 6, 2);
                drawThickLine(lx, 6, lx + bw, 11, 2);
                drawThickLine(rx - bw, 11, rx, 6, 2);
                drawThickLine(rx, 6, rx + bw, 12, 2);
                break;

            case Expression::SURPRISED:
                drawThickLine(lx - bw, 5, lx + bw, 3, 3);
                drawThickLine(rx - bw, 3, rx + bw, 5, 3);
                break;

            case Expression::SLEEPY:
                drawThickLine(lx - bw, 11, lx + bw, 13, 2);
                drawThickLine(rx - bw, 13, rx + bw, 11, 2);
                break;

            case Expression::THINKING:
                drawThickLine(lx - bw, 6, lx + bw, 12, 3);
                drawThickLine(rx - bw, 4, rx + bw, 4, 3);
                break;

            case Expression::TALKING:
                {
                    int16_t browOffset = isSpeakerPlaying ? (int16_t)(smoothedAudioLevel * 3.0f) : 0;
                    drawThickLine(lx - bw, 10 - browOffset, lx + bw, 8, 2);
                    drawThickLine(rx - bw, 8, rx + bw, 10 - browOffset, 2);
                }
                break;

            case Expression::SINGING:
                {
                    int16_t beatOffset = (int16_t)(sinf((float)millis() * 0.005f) * 1.5f);
                    drawThickLine(lx - bw, 11 + beatOffset, lx, 6 + beatOffset, 2);
                    drawThickLine(lx, 6 + beatOffset, lx + bw, 10 + beatOffset, 2);
                    drawThickLine(rx - bw, 10 - beatOffset, rx, 6 - beatOffset, 2);
                    drawThickLine(rx, 6 - beatOffset, rx + bw, 11 - beatOffset, 2);
                }
                break;
        }
    }

    void drawThinkingDots(uint32_t now) {
        // Animated bouncing thought dots between eyebrows
        for (int i = 0; i < 3; i++) {
            float phase = sinf(((float)now * 0.007f) - ((float)i * 0.95f));
            int16_t dotY = 8 + (int16_t)(phase * 3.0f);
            int16_t dotX = 57 + (i * 7);
            display.fillCircle(dotX, dotY, 1, SSD1306_WHITE);
        }
    }

    void drawMouth(uint32_t now) {
        if (currentExp == Expression::SLEEPY) return;

        int16_t mx = 64; // horizontal center
        int16_t my = 55; // vertical center below eyes

        if (currentExp == Expression::SINGING) {
            if (isSpeakerPlaying && smoothedAudioLevel > 0.015f) {
                // Singing mouth: open rounded cavity with dynamic vibrato oscillation
                float vibrato = 0.5f + 0.5f * sinf((float)now * 0.022f);
                float openness = constrain(smoothedAudioLevel * 2.2f + vibrato * 0.35f, 0.20f, 1.0f);
                int16_t mouthW = 12 + (int16_t)(openness * 12.0f);
                int16_t mouthH = 5 + (int16_t)(openness * 9.0f);
                mouthW = constrain(mouthW, 10, 24);
                mouthH = constrain(mouthH, 4, 14);
                int16_t radius = mouthH / 2;
                if (radius < 2) radius = 2;

                // Outer lips
                display.fillRoundRect(mx - (mouthW / 2), my - (mouthH / 2), mouthW, mouthH, radius, SSD1306_WHITE);

                // Inner cavity
                if (mouthH >= 6 && mouthW >= 12) {
                    int16_t inW = mouthW - 4;
                    int16_t inH = mouthH - 2;
                    int16_t inR = inH / 2;
                    if (inR < 1) inR = 1;
                    display.fillRoundRect(mx - (inW / 2), my - (inH / 2), inW, inH, inR, SSD1306_BLACK);
                }
            } else {
                // Cheerful melodic 'o' mouth when paused/quiet
                display.drawCircle(mx, my, 4, SSD1306_WHITE);
            }
            return;
        }

        // Mouth movement animation MUST ONLY play when audio is actively outputting to the speaker
        bool isTalking = isSpeakerPlaying && (currentExp != Expression::THINKING) && (smoothedAudioLevel > 0.015f);

        if (isTalking) {
            // Syllabic oscillation (~3.8 Hz) modulated by smooth audio volume
            float syllableWave = 0.5f + 0.5f * sinf((float)now * 0.024f);
            float openness = constrain(smoothedAudioLevel * 1.8f + syllableWave * 0.35f * (smoothedAudioLevel > 0.02f ? 1.0f : 0.0f), 0.0f, 1.0f);

            if (openness < 0.08f) {
                // Brief pause between words: neat subtle closed line
                display.drawFastHLine(mx - 4, my, 8, SSD1306_WHITE);
            } else {
                int16_t mouthW = 10 + (int16_t)(openness * 12.0f);
                int16_t mouthH = 3 + (int16_t)(openness * 7.0f);
                mouthW = constrain(mouthW, 8, 22);
                mouthH = constrain(mouthH, 2, 10);
                int16_t radius = mouthH / 2;
                if (radius < 1) radius = 1;

                // Outer lips
                display.fillRoundRect(mx - (mouthW / 2), my - (mouthH / 2), mouthW, mouthH, radius, SSD1306_WHITE);

                // Inner mouth cavity when open enough for expressive cartoon articulation
                if (mouthH >= 5 && mouthW >= 12) {
                    int16_t inW = mouthW - 4;
                    int16_t inH = mouthH - 2;
                    int16_t inR = inH / 2;
                    if (inR < 1) inR = 1;
                    display.fillRoundRect(mx - (inW / 2), my - (inH / 2), inW, inH, inR, SSD1306_BLACK);
                }
            }
        } else if (currentExp == Expression::HAPPY) {
            // Cute cheerful smile curve
            display.drawCircle(mx, my - 2, 5, SSD1306_WHITE);
            display.fillRect(mx - 7, my - 7, 14, 5, SSD1306_BLACK);
        } else if (currentExp == Expression::SURPRISED) {
            // Cute surprised round 'o' mouth
            display.drawCircle(mx, my, 3, SSD1306_WHITE);
        } else if (currentExp == Expression::THINKING) {
            // Cute pensive smirk line (steady, absolutely no talking animation)
            display.drawLine(mx - 4, my + 1, mx + 4, my - 1, SSD1306_WHITE);
        } else {
            // Expression::NORMAL (attentive listening / neutral friendly mouth)
            display.drawFastHLine(mx - 4, my, 8, SSD1306_WHITE);
            display.drawPixel(mx - 5, my - 1, SSD1306_WHITE);
            display.drawPixel(mx + 4, my - 1, SSD1306_WHITE);
        }
    }

    void drawSleepZzz(uint32_t now) {
        display.setTextColor(SSD1306_WHITE);
        display.setTextWrap(false);
        const uint32_t period = 2400;
        const uint32_t life   = 1800;

        for (int i = 0; i < 3; i++) {
            uint32_t delayMs = i * 600;
            uint32_t cycleTime = (now + period - (delayMs % period)) % period;
            if (cycleTime < life) {
                float p = (float)cycleTime / (float)life;
                uint8_t sz = (i == 2) ? 2 : 1;
                int16_t charW = sz * 6;
                int16_t zx = 76 + (i * 8) + (int16_t)(p * 10.0f);
                int16_t zy = 34 - (int16_t)(p * 30.0f);
                if (zy >= 0 && (zx + charW) <= 127) {
                    display.setTextSize(sz);
                    display.setCursor(zx, zy);
                    display.print(i == 0 ? "z" : "Z");
                }
            }
        }
    }

    void drawNoteGlyph(int16_t nx, int16_t ny, bool isDouble) {
        if (ny < 2 || ny > 60 || nx < 2 || nx > 124) return;
        if (!isDouble) {
            // Single musical note ♪
            display.fillCircle(nx, ny, 2, SSD1306_WHITE);
            display.drawFastVLine(nx + 2, ny - 6, 7, SSD1306_WHITE);
            display.drawLine(nx + 2, ny - 6, nx + 5, ny - 4, SSD1306_WHITE);
        } else {
            // Double musical note ♫
            display.fillCircle(nx, ny, 2, SSD1306_WHITE);
            display.fillCircle(nx + 6, ny - 1, 2, SSD1306_WHITE);
            display.drawFastVLine(nx + 2, ny - 6, 7, SSD1306_WHITE);
            display.drawFastVLine(nx + 8, ny - 7, 7, SSD1306_WHITE);
            display.drawLine(nx + 2, ny - 6, nx + 8, ny - 7, SSD1306_WHITE);
            display.drawLine(nx + 2, ny - 5, nx + 8, ny - 6, SSD1306_WHITE);
        }
    }

    void drawMusicNotes(uint32_t now) {
        // Fullscreen dynamic musical notes floating upwards across 128x64 display
        const int numNotes = 5;
        const int16_t baseXs[numNotes]   = { 16, 40, 64, 88, 112 };
        const uint32_t periods[numNotes] = { 2800, 3400, 2600, 3100, 2900 };
        const uint32_t delays[numNotes]  = { 0, 1600, 800, 2200, 1200 };
        const bool isDoubles[numNotes]   = { false, true, false, true, false };
        const float sways[numNotes]      = { 4.0f, 6.0f, 5.0f, 6.0f, 4.0f };

        float bounceAmp = 1.0f + (smoothedAudioLevel * 4.0f);

        for (int i = 0; i < numNotes; i++) {
            uint32_t t = (now + delays[i]) % periods[i];
            float p = (float)t / (float)periods[i];
            int16_t nx = baseXs[i] + (int16_t)(sinf((p * 6.283f * 1.5f) + (float)i) * sways[i] * bounceAmp);
            int16_t ny = 58 - (int16_t)(p * 54.0f);
            drawNoteGlyph(nx, ny, isDoubles[i]);
        }
    }

    void drawEye(int16_t centerX, int16_t centerY, int16_t w, int16_t h) {
        int16_t x0 = centerX - (w / 2);
        int16_t y0 = centerY - (h / 2);

        if (currentExp == Expression::SLEEPY) {
            int16_t slitH = 4;
            int16_t slitY0 = centerY + 2;
            display.fillRoundRect(x0, slitY0, w, slitH, 2, SSD1306_WHITE);
            return;
        }

        if (y0 < 16) {
            h -= (16 - y0);
            y0 = 16;
        }
        if (y0 + h > 64) {
            h = 64 - y0;
        }
        if (h < 2) h = 2;

        int16_t radius = min(w, h) / 3;
        if (radius < 2) radius = 2;

        display.fillRoundRect(x0, y0, w, h, radius, SSD1306_WHITE);

        if ((currentExp == Expression::HAPPY || currentExp == Expression::SINGING) && !isBlinking) {
            display.fillCircle(centerX, y0 + h + 6, w / 2 + 2, SSD1306_BLACK);
        }
    }

    void renderFullScreenTextInternal() {
        display.clearDisplay();
        display.setTextColor(SSD1306_WHITE);
        display.setTextWrap(true);

        int16_t bodyStartY = 4;
        if (textTitle.length() > 0) {
            display.setTextSize(1);
            display.setCursor(2, 3);
            display.print(textTitle.substring(0, 20));
            display.drawLine(0, 14, 127, 14, SSD1306_WHITE);
            bodyStartY = 18;
        }

        uint8_t effectiveSize = textFontSize;
        if (effectiveSize < 2) effectiveSize = 2;
        if (textTitle.length() == 0 && textBody.length() <= 6) {
            effectiveSize = 3;
            bodyStartY = 20;
        }

        display.setTextSize(effectiveSize);
        display.setCursor(2, bodyStartY);
        display.print(textBody);
        display.display();
    }

    void applyExpression(Expression exp) {
        currentExp     = exp;
        autonomousGaze = (exp == Expression::NORMAL);
        switch (exp) {
            case Expression::NORMAL:
                targetWidth  = defaultWidth;   // 30
                targetHeight = defaultHeight;  // 34
                break;
            case Expression::HAPPY:
                targetWidth  = 32.0f;
                targetHeight = 28.0f;
                break;
            case Expression::SURPRISED:
                targetWidth   = 34.0f;
                targetHeight  = 38.0f;
                targetOffsetX = 0.0f;
                targetOffsetY = 0.0f;
                break;
            case Expression::SLEEPY:
                targetWidth   = 28.0f;
                targetHeight  = 6.0f;
                targetOffsetX = 0.0f;
                targetOffsetY = 3.0f;
                break;
            case Expression::THINKING:
                targetWidth   = 28.0f;
                targetHeight  = 26.0f;
                targetOffsetX = -8.0f;
                targetOffsetY = -3.0f;
                break;
            case Expression::TALKING:
                targetWidth   = 32.0f;
                targetHeight  = 30.0f;
                targetOffsetX = 0.0f;
                targetOffsetY = 0.0f;
                break;
            case Expression::SINGING:
                targetWidth   = 32.0f;
                targetHeight  = 28.0f;
                targetOffsetX = 0.0f;
                targetOffsetY = 0.0f;
                break;
        }
    }

    static void faceTaskEntry(void* arg) {
        FaceEngine* self = static_cast<FaceEngine*>(arg);
        TickType_t lastWake = xTaskGetTickCount();
        for (;;) {
            self->update();
            vTaskDelayUntil(&lastWake, pdMS_TO_TICKS(40));
        }
    }

public:
    bool oledReady = false;
    volatile bool displayPowerEnabled = true;

    FaceEngine() : display(128, 64, &Wire, -1) {}

    bool begin() {
        drawMutex = xSemaphoreCreateMutex();
        Wire.begin(PIN_OLED_SDA, PIN_OLED_SCL);
        Wire.setClock(400000);

        if (!display.begin(SSD1306_SWITCHCAPVCC, 0x3C)) {
            oledReady = false;
            return false;
        }
        oledReady = true;
        display.clearDisplay();
        display.display();
        lastBlinkTime   = millis();
        lastSaccadeTime = millis();
        return true;
    }

    void startTask() {
        xTaskCreatePinnedToCore(faceTaskEntry, "faceTask", 4096, this, 1, NULL, 1);
    }

    void setLocalIp(const String& ip) {
        localIpStr = ip;
    }

    bool setDisplayPower(bool enable) {
        if (!oledReady) return false;
        xSemaphoreTake(drawMutex, portMAX_DELAY);
        displayPowerEnabled = enable;
        display.ssd1306_command(enable ? SSD1306_DISPLAYON : SSD1306_DISPLAYOFF);
        xSemaphoreGive(drawMutex);
        return true;
    }

    void unlockScreen() { screenLocked = false; }

    void showFullScreenText(const String& title, const String& body, uint8_t fontSize, uint32_t timeoutMs) {
        if (!oledReady) return;
        xSemaphoreTake(drawMutex, portMAX_DELAY);
        textTitle      = title;
        textBody       = body;
        textFontSize   = (fontSize >= 2) ? fontSize : 2;
        textExpireMs   = (timeoutMs > 0) ? (millis() + timeoutMs) : 0;
        textModeActive = true;
        renderFullScreenTextInternal();
        xSemaphoreGive(drawMutex);
    }

    void clearFullScreenText() {
        if (!oledReady) return;
        xSemaphoreTake(drawMutex, portMAX_DELAY);
        textModeActive   = false;
        bitmapModeActive = false;
        textExpireMs     = 0;
        bitmapExpireMs   = 0;
        xSemaphoreGive(drawMutex);
    }

    void showBitmap(const uint8_t* data, size_t len, uint32_t timeoutMs) {
        if (!oledReady || len != 1024) return;
        xSemaphoreTake(drawMutex, portMAX_DELAY);
        memcpy(currentBitmap, data, 1024);
        bitmapExpireMs   = (timeoutMs > 0) ? (millis() + timeoutMs) : 0;
        bitmapModeActive = true;
        textModeActive   = false;
        display.clearDisplay();
        display.drawBitmap(0, 0, currentBitmap, 128, 64, SSD1306_WHITE);
        display.display();
        xSemaphoreGive(drawMutex);
    }

    void clearBitmap() {
        if (!oledReady) return;
        xSemaphoreTake(drawMutex, portMAX_DELAY);
        bitmapModeActive = false;
        bitmapExpireMs   = 0;
        xSemaphoreGive(drawMutex);
    }

    void renderConfigPortalScreen(const char* apSsid, const char* apPass, const String& ipStr) {
        if (!oledReady) return;
        xSemaphoreTake(drawMutex, portMAX_DELAY);
        screenLocked = true;
        display.clearDisplay();
        display.setTextSize(1);
        display.setTextColor(SSD1306_WHITE);

        display.setCursor(10, 0);
        display.println("[ WIFI SETUP MODE ]");
        display.drawLine(0, 10, 127, 10, SSD1306_WHITE);

        display.setCursor(0, 16);
        display.print("SSID: ");
        display.println(apSsid);

        display.setCursor(0, 28);
        display.print("PASS: ");
        display.println(apPass);

        display.setCursor(0, 42);
        display.println("Open in Browser:");
        display.setCursor(12, 53);
        display.print("http://");
        display.println(ipStr);

        display.display();
        xSemaphoreGive(drawMutex);
    }

    void renderBootConnecting(const String& ssid) {
        if (!oledReady) return;
        xSemaphoreTake(drawMutex, portMAX_DELAY);
        screenLocked = true;
        display.clearDisplay();
        display.setTextSize(1);
        display.setTextColor(SSD1306_WHITE);
        display.setCursor(8, 16);
        display.println("Connecting WiFi...");
        display.setCursor(8, 32);
        display.println(ssid.substring(0, 18));
        display.display();
        xSemaphoreGive(drawMutex);
    }

    const char* setExpressionByName(const char* name, uint32_t durationMs = 0) {
        Expression target = Expression::NORMAL;
        const char* canonical = "normal";

        if (strcasecmp(name, "happy") == 0) {
            target = Expression::HAPPY;
            canonical = "happy";
        } else if (strcasecmp(name, "surprised") == 0) {
            target = Expression::SURPRISED;
            canonical = "surprised";
        } else if (strcasecmp(name, "sleepy") == 0) {
            target = Expression::SLEEPY;
            canonical = "sleepy";
        } else if (strcasecmp(name, "thinking") == 0) {
            target = Expression::THINKING;
            canonical = "thinking";
        } else if (strcasecmp(name, "talking") == 0) {
            target = Expression::TALKING;
            canonical = "talking";
        } else if (strcasecmp(name, "singing") == 0 || strcasecmp(name, "music") == 0 || strcasecmp(name, "song") == 0) {
            target = Expression::SINGING;
            canonical = "singing";
        }

        xSemaphoreTake(drawMutex, portMAX_DELAY);
        applyExpression(target);
        expExpireMs = (durationMs > 0) ? (millis() + durationMs) : 0;
        xSemaphoreGive(drawMutex);
        return canonical;
    }

    void setGaze(float normX, float normY, uint32_t holdMs = 0) {
        xSemaphoreTake(drawMutex, portMAX_DELAY);
        autonomousGaze = false;
        targetOffsetX  = constrain(normX, -1.0f, 1.0f) * 10.0f;
        targetOffsetY  = constrain(normY, -1.0f, 1.0f) * 4.0f;
        gazeExpireMs   = (holdMs > 0) ? (millis() + holdMs) : 0;
        xSemaphoreGive(drawMutex);
    }

    void triggerBlink() {
        if (currentExp == Expression::SLEEPY) return;
        blinkStartTime = millis();
        isBlinking     = true;
    }

    void setAudioLevel(float level) {
        currentAudioLevel = constrain(level, 0.0f, 1.0f);
    }

    void setSpeakerState(bool isPlaying, float level) {
        isSpeakerPlaying   = isPlaying;
        currentAudioLevel  = isPlaying ? constrain(level, 0.0f, 1.0f) : 0.0f;
    }

    void setStatusFlags(bool wsConnected, bool micStreaming) {
        isNetworkConnected = wsConnected;
        isStreamingMic     = micStreaming;
    }

    void update() {
        if (!oledReady || !displayPowerEnabled || screenLocked) return;
        if (xSemaphoreTake(drawMutex, 0) != pdTRUE) return;
        if (screenLocked || !displayPowerEnabled) {
            xSemaphoreGive(drawMutex);
            return;
        }

        uint32_t now = millis();

        if (textModeActive) {
            if (textExpireMs > 0 && (int32_t)(now - textExpireMs) >= 0) {
                textModeActive = false;
                textExpireMs   = 0;
            } else {
                xSemaphoreGive(drawMutex);
                return;
            }
        }

        if (bitmapModeActive) {
            if (bitmapExpireMs > 0 && (int32_t)(now - bitmapExpireMs) >= 0) {
                bitmapModeActive = false;
                bitmapExpireMs   = 0;
            } else {
                xSemaphoreGive(drawMutex);
                return;
            }
        }

        updateAutonomousBehavior(now);

        const float smoothFactor = 0.25f;
        curOffsetX = lerp(curOffsetX, targetOffsetX, smoothFactor);
        curOffsetY = lerp(curOffsetY, targetOffsetY, smoothFactor);
        curWidth   = lerp(curWidth, targetWidth, smoothFactor);

        // Exponential moving average smoothing for speaker audio
        float targetLevel = isSpeakerPlaying ? currentAudioLevel : 0.0f;
        smoothedAudioLevel = lerp(smoothedAudioLevel, targetLevel, 0.35f);

        float audioBounce = (isSpeakerPlaying && currentExp != Expression::SLEEPY) ? (smoothedAudioLevel * 3.5f) : 0.0f;
        float effectiveTargetHeight = isBlinking ? 3.0f : (targetHeight + audioBounce);
        float heightFactor = isBlinking ? 0.65f : 0.30f;
        curHeight = lerp(curHeight, effectiveTargetHeight, heightFactor);

        display.clearDisplay();

        if (currentExp == Expression::SINGING) {
            // When song/music is playing: ONLY musical notes are displayed on screen!
            drawMusicNotes(now);
            display.display();
            xSemaphoreGive(drawMutex);
            return;
        }

        if (!isNetworkConnected) {
            display.drawPixel(2, 2, SSD1306_WHITE);
        }

        if (isStreamingMic) {
            display.fillRect(124, 2, 2, 2, SSD1306_WHITE);
        }

        int16_t lx = (int16_t)(baseLeftX + curOffsetX);
        int16_t rx = (int16_t)(baseRightX + curOffsetX);
        int16_t ly = (int16_t)(baseY + curOffsetY);
        int16_t ry = (int16_t)(baseY + curOffsetY);
        int16_t rw = (int16_t)curWidth;
        int16_t rh = (int16_t)curHeight;

        // Display IP address when connected to Wi-Fi but disconnected from server
        if (WiFi.status() == WL_CONNECTED && !isNetworkConnected && localIpStr.length() > 0) {
            display.setTextSize(1);
            display.setTextColor(SSD1306_WHITE);
            int16_t ipX = max(0, (128 - (int)(localIpStr.length() * 6)) / 2);
            display.setCursor(ipX, 4);
            display.print(localIpStr);
        } else {
            drawEyebrows(lx, rx);
        }

        drawEye(lx, ly, rw, rh);
        if (currentExp == Expression::THINKING && !isBlinking) {
            drawEye(rx, ry, rw, max(6, rh - 8));
            drawThinkingDots(now);
        } else {
            drawEye(rx, ry, rw, rh);
        }

        drawMouth(now);

        if (currentExp == Expression::SLEEPY) {
            drawSleepZzz(now);
        }

        display.display();
        xSemaphoreGive(drawMutex);
    }
};

// ============================================================================
// Global State & Instances
// ============================================================================
ConfigManager cfg;
FaceEngine face;
AudioHal audio;
WebSocketsClient webSocket;
WebServer webServer(80);

volatile bool inConfigMode    = false;
volatile bool wsConnected     = false;
volatile bool streamMicToHost = false;
volatile bool sensorsEnabled  = false;

uint32_t lastTelemetryMs      = 0;
uint32_t pendingRebootAtMs    = 0;
String serialLineBuffer       = "";

void enterConfigMode();
void startNormalOperation();

void setMicStreaming(bool enable) {
    streamMicToHost = enable;
    audio.setCapture(enable);
}

// ============================================================================
// Web Provisioning Portal Handlers
// ============================================================================
void handleWebRoot() {
    String html = R"rawliteral(
<!DOCTYPE html>
<html>
<head>
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>RoboDesk Setup</title>
  <style>
    body { font-family: sans-serif; background: #121212; color: #e0e0e0; display: flex; justify-content: center; padding: 20px; }
    .card { background: #1e1e1e; padding: 24px; border-radius: 12px; width: 100%; max-width: 380px; box-shadow: 0 4px 15px rgba(0,0,0,0.5); }
    h2 { margin-top: 0; color: #4fc3f7; text-align: center; }
    label { display: block; margin-top: 12px; font-size: 0.9em; color: #aaa; }
    input { width: 100%; padding: 10px; margin-top: 5px; border-radius: 6px; border: 1px solid #333; background: #2c2c2c; color: #fff; box-sizing: border-box; }
    button { width: 100%; margin-top: 22px; padding: 12px; background: #0288d1; color: white; border: none; border-radius: 6px; font-size: 1em; font-weight: bold; cursor: pointer; }
    button:hover { background: #039be5; }
  </style>
</head>
<body>
  <div class="card">
    <h2>AI RoboDesk Config</h2>
    <form action="/save" method="POST">
      <label>Wi-Fi SSID</label>
      <input type="text" name="ssid" value=")rawliteral" + cfg.data.wifiSsid + R"rawliteral(" required>
      <label>Wi-Fi Password</label>
      <input type="password" name="pass" value=")rawliteral" + cfg.data.wifiPass + R"rawliteral(">
      <label>Host Server IP</label>
      <input type="text" name="host_ip" value=")rawliteral" + cfg.data.hostIp + R"rawliteral(" required>
      <label>Host Server Port</label>
      <input type="number" name="host_port" value=")rawliteral" + String(cfg.data.hostPort) + R"rawliteral(" required>
      <label>Device ID</label>
      <input type="text" name="dev_id" value=")rawliteral" + cfg.data.deviceId + R"rawliteral(" required>
      <button type="submit">Save & Connect</button>
    </form>
  </div>
</body>
</html>
)rawliteral";
    webServer.send(200, "text/html", html);
}

void handleWebSave() {
    String ssid  = webServer.arg("ssid");
    String pass  = webServer.arg("pass");
    String ip    = webServer.arg("host_ip");
    uint16_t port = (uint16_t)webServer.arg("host_port").toInt();
    String devId = webServer.arg("dev_id");

    if (port == 0) port = 8765;
    if (devId.isEmpty()) devId = "robo-desk-01";

    cfg.save(ssid, pass, ip, port, devId);

    webServer.send(200, "text/html",
        "<html><body style='background:#121212;color:#4fc3f7;font-family:sans-serif;text-align:center;padding-top:40px;'>"
        "<h2>Configuration Saved!</h2><p>Rebooting into normal mode...</p></body></html>"
    );

    Serial.println("[CONFIG] Settings saved via Web Portal. Rebooting...");
    audio.playBootChime();
    delay(800);
    ESP.restart();
}

void enterConfigMode() {
    inConfigMode = true;
    wsConnected  = false;
    setMicStreaming(false);
    webSocket.disconnect();
    audio.abortPlayback();

    WiFi.disconnect(true);
    delay(100);
    WiFi.mode(WIFI_AP);
    WiFi.softAP(AP_SSID, AP_PASS);

    IPAddress apIp = WiFi.softAPIP();
    String ipStr   = apIp.toString();

    webServer.on("/", HTTP_GET, handleWebRoot);
    webServer.on("/save", HTTP_POST, handleWebSave);
    webServer.begin();

    face.renderConfigPortalScreen(AP_SSID, AP_PASS, ipStr);
    audio.playConfigModeChime();

    Serial.println("\n==========================================");
    Serial.println("[CONFIG MODE ACTIVE - HOTSPOT STARTED]");
    Serial.printf("  Hotspot SSID : %s\n", AP_SSID);
    Serial.printf("  Hotspot PASS : %s\n", AP_PASS);
    Serial.printf("  Portal URL   : http://%s\n", ipStr.c_str());
    Serial.println("==========================================\n");
}

// ============================================================================
// RDP v1.0 Protocol Builders & RPC Dispatcher
// ============================================================================
void sendRdpEvent(const char* topic, JsonDocument& dataDoc) {
    if (!wsConnected) return;
    JsonDocument env;
    env["v"]     = RDP_VERSION;
    env["kind"]  = "event";
    env["topic"] = topic;
    env["data"]  = dataDoc.as<JsonObject>();

    String out;
    serializeJson(env, out);
    webSocket.sendTXT(out);
}

void sendRdpResponseOk(const char* reqId, JsonDocument& resDoc) {
    if (!wsConnected) return;
    JsonDocument env;
    env["v"]      = RDP_VERSION;
    env["kind"]   = "res";
    env["id"]     = reqId;
    env["status"] = "ok";
    env["result"] = resDoc.as<JsonObject>();
    env["error"]  = nullptr;

    String out;
    serializeJson(env, out);
    webSocket.sendTXT(out);
}

void sendRdpResponseError(const char* reqId, int code, const char* message) {
    if (!wsConnected) return;
    JsonDocument env;
    env["v"]      = RDP_VERSION;
    env["kind"]   = "res";
    env["id"]     = reqId;
    env["status"] = "error";
    env["result"] = nullptr;

    JsonObject errObj = env["error"].to<JsonObject>();
    errObj["code"]    = code;
    errObj["message"] = message;

    String out;
    serializeJson(env, out);
    webSocket.sendTXT(out);
}

void sendSystemHello() {
    JsonDocument dataDoc;
    dataDoc["device_id"] = cfg.data.deviceId;
    dataDoc["firmware"]  = FIRMWARE_VERSION;

    JsonObject hw = dataDoc["hardware"].to<JsonObject>();
    hw["chip"]      = "ESP32-D0WD-V3";
    hw["display"]   = face.oledReady ? "ssd1306_128x64" : "none";
    hw["audio_in"]  = audio.micReady ? "inmp441_pcm16_16khz" : "none";
    hw["audio_out"] = audio.spkReady ? "max98357a_pcm16_16khz" : "none";

    JsonArray sensors = hw["sensors"].to<JsonArray>();
    if (isGyroPresent())       sensors.add("mpu6050");
    if (isUltrasonicPresent()) sensors.add("hcsr04");

    sendRdpEvent("system.hello", dataDoc);
}

void sendPeriodicTelemetry() {
    if (!wsConnected || inConfigMode) return;
    uint32_t now = millis();
    if (now - lastTelemetryMs < TELEMETRY_INTERVAL_MS) return;
    lastTelemetryMs = now;

    JsonDocument dataDoc;
    dataDoc["free_heap"]          = ESP.getFreeHeap();
    dataDoc["wifi_rssi"]          = WiFi.RSSI();
    dataDoc["spk_buffered_bytes"] = audio.speakerBufferedBytes();
    dataDoc["spk_underruns"]      = audio.spkUnderruns;
    dataDoc["mic_drops"]          = audio.micDrops;
    dataDoc["mic_level"]          = serialized(String((float)audio.micLevel, 3));
    dataDoc["spk_level"]          = serialized(String((float)audio.spkLevel, 3));

    sendRdpEvent("sensor.telemetry", dataDoc);
}

static size_t decodeBase64(const char* input, uint8_t* output, size_t maxLen) {
    static const int8_t b64_table[256] = {
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,62,-1,-1,-1,63,
        52,53,54,55,56,57,58,59,60,61,-1,-1,-1,-1,-1,-1,
        -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9,10,11,12,13,14,
        15,16,17,18,19,20,21,22,23,24,25,-1,-1,-1,-1,-1,
        -1,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,
        41,42,43,44,45,46,47,48,49,50,51,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1
    };
    size_t inLen = strlen(input);
    size_t outIdx = 0;
    uint32_t val = 0;
    int valb = -8;
    for (size_t i = 0; i < inLen; i++) {
        uint8_t c = (uint8_t)input[i];
        if (c == '=') break;
        int8_t d = b64_table[c];
        if (d < 0) continue;
        val = (val << 6) | d;
        valb += 6;
        if (valb >= 0) {
            if (outIdx < maxLen) {
                output[outIdx++] = (uint8_t)((val >> valb) & 0xFF);
            }
            valb -= 8;
        }
    }
    return outIdx;
}

static size_t decodeHex(const char* input, uint8_t* output, size_t maxLen) {
    size_t inLen = strlen(input);
    size_t outIdx = 0;
    for (size_t i = 0; i + 1 < inLen && outIdx < maxLen; i += 2) {
        char c1 = input[i], c2 = input[i+1];
        auto hexVal = [](char c) -> int {
            if (c >= '0' && c <= '9') return c - '0';
            if (c >= 'a' && c <= 'f') return c - 'a' + 10;
            if (c >= 'A' && c <= 'F') return c - 'A' + 10;
            return 0;
        };
        output[outIdx++] = (uint8_t)((hexVal(c1) << 4) | hexVal(c2));
    }
    return outIdx;
}

void handleRdpJsonMessage(uint8_t* payload, size_t length) {
    JsonDocument doc;
    DeserializationError err = deserializeJson(doc, payload, length);
    if (err) {
        Serial.printf("[RDP] JSON parse error: %s\n", err.c_str());
        return;
    }

    const char* kind = doc["kind"] | "";
    if (strcmp(kind, "req") != 0) {
        return;
    }

    const char* reqId  = doc["id"] | "unknown";
    const char* method = doc["method"] | "";
    JsonObject params  = doc["params"].as<JsonObject>();

    JsonDocument resultDoc;
    resultDoc.to<JsonObject>();

    // --- 1. System Namespace ---
    if (strcmp(method, "system.ping") == 0) {
        resultDoc["timestamp"] = millis();
        sendRdpResponseOk(reqId, resultDoc);
    }
    else if (strcmp(method, "system.get_capabilities") == 0) {
        resultDoc["device_id"] = cfg.data.deviceId;
        JsonObject mods = resultDoc["modules"].to<JsonObject>();

        JsonObject mMic = mods["mic"].to<JsonObject>();
        mMic["present"] = audio.micReady;
        mMic["enabled"] = audio.micPowerEnabled;
        mMic["rate"]    = SAMPLE_RATE;

        JsonObject mSpk = mods["speaker"].to<JsonObject>();
        mSpk["present"] = audio.spkReady;
        mSpk["enabled"] = audio.spkPowerEnabled;
        mSpk["rate"]    = SAMPLE_RATE;

        JsonObject mDisp = mods["display"].to<JsonObject>();
        mDisp["present"] = face.oledReady;
        mDisp["enabled"] = face.displayPowerEnabled;
        mDisp["driver"]  = "ssd1306";

        bool ultOk = isUltrasonicPresent();
        JsonObject mUlt = mods["ultrasonic"].to<JsonObject>();
        mUlt["present"] = ultOk;
        mUlt["enabled"] = ultOk && sensorsEnabled;

        bool gyrOk = isGyroPresent();
        JsonObject mGyr = mods["gyro"].to<JsonObject>();
        mGyr["present"] = gyrOk;
        mGyr["enabled"] = gyrOk && sensorsEnabled;

        sendRdpResponseOk(reqId, resultDoc);
    }
    else if (strcmp(method, "system.reboot") == 0) {
        uint32_t delayMs = params["delay_ms"] | 500;
        pendingRebootAtMs = millis() + max((uint32_t)100, delayMs);
        resultDoc["rebooting_in_ms"] = delayMs;
        sendRdpResponseOk(reqId, resultDoc);
    }

    // --- 2. Hardware Power Control Namespace ---
    else if (strcmp(method, "hardware.set_power_state") == 0) {
        const char* mod = params["module"] | "";
        bool enabled    = params["enabled"] | true;

        if (strcmp(mod, "mic") == 0) {
            if (!enabled) setMicStreaming(false);
            audio.setMicPower(enabled);
        } else if (strcmp(mod, "speaker") == 0) {
            audio.setSpkPower(enabled);
        } else if (strcmp(mod, "display") == 0) {
            face.setDisplayPower(enabled);
        } else if (strcmp(mod, "sensors") == 0) {
            sensorsEnabled = enabled;
        } else {
            sendRdpResponseError(reqId, 400, "Unknown hardware module");
            return;
        }

        resultDoc["module"] = mod;
        resultDoc["state"]  = enabled ? "active" : "suspended";
        sendRdpResponseOk(reqId, resultDoc);
    }

    // --- 3. Face & Animation Namespace ---
    else if (strcmp(method, "face.set_expression") == 0) {
        const char* name  = params["name"] | "normal";
        uint32_t duration = params["duration_ms"] | 0;
        const char* active = face.setExpressionByName(name, duration);
        resultDoc["active_expression"] = active;
        sendRdpResponseOk(reqId, resultDoc);
    }
    else if (strcmp(method, "face.set_gaze") == 0) {
        float x         = params["x"] | 0.0f;
        float y         = params["y"] | 0.0f;
        uint32_t holdMs = params["hold_ms"] | 0;
        face.setGaze(x, y, holdMs);
        resultDoc["x"] = x;
        resultDoc["y"] = y;
        sendRdpResponseOk(reqId, resultDoc);
    }
    else if (strcmp(method, "face.blink") == 0) {
        face.triggerBlink();
        resultDoc["blinked"] = true;
        sendRdpResponseOk(reqId, resultDoc);
    }

    // --- 4. Full-Screen Text Display Namespace ---
    else if (strcmp(method, "display.show_text") == 0) {
        String title       = params["title"] | "";
        String body        = params["body"] | "";
        uint8_t fontSize   = params["font_size"] | 2;
        uint32_t timeoutMs = params["timeout_ms"] | 0;

        face.showFullScreenText(title, body, fontSize, timeoutMs);
        resultDoc["screen_state"] = "text_active";
        sendRdpResponseOk(reqId, resultDoc);
    }
    else if (strcmp(method, "display.clear_text") == 0) {
        face.clearFullScreenText();
        resultDoc["screen_state"] = "face_active";
        sendRdpResponseOk(reqId, resultDoc);
    }
    else if (strcmp(method, "display.show_bitmap") == 0) {
        const char* b64 = params["bitmap_base64"] | "";
        const char* hex = params["bitmap_hex"] | "";
        uint32_t timeoutMs = params["timeout_ms"] | 0;

        uint8_t bmpBuf[1024];
        size_t decodedLen = 0;

        if (strlen(b64) > 0) {
            decodedLen = decodeBase64(b64, bmpBuf, sizeof(bmpBuf));
        } else if (strlen(hex) > 0) {
            decodedLen = decodeHex(hex, bmpBuf, sizeof(bmpBuf));
        } else {
            sendRdpResponseError(reqId, 400, "Missing 'bitmap_base64' or 'bitmap_hex'");
            return;
        }

        if (decodedLen == 1024) {
            face.showBitmap(bmpBuf, 1024, timeoutMs);
            resultDoc["screen_state"] = "bitmap_active";
            sendRdpResponseOk(reqId, resultDoc);
        } else {
            sendRdpResponseError(reqId, 400, "Invalid bitmap size, expected 1024 bytes");
        }
    }
    else if (strcmp(method, "display.clear_bitmap") == 0) {
        face.clearBitmap();
        resultDoc["screen_state"] = "face_active";
        sendRdpResponseOk(reqId, resultDoc);
    }

    // --- 5. Audio Subsystem Namespace ---
    else if (strcmp(method, "audio.set_mic_capture") == 0) {
        bool stream = params["stream"] | false;
        if (stream && !audio.micPowerEnabled) {
            sendRdpResponseError(reqId, 409, "Microphone module is powered off");
            return;
        }
        setMicStreaming(stream);
        resultDoc["streaming"] = stream;
        sendRdpResponseOk(reqId, resultDoc);
    }
    else if (strcmp(method, "audio.abort") == 0) {
        size_t drained = audio.abortPlayback();
        resultDoc["drained_bytes"] = drained;
        resultDoc["spk_state"]     = "idle";
        sendRdpResponseOk(reqId, resultDoc);
    }
    else if (strcmp(method, "audio.eos") == 0) {
        audio.notifyEos();
        resultDoc["eos_acknowledged"] = true;
        sendRdpResponseOk(reqId, resultDoc);
    }
    else if (strcmp(method, "audio.set_volume") == 0) {
        if (params["level"].isNull()) {
            sendRdpResponseError(reqId, 400, "Missing 'level' parameter");
            return;
        }
        float level = params["level"].as<float>();
        audio.setVolume(level);
        resultDoc["volume"] = (float)audio.masterVolume;
        sendRdpResponseOk(reqId, resultDoc);
    }
    else if (strcmp(method, "audio.play_tone") == 0) {
        float freq   = params["freq"] | 880.0f;
        uint32_t dur = params["duration_ms"] | 150;
        float vol    = params["volume"] | 0.25f;
        audio.playTone(freq, dur, vol);
        resultDoc["playing_tone"] = true;
        sendRdpResponseOk(reqId, resultDoc);
    }
    else {
        sendRdpResponseError(reqId, 404, "Method not found");
    }
}

void onWebSocketEvent(WStype_t type, uint8_t* payload, size_t length) {
    switch (type) {
        case WStype_DISCONNECTED:
            if (wsConnected) {
                Serial.println("[WS] Disconnected from host. Autonomous mode active.");
            }
            wsConnected = false;
            setMicStreaming(false);
            audio.abortPlayback();
            break;

        case WStype_CONNECTED:
            Serial.printf("[WS] Connected to host: %s\n", payload);
            wsConnected = true;
            audio.playTone(1046.5f, 70, 0.2f);
            sendSystemHello();
            break;

        case WStype_TEXT:
            handleRdpJsonMessage(payload, length);
            break;

        case WStype_BIN:
            audio.enqueueSpeakerPcm(payload, length);
            break;

        default:
            break;
    }
}

void startNormalOperation() {
    inConfigMode = false;
    webServer.stop();

    face.renderBootConnecting(cfg.data.wifiSsid);
    WiFi.mode(WIFI_STA);
    WiFi.setSleep(false);
    WiFi.begin(cfg.data.wifiSsid.c_str(), cfg.data.wifiPass.c_str());

    Serial.printf("[WIFI] Connecting to '%s' ", cfg.data.wifiSsid.c_str());
    uint32_t startAttempt = millis();
    while (WiFi.status() != WL_CONNECTED && (millis() - startAttempt < 12000)) {
        delay(250);
        Serial.print(".");
    }
    Serial.println();

    if (WiFi.status() != WL_CONNECTED) {
        Serial.println("[WIFI] Failed to connect within 12s! Falling back to Hotspot Config Mode.");
        enterConfigMode();
        return;
    }

    String myIp = WiFi.localIP().toString();
    Serial.printf("[WIFI] Connected! Local IP: %s | RSSI: %d dBm\n", myIp.c_str(), WiFi.RSSI());

    // Safely start web portal after Wi-Fi is fully established with an IP address
    webServer.on("/", HTTP_GET, handleWebRoot);
    webServer.on("/save", HTTP_POST, handleWebSave);
    webServer.begin();

    face.setLocalIp(myIp);
    face.unlockScreen();

    webSocket.begin(cfg.data.hostIp.c_str(), cfg.data.hostPort, "/ws/robot");
    webSocket.onEvent(onWebSocketEvent);
    webSocket.setReconnectInterval(3000);
    webSocket.enableHeartbeat(15000, 3000, 2);
}

// ============================================================================
// Non-Blocking Serial Slash-Command CLI
// ============================================================================
void printHelp() {
    Serial.println("\n--- RoboDesk Serial CLI Commands ---");
    Serial.println("  /help                        : Show this command list");
    Serial.println("  /status                      : Print system, Wi-Fi, NVS, RAM & audio stats");
    Serial.println("  /config                      : Start Hotspot & Web Config Portal");
    Serial.println("  /exit_config                 : Exit Config Mode and connect to Wi-Fi");
    Serial.println("  /set_wifi <ssid> <pass>      : Save Wi-Fi credentials directly");
    Serial.println("  /set_host <ip> <port>        : Save Python server IP and port");
    Serial.println("  /clear_config                : Wipe NVS settings and enter Config Mode");
    Serial.println("  /face <normal|happy|surprised|sleepy|thinking|talking|singing>");
    Serial.println("  /gaze <x> <y>                : Move eyes (-1.0 to 1.0)");
    Serial.println("  /text <msg>                  : Show 5s large full-screen text on OLED");
    Serial.println("  /tone <freq> <ms>            : Play test tone on speaker");
    Serial.println("  /echo                        : Run 1-sec Mic-to-Speaker echo test");
    Serial.println("  /reboot                      : Restart ESP32\n");
}

void printStatus() {
    Serial.println("\n--- System & Configuration Status ---");
    Serial.printf("  Mode           : %s\n", inConfigMode ? "CONFIG_HOTSPOT" : "NORMAL_OPERATION");
    Serial.printf("  Device ID      : %s\n", cfg.data.deviceId.c_str());
    Serial.printf("  Saved SSID     : %s (Configured: %s)\n",
                  cfg.data.wifiSsid.c_str(), cfg.data.configured ? "YES" : "NO");
    Serial.printf("  Target Host    : ws://%s:%u/ws/robot\n",
                  cfg.data.hostIp.c_str(), cfg.data.hostPort);
    if (!inConfigMode) {
        Serial.printf("  Wi-Fi Connected: %s (IP: %s, RSSI: %d dBm)\n",
                      WiFi.status() == WL_CONNECTED ? "YES" : "NO",
                      WiFi.localIP().toString().c_str(),
                      WiFi.RSSI());
        Serial.printf("  WebSocket      : %s\n", wsConnected ? "CONNECTED" : "DISCONNECTED");
    }
    Serial.printf("  Power States   : Mic=%s, Spk=%s, OLED=%s\n",
                  audio.micPowerEnabled ? "ON" : "OFF",
                  audio.spkPowerEnabled ? "ON" : "OFF",
                  face.displayPowerEnabled ? "ON" : "OFF");
    Serial.printf("  Sensors Status : Gyro(MPU6050)=%s, Proximity(HC-SR04)=%s\n",
                  isGyroPresent() ? "ATTACHED" : "NOT_FOUND",
                  isUltrasonicPresent() ? "ATTACHED" : "NOT_FOUND");
    Serial.printf("  Master Volume  : %.2f\n", (float)audio.masterVolume);
    Serial.printf("  Mic drops      : %u\n", (unsigned)audio.micDrops);
    Serial.printf("  Speaker drops  : %u\n", (unsigned)audio.spkDrops);
    Serial.printf("  Spk underruns  : %u\n", (unsigned)audio.spkUnderruns);
    Serial.printf("  Spk buffered   : %u bytes\n", (unsigned)audio.speakerBufferedBytes());
    Serial.printf("  Free Heap RAM  : %u bytes\n\n", ESP.getFreeHeap());
}

void processSerialCommand(String line) {
    line.trim();
    if (line.isEmpty()) return;

    Serial.printf("> %s\n", line.c_str());

    if (line.equalsIgnoreCase("/help")) {
        printHelp();
    } else if (line.equalsIgnoreCase("/status")) {
        printStatus();
    } else if (line.equalsIgnoreCase("/config")) {
        enterConfigMode();
    } else if (line.equalsIgnoreCase("/exit_config")) {
        startNormalOperation();
    } else if (line.equalsIgnoreCase("/clear_config")) {
        cfg.clear();
        enterConfigMode();
    } else if (line.equalsIgnoreCase("/reboot")) {
        ESP.restart();
    } else if (line.equalsIgnoreCase("/echo")) {
        face.setExpressionByName("surprised");
        audio.runEchoTest(streamMicToHost);
        face.setExpressionByName("normal");
    } else if (line.startsWith("/set_wifi ")) {
        String args = line.substring(10);
        args.trim();
        int spaceIdx = args.indexOf(' ');
        if (spaceIdx == -1) return;
        cfg.save(args.substring(0, spaceIdx), args.substring(spaceIdx + 1),
                 cfg.data.hostIp, cfg.data.hostPort, cfg.data.deviceId);
        startNormalOperation();
    } else if (line.startsWith("/set_host ")) {
        String args = line.substring(10);
        args.trim();
        int spaceIdx = args.indexOf(' ');
        String newIp = (spaceIdx == -1) ? args : args.substring(0, spaceIdx);
        uint16_t newPort = (spaceIdx == -1) ? 8765 : (uint16_t)args.substring(spaceIdx + 1).toInt();
        if (newPort == 0) newPort = 8765;
        cfg.save(cfg.data.wifiSsid, cfg.data.wifiPass, newIp, newPort, cfg.data.deviceId);
        if (!inConfigMode) {
            webSocket.disconnect();
            webSocket.begin(cfg.data.hostIp.c_str(), cfg.data.hostPort, "/ws/robot");
        }
    } else if (line.startsWith("/face ")) {
        String expr = line.substring(6);
        expr.trim();
        face.setExpressionByName(expr.c_str());
    } else if (line.startsWith("/gaze ")) {
        String args = line.substring(6);
        args.trim();
        int spaceIdx = args.indexOf(' ');
        if (spaceIdx != -1) {
            face.setGaze(args.substring(0, spaceIdx).toFloat(), args.substring(spaceIdx + 1).toFloat());
        }
    } else if (line.startsWith("/text ")) {
        String msg = line.substring(6);
        face.showFullScreenText("[ CLI MESSAGE ]", msg, 2, 5000);
    } else if (line.startsWith("/tone ")) {
        String args = line.substring(6);
        args.trim();
        int spaceIdx = args.indexOf(' ');
        float freq = (spaceIdx == -1) ? args.toFloat() : args.substring(0, spaceIdx).toFloat();
        uint32_t dur = (spaceIdx == -1) ? 150 : (uint32_t)args.substring(spaceIdx + 1).toInt();
        audio.playTone(freq > 50.0f ? freq : 880.0f, dur, 0.25f);
    } else {
        Serial.println("[CLI] Unknown command. Type /help.");
    }
}

void pollSerialCli() {
    while (Serial.available()) {
        char c = (char)Serial.read();
        if (c == '\n' || c == '\r') {
            if (!serialLineBuffer.isEmpty()) {
                processSerialCommand(serialLineBuffer);
                serialLineBuffer = "";
            }
        } else if (serialLineBuffer.length() < 128) {
            serialLineBuffer += c;
        }
    }
}

// ============================================================================
// Mic Ring Buffer -> WebSocket Pump (Enforces Half-Duplex Auto-Mute)
// ============================================================================
void pumpMicToNetwork() {
    bool spkBusy = audio.isSpeakerActive();

    for (int i = 0; i < 6; i++) {
        size_t got = 0;
        uint8_t* p = (uint8_t*)xRingbufferReceiveUpTo(audio.micRing, &got, 0, MIC_SEND_BYTES);
        if (!p) break;

        if (wsConnected && streamMicToHost && !spkBusy && audio.micPowerEnabled) {
            webSocket.sendBIN(p, got);
        }
        vRingbufferReturnItem(audio.micRing, p);
    }
}

// ============================================================================
// Main Setup & Loop
// ============================================================================
void setup() {
    WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG, 0);

    Serial.begin(115200);
    delay(1500);
    Serial.println("\n=== AI RoboDesk Companion (RDP v1.0 Full Firmware) ===");

    face.begin();
    face.startTask();
    audio.begin();
    cfg.load();

    printHelp();

    if (!cfg.data.configured || cfg.data.wifiSsid.isEmpty()) {
        Serial.println("[BOOT] No Wi-Fi config found in NVS. Starting Hotspot Portal...");
        enterConfigMode();
    } else {
        face.setExpressionByName("happy");
        audio.playBootChime();
        delay(350);
        face.setExpressionByName("normal");
        startNormalOperation();
    }
}

void loop() {
    pollSerialCli();

    if (pendingRebootAtMs > 0 && (int32_t)(millis() - pendingRebootAtMs) >= 0) {
        ESP.restart();
    }

    if (inConfigMode) {
        webServer.handleClient();
        delay(2);
        return;
    }

    if (WiFi.status() == WL_CONNECTED) {
        webServer.handleClient();
    }

    webSocket.loop();
    pumpMicToNetwork();
    sendPeriodicTelemetry();

    bool spkActive = audio.isSpeakerActive();
    face.setSpeakerState(spkActive, spkActive ? audio.spkLevel : 0.0f);
    face.setStatusFlags(wsConnected, streamMicToHost && !spkActive);

    delay(1);
}