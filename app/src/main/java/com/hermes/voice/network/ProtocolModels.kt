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

data class CommandMessage(
    val type: String = "command",
    val cmd: String // "stop" | "new"
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
    // busy / error
    val message: String? = null
)
