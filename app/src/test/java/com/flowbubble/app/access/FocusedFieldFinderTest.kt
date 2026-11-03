package com.flowbubble.app.access

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Dictation reported "Nowhere to type" while a field was visibly focused and the keyboard was up. The
 * search only ever walked *up* from whatever `findFocus` returned, and a Jetpack Compose text field —
 * which is what ChatGPT, Gmail, Chrome and this app's own screens are built from — does not always
 * carry an input-focus node the framework will hand back. The fix is a whole-window answer, so it needs
 * a rule that can be decided without a phone.
 */
class FocusedFieldFinderTest {
    private fun field(
        editable: Boolean = false,
        password: Boolean = false,
        textEntry: Boolean = false,
        focused: Boolean = false
    ) = FieldCandidate(editable = editable, password = password, textEntry = textEntry, focused = focused)

    @Test
    fun `the focused field wins`() {
        val nodes = listOf(field(), field(editable = true, focused = true), field(editable = true))
        assertEquals(1, FocusedFieldFinder.pick(nodes))
    }

    /**
     * The shape Compose hands over: an editable node with no input-focus mark anywhere in the tree.
     * This is the case that shipped as "Nowhere to type".
     */
    @Test
    fun `the only editable field is used when nothing reports focus`() {
        val nodes = listOf(field(), field(editable = true), field())
        assertEquals(1, FocusedFieldFinder.pick(nodes))
    }

    @Test
    fun `a node that only claims text entry still counts as a field`() {
        val nodes = listOf(field(), field(textEntry = true))
        assertEquals(1, FocusedFieldFinder.pick(nodes))
    }

    @Test
    fun `two unfocused fields are not a coin flip`() {
        val nodes = listOf(field(editable = true), field(editable = true))
        assertNull(FocusedFieldFinder.pick(nodes))
    }

    @Test
    fun `a password field is never a target`() {
        val nodes = listOf(field(), field(editable = true, password = true))
        assertNull(FocusedFieldFinder.pick(nodes))
    }

    /**
     * Safety rule, not a convenience: with a credential field focused, dictation must not quietly land
     * in the next field down the screen.
     */
    @Test
    fun `a focused password field stops the search`() {
        val nodes = listOf(field(editable = true, password = true, focused = true), field(editable = true))
        assertNull(FocusedFieldFinder.pick(nodes))
    }

    @Test
    fun `no field at all is no target`() {
        assertNull(FocusedFieldFinder.pick(listOf(field(), field())))
    }
}
