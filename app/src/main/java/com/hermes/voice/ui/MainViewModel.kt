package com.hermes.voice.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.voice.data.MessageDao
import com.hermes.voice.data.MessageEntity
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val apiConfig: ApiConfig,
    private val wsClient: VoiceWebSocketClient,
    private val connectionManager: ConnectionManager,
    private val voiceSessionManager: VoiceSessionManager,
    private val messageDao: MessageDao
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
    private var waitingForTextResponse = false // 仅文字对话模式
    private var inVoiceSession = false // 语音对话模式

    init {
        checkConfig()
        loadHistory()
        initVoiceSession()
        observeConnection()
        observeWsEvents()
    }

    private fun loadHistory() {
        viewModelScope.launch {
            val messages = messageDao.getAll()
            var lastDate = ""
            for (msg in messages) {
                val dateStr = formatDate(msg.timestamp)
                val timeStr = formatTime(msg.timestamp)
                // 日期变化时显示日期分隔线
                if (dateStr != lastDate) {
                    chatLogBuilder.append("── $dateStr ──\n\n")
                    lastDate = dateStr
                }
                when (msg.role) {
                    "user" -> chatLogBuilder.append("[$timeStr] 你: ${msg.content}\n\n")
                    "assistant" -> chatLogBuilder.append("[$timeStr] Hermes: ${msg.content}\n\n")
                    "system" -> chatLogBuilder.append("[$timeStr] ${msg.content}\n\n")
                    "error" -> chatLogBuilder.append("[$timeStr] 错误: ${msg.content}\n\n")
                }
            }
            if (chatLogBuilder.isNotEmpty()) {
                _chatLog.postValue(chatLogBuilder.toString())
            }
        }
    }

    private fun saveMessage(role: String, content: String) {
        viewModelScope.launch {
            messageDao.insert(MessageEntity(role = role, content = content))
        }
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    private fun formatDate(timestamp: Long): String {
        return SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(timestamp))
    }

    private fun appendWithTime(prefix: String, content: String) {
        val time = formatTime(System.currentTimeMillis())
        chatLogBuilder.append("[$time] $prefix$content\n\n")
    }

    private fun initVoiceSession() {
        voiceSessionManager.initialize()

        voiceObserveJob = viewModelScope.launch {
            launch {
                voiceSessionManager.state.collect { state ->
                    _sessionState.postValue(state)
                    // 跟踪语音会话状态
                    inVoiceSession = state != SessionState.IDLE
                }
            }
            launch {
                voiceSessionManager.transcript.collect { text ->
                    // 最终识别结果，追加到 chatLog
                    appendWithTime("你: ", text)
                    _chatLog.postValue("${chatLogBuilder}Hermes: ...")
                    saveMessage("user", text)
                }
            }
            launch {
                voiceSessionManager.partial.collect { text ->
                    // 中间识别结果，临时显示不追加
                    _chatLog.postValue("${chatLogBuilder}你: $text")
                }
            }
            launch {
                voiceSessionManager.error.collect { msg ->
                    appendWithTime("错误: ", msg)
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
                        // 文字模式也播报 TTS（过滤系统消息）
                        if (shouldTts(event.content)) {
                            voiceSessionManager.feedTtsToken(event.content)
                        }
                    }
                    is WsEvent.End -> {
                        if (currentResponse.isNotEmpty()) {
                            val response = currentResponse.toString()
                            appendWithTime("Hermes: ", response)
                            currentResponse.clear()
                            _chatLog.postValue(chatLogBuilder.toString())
                            saveMessage("assistant", response)
                        }
                        voiceSessionManager.finishTts()
                        if (waitingForTextResponse) {
                            waitingForTextResponse = false
                            _sessionState.postValue(SessionState.IDLE)
                        }
                    }
                    is WsEvent.Busy -> {
                        appendWithTime("系统: ", event.message)
                        _chatLog.postValue(chatLogBuilder.toString())
                    }
                    is WsEvent.System -> {
                        // 系统消息不播报，静默显示
                        appendWithTime("", event.content)
                        _chatLog.postValue(chatLogBuilder.toString())
                    }
                    is WsEvent.ToolStart -> {
                        _chatLog.postValue("${chatLogBuilder}[工具] ${event.description}...")
                    }
                    is WsEvent.ApprovalRequest -> {
                        appendWithTime("审批: ", "${event.description}\n命令: ${event.command}")
                        _chatLog.postValue(chatLogBuilder.toString())
                    }
                    is WsEvent.Error -> {
                        if (waitingForTextResponse || inVoiceSession) {
                            appendWithTime("错误: ", event.message)
                            currentResponse.clear()
                            waitingForTextResponse = false
                            _chatLog.postValue(chatLogBuilder.toString())
                            if (waitingForTextResponse) {
                                _sessionState.postValue(SessionState.IDLE)
                            }
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
        // 连接由 VoiceService 管理，这里只更新 UI 状态
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
            appendWithTime("错误: ", "未连接到服务器")
            _chatLog.value = chatLogBuilder.toString()
            return
        }

        appendWithTime("你: ", text)
        _chatLog.value = "${chatLogBuilder}Hermes: ..."
        currentResponse.clear()
        waitingForTextResponse = true
        saveMessage("user", text)
        _sessionState.value = SessionState.THINKING

        wsClient.sendMessage(text)
    }

    fun sendNewSession() {
        wsClient.sendCommand("new")
        chatLogBuilder.clear()
        currentResponse.clear()
        _chatLog.value = ""
        viewModelScope.launch { messageDao.deleteAll() }
    }

    override fun onCleared() {
        super.onCleared()
        voiceObserveJob?.cancel()
        // 不 stop connectionManager，由 VoiceService 管理
    }

    companion object {
        private val systemPatterns = listOf(
            "Self-improvement review",
            "Memory updated",
            "User profile updated",
            "Skill library updated",
            "File-mutation verifier",
            "No home channel",
        )

        fun shouldTts(content: String): Boolean {
            return systemPatterns.none { content.contains(it) }
        }
    }
}
