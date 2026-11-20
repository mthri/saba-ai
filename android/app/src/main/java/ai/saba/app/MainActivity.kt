package ai.saba.app

import ai.saba.app.ai.SabaVoiceCoordinator
import ai.saba.app.service.SabaAppController
import ai.saba.app.service.SabaHostService
import ai.saba.app.ui.AssistantTab
import ai.saba.app.ui.RobotPanelTab
import ai.saba.app.ui.SettingsTab
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var coordinator: SabaVoiceCoordinator

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val micGranted = results[Manifest.permission.RECORD_AUDIO] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            coordinator.onMicPermissionChanged(micGranted)
            if (micGranted) {
                SabaHostService.start(this)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()

        coordinator = SabaAppController.getOrCreate(applicationContext)
        checkAndRequestPermissions()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    SabaMainScreen(
                        coordinator = coordinator,
                        onRequestPermission = ::checkAndRequestPermissions,
                    )
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (needed.isEmpty()) {
            coordinator.onMicPermissionChanged(true)
            SabaHostService.start(this)
        } else {
            requestPermissionsLauncher.launch(needed.toTypedArray())
        }
    }
}

@Composable
private fun SabaMainScreen(
    coordinator: SabaVoiceCoordinator,
    onRequestPermission: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val uiState by coordinator.uiState.collectAsState()
    val robotState by coordinator.robotState.collectAsState()
    val settings by coordinator.settingsStore.settings.collectAsState()

    val tabs = listOf(
        "دستیار صوتی",
        "پنل ربات",
        "تنظیمات AI",
    )

    Scaffold(
        containerColor = Color(0xFF0E1117),
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF161B22)) {
                tabs.forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = {
                            Text(
                                text = when (index) {
                                    0 -> "🎙️"
                                    1 -> "🤖"
                                    else -> "⚙️"
                                }
                            )
                        },
                        label = {
                            Text(
                                text = title,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (selectedTab) {
                0 -> AssistantTab(
                    uiState = uiState,
                    robotState = robotState,
                    settings = settings,
                    coordinator = coordinator,
                    onRequestPermission = onRequestPermission,
                )
                1 -> RobotPanelTab(
                    uiState = uiState,
                    robotState = robotState,
                    coordinator = coordinator,
                )
                else -> SettingsTab(
                    settings = settings,
                    coordinator = coordinator,
                )
            }
        }
    }
}
