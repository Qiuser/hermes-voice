package com.hermes.voice.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.VadModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpeechRecognizerManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "VoiceSTT"
        private const val SAMPLE_RATE = 16000
        private const val MODEL_DIR = "sherpa-onnx"
    }

    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val _events = MutableSharedFlow<SttEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<SttEvent> = _events

    private var isInitialized = false

    // 讯飞在线 STT
    private var xfyunUrl: String? = null
    private var xfyunAppId: String? = null
    private var tokenSetTime: Long = 0

    /**
     * 设置讯飞在线 STT 凭据
     */
    fun setSttToken(url: String, appId: String) {
        xfyunUrl = url
        xfyunAppId = appId
        tokenSetTime = System.currentTimeMillis()
        Log.d(TAG, "Xfyun STT token set, appId=$appId")
    }

    fun hasSttToken(): Boolean {
        if (xfyunUrl.isNullOrBlank()) return false
        // 检查是否过期（4分钟刷新，留 1 分钟余量）
        val elapsed = System.currentTimeMillis() - tokenSetTime
        if (elapsed > 4 * 60 * 1000) {
            Log.d(TAG, "STT token expired, falling back to offline")
            return false
        }
        return true
    }

    fun initialize() {
        if (isInitialized) return
        try {
            initRecognizer()
            initVad()
            isInitialized = true
            Log.d(TAG, "Sherpa-ONNX initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Sherpa-ONNX", e)
            _events.tryEmit(SttEvent.Error(-1, "语音引擎初始化失败: ${e.message}"))
        }
    }

    private fun initRecognizer() {
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = "$MODEL_DIR/model.int8.onnx",
                    language = "zh",
                    useInverseTextNormalization = true,
                ),
                tokens = "$MODEL_DIR/tokens.txt",
                numThreads = 2,
                debug = false,
            ),
            decodingMethod = "greedy_search",
        )
        recognizer = OfflineRecognizer(
            assetManager = context.assets,
            config = config,
        )
    }

    private fun initVad() {
        val config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "$MODEL_DIR/silero_vad.onnx",
                minSilenceDuration = 2.0f,
                minSpeechDuration = 0.5f,
                maxSpeechDuration = 15.0f,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        )
        vad = Vad(
            assetManager = context.assets,
            config = config,
        )
    }

    fun startListening() {
        if (!isInitialized) {
            initialize()
            if (!isInitialized) return
        }

        Log.d(TAG, "startListening(), online=${hasSttToken()}")
        _events.tryEmit(SttEvent.Ready)

        if (hasSttToken()) {
            startOnlineRecognition()
        } else {
            startOfflineRecognition()
        }
    }

    // ========== 在线模式（讯飞）==========

    private fun startOnlineRecognition() {
        val url = xfyunUrl ?: return
        val appId = xfyunAppId ?: return

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            _events.tryEmit(SttEvent.Error(-1, "录音初始化失败"))
            return
        }

        audioRecord?.startRecording()
        _events.tryEmit(SttEvent.SpeechStart)

        var xfyunClient: XfyunSttClient? = null
        var finalResult: String? = null
        var hasError = false

        xfyunClient = XfyunSttClient(
            wsUrl = url,
            appId = appId,
            onPartialResult = { text ->
                _events.tryEmit(SttEvent.PartialResult(text))
            },
            onFinalResult = { text ->
                finalResult = text
            },
            onError = { msg ->
                hasError = true
                Log.e(TAG, "Xfyun error: $msg")
            }
        )

        xfyunClient.connect()

        recordingJob = scope.launch {
            val buffer = ShortArray(640) // 40ms @16kHz
            var speechDetected = false
            var silenceFrames = 0
            val maxSilenceFrames = (SAMPLE_RATE * 5) / 640 // 5秒无声超时

            // 预缓冲
            val preBufferFrames = (SAMPLE_RATE * 1.0f).toInt() / 640 + 1
            val preBuffer = ArrayDeque<ShortArray>(preBufferFrames + 1)

            while (isActive && !hasError) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read <= 0) continue

                // VAD 检测
                val samples = FloatArray(read) { buffer[it] / 32768.0f }
                vad?.acceptWaveform(samples)

                if (vad?.isSpeechDetected() == true) {
                    if (!speechDetected) {
                        speechDetected = true
                        // 发送预缓冲的音频
                        for (frame in preBuffer) {
                            xfyunClient.sendAudioFrame(frame, frame.size)
                        }
                        preBuffer.clear()
                    }
                    silenceFrames = 0
                    xfyunClient.sendAudioFrame(buffer.copyOf(read), read)
                } else if (speechDetected) {
                    xfyunClient.sendAudioFrame(buffer.copyOf(read), read)
                    if (!vad!!.empty()) {
                        // VAD 判定说话结束
                        break
                    }
                } else {
                    // 未说话，维护预缓冲
                    preBuffer.addLast(buffer.copyOf(read))
                    if (preBuffer.size > preBufferFrames) {
                        preBuffer.removeFirst()
                    }
                    silenceFrames++
                    if (silenceFrames >= maxSilenceFrames) {
                        Log.d(TAG, "No speech detected, timeout")
                        _events.tryEmit(SttEvent.Error(0, "语音超时"))
                        xfyunClient.close()
                        stopRecording()
                        vad?.reset()
                        return@launch
                    }
                }
            }

            // 发送结束帧
            stopRecording()
            _events.tryEmit(SttEvent.SpeechEnd)
            xfyunClient.sendEndFrame()

            // 等待最终结果（最多 5 秒）
            var waitMs = 0
            while (finalResult == null && !hasError && waitMs < 5000) {
                kotlinx.coroutines.delay(100)
                waitMs += 100
            }

            vad?.reset()

            if (hasError) {
                // 讯飞出错，降级到本地
                Log.d(TAG, "Xfyun failed, no fallback audio available")
                _events.tryEmit(SttEvent.Error(-1, "在线识别失败"))
            } else if (finalResult != null && finalResult!!.isNotBlank()) {
                Log.d(TAG, "Xfyun result: $finalResult")
                _events.tryEmit(SttEvent.Result(finalResult!!))
            } else {
                _events.tryEmit(SttEvent.Error(-1, "未识别到内容"))
            }

            xfyunClient.close()
        }
    }

    // ========== 离线模式（SenseVoice）==========

    private fun startOfflineRecognition() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            _events.tryEmit(SttEvent.Error(-1, "录音初始化失败"))
            return
        }

        audioRecord?.startRecording()
        _events.tryEmit(SttEvent.SpeechStart)

        recordingJob = scope.launch {
            val buffer = ShortArray(bufferSize / 2)
            val allSamples = mutableListOf<Float>()
            var speechDetected = false
            var silenceFrames = 0
            val maxSilenceFrames = (SAMPLE_RATE * 5) / (bufferSize / 2)

            val preBufferFrames = (SAMPLE_RATE * 1.0f).toInt() / (bufferSize / 2) + 1
            val preBuffer = ArrayDeque<FloatArray>(preBufferFrames + 1)

            while (isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read <= 0) continue

                val samples = FloatArray(read) { buffer[it] / 32768.0f }
                vad?.acceptWaveform(samples)

                if (vad?.isSpeechDetected() == true) {
                    if (!speechDetected) {
                        speechDetected = true
                        for (frame in preBuffer) {
                            allSamples.addAll(frame.toList())
                        }
                        preBuffer.clear()
                    }
                    silenceFrames = 0
                    allSamples.addAll(samples.toList())
                } else if (speechDetected) {
                    allSamples.addAll(samples.toList())
                    if (!vad!!.empty()) {
                        break
                    }
                } else {
                    preBuffer.addLast(samples.clone())
                    if (preBuffer.size > preBufferFrames) {
                        preBuffer.removeFirst()
                    }
                    silenceFrames++
                    if (silenceFrames >= maxSilenceFrames) {
                        Log.d(TAG, "No speech detected, timeout")
                        _events.tryEmit(SttEvent.Error(0, "语音超时"))
                        stopRecording()
                        vad?.reset()
                        return@launch
                    }
                }
            }

            stopRecording()
            _events.tryEmit(SttEvent.SpeechEnd)

            if (allSamples.isNotEmpty()) {
                val result = recognize(allSamples.toFloatArray())
                if (result.isNotBlank()) {
                    Log.d(TAG, "Offline result: $result")
                    _events.tryEmit(SttEvent.Result(result))
                } else {
                    _events.tryEmit(SttEvent.Error(-1, "未识别到内容"))
                }
            } else {
                _events.tryEmit(SttEvent.Error(-1, "未识别到语音"))
            }

            vad?.reset()
        }
    }

    private fun recognize(samples: FloatArray): String {
        val rec = recognizer ?: return ""
        val stream = rec.createStream()
        stream.acceptWaveform(samples, SAMPLE_RATE)
        rec.decode(stream)
        val result = rec.getResult(stream)
        stream.release()
        return result.text.trim()
    }

    fun stopListening() {
        recordingJob?.cancel()
        recordingJob = null
        stopRecording()
    }

    private fun stopRecording() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        }
        audioRecord = null
    }

    fun destroy() {
        stopListening()
        recognizer?.release()
        recognizer = null
        vad?.release()
        vad = null
        isInitialized = false
    }
}
