package ai.saba.app.service

import ai.saba.app.MainActivity
import ai.saba.app.ai.SabaVoiceCoordinator
import ai.saba.app.config.SabaSettingsStore
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

object SabaAppController {
    @Volatile
    private var coordinatorInstance: SabaVoiceCoordinator? = null

    fun getOrCreate(context: Context): SabaVoiceCoordinator {
        return coordinatorInstance ?: synchronized(this) {
            coordinatorInstance ?: run {
                val appCtx = context.applicationContext
                val store = SabaSettingsStore(appCtx)
                SabaVoiceCoordinator(appCtx, store).also {
                    it.startAll()
                    coordinatorInstance = it
                }
            }
        }
    }

    fun shutdown() {
        synchronized(this) {
            coordinatorInstance?.shutdown()
            coordinatorInstance = null
        }
    }
}

class SabaHostService : Service() {
    companion object {
        private const val CHANNEL_ID = "saba_host_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "ai.saba.app.action.STOP_HOST"

        fun start(context: Context) {
            val intent = Intent(context, SabaHostService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                // Fallback safely if foreground service cannot start before permissions
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SabaAppController.shutdown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val coordinator = SabaAppController.getOrCreate(applicationContext)
        val ip = coordinator.uiState.value.localIpAddress
        val port = coordinator.settingsStore.current.wsPort

        try {
            startForeground(NOTIFICATION_ID, buildNotification("ws://$ip:$port/ws/robot"))
        } catch (_: SecurityException) {
            // If RECORD_AUDIO wasn't granted yet on API 34+, foreground microphone service may throw
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Saba AI Robot Host",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "سرور WebSocket و پایپ‌لاین صوتی ربات صبا"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(endpoint: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SabaHostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Saba AI Host فعال است")
            .setContentText("سرور ربات: $endpoint")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(null, "توقف سرور", stopIntent).build()
            )
            .build()
    }
}
