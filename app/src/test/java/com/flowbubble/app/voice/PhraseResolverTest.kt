package com.flowbubble.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhraseResolverTest {

    private val apps = listOf("WhatsApp", "Photoshop", "YouTube", "Chrome", "Telegram", "Zomato")

    @Test
    fun `exact name resolves`() {
        val outcome = PhraseResolver.resolve("whatsapp", apps)
        assertTrue(outcome is PhraseOutcome.Resolved)
        assertEquals("WhatsApp", (outcome as PhraseOutcome.Resolved).text)
    }

    @Test
    fun `phonetic spelling resolves to the installed app`() {
        val outcome = PhraseResolver.resolve("fotoshop", apps)
        assertEquals("Photoshop", (outcome as PhraseOutcome.Resolved).text)
    }

    @Test
    fun `split apart mishearing resolves`() {
        val outcome = PhraseResolver.resolve("wats app", apps)
        assertEquals("WhatsApp", (outcome as PhraseOutcome.Resolved).text)
    }

    @Test
    fun `dropped syllable resolves`() {
        val outcome = PhraseResolver.resolve("crom", apps)
        assertEquals("Chrome", (outcome as PhraseOutcome.Resolved).text)
    }

    @Test
    fun `phrase that contains the whole name resolves`() {
        val outcome = PhraseResolver.resolve("open the youtube app", apps)
        assertEquals("YouTube", (outcome as PhraseOutcome.Resolved).text)
    }

    @Test
    fun `two near identical names stay a suggestion`() {
        val outcome = PhraseResolver.resolve("telegraf", listOf("Telegram", "Telegraph"))
        assertTrue(outcome is PhraseOutcome.Suggest)
        assertEquals(2, (outcome as PhraseOutcome.Suggest).options.size)
    }

    @Test
    fun `unrelated word produces nothing`() {
        assertTrue(PhraseResolver.resolve("zebra", apps) is PhraseOutcome.Unknown)
    }

    @Test
    fun `dictated sentences are never rescoring candidates`() {
        val sentence = "I wanted to tell you that the meeting moved to four thirty tomorrow"
        val outcome = PhraseResolver.resolve(sentence, apps)
        assertTrue(outcome is PhraseOutcome.Unknown)
    }

    @Test
    fun `fold treats sound alikes the same`() {
        assertEquals(PhraseResolver.fold("photoshop"), PhraseResolver.fold("fotoshop"))
        assertEquals(PhraseResolver.fold("whatsapp"), PhraseResolver.fold("wats app"))
    }

    @Test
    fun `edit distance counts substitutions and inserts`() {
        assertEquals(0, PhraseResolver.editDistance("kitten", "kitten"))
        assertEquals(3, PhraseResolver.editDistance("kitten", "sitting"))
        assertEquals(2, PhraseResolver.editDistance("chrome", "crom"))
        assertEquals(7, PhraseResolver.editDistance("", "sitting"))
    }

    @Test
    fun `scores stay inside the unit range`() {
        apps.forEach { known ->
            val score = PhraseResolver.score("open chrome now", known)
            assertTrue("$known scored $score", score in 0f..1f)
        }
    }
}
