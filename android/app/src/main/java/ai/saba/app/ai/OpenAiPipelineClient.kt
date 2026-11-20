package ai.saba.app.ai

import ai.saba.app.audio.AudioUtils
import ai.saba.app.config.SabaSettings
import android.content.Context
import android.util.Base64
import com.aallam.openai.api.audio.SpeechRequest
import com.aallam.openai.api.audio.SpeechResponseFormat
import com.aallam.openai.api.audio.Voice
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.LoggingConfig
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

data class ConversationTurn(
    val id: Long,
    val role: String, // "user" or "assistant"
    val text: String,
    val emotion: String? = null,
    val timestamp: String,
)

data class LlmReply(
    val emotion: String?,
    val spokenText: String,
    val rawText: String,
    val networkMs: Long = 0L,
)

data class TtsResult(
    val pcm16k: ByteArray,
    val networkMs: Long,
    val decodeMs: Long,
    val rawBytes: Int,
)

class OpenAiPipelineClient(
    private val context: Context,
) {
    companion object {
        private val FACE_TAG_REGEX =
            Regex("""\[face:(normal|happy|surprised|sleepy|thinking|talking|singing)]""", RegexOption.IGNORE_CASE)
        private val THINK_TAG_REGEX =
            Regex("""<think>[\s\S]*?</think>""", RegexOption.IGNORE_CASE)
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun buildClient(baseUrl: String, apiKey: String): OpenAI {
        return OpenAI(
            OpenAIConfig(
                token = apiKey.ifBlank { "no-key" },
                host = OpenAIHost(baseUrl = SabaSettings.normalizeBaseUrl(baseUrl)),
                logging = LoggingConfig(logLevel = LogLevel.None),
                timeout = Timeout(
                    request = 90.seconds,
                    connect = 20.seconds,
                    socket = 60.seconds,
                ),
            )
        )
    }

    /**
     * Stage 1 (Audio-Direct): Sends recorded 16kHz PCM wrapped as WAV directly to
     * an OpenAI-compatible multimodal LLM (/chat/completions with input_audio).
     * Eliminates separate STT round-trip and cost.
     */
    suspend fun generateReplyFromAudio(
        pcm16kMono: ByteArray,
        history: List<ConversationTurn>,
        settings: SabaSettings,
    ): LlmReply = withContext(Dispatchers.IO) {
        val wavBytes = AudioUtils.pcm16ToWavBytes(pcm16kMono, AudioUtils.ROBOT_SAMPLE_RATE)
        val audioBase64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)

        val messagesArr = JSONArray()
        if (settings.systemPrompt.isNotBlank()) {
            messagesArr.put(
                JSONObject()
                    .put("role", "system")
                    .put("content", settings.systemPrompt)
            )
        }
        val recent = history.takeLast(settings.maxHistoryMessages.coerceAtLeast(0))
        for (turn in recent) {
            messagesArr.put(
                JSONObject()
                    .put("role", turn.role)
                    .put("content", turn.text)
            )
        }

        val userContentParts = JSONArray()
            .put(
                JSONObject()
                    .put("type", "text")
                    .put("text", "به صدای کاربر در این فایل صوتی به فارسی گوش بده و مستقیم و کوتاه جواب بده.")
            )
            .put(
                JSONObject()
                    .put("type", "input_audio")
                    .put(
                        "input_audio",
                        JSONObject()
                            .put("data", audioBase64)
                            .put("format", "wav")
                    )
            )

        messagesArr.put(
            JSONObject()
                .put("role", "user")
                .put("content", userContentParts)
        )

        val payload = JSONObject()
            .put("model", settings.llmModel.ifBlank { "gemini-2.5-flash" })
            .put("messages", messagesArr)

        val endpoint = "${SabaSettings.normalizeBaseUrl(settings.llmBaseUrl)}chat/completions"
        val reqBuilder = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
        val apiKey = settings.llmApiKey.trim()
        if (apiKey.isNotEmpty()) {
            reqBuilder.header("Authorization", "Bearer $apiKey")
        }

        val t0 = System.currentTimeMillis()
        okHttp.newCall(reqBuilder.build()).execute().use { response ->
            val netMs = System.currentTimeMillis() - t0
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    JSONObject(bodyStr).optJSONObject("error")?.optString("message")
                        ?.takeIf { it.isNotBlank() }
                }.getOrNull() ?: bodyStr.take(300)
                throw IOException("HTTP ${response.code}: $detail")
            }

            val root = JSONObject(bodyStr)
            val choices = root.optJSONArray("choices")
            val msgObj = choices?.optJSONObject(0)?.optJSONObject("message")
            val raw = extractMessageText(msgObj)
            parseLlmReply(raw, netMs)
        }
    }

    /** Stage 1 (Text Chat): Uses OpenAI Kotlin SDK /chat/completions */
    suspend fun generateReplyFromText(
        userText: String,
        history: List<ConversationTurn>,
        settings: SabaSettings,
    ): LlmReply {
        val client = buildClient(settings.llmBaseUrl, settings.llmApiKey)
        try {
            val messages = mutableListOf<ChatMessage>()
            if (settings.systemPrompt.isNotBlank()) {
                messages.add(ChatMessage(role = ChatRole.System, content = settings.systemPrompt))
            }
            val recent = history.takeLast(settings.maxHistoryMessages.coerceAtLeast(0))
            for (turn in recent) {
                val role = if (turn.role == "user") ChatRole.User else ChatRole.Assistant
                messages.add(ChatMessage(role = role, content = turn.text))
            }
            messages.add(ChatMessage(role = ChatRole.User, content = userText))

            val request = ChatCompletionRequest(
                model = ModelId(settings.llmModel.ifBlank { "gemini-2.5-flash" }),
                messages = messages,
            )
            val t0 = System.currentTimeMillis()
            val completion = client.chatCompletion(request)
            val netMs = System.currentTimeMillis() - t0
            val raw = completion.choices.firstOrNull()?.message?.content.orEmpty().trim()
            return parseLlmReply(raw, netMs)
        } finally {
            client.close()
        }
    }

    /** Stage 2: Text-to-Speech via OpenAI Kotlin SDK /audio/speech -> 16kHz 16-bit mono PCM */
    suspend fun synthesizeToPcm16k(text: String, settings: SabaSettings): TtsResult {
        val client = buildClient(settings.ttsBaseUrl, settings.ttsApiKey)
        val fmt = settings.ttsResponseFormat.trim().lowercase().ifEmpty { "mp3" }
        try {
            val request = SpeechRequest(
                model = ModelId(settings.ttsModel.ifBlank { "tts-1" }),
                input = text,
                voice = Voice(settings.ttsVoice.ifBlank { "alloy" }),
                responseFormat = SpeechResponseFormat(fmt),
            )
            val t0 = System.currentTimeMillis()
            val audioBytes = client.speech(request)
            val netMs = System.currentTimeMillis() - t0

            val tDecode0 = System.currentTimeMillis()
            val pcm16k = AudioUtils.decodeAudioToPcm16kMono(context, audioBytes, fmt)
            val decodeMs = System.currentTimeMillis() - tDecode0

            return TtsResult(
                pcm16k = pcm16k,
                networkMs = netMs,
                decodeMs = decodeMs,
                rawBytes = audioBytes.size,
            )
        } finally {
            client.close()
        }
    }

    /**
     * Splits an LLM reply into speakable sentences for pipelined TTS playback,
     * merging very short fragments so we don't make redundant tiny TTS calls.
     */
    fun splitSentencesForTts(text: String): List<String> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        val rawParts = clean.split(Regex("(?<=[.!؟?؛\\n])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (rawParts.size <= 1) return listOf(clean)

        val merged = mutableListOf<String>()
        var current = ""
        for (part in rawParts) {
            if (current.isEmpty()) {
                current = part
            } else if (current.length < 24) {
                current = "$current $part"
            } else {
                merged.add(current)
                current = part
            }
        }
        if (current.isNotEmpty()) merged.add(current)
        return merged
    }

    private fun extractMessageText(msgObj: JSONObject?): String {
        if (msgObj == null) return ""
        val contentVal = msgObj.opt("content")
        return when (contentVal) {
            is String -> contentVal.trim()
            is JSONArray -> {
                buildString {
                    for (i in 0 until contentVal.length()) {
                        val part = contentVal.optJSONObject(i)
                        if (part != null && part.optString("type") == "text") {
                            append(part.optString("text"))
                        }
                    }
                }.trim()
            }
            else -> ""
        }
    }

    private fun parseLlmReply(raw: String, networkMs: Long): LlmReply {
        val withoutThink = THINK_TAG_REGEX.replace(raw, "").trim()
        val match = FACE_TAG_REGEX.find(withoutThink)
        val emotion = match?.groupValues?.getOrNull(1)?.lowercase()
        val spoken = FACE_TAG_REGEX.replace(withoutThink, "").trim()
        return LlmReply(
            emotion = emotion,
            spokenText = spoken.ifEmpty { withoutThink },
            rawText = raw,
            networkMs = networkMs,
        )
    }
}
