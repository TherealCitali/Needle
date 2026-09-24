package dev.citali.needle.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.citali.needle.MainActivity
import dev.citali.needle.engine.NeedlePrefs

/**
 * Keeps the Telegram listener alive while the app is in the background.
 *
 * The assistant itself never needs a background service: inference only happens
 * when something asks for it. This exists purely so a message can arrive while
 * the phone is idle, and it stops the moment the user turns remote control off.
 */
class NeedleRemoteService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = intent?.getStringExtra(EXTRA_TOKEN)?.takeIf { it.isNotBlank() }
            ?: NeedlePrefs.telegramToken(this)
        startAsForeground()
        if (token.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!TelegramBridge.isRunning()) {
            TelegramBridge.start(this, token)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        TelegramBridge.stop()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Needle remote control",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Listens for Telegram commands" }
            manager?.createNotificationChannel(channel)
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Needle is listening")
            .setContentText("Telegram commands run on the on-device model")
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                },
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "needle-remote"
        private const val NOTIFICATION_ID = 4211
        const val EXTRA_TOKEN = "token"

        fun start(context: Context, token: String) {
            val intent = Intent(context, NeedleRemoteService::class.java).putExtra(EXTRA_TOKEN, token)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NeedleRemoteService::class.java))
        }
    }
}
