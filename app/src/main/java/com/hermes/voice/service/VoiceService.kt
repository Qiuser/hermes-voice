package com.hermes.voice.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import com.hermes.voice.MainActivity
import com.hermes.voice.R
import com.hermes.voice.network.ConnectionManager
import com.hermes.voice.session.SessionState
import com.hermes.voice.session.VoiceSessionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class VoiceService : Service() {

    companion object {
        private const val TAG = "VoiceService"
        const val CHANNEL_ID = "hermes_voice_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_TOGGLE_SESSION = "com.hermes.voice.TOGGLE_SESSION"
    }

    @Inject lateinit var connectionManager: ConnectionManager
    @Inject lateinit var voiceSessionManager: VoiceSessionManager

    private var mediaSession: MediaSessionCompat? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var soundPool: SoundPool? = null
    private var soundStart: Int = 0
    private var soundEnd: Int = 0

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("待命中"))
        setupMediaSession()
        setupSoundPool()
        connectionManager.start()
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_SESSION -> toggleSession()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        mediaSession?.release()
        soundPool?.release()
        connectionManager.stop()
        voiceSessionManager.destroy()
        super.onDestroy()
    }

    private fun setupSoundPool() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(attrs)
            .build()
        soundStart = soundPool!!.load(this, R.raw.tone_start, 1)
        soundEnd = soundPool!!.load(this, R.raw.tone_end, 1)
    }

    private fun playStartTone() {
        soundPool?.play(soundStart, 1.0f, 1.0f, 1, 0, 1.0f)
    }

    private fun playEndTone() {
        soundPool?.play(soundEnd, 1.0f, 1.0f, 1, 0, 1.0f)
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "HermesVoice").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    val event = mediaButtonEvent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                        ?: return super.onMediaButtonEvent(mediaButtonEvent)

                    if (event.action != KeyEvent.ACTION_DOWN) return true

                    Log.d(TAG, "Media button: keyCode=${event.keyCode}")

                    when (event.keyCode) {
                        KeyEvent.KEYCODE_HEADSETHOOK,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_PLAY -> {
                            toggleSession()
                            return true
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            })

            // 设为 active 才能接收蓝牙按键
            isActive = true

            // 设置 PlaybackState 让系统知道我们在"播放"（接收按键必需）
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1.0f)
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE
                    )
                    .build()
            )
        }
    }

    private fun toggleSession() {
        val currentState = voiceSessionManager.state.value
        if (currentState == SessionState.IDLE) {
            Log.d(TAG, "Starting voice session")
            playStartTone()
            voiceSessionManager.startSession()
        } else {
            Log.d(TAG, "Stopping voice session")
            playEndTone()
            voiceSessionManager.stopSession()
        }
    }

    private fun observeState() {
        serviceScope.launch {
            voiceSessionManager.state.collect { state ->
                val statusText = state.displayName
                updateNotification(statusText)
            }
        }
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }

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
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = PendingIntent.getService(
            this, 1,
            Intent(this, VoiceService::class.java).apply { action = ACTION_TOGGLE_SESSION },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hermes Voice")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_mic, "语音对话", toggleIntent)
            .build()
    }
}
