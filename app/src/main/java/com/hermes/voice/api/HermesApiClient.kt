package com.hermes.voice.api

import com.google.gson.Gson
import com.hermes.voice.util.Constants
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HermesApiClient @Inject constructor(
    private val apiConfig: ApiConfig
) {
    private val gson = Gson()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // SSE 不超时
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val sseFactory = EventSources.createFactory(httpClient)

    fun streamChat(userMessage: String, history: List<ChatMessage> = emptyList()): Flow<StreamEvent> = callbackFlow {
        val messages = mutableListOf<ChatMessage>()
        messages.add(ChatMessage(role = "system", content = Constants.VOICE_SYSTEM_PROMPT))
        messages.addAll(history)
        messages.add(ChatMessage(role = "user", content = userMessage))

        val request = ChatRequest(messages = messages)
        val jsonBody = gson.toJson(request)

        val url = apiConfig.apiUrl.trimEnd('/') + "/v1/chat/completions"
        val httpRequest = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${apiConfig.apiToken}")
            .addHeader("Accept", "text/event-stream")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                trySend(StreamEvent.Connected)
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    trySend(StreamEvent.Done)
                    close()
                    return
                }

                try {
                    val parsed = gson.fromJson(data, ChatStreamResponse::class.java)
                    val content = parsed.choices?.firstOrNull()?.delta?.content
                    if (!content.isNullOrEmpty()) {
                        trySend(StreamEvent.Token(content))
                    }
                } catch (e: Exception) {
                    // 跳过解析失败的行
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val error = when {
                    response?.code == 401 -> StreamEvent.Error("认证失败，请检查 Token")
                    response?.code == 403 -> StreamEvent.Error("访问被拒绝")
                    response != null -> StreamEvent.Error("服务错误: ${response.code}")
                    t is IOException -> StreamEvent.Error("网络连接失败")
                    else -> StreamEvent.Error("未知错误: ${t?.message}")
                }
                trySend(error)
                close()
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val eventSource = sseFactory.newEventSource(httpRequest, listener)

        awaitClose {
            eventSource.cancel()
        }
    }
}

sealed class StreamEvent {
    data object Connected : StreamEvent()
    data class Token(val content: String) : StreamEvent()
    data object Done : StreamEvent()
    data class Error(val message: String) : StreamEvent()
}
