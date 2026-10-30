package com.flowbubble.app.voice

/** Why a listening attempt stopped, so callers can decide whether switching engines could help. */
enum class TranscriptionFailure {
    NO_SPEECH,
    PERMISSION,
    NETWORK,
    BUSY,
    MODEL_MISSING,
    ENGINE
}

/**
 * Everything the rest of FlowBubble needs to know about an utterance, independent of
 * which backend produced it.
 */
interface TranscriptionListener {
    fun onReady()

    /** Emitted whenever an engine actually begins (or takes over) listening. */
    fun onEngine(kind: EngineKind, note: String?)

    fun onPartial(text: String)
    fun onFinal(text: String)
    fun onAmplitude(level: Float)
    fun onError(message: String, failure: TranscriptionFailure)
}

enum class EngineKind(val label: String) {
    LOCAL_MODEL("on-device model"),
    SYSTEM("system recognizer")
}

/** User-facing speech engine policy. AUTO never downloads a model on its own. */
enum class EngineChoice(val id: String, val label: String) {
    AUTO("auto", "Automatic"),
    LOCAL("local", "On-device model"),
    SYSTEM("system", "System recognizer");

    companion object {
        fun from(id: String?): EngineChoice = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

interface TranscriptionEngine {
    val kind: EngineKind

    var listener: TranscriptionListener?

    /** Cheap, synchronous answer used to pick an engine before the user is kept waiting. */
    fun isAvailable(language: String): Boolean

    fun start(language: String): Boolean
    fun stop()
    fun cancel()
    fun destroy()
}
