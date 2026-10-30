package com.flowbubble.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextProcessorTest {

    @Test
    fun `removes fillers and capitalizes`() {
        assertEquals(
            "So I was thinking, we should meet tomorrow.",
            TextProcessor.clean("um so I was thinking, uh, we should meet tomorrow")
        )
    }

    @Test
    fun `capitalizes lowercase i`() {
        assertEquals("I think I am ready.", TextProcessor.clean("i think i am ready"))
    }

    @Test
    fun `returns blank for filler only`() {
        assertEquals("", TextProcessor.clean("um uh"))
    }

    @Test
    fun `turns new line into a break`() {
        val result = TextProcessor.clean("hello new line world")
        assertTrue(result.contains("\n"))
        assertEquals("Hello\nWorld.", result)
    }

    @Test
    fun `collapses repeated words`() {
        assertEquals("We should go now.", TextProcessor.clean("we we should go go now"))
    }

    @Test
    fun `builds bullets from a list`() {
        assertEquals(
            "\u2022 Buy milk\n\u2022 Eggs\n\u2022 Bread",
            TextProcessor.toBullets("buy milk, eggs and bread")
        )
    }

    @Test
    fun `professional removes contractions and just`() {
        assertEquals(
            "I am going to finish it.",
            TextProcessor.professional("i'm gonna just finish it")
        )
    }

    @Test
    fun `casual adds contractions`() {
        assertEquals(
            "I can't attend because it's late.",
            TextProcessor.casual("I cannot attend because it is late.")
        )
    }

    @Test
    fun `local edit handles known instructions`() {
        val professional = TextProcessor.localEdit("make this professional", "i'm gonna finish it")
        assertEquals("I am going to finish it.", professional)

        val bullets = TextProcessor.localEdit("turn this into bullet points", "one. two. three.")
        assertEquals("\u2022 One\n\u2022 Two\n\u2022 Three", bullets)

        assertNull(TextProcessor.localEdit("translate this to Spanish", "hola amigo"))
    }
}
