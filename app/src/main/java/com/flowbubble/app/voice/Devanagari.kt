package com.flowbubble.app.voice

/**
 * Devanagari to Latin transliteration for matching, not for display: names and app words are
 * compared against Latin contacts and labels, so the output is deliberately plain ASCII. Words
 * that Hindi borrows from English (यूट्यूब) do not transliterate back to their real spelling,
 * which is why [HindiCommands] keeps a small label table on top of this.
 */
object Devanagari {

    private const val VIRAMA = '\u094D'
    private const val NUKTA = '\u093C'
    private const val ANUSVARA = '\u0902'
    private const val CHANDRABINDU = '\u0901'
    private const val VISARGA = '\u0903'
    private const val DANDA = '\u0964'
    private const val DOUBLE_DANDA = '\u0965'

    private val INDEPENDENT = mapOf(
        '\u0905' to "a", '\u0906' to "aa", '\u0907' to "i", '\u0908' to "ee",
        '\u0909' to "u", '\u090A' to "oo", '\u090B' to "ri", '\u090F' to "e",
        '\u0910' to "ai", '\u0913' to "o", '\u0914' to "au", '\u0911' to "o",
        '\u090D' to "e", '\u090E' to "e", '\u0912' to "o", '\u0950' to "om"
    )

    private val CONSONANTS = mapOf(
        '\u0915' to "k", '\u0916' to "kh", '\u0917' to "g", '\u0918' to "gh", '\u0919' to "ng",
        '\u091A' to "ch", '\u091B' to "chh", '\u091C' to "j", '\u091D' to "jh", '\u091E' to "n",
        '\u091F' to "t", '\u0920' to "th", '\u0921' to "d", '\u0922' to "dh", '\u0923' to "n",
        '\u0924' to "t", '\u0925' to "th", '\u0926' to "d", '\u0927' to "dh", '\u0928' to "n",
        '\u092A' to "p", '\u092B' to "ph", '\u092C' to "b", '\u092D' to "bh", '\u092E' to "m",
        '\u092F' to "y", '\u0930' to "r", '\u0932' to "l", '\u0935' to "v", '\u0936' to "sh",
        '\u0937' to "sh", '\u0938' to "s", '\u0939' to "h", '\u0933' to "l"
    )

    /** Precomposed and decomposed nukta letters, with the sound they borrow. */
    private val NUKTA_CONSONANTS = mapOf(
        '\u0915' to "q", '\u0916' to "kh", '\u0917' to "gh", '\u091C' to "z",
        '\u0921' to "r", '\u0922' to "rh", '\u092B' to "f", '\u092F' to "y"
    )

    private val MATRAS = mapOf(
        '\u093E' to "a", '\u093F' to "i", '\u0940' to "ee", '\u0941' to "u",
        '\u0942' to "oo", '\u0943' to "ri", '\u0947' to "e", '\u0948' to "ai",
        '\u094B' to "o", '\u094C' to "au", '\u0949' to "o", '\u0945' to "e",
        '\u0946' to "e", '\u094A' to "o"
    )

    fun contains(text: String): Boolean = text.any { isDevanagari(it) }

    fun isDevanagari(char: Char): Boolean = char in '\u0900'..'\u097F'

    /**
     * One word at a time. The inherent "a" after a bare consonant is dropped at the end of the
     * word (प्रणव → pranav, क्रोम → krom) and a final long आ is shortened, because that is how
     * names are actually written in Latin script.
     */
    fun word(input: String): String {
        val out = StringBuilder()
        var lastWasInherentA = false
        var index = 0
        while (index < input.length) {
            val char = input[index]
            val nukta = index + 1 < input.length && input[index + 1] == NUKTA
            val consumed = if (nukta) 2 else 1
            val consonant = if (nukta) NUKTA_CONSONANTS[char] else CONSONANTS[char]
            when {
                consonant != null -> {
                    out.append(consonant)
                    val next = input.getOrNull(index + consumed)
                    when {
                        next == VIRAMA -> {
                            index += consumed + 1
                            lastWasInherentA = false
                            continue
                        }
                        next != null && MATRAS.containsKey(next) -> {
                            out.append(MATRAS[next])
                            index += consumed + 1
                            lastWasInherentA = false
                            continue
                        }
                        else -> {
                            out.append('a')
                            lastWasInherentA = true
                        }
                    }
                }
                INDEPENDENT.containsKey(char) -> {
                    out.append(INDEPENDENT[char])
                    lastWasInherentA = false
                }
                MATRAS.containsKey(char) -> {
                    out.append(MATRAS[char])
                    lastWasInherentA = false
                }
                char == ANUSVARA || char == CHANDRABINDU -> out.append('n')
                char == VISARGA -> out.append('h')
                char == DANDA || char == DOUBLE_DANDA -> out.append('.')
                char in '\u0966'..'\u096F' -> out.append(char - '\u0966')
                char == VIRAMA || char == NUKTA -> Unit
                else -> out.append(char)
            }
            index += consumed
        }
        var latin = out.toString()
        if (lastWasInherentA && latin.endsWith('a')) latin = latin.dropLast(1)
        if (latin.endsWith("aa")) latin = latin.dropLast(1)
        return latin
    }
}
