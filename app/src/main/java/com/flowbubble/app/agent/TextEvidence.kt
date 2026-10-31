package com.flowbubble.app.agent

/**
 * Confirms a typing step from what the screen shows afterwards. Composers such as
 * Messages' recipient field hand accessibility focus to a suggestion list the moment
 * text arrives, so the focused node is not always the field we just wrote to.
 */
object TextEvidence {
    fun confirmed(expected: String, fieldText: String?, visible: (String) -> Boolean): Boolean {
        val needle = expected.trim()
        if (needle.isEmpty()) return false
        if (fieldText?.contains(needle, ignoreCase = true) == true) return true
        return visible(needle)
    }
}
