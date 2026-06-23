package com.hermes.voice.network

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApiConfig @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "hermes_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var wsUrl: String
        get() = prefs.getString(KEY_WS_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WS_URL, value).apply()

    var voiceToken: String
        get() = prefs.getString(KEY_VOICE_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_VOICE_TOKEN, value).apply()

    var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE_WORD, false)
        set(value) = prefs.edit().putBoolean(KEY_WAKE_WORD, value).apply()

    var autoContinueEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CONTINUE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CONTINUE, value).apply()

    val isConfigured: Boolean
        get() = wsUrl.isNotBlank() && voiceToken.isNotBlank() && deviceId.isNotBlank()

    companion object {
        private const val KEY_WS_URL = "ws_url"
        private const val KEY_VOICE_TOKEN = "voice_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_WAKE_WORD = "wake_word_enabled"
        private const val KEY_AUTO_CONTINUE = "auto_continue_enabled"
    }
}
