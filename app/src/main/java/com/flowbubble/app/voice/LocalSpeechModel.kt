package com.flowbubble.app.voice

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import java.util.Locale

enum class ModelStatus { UNKNOWN, UNAVAILABLE, INSTALLABLE, INSTALLING, READY, FAILED }

data class ModelState(
    val status: ModelStatus,
    val mode: Int? = null,
    val detail: String? = null
)

/** Progress of an explicit, user-triggered model install. */
sealed class InstallEvent {
    data class Started(val bytesToDownload: Long) : InstallEvent()
    data class Progress(val bytesDownloaded: Long) : InstallEvent()
    data object Completed : InstallEvent()
    data class Failed(val reason: String) : InstallEvent()
}

/**
 * Owns the ML Kit on-device speech clients and answers one question honestly: is the model
 * actually usable for this language on this device right now? Nothing here downloads without
 * the user asking for it.
 */
class LocalSpeechModel {

    private val clients = HashMap<Pair<Int, String>, SpeechRecognizer>()
    private val states = HashMap<String, ModelState>()

    fun state(language: String): ModelState = states[language] ?: ModelState(ModelStatus.UNKNOWN)

    fun isReadyNow(language: String): Boolean = state(language).status == ModelStatus.READY

    fun clientFor(language: String): SpeechRecognizer? {
        val state = state(language)
        val mode = state.mode ?: return null
        if (state.status != ModelStatus.READY) return null
        return runCatching { client(mode, language) }.getOrNull()
    }

    suspend fun refresh(language: String): ModelState = probe(language).also { states[language] = it }

    /** Installs the model for the probed mode. Only ever called from an explicit user action. */
    suspend fun install(language: String, onProgress: (InstallEvent) -> Unit): ModelState {
        val mode = state(language).mode ?: refresh(language).mode
        val client = mode?.let { runCatching { client(it, language) }.getOrNull() }
        if (client == null) {
            return state(language).let {
                if (it.status == ModelStatus.UNKNOWN) ModelState(ModelStatus.UNAVAILABLE) else it
            }
        }
        val outcome = runCatching {
            client.download().collect { status ->
                when (status) {
                    is DownloadStatus.DownloadStarted -> onProgress(InstallEvent.Started(status.bytesToDownload))
                    is DownloadStatus.DownloadProgress -> onProgress(InstallEvent.Progress(status.totalBytesDownloaded))
                    is DownloadStatus.DownloadFailed -> onProgress(InstallEvent.Failed(describeSpeechError(status.e)))
                    else -> Unit
                }
                if (status is DownloadStatus.DownloadCompleted) onProgress(InstallEvent.Completed)
            }
        }
        return if (outcome.isSuccess) {
            refresh(language).let {
                if (it.status == ModelStatus.READY) it
                else ModelState(ModelStatus.FAILED, mode, "The model didn't finish installing.")
            }
        } else {
            ModelState(ModelStatus.FAILED, mode, outcome.exceptionOrNull()?.message ?: "Install failed.")
                .also { states[language] = it }
        }
    }

    fun close() {
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
    }

    private suspend fun probe(language: String): ModelState {
        var downloadable: ModelState? = null
        var lastError: String? = null
        for (mode in MODE_PRIORITY) {
            val client = runCatching { client(mode, language) }.getOrNull()
                ?: return ModelState(ModelStatus.UNAVAILABLE, mode, "This language isn't supported by the on-device model.")
            val status = runCatching { client.checkStatus() }
                .getOrElse {
                    lastError = if (it is GenAiException) describeSpeechError(it) else it.message
                    continue
                }
            when (status) {
                FeatureStatus.AVAILABLE -> return ModelState(ModelStatus.READY, mode)
                FeatureStatus.DOWNLOADABLE -> if (downloadable == null) {
                    downloadable = ModelState(ModelStatus.INSTALLABLE, mode)
                }
                FeatureStatus.DOWNLOADING -> return ModelState(ModelStatus.INSTALLING, mode)
                else -> lastError = "Feature status $status"
            }
        }
        return downloadable ?: ModelState(ModelStatus.UNAVAILABLE, MODE_PRIORITY[0], lastError)
    }

    private fun client(mode: Int, language: String): SpeechRecognizer =
        clients.getOrPut(mode to language) {
            val options = SpeechRecognizerOptions.builder().apply {
                locale = localeFor(language)
                preferredMode = mode
            }.build()
            SpeechRecognition.getClient(options)
        }

    private fun localeFor(language: String): Locale =
        Locale.forLanguageTag(language.replace('_', '-'))

    private companion object {
        /** Advanced first for accuracy, basic as the fallback when the device can't run it. */
        val MODE_PRIORITY = intArrayOf(
            SpeechRecognizerOptions.Mode.MODE_ADVANCED,
            SpeechRecognizerOptions.Mode.MODE_BASIC
        )
    }
}

internal fun describeSpeechError(error: GenAiException): String = when (error.errorCode) {
    GenAiException.ErrorCode.NOT_AVAILABLE -> "The on-device model isn't available here."
    GenAiException.ErrorCode.NOT_SUPPORTED -> "This device doesn't support the on-device model."
    GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE -> "Google Play services needs an update for on-device speech."
    GenAiException.ErrorCode.AICORE_INCOMPATIBLE -> "This device can't run the on-device model."
    GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE -> "Not enough storage for the on-device speech model."
    GenAiException.ErrorCode.BUSY -> "The on-device model is busy. Try again."
    GenAiException.ErrorCode.CANCELLED -> "On-device recognition was cancelled."
    GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED,
    GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED ->
        "Android paused on-device speech to protect battery. Try again."
    else -> error.message ?: "On-device speech failed."
}

