package com.hermes.voice.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.voice.network.ApiConfig
import com.hermes.voice.network.ConnectionManager
import com.hermes.voice.network.ConnectionState
import com.hermes.voice.network.VoiceWebSocketClient
import com.hermes.voice.network.WsEvent
import com.hermes.voice.session.SessionState
import com.hermes.voice.session.VoiceSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val apiConfig: ApiConfig,
    private val wsClient: VoiceWebSocketClient,
    private val connectionManager: ConnectionManager,
    private val voiceSessionManager: VoiceSessionManager
) : ViewModel() {

    private val _sessionState = MutableLiveData(SessionState.IDLE)
    val sessionState: LiveData<SessionState> = _sessionState

    private val _chatLog = MutableLiveData("")
    val chatLog: LiveData<String> = _chatLog

    private val _configValid = MutableLiveData(false)
    val configValid: LiveData<Boolean> = _configValid

    private val _connectionStatus = MutableLiveData("未连接")
    val connectionStatus: LiveData<String> = _connectionStatus

    private var voiceObserveJob: Job? = null
    private val chatLogBuilder = StringBuilder()
    private val currentResponse = StringBuilder()
    private var waitingForResponse = false

    init {
        checkConfig()
        initVoiceSession()
        observeConnection()
        observeWsEvents()
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
                voiceSessionManager.transcript.collect { text ->
                    _chatLog.postValue("${chatLogBuilder}你: $text")
                }
            }
            launch {
                voiceSessionManager.error.collect { msg ->
                    chatLogBuilder.append("错误: $msg\n\n")
                    _chatLog.postValue(chatLogBuilder.toString())
                }
            }
        }
    }

    private fun observeConnection() {
        viewModelScope.launch {
            connectionManager.connectionState.collect { state ->
                val text = when (state) {
                    ConnectionState.DISCONNECTED -> "未连接"
                    ConnectionState.CONNECTING -> "连接中..."
                    ConnectionState.CONNECTED -> "已连接"
                    ConnectionState.AUTH_FAILED -> "认证失败"
                }
                _connectionStatus.postValue(text)
            }
        }
    }

    private fun observeWsEvents() {
        viewModelScope.launch {
            wsClient.events.collect { event ->
                when (event) {
                    is WsEvent.Delta -> {
                        currentResponse.append(event.content)
                        _chatLog.postValue("${chatLogBuilder}Hermes: $currentResponse")
                        if (_sessionState.value == SessionState.IDLE && waitingForResponse) {
                            _sessionState.postValue(SessionState.SPEAKING)
                        }
                    }
                    is WsEvent.End -> {
                        if (waitingForResponse) {
                            chatLogBuilder.append("Hermes: $currentResponse\n\n")
                            currentResponse.clear()
                            waitingForResponse = false
                            _chatLog.postValue(chatLogBuilder.toString())
                            _sessionState.postValue(SessionState.IDLE)
                        }
                    }
                    is WsEvent.Busy -> {
                        chatLogBuilder.append("系统: ${event.message}\n\n")
                        _chatLog.postValue(chatLogBuilder.toString())
                    }
                    is WsEvent.ToolStart -> {
                        _chatLog.postValue("${chatLogBuilder}[工具] ${event.description}...")
                    }
                    is WsEvent.Error -> {
                        if (waitingForResponse) {
                            chatLogBuilder.append("错误: ${event.message}\n\n")
                            currentResponse.clear()
                            waitingForResponse = false
                            _chatLog.postValue(chatLogBuilder.toString())
                            _sessionState.postValue(SessionState.IDLE)
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    fun checkConfig() {
        val configured = apiConfig.isConfigured
        _configValid.value = configured
        if (configured && connectionManager.connectionState.value == ConnectionState.DISCONNECTED) {
            connectionManager.start()
        }
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
        if (!wsClient.isConnected) {
            chatLogBuilder.append("错误: 未连接到服务器\n\n")
            _chatLog.value = chatLogBuilder.toString()
            return
        }

        chatLogBuilder.append("你: $text\n\n")
        _chatLog.value = "${chatLogBuilder}Hermes: ..."
        currentResponse.clear()
        waitingForResponse = true
        _sessionState.value = SessionState.THINKING

        wsClient.sendMessage(text)
    }

    fun sendNewSession() {
        wsClient.sendCommand("new")
        chatLogBuilder.clear()
        currentResponse.clear()
        _chatLog.value = ""
    }

    override fun onCleared() {
        super.onCleared()
        voiceObserveJob?.cancel()
        connectionManager.stop()
    }
}
