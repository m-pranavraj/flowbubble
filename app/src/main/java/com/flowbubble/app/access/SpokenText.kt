package com.flowbubble.app.access

/**
 * Folds screen labels and spoken words into the same shape before matching. Screen
 * labels carry symbols and punctuation the spoken command never contains, so
 * "Network & internet" must answer to "network and internet" and "Wi-Fi" to "wifi".
 */
object SpokenText {
    private val symbolWords = mapOf(
        '&' to " and ",
        '+' to " plus ",
        '@' to " at ",
        '%' to " percent ",
        '#' to " number ",
    )

    fun fold(raw: String): String {
        val expanded = buildString(raw.length + 8) {
            raw.lowercase().forEach { ch ->
                val word = symbolWords[ch]
                if (word != null) append(word) else append(ch)
            }
        }
        // Combining marks (Devanagari matras and similar) belong to the letter
        // they decorate, so keep \p{M} or Hindi words fall apart into fragments.
        return expanded.split(Regex("[^\\p{L}\\p{N}\\p{M}]+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    fun compact(raw: String): String = fold(raw).replace(" ", "")

    fun matches(label: String, target: String): Boolean {
        val folded = fold(target)
        if (folded.isEmpty()) return false
        return fold(label).contains(folded) || compact(label).contains(compact(target))
    }
}
