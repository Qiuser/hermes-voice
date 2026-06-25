package com.hermes.voice.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WakeWordDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "WakeWord"
        private const val KWS_DIR = "sherpa-onnx-kws"
        private const val SAMPLE_RATE = 16000
    }

    private var keywordSpotter: KeywordSpotter? = null
    private var audioRecord: AudioRecord? = null
    private var detectJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var isRunning = false
    private var onWakeWordDetected: (() -> Unit)? = null

    fun setOnWakeWordDetected(callback: () -> Unit) {
        onWakeWordDetected = callback
    }

    fun start() {
        if (isRunning) return
        Log.d(TAG, "Starting wake word detection")
        try {
            if (keywordSpotter == null) {
                initSpotter()
            }
            isRunning = true
            startDetecting()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start wake word detection", e)
        }
    }

    fun stop() {
        if (!isRunning) return
        Log.d(TAG, "Stopping wake word detection")
        isRunning = false
        detectJob?.cancel()
        detectJob = null
        stopRecording()
    }

    fun resume() {
        if (detectJob?.isActive == true) return
        if (!isRunning) isRunning = true
        startDetecting()
    }

    private fun initSpotter() {
        val config = KeywordSpotterConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$KWS_DIR/encoder.onnx",
                    decoder = "$KWS_DIR/decoder.onnx",
                    joiner = "$KWS_DIR/joiner.onnx",
                ),
                tokens = "$KWS_DIR/tokens.txt",
                numThreads = 1,
                debug = false,
            ),
            keywordsFile = "$KWS_DIR/keywords.txt",
            keywordsThreshold = 0.1f,
            keywordsScore = 1.5f,
        )
        keywordSpotter = KeywordSpotter(
            assetManager = context.assets,
            config = config,
        )
        Log.d(TAG, "KeywordSpotter initialized")
    }

    private fun startDetecting() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            return
        }

        audioRecord?.startRecording()

        val spotter = keywordSpotter ?: return
        val stream = spotter.createStream()

        detectJob = scope.launch {
            val buffer = ShortArray(1600) // 100ms @16kHz
            try {
                while (isActive && isRunning) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                    if (read <= 0) continue

                    val samples = FloatArray(read) { buffer[it] / 32768.0f }
                    stream.acceptWaveform(samples, SAMPLE_RATE)

                    if (spotter.isReady(stream)) {
                        spotter.decode(stream)

                        val result = spotter.getResult(stream)
                        if (result.keyword.isNotBlank()) {
                            Log.d(TAG, "Wake word detected: ${result.keyword}")
                            spotter.reset(stream)
                            stopRecording()
                            onWakeWordDetected?.invoke()
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Detection error: ${e.message}", e)
                stopRecording()
            }
        }
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
        stop()
        keywordSpotter?.release()
        keywordSpotter = null
    }
}
