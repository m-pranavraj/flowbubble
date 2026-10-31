package com.flowbubble.app.access

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenTextTest {

    @Test
    fun `ampersand answers to the spoken word`() {
        assertEquals("network and internet", SpokenText.fold("Network & internet"))
        assertTrue(SpokenText.matches("Network & internet", "network and internet"))
    }

    @Test
    fun `compact form matches hyphenated labels`() {
        assertTrue(SpokenText.matches("Wi-Fi", "wifi"))
        assertTrue(SpokenText.matches("Wi-Fi calling", "wifi calling"))
    }

    @Test
    fun `punctuation around a word does not hide it`() {
        assertTrue(SpokenText.matches("Battery:", "battery"))
        assertTrue(SpokenText.matches("Settings \u2022 Battery", "battery"))
    }

    @Test
    fun `unrelated labels stay unmatched`() {
        assertFalse(SpokenText.matches("Connected devices", "battery"))
        assertFalse(SpokenText.matches("Bluetooth", "battery"))
    }

    @Test
    fun `blank targets match nothing`() {
        assertFalse(SpokenText.matches("Battery", "   "))
        assertFalse(SpokenText.matches("Battery", ""))
    }

    @Test
    fun `non latin letters survive folding`() {
        assertEquals("वाई फाई", SpokenText.fold("वाई-फाई"))
        assertTrue(SpokenText.matches("वाई-फाई", "वाई फाई"))
    }
}
