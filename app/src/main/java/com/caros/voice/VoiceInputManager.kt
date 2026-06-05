package com.caros.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

enum class VoiceListeningState { IDLE, LISTENING, PROCESSING, SPEAKING, ERROR }

@Singleton
class VoiceInputManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var recognizer: SpeechRecognizer? = null

    private val _state = MutableStateFlow(VoiceListeningState.IDLE)
    val state: StateFlow<VoiceListeningState> = _state

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText

    private val _results = Channel<String>(Channel.BUFFERED)
    val results: Flow<String> = _results.receiveAsFlow()

    fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit = {}) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Rozpoznávání řeči není dostupné")
            return
        }
        _state.value = VoiceListeningState.LISTENING
        _partialText.value = ""

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { _state.value = VoiceListeningState.PROCESSING }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: ""
                    _partialText.value = partial
                }
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: ""
                    _state.value = VoiceListeningState.IDLE
                    if (text.isNotBlank()) onResult(text)
                }
                override fun onError(error: Int) {
                    _state.value = VoiceListeningState.ERROR
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH      -> "Nic jsem neslyšel"
                        SpeechRecognizer.ERROR_NETWORK       -> "Chyba sítě"
                        SpeechRecognizer.ERROR_AUDIO         -> "Chyba mikrofonu"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Timeout"
                        else                                  -> "Chyba $error"
                    }
                    Timber.w("SpeechRecognizer error $error: $msg")
                    onError(msg)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "cs-CZ")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        recognizer?.startListening(intent)
    }

    fun stopListening() {
        recognizer?.stopListening()
        _state.value = VoiceListeningState.IDLE
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }
}
