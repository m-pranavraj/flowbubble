package com.flowbubble.app.voice

object TextProcessor {
    private val fillerRegex = Regex("(?i)\\b(?:um|uh|uhm|erm|hmm)\\b,?\\s*")
    private val discourseRegex = Regex("(?i)\\b(?:you know|i mean),\\s*")
    private val repeatedWordRegex = Regex("\\b(\\w+)(?:\\s+\\1\\b)+", RegexOption.IGNORE_CASE)
    private val multiSpaceRegex = Regex("\\s{2,}")
    private val spaceBeforePunctuation = Regex("\\s+([,.!?;:])")
    private val lowercaseI = Regex("\\bi\\b")

    private val contractions = linkedMapOf(
        "i'm" to "I am",
        "i've" to "I have",
        "i'll" to "I will",
        "i'd" to "I would",
        "can't" to "cannot",
        "won't" to "will not",
        "don't" to "do not",
        "doesn't" to "does not",
        "didn't" to "did not",
        "isn't" to "is not",
        "aren't" to "are not",
        "wasn't" to "was not",
        "weren't" to "were not",
        "you're" to "you are",
        "we're" to "we are",
        "they're" to "they are",
        "it's" to "it is",
        "that's" to "that is",
        "there's" to "there is",
        "let's" to "let us",
        "gonna" to "going to",
        "wanna" to "want to",
        "gotta" to "have to"
    )

    private val expansions = linkedMapOf(
        "do not" to "don't",
        "does not" to "doesn't",
        "did not" to "didn't",
        "cannot" to "can't",
        "will not" to "won't",
        "is not" to "isn't",
        "are not" to "aren't",
        "I am" to "I'm",
        "I have" to "I've",
        "I will" to "I'll",
        "you are" to "you're",
        "we are" to "we're",
        "they are" to "they're",
        "it is" to "it's"
    )

    fun clean(input: String): String {
        var text = input.trim().replace(Regex("\\s+"), " ")
        text = text.replace(fillerRegex, "")
        text = text.replace(discourseRegex, "")
        text = text.replace(repeatedWordRegex) { it.groupValues[1] }
        text = text.replace(Regex("(?i)\\s*\\bnew paragraph\\b\\s*"), "\n\n")
        text = text.replace(Regex("(?i)\\s*\\bnew line\\b\\s*"), "\n")
        text = text.replace(spaceBeforePunctuation) { it.groupValues[1] }
        text = text.replace(multiSpaceRegex, " ").trim()
        if (text.isBlank()) return ""
        text = text.replace(lowercaseI, "I")
        text = capitalizeSentences(text)
        return ensureEnding(text)
    }

    fun toBullets(input: String): String {
        val cleaned = clean(input).trimEnd('.', ' ')
        if (cleaned.isBlank()) return ""
        var parts = cleaned
            .split(Regex("(?<=[.!?])\\s+|\\s*;\\s*"))
            .map { it.trim().trim('.', ';', ' ') }
            .filter { it.isNotBlank() }
        if (parts.size < 2) {
            parts = cleaned
                .split(Regex("(?i),?\\s+and\\s+|,\\s+"))
                .map { it.trim().trim('.', ';', ' ') }
                .filter { it.isNotBlank() }
        }
        return parts.joinToString("\n") { "• " + it.replaceFirstChar { ch -> ch.uppercaseChar() } }
    }

    fun professional(input: String): String {
        var text = clean(input)
        contractions.forEach { (from, to) ->
            text = text.replace(Regex("(?i)\\b" + Regex.escape(from) + "\\b"), to)
        }
        text = text.replace(Regex("(?i)\\bjust\\s+"), "")
        text = text.replace(multiSpaceRegex, " ").trim()
        text = capitalizeSentences(text)
        return ensureEnding(text)
    }

    fun casual(input: String): String {
        var text = clean(input)
        expansions.forEach { (from, to) ->
            text = text.replace(Regex("(?i)\\b" + Regex.escape(from) + "\\b"), to)
        }
        return text
    }

    fun localEdit(instruction: String, selected: String): String? {
        val lower = instruction.lowercase()
        return when {
            "bullet" in lower || "list" in lower -> toBullets(selected)
            "grammar" in lower || "spelling" in lower || "punctuation" in lower -> clean(selected)
            "clean" in lower || "tidy" in lower -> clean(selected)
            "professional" in lower || "formal" in lower -> professional(selected)
            "casual" in lower || "friendly" in lower || "relaxed" in lower -> casual(selected)
            else -> null
        }
    }

    private fun capitalizeSentences(text: String): String {
        val chars = text.toCharArray()
        var capitalizeNext = true
        for (index in chars.indices) {
            val ch = chars[index]
            if (capitalizeNext && ch.isLetter()) {
                chars[index] = ch.uppercaseChar()
                capitalizeNext = false
            } else if (ch == '.' || ch == '!' || ch == '?' || ch == '\n') {
                capitalizeNext = true
            }
        }
        return String(chars)
    }

    private fun ensureEnding(text: String): String {
        val last = text.lastOrNull() ?: return text
        if (last in ".!?…") return text
        if (last == '"' || last == '\'' || last == ')' || last == ']') {
            val before = text.dropLast(1).lastOrNull()
            if (before != null && before in ".!?…") return text
        }
        return "$text."
    }
}
