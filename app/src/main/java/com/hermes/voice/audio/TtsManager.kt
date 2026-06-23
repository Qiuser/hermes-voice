package com.hermes.voice.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
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
        private const val SAMPLE_RATE = 22050
    }

    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private var isReady = false
    private var speakJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val _events = MutableSharedFlow<TtsEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<TtsEvent> = _events

    // 分句缓冲
    private val sentenceBuffer = StringBuilder()
    private val sentenceQueue = mutableListOf<String>()
    private var streamFinished = false

    private val sentenceDelimiters = charArrayOf('。', '！', '？', '，', '；', '\n', '.', '!', '?')

    fun init() {
        try {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = "$TTS_MODEL_DIR/model.onnx",
                        lexicon = "$TTS_MODEL_DIR/lexicon.txt",
                        tokens = "$TTS_MODEL_DIR/tokens.txt",
                    ),
                    numThreads = 2,
                    debug = true,
                ),
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
        speakJob = scope.launch {
            _events.tryEmit(TtsEvent.SpeakStart)

            while (isActive) {
                val sentence = synchronized(sentenceQueue) {
                    if (sentenceQueue.isNotEmpty()) sentenceQueue.removeAt(0) else null
                }

                if (sentence != null) {
                    speakSentence(sentence)
                } else if (streamFinished) {
                    break
                } else {
                    // 等待更多句子
                    kotlinx.coroutines.delay(100)
                }
            }

            streamFinished = false
            releaseAudioTrack()
            _events.tryEmit(TtsEvent.AllDone)
        }
    }

    private fun speakSentence(text: String) {
        val engine = tts ?: return
        Log.d(TAG, "Speaking: '$text', sid=0, speed=1.0")

        val audio = engine.generate(text = text, sid = 0, speed = 1.0f)
        Log.d(TAG, "Generated ${audio.samples.size} samples at ${audio.sampleRate}Hz")
        if (audio.samples.isEmpty()) return

        val sampleRate = audio.sampleRate
        ensureAudioTrack(sampleRate)

        // 转 PCM 16-bit
        val pcm = ShortArray(audio.samples.size) {
            (audio.samples[it] * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }

        audioTrack?.write(pcm, 0, pcm.size)
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
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
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
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }

    fun stop() {
        speakJob?.cancel()
        speakJob = null
        synchronized(sentenceQueue) { sentenceQueue.clear() }
        sentenceBuffer.clear()
        streamFinished = false
        releaseAudioTrack()
    }

    fun speakImmediate(text: String) {
        if (!isReady) return
        stop()
        synchronized(sentenceQueue) { sentenceQueue.add(text) }
        streamFinished = true
        ensureSpeaking()
    }

    fun destroy() {
        stop()
        tts?.release()
        tts = null
        isReady = false
    }
}

sealed class TtsEvent {
    data object Initialized : TtsEvent()
    data object SpeakStart : TtsEvent()
    data object AllDone : TtsEvent()
    data class Error(val message: String) : TtsEvent()
}
