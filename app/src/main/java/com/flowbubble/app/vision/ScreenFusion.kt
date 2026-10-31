package com.flowbubble.app.vision

import java.util.Locale

object ScreenFusion {
    private const val MAX_TARGETS = 600
    private const val MAX_AFFORDANCE_WORDS = 3

    fun targets(nodes: List<ScreenNode>, ocr: List<OcrLine>): List<ScreenTarget> {
        val result = ArrayList<ScreenTarget>(minOf(MAX_TARGETS, nodes.size + ocr.size))

        nodes.asSequence()
            .filter { it.box.isValid && it.label.isNotBlank() && !it.password }
            .sortedWith(compareBy({ it.box.top }, { it.box.left }))
            .forEach { node ->
                addTarget(
                    result,
                    ScreenTarget(
                        label = node.label.trim(),
                        box = node.box,
                        source = TargetSource.ACCESSIBILITY,
                        clickable = node.clickable || node.editable,
                        editable = node.editable
                    )
                )
            }

        ocr.forEach { line ->
            line.words.forEach { word ->
                if (word.text.length >= 2 && word.box.isValid) {
                    addTarget(
                        result,
                        ScreenTarget(
                            label = word.text,
                            box = word.box,
                            source = TargetSource.OCR,
                            clickable = true
                        )
                    )
                }
            }
            if (line.text.length >= 2 && line.box.isValid) {
                addTarget(
                    result,
                    ScreenTarget(
                        label = line.text,
                        box = line.box,
                        source = TargetSource.OCR,
                        clickable = true
                    )
                )
            }
        }

        return result
            .sortedWith(compareBy({ it.box.top }, { it.box.left }))
            .take(MAX_TARGETS)
    }

    fun findTarget(query: String, targets: List<ScreenTarget>, ordinal: Int? = null): ScreenTarget? {
        if (targets.isEmpty()) return null
        if (ordinal != null) {
            val clickable = targets.filter { it.clickable && it.box.isValid }
            val pool = if (clickable.size >= ordinal) clickable else targets.filter { it.box.isValid }
            return pool.getOrNull(ordinal - 1)
        }

        val normalizedQuery = TextMatcher.normalize(query)
        if (normalizedQuery.isEmpty()) return null

        var best: ScreenTarget? = null
        var bestScore = 0
        var bestTieBreak = Int.MIN_VALUE
        targets.forEach { target ->
            val score = TextMatcher.score(normalizedQuery, target.label)
            if (score == 0) return@forEach
            // A spoken phrase often appears both as typed text in a field and as the
            // control the user named (a recipient, a suggestion, a result). The control
            // wins; the field is only tapped when nothing else matches.
            val tieBreak = when {
                target.source == TargetSource.ACCESSIBILITY && target.clickable && !target.editable -> 4
                target.source == TargetSource.ACCESSIBILITY && !target.editable -> 3
                target.source == TargetSource.ACCESSIBILITY -> 2
                target.editable -> 1
                else -> 0
            }
            if (score > bestScore || (score == bestScore && tieBreak > bestTieBreak)) {
                bestScore = score
                bestTieBreak = tieBreak
                best = target
            }
        }
        return best?.takeIf { bestScore >= 420 }
    }

    fun searchField(targets: List<ScreenTarget>, hints: List<String> = emptyList()): ScreenTarget? {
        val normalizedHints = hints.map { TextMatcher.normalize(it) }.filter { it.isNotEmpty() }
        val editable = targets.filter { it.editable && it.box.isValid }
        if (editable.isEmpty()) return null

        editable.firstOrNull { target ->
            val label = target.normalized
            normalizedHints.any { hint -> label.contains(hint) }
        }?.let { return it }

        editable.firstOrNull { target ->
            val label = target.normalized
            label.contains("search") || label.contains("look up")
        }?.let { return it }

        editable.firstOrNull { target ->
            val label = target.normalized
            label.contains("type") || label.contains("message") ||
                label.contains("find") || label.contains("ask")
        }?.let { return it }

        return editable.firstOrNull { it.normalized.isEmpty() } ?: editable.first()
    }

    /**
     * The control that *opens* an app's search screen. Most apps hide search behind an icon instead
     * of exposing a field, so a chain that has to search taps this and then looks for the field.
     * Editable nodes are excluded so this never competes with a field `searchField` already found,
     * and only a short label that is plainly the search control qualifies: body text that happens to
     * mention search, and search-result rows, must not be tapped.
     *
     * The label does not have to be the clickable node. Settings puts "Search settings" on a plain
     * TextView and makes its parent the control, and tapping a label already walks up to the nearest
     * clickable ancestor.
     */
    fun searchAffordance(targets: List<ScreenTarget>): ScreenTarget? =
        targets.firstOrNull { candidate ->
            val label = candidate.normalized
            !candidate.editable && candidate.box.isValid &&
                label.isNotEmpty() && !label.contains("result") &&
                label.split(" ").count { it.isNotBlank() } <= MAX_AFFORDANCE_WORDS &&
                (label == "search" || label.startsWith("search ") || label.endsWith(" search"))
        }

    fun looksLikeResult(label: String): Boolean {
        val text = TextMatcher.normalize(label)
        if (text.length < 3) return false
        val blocked = listOf(
            "back", "close", "cancel", "menu", "more", "settings", "share", "search",
            "home", "account", "sign in", "sign up", "help", "privacy", "terms"
        )
        return blocked.none { text == it } && !text.startsWith("http")
    }

    fun textHash(model: ScreenModel): Int {
        val text = model.readableText(3000).lowercase(Locale.ROOT)
        return text.hashCode()
    }

    private fun addTarget(out: MutableList<ScreenTarget>, candidate: ScreenTarget) {
        if (out.size >= MAX_TARGETS) return
        val duplicate = out.any { existing ->
            existing.normalized == candidate.normalized &&
                existing.box.overlapRatio(candidate.box) > 0.72f
        }
        if (duplicate) return
        if (candidate.source == TargetSource.OCR) {
            val coveredByAccessibility = out.any { existing ->
                existing.source == TargetSource.ACCESSIBILITY &&
                    existing.box.overlapRatio(candidate.box) > 0.55f &&
                    (existing.normalized.contains(candidate.normalized) ||
                        candidate.normalized.contains(existing.normalized))
            }
            if (coveredByAccessibility) return
        }
        out.add(candidate)
    }
}
