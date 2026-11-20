package ai.saba.app.ai

import ai.saba.app.audio.AudioUtils
import ai.saba.app.config.AudioSourceMode
import ai.saba.app.config.SabaSettings
import ai.saba.app.config.SabaSettingsStore
import ai.saba.app.protocol.RdpWebSocketServer
import ai.saba.app.protocol.RobotConnectionState
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class AssistantStage(val labelFa: String) {
    INITIALIZING("در حال آماده‌سازی..."),
    STANDBY("آماده‌باش (بگویید «صبا»)"),
    RECORDING_COMMAND("در حال گوش دادن به دستور..."),
    PROCESSING_LLM("ارسال صوت و دریافت پاسخ (LLM)..."),
    PROCESSING_TTS("تولید صدا (TTS)..."),
    SPEAKING("در حال صحبت کردن..."),
    WALKIE_TALKIE("واکی‌تاکی فعال (ارسال صدا)"),
    ERROR("خطا"),
}

data class LogEntry(
    val id: Long,
    val time: String,
    val tag: String,
    val message: String,
    val isHighlight: Boolean = false,
    val isError: Boolean = false,
)

data class SabaUiState(
    val stage: AssistantStage = AssistantStage.INITIALIZING,
    val statusDetail: String = "در حال راه‌اندازی موتورها...",
    val errorMessage: String? = null,
    val micPermissionGranted: Boolean = false,
    val activeAudioInput: String = "—",
    val localIpAddress: String = "0.0.0.0",
    val isSpeechDetected: Boolean = false,
    val rmsLevel: Float = 0f,
    val wakeWordCount: Int = 0,
    val lastWakeWordTime: String = "",
    val lastReply: String = "",
    val isListeningToRobotMic: Boolean = false,
    val isWalkieTalkieActive: Boolean = false,
    val robotVolume: Float = 0.8f,
    val history: List<ConversationTurn> = emptyList(),
    val logs: List<LogEntry> = emptyList(),
)

