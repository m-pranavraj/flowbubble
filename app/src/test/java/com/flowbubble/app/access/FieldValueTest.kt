package com.flowbubble.app.access

import org.junit.Assert.assertEquals
import org.junit.Test

class FieldValueTest {
    @Test
    fun placeholder_reported_as_text_is_empty() {
        assertEquals("", FieldValue.of("Text message", "Text message"))
    }

    @Test
    fun real_text_beats_placeholder() {
        assertEquals("hello there", FieldValue.of("hello there", "Text message"))
    }

    @Test
    fun missing_hint_keeps_text() {
        assertEquals("hello", FieldValue.of("hello", null))
    }

    @Test
    fun empty_text_with_hint_is_empty() {
        assertEquals("", FieldValue.of("", "Search"))
    }

    @Test
    fun null_text_is_empty() {
        assertEquals("", FieldValue.of(null, "Search"))
    }

    @Test
    fun shorter_typed_text_that_resembles_hint_is_kept() {
        assertEquals("Text", FieldValue.of("Text", "Text message"))
    }
}
