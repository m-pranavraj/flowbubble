package com.flowbubble.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CriticalPolicyTest {

    @Test
    fun `spending money is critical`() {
        assertEquals(CriticalCategory.SPEND_MONEY, CriticalPolicy.classify("Pay now")?.category)
        assertEquals(CriticalCategory.SPEND_MONEY, CriticalPolicy.classify("Buy tickets")?.category)
        assertEquals(CriticalCategory.SPEND_MONEY, CriticalPolicy.classify("Checkout")?.category)
    }

    @Test
    fun `transferring money outranks spending`() {
        assertEquals(CriticalCategory.TRANSFER_MONEY, CriticalPolicy.classify("Transfer funds to account")?.category)
        assertEquals(CriticalCategory.TRANSFER_MONEY, CriticalPolicy.classify("Send money to Raj")?.category)
    }

    @Test
    fun `deleting user data for good is critical`() {
        assertEquals(CriticalCategory.IRREVERSIBLE_DELETE, CriticalPolicy.classify("Delete account")?.category)
        assertEquals(CriticalCategory.IRREVERSIBLE_DELETE, CriticalPolicy.classify("Erase everything")?.category)
        assertEquals(CriticalCategory.IRREVERSIBLE_DELETE, CriticalPolicy.classify("factory reset")?.category)
        assertEquals(CriticalCategory.IRREVERSIBLE_DELETE, CriticalPolicy.classify("reset device")?.category)
    }

    @Test
    fun `a plain clear or dismiss is not an irreversible delete`() {
        assertNull(CriticalPolicy.classify("Clear"))
        assertNull(CriticalPolicy.classify("Dismiss"))
        assertNull(CriticalPolicy.classify("Remove from list"))
    }

    @Test
    fun `changing a password is critical as an intent`() {
        assertEquals(CriticalCategory.PASSWORD_CHANGE, CriticalPolicy.classify("Change password")?.category)
        assertEquals(CriticalCategory.PASSWORD_CHANGE, CriticalPolicy.classify("New password")?.category)
    }

    @Test
    fun `ordinary controls classify as nothing`() {
        assertNull(CriticalPolicy.classify(""))
        assertNull(CriticalPolicy.classify("Search"))
        assertNull(CriticalPolicy.classify("Continue"))
        assertNull(CriticalPolicy.classify("Next"))
    }

    @Test
    fun `every critical match carries the critical tier and a reason`() {
        val match = CriticalPolicy.classify("Pay now")
        assertNotNull(match)
        assertEquals(RiskTier.CRITICAL, match!!.tier)
        assertTrue(match.reason.isNotBlank())
    }

    @Test
    fun `sending to an unnamed recipient is critical but a named one passes`() {
        assertEquals(CriticalCategory.SEND_TO_UNNAMED, CriticalPolicy.classifySendToPerson("Priya", listOf("Raj"))?.category)
        assertEquals(CriticalCategory.SEND_TO_UNNAMED, CriticalPolicy.classifySendToPerson("Raj", emptyList())?.category)
        assertNull(CriticalPolicy.classifySendToPerson("Raj", listOf("raj")))
        assertNull(CriticalPolicy.classifySendToPerson("", listOf("Raj")))
    }

    @Test
    fun `a tier maps from the risk level it grew out of`() {
        assertEquals(RiskTier.SAFE, RiskTier.forLevel(RiskLevel.SAFE))
        assertEquals(RiskTier.CONFIRM, RiskTier.forLevel(RiskLevel.CONFIRM))
        assertEquals(RiskTier.BLOCKED, RiskTier.forLevel(RiskLevel.BLOCKED))
    }
}
