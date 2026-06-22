package com.hermes.voice.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hermes.voice.R
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class VoiceService : Service() {

    companion object {
        const val CHANNEL_ID = "hermes_voice_channel"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("待命中"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Hermes 语音服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "语音助手后台运行通知"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hermes Voice")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .build()
    }
}
