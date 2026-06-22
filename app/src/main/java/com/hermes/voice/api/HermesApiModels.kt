package com.hermes.voice.api

import com.google.gson.annotations.SerializedName

data class ChatMessage(
    val role: String,
    val content: String
)

data class ChatRequest(
    val model: String = "hermes-agent",
    val stream: Boolean = true,
    val messages: List<ChatMessage>
)

data class ChatStreamResponse(
    val id: String?,
    val choices: List<StreamChoice>?
)

data class StreamChoice(
    val delta: Delta?,
    @SerializedName("finish_reason")
    val finishReason: String?
)

data class Delta(
    val role: String?,
    val content: String?
)
