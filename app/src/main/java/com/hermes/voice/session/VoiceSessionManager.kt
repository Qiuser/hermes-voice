package com.hermes.voice.session

import com.hermes.voice.audio.AudioFocusManager
import com.hermes.voice.audio.SpeechRecognizerManager
import com.hermes.voice.audio.SttEvent
import com.hermes.voice.audio.TtsEvent
import com.hermes.voice.audio.TtsManager
import com.hermes.voice.network.ApiConfig
import com.hermes.voice.network.VoiceWebSocketClient
import com.hermes.voice.network.WsEvent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceSessionManager @Inject constructor(
    private val sttManager: SpeechRecognizerManager,
    private val ttsManager: TtsManager,
    private val wsClient: VoiceWebSocketClient,
    private val audioFocusManager: AudioFocusManager,
    private val apiConfig: ApiConfig
) {
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val _state = MutableStateFlow(SessionState.IDLE)
    val state: StateFlow<SessionState> = _state

    private val _transcript = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val transcript: SharedFlow<String> = _transcript

    private val _partial = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val partial: SharedFlow<String> = _partial

    private val _response = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val response: SharedFlow<String> = _response

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val error: SharedFlow<String> = _error

    private var sttJob: Job? = null
    private var ttsJob: Job? = null
    private var wsJob: Job? = null

    fun initialize() {
        ttsManager.init()
        sttManager.initialize()
        observeStt()
        observeTts()
        observeWs()
    }

    fun startSession() {
        if (_state.value != SessionState.IDLE) return
        audioFocusManager.requestFocus()
        // 每次开始对话前刷新 STT token（如果快过期）
        if (!sttManager.hasSttToken()) {
            wsClient.requestSttToken()
        }
        transitionTo(SessionState.LISTENING)
        sttManager.startListening()
    }

    fun stopSession() {
        sttManager.stopListening()
        ttsManager.stop()
        audioFocusManager.releaseFocus()
        transitionTo(SessionState.IDLE)
    }

    private fun observeStt() {
        sttJob = scope.launch {
            sttManager.events.collect { event ->
                when (event) {
                    is SttEvent.Result -> {
                        Log.d("VoiceSession", "STT result: ${event.text}, sending to WS")
                        _transcript.tryEmit(event.text)
                        transitionTo(SessionState.THINKING)
                        wsClient.sendMessage(event.text)
                        // 提示音：消息已发送
                        ttsManager.playBeep(500, 100)
                    }
                    is SttEvent.PartialResult -> {
                        // partial result 不发送，只通知 UI 临时展示
                        _partial.tryEmit(event.text)
                    }
                    is SttEvent.Error -> {
                        Log.d("VoiceSession", "STT error: ${event.message}")
                        if (_state.value == SessionState.LISTENING) {
                            _error.tryEmit(event.message)
                            stopSession()
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun observeTts() {
        ttsJob = scope.launch {
            ttsManager.events.collect { event ->
                when (event) {
                    is TtsEvent.AllDone -> {
                        if (_state.value == SessionState.SPEAKING) {
                            if (apiConfig.autoContinueEnabled) {
                                // 播报完成 → 暂停 0.5 秒 → 提示音 → 继续监听
                                kotlinx.coroutines.delay(500)
                                ttsManager.playBeep(500, 100)
                                kotlinx.coroutines.delay(150)
                                transitionTo(SessionState.LISTENING)
                                sttManager.startListening()
                            } else {
                                // 不自动继续，回到待命
                                transitionTo(SessionState.IDLE)
                                audioFocusManager.releaseFocus()
                            }
                        }
                    }
                    is TtsEvent.Error -> {
                        _error.tryEmit(event.message)
                        stopSession()
                    }
                    else -> {}
                }
            }
        }
    }

    private fun observeWs() {
        wsJob = scope.launch {
            wsClient.events.collect { event ->
                when (event) {
                    is WsEvent.Connected -> {
                        // 连接成功后请求 STT 凭据
                        wsClient.requestSttToken()
                    }
                    is WsEvent.SttToken -> {
                        // 收到讯飞凭据，设置给 STT 管理器
                        sttManager.setSttToken(event.url, event.appId)
                    }
                    is WsEvent.Delta -> {
                        if (_state.value == SessionState.THINKING) {
                            transitionTo(SessionState.SPEAKING)
                        }
                        _response.tryEmit(event.content)
                        // TTS 由 ViewModel 统一驱动，这里不再重复 feed
                    }
                    is WsEvent.End -> {
                        // TTS finishStream 也由 ViewModel 驱动
                    }
                    is WsEvent.Busy -> {
                        ttsManager.speakImmediate(event.message)
                    }
                    is WsEvent.Error -> {
                        if (_state.value != SessionState.IDLE) {
                            ttsManager.speakImmediate(event.message)
                            _error.tryEmit(event.message)
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun transitionTo(newState: SessionState) {
        _state.value = newState
    }

    fun destroy() {
        stopSession()
        sttJob?.cancel()
        ttsJob?.cancel()
        wsJob?.cancel()
        sttManager.destroy()
        ttsManager.destroy()
    }

    /** 文字模式下也可调用 TTS 播报 */
    fun feedTtsToken(content: String) {
        ttsManager.feedToken(content)
    }

    fun finishTts() {
        ttsManager.finishStream()
    }

    /** 播放简短提示语 */
    fun speakCue(text: String) {
        ttsManager.speakImmediate(text)
    }
}
