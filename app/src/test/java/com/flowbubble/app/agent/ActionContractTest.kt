package com.flowbubble.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionContractTest {

    private val payContract = ActionContract.forTap("Pay now", null, SafetyPolicy.assessTap("Pay now"))

    @Test
    fun `a money tap builds a critical contract keyed to its exact id`() {
        assertEquals(RiskTier.CRITICAL, payContract.tier)
        assertEquals("tap:Pay now", payContract.id)
        assertTrue(payContract.requiresFreshConfirmation)
        assertTrue(payContract.requiresConfirmation)
    }

    @Test
    fun `an ordinary control keeps the tier safety policy already chose`() {
        assertEquals(RiskTier.CONFIRM, ActionContract.forTap("Send", null, SafetyPolicy.assessTap("Send")).tier)
        assertEquals(RiskTier.SAFE, ActionContract.forTap("Search", null, SafetyPolicy.assessTap("Search")).tier)
        assertFalse(ActionContract.forTap("Search", null, SafetyPolicy.assessTap("Search")).requiresConfirmation)
    }

    @Test
    fun `the id carries the ordinal so two controls never share one confirmation`() {
        assertEquals("tap:Pay now#2", ActionContract.forTap("Pay now", 2, SafetyPolicy.assessTap("Pay now")).id)
    }

    @Test
    fun `a critical contract needs a fresh approval for exactly its id`() {
        assertFalse(payContract.mayRun(null))
        assertFalse(payContract.mayRun(ActionConfirmation.approved("tap:Pay now#2")))
        assertFalse(payContract.mayRun(ActionConfirmation.TimedOut))
        assertFalse(payContract.mayRun(ActionConfirmation(payContract.id, ConfirmationStatus.APPROVED, freshThisTurn = false)))
        assertTrue(payContract.mayRun(ActionConfirmation.approved(payContract.id)))
    }

    @Test
    fun `a confirm contract does not demand freshness but still demands the exact id`() {
        val send = ActionContract.forTap("Send", null, SafetyPolicy.assessTap("Send"))
        assertTrue(send.mayRun(ActionConfirmation(send.id, ConfirmationStatus.APPROVED, freshThisTurn = false)))
        assertFalse(send.mayRun(ActionConfirmation.approved("tap:Pay now")))
        assertFalse(send.mayRun(null))
    }

    @Test
    fun `blank evidence means any real screen change proves it`() {
        assertTrue(payContract.provenBy(emptyList(), screenChanged = true))
        assertFalse(payContract.provenBy(emptyList(), screenChanged = false))
    }

    @Test
    fun `named evidence is matched against the labels that appeared`() {
        val contract = ActionContract(
            id = "send-report",
            target = "Weekly report",
            change = "shares the report",
            tier = RiskTier.CONFIRM,
            expectedEvidence = "Sent",
            fallbackMessage = "I shared it, but I never saw proof it went out."
        )
        assertTrue(contract.provenBy(listOf("Message sent"), screenChanged = false))
        assertFalse(contract.provenBy(listOf("Draft"), screenChanged = false))
    }

    @Test
    fun `the outcome note is a proof line when shown and the fallback otherwise`() {
        val contract = ActionContract(
            id = "share",
            target = "Report",
            change = "shares",
            tier = RiskTier.CONFIRM,
            expectedEvidence = "Sent",
            fallbackMessage = "I can't prove the share landed."
        )
        assertEquals("Did it: \u201CReport\u201D", contract.outcomeNote(listOf("Sent"), screenChanged = false))
        assertEquals("I can't prove the share landed.", contract.outcomeNote(listOf("Nothing"), screenChanged = false))
    }

    @Test
    fun `a critical fallback states the danger rather than claiming success`() {
        assertTrue(payContract.fallbackMessage.contains("spends or commits your money"))
        assertFalse(payContract.fallbackMessage.startsWith("Did it"))
    }

    @Test
    fun `an unnamed recipient is surfaced on the contract for re-confirmation`() {
        val toRaj = ActionContract(
            id = "send-to",
            target = "Chat",
            change = "sends a message",
            tier = RiskTier.CONFIRM,
            fallbackMessage = "I can't prove it sent.",
            recipient = "Raj",
            userNamedRecipients = listOf("Priya")
        )
        assertNotNull(toRaj.unnamedRecipientMatch())
        assertEquals(CriticalCategory.SEND_TO_UNNAMED, toRaj.unnamedRecipientMatch()?.category)

        val toNamed = toRaj.copy(userNamedRecipients = listOf("raj"))
        assertNull(toNamed.unnamedRecipientMatch())

        val noRecipient = toRaj.copy(recipient = "")
        assertNull(noRecipient.unnamedRecipientMatch())
    }

    @Test
    fun `a contract refuses to be built without an id, a change, or a critical fallback`() {
        assertThrows(IllegalArgumentException::class.java) {
            ActionContract(id = "", target = "x", change = "c", tier = RiskTier.SAFE, fallbackMessage = "f")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActionContract(id = "a", target = "x", change = "", tier = RiskTier.SAFE, fallbackMessage = "f")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActionContract(id = "a", target = "x", change = "c", tier = RiskTier.CRITICAL, fallbackMessage = "")
        }
    }

    @Test
    fun `a blocked contract never runs even with a fresh approval`() {
        val blocked = ActionContract(
            id = "tap:Password",
            target = "Password",
            change = "taps a credential",
            tier = RiskTier.BLOCKED,
            fallbackMessage = "I won't touch a credential field."
        )
        assertFalse(blocked.mayRun(ActionConfirmation.approved(blocked.id)))
    }
}
