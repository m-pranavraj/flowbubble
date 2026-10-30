package com.flowbubble.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartActionsTest {

    @Test
    fun `finds a phone number`() {
        val actions = SmartActions.detect("Call +91 98765 43210 for support")
        val phone = actions.first { it.kind == ActionEntityKind.PHONE }
        assertEquals("+919876543210", phone.value)
        assertEquals("+91 98765 43210", phone.display)
    }

    @Test
    fun `finds an email address`() {
        val actions = SmartActions.detect("Questions? Write to sarah@example.com today.")
        val email = actions.first { it.kind == ActionEntityKind.EMAIL }
        assertEquals("sarah@example.com", email.value)
    }

    @Test
    fun `finds a web link and trims trailing punctuation`() {
        val actions = SmartActions.detect("Read more at https://example.com/refunds.")
        val url = actions.first { it.kind == ActionEntityKind.URL }
        assertEquals("https://example.com/refunds", url.value)
    }

    @Test
    fun `finds a street address`() {
        val actions = SmartActions.detect("Visit us at 221 Baker Street, London")
        val address = actions.first { it.kind == ActionEntityKind.ADDRESS }
        assertEquals("221 Baker Street", address.value)
    }

    @Test
    fun `orders results by their position on screen`() {
        val actions = SmartActions.detect(
            "Call 9876543210 or email help@example.org, or see https://example.org/help"
        )
        assertEquals(
            listOf(ActionEntityKind.PHONE, ActionEntityKind.EMAIL, ActionEntityKind.URL),
            actions.map { it.kind }
        )
    }

    @Test
    fun `ignores dates and short numbers`() {
        val actions = SmartActions.detect("Invoice dated 2024-10-30, order 12345, room 7")
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `deduplicates repeated entities and respects the limit`() {
        val text = "Call 9876543210. Again: 9876543210. Mail a@b.co and c@d.co"
        val actions = SmartActions.detect(text)
        assertEquals(1, actions.count { it.kind == ActionEntityKind.PHONE })
        assertEquals(3, actions.size)
        assertEquals(1, SmartActions.detect(text, limit = 1).size)
    }

    @Test
    fun `returns nothing for blank text`() {
        assertTrue(SmartActions.detect("   ").isEmpty())
        assertTrue(SmartActions.detect("no entities here").isEmpty())
    }
}
