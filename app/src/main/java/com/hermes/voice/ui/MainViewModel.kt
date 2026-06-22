package com.hermes.voice.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.voice.api.ApiConfig
import com.hermes.voice.api.HermesApiClient
import com.hermes.voice.api.StreamEvent
import com.hermes.voice.session.SessionState
import com.hermes.voice.session.VoiceSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val apiConfig: ApiConfig,
    private val apiClient: HermesApiClient,
    private val voiceSessionManager: VoiceSessionManager
) : ViewModel() {

    private val _sessionState = MutableLiveData(SessionState.IDLE)
    val sessionState: LiveData<SessionState> = _sessionState

    private val _lastMessage = MutableLiveData("")
    val lastMessage: LiveData<String> = _lastMessage

    private val _configValid = MutableLiveData(false)
    val configValid: LiveData<Boolean> = _configValid

    private var chatJob: Job? = null
    private var voiceObserveJob: Job? = null

    init {
        checkConfig()
        initVoiceSession()
    }

    private fun initVoiceSession() {
        voiceSessionManager.initialize()

        voiceObserveJob = viewModelScope.launch {
            launch {
                voiceSessionManager.state.collect { state ->
                    _sessionState.postValue(state)
                }
            }
            launch {
                val responseBuilder = StringBuilder()
                voiceSessionManager.response.collect { token ->
                    responseBuilder.append(token)
                    _lastMessage.postValue("Hermes: $responseBuilder")
                }
            }
            launch {
                voiceSessionManager.transcript.collect { text ->
                    _lastMessage.postValue("你: $text")
                }
            }
            launch {
                voiceSessionManager.error.collect { msg ->
                    _lastMessage.postValue("错误: $msg")
                }
            }
        }
    }

    fun checkConfig() {
        _configValid.value = apiConfig.isConfigured
    }

    fun toggleVoiceSession() {
        if (_sessionState.value == SessionState.IDLE) {
            voiceSessionManager.startSession()
        } else {
            voiceSessionManager.stopSession()
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        if (!apiConfig.isConfigured) {
            _lastMessage.value = "请先在设置中配置 API 地址和 Token"
            return
        }

        chatJob?.cancel()
        _sessionState.value = SessionState.THINKING
        _lastMessage.value = "你: $text\n\nHermes: "

        val responseBuilder = StringBuilder()

        chatJob = viewModelScope.launch {
            apiClient.streamChat(text).collect { event ->
                when (event) {
                    is StreamEvent.Connected -> {
                        _sessionState.postValue(SessionState.THINKING)
                    }
                    is StreamEvent.Token -> {
                        responseBuilder.append(event.content)
                        _lastMessage.postValue("你: $text\n\nHermes: $responseBuilder")
                        if (_sessionState.value == SessionState.THINKING) {
                            _sessionState.postValue(SessionState.SPEAKING)
                        }
                    }
                    is StreamEvent.Done -> {
                        _sessionState.postValue(SessionState.IDLE)
                    }
                    is StreamEvent.Error -> {
                        _lastMessage.postValue("你: $text\n\n错误: ${event.message}")
                        _sessionState.postValue(SessionState.IDLE)
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceObserveJob?.cancel()
    }
}
