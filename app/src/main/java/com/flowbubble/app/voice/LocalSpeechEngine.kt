package com.flowbubble.app.voice

import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerRequest
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Google ML Kit GenAI speech recognition — streaming, fully on-device once the model is installed. */
class LocalSpeechEngine(
    private val scope: CoroutineScope,
    private val model: LocalSpeechModel
) : TranscriptionEngine {

    override val kind = EngineKind.LOCAL_MODEL
    override var listener: TranscriptionListener? = null

    private var job: Job? = null
    private var recognizer: SpeechRecognizer? = null
    private var audioSource: AudioSource? = null
    private var lastPartial = ""
    private var settled = false

    override fun isAvailable(language: String): Boolean = model.isReadyNow(language)

    override fun start(language: String): Boolean {
        if (job?.isActive == true) return true
        val client = model.clientFor(language) ?: return false
        recognizer = client
        settled = false
        lastPartial = ""
        val source = runCatching { AudioSource.fromMic() }.getOrNull() ?: return false
        audioSource = source
        val request = SpeechRecognizerRequest.builder().apply { audioSource = source }.build()
        job = scope.launch {
            try {
                client.startRecognition(request).collect { response -> handle(response) }
                finishStream()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                failWith(error)
            }
        }
        return true
    }

    override fun stop() {
        val client = recognizer ?: return
        if (job?.isActive != true) return
        scope.launch { runCatching { client.stopRecognition() } }
    }

    override fun cancel() {
        settled = true
        lastPartial = ""
        job?.cancel()
        job = null
        releaseAudioSource()
    }

    override fun destroy() {
        cancel()
        recognizer = null
        listener = null
    }

    private suspend fun handle(response: SpeechRecognizerResponse) {
        when (response) {
            is SpeechRecognizerResponse.PartialTextResponse -> {
                val text = response.text.trim()
                if (text.isNotEmpty()) {
                    lastPartial = text
                    listener?.onPartial(text)
                }
            }

            is SpeechRecognizerResponse.FinalTextResponse -> {
                settled = true
                val text = response.text.trim()
                if (text.isNotEmpty()) listener?.onFinal(text) else emitEmpty()
            }

            // The SDK doesn't document the unit; it behaves as a 0..100 percentage meter.
            is SpeechRecognizerResponse.AudioLevelResponse ->
                listener?.onAmplitude(response.audioLevel.coerceIn(0, 100) / 100f)

            is SpeechRecognizerResponse.ErrorResponse -> {
                settled = true
                val error = response.e
                listener?.onError(describeSpeechError(error), failureOf(error))
            }

            is SpeechRecognizerResponse.CompletedResponse -> Unit
        }
    }

    private fun finishStream() {
        releaseAudioSource()
        job = null
        if (settled) return
        settled = true
        if (lastPartial.isNotEmpty()) {
            listener?.onFinal(lastPartial)
        } else {
            emitEmpty()
        }
        lastPartial = ""
    }

    private fun emitEmpty() {
        listener?.onError("I didn't catch that. Try again.", TranscriptionFailure.NO_SPEECH)
    }

    private fun fail(message: String, failure: TranscriptionFailure) {
        settled = true
        job = null
        releaseAudioSource()
        listener?.onError(message, failure)
    }

    private fun releaseAudioSource() {
        runCatching { audioSource?.close() }
        audioSource = null
    }

    private fun failWith(error: Throwable) {
        val message = if (error is GenAiException) {
            describeSpeechError(error)
        } else {
            error.message ?: "On-device recognition stopped unexpectedly."
        }
        fail(message, if (error is GenAiException) failureOf(error) else TranscriptionFailure.ENGINE)
    }

    private fun failureOf(error: GenAiException): TranscriptionFailure =
        if (error.errorCode == GenAiException.ErrorCode.BUSY) {
            TranscriptionFailure.BUSY
        } else {
            TranscriptionFailure.ENGINE
        }
}

