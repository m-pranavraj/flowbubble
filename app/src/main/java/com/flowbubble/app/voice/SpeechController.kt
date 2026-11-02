package com.flowbubble.app.voice

import android.content.Context
import com.flowbubble.app.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Picks the transcription engine for each utterance and keeps the user honestly informed about
 * which one is running. Preference is user-controlled and never triggers a model download.
 */
class SpeechController(private val context: Context) {

    var listener: TranscriptionListener? = null

    val engineKind: EngineKind? get() = active?.kind

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val model = LocalSpeechModel()
    private val system: TranscriptionEngine = SystemSpeechEngine(context)
    private val local: TranscriptionEngine = LocalSpeechEngine(scope, model)

    private var active: TranscriptionEngine? = null
    private var language = ""
    private var usedFallback = false

    private val router = object : TranscriptionListener {
        override fun onReady() {
            listener?.onReady()
        }

        override fun onEngine(kind: EngineKind, note: String?) {
            listener?.onEngine(kind, note)
        }

        override fun onPartial(text: String) {
            listener?.onPartial(text)
        }

        override fun onFinal(text: String) {
            active = null
            listener?.onFinal(text)
        }

        override fun onAmplitude(level: Float) {
            listener?.onAmplitude(level)
        }

        override fun onError(message: String, failure: TranscriptionFailure) {
            val current = active
            val worthRetrying = failure == TranscriptionFailure.ENGINE ||
                failure == TranscriptionFailure.NETWORK ||
                failure == TranscriptionFailure.BUSY
            val other = when (current) {
                local -> system
                system -> local
                else -> null
            }
            if (current == null || !worthRetrying || usedFallback ||
                other == null || !other.isAvailable(language) || !other.start(language)
            ) {
                active = null
                listener?.onError(message, failure)
                return
            }
            usedFallback = true
            current.cancel()
            active = other
            listener?.onEngine(other.kind, "$message — switched to the ${other.kind.label}. Say that again.")
        }
    }

    init {
        system.listener = router
        local.listener = router
    }

    /** Warms the model cache in the background so the next hold-to-talk chooses without stalling. */
    fun refreshEngineStatus(language: String) {
        scope.launch { model.refresh(language) }
    }

    fun modelState(language: String): ModelState = model.state(language)

    fun installModel(language: String, onProgress: (InstallEvent) -> Unit) {
        scope.launch { model.install(language, onProgress) }
    }

    fun start(language: String): Boolean {
        this.language = language
        usedFallback = false
        val choice = Prefs.engineChoice(context)
        if (choice == EngineChoice.LOCAL && !local.isAvailable(language)) {
            active = null
            listener?.onError(MISSING_MODEL_HINT, TranscriptionFailure.MODEL_MISSING)
            return false
        }
        val preferred = when (choice) {
            EngineChoice.SYSTEM -> system
            EngineChoice.LOCAL -> local
            EngineChoice.AUTO -> if (local.isAvailable(language)) local else system
        }
        if (preferred.start(language)) {
            active = preferred
            listener?.onEngine(preferred.kind, switchNote(choice, preferred.kind))
            return true
        }
        if (preferred !== system && system.isAvailable(language) && system.start(language)) {
            active = system
            listener?.onEngine(system.kind, "${preferred.kind.label} couldn't start — using the ${system.kind.label}.")
            return true
        }
        active = null
        return false
    }

    fun stop() {
        active?.stop()
    }

    fun cancel() {
        system.cancel()
        local.cancel()
        active = null
    }

    fun destroy() {
        active = null
        system.destroy()
        local.destroy()
        scope.cancel()
        model.close()
        listener = null
    }

    private fun switchNote(choice: EngineChoice, kind: EngineKind): String? =
        if (choice == EngineChoice.AUTO && kind == EngineKind.SYSTEM) MISSING_MODEL_HINT else null

    private companion object {
        const val MISSING_MODEL_HINT =
            "The on-device model isn't installed, so Your Bubble is using the system recognizer."
    }
}
