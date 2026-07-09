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
import kotlinx.coroutines.isActive
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
        private const val DAC_WARMUP_MS = 50
    }

    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private var isReady = false
    private var speakJob: Job? = null
    private var speakGeneration = 0L
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val audioLock = Any()

    private val _events = MutableSharedFlow<TtsEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<TtsEvent> = _events

    // 分句缓冲
    private val sentenceBuffer = StringBuilder()
    private val sentenceQueue = mutableListOf<String>()
    private var streamFinished = false
    private var dacWarmedUp = false
    @Volatile private var interrupted = false

    private val sentenceDelimiters = charArrayOf('。', '！', '？', '，', '；', '\n', '.', '!', '?')

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
     * 流式接收 token，按标点分句后立即入队合成播报
     */
    fun feedToken(token: String) {
        if (!isReady) return

        sentenceBuffer.append(token)

        val content = sentenceBuffer.toString()
        val lastDelimiterIndex = content.indexOfLast { it in sentenceDelimiters }

        if (lastDelimiterIndex >= 0) {
            val sentence = content.substring(0, lastDelimiterIndex + 1).trim()
            val remaining = content.substring(lastDelimiterIndex + 1)
            sentenceBuffer.clear()
            sentenceBuffer.append(remaining)

            if (sentence.isNotBlank()) {
                synchronized(sentenceQueue) {
                    sentenceQueue.add(sentence)
                }
                ensureSpeaking()
            }
        }
    }

    /**
     * 流结束，播报剩余缓冲内容
     */
    fun finishStream() {
        if (!isReady) return
        val remaining = sentenceBuffer.toString().trim()
        if (remaining.isNotBlank()) {
            synchronized(sentenceQueue) {
                sentenceQueue.add(remaining)
            }
        }
        sentenceBuffer.clear()
        streamFinished = true
        ensureSpeaking()
    }

    private fun ensureSpeaking() {
        if (speakJob?.isActive == true) return
        interrupted = false
        val generation = speakGeneration
        speakJob = scope.launch {
            _events.tryEmit(TtsEvent.SpeakStart)
            dacWarmedUp = false

            while (isActive && !interrupted) {
                val sentence = synchronized(sentenceQueue) {
                    if (sentenceQueue.isNotEmpty()) sentenceQueue.removeAt(0) else null
                }

                if (sentence != null) {
                    speakSentence(sentence)
                } else if (streamFinished) {
                    break
                } else {
                    kotlinx.coroutines.delay(100)
                }
            }

            streamFinished = false
            if (generation == speakGeneration) {
                _events.tryEmit(TtsEvent.AllDone)
            }
        }
    }

    private fun speakSentence(text: String) {
        val engine = tts ?: return
        Log.d(TAG, "Speaking: '$text'")

        val audio = try {
            engine.generate(text = text, sid = SPEAKER_ID, speed = 1.0f)
        } catch (e: Exception) {
            Log.e(TAG, "TTS generate error: ${e.message}")
            return
        }
        if (audio.samples.isEmpty()) return

        // 生成完后检查是否被打断
        if (interrupted) return

        // 转 PCM 16-bit
        val pcm = ShortArray(audio.samples.size) {
            (audio.samples[it] * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }

        synchronized(audioLock) {
            if (interrupted) return
            ensureAudioTrack(audio.sampleRate)

            // 首句前插入 DAC 预热信号，防止硬件 gate 导致淡入
            if (!dacWarmedUp) {
                val warmupSamples = audio.sampleRate * DAC_WARMUP_MS / 1000
                val warmup = ShortArray(warmupSamples) { 1 }
                writePcmLocked(warmup)
                dacWarmedUp = true
            }

            writePcmLocked(pcm)
        }
    }

    fun stop() {
        // 标记打断，当前句生成完后不写入 AudioTrack；同时废弃旧 job 的 AllDone 事件
        speakGeneration++
        interrupted = true
        speakJob?.cancel()
        speakJob = null
        synchronized(sentenceQueue) { sentenceQueue.clear() }
        sentenceBuffer.clear()
        streamFinished = true
        dacWarmedUp = false
        // 立刻静音：flush AudioTrack 缓冲区。AudioTrack 不是线程安全的，必须避免和播放线程 write 并发。
        synchronized(audioLock) {
            try {
                audioTrack?.pause()
                audioTrack?.flush()
                audioTrack?.play()
            } catch (e: Exception) {
                Log.w(TAG, "AudioTrack flush error: ${e.message}")
            }
        }
    }

    fun speakImmediate(text: String) {
        if (!isReady) return
        stop()
        synchronized(sentenceQueue) { sentenceQueue.add(text) }
        streamFinished = true
        ensureSpeaking()
    }

    private fun ensureAudioTrack(sampleRate: Int) {
        if (audioTrack != null) return

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufferSize, sampleRate * 2)

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

    private fun writePcmLocked(pcm: ShortArray) {
        val track = audioTrack ?: return
        var offset = 0
        val chunkSize = SAMPLE_RATE / 4
        while (offset < pcm.size && !interrupted) {
            val count = minOf(chunkSize, pcm.size - offset)
            val written = track.write(pcm, offset, count)
            if (written <= 0) {
                Log.w(TAG, "AudioTrack write returned $written")
                break
            }
            offset += written
        }
    }

    private fun releaseAudioTrack() {
        synchronized(audioLock) {
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
    }

    fun destroy() {
        stop()
        releaseAudioTrack()
        tts?.release()
        tts = null
        isReady = false
    }

    /**
     * 播放一个短促提示音（独立 AudioTrack，不和 TTS 排队）
     */
    fun playBeep(freqHz: Int = 880, durationMs: Int = 80) {
        Thread {
            val sampleRate = 16000
            val numSamples = sampleRate * durationMs / 1000
            // DAC 预热 + 提示音
            val warmupSamples = sampleRate * 50 / 1000
            val total = warmupSamples + numSamples
            val samples = ShortArray(total)
            for (i in 0 until warmupSamples) samples[i] = 1
            for (i in 0 until numSamples) {
                val t = i.toFloat() / sampleRate
                val env = minOf(1.0f, i.toFloat() / (numSamples * 0.1f)) *
                          minOf(1.0f, (numSamples - i).toFloat() / (numSamples * 0.2f))
                samples[warmupSamples + i] = (kotlin.math.sin(2.0 * Math.PI * freqHz * t).toFloat() * 28000 * env)
                    .toInt().coerceIn(-32768, 32767).toShort()
            }

            val bufSize = android.media.AudioTrack.getMinBufferSize(
                sampleRate, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT
            )
            val track = android.media.AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(bufSize, total * 2))
                .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                .build()

            track.write(samples, 0, samples.size)
            track.play()
            Thread.sleep((50 + durationMs).toLong() + 30)
            try { track.stop() } catch (_: Exception) {}
            try { track.release() } catch (_: Exception) {}
        }.start()
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
