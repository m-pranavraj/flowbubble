package com.flowbubble.app.voice

import com.flowbubble.app.core.LocalModelCatalog
import com.flowbubble.app.core.LocalModelSpec
import com.flowbubble.app.core.LocalModelStore
import com.flowbubble.app.core.LocalVerdict
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The on-device half of [LlmClient]. Same shape as the hosted call — a system prompt, the user's
 * text, a model reference, and a `Result<String>` back — so every feature that already asked a model
 * for something gets local support without touching its own code.
 *
 * It is a thin wrapper over Google's LiteRT-LM Android SDK (`com.google.ai.edge.litertlm.Engine`,
 * `EngineConfig`, `Conversation`), used exactly as the published Android guide describes: build an
 * `Engine` from a model path, `initialize()` it, open a `Conversation` per request with the system
 * prompt as its `systemInstruction`, and read the reply out of the returned `Message`.
 *
 * Three things are deliberate here.
 *
 * The engine is loaded once and kept, because `initialize()` takes seconds and reloading it per
 * keystroke would make dictation unusable. Only one model is resident at a time: a gigabyte-scale
 * model holds a gigabyte-scale working set, and two of them is how a phone runs out of memory.
 *
 * The CPU backend is the only one used. GPU and NPU need `<uses-native-library>` entries in the
 * manifest and vendor libraries that may not exist on this phone, and a backend that fails to load
 * is worse than a slower one that always works.
 *
 * Nothing is logged. Native failures are reported as their own sentence with the model's name in it,
 * never with any part of the prompt, because the prompt is somebody's screen or somebody's speech.
 */
object LocalLlmEngine {

    /** The same answer shape as the network path: temperature held low so cleanup is faithful. */
    private const val TEMPERATURE = 0.2
    private const val TOP_K = 10
    private const val TOP_P = 0.95

    /** Long enough for a summary or a plan, short enough that a runaway model stops itself. */
    private const val MAX_OUTPUT_TOKENS = 512

    private val gate = Mutex()

    private var engine: Engine? = null
    private var loadedModelId: String? = null
    private var nativeLogQuieted = false

    /** Frees the resident model. Called when settings change or the phone is being closed down. */
    fun release(reason: String = "settings changed") {
        val closing = engine
        engine = null
        loadedModelId = null
        runCatching { closing?.close() }
            .onFailure { android.util.Log.d("FlowBubbleLocalLlm", "engine close skipped: $reason") }
    }

    /** Which model is resident, for the settings screen. Never a prompt, never a path. */
    fun loadedModelId(): String? = loadedModelId

    suspend fun isReady(modelId: String?): Boolean = withContext(Dispatchers.IO) {
        val context = LocalModelStore.contextOrNull() ?: return@withContext false
        val spec = LocalModelCatalog.find(modelId) ?: return@withContext false
        LocalModelStore.isReadyNow(context, spec.id)
    }

    /**
     * Runs one request entirely on this phone. Returns the model's text, or a failure whose message
     * says what to do about it without repeating anything the user sent.
     */
    suspend fun complete(
        systemPrompt: String,
        userPrompt: String,
        modelId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching { ask(systemPrompt, userPrompt, modelId) }
    }

    private suspend fun ask(
        systemPrompt: String,
        userPrompt: String,
        modelId: String
    ): String {
        val context = LocalModelStore.contextOrNull()
            ?: error("Your Bubble has not opened its storage yet, so no on-device model can answer.")
        val spec = LocalModelCatalog.find(modelId)
            ?: error("'$modelId' is not a model Your Bubble knows how to run on this phone.")
        if (!spec.roleCapable) {
            error("${spec.label} makes embeddings rather than answering, so no job can be routed to it.")
        }
        if (userPrompt.isBlank()) return ""

        val path = LocalModelStore.pathFor(context, spec)
            ?: error("${spec.label} is not on this phone yet. Download it in Settings, AI models.")
        val status = LocalModelStore.status(context, spec)
        if (status.verdict !is LocalVerdict.Ready) error(status.verdict.reasonFor(spec))

        val resident = engineFor(context, spec, path)
        val answer = try {
            val config = ConversationConfig(
                systemInstruction = Contents.of(systemPrompt),
                samplerConfig = SamplerConfig(
                    topK = TOP_K,
                    topP = TOP_P,
                    temperature = TEMPERATURE
                ),
                maxOutputToken = MAX_OUTPUT_TOKENS
            )
            resident.createConversation(config).use { conversation ->
                textOf(conversation.sendMessage(userPrompt))
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Throwable) {
            // A native failure can mean the model file is damaged or the phone ran out of memory
            // while loading it. Either way this engine instance is not trustworthy for the next call.
            release("request failed")
            throw IllegalStateException(
                "${spec.label} stopped while answering: ${briefCause(failure)}", failure
            )
        }
        check(answer.isNotBlank()) { "${spec.label} answered with nothing." }
        return answer
    }

    /** The one resident engine, loaded on first use and swapped only when the model changes. */
    private suspend fun engineFor(
        context: android.content.Context,
        spec: LocalModelSpec,
        path: String
    ): Engine = gate.withLock {
        val existing = engine
        if (existing != null && loadedModelId == spec.id) return@withLock existing
        runCatching { existing?.close() }
        engine = null
        loadedModelId = null

        if (!nativeLogQuieted) {
            // FATAL, not ERROR: a token-budget warning from the native layer has been seen to quote
            // the prompt it choked on, and the prompt is somebody's screen text.
            runCatching { Engine.setNativeMinLogSeverity(LogSeverity.FATAL) }
            nativeLogQuieted = true
        }
        val loaded = Engine(
            EngineConfig(
                modelPath = path,
                backend = Backend.CPU(),
                cacheDir = LocalModelStore.cacheDir(context).path
            )
        )
        try {
            loaded.initialize()
        } catch (failure: Throwable) {
            runCatching { loaded.close() }
            throw IllegalStateException(
                "${spec.label} could not start on this phone: ${briefCause(failure)}", failure
            )
        }
        engine = loaded
        loadedModelId = spec.id
        loaded
    }

    /**
     * The reply's text. Only the plain text parts are read, so a reasoning model's thinking channel
     * stays inside the model instead of ending up in a message field or a summary.
     */
    private fun textOf(message: Message): String {
        val joined = message.contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString("") { it.text }
        return joined.ifBlank { message.toString() }.trim()
    }

    /** A cause worth showing the user, with any path in it cut off the front. */
    private fun briefCause(failure: Throwable): String {
        val raw = failure.message ?: failure.javaClass.simpleName
        val oneLine = raw.replace(Regex("\\s+"), " ").trim()
        val tail = oneLine.substringAfterLast(": ").ifBlank { oneLine }
        return if (tail.length <= 160) tail else tail.take(160) + "…"
    }
}
