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
    companion object {
        private const val TAG = "VoiceSession"
        private const val APPROVAL_TIMEOUT_MS = 30_000L
        private const val APPROVAL_SILENCE_TIMEOUT_SEC = 10f
        private const val PAIRING_POLL_INTERVAL_MS = 3_000L
        private const val PAIRING_MAX_POLLS = 40
    }

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
    private var tokenRefreshJob: Job? = null

    // Approval state
    private var pendingApprovalId: String? = null
    private var approvalRetryCount = 0
    private var approvalTimeoutJob: Job? = null
    private var pairingPollJob: Job? = null

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
        transitionTo(SessionState.LISTENING)

        // 如果没有可用 token，先请求再等
        if (!sttManager.hasSttToken()) {
            wsClient.requestSttToken()
        }

        // 延迟 500ms 再开始录音（给 token 请求留时间，也给"嗯"播完留时间）
        scope.launch {
            kotlinx.coroutines.delay(500)
            sttManager.startListening()
        }
    }

    fun stopSession() {
        cancelApprovalTimeout()
        stopPairingPolling()
        pendingApprovalId = null
        sttManager.stopListening()
        ttsManager.stop()
        audioFocusManager.releaseFocus()
        transitionTo(SessionState.IDLE)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Approval handling
    // ──────────────────────────────────────────────────────────────────────

    private fun handleApprovalRequest(approvalId: String, command: String, description: String) {
        Log.d(TAG, "Approval request: id=$approvalId cmd=${command.take(80)}")

        // Save approval state
        pendingApprovalId = approvalId
        approvalRetryCount = 0

        // Interrupt whatever is happening
        val currentState = _state.value
        when (currentState) {
            SessionState.IDLE -> audioFocusManager.requestFocus()
            SessionState.LISTENING -> sttManager.stopListening()
            SessionState.SPEAKING -> ttsManager.stop()
            else -> {} // THINKING / APPROVAL_WAITING — just override
        }

        // Build and speak the approval prompt
        val prompt = buildApprovalPrompt(command, description)
        transitionTo(SessionState.APPROVAL_WAITING)
        ttsManager.speakImmediate(prompt)
        // After TTS finishes, observeTts() will detect APPROVAL_WAITING and start STT
    }

    private fun handleApprovalClarify(approvalId: String, message: String) {
        Log.d(TAG, "Approval clarify: id=$approvalId")
        cancelApprovalTimeout()
        sttManager.stopListening()
        ttsManager.stop()
        pendingApprovalId = approvalId
        approvalRetryCount = 0
        transitionTo(SessionState.APPROVAL_WAITING)
        ttsManager.speakImmediate(message)
    }

    private fun buildApprovalPrompt(command: String, description: String): String {
        // 只提取命令名（第一个词），不读参数/URL，语音只播一句概括
        val cmdName = command.trimStart()
            .split(" ", "|", ";", "&&", "\n")
            .first()
            .split("/")
            .last()
            .ifBlank { "未知" }
        return "需要执行 $cmdName 命令，是否允许？"
    }

    private fun startApprovalListening() {
        Log.d(TAG, "Starting approval STT listening")
        // Request fresh STT token if needed
        if (!sttManager.hasSttToken()) {
            wsClient.requestSttToken()
        }
        // Start timeout
        startApprovalTimeout()
        // Start listening with longer silence timeout for approval
        scope.launch {
            kotlinx.coroutines.delay(300)
            ttsManager.playBeep(500, 100)
            kotlinx.coroutines.delay(150)
            sttManager.startListening(silenceTimeoutSec = APPROVAL_SILENCE_TIMEOUT_SEC)
        }
    }

    private fun handleApprovalSttResult(text: String) {
        Log.d(TAG, "Approval STT result: '$text'")
        cancelApprovalTimeout()

        val approvalId = pendingApprovalId ?: return
        Log.d(TAG, "Sending approval reply to server for LLM classification: '$text'")

        // Send raw text to server — LLM will classify intent
        wsClient.sendApprovalReply(approvalId, text)

        pendingApprovalId = null
        approvalRetryCount = 0

        // Brief feedback and wait for server's response
        ttsManager.speakImmediate("正在处理")
        transitionTo(SessionState.THINKING)
    }

    private fun startApprovalTimeout() {
        cancelApprovalTimeout()
        approvalTimeoutJob = scope.launch {
            kotlinx.coroutines.delay(APPROVAL_TIMEOUT_MS)
            if (pendingApprovalId != null) {
                Log.d(TAG, "Approval timeout, auto-deny")
                sttManager.stopListening()
                // Timeout — send /deny directly
                wsClient.sendMessage("/deny")
                pendingApprovalId = null
                approvalRetryCount = 0
                ttsManager.speakImmediate("超时未回复，已自动拒绝")
                transitionTo(SessionState.THINKING)
            }
        }
    }

    private fun cancelApprovalTimeout() {
        approvalTimeoutJob?.cancel()
        approvalTimeoutJob = null
    }

    // ──────────────────────────────────────────────────────────────────────
    // Event observers
    // ──────────────────────────────────────────────────────────────────────

    private fun observeStt() {
        sttJob = scope.launch {
            sttManager.events.collect { event ->
                when (event) {
                    is SttEvent.Result -> {
                        if (_state.value == SessionState.APPROVAL_WAITING) {
                            // In approval mode — persist the user's spoken reply, then send it for server-side LLM classification
                            _transcript.tryEmit(event.text)
                            handleApprovalSttResult(event.text)
                        } else {
                            // Normal conversation
                            Log.d(TAG, "STT result: ${event.text}, sending to WS")
                            _transcript.tryEmit(event.text)
                            transitionTo(SessionState.THINKING)
                            wsClient.sendMessage(event.text)
                            // 提示音：消息已发送
                            ttsManager.playBeep(500, 100)
                            // 预请求下一个 STT token（讯飞签名 URL 是一次性的）
                            wsClient.requestSttToken()
                        }
                    }
                    is SttEvent.PartialResult -> {
                        // partial result 不发送，只通知 UI 临时展示
                        _partial.tryEmit(event.text)
                    }
                    is SttEvent.Error -> {
                        Log.d(TAG, "STT error: ${event.message}")
                        if (_state.value == SessionState.APPROVAL_WAITING) {
                            // STT failed during approval — timeout will handle it
                            Log.d(TAG, "STT error during approval, waiting for timeout")
                        } else if (_state.value == SessionState.LISTENING) {
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
                        if (_state.value == SessionState.APPROVAL_WAITING) {
                            // Approval prompt finished — start listening for user's response
                            startApprovalListening()
                        } else if (_state.value == SessionState.SPEAKING) {
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
                        // 连接成功后请求 STT 凭据（预备第一次使用）
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
                    is WsEvent.ApprovalRequest -> {
                        handleApprovalRequest(event.approvalId, event.command, event.description)
                    }
                    is WsEvent.ApprovalClarify -> {
                        handleApprovalClarify(event.approvalId, event.message)
                    }
                    is WsEvent.PairingRequired -> {
                        handlePairingRequired(event.code, event.message)
                    }
                    is WsEvent.PairingApproved -> {
                        handlePairingApproved()
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

    private fun handlePairingRequired(code: String, message: String) {
        Log.d(TAG, "Pairing required: code=$code")
        cancelApprovalTimeout()
        pendingApprovalId = null
        sttManager.stopListening()
        ttsManager.stop()
        transitionTo(SessionState.PAIRING_WAITING)
        ttsManager.speakImmediate(message.ifBlank { "设备未授权，请在服务端批准配对" })
        startPairingPolling()
    }

    private fun startPairingPolling() {
        pairingPollJob?.cancel()
        pairingPollJob = scope.launch {
            repeat(PAIRING_MAX_POLLS) {
                kotlinx.coroutines.delay(PAIRING_POLL_INTERVAL_MS)
                wsClient.sendPairingStatus()
            }
            Log.d(TAG, "Pairing polling timed out")
            transitionTo(SessionState.IDLE)
            ttsManager.speakImmediate("等待授权超时，请重新发送消息")
            audioFocusManager.releaseFocus()
        }
    }

    private fun handlePairingApproved() {
        Log.d(TAG, "Pairing approved")
        stopPairingPolling()
        transitionTo(SessionState.THINKING)
        ttsManager.speakImmediate("授权成功，正在继续")
    }

    private fun stopPairingPolling() {
        pairingPollJob?.cancel()
        pairingPollJob = null
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
