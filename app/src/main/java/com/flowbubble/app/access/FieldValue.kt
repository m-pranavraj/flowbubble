package com.flowbubble.app.access

/**
 * Empty text fields often expose their placeholder as text once cleared.
 * Treat a value equal to its hint as empty so verification doesn't mistake
 * placeholders for user input.
 */
object FieldValue {
    fun of(text: CharSequence?, hint: CharSequence?): String {
        val value = text?.toString().orEmpty()
        val placeholder = hint?.toString().orEmpty()
        return if (placeholder.isNotEmpty() && value == placeholder) "" else value
    }
}