class SabaVoiceCoordinator(
    private val context: Context,
    val settingsStore: SabaSettingsStore,
) {
    companion object {
        private const val TAG = "SabaCoordinator"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = 512 // 32ms @ 16kHz
        private const val MAX_RECORD_MS = 15_000L
        private const val NO_SPEECH_TIMEOUT_MS = 6_500L
        private const val MIN_VALID_AUDIO_BYTES = 11_200 // ~0.35s @ 16kHz mono PCM16
        private const val MAX_LOGS = 200
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val aiClient = OpenAiPipelineClient(context)
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _uiState = MutableStateFlow(SabaUiState(localIpAddress = getLocalIpv4Address()))
    val uiState: StateFlow<SabaUiState> = _uiState.asStateFlow()

    val rdpServer: RdpWebSocketServer = RdpWebSocketServer(
        portNumber = settingsStore.current.wsPort,
        onRobotHello = ::handleRobotHello,
        onRobotDisconnected = ::handleRobotDisconnected,
        onRobotMicPcm = ::handleIncomingRobotPcm,
        onSensorTrigger = ::handleRobotSensorTrigger,
        onLog = { tag, msg, isErr -> log(tag, msg, isError = isErr) },
    )

    val robotState: StateFlow<RobotConnectionState> get() = rdpServer.state

    private val audioInputChannel = Channel<ByteArray>(capacity = 64)

    // Sherpa-ONNX engines (accessed only inside audioProcessorJob)
    private var kws: KeywordSpotter? = null
    private var kwsStream: OnlineStream? = null
    private var vad: Vad? = null
    private var toneGenerator: ToneGenerator? = null

    private var audioProcessorJob: Job? = null
    private var phoneMicJob: Job? = null
    private var turnJob: Job? = null
    private var walkieTalkieJob: Job? = null
    private var robotMicMonitorTrack: AudioTrack? = null

    private val isStarted = AtomicBoolean(false)
    private val isReleased = AtomicBoolean(false)
    @Volatile
    private var currentStage: AssistantStage = AssistantStage.INITIALIZING
    @Volatile
    private var listeningToRobotMic = false
    @Volatile
    private var walkieTalkieActive = false
    @Volatile
    private var forceWakeRequested = false
    @Volatile
    private var reloadEnginesRequested = false

    private var nextLogId = 1L
    private var nextTurnId = 1L

    private val commandPcmBuffer = ByteArrayOutputStream()
    private var recordingStartedAtMs = 0L
    private var hasHeardSpeechInCommand = false
    private var lastSpeechSeenAtMs = 0L

    fun startAll() {
        if (!isStarted.compareAndSet(false, true)) return
        refreshLocalIp()
        rdpServer.startServer()
        toneGenerator = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 85) }.getOrNull()
        startAudioProcessorLoop()
        syncPhoneMicCapture()
    }

    fun refreshLocalIp() {
        _uiState.update { it.copy(localIpAddress = getLocalIpv4Address()) }
    }

    fun onMicPermissionChanged(granted: Boolean) {
        _uiState.update { it.copy(micPermissionGranted = granted) }
        log("PERM", if (granted) "مجوز میکروفون تأیید شد" else "مجوز میکروفون رد شد", isError = !granted)
        syncPhoneMicCapture()
    }

    fun applySettings(updated: SabaSettings) {
        val old = settingsStore.current
        settingsStore.save(updated)
        log("CFG", "تنظیمات ذخیره شد (LLM=${updated.llmModel}, TTS=${updated.ttsModel}/${updated.ttsResponseFormat})")

        if (old.vadThreshold != updated.vadThreshold ||
            old.kwsScore != updated.kwsScore ||
            old.kwsThreshold != updated.kwsThreshold
        ) {
            reloadEnginesRequested = true
        }
        if (old.audioSourceMode != updated.audioSourceMode) {
            syncPhoneMicCapture()
        }
    }

    private fun shouldUseRobotAudio(): Boolean {
        return when (settingsStore.current.audioSourceMode) {
            AudioSourceMode.ROBOT_ONLY -> true
            AudioSourceMode.PHONE_MIC_TEST -> false
            AudioSourceMode.AUTO_PREFER_ROBOT -> rdpServer.isRobotConnected
        }
    }

    private fun syncPhoneMicCapture() {
        val useRobot = shouldUseRobotAudio()
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        _uiState.update {
            it.copy(
                micPermissionGranted = hasPerm,
                activeAudioInput = when {
                    useRobot && rdpServer.isRobotConnected -> "میکروفون ربات (${rdpServer.state.value.deviceId.ifEmpty { "ESP32" }})"
                    useRobot -> "در انتظار اتصال ربات..."
                    hasPerm -> "میکروفون گوشی (تست)"
                    else -> "نیاز به مجوز میکروفون"
                },
            )
        }

        if (!useRobot && hasPerm && !walkieTalkieActive) {
            startPhoneMicCapture()
        } else {
            stopPhoneMicCapture()
        }
    }

    private fun handleRobotHello(deviceId: String, firmware: String) {
        scope.launch {
            delay(400)
            runCatching { rdpServer.setExpression("sleepy", 0) }
            runCatching { rdpServer.setVolume(_uiState.value.robotVolume) }
            runCatching { rdpServer.setMicCapture(true) }
            syncPhoneMicCapture()
            log("ROBOT", "ربات $deviceId آماده شد (میکروفون فعال)", isHighlight = true)
        }
    }

    private fun handleRobotDisconnected() {
        setListenToRobotMic(false)
        if (walkieTalkieActive) stopWalkieTalkie()
        syncPhoneMicCapture()
    }

    private fun handleRobotSensorTrigger(sensor: String, type: String, intensity: Float) {
        if (sensor == "mpu6050" && type == "shake") {
            if (currentStage == AssistantStage.SPEAKING) {
                abortCurrentAction()
            } else if (currentStage == AssistantStage.STANDBY) {
                triggerManualWake()
            }
        }
    }

    private fun handleIncomingRobotPcm(pcmBytes: ByteArray) {
        if (listeningToRobotMic) {
            runCatching { robotMicMonitorTrack?.write(pcmBytes, 0, pcmBytes.size) }
        }
        if (shouldUseRobotAudio() && !walkieTalkieActive) {
            audioInputChannel.trySend(pcmBytes)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPhoneMicCapture() {
        if (phoneMicJob?.isActive == true) return
        phoneMicJob = scope.launch {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) {
                log("MIC", "AudioRecord.getMinBufferSize خطا داد: $minBuf", isError = true)
                return@launch
            }
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, CHUNK_SAMPLES * 2 * 8),
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                log("MIC", "میکروفون گوشی آماده نشد", isError = true)
                return@launch
            }
            val shortBuf = ShortArray(CHUNK_SAMPLES)
            try {
                recorder.startRecording()
                log("MIC", "میکروفون گوشی فعال شد (16kHz mono)")
                while (isActive && !walkieTalkieActive && !shouldUseRobotAudio()) {
                    val read = recorder.read(shortBuf, 0, shortBuf.size)
                    if (read > 0) {
                        val pcmBytes = AudioUtils.shortArrayToPcm16LeBytes(shortBuf, read)
                        audioInputChannel.trySend(pcmBytes)
                    }
                }
            } finally {
                runCatching {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                    recorder.release()
                }
            }
        }
    }

    private fun stopPhoneMicCapture() {
        phoneMicJob?.cancel()
        phoneMicJob = null
    }

    private fun startAudioProcessorLoop() {
        audioProcessorJob = scope.launch {
            try {
                initSherpaEngines()
                updateStage(AssistantStage.STANDBY, "گوش‌به‌زنگ کلمه بیدارباش «صبا»...")

                val pendingBytes = ByteArrayOutputStream()
                val chunkBytes = CHUNK_SAMPLES * 2

                for (pcmBytes in audioInputChannel) {
                    if (!isActive || isReleased.get()) break

                    if (reloadEnginesRequested) {
                        reloadEnginesRequested = false
                        releaseSherpaEngines()
                        initSherpaEngines()
                        log("KWS", "موتورهای KWS/VAD با تنظیمات جدید بازنشانی شدند")
                    }

                    pendingBytes.write(pcmBytes)
                    val allBytes = pendingBytes.toByteArray()
                    val fullChunks = allBytes.size / chunkBytes
                    if (fullChunks == 0) continue

                    pendingBytes.reset()
                    val consumed = fullChunks * chunkBytes
                    if (consumed < allBytes.size) {
                        pendingBytes.write(allBytes, consumed, allBytes.size - consumed)
                    }

                    for (c in 0 until fullChunks) {
                        val slice = allBytes.copyOfRange(c * chunkBytes, (c + 1) * chunkBytes)
                        processAudioFrame(slice)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val msg = "${t::class.java.simpleName}: ${t.message}"
                Log.e(TAG, "Audio processor crashed", t)
                updateStage(AssistantStage.ERROR, "خطا در موتور صوتی", error = msg)
                log("ERR", msg, isError = true)
            } finally {
                releaseSherpaEngines()
            }
        }
    }

    private fun initSherpaEngines() {
        val cfg = settingsStore.current
        updateStage(AssistantStage.INITIALIZING, "بارگذاری مدل‌های Sherpa-ONNX KWS و Silero VAD...")

        val kwsConfig = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "kws/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    decoder = "kws/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    joiner = "kws/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                ),
                tokens = "kws/tokens.txt",
                numThreads = 2,
                debug = false,
                provider = "cpu",
                modelType = "zipformer2",
            ),
            maxActivePaths = 4,
            keywordsFile = "kws/keywords.txt",
            keywordsScore = cfg.kwsScore,
            keywordsThreshold = cfg.kwsThreshold,
            numTrailingBlanks = 1,
        )
        val spotter = KeywordSpotter(assetManager = context.assets, config = kwsConfig)
        kws = spotter
        kwsStream = spotter.createStream("")

        val vadConfig = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "vad/silero_vad.onnx",
                threshold = cfg.vadThreshold,
                minSilenceDuration = (cfg.vadEndSilenceMs / 1000f).coerceIn(0.25f, 3.0f),
                minSpeechDuration = 0.2f,
                windowSize = CHUNK_SAMPLES,
                maxSpeechDuration = 20.0f,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
            debug = false,
        )
        vad = Vad(assetManager = context.assets, config = vadConfig)
        log("INIT", "Sherpa-ONNX KWS (Saba) و Silero VAD آماده شدند")
    }

    private fun releaseSherpaEngines() {
        runCatching { kwsStream?.release() }
        kwsStream = null
        runCatching { kws?.release() }
        kws = null
        runCatching { vad?.release() }
        vad = null
    }

    private fun processAudioFrame(pcmChunk: ByteArray) {
        val spotter = kws ?: return
        val stream = kwsStream ?: return
        val voiceDetector = vad ?: return

        val samples = AudioUtils.pcm16LeBytesToFloatArray(pcmChunk)
        val rms = AudioUtils.computeRms(samples)

        voiceDetector.acceptWaveform(samples)
        val speechNow = voiceDetector.isSpeechDetected()
        while (!voiceDetector.empty()) {
            voiceDetector.pop()
        }

        _uiState.update { state ->
            if (state.isSpeechDetected != speechNow || kotlin.math.abs(state.rmsLevel - rms) > 0.008f) {
                state.copy(isSpeechDetected = speechNow, rmsLevel = rms)
            } else {
                state
            }
        }

        if (walkieTalkieActive) return

        if (forceWakeRequested && currentStage == AssistantStage.STANDBY) {
            forceWakeRequested = false
            enterRecordingCommand(triggerLabel = "بیدارباش دستی")
            return
        }

        when (currentStage) {
            AssistantStage.STANDBY -> {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                while (spotter.isReady(stream)) {
                    spotter.decode(stream)
                    val res = spotter.getResult(stream)
                    val detected = res.keyword.trim()
                    if (detected.isNotEmpty()) {
                        val toks = res.tokens.joinToString(" ")
                        spotter.reset(stream)
                        val nowStr = nowTime()
                        _uiState.update {
                            it.copy(
                                wakeWordCount = it.wakeWordCount + 1,
                                lastWakeWordTime = nowStr,
                            )
                        }
                        enterRecordingCommand(triggerLabel = "\"$detected\" ($toks)")
                        return
                    }
                }
            }

            AssistantStage.RECORDING_COMMAND -> {
                commandPcmBuffer.write(pcmChunk)
                val nowMs = System.currentTimeMillis()
                val elapsedMs = nowMs - recordingStartedAtMs
                val endSilenceMs = settingsStore.current.vadEndSilenceMs.toLong().coerceIn(300L, 4000L)

                if (speechNow) {
                    hasHeardSpeechInCommand = true
                    lastSpeechSeenAtMs = nowMs
                }

                val silenceAfterSpeechDone = hasHeardSpeechInCommand && (nowMs - lastSpeechSeenAtMs >= endSilenceMs)
                val timedOutNoSpeech = !hasHeardSpeechInCommand && elapsedMs >= NO_SPEECH_TIMEOUT_MS
                val reachedMaxLen = elapsedMs >= MAX_RECORD_MS

                if (timedOutNoSpeech) {
                    commandPcmBuffer.reset()
                    hasHeardSpeechInCommand = false
                    log("VAD", "صدایی شنیده نشد؛ بازگشت به حالت آماده‌باش")
                    returnToStandby()
                } else if (silenceAfterSpeechDone || reachedMaxLen) {
                    val capturedPcm = commandPcmBuffer.toByteArray()
                    commandPcmBuffer.reset()
                    hasHeardSpeechInCommand = false

                    if (capturedPcm.size < MIN_VALID_AUDIO_BYTES) {
                        log("VAD", "صدای ضبط‌شده خیلی کوتاه بود (${capturedPcm.size} B)؛ نادیده گرفته شد")
                        returnToStandby()
                        return
                    }

                    // CRITICAL: Transition stage synchronously BEFORE launching async turnJob
                    // so subsequent 512-sample chunks in the same WebSocket frame cannot re-trigger!
                    val durSec = capturedPcm.size.toFloat() / AudioUtils.BYTES_PER_SECOND
                    updateStage(
                        AssistantStage.PROCESSING_LLM,
                        "ارسال مستقیم صوت (${String.format(Locale.US, "%.1f", durSec)}s) به LLM...",
                    )
                    log("VAD", "ضبط دستور پایان یافت (${String.format(Locale.US, "%.1f", durSec)} ثانیه)")
                    launchAudioTurn(capturedPcm, durSec)
                }
            }

            else -> {
                // Ignore incoming audio while LLM / TTS / SPEAKING are running
            }
        }
    }

    private fun enterRecordingCommand(triggerLabel: String) {
        commandPcmBuffer.reset()
        recordingStartedAtMs = System.currentTimeMillis()
        hasHeardSpeechInCommand = false
        lastSpeechSeenAtMs = recordingStartedAtMs
        vad?.reset()

        updateStage(AssistantStage.RECORDING_COMMAND, "صحبت کنید... پس از سکوت مستقیم برای LLM ارسال می‌شود")
        log("KWS", "بیدار شد: $triggerLabel -> در حال ضبط دستور", isHighlight = true)

        scope.launch {
            if (rdpServer.isRobotConnected) {
                runCatching { rdpServer.setExpression("happy", 1200) }
                runCatching { rdpServer.playTone(1046.5f, 120, 0.35f) }
            } else {
                runCatching { toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 120) }
            }
        }
    }

    private fun launchAudioTurn(recordedPcm16k: ByteArray, durationSec: Float) {
        turnJob?.cancel()
        turnJob = scope.launch {
            val cfg = settingsStore.current
            try {
                if (rdpServer.isRobotConnected) {
                    runCatching { rdpServer.setMicCapture(false) }
                    runCatching { rdpServer.setExpression("thinking", 0) }
                }

                // Stage 1: Send recorded WAV directly to multimodal LLM
                val currentHistory = _uiState.value.history
                val reply = aiClient.generateReplyFromAudio(recordedPcm16k, currentHistory, cfg)
                val emotion = reply.emotion ?: "talking"
                val userLabel = "🎙️ پیام صوتی (${String.format(Locale.US, "%.1f", durationSec)} ثانیه)"

                appendHistory("user", userLabel, null)
                appendHistory("assistant", reply.spokenText, emotion)
                _uiState.update { it.copy(lastReply = reply.spokenText, errorMessage = null) }
                log(
                    "LLM",
                    "پاسخ در ${formatSec(reply.networkMs)} [$emotion]: ${reply.spokenText}",
                    isHighlight = true,
                )

                // Stage 2: Pipelined Sentence-by-Sentence TTS & Playback
                speakWithSentencePipeline(reply.spokenText, emotion, cfg)
                returnToStandby()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val err = "${t::class.java.simpleName}: ${t.message}"
                log("AI", "خطا در پایپ‌لاین: $err", isError = true)
                _uiState.update { it.copy(errorMessage = err) }
                if (rdpServer.isRobotConnected) {
                    runCatching { rdpServer.setExpression("surprised", 1500) }
                }
                delay(400)
                returnToStandby()
            }
        }
    }

    /** Sends typed text directly into LLM -> Pipelined TTS -> Robot/Phone Speaker. */
    fun sendTextChat(userText: String) {
        val clean = userText.trim()
        if (clean.isEmpty()) return
        turnJob?.cancel()
        updateStage(AssistantStage.PROCESSING_LLM, "دریافت پاسخ از LLM...")
        turnJob = scope.launch {
            val cfg = settingsStore.current
            try {
                if (rdpServer.isRobotConnected) {
                    runCatching { rdpServer.setMicCapture(false) }
                    runCatching { rdpServer.setExpression("thinking", 0) }
                }
                _uiState.update { it.copy(errorMessage = null) }
                log("CHAT", "متن کاربر: $clean", isHighlight = true)

                val reply = aiClient.generateReplyFromText(clean, _uiState.value.history, cfg)
                val emotion = reply.emotion ?: "talking"

                appendHistory("user", clean, null)
                appendHistory("assistant", reply.spokenText, emotion)
                _uiState.update { it.copy(lastReply = reply.spokenText) }
                log(
                    "LLM",
                    "پاسخ در ${formatSec(reply.networkMs)} [$emotion]: ${reply.spokenText}",
                    isHighlight = true,
                )

                speakWithSentencePipeline(reply.spokenText, emotion, cfg)
                returnToStandby()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val err = "${t::class.java.simpleName}: ${t.message}"
                log("AI", "خطا در پاسخ متنی: $err", isError = true)
                _uiState.update { it.copy(errorMessage = err) }
                returnToStandby()
            }
        }
    }

    /**
     * Synthesizes all sentences in parallel immediately after LLM finishes, caching
     * their decoded 16kHz PCM in memory so sentence N+1 starts playing immediately
     * when sentence N finishes (zero inter-sentence gap).
     */
    private suspend fun speakWithSentencePipeline(
        text: String,
        emotion: String,
        cfg: SabaSettings,
    ) {
        val sentences = aiClient.splitSentencesForTts(text)
        if (sentences.isEmpty()) return

        if (sentences.size > 1) {
            log("TTS", "شروع تولید موازی و کش ${sentences.size} جمله (${cfg.ttsModel})...")
        }

        // Dispatch all sentence TTS calls concurrently so subsequent sentences are
        // already synthesized and cached in RAM by the time sentence 1 finishes playing.
        val ttsJobs: List<Deferred<TtsResult>> = sentences.map { sentence ->
            scope.async { aiClient.synthesizeToPcm16k(sentence, cfg) }
        }

        try {
            for (i in ttsJobs.indices) {
                if (!ttsJobs[i].isCompleted) {
                    updateStage(
                        AssistantStage.PROCESSING_TTS,
                        "در انتظار آماده شدن جمله ${i + 1}/${sentences.size} (${cfg.ttsModel})...",
                    )
                }

                val wasCached = ttsJobs[i].isCompleted
                val ttsRes = ttsJobs[i].await()

                val audioSec = ttsRes.pcm16k.size.toFloat() / AudioUtils.BYTES_PER_SECOND
                val cacheTag = if (wasCached) " [از کش آماده]" else ""
                log(
                    "TTS",
                    "جمله ${i + 1}/${sentences.size}$cacheTag: شبکه=${formatSec(ttsRes.networkMs)} | دیکد=${ttsRes.decodeMs}ms | صدا=${String.format(Locale.US, "%.1f", audioSec)}s (${ttsRes.rawBytes / 1024}KB)",
                )

                if (ttsRes.pcm16k.isEmpty()) continue

                updateStage(
                    AssistantStage.SPEAKING,
                    if (rdpServer.isRobotConnected) {
                        "پخش جمله ${i + 1}/${sentences.size} روی ربات..."
                    } else {
                        "پخش جمله ${i + 1}/${sentences.size} روی گوشی..."
                    },
                )
                if (rdpServer.isRobotConnected) {
                    val ok = rdpServer.streamPcmToSpeaker(ttsRes.pcm16k, expression = emotion)
                    if (!ok) break
                } else {
                    AudioUtils.playPcmOnPhoneSpeaker(ttsRes.pcm16k)
                }
            }
        } finally {
            ttsJobs.forEach { it.cancel() }
        }
    }

    private fun returnToStandby() {
        updateStage(AssistantStage.STANDBY, "گوش‌به‌زنگ کلمه بیدارباش «صبا»...")
        scope.launch {
            if (rdpServer.isRobotConnected) {
                runCatching { rdpServer.setExpression("sleepy", 0) }
                runCatching { rdpServer.setMicCapture(true) }
            }
        }
    }

    fun triggerManualWake() {
        if (currentStage == AssistantStage.STANDBY) {
            forceWakeRequested = true
            audioInputChannel.trySend(ByteArray(CHUNK_SAMPLES * 2))
        }
    }

    fun abortCurrentAction() {
        turnJob?.cancel()
        turnJob = null
        commandPcmBuffer.reset()
        hasHeardSpeechInCommand = false
        scope.launch {
            if (rdpServer.isRobotConnected) {
                runCatching { rdpServer.abortAudio() }
            }
            log("SYS", "عملیات جاری متوقف شد -> بازگشت به آماده‌باش")
            returnToStandby()
        }
    }

    fun speakCustomText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        turnJob?.cancel()
        updateStage(AssistantStage.PROCESSING_TTS, "تولید صدای متن سفارشی...")
        turnJob = scope.launch {
            val cfg = settingsStore.current
            try {
                if (rdpServer.isRobotConnected) runCatching { rdpServer.setMicCapture(false) }
                speakWithSentencePipeline(clean, "happy", cfg)
                log("TTS", "پخش شد: $clean")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                log("TTS", "خطا در TTS: ${t.message}", isError = true)
                _uiState.update { it.copy(errorMessage = t.message) }
            } finally {
                returnToStandby()
            }
        }
    }

    fun playTestMelody() {
        turnJob?.cancel()
        updateStage(AssistantStage.SPEAKING, "پخش ملودی تست...")
        turnJob = scope.launch {
            val pcm = AudioUtils.generateMelodyPcm16k()
            if (rdpServer.isRobotConnected) {
                runCatching { rdpServer.setMicCapture(false) }
                rdpServer.streamPcmToSpeaker(pcm, expression = "singing")
            } else {
                AudioUtils.playPcmOnPhoneSpeaker(pcm)
            }
            returnToStandby()
        }
    }

    // ==================== Robot Control Panel Actions ====================

    fun sendRobotCommand(label: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                if (!rdpServer.isRobotConnected) {
                    log("ROBOT", "ربات متصل نیست ($label)", isError = true)
                    return@launch
                }
                block()
                log("ROBOT", "اجرا شد: $label")
            } catch (e: Exception) {
                log("ROBOT", "خطا در $label: ${e.message}", isError = true)
            }
        }
    }

    fun setRobotVolume(level: Float) {
        _uiState.update { it.copy(robotVolume = level) }
        sendRobotCommand("تنظیم ولوم ${(level * 100).toInt()}%") {
            rdpServer.setVolume(level)
        }
    }

    fun setListenToRobotMic(enabled: Boolean) {
        if (enabled == listeningToRobotMic) return
        if (enabled) {
            if (!rdpServer.isRobotConnected) {
                log("AUDIO", "برای شنود میکروفون ربات، ابتدا ربات باید متصل باشد", isError = true)
                return
            }
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf * 2, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
            robotMicMonitorTrack = track
            listeningToRobotMic = true
            _uiState.update { it.copy(isListeningToRobotMic = true) }
            sendRobotCommand("روشن کردن شنود میکروفون ربات") {
                rdpServer.setMicCapture(true)
            }
        } else {
            listeningToRobotMic = false
            runCatching {
                robotMicMonitorTrack?.stop()
                robotMicMonitorTrack?.release()
            }
            robotMicMonitorTrack = null
            _uiState.update { it.copy(isListeningToRobotMic = false) }
            log("AUDIO", "شنود میکروفون ربات خاموش شد")
        }
    }

    @SuppressLint("MissingPermission")
    fun startWalkieTalkie() {
        if (walkieTalkieActive) return
        if (!rdpServer.isRobotConnected) {
            log("PTT", "برای واکی‌تاکی، ربات باید متصل باشد", isError = true)
            return
        }
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) {
            log("PTT", "مجوز میکروفون گوشی برای واکی‌تاکی لازم است", isError = true)
            return
        }

        walkieTalkieActive = true
        stopPhoneMicCapture()
        turnJob?.cancel()
        _uiState.update { it.copy(isWalkieTalkieActive = true) }
        updateStage(AssistantStage.WALKIE_TALKIE, "نگه دارید و صحبت کنید (ارسال زنده به ربات)...")

        walkieTalkieJob = scope.launch {
            runCatching { rdpServer.setMicCapture(false) }
            runCatching { rdpServer.setExpression("talking", 0) }

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, CHUNK_SAMPLES * 2 * 6),
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                log("PTT", "خطا در باز کردن میکروفون برای واکی‌تاکی", isError = true)
                stopWalkieTalkie()
                return@launch
            }

            val shortBuf = ShortArray(CHUNK_SAMPLES)
            try {
                recorder.startRecording()
                log("PTT", "واکی‌تاکی شروع شد", isHighlight = true)
                while (isActive && walkieTalkieActive && rdpServer.isRobotConnected) {
                    val read = recorder.read(shortBuf, 0, shortBuf.size)
                    if (read > 0) {
                        val pcmBytes = AudioUtils.shortArrayToPcm16LeBytes(shortBuf, read)
                        rdpServer.sendRawPcmChunk(pcmBytes)
                    }
                }
            } finally {
                runCatching {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                    recorder.release()
                }
                if (rdpServer.isRobotConnected) {
                    runCatching { rdpServer.sendAudioEos() }
                }
            }
        }
    }

    fun stopWalkieTalkie() {
        if (!walkieTalkieActive) return
        walkieTalkieActive = false
        walkieTalkieJob?.cancel()
        walkieTalkieJob = null
        _uiState.update { it.copy(isWalkieTalkieActive = false) }
        log("PTT", "واکی‌تاکی متوقف شد")
        syncPhoneMicCapture()
        returnToStandby()
    }

    fun clearHistory() {
        _uiState.update { it.copy(history = emptyList()) }
        log("CHAT", "تاریخچه گفتگو پاک شد")
    }

    fun clearLogs() {
        _uiState.update { it.copy(logs = emptyList()) }
    }

    private fun appendHistory(role: String, text: String, emotion: String?) {
        val turn = ConversationTurn(
            id = synchronized(this) { nextTurnId++ },
            role = role,
            text = text,
            emotion = emotion,
            timestamp = nowTime(),
        )
        _uiState.update { it.copy(history = (it.history + turn).takeLast(40)) }
    }

    private fun updateStage(stage: AssistantStage, detail: String, error: String? = null) {
        currentStage = stage
        _uiState.update {
            it.copy(
                stage = stage,
                statusDetail = detail,
                errorMessage = error ?: if (stage != AssistantStage.ERROR) null else it.errorMessage,
            )
        }
    }

    private fun formatSec(ms: Long): String =
        if (ms < 1000L) "${ms}ms" else String.format(Locale.US, "%.2fs", ms / 1000.0)

    private fun nowTime(): String = synchronized(timeFmt) { timeFmt.format(Date()) }

    fun log(tag: String, message: String, isHighlight: Boolean = false, isError: Boolean = false) {
        if (isError) Log.e(TAG, "[$tag] $message") else Log.i(TAG, "[$tag] $message")
        val entry = LogEntry(
            id = synchronized(this) { nextLogId++ },
            time = nowTime(),
            tag = tag,
            message = message,
            isHighlight = isHighlight,
            isError = isError,
        )
        _uiState.update { state ->
            state.copy(logs = (listOf(entry) + state.logs).take(MAX_LOGS))
        }
    }

    private fun getLocalIpv4Address(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "0.0.0.0"
            var fallback = "0.0.0.0"
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (nif.name.startsWith("wlan") || nif.name.startsWith("ap")) {
                            return ip
                        }
                        fallback = ip
                    }
                }
            }
            fallback
        } catch (_: Exception) {
            "0.0.0.0"
        }
    }

    fun shutdown() {
        if (!isReleased.compareAndSet(false, true)) return
        setListenToRobotMic(false)
        stopWalkieTalkie()
        stopPhoneMicCapture()
        turnJob?.cancel()
        rdpServer.stopServer()
        audioInputChannel.close()
        runBlocking {
            audioProcessorJob?.cancelAndJoin()
        }
        runCatching { toneGenerator?.release() }
        toneGenerator = null
        scope.cancel()
    }
}
