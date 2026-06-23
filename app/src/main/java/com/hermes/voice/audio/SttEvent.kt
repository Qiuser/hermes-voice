package com.hermes.voice.audio

sealed class SttEvent {
    data object Ready : SttEvent()
    data object SpeechStart : SttEvent()
    data object SpeechEnd : SttEvent()
    data class PartialResult(val text: String) : SttEvent()
    data class Result(val text: String) : SttEvent()
    data class Error(val code: Int, val message: String) : SttEvent()
}
