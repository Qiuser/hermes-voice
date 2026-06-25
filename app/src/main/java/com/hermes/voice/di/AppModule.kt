package com.hermes.voice.di

import android.content.Context
import com.hermes.voice.audio.AudioFocusManager
import com.hermes.voice.audio.SpeechRecognizerManager
import com.hermes.voice.audio.TtsManager
import com.hermes.voice.audio.WakeWordDetector
import com.hermes.voice.data.AppDatabase
import com.hermes.voice.data.MessageDao
import com.hermes.voice.network.ApiConfig
import com.hermes.voice.network.ConnectionManager
import com.hermes.voice.network.VoiceWebSocketClient
import com.hermes.voice.session.VoiceSessionManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideApiConfig(@ApplicationContext context: Context): ApiConfig {
        return ApiConfig(context)
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideMessageDao(database: AppDatabase): MessageDao {
        return database.messageDao()
    }

    @Provides
    @Singleton
    fun provideVoiceWebSocketClient(apiConfig: ApiConfig): VoiceWebSocketClient {
        return VoiceWebSocketClient(apiConfig)
    }

    @Provides
    @Singleton
    fun provideConnectionManager(wsClient: VoiceWebSocketClient, apiConfig: ApiConfig): ConnectionManager {
        return ConnectionManager(wsClient, apiConfig)
    }

    @Provides
    @Singleton
    fun provideSpeechRecognizerManager(@ApplicationContext context: Context): SpeechRecognizerManager {
        return SpeechRecognizerManager(context)
    }

    @Provides
    @Singleton
    fun provideTtsManager(@ApplicationContext context: Context): TtsManager {
        return TtsManager(context)
    }

    @Provides
    @Singleton
    fun provideAudioFocusManager(@ApplicationContext context: Context): AudioFocusManager {
        return AudioFocusManager(context)
    }

    @Provides
    @Singleton
    fun provideWakeWordDetector(@ApplicationContext context: Context): WakeWordDetector {
        return WakeWordDetector(context)
    }

    @Provides
    @Singleton
    fun provideVoiceSessionManager(
        sttManager: SpeechRecognizerManager,
        ttsManager: TtsManager,
        wsClient: VoiceWebSocketClient,
        audioFocusManager: AudioFocusManager,
        apiConfig: ApiConfig
    ): VoiceSessionManager {
        return VoiceSessionManager(sttManager, ttsManager, wsClient, audioFocusManager, apiConfig)
    }
}
