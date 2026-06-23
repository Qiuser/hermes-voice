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
                minSilenceDuration = 2.0f,  // 2秒静默才视为说完
                minSpeechDuration = 0.5f,   // 至少0.5秒才算有效语音
                maxSpeechDuration = 15.0f,  // 最多录15秒
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

        Log.d(TAG, "startListening()")
        _events.tryEmit(SttEvent.Ready)

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
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
            val maxSilenceFrames = (SAMPLE_RATE * 5) / (bufferSize / 2) // ~5秒无声超时（未说话时）

            // 预缓冲区：保留最近 1 秒的音频，防止开头被截
            val preBufferFrames = (SAMPLE_RATE * 1.0f).toInt() / (bufferSize / 2) + 1
            val preBuffer = ArrayDeque<FloatArray>(preBufferFrames + 1)

            while (isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read <= 0) continue

                // 转为 float [-1, 1]
                val samples = FloatArray(read) { buffer[it] / 32768.0f }

                // 送入 VAD
                vad?.acceptWaveform(samples)

                if (vad?.isSpeechDetected() == true) {
                    if (!speechDetected) {
                        speechDetected = true
                        // 把预缓冲区的音频加进来（包含开头部分）
                        for (frame in preBuffer) {
                            allSamples.addAll(frame.toList())
                        }
                        preBuffer.clear()
                    }
                    silenceFrames = 0
                    allSamples.addAll(samples.toList())
                } else if (speechDetected) {
                    allSamples.addAll(samples.toList())
                    // VAD 认为说完了（静默超过 minSilenceDuration）
                    if (!vad!!.empty()) {
                        // VAD 内部已经分好了一段完整语音
                        break
                    }
                } else {
                    // 还没开始说话，维护预缓冲区
                    preBuffer.addLast(samples.clone())
                    if (preBuffer.size > preBufferFrames) {
                        preBuffer.removeFirst()
                    }
                    silenceFrames++
                    if (silenceFrames >= maxSilenceFrames) {
                        // 一直没说话，超时
                        Log.d(TAG, "No speech detected, timeout")
                        _events.tryEmit(SttEvent.Error(0, "语音超时"))
                        stopRecording()
                        return@launch
                    }
                }
            }

            // 停止录音
            stopRecording()
            _events.tryEmit(SttEvent.SpeechEnd)

            // 执行识别
            if (allSamples.isNotEmpty()) {
                val result = recognize(allSamples.toFloatArray())
                if (result.isNotBlank()) {
                    Log.d(TAG, "Recognition result: $result")
                    _events.tryEmit(SttEvent.Result(result))
                } else {
                    _events.tryEmit(SttEvent.Error(-1, "未识别到内容"))
                }
            } else {
                _events.tryEmit(SttEvent.Error(-1, "未识别到语音"))
            }

            // 重置 VAD
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
