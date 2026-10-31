package com.flowbubble.app.voice

/** What an utterance during hands-free listening turned out to be. */
sealed class WakeResult {
    data object None : WakeResult()
    data object WakeOnly : WakeResult()
    data class Command(val text: String) : WakeResult()
}

/**
 * The wake phrase is a greeting plus the product name, so ordinary speech can never trigger a
 * command. Matching is forgiving about punctuation, spacing, and the ways recognizers misspell
 * "bubble", but it never matches "bubble" inside a longer word.
 */
object HandsFree {
    private val WAKE = Regex(
        "\\b(?:hello|hey|hi|okay|ok)[\\s,.!?\\-]+(?:flow[\\s\\-]*)?(?:bubble|buble|bobble|babel)\\b",
        RegexOption.IGNORE_CASE
    )

    private const val EDGE_PUNCTUATION = " \t\n.,!?;:\u2014\u2013-\u2026'\u201C\u201D\""

    fun parse(text: String): WakeResult {
        val matches = WAKE.findAll(text).toList()
        if (matches.isEmpty()) return WakeResult.None
        val command = text.substring(matches.last().range.last + 1)
            .trim { it in EDGE_PUNCTUATION }
        if (command.isEmpty()) return WakeResult.WakeOnly
        // "hello bubble hello bubble" is the recognizer echoing the wake phrase, not a request.
        if (WAKE.matches(command)) return WakeResult.WakeOnly
        return WakeResult.Command(command)
    }
}

enum class VoiceAnswer { YES, NO }

/** Reads a spoken yes or no out of a confirmation answer. Anything else returns null. */
object VoiceAnswerParser {
    private val YES_WORDS = setOf(
        "yes", "yeah", "yep", "yup", "ya", "sure", "ok", "okay", "haan", "han", "ha",
        "confirm", "confirmed", "approve", "approved", "affirmative", "correct", "right",
        "proceed", "done", "haanji", "हाँ", "हां", "हा", "ठीक", "बिल्कुल"
    )
    private val NO_WORDS = setOf(
        "no", "nope", "nah", "na", "nahi", "nahin", "cancel", "stop", "abort", "deny",
        "never", "nevermind", "dont", "mat", "close", "dismiss", "leave", "quit", "exit",
        "नहीं", "नही", "ना", "मत", "रद्द"
    )
    private val YES_PHRASES = setOf(
        "do it", "go ahead", "go for it", "send it", "please do", "kar do", "theek hai",
        "sounds good", "of course", "yes please"
    )
    private val NO_PHRASES = setOf(
        "never mind", "mat karo", "leave it", "not now", "no thanks", "don't do it"
    )
    private val FILLERS = setOf("oh", "um", "uh", "so", "well", "please", "okay", "ok")

    fun parse(text: String): VoiceAnswer? {
        val words = normalize(text)
        if (words.isEmpty()) return null
        if (words.joinToString(" ") in YES_PHRASES) return VoiceAnswer.YES
        if (words.joinToString(" ") in NO_PHRASES) return VoiceAnswer.NO
        val first = words.firstOrNull { it !in FILLERS || words.size == 1 } ?: return null
        return when {
            first in YES_WORDS -> VoiceAnswer.YES
            first in NO_WORDS -> VoiceAnswer.NO
            else -> null
        }
    }

    private fun normalize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}']+"), " ")
            .trim()
            .split(' ')
            .filter { it.isNotBlank() }
}
