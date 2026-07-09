package com.hermes.voice.network

import com.google.gson.annotations.SerializedName

// === App → 服务端 ===

data class AuthMessage(
    val type: String = "auth",
    val token: String,
    @SerializedName("device_id")
    val deviceId: String
)

data class TextMessage(
    val type: String = "message",
    val text: String
)

data class ApprovalResponseMessage(
    val type: String = "approval_response",
    @SerializedName("approval_id")
    val approvalId: String,
    val choice: String // "once" | "session" | "always" | "deny"
)

data class ApprovalReplyMessage(
    val type: String = "approval_reply",
    @SerializedName("approval_id")
    val approvalId: String,
    val text: String // 用户原始语音文本，由服务端 LLM 分类
)

data class CommandMessage(
    val type: String = "command",
    val cmd: String // "stop" | "new"
)

data class RequestSttTokenMessage(
    val type: String = "request_stt_token"
)

data class PairingStatusMessage(
    val type: String = "pairing_status"
)

data class PongMessage(
    val type: String = "pong"
)

// === 服务端 → App ===

data class ServerMessage(
    val type: String,
    // auth
    val reason: String? = null,
    // delta
    val content: String? = null,
    // end
    @SerializedName("finish_reason")
    val finishReason: String? = null,
    // tool
    val name: String? = null,
    val description: String? = null,
    val duration: Double? = null,
    // approval
    @SerializedName("approval_id")
    val approvalId: String? = null,
    val command: String? = null,
    // task_complete
    val task: String? = null,
    val success: Boolean? = null,
    // pairing / busy / error
    val code: String? = null,
    val message: String? = null,
    // stt_token
    val provider: String? = null,
    val url: String? = null,
    @SerializedName("expires_in")
    val expiresIn: Int? = null,
    @SerializedName("app_id")
    val appId: String? = null,
    val error: String? = null,
)
