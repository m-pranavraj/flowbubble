package com.flowbubble.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LastActionMemoryTest {

    @Before
    fun reset() {
        LastActionMemory.clear()
    }

    @Test
    fun `splits an appended dictation into a segment`() {
        LastActionMemory.rememberEdit("Hello", "Hello world", "Message")

        val edit = LastActionMemory.edit!!
        assertEquals("Hello", edit.prefix)
        assertEquals("", edit.oldSegment)
        assertEquals(" world", edit.newSegment)
        assertEquals("", edit.suffix)
        assertEquals(" world", LastActionMemory.latestText()!!.first)
        assertTrue(LastActionMemory.latestText()!!.second)
    }

    @Test
    fun `splits a replaced selection into old and new segments`() {
        LastActionMemory.rememberEdit("abc DEF ghi", "abc def ghi", null)

        val edit = LastActionMemory.edit!!
        assertEquals("abc ", edit.prefix)
        assertEquals("DEF", edit.oldSegment)
        assertEquals("def", edit.newSegment)
        assertEquals(" ghi", edit.suffix)
    }

    @Test
    fun `insertion in the middle keeps the following text as the suffix`() {
        LastActionMemory.rememberEdit("hello world", "hello brave world", null)

        val edit = LastActionMemory.edit!!
        assertEquals("hello ", edit.prefix)
        assertEquals("brave ", edit.newSegment)
        assertEquals("world", edit.suffix)
    }

    @Test
    fun `a blank field result is ignored`() {
        LastActionMemory.rememberEdit("some text", "", null)
        assertNull(LastActionMemory.edit)
    }

    @Test
    fun `the newest of an edit and an answer wins`() {
        LastActionMemory.rememberOutput("model answer")
        LastActionMemory.rememberEdit("Hello", "Hello world", null)

        assertEquals(" world", LastActionMemory.latestText()!!.first)

        LastActionMemory.rememberOutput("newer answer")
        val latest = LastActionMemory.latestText()!!
        assertEquals("newer answer", latest.first)
        assertEquals(false, latest.second)
    }

    @Test
    fun `forgetting the edit keeps the last answer`() {
        LastActionMemory.rememberEdit("Hello", "Hello world", null)
        LastActionMemory.rememberOutput("model answer")
        LastActionMemory.forgetEdit()

        val latest = LastActionMemory.latestText()!!
        assertEquals("model answer", latest.first)
        assertEquals(false, latest.second)
    }

    @Test
    fun `raw commands survive follow-up edits and clear wipes everything`() {
        LastActionMemory.rememberRaw("open YouTube")
        assertEquals("open YouTube", LastActionMemory.raw)

        LastActionMemory.clear()
        assertNull(LastActionMemory.raw)
        assertNull(LastActionMemory.edit)
        assertNull(LastActionMemory.output)
        assertNull(LastActionMemory.latestText())
    }
}
