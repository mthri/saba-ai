package ai.saba.app.ui

import ai.saba.app.ai.SabaVoiceCoordinator
import ai.saba.app.config.SabaSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsTab(
    settings: SabaSettings,
    coordinator: SabaVoiceCoordinator,
) {
    var llmBaseUrl by remember(settings) { mutableStateOf(settings.llmBaseUrl) }
    var llmApiKey by remember(settings) { mutableStateOf(settings.llmApiKey) }
    var llmModel by remember(settings) { mutableStateOf(settings.llmModel) }
    var systemPrompt by remember(settings) { mutableStateOf(settings.systemPrompt) }

    var ttsBaseUrl by remember(settings) { mutableStateOf(settings.ttsBaseUrl) }
    var ttsApiKey by remember(settings) { mutableStateOf(settings.ttsApiKey) }
    var ttsModel by remember(settings) { mutableStateOf(settings.ttsModel) }
    var ttsVoice by remember(settings) { mutableStateOf(settings.ttsVoice) }
    var ttsFormat by remember(settings) { mutableStateOf(settings.ttsResponseFormat) }

    var vadEndSilenceMs by remember(settings) { mutableStateOf(settings.vadEndSilenceMs.toString()) }
    var vadThreshold by remember(settings) { mutableStateOf(settings.vadThreshold.toString()) }
    var kwsScore by remember(settings) { mutableStateOf(settings.kwsScore.toString()) }
    var kwsThreshold by remember(settings) { mutableStateOf(settings.kwsThreshold.toString()) }

    var showKeys by remember { mutableStateOf(false) }
    var savedBanner by remember { mutableStateOf<String?>(null) }

    val keyVisual = if (showKeys) VisualTransformation.None else PasswordVisualTransformation()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "تنظیمات مدل‌های هوش مصنوعی",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            OutlinedButton(onClick = { showKeys = !showKeys }) {
                Text(if (showKeys) "مخفی کردن کلیدها" else "نمایش کلیدها", fontSize = 11.5.sp)
            }
        }

        // 1. Direct Audio-to-LLM Settings
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "۱. مدل هوشمند صوتی و گفتگو (Direct Audio-to-LLM)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF60A5FA),
                )
                Text(
                    text = "صوت ضبط‌شده کاربر مستقیم به‌صورت WAV برای این مدل ارسال می‌شود (بدون نیاز به STT جداگانه).",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF9BA3B4),
                )
                OutlinedTextField(
                    value = llmBaseUrl,
                    onValueChange = { llmBaseUrl = it },
                    label = { Text("LLM Base URL") },
                    placeholder = { Text("https://api.avalai.ir/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = llmApiKey,
                    onValueChange = { llmApiKey = it },
                    label = { Text("LLM API Key") },
                    visualTransformation = keyVisual,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = llmModel,
                    onValueChange = { llmModel = it },
                    label = { Text("LLM Model (پشتیبانی از صوت)") },
                    placeholder = { Text("gemini-3.8-flash") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        ttsBaseUrl = llmBaseUrl
                        ttsApiKey = llmApiKey
                        savedBanner = "Base URL و API Key روی TTS کپی شد (دکمه ذخیره را بزنید)"
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("کپی Base URL و API Key برای TTS", fontSize = 12.sp)
                }
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text("System Prompt (شخصیت صبا)") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // 2. TTS Settings
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "۲. تبدیل متن به گفتار (TTS - Audio Speech)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFA78BFA),
                )
                OutlinedTextField(
                    value = ttsBaseUrl,
                    onValueChange = { ttsBaseUrl = it },
                    label = { Text("TTS Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ttsApiKey,
                    onValueChange = { ttsApiKey = it },
                    label = { Text("TTS API Key") },
                    visualTransformation = keyVisual,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = ttsModel,
                        onValueChange = { ttsModel = it },
                        label = { Text("TTS Model") },
                        placeholder = { Text("eleven_flash_v2_5 / gpt-audio-1.5") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = ttsVoice,
                        onValueChange = { ttsVoice = it },
                        label = { Text("Voice") },
                        placeholder = { Text("alloy / nova") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                Text(
                    text = "فرمت خروجی TTS (MP3 سبک‌تر و سریع‌تر برای دانلود است):",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF9BA3B4),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("mp3", "wav", "pcm").forEach { fmt ->
                        FilterChip(
                            selected = ttsFormat == fmt,
                            onClick = { ttsFormat = fmt },
                            label = { Text(fmt.uppercase()) },
                        )
                    }
                }
            }
        }

        // 3. VAD & KWS Tuning
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "تنظیمات حساسیت VAD و Wake-Word (Saba)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFBBF24),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = vadThreshold,
                        onValueChange = { vadThreshold = it },
                        label = { Text("VAD Threshold") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = vadEndSilenceMs,
                        onValueChange = { vadEndSilenceMs = it },
                        label = { Text("سکوت پایان (ms)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = kwsScore,
                        onValueChange = { kwsScore = it },
                        label = { Text("KWS Boost Score") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = kwsThreshold,
                        onValueChange = { kwsThreshold = it },
                        label = { Text("KWS Threshold") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (savedBanner != null) {
            Text(
                text = savedBanner!!,
                color = Color(0xFF34D399),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Button(
            onClick = {
                val updated = settings.copy(
                    llmBaseUrl = llmBaseUrl.trim(),
                    llmApiKey = llmApiKey.trim(),
                    llmModel = llmModel.trim(),
                    systemPrompt = systemPrompt,
                    ttsBaseUrl = ttsBaseUrl.trim(),
                    ttsApiKey = ttsApiKey.trim(),
                    ttsModel = ttsModel.trim(),
                    ttsVoice = ttsVoice.trim(),
                    ttsResponseFormat = ttsFormat,
                    vadThreshold = vadThreshold.toFloatOrNull()?.coerceIn(0.1f, 0.95f) ?: settings.vadThreshold,
                    vadEndSilenceMs = vadEndSilenceMs.toIntOrNull()?.coerceIn(300, 4000) ?: settings.vadEndSilenceMs,
                    kwsScore = kwsScore.toFloatOrNull()?.coerceIn(0.5f, 5.0f) ?: settings.kwsScore,
                    kwsThreshold = kwsThreshold.toFloatOrNull()?.coerceIn(0.05f, 0.9f) ?: settings.kwsThreshold,
                )
                coordinator.applySettings(updated)
                savedBanner = "تنظیمات با موفقیت ذخیره و اعمال شد!"
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
        ) {
            Text("ذخیره و اعمال تنظیمات", fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(12.dp))
    }
}
