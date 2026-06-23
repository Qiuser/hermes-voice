package com.hermes.voice.audio

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * 讯飞实时语音听写 WebSocket 客户端
 * 边录音边发送音频帧，实时返回识别结果
 */
class XfyunSttClient(
    private val wsUrl: String,
    private val appId: String,
    private val onPartialResult: (String) -> Unit,
    private val onFinalResult: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "XfyunSTT"
        private const val FRAME_SIZE = 1280 // 每帧 1280 字节 = 40ms @16kHz 16bit
    }

    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private var isFirstFrame = true
    private var isClosed = false
    private val resultBuilder = StringBuilder()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun connect() {
        Log.d(TAG, "Connecting to xfyun: ${wsUrl.take(80)}...")
        val request = Request.Builder().url(wsUrl).build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "Connected to xfyun")
                isFirstFrame = true
                isClosed = false
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleResponse(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Connection failed: ${t.message}")
                if (!isClosed) {
                    isClosed = true
                    onError("讯飞连接失败: ${t.message}")
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Connection closed: $code $reason")
            }
        })
    }

    /**
     * 发送一帧音频数据（PCM 16kHz 16bit mono）
     */
    fun sendAudioFrame(pcmData: ShortArray, size: Int) {
        if (isClosed || webSocket == null) return

        // short[] 转 byte[]
        val bytes = ByteArray(size * 2)
        for (i in 0 until size) {
            bytes[i * 2] = (pcmData[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (pcmData[i].toInt() shr 8 and 0xFF).toByte()
        }

        val audio = Base64.encodeToString(bytes, Base64.NO_WRAP)

        val json = if (isFirstFrame) {
            isFirstFrame = false
            """{"common":{"app_id":"$appId"},"business":{"language":"zh_cn","domain":"iat","accent":"mandarin","ptt":1,"vad_eos":3000},"data":{"status":0,"format":"audio/L16;rate=16000","encoding":"raw","audio":"$audio"}}"""
        } else {
            """{"data":{"status":1,"format":"audio/L16;rate=16000","encoding":"raw","audio":"$audio"}}"""
        }

        webSocket?.send(json)
    }

    /**
     * 发送结束帧，告知讯飞录音结束
     */
    fun sendEndFrame() {
        if (isClosed || webSocket == null) return
        val json = """{"data":{"status":2,"format":"audio/L16;rate=16000","encoding":"raw","audio":""}}"""
        webSocket?.send(json)
        Log.d(TAG, "End frame sent")
    }

    fun close() {
        isClosed = true
        webSocket?.close(1000, "done")
        webSocket = null
    }

    private fun handleResponse(text: String) {
        try {
            val json = gson.fromJson(text, JsonObject::class.java)
            val code = json.get("code")?.asInt ?: 0
            if (code != 0) {
                val msg = json.get("message")?.asString ?: "unknown error"
                Log.e(TAG, "Error from xfyun: code=$code, msg=$msg")
                onError("讯飞错误: $msg")
                close()
                return
            }

            val data = json.getAsJsonObject("data") ?: return
            val status = data.get("status")?.asInt ?: 0
            val result = data.getAsJsonObject("result") ?: return

            // 解析识别文字
            val ws = result.getAsJsonArray("ws") ?: return
            val segmentText = StringBuilder()
            for (i in 0 until ws.size()) {
                val cw = ws[i].asJsonObject.getAsJsonArray("cw")
                for (j in 0 until cw.size()) {
                    segmentText.append(cw[j].asJsonObject.get("w").asString)
                }
            }

            // 拼接完整结果
            // 讯飞的 result 里有 pgs 字段：rpl=替换之前的，apd=追加
            val pgs = result.get("pgs")?.asString
            if (pgs == "rpl") {
                // 替换模式：清除之前的片段重新拼接
                // sn 表示第几句
            }
            // 简单处理：直接用累积方式
            resultBuilder.append(segmentText)

            val currentText = resultBuilder.toString()
            onPartialResult(currentText)

            if (status == 2) {
                // 最终结果
                Log.d(TAG, "Final result: $currentText")
                onFinalResult(currentText)
                close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
        }
    }
}
