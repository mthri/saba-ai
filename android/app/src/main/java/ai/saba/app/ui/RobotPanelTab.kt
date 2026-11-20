package ai.saba.app.ui

import ai.saba.app.ai.SabaUiState
import ai.saba.app.ai.SabaVoiceCoordinator
import ai.saba.app.protocol.RobotConnectionState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RobotPanelTab(
    uiState: SabaUiState,
    robotState: RobotConnectionState,
    coordinator: SabaVoiceCoordinator,
) {
    var gazeX by remember { mutableFloatStateOf(0f) }
    var gazeY by remember { mutableFloatStateOf(0f) }
    var sayText by remember { mutableStateOf("") }
    var oledTitle by remember { mutableStateOf("صبا") }
    var oledBody by remember { mutableStateOf("سلام رفیق!") }
    var volumeSlider by remember(uiState.robotVolume) { mutableFloatStateOf(uiState.robotVolume) }

    val expressions = listOf(
        "normal" to "عادی",
        "happy" to "خوشحال",
        "surprised" to "متعجب",
        "sleepy" to "خواب‌آلود",
        "thinking" to "در حال فکر",
        "talking" to "در حال حرف زدن",
        "singing" to "آواز خواندن",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!robotState.connected) {
            Surface(
                color = Color(0xFF3B2608),
                border = BorderStroke(1.dp, Color(0xFFF59E0B)),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = "ربات در حال حاضر به ws://${uiState.localIpAddress}:${robotState.port}/ws/robot متصل نیست. به‌محض اتصال ESP32، تمام کنترل‌های زیر فعال می‌شوند.",
                    color = Color(0xFFFDE68A),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                )
            }
        }

        // 1. Walkie-Talkie & Live Robot Mic Listening
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "واکی‌تاکی و شنود صدای ربات",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )

                // Hold-to-Talk surface
                Surface(
                    color = if (uiState.isWalkieTalkieActive) Color(0xFFBE185D) else Color(0xFF1D4ED8),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(
                        2.dp,
                        if (uiState.isWalkieTalkieActive) Color(0xFFF472B6) else Color(0xFF60A5FA),
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .pointerInput(robotState.connected) {
                            detectTapGestures(
                                onPress = {
                                    coordinator.startWalkieTalkie()
                                    try {
                                        tryAwaitRelease()
                                    } finally {
                                        coordinator.stopWalkieTalkie()
                                    }
                                }
                            )
                        },
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = if (uiState.isWalkieTalkieActive) {
                                "در حال ارسال صدا به اسپیکر ربات... (رها کنید تا قطع شود)"
                            } else {
                                "نگه دارید و صحبت کنید (واکی‌تاکی به ربات)"
                            },
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "شنود زنده میکروفون ربات",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "پخش زنده صدای دریافتی از میکروفون ESP32 روی گوشی",
                            color = Color(0xFF9BA3B4),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = uiState.isListeningToRobotMic,
                        onCheckedChange = { coordinator.setListenToRobotMic(it) },
                    )
                }
            }
        }

        // 2. Face Expression & Gaze Control
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "حالت چهره و چشم‌ها",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    OutlinedButton(
                        onClick = {
                            coordinator.sendRobotCommand("چشمک (face.blink)") {
                                coordinator.rdpServer.blink()
                            }
                        },
                    ) {
                        Text("چشمک (Blink)", fontSize = 12.sp)
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    expressions.forEach { (id, label) ->
                        FilterChip(
                            selected = robotState.activeExpression == id,
                            onClick = {
                                coordinator.sendRobotCommand("چهره: $id") {
                                    coordinator.rdpServer.setExpression(id, 0)
                                }
                            },
                            label = { Text("$label ($id)", fontSize = 12.sp) },
                        )
                    }
                }

                Spacer(Modifier.height(2.dp))
                Text(
                    text = "جهت نگاه چشم (Gaze): X=${String.format(Locale.US, "%.2f", gazeX)}, Y=${String.format(Locale.US, "%.2f", gazeY)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF9BA3B4),
                )
                Slider(
                    value = gazeX,
                    onValueChange = { gazeX = it },
                    onValueChangeFinished = {
                        coordinator.sendRobotCommand("Gaze ($gazeX, $gazeY)") {
                            coordinator.rdpServer.setGaze(gazeX, gazeY, 3000)
                        }
                    },
                    valueRange = -1f..1f,
                )
                Slider(
                    value = gazeY,
                    onValueChange = { gazeY = it },
                    onValueChangeFinished = {
                        coordinator.sendRobotCommand("Gaze ($gazeX, $gazeY)") {
                            coordinator.rdpServer.setGaze(gazeX, gazeY, 3000)
                        }
                    },
                    valueRange = -1f..1f,
                )
                OutlinedButton(
                    onClick = {
                        gazeX = 0f
                        gazeY = 0f
                        coordinator.sendRobotCommand("مرکز نگاه (0, 0)") {
                            coordinator.rdpServer.setGaze(0f, 0f, 1500)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("برگرداندن نگاه به مرکز")
                }
            }
        }

        // 3. Speaker, Volume, Tone, Melody & TTS Say
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "اسپیکر و پخش صدا روی ربات",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )

                Text(
                    text = "ولوم اسپیکر ربات: ${(volumeSlider * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF9BA3B4),
                )
                Slider(
                    value = volumeSlider,
                    onValueChange = { volumeSlider = it },
                    onValueChangeFinished = { coordinator.setRobotVolume(volumeSlider) },
                    valueRange = 0f..1f,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            coordinator.sendRobotCommand("پخش بوق روی ربات") {
                                coordinator.rdpServer.playTone(1046.5f, 180, 0.35f)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("بوق تست", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { coordinator.playTestMelody() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("ملودی", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { coordinator.abortCurrentAction() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF87171)),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("قطع صدا", fontSize = 12.sp)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = sayText,
                        onValueChange = { sayText = it },
                        placeholder = { Text("متنی که ربات با TTS بخواند...", fontSize = 12.5.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            if (sayText.isNotBlank()) {
                                coordinator.speakCustomText(sayText)
                                sayText = ""
                            }
                        },
                    ) {
                        Text("بگو")
                    }
                }
            }
        }

        // 4. OLED Display Control (Persian Bitmap & English Text)
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
                    text = "نمایشگر OLED ربات (پشتیبانی فارسی و انگلیسی)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                OutlinedTextField(
                    value = oledTitle,
                    onValueChange = { oledTitle = it },
                    label = { Text("عنوان") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = oledBody,
                    onValueChange = { oledBody = it },
                    label = { Text("متن نمایشی") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            coordinator.sendRobotCommand("نمایش متن روی OLED") {
                                coordinator.rdpServer.showText(oledTitle, oledBody, fontSize = 1, timeoutMs = 6000)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("نمایش روی OLED")
                    }
                    OutlinedButton(
                        onClick = {
                            coordinator.sendRobotCommand("پاک کردن نمایشگر") {
                                coordinator.rdpServer.clearDisplay()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("پاک کردن")
                    }
                }
            }
        }

        // 5. Telemetry & System Controls
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
                    text = "تله‌متری و سیستم ESP32",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                val tel = robotState.telemetry
                Text(
                    text = "FW: ${robotState.firmware.ifEmpty { "—" }} | Heap: ${tel.freeHeap} B | RSSI: ${tel.wifiRssi} dBm\n" +
                        "SpkBuf: ${tel.spkBufferedBytes} B | Underruns: ${tel.spkUnderruns} | MicDrops: ${tel.micDrops}\n" +
                        "MicLvl: ${String.format(Locale.US, "%.2f", tel.micLevel)} | SpkLvl: ${String.format(Locale.US, "%.2f", tel.spkLevel)} | RX: ${robotState.rxAudioBytes / 1024} KB",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    color = Color(0xFFCBD5E1),
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            coordinator.sendRobotCommand("Ping") {
                                val res = coordinator.rdpServer.ping()
                                coordinator.log("PING", res.toString())
                            }
                        },
                    ) { Text("Ping", fontSize = 12.sp) }

                    OutlinedButton(
                        onClick = {
                            coordinator.sendRobotCommand("Capabilities") {
                                val res = coordinator.rdpServer.getCapabilities()
                                coordinator.log("CAPS", res.toString())
                            }
                        },
                    ) { Text("Capabilities", fontSize = 12.sp) }

                    OutlinedButton(
                        onClick = {
                            val next = !robotState.micStreamEnabled
                            coordinator.sendRobotCommand("میکروفون ربات: $next") {
                                coordinator.rdpServer.setMicCapture(next)
                            }
                        },
                    ) {
                        Text(
                            if (robotState.micStreamEnabled) "قطع میکروفون ربات" else "وصل میکروفون ربات",
                            fontSize = 12.sp,
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            coordinator.sendRobotCommand("Reboot ESP32") {
                                coordinator.rdpServer.reboot(500)
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF87171)),
                    ) { Text("Reboot ربات", fontSize = 12.sp) }
                }
            }
        }
    }
}
