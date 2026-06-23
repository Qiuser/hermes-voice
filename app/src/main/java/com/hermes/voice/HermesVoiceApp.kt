package com.hermes.voice

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class HermesVoiceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 确保 native 库尽早加载
        try {
            System.loadLibrary("sherpa-onnx-jni")
        } catch (e: UnsatisfiedLinkError) {
            // 已加载则忽略
        }
    }
}
