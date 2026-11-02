package com.flowbubble.app.vision

import com.flowbubble.app.agent.ActionParsing

enum class ActionEntityKind { PHONE, EMAIL, URL, ADDRESS }

data class ActionEntity(val kind: ActionEntityKind, val value: String, val display: String)

/**
 * Finds things on screen that Your Bubble can act on. Detection only; nothing here
 * ever dials, mails, opens, or navigates on its own.
 */
object SmartActions {
    private val urlRegex = Regex(
        "(?<![\\w@])(?:https?://|www\\.)[^\\s<>\"']+",
        RegexOption.IGNORE_CASE
    )
    private val emailRegex = Regex(
        "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}"
    )
    private val phoneRegex = Regex("(?<![\\w@])(\\+?\\d[\\d\\s().-]{5,18}\\d)(?![\\w@])")
    private val addressRegex = Regex(
        "\\b\\d{1,5}\\s+(?:[A-Za-z0-9][A-Za-z0-9.'-]*\\s+){1,4}" +
            "(?:Street|St|Road|Rd|Avenue|Ave|Lane|Ln|Drive|Dr|Boulevard|Blvd|Way|Court|Ct|Place|Pl|Square|Sq)\\.?\\b",
        RegexOption.IGNORE_CASE
    )
    private val dateLikeRegex = Regex(
        "^(?:\\d{4}-\\d{1,2}-\\d{1,2}|\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4})$"
    )

    private data class Candidate(
        val start: Int,
        val end: Int,
        val entity: ActionEntity
    )

    private fun IntRange.overlaps(other: IntRange): Boolean =
        first <= other.last && other.first <= last

    fun detect(text: String, limit: Int = 6): List<ActionEntity> {
        if (text.isBlank() || limit <= 0) return emptyList()
        val candidates = ArrayList<Candidate>()
        val taken = ArrayList<IntRange>()

        urlRegex.findAll(text).forEach { match ->
            val raw = match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
            if (raw.length <= 5) return@forEach
            val url = ActionParsing.canonicalUrl(raw) ?: return@forEach
            val end = match.range.first + raw.length
            candidates.add(Candidate(match.range.first, end, ActionEntity(ActionEntityKind.URL, url, raw.take(160))))
            taken.add(match.range.first until end)
        }

        emailRegex.findAll(text).forEach { match ->
            if (taken.any { it.overlaps(match.range) }) return@forEach
            val address = match.value.trimEnd('.', ',', ';', ':', ')')
            if (address.length > 120) return@forEach
            candidates.add(
                Candidate(match.range.first, match.range.first + address.length, ActionEntity(ActionEntityKind.EMAIL, address, address))
            )
            taken.add(match.range.first until match.range.first + address.length)
        }

        phoneRegex.findAll(text).forEach { match ->
            if (taken.any { it.overlaps(match.range) }) return@forEach
            val raw = match.groupValues[1].trim()
            val digits = raw.count(Char::isDigit)
            if (digits !in 7..15) return@forEach
            val formatted = raw.any { it == '+' || it == '(' || it == ')' || it == '-' || it == ' ' || it == '.' }
            if (!formatted && digits < 10) return@forEach
            if (dateLikeRegex.matches(raw)) return@forEach
            val value = if (raw.startsWith("+")) "+" + raw.filter(Char::isDigit) else raw.filter(Char::isDigit)
            candidates.add(Candidate(match.range.first, match.range.last + 1, ActionEntity(ActionEntityKind.PHONE, value, raw)))
            taken.add(match.range)
        }

        addressRegex.findAll(text).forEach { match ->
            if (taken.any { it.overlaps(match.range) }) return@forEach
            val address = match.value.trim().trimEnd('.', ',')
            if (address.length > 120) return@forEach
            candidates.add(
                Candidate(match.range.first, match.range.first + address.length, ActionEntity(ActionEntityKind.ADDRESS, address, address))
            )
            taken.add(match.range.first until match.range.first + address.length)
        }

        val seen = HashSet<String>()
        return candidates
            .sortedBy { it.start }
            .filter { seen.add("${it.entity.kind}|${it.entity.value.lowercase()}") }
            .map { it.entity }
            .take(limit)
    }
}
