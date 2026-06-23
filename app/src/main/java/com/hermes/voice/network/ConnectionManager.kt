package com.hermes.voice.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 管理 WebSocket 连接生命周期：自动重连 + 指数退避
 */
@Singleton
class ConnectionManager @Inject constructor(
    private val wsClient: VoiceWebSocketClient,
    private val config: ApiConfig
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null
    private var observeJob: Job? = null
    private var retryCount = 0

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    companion object {
        private const val INITIAL_DELAY_MS = 1000L
        private const val MAX_DELAY_MS = 30000L
    }

    fun start() {
        if (!config.isConfigured) return
        if (_connectionState.value == ConnectionState.CONNECTED || _connectionState.value == ConnectionState.CONNECTING) return
        _connectionState.value = ConnectionState.CONNECTING
        observeEvents()
        wsClient.connect()
    }

    fun stop() {
        reconnectJob?.cancel()
        wsClient.disconnect()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun observeEvents() {
        if (observeJob != null) return
        observeJob = scope.launch {
            wsClient.events.collect { event ->
                when (event) {
                    is WsEvent.Connected -> {
                        retryCount = 0
                        _connectionState.value = ConnectionState.CONNECTED
                    }
                    is WsEvent.Disconnected -> {
                        _connectionState.value = ConnectionState.DISCONNECTED
                        scheduleReconnect()
                    }
                    is WsEvent.AuthFailed -> {
                        _connectionState.value = ConnectionState.AUTH_FAILED
                        // 鉴权失败不重连，需要用户修改配置
                    }
                    else -> {}
                }
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delay = (INITIAL_DELAY_MS * (1L shl minOf(retryCount, 5)))
                .coerceAtMost(MAX_DELAY_MS)
            delay(delay)
            retryCount++
            _connectionState.value = ConnectionState.CONNECTING
            wsClient.connect()
        }
    }
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    AUTH_FAILED
}
