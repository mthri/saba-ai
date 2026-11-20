package ai.saba.app.ui

import ai.saba.app.ai.AssistantStage
import ai.saba.app.ai.SabaUiState
import ai.saba.app.ai.SabaVoiceCoordinator
import ai.saba.app.config.AudioSourceMode
import ai.saba.app.config.SabaSettings
import ai.saba.app.protocol.RobotConnectionState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssistantTab(
    uiState: SabaUiState,
    robotState: RobotConnectionState,
    settings: SabaSettings,
    coordinator: SabaVoiceCoordinator,
    onRequestPermission: () -> Unit,
) {
    var chatInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. Server & Robot Connection Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(11.dp)
                                .clip(CircleShape)
                                .background(
                                    if (robotState.connected) Color(0xFF10B981) else Color(0xFFF59E0B)
                                )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (robotState.connected) {
                                "ربات متصل: ${robotState.deviceId.ifEmpty { robotState.clientIp }}"
                            } else {
                                "در انتظار اتصال ربات (ESP32)..."
                            },
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    TextButton(onClick = { coordinator.refreshLocalIp() }) {
                        Text("بروزرسانی IP", fontSize = 12.sp)
                    }
                }

                Surface(
                    color = Color(0xFF0D1117),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFF30363D)),
                ) {
                    Text(
                        text = "ws://${uiState.localIpAddress}:${robotState.port}/ws/robot",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        color = Color(0xFF60A5FA),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }

                Text(
                    text = "ورودی صدای فعال: ${uiState.activeAudioInput}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF9BA3B4),
                )

                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AudioSourceMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.audioSourceMode == mode,
                            onClick = {
                                coordinator.applySettings(settings.copy(audioSourceMode = mode))
                            },
                            label = {
                                Text(
                                    text = when (mode) {
                                        AudioSourceMode.AUTO_PREFER_ROBOT -> "خودکار (ربات/گوشی)"
                                        AudioSourceMode.ROBOT_ONLY -> "فقط ربات"
                                        AudioSourceMode.PHONE_MIC_TEST -> "میکروفون گوشی"
                                    },
                                    fontSize = 11.5.sp,
                                )
                            },
                        )
                    }
                }

                if (!uiState.micPermissionGranted) {
                    Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
                        Text("دادن مجوز میکروفون گوشی")
                    }
                }
            }
        }

        // 2. Live Assistant Pipeline State + Silero VAD + KWS Card
        val stageColor = when (uiState.stage) {
            AssistantStage.STANDBY -> Color(0xFF60A5FA)
            AssistantStage.RECORDING_COMMAND -> Color(0xFF10B981)
            AssistantStage.PROCESSING_LLM,
            AssistantStage.PROCESSING_TTS -> Color(0xFFF59E0B)
            AssistantStage.SPEAKING -> Color(0xFFA78BFA)
            AssistantStage.WALKIE_TALKIE -> Color(0xFFEC4899)
            AssistantStage.ERROR -> Color(0xFFEF4444)
            AssistantStage.INITIALIZING -> Color(0xFF9CA3AF)
        }
        val stageBg by animateColorAsState(
            targetValue = stageColor.copy(alpha = 0.14f),
            label = "stageBg",
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = stageBg),
            border = BorderStroke(1.5.dp, stageColor),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = uiState.stage.labelFa,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = stageColor,
                        )
                        Text(
                            text = uiState.statusDetail,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFE5E7EB),
                        )
                    }
                    Surface(
                        color = Color(0xFF0D1117),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            text = "صبا: ${uiState.wakeWordCount}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = Color(0xFFFBBF24),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }

                // VAD + RMS row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    if (uiState.isSpeechDetected) Color(0xFF10B981) else Color(0xFF4B5563)
                                )
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (uiState.isSpeechDetected) "VAD: صحبت تشخیص داده شد" else "VAD: سکوت",
                            fontSize = 12.sp,
                            color = if (uiState.isSpeechDetected) Color(0xFF34D399) else Color(0xFF9BA3B4),
                        )
                    }
                    Text(
                        text = "RMS ${(uiState.rmsLevel * 100).toInt()}%",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFF9BA3B4),
                    )
                }

                LinearProgressIndicator(
                    progress = { (uiState.rmsLevel * 4f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = stageColor,
                    trackColor = Color(0xFF1F2937),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { coordinator.triggerManualWake() },
                        enabled = uiState.stage == AssistantStage.STANDBY,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("بیدارباش دستی")
                    }
                    OutlinedButton(
                        onClick = { coordinator.abortCurrentAction() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF87171)),
                        border = BorderStroke(1.dp, Color(0xFFEF4444)),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("توقف / Abort")
                    }
                }

                if (uiState.errorMessage != null) {
                    Surface(
                        color = Color(0xFF3B1219),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFFEF4444)),
                    ) {
                        Text(
                            text = uiState.errorMessage,
                            color = Color(0xFFFCA5A5),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                        )
                    }
                }
            }
        }

        // 3. Conversation History & Quick Text Input
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "گفتگو با صبا (${uiState.history.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    TextButton(onClick = { coordinator.clearHistory() }) {
                        Text("پاک کردن حافظه", fontSize = 12.sp)
                    }
                }

                if (uiState.history.isEmpty()) {
                    Text(
                        text = "هنوز گفتگویی ثبت نشده است. بگویید «صبا» یا زیر پیام بفرستید.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8B949E),
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        uiState.history.takeLast(8).forEach { turn ->
                            val isUser = turn.role == "user"
                            Surface(
                                color = if (isUser) Color(0xFF1E293B) else Color(0xFF1F2937),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (isUser) Color(0xFF3B82F6) else Color(0xFF10B981),
                                ),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                ) {
                                    Text(
                                        text = if (isUser) {
                                            "شما • ${turn.timestamp}"
                                        } else {
                                            "صبا ${turn.emotion?.let { "[$it]" } ?: ""} • ${turn.timestamp}"
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isUser) Color(0xFF93C5FD) else Color(0xFF6EE7B7),
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = turn.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White,
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = chatInput,
                        onValueChange = { chatInput = it },
                        placeholder = { Text("ارسال متن تست به LLM + TTS...", fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            if (chatInput.isNotBlank()) {
                                coordinator.sendTextChat(chatInput)
                                chatInput = ""
                            }
                        },
                    ) {
                        Text("ارسال")
                    }
                }
            }
        }

        // 4. Live Event Log
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "لاگ زنده (${uiState.logs.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFFD1D5DB),
                    )
                    TextButton(onClick = { coordinator.clearLogs() }) {
                        Text("پاک کردن", fontSize = 12.sp)
                    }
                }
                HorizontalDivider(color = Color(0xFF2D333B))
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(uiState.logs, key = { it.id }) { item ->
                        val color = when {
                            item.isError -> Color(0xFFF87171)
                            item.isHighlight -> Color(0xFFFBBF24)
                            else -> Color(0xFFCBD5E1)
                        }
                        Text(
                            text = "[${item.time}] [${item.tag}] ${item.message}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = color,
                        )
                    }
                }
            }
        }
    }
}
