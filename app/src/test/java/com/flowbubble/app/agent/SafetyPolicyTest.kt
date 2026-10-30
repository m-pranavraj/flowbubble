package com.flowbubble.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyPolicyTest {

    @Test
    fun `ordinary controls are safe`() {
        assertEquals(RiskLevel.SAFE, SafetyPolicy.assessTap("Search").level)
        assertEquals(RiskLevel.SAFE, SafetyPolicy.assessTap("Continue").level)
        assertEquals(RiskLevel.SAFE, SafetyPolicy.assessTap("Next").level)
    }

    @Test
    fun `sending and money controls require confirmation`() {
        assertEquals(RiskLevel.CONFIRM, SafetyPolicy.assessTap("Send").level)
        assertEquals(RiskLevel.CONFIRM, SafetyPolicy.assessTap("Pay now").level)
        assertEquals(RiskLevel.CONFIRM, SafetyPolicy.assessTap("Buy tickets").level)
        assertEquals(RiskLevel.CONFIRM, SafetyPolicy.assessTap("Delete account").level)
        assertEquals(RiskLevel.CONFIRM, SafetyPolicy.assessTap("Install update").level)
        assertEquals(RiskLevel.CONFIRM, SafetyPolicy.assessTap("Call mom").level)
    }

    @Test
    fun `credential controls are blocked`() {
        assertEquals(RiskLevel.BLOCKED, SafetyPolicy.assessTap("Password").level)
        assertEquals(RiskLevel.BLOCKED, SafetyPolicy.assessTap("Enter CVV").level)
        assertEquals(RiskLevel.BLOCKED, SafetyPolicy.assessTap("One-time code").level)
        assertEquals(RiskLevel.BLOCKED, SafetyPolicy.assessTap("Card number").level)
    }

    @Test
    fun `submitting a search is safe but composing a message is not`() {
        assertEquals(RiskLevel.SAFE, SafetyPolicy.assessSubmission("Search YouTube").level)
        assertEquals(RiskLevel.SAFE, SafetyPolicy.assessSubmission("Find products").level)
        assertEquals(RiskLevel.CONFIRM, SafetyPolicy.assessSubmission("Message Priya").level)
        assertEquals(RiskLevel.CONFIRM, SafetyPolicy.assessSubmission("").level)
        assertEquals(RiskLevel.CONFIRM, SafetyPolicy.assessSubmission("Full name").level)
        assertEquals(RiskLevel.BLOCKED, SafetyPolicy.assessSubmission("Password").level)
    }

    @Test
    fun `credential labels are recognized`() {
        assertTrue(SafetyPolicy.isCredentialLabel("CVV"))
        assertTrue(SafetyPolicy.isCredentialLabel("Security code"))
        assertFalse(SafetyPolicy.isCredentialLabel("Search"))
        assertFalse(SafetyPolicy.isCredentialLabel("Send"))
    }

    @Test
    fun `verification prompts are never tappable`() {
        assertEquals(RiskLevel.BLOCKED, SafetyPolicy.assessTap("Verify you are not a robot").level)
        assertEquals(RiskLevel.BLOCKED, SafetyPolicy.assessTap("Enter CAPTCHA").level)
        assertEquals(RiskLevel.BLOCKED, SafetyPolicy.assessTap("Use fingerprint").level)
    }

    @Test
    fun `closing is safe but giving up or cancelling an order is not`() {
        assertEquals(RiskLevel.SAFE, SafetyPolicy.assessTap("Close").level)
        assertEquals(RiskLevel.SAFE, SafetyPolicy.assessTap("Cancel").level)
        assertEquals(RiskLevel.CONFIRM, SafetyPolicy.assessTap("Dismiss").level)
        assertEquals(RiskLevel.CONFIRM, SafetyPolicy.assessTap("Discard draft").level)
        assertEquals(RiskLevel.CONFIRM, SafetyPolicy.assessTap("Cancel order").level)
        assertEquals(RiskLevel.CONFIRM, SafetyPolicy.assessTap("Cancel subscription").level)
        assertEquals(RiskLevel.CONFIRM, SafetyPolicy.assessTap("Allow access").level)
    }
}
