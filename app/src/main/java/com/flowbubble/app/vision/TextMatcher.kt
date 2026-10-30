package com.flowbubble.app.vision

import java.util.Locale

object TextMatcher {
    data class Match(val index: Int, val score: Int)

    private val edgePunctuation = charArrayOf(
        '"', '\'', '\u201C', '\u201D', '\u2018', '\u2019', ':', ';', ',', '.',
        '!', '?', '(', ')', '[', ']', '{', '}', '\u2026', '\u2014', '\u2013'
    )

    fun normalize(raw: String): String = raw
        .replace('\u00A0', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim(*edgePunctuation)
        .lowercase(Locale.ROOT)
        .trim()

    fun score(query: String, label: String): Int {
        val q = normalize(query)
        val l = normalize(label)
        if (q.isEmpty() || l.isEmpty()) return 0
        if (q == l) return 1000 - l.length.coerceAtMost(40)

        val withoutTrailing = l.trimEnd('.', ',', ';', ':', '!', '?')
        if (q == withoutTrailing) return 960 - l.length.coerceAtMost(40)

        if (l.startsWith(q)) return 880 - (l.length - q.length).coerceAtMost(80)
        if (q.length >= 3 && l.contains(q)) return 780 - (l.length - q.length).coerceAtMost(80)

        val queryTokens = q.split(' ').filter { it.length > 1 }
        val labelTokens = l.split(' ').filter { it.isNotEmpty() }
        if (queryTokens.isNotEmpty()) {
            val allPresent = queryTokens.all { token ->
                labelTokens.any { it == token || it.startsWith(token) }
            }
            if (allPresent) return 690 - (labelTokens.size - queryTokens.size).coerceAtLeast(0) * 4
        }

        if (q.length >= 3) {
            val distance = editDistance(q, l)
            val allowed = maxOf(1, q.length / 4)
            if (distance <= allowed) return 600 - distance * 20
        }
        return 0
    }

    fun best(query: String, labels: List<String>): Match? {
        var bestIndex = -1
        var bestScore = 0
        labels.forEachIndexed { index, label ->
            val candidate = score(query, label)
            if (candidate > bestScore) {
                bestScore = candidate
                bestIndex = index
            }
        }
        return if (bestIndex >= 0 && bestScore >= 420) Match(bestIndex, bestScore) else null
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
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    substitution
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
