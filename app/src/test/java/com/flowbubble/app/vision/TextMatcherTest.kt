package com.flowbubble.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMatcherTest {

    @Test
    fun `normalizes case, spacing, and edge punctuation`() {
        assertEquals("hello", TextMatcher.normalize("  \u201CHello,\u201D  "))
        assertEquals("search settings", TextMatcher.normalize("Search   Settings..."))
        assertEquals("", TextMatcher.normalize(" \u2014 "))
    }

    @Test
    fun `exact matches outrank prefixes and contains`() {
        val exact = TextMatcher.score("Settings", "Settings")
        val prefix = TextMatcher.score("Settings", "Settings and privacy")
        val contains = TextMatcher.score("Settings", "Open Settings now")
        assertTrue(exact > prefix)
        assertTrue(prefix > contains)
        assertTrue(contains > 0)
    }

    @Test
    fun `token matches survive extra words`() {
        val score = TextMatcher.score("delete account", "Delete my account")
        assertTrue(score in 600..800)
    }

    @Test
    fun `small typos still match`() {
        val score = TextMatcher.score("setings", "Settings")
        assertTrue(score > 500)
        assertEquals(1, TextMatcher.editDistance("setings", "settings"))
    }

    @Test
    fun `best chooses the strongest label and rejects weak ones`() {
        val match = TextMatcher.best(
            "continue",
            listOf("Cancel", "Continue", "Continue with email")
        )
        assertNotNull(match)
        assertEquals(1, match!!.index)

        assertNull(TextMatcher.best("xyzzy", listOf("Settings", "Home")))
    }

    @Test
    fun `distant strings do not match`() {
        assertEquals(0, TextMatcher.score("hello", "totally unrelated"))
        assertEquals(3, TextMatcher.editDistance("kitten", "sitting"))
    }
}
