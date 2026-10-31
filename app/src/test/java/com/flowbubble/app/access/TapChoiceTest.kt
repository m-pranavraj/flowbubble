package com.flowbubble.app.access

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TapChoiceTest {

    @Test
    fun `typed field text does not win over the matching suggestion`() {
        val candidates = listOf(
            TapChoice.Candidate("pranav", clickable = true, editable = true),
            TapChoice.Candidate("Pranav", clickable = true, editable = false)
        )
        assertEquals(1, TapChoice.best(candidates, "pranav"))
    }

    @Test
    fun `label without its own click action still wins over the field`() {
        val candidates = listOf(
            TapChoice.Candidate("pranav", clickable = true, editable = true),
            TapChoice.Candidate("Pranav", clickable = false, editable = false)
        )
        assertEquals(1, TapChoice.best(candidates, "pranav"))
    }

    @Test
    fun `the field is still tappable when it is the only exact match`() {
        val candidates = listOf(
            TapChoice.Candidate("pranav", clickable = true, editable = true),
            TapChoice.Candidate("Pranav Kumar", clickable = true, editable = false)
        )
        assertEquals(0, TapChoice.best(candidates, "pranav"))
    }

    @Test
    fun `exact button label beats a longer partial match`() {
        val candidates = listOf(
            TapChoice.Candidate("Close all notifications", clickable = true, editable = false),
            TapChoice.Candidate("Close", clickable = true, editable = false)
        )
        assertEquals(1, TapChoice.best(candidates, "close"))
    }

    @Test
    fun `partial matches fall back to the shortest label`() {
        val candidates = listOf(
            TapChoice.Candidate("Send message to Priya", clickable = true, editable = false),
            TapChoice.Candidate("Send", clickable = true, editable = false)
        )
        assertEquals(1, TapChoice.best(candidates, "send"))
    }

    @Test
    fun `control prefix beats longer text that merely contains the word`() {
        val candidates = listOf(
            TapChoice.Candidate("test send gate", clickable = true, editable = true),
            TapChoice.Candidate("Send SMS", clickable = false, editable = false)
        )
        assertEquals(1, TapChoice.best(candidates, "send"))
    }

    @Test
    fun `containing match still wins when nothing starts with the word`() {
        val candidates = listOf(
            TapChoice.Candidate("Log out", clickable = true, editable = false),
            TapChoice.Candidate("Sign out and log out", clickable = true, editable = false)
        )
        assertEquals(0, TapChoice.best(candidates, "out"))
    }

    @Test
    fun `non clickable exact match is last resort`() {
        val candidates = listOf(
            TapChoice.Candidate("Done", clickable = false, editable = false),
            TapChoice.Candidate("Done editing", clickable = true, editable = false)
        )
        assertEquals(0, TapChoice.best(candidates, "done"))
    }

    @Test
    fun `blank target chooses nothing`() {
        val candidates = listOf(TapChoice.Candidate("Done", clickable = true, editable = false))
        assertNull(TapChoice.best(candidates, "   "))
    }

    @Test
    fun `symbol label matches the spoken words`() {
        val candidates = listOf(
            TapChoice.Candidate("Connected devices", clickable = true, editable = false),
            TapChoice.Candidate("Network & internet", clickable = true, editable = false)
        )
        assertEquals(1, TapChoice.best(candidates, "network and internet"))
    }

    @Test
    fun `spoken symbol phrase prefers the exact control over a longer one`() {
        val candidates = listOf(
            TapChoice.Candidate("Network & internet settings", clickable = true, editable = false),
            TapChoice.Candidate("Network & internet", clickable = true, editable = false)
        )
        assertEquals(1, TapChoice.best(candidates, "network and internet"))
    }

    @Test
    fun `hyphen free speech matches a hyphenated label`() {
        val candidates = listOf(
            TapChoice.Candidate("Mobile network", clickable = true, editable = false),
            TapChoice.Candidate("Wi-Fi", clickable = true, editable = false)
        )
        assertEquals(1, TapChoice.best(candidates, "wifi"))
    }
}
