package com.flowbubble.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A misheard request must still be scored as `verb + name`, so the app name is compared to the
 * installed apps on its own instead of being diluted by the verb it was spoken with.
 */
class ActionVerbStripTest {

    private val apps = listOf("YouTube", "Camera", "Chrome", "Play Store", "Telegram")

    @Test
    fun `exact verb leaves just the name`() {
        assertEquals("youtube", CommandParser.stripActionVerb("open youtube"))
        assertEquals("YouTube", CommandParser.stripActionVerb("Launch the YouTube"))
        assertEquals("camera", CommandParser.stripActionVerb("start camera"))
    }

    @Test
    fun `misheard verb still leaves the name`() {
        assertEquals("youube", CommandParser.stripActionVerb("opem youube"))
        assertEquals("chrome", CommandParser.stripActionVerb("lanch chrome"))
        assertEquals("youube", CommandParser.stripActionVerb("opean youube"))
    }

    @Test
    fun `code mixed verb leaves the name`() {
        assertEquals("flipkart", CommandParser.stripActionVerb("kholo flipkart"))
        assertEquals("camera", CommandParser.stripActionVerb("khol camera"))
    }

    @Test
    fun `an ordinary sentence is left alone`() {
        assertEquals("the meeting moved to four thirty tomorrow afternoon",
            CommandParser.stripActionVerb("the meeting moved to four thirty tomorrow afternoon"))
        assertEquals("opem youube now and then later on tonight",
            CommandParser.stripActionVerb("opem youube now and then later on tonight"))
    }

    @Test
    fun `a word that is not near a verb is kept`() {
        assertEquals("zebra crossing is here", CommandParser.stripActionVerb("zebra crossing is here"))
        assertEquals("hi", CommandParser.stripActionVerb("hi"))
        assertEquals("", CommandParser.stripActionVerb("   "))
    }

    @Test
    fun `a request verb is recognised even when misheard`() {
        assertTrue(CommandParser.startsWithActionVerb("open youtube"))
        assertTrue(CommandParser.startsWithActionVerb("opem youube"))
        assertTrue(CommandParser.startsWithActionVerb("kholo flipkart"))
        assertTrue(CommandParser.startsWithActionVerb("Launch the YouTube"))
    }

    @Test
    fun `ordinary text is not treated as an app request`() {
        assertFalse(CommandParser.startsWithActionVerb("the meeting moved to four thirty"))
        assertFalse(CommandParser.startsWithActionVerb("zebra crossing is here"))
        assertFalse(CommandParser.startsWithActionVerb("hi"))
        assertFalse(CommandParser.startsWithActionVerb("   "))
        assertFalse(CommandParser.startsWithActionVerb(
                "opem youube now and then later on tonight after the meeting ends"
            )
        )
    }

    @Test
    fun `stripping the verb turns a wrong suggestion into the right app`() {
        val spoken = "opem youube"
        val heard = CommandParser.stripActionVerb(spoken)
        val outcome = PhraseResolver.resolve(heard, apps)
        assertEquals("YouTube", (outcome as PhraseOutcome.Resolved).text)
    }

    @Test
    fun `the whole phrase alone really was ambiguous`() {
        val outcome = PhraseResolver.resolve("opem youube", apps)
        val options = (outcome as PhraseOutcome.Suggest).options.map { it.text }
        assertTrue("both names tie on the raw phrase: $options", "Camera" in options && "YouTube" in options)
    }
}
