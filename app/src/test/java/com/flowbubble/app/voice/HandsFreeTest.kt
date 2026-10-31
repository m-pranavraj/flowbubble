package com.flowbubble.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandsFreeTest {

    @Test
    fun `wake phrase alone arms the next command`() {
        assertEquals(WakeResult.WakeOnly, HandsFree.parse("Hello Bubble!"))
    }

    @Test
    fun `wake phrase with command returns the remainder untouched`() {
        assertEquals(WakeResult.Command("open WhatsApp"), HandsFree.parse("hello bubble open WhatsApp"))
    }

    @Test
    fun `punctuation between the words still counts`() {
        assertEquals(WakeResult.Command("open gmail"), HandsFree.parse("Hello, Bubble... open gmail"))
    }

    @Test
    fun `short greetings work`() {
        assertEquals(WakeResult.WakeOnly, HandsFree.parse("hey bubble"))
        assertEquals(WakeResult.WakeOnly, HandsFree.parse("ok bubble"))
    }

    @Test
    fun `bubble inside another word is not a wake phrase`() {
        assertEquals(WakeResult.None, HandsFree.parse("open bubblegum"))
        assertEquals(WakeResult.None, HandsFree.parse("this is bubble wrap"))
    }

    @Test
    fun `no greeting means no wake`() {
        assertEquals(WakeResult.None, HandsFree.parse("open bunble"))
        assertEquals(WakeResult.None, HandsFree.parse("hello bob"))
        assertEquals(WakeResult.None, HandsFree.parse(""))
    }

    @Test
    fun `common mishearing of bubble still wakes`() {
        assertEquals(WakeResult.WakeOnly, HandsFree.parse("hello buble"))
        assertEquals(WakeResult.Command("open youtube"), HandsFree.parse("hello bobble open youtube"))
    }

    @Test
    fun `the last wake phrase wins when the recognizer repeats`() {
        assertEquals(WakeResult.Command("open gmail"), HandsFree.parse("hello bubble hello bubble open gmail"))
    }

    @Test
    fun `flowbubble is accepted as the name`() {
        assertEquals(WakeResult.Command("open maps"), HandsFree.parse("hey flow bubble open maps"))
    }

    @Test
    fun `spoken yes variants confirm`() {
        for (phrase in listOf("yes", "Yeah!", "yep", "sure", "okay", "haan", "ha", "do it", "go ahead", "yes please")) {
            assertEquals(phrase, VoiceAnswer.YES, VoiceAnswerParser.parse(phrase))
        }
    }

    @Test
    fun `spoken no variants deny`() {
        for (phrase in listOf("no", "Nope", "nahi", "cancel", "stop", "never mind", "mat karo", "no thanks")) {
            assertEquals(phrase, VoiceAnswer.NO, VoiceAnswerParser.parse(phrase))
        }
    }

    @Test
    fun `first word decides between mixed answers`() {
        assertEquals(VoiceAnswer.YES, VoiceAnswerParser.parse("yes no maybe"))
        assertEquals(VoiceAnswer.NO, VoiceAnswerParser.parse("no that's wrong"))
    }

    @Test
    fun `unrelated speech is not an answer`() {
        for (phrase in listOf("", "maybe later", "hello bubble", "open gmail", "what is this")) {
            assertNull(phrase, VoiceAnswerParser.parse(phrase))
        }
    }
}
