package com.flowbubble.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEvidenceTest {

    @Test
    fun `field text confirms the write`() {
        assertTrue(TextEvidence.confirmed("pranav", "pranav", { false }))
    }

    @Test
    fun `composers that capitalize the first letter still confirm`() {
        assertTrue(TextEvidence.confirmed("pranav", "Pranav", { false }))
    }

    @Test
    fun `field holding more text than was typed confirms`() {
        assertTrue(TextEvidence.confirmed("lofi beats", "Search: lofi beats now", { false }))
    }

    @Test
    fun `lost focus falls back to text that is visibly on screen`() {
        assertTrue(TextEvidence.confirmed("pranav", null, { it == "pranav" }))
    }

    @Test
    fun `nothing changed and nothing visible is a failure`() {
        assertFalse(TextEvidence.confirmed("pranav", "", { false }))
    }

    @Test
    fun `unrelated field text does not confirm`() {
        assertFalse(TextEvidence.confirmed("hello there", "draft to Priya", { false }))
    }

    @Test
    fun `blank input never confirms`() {
        assertFalse(TextEvidence.confirmed("   ", "anything", { true }))
    }
}
