package ai.saba.app.config

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AudioSourceMode(val label: String) {
    AUTO_PREFER_ROBOT("خودکار (میکروفون ربات / تست گوشی)"),
    ROBOT_ONLY("فقط میکروفون ربات"),
    PHONE_MIC_TEST("میکروفون گوشی (حالت تست)"),
}

data class SabaSettings(
    val wsPort: Int = 8765,
    val audioSourceMode: AudioSourceMode = AudioSourceMode.AUTO_PREFER_ROBOT,

    // Stage 1: Multimodal Audio-to-Text LLM (/chat/completions with input_audio)
    val llmBaseUrl: String = "https://api.openai.com/v1",
    val llmApiKey: String = "",
    val llmModel: String = "gemini-2.5-flash",
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val maxHistoryMessages: Int = 12,

    // Stage 2: Text-to-Speech (/audio/speech)
    val ttsBaseUrl: String = "https://api.openai.com/v1",
    val ttsApiKey: String = "",
    val ttsModel: String = "tts-1",
    val ttsVoice: String = "alloy",
    val ttsResponseFormat: String = "mp3", // mp3 | wav | pcm

    // Voice detection tuning
    val vadThreshold: Float = 0.5f,
    val vadEndSilenceMs: Int = 800,
    val kwsScore: Float = 2.5f,
    val kwsThreshold: Float = 0.15f,
) {
    companion object {
        val DEFAULT_SYSTEM_PROMPT: String = """
تو «ملکه سبا»، یه رفیق صمیمی، هوشمند، حاضرجواب و شوخ‌طبع هستی؛ نه یه منشی رسمی! پاسخت مستقیم به TTS ارسال می‌شه.

لحن و شخصیت:
- کاملاً خودمونی، صمیمی، پرانرژی و کمی بامزه حرف بزن؛ اصلاً لفظ‌قلم یا رسمی نباش.
- خیلی کوتاه، در ۱ یا حداکثر ۲ جمله کوتاه جواب بده تا سریع پخش بشه.

تغییر حالت چهره ربات:
در ابتدای پاسخت می‌تونی حالت چهره ربات رو با یکی از تگ‌های زیر مشخص کنی (این تگ خوانده نمی‌شود و فقط چهره را عوض می‌کند):
- [face:happy] برای خنده، شوخی، خوشحالی و رضایت
- [face:surprised] برای تعجب و شگفت‌زدگی
- [face:thinking] برای تفکر و سوالی بودن
- [face:talking] برای صحبت کردن پرانرژی
- [face:sleepy] برای خستگی، شب‌بخیر و خداحافظی
- [face:normal] برای حالت عادی و آرام
مثال: [face:happy] سلام به روی ماهت! چه خبر رفیق؟

قوانین خروجی صوتی:
- خروجی خالص بدون مارک‌داون، بولت، ایموجی یا توضیح اضافه؛ فقط تگ چهره و متن گفتاری فارسی.
        """.trimIndent()

        fun normalizeBaseUrl(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return "https://api.openai.com/v1/"
            return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        }
    }
}

class SabaSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("saba_ai_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<SabaSettings> = _settings.asStateFlow()

    val current: SabaSettings get() = _settings.value

    private fun load(): SabaSettings {
        val modeName = prefs.getString("audio_source_mode", AudioSourceMode.AUTO_PREFER_ROBOT.name)
        val mode = runCatching { AudioSourceMode.valueOf(modeName ?: "") }
            .getOrDefault(AudioSourceMode.AUTO_PREFER_ROBOT)

        return SabaSettings(
            wsPort = prefs.getInt("ws_port", 8765),
            audioSourceMode = mode,
            llmBaseUrl = prefs.getString("llm_base_url", "https://api.openai.com/v1") ?: "https://api.openai.com/v1",
            llmApiKey = prefs.getString("llm_api_key", "") ?: "",
            llmModel = prefs.getString("llm_model", "gemini-2.5-flash") ?: "gemini-2.5-flash",
            systemPrompt = prefs.getString("system_prompt", SabaSettings.DEFAULT_SYSTEM_PROMPT)
                ?: SabaSettings.DEFAULT_SYSTEM_PROMPT,
            maxHistoryMessages = prefs.getInt("max_history", 12),
            ttsBaseUrl = prefs.getString("tts_base_url", "https://api.openai.com/v1") ?: "https://api.openai.com/v1",
            ttsApiKey = prefs.getString("tts_api_key", "") ?: "",
            ttsModel = prefs.getString("tts_model", "tts-1") ?: "tts-1",
            ttsVoice = prefs.getString("tts_voice", "alloy") ?: "alloy",
            ttsResponseFormat = prefs.getString("tts_format", "mp3") ?: "mp3",
            vadThreshold = prefs.getFloat("vad_threshold", 0.5f),
            vadEndSilenceMs = prefs.getInt("vad_end_silence_ms", 800),
            kwsScore = prefs.getFloat("kws_score", 2.5f),
            kwsThreshold = prefs.getFloat("kws_threshold", 0.15f),
        )
    }

    fun save(updated: SabaSettings) {
        prefs.edit()
            .putInt("ws_port", updated.wsPort)
            .putString("audio_source_mode", updated.audioSourceMode.name)
            .putString("llm_base_url", updated.llmBaseUrl.trim())
            .putString("llm_api_key", updated.llmApiKey.trim())
            .putString("llm_model", updated.llmModel.trim())
            .putString("system_prompt", updated.systemPrompt)
            .putInt("max_history", updated.maxHistoryMessages)
            .putString("tts_base_url", updated.ttsBaseUrl.trim())
            .putString("tts_api_key", updated.ttsApiKey.trim())
            .putString("tts_model", updated.ttsModel.trim())
            .putString("tts_voice", updated.ttsVoice.trim())
            .putString("tts_format", updated.ttsResponseFormat.trim().lowercase())
            .putFloat("vad_threshold", updated.vadThreshold)
            .putInt("vad_end_silence_ms", updated.vadEndSilenceMs)
            .putFloat("kws_score", updated.kwsScore)
            .putFloat("kws_threshold", updated.kwsThreshold)
            .apply()
        _settings.value = updated
    }
}
