package ai.saba.app.protocol

import ai.saba.app.audio.AudioUtils
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class RobotTelemetry(
    val freeHeap: Long = 0L,
    val wifiRssi: Int = 0,
    val spkBufferedBytes: Int = 0,
    val spkUnderruns: Int = 0,
    val micDrops: Int = 0,
    val micLevel: Float = 0f,
    val spkLevel: Float = 0f,
)

data class RobotConnectionState(
    val serverRunning: Boolean = false,
    val port: Int = 8765,
    val connected: Boolean = false,
    val clientIp: String = "",
    val deviceId: String = "",
    val firmware: String = "",
    val activeExpression: String = "normal",
    val micStreamEnabled: Boolean = false,
    val telemetry: RobotTelemetry = RobotTelemetry(),
    val rxAudioBytes: Long = 0L,
)

class RdpWebSocketServer(
    private val portNumber: Int,
    private val onRobotHello: (deviceId: String, firmware: String) -> Unit,
    private val onRobotDisconnected: () -> Unit,
    private val onRobotMicPcm: (ByteArray) -> Unit,
    private val onSensorTrigger: (sensor: String, type: String, intensity: Float) -> Unit,
    private val onLog: (tag: String, msg: String, isError: Boolean) -> Unit,
) {
    companion object {
        private const val TAG = "RdpWsServer"
        private const val RDP_VERSION = "1.0"
        private const val SPK_CHUNK_SIZE = 1024
        private const val SPK_PREFILL_BYTES = 6144
    }

    private val _state = MutableStateFlow(RobotConnectionState(port = portNumber))
    val state: StateFlow<RobotConnectionState> = _state.asStateFlow()

    val isRobotConnected: Boolean get() = activeSocket != null && _state.value.connected

    @Volatile
    private var activeSocket: WebSocket? = null
    private var wsServer: InnerWsServer? = null
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val streamAbortRequested = AtomicBoolean(false)

    fun startServer() {
        stopServer()
        try {
            val server = InnerWsServer(InetSocketAddress("0.0.0.0", portNumber)).apply {
                isReuseAddr = true
                connectionLostTimeout = 15
            }
            wsServer = server
            server.start()
            _state.update { it.copy(serverRunning = true, port = portNumber) }
            onLog("WS", "RDP v1.0 WebSocket server listening on :$portNumber/ws/robot", false)
        } catch (e: Exception) {
            onLog("WS", "Failed to start WebSocket server: ${e.message}", true)
        }
    }

    fun stopServer() {
        streamAbortRequested.set(true)
        pendingRequests.values.forEach { it.cancel() }
        pendingRequests.clear()
        runCatching { activeSocket?.close(1000, "Host shutting down") }
        activeSocket = null
        runCatching { wsServer?.stop(500) }
        wsServer = null
        _state.update {
            it.copy(
                serverRunning = false,
                connected = false,
                clientIp = "",
                deviceId = "",
                firmware = "",
            )
        }
    }

    suspend fun sendRequest(
        method: String,
        params: JSONObject = JSONObject(),
        timeoutMs: Long = 4000L,
    ): JSONObject {
        val sock = activeSocket ?: throw IllegalStateException("No robot connected")
        val reqId = "req-${UUID.randomUUID().toString().take(8)}"
        val envelope = JSONObject().apply {
            put("v", RDP_VERSION)
            put("kind", "req")
            put("id", reqId)
            put("method", method)
            put("params", params)
        }

        val deferred = CompletableDeferred<JSONObject>()
        pendingRequests[reqId] = deferred
        try {
            sock.send(envelope.toString())
            return withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pendingRequests.remove(reqId)
        }
    }

    suspend fun setExpression(name: String, durationMs: Int = 0): JSONObject {
        _state.update { it.copy(activeExpression = name) }
        return sendRequest(
            "face.set_expression",
            JSONObject().put("name", name).put("duration_ms", durationMs),
        )
    }

    suspend fun setGaze(x: Float, y: Float, holdMs: Int = 2500): JSONObject =
        sendRequest(
            "face.set_gaze",
            JSONObject()
                .put("x", x.coerceIn(-1f, 1f).toDouble())
                .put("y", y.coerceIn(-1f, 1f).toDouble())
                .put("hold_ms", holdMs),
        )

    suspend fun blink(): JSONObject = sendRequest("face.blink")

    suspend fun showText(title: String, body: String, fontSize: Int = 1, timeoutMs: Int = 5000): JSONObject {
        return if (AudioUtils.isPersianText(title) || AudioUtils.isPersianText(body)) {
            val bitmapBytes = AudioUtils.renderTextToOledBitmap128x64(title, body)
            val b64 = Base64.encodeToString(bitmapBytes, Base64.NO_WRAP)
            sendRequest(
                "display.show_bitmap",
                JSONObject().put("bitmap_base64", b64).put("timeout_ms", timeoutMs),
            )
        } else {
            sendRequest(
                "display.show_text",
                JSONObject()
                    .put("title", title)
                    .put("body", body)
                    .put("font_size", fontSize)
                    .put("timeout_ms", timeoutMs),
            )
        }
    }

    suspend fun clearDisplay() {
        runCatching { sendRequest("display.clear_text", timeoutMs = 2000L) }
        runCatching { sendRequest("display.clear_bitmap", timeoutMs = 2000L) }
    }

    suspend fun setMicCapture(stream: Boolean): JSONObject {
        _state.update { it.copy(micStreamEnabled = stream) }
        return sendRequest("audio.set_mic_capture", JSONObject().put("stream", stream))
    }

    suspend fun setVolume(level: Float): JSONObject =
        sendRequest("audio.set_volume", JSONObject().put("level", level.coerceIn(0f, 1f).toDouble()))

    suspend fun playTone(freq: Float, durationMs: Int = 150, volume: Float = 0.3f): JSONObject =
        sendRequest(
            "audio.play_tone",
            JSONObject()
                .put("freq", freq.toDouble())
                .put("duration_ms", durationMs)
                .put("volume", volume.toDouble()),
        )

    suspend fun abortAudio(): JSONObject {
        streamAbortRequested.set(true)
        return sendRequest("audio.abort", timeoutMs = 2000L)
    }

    suspend fun sendAudioEos(): JSONObject =
        sendRequest("audio.eos", timeoutMs = 3000L)

    suspend fun ping(): JSONObject = sendRequest("system.ping")

    suspend fun getCapabilities(): JSONObject = sendRequest("system.get_capabilities")

    suspend fun reboot(delayMs: Int = 500): JSONObject =
        sendRequest("system.reboot", JSONObject().put("delay_ms", delayMs))

    suspend fun setPowerState(module: String, enabled: Boolean): JSONObject =
        sendRequest(
            "hardware.set_power_state",
            JSONObject().put("module", module).put("enabled", enabled),
        )

    /** Send a single raw PCM binary chunk directly (used by live Walkie-Talkie). */
    fun sendRawPcmChunk(chunk: ByteArray): Boolean {
        val sock = activeSocket ?: return false
        return try {
            sock.send(chunk)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send raw PCM chunk", e)
            false
        }
    }

    /**
     * Paced 16kHz 16-bit mono PCM streamer matching server/core/streamer.py flow control
     * for the ESP32 MAX98357A 24 KB jitter buffer.
     */
    suspend fun streamPcmToSpeaker(
        pcmData: ByteArray,
        expression: String = "talking",
    ): Boolean {
        val sock = activeSocket ?: return false
        if (pcmData.isEmpty()) return false

        streamAbortRequested.set(false)
        runCatching { setExpression(expression, 0) }

        val totalBytes = pcmData.size
        var bytesSent = 0
        val startNs = System.nanoTime()

        try {
            var offset = 0
            while (offset < totalBytes) {
                if (streamAbortRequested.get()) {
                    runCatching { sendRequest("audio.abort", timeoutMs = 2000L) }
                    return false
                }
                val end = minOf(offset + SPK_CHUNK_SIZE, totalBytes)
                val chunk = pcmData.copyOfRange(offset, end)

                if (bytesSent >= SPK_PREFILL_BYTES) {
                    val targetSec = (bytesSent - SPK_PREFILL_BYTES).toDouble() / AudioUtils.BYTES_PER_SECOND
                    val elapsedSec = ((System.nanoTime() - startNs) / 1_000_000_000.0) - 0.15
                    val sleepMs = ((targetSec - elapsedSec) * 1000.0).toLong()
                    if (sleepMs > 2L) {
                        delay(minOf(sleepMs, 100L))
                    }
                } else {
                    delay(5L)
                }

                sock.send(chunk)
                bytesSent += chunk.size
                offset = end
            }

            if (streamAbortRequested.get()) {
                runCatching { sendRequest("audio.abort", timeoutMs = 2000L) }
                return false
            }

            runCatching { sendAudioEos() }
            val bufferedBytes = minOf(totalBytes, 8192)
            val drainMs = ((bufferedBytes.toDouble() / AudioUtils.BYTES_PER_SECOND) * 1000.0).toLong() + 150L
            delay(drainMs)
            return true
        } catch (e: CancellationException) {
            runCatching { sendRequest("audio.abort", timeoutMs = 1500L) }
            throw e
        } catch (e: Exception) {
            onLog("AUDIO", "Speaker stream error: ${e.message}", true)
            runCatching { sendRequest("audio.abort", timeoutMs = 1500L) }
            return false
        }
    }

    private inner class InnerWsServer(address: InetSocketAddress) : WebSocketServer(address) {
        override fun onStart() {
            Log.i(TAG, "WebSocketServer started on port $portNumber")
        }

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            val path = handshake.resourceDescriptor ?: "/"
            if (path != "/ws/robot" && path != "/") {
                conn.close(1008, "Invalid path: $path")
                return
            }
            // Replace previous connection if any
            val prev = activeSocket
            if (prev != null && prev != conn) {
                runCatching { prev.close(1000, "Replaced by new robot connection") }
            }
            activeSocket = conn
            val ip = conn.remoteSocketAddress?.address?.hostAddress ?: "unknown"
            _state.update {
                it.copy(
                    connected = true,
                    clientIp = ip,
                    rxAudioBytes = 0L,
                )
            }
            onLog("WS", "Robot connected from $ip ($path)", false)
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            if (activeSocket == conn) {
                activeSocket = null
                pendingRequests.values.forEach { it.cancel() }
                pendingRequests.clear()
                _state.update {
                    it.copy(
                        connected = false,
                        clientIp = "",
                        deviceId = "",
                        firmware = "",
                        micStreamEnabled = false,
                    )
                }
                onLog("WS", "Robot disconnected (code=$code, reason=$reason)", false)
                onRobotDisconnected()
            }
        }

        override fun onMessage(conn: WebSocket, message: String) {
            try {
                val json = JSONObject(message)
                when (json.optString("kind")) {
                    "res" -> {
                        val id = json.optString("id")
                        pendingRequests.remove(id)?.complete(json)
                    }
                    "event" -> handleEvent(json)
                }
            } catch (e: Exception) {
                onLog("WS", "Malformed JSON from robot: ${e.message}", true)
            }
        }

        override fun onMessage(conn: WebSocket, message: ByteBuffer) {
            val bytes = ByteArray(message.remaining())
            message.get(bytes)
            _state.update { it.copy(rxAudioBytes = it.rxAudioBytes + bytes.size) }
            onRobotMicPcm(bytes)
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            onLog("WS", "WebSocket error: ${ex.message}", true)
        }

        private fun handleEvent(json: JSONObject) {
            val topic = json.optString("topic")
            val data = json.optJSONObject("data") ?: JSONObject()
            when (topic) {
                "system.hello" -> {
                    val devId = data.optString("device_id", "robo-desk")
                    val fw = data.optString("firmware", "unknown")
                    _state.update { it.copy(deviceId = devId, firmware = fw) }
                    onLog("ROBOT", "system.hello -> ID=$devId, FW=$fw", false)
                    onRobotHello(devId, fw)
                }
                "sensor.telemetry" -> {
                    val tel = RobotTelemetry(
                        freeHeap = data.optLong("free_heap", 0L),
                        wifiRssi = data.optInt("wifi_rssi", 0),
                        spkBufferedBytes = data.optInt("spk_buffered_bytes", 0),
                        spkUnderruns = data.optInt("spk_underruns", 0),
                        micDrops = data.optInt("mic_drops", 0),
                        micLevel = data.optDouble("mic_level", 0.0).toFloat(),
                        spkLevel = data.optDouble("spk_level", 0.0).toFloat(),
                    )
                    _state.update { it.copy(telemetry = tel) }
                }
                "sensor.trigger" -> {
                    val sensor = data.optString("sensor", "unknown")
                    val type = data.optString("type", "unknown")
                    val intensity = data.optDouble("intensity", 1.0).toFloat()
                    onLog("SENSOR", "Trigger: $sensor/$type (intensity=$intensity)", false)
                    onSensorTrigger(sensor, type, intensity)
                }
                else -> {
                    onLog("EVENT", "$topic -> $data", false)
                }
            }
        }
    }
}
