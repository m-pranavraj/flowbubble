package com.flowbubble.app.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Short spoken feedback for hands-free mode. It only ever says prompts and outcome summaries the
 * overlay already shows on screen — never screen text, transcripts, or credentials.
 */
class Speaker(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var broken = false
    private var pending: Utterance? = null

    private data class Utterance(val text: String, val locale: Locale, val onDone: (() -> Unit)?)

    fun say(text: String, languageTag: String, onDone: (() -> Unit)? = null) {
        handler.post {
            val phrase = text.trim()
            if (phrase.isEmpty() || broken) {
                onDone?.invoke()
                return@post
            }
            pending = Utterance(phrase, localeFor(languageTag), onDone)
            ensureEngine()
            if (ready) speakPending()
        }
    }

    fun stop() {
        handler.post {
            pending = null
            runCatching { engine?.stop() }
        }
    }

    fun shutdown() {
        handler.post {
            pending = null
            runCatching { engine?.shutdown() }
            engine = null
            ready = false
        }
    }

    private fun ensureEngine() {
        if (engine != null) return
        engine = TextToSpeech(appContext) { status ->
            handler.post {
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    engine?.setOnUtteranceProgressListener(progress)
                    speakPending()
                } else {
                    broken = true
                    drain()
                }
            }
        }
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            handler.post { drain() }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            handler.post { drain() }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            handler.post { drain() }
        }
    }

    private fun speakPending() {
        val utterance = pending ?: return
        val tts = engine ?: return
        val locale = if (tts.isLanguageAvailable(utterance.locale) >= TextToSpeech.LANG_AVAILABLE) {
            utterance.locale
        } else {
            Locale.US
        }
        tts.language = locale
        if (tts.speak(utterance.text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID) == TextToSpeech.ERROR) {
            drain()
        }
    }

    private fun drain() {
        val utterance = pending
        pending = null
        utterance?.onDone?.invoke()
    }

    private fun localeFor(tag: String): Locale =
        runCatching { Locale.forLanguageTag(tag) }.getOrDefault(Locale.US)

    private companion object {
        const val UTTERANCE_ID = "flowbubble"
    }
}
