package com.hermes.voice.di

import android.content.Context
import com.hermes.voice.api.ApiConfig
import com.hermes.voice.api.HermesApiClient
import com.hermes.voice.audio.AudioFocusManager
import com.hermes.voice.audio.SpeechRecognizerManager
import com.hermes.voice.audio.TtsManager
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
    fun provideHermesApiClient(apiConfig: ApiConfig): HermesApiClient {
        return HermesApiClient(apiConfig)
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
    fun provideVoiceSessionManager(
        sttManager: SpeechRecognizerManager,
        ttsManager: TtsManager,
        apiClient: HermesApiClient,
        audioFocusManager: AudioFocusManager
    ): VoiceSessionManager {
        return VoiceSessionManager(sttManager, ttsManager, apiClient, audioFocusManager)
    }
}
