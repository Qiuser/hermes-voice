package com.hermes.voice.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.voice.api.ApiConfig
import com.hermes.voice.api.ChatMessage
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

    private val _chatLog = MutableLiveData("")
    val chatLog: LiveData<String> = _chatLog

    private val _configValid = MutableLiveData(false)
    val configValid: LiveData<Boolean> = _configValid

    private var chatJob: Job? = null
    private var voiceObserveJob: Job? = null
    private val chatHistory = mutableListOf<ChatMessage>()
    private val chatLogBuilder = StringBuilder()

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
                    _chatLog.postValue("${chatLogBuilder}Hermes: $responseBuilder")
                }
            }
            launch {
                voiceSessionManager.transcript.collect { text ->
                    _chatLog.postValue("${chatLogBuilder}你: $text")
                }
            }
            launch {
                voiceSessionManager.error.collect { msg ->
                    _chatLog.postValue("${chatLogBuilder}错误: $msg")
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
            _chatLog.value = "请先在设置中配置 API 地址和 Token"
            return
        }

        chatJob?.cancel()
        _sessionState.value = SessionState.THINKING

        // 追加用户消息到日志
        chatLogBuilder.append("你: $text\n\n")
        _chatLog.value = "${chatLogBuilder}Hermes: ..."

        val responseBuilder = StringBuilder()

        chatJob = viewModelScope.launch {
            apiClient.streamChat(text, chatHistory).collect { event ->
                when (event) {
                    is StreamEvent.Connected -> {
                        _sessionState.postValue(SessionState.THINKING)
                    }
                    is StreamEvent.Token -> {
                        responseBuilder.append(event.content)
                        _chatLog.postValue("${chatLogBuilder}Hermes: $responseBuilder")
                        if (_sessionState.value == SessionState.THINKING) {
                            _sessionState.postValue(SessionState.SPEAKING)
                        }
                    }
                    is StreamEvent.Done -> {
                        // 保存到对话历史
                        chatHistory.add(ChatMessage(role = "user", content = text))
                        chatHistory.add(ChatMessage(role = "assistant", content = responseBuilder.toString()))
                        // 追加助手回复到日志
                        chatLogBuilder.append("Hermes: $responseBuilder\n\n")
                        _chatLog.postValue(chatLogBuilder.toString())
                        _sessionState.postValue(SessionState.IDLE)
                    }
                    is StreamEvent.Error -> {
                        chatLogBuilder.append("错误: ${event.message}\n\n")
                        _chatLog.postValue(chatLogBuilder.toString())
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
