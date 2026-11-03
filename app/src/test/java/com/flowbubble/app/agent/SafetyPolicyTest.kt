package com.flowbubble.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun `money and irreversible deletion stay confirm-level but rise to the critical tier`() {
        // Extending only: the level is unchanged, so no existing gate is weakened or re-tiered.
        assertEquals(RiskLevel.CONFIRM, SafetyPolicy.assessTap("Pay now").level)
        assertEquals(RiskTier.CRITICAL, SafetyPolicy.assessTap("Pay now").tier)
        assertEquals(RiskTier.CRITICAL, SafetyPolicy.assessTap("Transfer money").tier)
        assertEquals(RiskTier.CRITICAL, SafetyPolicy.assessTap("Delete account").tier)
        assertEquals(RiskTier.CRITICAL, SafetyPolicy.assessTap("Submit payment").tier)
    }

    @Test
    fun `ordinary confirm controls do not reach the critical tier`() {
        assertEquals(RiskTier.CONFIRM, SafetyPolicy.assessTap("Send").tier)
        assertEquals(RiskTier.CONFIRM, SafetyPolicy.assessTap("Install update").tier)
        assertEquals(RiskTier.CONFIRM, SafetyPolicy.assessTap("Call mom").tier)
        assertEquals(RiskTier.CONFIRM, SafetyPolicy.assessTap("Dismiss").tier)
        assertEquals(RiskTier.SAFE, SafetyPolicy.assessTap("Search").tier)
    }

    @Test
    fun `credential block outranks the critical tier even for a money word`() {
        assertEquals(RiskLevel.BLOCKED, SafetyPolicy.assessTap("Pay with card number").level)
        assertEquals(RiskTier.BLOCKED, SafetyPolicy.assessTap("Pay with card number").tier)
    }

    @Test
    fun `sending to an unnamed recipient is critical but a named one is only confirm`() {
        assertEquals(RiskTier.CRITICAL, SafetyPolicy.assessSendToPerson("Raj", listOf("Mom", "Priya")).tier)
        assertEquals(RiskLevel.CONFIRM, SafetyPolicy.assessSendToPerson("Raj", listOf("Mom")).level)
        assertEquals(RiskTier.CONFIRM, SafetyPolicy.assessSendToPerson("Mom", listOf("mom")).tier)
    }

    @Test
    fun `a critical action cannot execute without an explicit fresh confirmation`() {
        val id = "tap:Pay now"
        assertFalse(SafetyPolicy.mayExecute(id, RiskTier.CRITICAL, null))
        assertFalse(SafetyPolicy.mayExecute(id, RiskTier.CRITICAL, ActionConfirmation.approved("other")))
        assertFalse(SafetyPolicy.mayExecute(id, RiskTier.CRITICAL, ActionConfirmation(id, ConfirmationStatus.DENIED)))
        assertFalse(SafetyPolicy.mayExecute(id, RiskTier.CRITICAL, ActionConfirmation.TimedOut))
        assertFalse(
            SafetyPolicy.mayExecute(
                id,
                RiskTier.CRITICAL,
                ActionConfirmation(id, ConfirmationStatus.APPROVED, freshThisTurn = false)
            )
        )
        assertTrue(SafetyPolicy.mayExecute(id, RiskTier.CRITICAL, ActionConfirmation.approved(id)))
    }

    @Test
    fun `blocked never runs and safe always runs regardless of confirmation`() {
        assertFalse(SafetyPolicy.mayExecute("x", RiskTier.BLOCKED, ActionConfirmation.approved("x")))
        assertTrue(SafetyPolicy.mayExecute("x", RiskTier.SAFE, null))
    }

    @Test
    fun `ordinary confirm needs an approval for this exact step`() {
        assertTrue(SafetyPolicy.mayExecute("tap:Send", RiskTier.CONFIRM, ActionConfirmation.approved("tap:Send")))
        assertFalse(SafetyPolicy.mayExecute("tap:Send", RiskTier.CONFIRM, null))
        assertFalse(SafetyPolicy.mayExecute("tap:Send", RiskTier.CONFIRM, ActionConfirmation.approved("tap:Pay")))
    }

    @Test
    fun `the same-kind ceiling stops a loop that repeats one control`() {
        val budget = ReactiveBudget()
        val loop = observationLoop()
        val underCeiling = loop.copy(actionsByKind = mapOf("tap:pay now" to budget.maxActionsOfOneKind - 1))
        assertNull(budget.stopReason(underCeiling))

        val atCeiling = loop.copy(actionsByKind = mapOf("tap:pay now" to budget.maxActionsOfOneKind))
        val reason = budget.stopReason(atCeiling)
        assertNotNull(reason)
        assertTrue(reason!!.contains("repeating the same kind of action"))

        val spread = loop.copy(actionsByKind = mapOf("tap:a" to 2, "tap:b" to 2, "tap:c" to 2))
        assertNull(budget.stopReason(spread))
    }

    @Test
    fun `recording an action kind increments the tally`() {
        val loop = observationLoop()
        val recorded = loop.recordActionKind("tap:send").recordActionKind("tap:send").recordActionKind("scroll")
        assertEquals(2, recorded.actionsByKind["tap:send"]!!)
        assertEquals(1, recorded.actionsByKind["scroll"]!!)
        assertEquals(2, recorded.mostRepeatedActionKind)
    }

    @Test
    fun `earlier budget branches still win over the same-kind ceiling`() {
        val loop = observationLoop().copy(
            stepsUsed = 99,
            actionsByKind = mapOf("tap:pay now" to 99)
        )
        val reason = ReactiveBudget().stopReason(loop)
        assertTrue(reason!!.contains("steps"))
    }

    private fun observationLoop(): LoopContext = LoopContext(
        goal = ReactiveGoal("open the store and buy it", GoalKind.GENERIC),
        now = ScreenObservation(revision = 0, packageName = "com.shop", labels = emptyList(), textHash = 1)
    )
}
