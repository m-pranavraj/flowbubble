package com.flowbubble.app.voice

data class PhraseSuggestion(val text: String, val score: Float)

sealed interface PhraseOutcome {
    /** Confident enough to act on, but the UI still has to say what was heard. */
    data class Resolved(val text: String, val score: Float) : PhraseOutcome

    /** Close, but the user has to pick. */
    data class Suggest(val options: List<PhraseSuggestion>) : PhraseOutcome

    data object Unknown : PhraseOutcome
}

/**
 * Maps a misheard phrase back to a word Your Bubble can act on. Neither speech engine exposes a
 * confidence value or n-best alternates, so this post-transcription rescoring against a known
 * vocabulary is the only place the correction can happen.
 */
object PhraseResolver {

    const val AUTO_CONFIDENCE = 0.82f
    const val SUGGEST_CONFIDENCE = 0.55f
    private const val MAX_LENGTH = 64
    private const val AMBIGUITY_MARGIN = 0.05f

    private val VOWELS = "aeiouy"

    /** Ordered so digraphs collapse before their letters are folded on their own. */
    private val FOLDINGS = listOf(
        "wh" to "w", "ph" to "f", "sh" to "s", "ch" to "k", "th" to "t", "ck" to "k",
        "x" to "ks", "q" to "k", "z" to "s", "v" to "f", "b" to "p",
        "d" to "t", "g" to "k", "j" to "k", "c" to "k"
    )

    fun resolve(spoken: String, vocabulary: Collection<String>, limit: Int = 4): PhraseOutcome {
        if (spoken.isBlank() || vocabulary.isEmpty()) return PhraseOutcome.Unknown
        val scored = vocabulary
            .map { it to score(spoken, it) }
            .filter { it.second >= SUGGEST_CONFIDENCE }
            .sortedWith(compareByDescending<Pair<String, Float>> { it.second }.thenBy { it.first.lowercase() })
        if (scored.isEmpty()) return PhraseOutcome.Unknown
        val options = scored.take(limit).map { PhraseSuggestion(it.first, it.second) }
        val top = options.first()
        val runnerUp = options.drop(1).firstOrNull { it.text.lowercase() != top.text.lowercase() }
        val ambiguous = runnerUp != null && runnerUp.score >= top.score - AMBIGUITY_MARGIN
        return when {
            ambiguous -> PhraseOutcome.Suggest(options)
            top.score >= AUTO_CONFIDENCE -> PhraseOutcome.Resolved(top.text, top.score)
            else -> PhraseOutcome.Suggest(options)
        }
    }

    fun score(spoken: String, candidate: String): Float {
        val heard = letters(spoken)
        val known = letters(candidate)
        if (heard.isEmpty() || known.isEmpty()) return 0f
        if (heard == known) return 1f
        if (covers(spoken, candidate)) return 0.97f
        val literal = similarity(heard, known)
        val phonetic = similarity(fold(heard), fold(known))
        val blend = if (phonetic >= 0.75f && literal >= 0.55f) (literal + phonetic) / 2f + 0.1f else 0f
        return maxOf(literal, phonetic, blend).coerceIn(0f, 1f)
    }

    /** Lowercase letters and digits only, spaces and punctuation removed. */
    fun letters(text: String): String =
        text.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    /** Rough sound-alike form: same consonant confusions a listener makes, vowels collapsed. */
    fun fold(text: String): String {
        var s = letters(text)
        FOLDINGS.forEach { (from, to) -> s = s.replace(from, to) }
        val out = StringBuilder(s.length)
        var previous = '\u0000'
        for (ch in s) {
            val mapped = if (ch in VOWELS) 'a' else ch
            if (mapped != previous) out.append(mapped)
            previous = mapped
        }
        return out.toString()
    }

    fun similarity(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        if (a.length > MAX_LENGTH || b.length > MAX_LENGTH) return 0f
        return (1f - editDistance(a, b).toFloat() / maxOf(a.length, b.length)).coerceIn(0f, 1f)
    }

    fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    /** True when every word of the candidate is present verbatim in what was heard. */
    private fun covers(spoken: String, candidate: String): Boolean {
        val heard = words(spoken)
        val known = words(candidate)
        return known.isNotEmpty() && heard.containsAll(known)
    }

    private fun words(text: String): List<String> =
        text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
}
