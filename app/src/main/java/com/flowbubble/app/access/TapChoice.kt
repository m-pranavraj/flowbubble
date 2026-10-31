package com.flowbubble.app.access

/**
 * Ranks visible matches for a spoken tap target. A phrase often appears both inside
 * a text field and on the control it names (a typed recipient and the matching
 * suggestion); the control is what the user means.
 */
object TapChoice {
    data class Candidate(val label: String, val clickable: Boolean, val editable: Boolean)

    fun best(candidates: List<Candidate>, needle: String): Int? {
        val target = SpokenText.fold(needle)
        if (target.isEmpty() || candidates.isEmpty()) return null
        val compactTarget = SpokenText.compact(needle)
        val exact = candidates.indices.filter {
            SpokenText.fold(candidates[it].label) == target ||
                SpokenText.compact(candidates[it].label) == compactTarget
        }
        if (exact.isNotEmpty()) {
            return exact.firstOrNull { !candidates[it].editable && candidates[it].clickable }
                ?: exact.firstOrNull { !candidates[it].editable }
                ?: exact.firstOrNull { candidates[it].clickable }
                ?: exact.first()
        }
        // A control label usually begins with the spoken word ("Send SMS" for
        // "send"); typed text that merely contains it ("test send gate") must lose
        // even when the control's label is longer.
        val prefix = candidates.indices.filter {
            SpokenText.fold(candidates[it].label).startsWith(target)
        }
        val pool = if (prefix.isNotEmpty()) prefix else candidates.indices
        return pool.minWithOrNull(
            compareBy(
                { candidates[it].editable },
                { !candidates[it].clickable },
                { candidates[it].label.length }
            )
        )
    }
}
