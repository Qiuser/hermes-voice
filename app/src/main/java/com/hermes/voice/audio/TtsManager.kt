package com.hermes.voice.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TtsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var tts: TextToSpeech? = null
    private var isReady = false
    private val utteranceCounter = AtomicInteger(0)
    private var pendingUtteranceId: String? = null

    private val _events = MutableSharedFlow<TtsEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<TtsEvent> = _events

    // 分句缓冲
    private val sentenceBuffer = StringBuilder()
    private var totalQueued = 0
    private var totalSpoken = 0

    private val sentenceDelimiters = charArrayOf('。', '！', '？', '，', '；', '\n')

    fun init() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINESE
                tts?.setSpeechRate(1.0f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _events.tryEmit(TtsEvent.SpeakStart)
                    }

                    override fun onDone(utteranceId: String?) {
                        totalSpoken++
                        if (utteranceId == pendingUtteranceId) {
                            _events.tryEmit(TtsEvent.AllDone)
                            reset()
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _events.tryEmit(TtsEvent.Error("TTS 播报错误"))
                    }
                })
                isReady = true
                _events.tryEmit(TtsEvent.Initialized)
            } else {
                _events.tryEmit(TtsEvent.Error("TTS 初始化失败"))
            }
        }
    }

    /**
     * 流式接收 token，按标点分句后立即入队播报
     */
    fun feedToken(token: String) {
        if (!isReady) return

        sentenceBuffer.append(token)

        // 检查是否有完整句子可以播报
        val content = sentenceBuffer.toString()
        val lastDelimiterIndex = content.indexOfLast { it in sentenceDelimiters }

        if (lastDelimiterIndex >= 0) {
            val sentence = content.substring(0, lastDelimiterIndex + 1).trim()
            val remaining = content.substring(lastDelimiterIndex + 1)
            sentenceBuffer.clear()
            sentenceBuffer.append(remaining)

            if (sentence.isNotBlank()) {
                speakQueued(sentence)
            }
        }
    }

    /**
     * 流式结束，播报剩余缓冲内容
     */
    fun finishStream() {
        if (!isReady) return

        val remaining = sentenceBuffer.toString().trim()
        if (remaining.isNotBlank()) {
            speakQueued(remaining)
        }
        sentenceBuffer.clear()

        // 标记最后一个 utterance
        pendingUtteranceId = "utt_${utteranceCounter.get()}"
        // 如果没有任何内容被播报，直接通知完成
        if (totalQueued == 0) {
            _events.tryEmit(TtsEvent.AllDone)
            reset()
        }
    }

    private fun speakQueued(text: String) {
        val id = "utt_${utteranceCounter.incrementAndGet()}"
        pendingUtteranceId = id
        totalQueued++
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    fun stop() {
        tts?.stop()
        reset()
    }

    fun speakImmediate(text: String) {
        if (!isReady) return
        val id = "utt_${utteranceCounter.incrementAndGet()}"
        pendingUtteranceId = id
        totalQueued = 1
        totalSpoken = 0
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun reset() {
        sentenceBuffer.clear()
        totalQueued = 0
        totalSpoken = 0
        pendingUtteranceId = null
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
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
