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
import java.util.concurrent.atomic.AtomicInteger

/**
 * 讯飞中文识别大模型 WebSocket 客户端（domain=slm）
 * 接口：wss://iat.xf-yun.com/v1（鉴权参数在 URL 里）
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
    }

    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private var isFirstFrame = true
    private var isClosed = false
    private val seqCounter = AtomicInteger(0)
    private val sentences = mutableMapOf<Int, String>() // sn → 文字

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun connect() {
        Log.d(TAG, "Connecting to xfyun: ${wsUrl.take(80)}...")
        val request = Request.Builder().url(wsUrl).build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "Connected to xfyun")
                isFirstFrame = true
                isClosed = false
                seqCounter.set(0)
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
        val seq = seqCounter.incrementAndGet()

        val json = if (isFirstFrame) {
            isFirstFrame = false
            // 首帧：带 header + parameter + payload
            """{"header":{"app_id":"$appId","status":0},"parameter":{"iat":{"domain":"slm","language":"zh_cn","accent":"mandarin","eos":6000,"dwa":"wpgs","result":{"encoding":"utf8","compress":"raw","format":"json"}}},"payload":{"audio":{"encoding":"raw","sample_rate":16000,"channels":1,"bit_depth":16,"seq":$seq,"status":0,"audio":"$audio"}}}"""
        } else {
            // 中间帧：只需 header + payload
            """{"header":{"app_id":"$appId","status":1},"payload":{"audio":{"encoding":"raw","sample_rate":16000,"channels":1,"bit_depth":16,"seq":$seq,"status":1,"audio":"$audio"}}}"""
        }

        webSocket?.send(json)
    }

    /**
     * 发送结束帧
     */
    fun sendEndFrame() {
        if (isClosed || webSocket == null) return
        val seq = seqCounter.incrementAndGet()
        val json = """{"header":{"app_id":"$appId","status":2},"payload":{"audio":{"encoding":"raw","sample_rate":16000,"channels":1,"bit_depth":16,"seq":$seq,"status":2,"audio":""}}}"""
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
            val header = json.getAsJsonObject("header") ?: return
            val code = header.get("code")?.asInt ?: 0

            if (code != 0) {
                val msg = header.get("message")?.asString ?: "unknown error"
                Log.e(TAG, "Error from xfyun: code=$code, msg=$msg")
                onError("讯飞错误: $msg")
                close()
                return
            }

            val status = header.get("status")?.asInt ?: 0
            val payload = json.getAsJsonObject("payload") ?: return
            val result = payload.getAsJsonObject("result") ?: return

            // text 是 base64 编码的 JSON
            val textBase64 = result.get("text")?.asString ?: return
            val textJson = String(Base64.decode(textBase64, Base64.DEFAULT))

            // 解析识别结果 JSON
            val resultObj = gson.fromJson(textJson, JsonObject::class.java)
            val ws = resultObj.getAsJsonArray("ws") ?: return
            val sn = resultObj.get("sn")?.asInt ?: 0
            val pgs = resultObj.get("pgs")?.asString

            val segmentText = StringBuilder()
            for (i in 0 until ws.size()) {
                val cw = ws[i].asJsonObject.getAsJsonArray("cw")
                for (j in 0 until cw.size()) {
                    segmentText.append(cw[j].asJsonObject.get("w").asString)
                }
            }

            Log.d(TAG, "sn=$sn pgs=$pgs segment='$segmentText' status=$status")

            if (pgs == "rpl") {
                val rg = resultObj.getAsJsonArray("rg")
                if (rg != null && rg.size() >= 2) {
                    val start = rg[0].asInt
                    val end = rg[1].asInt
                    for (i in start..end) {
                        sentences.remove(i)
                    }
                }
            }
            sentences[sn] = segmentText.toString()

            val fullText = sentences.toSortedMap().values.joinToString("")
            onPartialResult(fullText)

            if (status == 2) {
                Log.d(TAG, "Final result: $fullText")
                onFinalResult(fullText)
                close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}", e)
        }
    }
}
