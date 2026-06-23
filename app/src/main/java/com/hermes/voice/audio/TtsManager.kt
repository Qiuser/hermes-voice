package com.hermes.voice.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TtsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "VoiceTTS"
        private const val TTS_MODEL_DIR = "sherpa-onnx-tts"
        private const val SAMPLE_RATE = 16000
        private const val SPEAKER_ID = 0
    }

    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private var isReady = false
    private var speakJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val _events = MutableSharedFlow<TtsEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<TtsEvent> = _events

    // 收集完整回复文本，end 后一次性合成
    private val textBuffer = StringBuilder()

    fun init() {
        try {
            val dataDir = extractDataDir("$TTS_MODEL_DIR/espeak-ng-data", "espeak-ng-data", "matcha-zh-en-v1")

            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    matcha = OfflineTtsMatchaModelConfig(
                        acousticModel = "$TTS_MODEL_DIR/model-steps-3.onnx",
                        vocoder = "$TTS_MODEL_DIR/vocos.onnx",
                        lexicon = "$TTS_MODEL_DIR/lexicon.txt",
                        tokens = "$TTS_MODEL_DIR/tokens.txt",
                        dataDir = dataDir,
                        noiseScale = 0.3f,
                        lengthScale = 1.0f,
                    ),
                    numThreads = 2,
                    debug = false,
                ),
                ruleFsts = "$TTS_MODEL_DIR/phone-zh.fst,$TTS_MODEL_DIR/date-zh.fst,$TTS_MODEL_DIR/number-zh.fst",
                maxNumSentences = 1,
            )
            tts = OfflineTts(assetManager = context.assets, config = config)
            isReady = true
            Log.d(TAG, "Sherpa-ONNX TTS initialized, sampleRate=${tts?.sampleRate()}, numSpeakers=${tts?.numSpeakers()}")
            _events.tryEmit(TtsEvent.Initialized)
        } catch (e: Exception) {
            Log.e(TAG, "TTS init failed", e)
            _events.tryEmit(TtsEvent.Error("TTS 初始化失败: ${e.message}"))
        }
    }

    /**
     * 流式接收 token，只缓存不合成
     */
    fun feedToken(token: String) {
        if (!isReady) return
        textBuffer.append(token)
    }

    /**
     * 流结束，把完整文本一次性送 TTS 合成播放
     */
    fun finishStream() {
        if (!isReady) return
        val text = textBuffer.toString().trim()
        textBuffer.clear()
        if (text.isBlank()) return

        speakJob?.cancel()
        speakJob = scope.launch {
            _events.tryEmit(TtsEvent.SpeakStart)
            speakText(text)
            _events.tryEmit(TtsEvent.AllDone)
        }
    }

    /**
     * 立即播报一段文字（中断当前播放）
     */
    fun speakImmediate(text: String) {
        if (!isReady) return
        stop()
        speakJob = scope.launch {
            _events.tryEmit(TtsEvent.SpeakStart)
            speakText(text)
            _events.tryEmit(TtsEvent.AllDone)
        }
    }

    private fun speakText(text: String) {
        val engine = tts ?: return
        Log.d(TAG, "Speaking: '$text'")

        val audio = engine.generate(text = text, sid = SPEAKER_ID, speed = 1.0f)
        if (audio.samples.isEmpty()) return

        ensureAudioTrack(audio.sampleRate)

        // 在音频前插入 50ms 极低音量噪声，防止 DAC gate 导致淡入
        val warmupSamples = audio.sampleRate / 20 // 50ms
        val warmup = ShortArray(warmupSamples) { 1 } // 极低振幅，人耳不可闻
        audioTrack?.write(warmup, 0, warmup.size)

        // 转 PCM 16-bit 直接播放
        val pcm = ShortArray(audio.samples.size) {
            (audio.samples[it] * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }

        audioTrack?.write(pcm, 0, pcm.size)
    }

    fun stop() {
        speakJob?.cancel()
        speakJob = null
        textBuffer.clear()
        // 不释放 AudioTrack，保持常驻避免淡入
    }

    private fun ensureAudioTrack(sampleRate: Int) {
        if (audioTrack != null) return

        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
    }

    private fun releaseAudioTrack() {
        try {
            audioTrack?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack stop error: ${e.message}")
        }
        try {
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack release error: ${e.message}")
        }
        audioTrack = null
    }

    fun destroy() {
        stop()
        releaseAudioTrack()
        tts?.release()
        tts = null
        isReady = false
    }

    private fun extractDataDir(assetPath: String, dirName: String, version: String): String {
        val targetDir = java.io.File(context.filesDir, dirName)
        val versionFile = java.io.File(targetDir, ".version")

        if (targetDir.exists() && versionFile.exists() && versionFile.readText() == version) {
            return targetDir.absolutePath
        }

        Log.d(TAG, "Extracting $assetPath to ${targetDir.absolutePath}")
        targetDir.deleteRecursively()
        targetDir.mkdirs()
        copyAssetDir(assetPath, targetDir)
        versionFile.writeText(version)
        Log.d(TAG, "$dirName extracted")
        return targetDir.absolutePath
    }

    private fun copyAssetDir(assetPath: String, targetDir: java.io.File) {
        val assets = context.assets
        val list = assets.list(assetPath) ?: return

        if (list.isEmpty()) {
            assets.open(assetPath).use { input ->
                java.io.File(targetDir.parent!!, targetDir.name).outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            targetDir.mkdirs()
            for (item in list) {
                val childAssetPath = "$assetPath/$item"
                val childTarget = java.io.File(targetDir, item)
                val childList = assets.list(childAssetPath)
                if (childList != null && childList.isNotEmpty()) {
                    copyAssetDir(childAssetPath, childTarget)
                } else {
                    assets.open(childAssetPath).use { input ->
                        childTarget.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }
}

sealed class TtsEvent {
    data object Initialized : TtsEvent()
    data object SpeakStart : TtsEvent()
    data object AllDone : TtsEvent()
    data class Error(val message: String) : TtsEvent()
}
