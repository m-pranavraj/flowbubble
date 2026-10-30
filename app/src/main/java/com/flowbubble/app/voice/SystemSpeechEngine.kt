package com.flowbubble.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Android's built-in recognizer. Usually network-backed, always present on GMS devices. */
class SystemSpeechEngine(private val context: Context) : TranscriptionEngine {

    override val kind = EngineKind.SYSTEM
    override var listener: TranscriptionListener? = null

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var lastPartial = ""

    override fun isAvailable(language: String): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    override fun start(language: String): Boolean {
        if (!isAvailable(language)) {
            listener?.onError("Speech recognition isn't available on this device.", TranscriptionFailure.ENGINE)
            return false
        }
        if (recognizer == null) {
            recognizer = runCatching {
                SpeechRecognizer.createSpeechRecognizer(context).also {
                    it.setRecognitionListener(recognitionListener)
                }
            }.getOrNull()
        }
        val engine = recognizer ?: run {
            listener?.onError("Couldn't start speech recognition.", TranscriptionFailure.ENGINE)
            return false
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        lastPartial = ""
        listening = true
        return runCatching {
            engine.startListening(intent)
            true
        }.getOrElse {
            listening = false
            listener?.onError("Couldn't start listening. Try again.", TranscriptionFailure.ENGINE)
            false
        }
    }

    override fun stop() {
        if (listening) {
            runCatching { recognizer?.stopListening() }
        }
    }

    override fun cancel() {
        listening = false
        lastPartial = ""
        runCatching { recognizer?.cancel() }
    }

    override fun destroy() {
        listening = false
        runCatching { recognizer?.destroy() }
        recognizer = null
        listener = null
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            listener?.onReady()
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            val level = ((rmsdB.coerceIn(-2f, 10f) + 2f) / 12f)
            listener?.onAmplitude(level)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            listening = false
            val partial = lastPartial.trim()
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (partial.isNotEmpty()) {
                        lastPartial = ""
                        listener?.onFinal(partial)
                    } else {
                        listener?.onError("I didn't catch that. Try again.", TranscriptionFailure.NO_SPEECH)
                    }
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    listener?.onError("Microphone permission is missing.", TranscriptionFailure.PERMISSION)
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    listener?.onError("Speech recognition needs a working network connection.", TranscriptionFailure.NETWORK)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    listener?.onError("Speech recognition is busy. Try again.", TranscriptionFailure.BUSY)
                SpeechRecognizer.ERROR_CLIENT ->
                    listener?.onError("Listening was interrupted.", TranscriptionFailure.ENGINE)
                else ->
                    listener?.onError("Speech recognition failed. Try again.", TranscriptionFailure.ENGINE)
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val best = texts?.firstOrNull { it.isNotBlank() }?.trim()
                ?: lastPartial.trim()
            lastPartial = ""
            if (best.isNotEmpty()) {
                listener?.onFinal(best)
            } else {
                listener?.onError("I didn't catch that. Try again.", TranscriptionFailure.NO_SPEECH)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = texts?.firstOrNull { it.isNotBlank() }?.trim() ?: return
            lastPartial = partial
            listener?.onPartial(partial)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
