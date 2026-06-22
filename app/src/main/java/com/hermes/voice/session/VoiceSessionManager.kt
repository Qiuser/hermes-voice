package com.hermes.voice.session

import com.hermes.voice.api.HermesApiClient
import com.hermes.voice.api.StreamEvent
import com.hermes.voice.audio.AudioFocusManager
import com.hermes.voice.audio.SpeechRecognizerManager
import com.hermes.voice.audio.SttEvent
import com.hermes.voice.audio.TtsEvent
import com.hermes.voice.audio.TtsManager
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
    private val apiClient: HermesApiClient,
    private val audioFocusManager: AudioFocusManager
) {
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val _state = MutableStateFlow(SessionState.IDLE)
    val state: StateFlow<SessionState> = _state

    private val _transcript = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val transcript: SharedFlow<String> = _transcript

    private val _response = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val response: SharedFlow<String> = _response

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val error: SharedFlow<String> = _error

    private var apiJob: Job? = null
    private var sttJob: Job? = null
    private var ttsJob: Job? = null

    fun initialize() {
        ttsManager.init()
        observeStt()
        observeTts()
    }

    fun startSession() {
        if (_state.value != SessionState.IDLE) return
        audioFocusManager.requestFocus()
        transitionTo(SessionState.LISTENING)
        sttManager.startListening()
    }

    fun stopSession() {
        apiJob?.cancel()
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
                        _transcript.tryEmit(event.text)
                        transitionTo(SessionState.THINKING)
                        sendToApi(event.text)
                    }
                    is SttEvent.PartialResult -> {
                        _transcript.tryEmit(event.text)
                    }
                    is SttEvent.Error -> {
                        if (_state.value == SessionState.LISTENING) {
                            // 静默超时或无匹配 → 回到空闲
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
                            // 播报完成 → 继续监听（连续对话）
                            transitionTo(SessionState.LISTENING)
                            sttManager.startListening()
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

    private fun sendToApi(text: String) {
        apiJob?.cancel()
        apiJob = scope.launch {
            apiClient.streamChat(text).collect { event ->
                when (event) {
                    is StreamEvent.Connected -> {}
                    is StreamEvent.Token -> {
                        if (_state.value == SessionState.THINKING) {
                            transitionTo(SessionState.SPEAKING)
                        }
                        _response.tryEmit(event.content)
                        ttsManager.feedToken(event.content)
                    }
                    is StreamEvent.Done -> {
                        ttsManager.finishStream()
                    }
                    is StreamEvent.Error -> {
                        ttsManager.speakImmediate(event.message)
                        _error.tryEmit(event.message)
                    }
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
        sttManager.destroy()
        ttsManager.destroy()
    }
}
