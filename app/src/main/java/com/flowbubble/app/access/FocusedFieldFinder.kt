package com.flowbubble.app.access

/**
 * What one node on screen looks like to the rule below, stripped of the framework so the rule can be
 * tested with a list instead of a phone.
 */
data class FieldCandidate(
    val editable: Boolean,
    val password: Boolean,
    val textEntry: Boolean,
    val focused: Boolean
)

/**
 * Which node on the screen is the field dictated text belongs in.
 *
 * Walking up from `findFocus` is the right answer for a classic `EditText` and the only answer Android
 * offers there, but a Jetpack Compose text field — which is what ChatGPT, Gmail, Chrome and this app's
 * own screens are made of — does not always publish an input-focus node. The old code then concluded
 * there was nowhere to type, which is the wrong conclusion to reach about a screen full of fields.
 *
 * So the whole window is asked as well, with two rules that keep a guess from becoming a keystroke:
 * an unfocused field is only chosen when it is the only candidate on screen, and a credential field
 * ends the search rather than redirecting it.
 */
object FocusedFieldFinder {
    /** The index to write into, or null when writing anywhere would be a guess. */
    fun pick(candidates: List<FieldCandidate>): Int? {
        val fields = candidates.mapIndexed { index, c -> index to c }
            .filter { (_, c) -> c.editable || c.textEntry }
        if (fields.isEmpty()) return null

        val focused = fields.firstOrNull { (_, c) -> c.focused }
        if (focused != null) return if (focused.second.password) null else focused.first

        val open = fields.filter { (_, c) -> !c.password }
        return if (open.size == 1) open.first().first else null
    }
}
