package com.hermes.voice.network

import com.google.gson.Gson
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import android.util.Log
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceWebSocketClient @Inject constructor(
    private val config: ApiConfig
) {
    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private var authenticated = false

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS) // 服务端发 ping，我们回 pong
        .build()

    private val _events = MutableSharedFlow<WsEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<WsEvent> = _events

    val isConnected: Boolean
        get() = webSocket != null && authenticated

    fun connect() {
        if (webSocket != null) return
        Log.d("VoiceWS", "connect() called, url=${config.wsUrl}")

        val request = Request.Builder()
            .url(config.wsUrl)
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d("VoiceWS", "onOpen, sending auth")
                // 连接成功，立刻发鉴权
                val auth = AuthMessage(token = config.voiceToken, deviceId = config.deviceId)
                ws.send(gson.toJson(auth))
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d("VoiceWS", "onMessage: $text")
                handleMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.d("VoiceWS", "onClosing: $code $reason")
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d("VoiceWS", "onClosed: $code $reason")
                webSocket = null
                authenticated = false
                _events.tryEmit(WsEvent.Disconnected(reason))
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e("VoiceWS", "onFailure: ${t.message}", t)
                webSocket = null
                authenticated = false
                _events.tryEmit(WsEvent.Error("连接失败: ${t.message}"))
                _events.tryEmit(WsEvent.Disconnected(t.message ?: "unknown"))
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "client disconnect")
        webSocket = null
        authenticated = false
    }

    fun sendMessage(text: String) {
        if (!authenticated) {
            _events.tryEmit(WsEvent.Error("未连接"))
            return
        }
        val msg = TextMessage(text = text)
        webSocket?.send(gson.toJson(msg))
    }

    fun sendApprovalResponse(approvalId: String, choice: String) {
        if (!authenticated) return
        val msg = ApprovalResponseMessage(approvalId = approvalId, choice = choice)
        webSocket?.send(gson.toJson(msg))
    }

    fun sendCommand(cmd: String) {
        if (!authenticated) return
        val msg = CommandMessage(cmd = cmd)
        webSocket?.send(gson.toJson(msg))
    }

    fun requestSttToken() {
        if (!authenticated) return
        val msg = RequestSttTokenMessage()
        webSocket?.send(gson.toJson(msg))
    }

    private fun handleMessage(text: String) {
        val msg = try {
            gson.fromJson(text, ServerMessage::class.java)
        } catch (e: Exception) {
            return
        }

        when (msg.type) {
            "auth_ok" -> {
                authenticated = true
                _events.tryEmit(WsEvent.Connected)
            }
            "auth_fail" -> {
                authenticated = false
                _events.tryEmit(WsEvent.AuthFailed(msg.reason ?: "认证失败"))
            }
            "delta" -> {
                msg.content?.let { _events.tryEmit(WsEvent.Delta(it)) }
            }
            "end" -> {
                _events.tryEmit(WsEvent.End(msg.finishReason ?: "stop"))
            }
            "tool_start" -> {
                _events.tryEmit(WsEvent.ToolStart(msg.name ?: "", msg.description ?: ""))
            }
            "tool_end" -> {
                _events.tryEmit(WsEvent.ToolEnd(msg.name ?: "", msg.duration ?: 0.0))
            }
            "approval_request" -> {
                _events.tryEmit(WsEvent.ApprovalRequest(
                    approvalId = msg.approvalId ?: "",
                    command = msg.command ?: "",
                    description = msg.description ?: ""
                ))
            }
            "task_complete" -> {
                _events.tryEmit(WsEvent.TaskComplete(msg.task ?: "", msg.success ?: false))
            }
            "busy" -> {
                _events.tryEmit(WsEvent.Busy(msg.message ?: ""))
            }
            "system" -> {
                msg.content?.let { _events.tryEmit(WsEvent.System(it)) }
            }
            "display" -> {
                msg.content?.let { _events.tryEmit(WsEvent.Display(it)) }
            }
            "stt_token" -> {
                if (!msg.url.isNullOrBlank()) {
                    _events.tryEmit(WsEvent.SttToken(
                        provider = msg.provider ?: "",
                        url = msg.url,
                        expiresIn = msg.expiresIn ?: 300,
                        appId = msg.appId ?: ""
                    ))
                } else {
                    _events.tryEmit(WsEvent.Error("STT 凭据获取失败: ${msg.error}"))
                }
            }
            "ping" -> {
                webSocket?.send(gson.toJson(PongMessage()))
            }
            "error" -> {
                _events.tryEmit(WsEvent.Error(msg.message ?: "未知错误"))
            }
        }
    }
}

sealed class WsEvent {
    data object Connected : WsEvent()
    data class Disconnected(val reason: String) : WsEvent()
    data class AuthFailed(val reason: String) : WsEvent()
    data class Delta(val content: String) : WsEvent()
    data class End(val finishReason: String) : WsEvent()
    data class ToolStart(val name: String, val description: String) : WsEvent()
    data class ToolEnd(val name: String, val duration: Double) : WsEvent()
    data class ApprovalRequest(val approvalId: String, val command: String, val description: String) : WsEvent()
    data class TaskComplete(val task: String, val success: Boolean) : WsEvent()
    data class Busy(val message: String) : WsEvent()
    data class System(val content: String) : WsEvent()
    data class Display(val content: String) : WsEvent()
    data class SttToken(val provider: String, val url: String, val expiresIn: Int, val appId: String) : WsEvent()
    data class Error(val message: String) : WsEvent()
}
