package com.flowbubble.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Facts one task carries into the next app: what was seen, where it was seen, and whether anything
 * actually vouched for it. The store has two hard edges, time and ownership, because a stale fact and
 * a fact borrowed by a later task both get typed as though they had been read just now.
 */
class TaskContextTest {

    private var now = 0L

    private fun context(ttlMs: Long = 300_000L, maxFacts: Int = 40) =
        TaskContext("task-1", clock = { now }, factTtlMs = ttlMs, maxFacts = maxFacts)

    private fun remember(
        ctx: TaskContext,
        key: String = "confirmation_number",
        value: String = "GM-4471",
        packageName: String = "com.google.android.gm",
        source: FactSource = FactSource.ACCESSIBILITY_TEXT
    ): FactRecord = ctx.record(key, value, packageName, source, screenRevision = 3)

    @Test
    fun `an accessibility reading is a verified fact`() {
        val ctx = context()
        assertTrue(remember(ctx) is FactRecord.Stored)
        val fact = ctx.fact("confirmation_number")
        assertNotNull(fact)
        assertEquals("GM-4471", fact!!.value)
        assertEquals("com.google.android.gm", fact.packageName)
        assertEquals(3, fact.screenRevision)
        assertTrue(fact.verified)
        assertEquals(FactSource.ACCESSIBILITY_TEXT, fact.source)
    }

    @Test
    fun `the key is matched however it was spelled`() {
        val ctx = context()
        remember(ctx)
        assertEquals("GM-4471", ctx.fact("  Confirmation_Number ")?.value)
    }

    @Test
    fun `pixels are kept but marked as unverified`() {
        val ctx = context()
        remember(ctx, source = FactSource.OCR)
        val fact = ctx.fact("confirmation_number")
        assertNotNull(fact)
        assertFalse(fact!!.verified)
        assertNull(ctx.fact("confirmation_number", verifiedOnly = true))
        assertTrue(ctx.facts(verifiedOnly = true).isEmpty())
        assertEquals(1, ctx.facts().size)
        assertTrue(fact.source.describe().contains("pixels"))
    }

    @Test
    fun `a fact nothing confirmed is handed back for checking, not typed`() {
        val ctx = context()
        remember(ctx, source = FactSource.INFERRED)
        val handoff = ctx.handoff("confirmation_number", "com.whatsapp")
        assertTrue(handoff is FactHandoff.NeedsRecheck)
        val recheck = handoff as FactHandoff.NeedsRecheck
        assertEquals("GM-4471", recheck.fact.value)
        assertTrue(recheck.reason.isNotBlank())
    }

    @Test
    fun `a verified fact crosses into the next app`() {
        val ctx = context()
        remember(ctx)
        val elsewhere = ctx.handoff("confirmation_number", "com.whatsapp")
        assertTrue(elsewhere is FactHandoff.Usable)
        assertTrue((elsewhere as FactHandoff.Usable).crossedApps)

        val sameApp = ctx.handoff("confirmation_number", "com.google.android.gm")
        assertFalse((sameApp as FactHandoff.Usable).crossedApps)
    }

    @Test
    fun `a fact that was never recorded is missing, not empty`() {
        val ctx = context()
        remember(ctx)
        val handoff = ctx.handoff("order_total", "com.whatsapp")
        assertTrue(handoff is FactHandoff.Missing)
        assertEquals("order_total", (handoff as FactHandoff.Missing).key)
    }

    @Test
    fun `a fact expires and cannot be read again`() {
        val ctx = context(ttlMs = 300_000L)
        remember(ctx)
        now = 299_999L
        assertNotNull(ctx.fact("confirmation_number"))

        now = 300_001L
        assertNull(ctx.fact("confirmation_number"))
        assertTrue(ctx.facts().isEmpty())
        assertEquals(1, ctx.expire())
        assertEquals(0, ctx.size())
        assertTrue(ctx.handoff("confirmation_number", "com.whatsapp") is FactHandoff.Missing)
    }

    @Test
    fun `age is measured from when it was seen`() {
        val ctx = context()
        remember(ctx)
        now = 60_000L
        assertEquals(60_000L, ctx.fact("confirmation_number")!!.ageMs(now))
        assertTrue(ctx.fact("confirmation_number")!!.isFresh(ctx.factTtlMs, now))
    }

    @Test
    fun `the newest reading of a key is the answer`() {
        val ctx = context()
        remember(ctx, value = "GM-1111")
        now = 1_000L
        remember(ctx, value = "GM-2222")
        assertEquals(1, ctx.size())
        assertEquals("GM-2222", ctx.fact("confirmation_number")?.value)
    }

    @Test
    fun `the store is bounded so a long task cannot grow it without limit`() {
        val ctx = context(maxFacts = 2)
        remember(ctx, key = "first")
        remember(ctx, key = "second")
        remember(ctx, key = "third")
        assertEquals(2, ctx.size())
        assertNull(ctx.fact("first"))
        assertNotNull(ctx.fact("third"))
    }

    @Test
    fun `the task's memory is emptied when it ends`() {
        val ctx = context()
        remember(ctx)
        ctx.close("you stopped it")
        assertFalse(ctx.isOpen)
        assertEquals("you stopped it", ctx.closedReason)
        assertEquals(0, ctx.size())
        assertNull(ctx.fact("confirmation_number"))
        val refused = ctx.record("later", "value", "com.example", FactSource.USER_SPOKEN)
        assertTrue(refused is FactRecord.Refused)
    }

    @Test
    fun `a later task reading through the same store gets nothing`() {
        val ctx = context()
        remember(ctx)
        assertEquals(1, ctx.viewFor("task-1").size)
        assertTrue(ctx.viewFor("task-2").isEmpty())
    }

    @Test
    fun `credentials are never kept as facts`() {
        val ctx = context()
        val password = ctx.record("password", "hunter2", "com.example", FactSource.ACCESSIBILITY_TEXT)
        val card = ctx.record("card number", "4111 1111 1111 1111", "com.example", FactSource.OCR)
        assertTrue(password is FactRecord.Refused)
        assertTrue(card is FactRecord.Refused)
        assertEquals(0, ctx.size())
    }

    @Test
    fun `there has to be something to remember`() {
        val ctx = context()
        val noKey = ctx.record("   ", "GM-4471", "com.example", FactSource.ACCESSIBILITY_TEXT)
        val noValue = ctx.record("confirmation_number", "   ", "com.example", FactSource.ACCESSIBILITY_TEXT)
        assertTrue(noKey is FactRecord.Refused)
        assertTrue(noValue is FactRecord.Refused)
        assertEquals(0, ctx.size())
    }

    @Test
    fun `a stored value is capped and never carried into a rendered line`() {
        val ctx = context()
        remember(ctx, value = "y".repeat(900))
        val fact = ctx.fact("confirmation_number")
        assertNotNull(fact)
        assertEquals(160, fact!!.value.length)
        assertFalse(fact.describe().contains("yyyy"))
        assertTrue(fact.describe().contains("confirmation_number"))
    }

    @Test
    fun `facts can be filtered to the app they came from`() {
        val ctx = context()
        remember(ctx, key = "tracking")
        remember(ctx, key = "address", value = "1 Infinite Loop", packageName = "com.apps.apple")
        assertEquals(1, ctx.facts(fromApp = "com.apps.apple").size)
        assertEquals("address", ctx.facts(fromApp = "com.apps.apple").first().key)
        assertEquals(2, ctx.facts().size)
        assertEquals(listOf("address", "tracking"), ctx.facts().map { it.key }.sorted())
    }

    @Test
    fun `a spoken fact is trusted but is not a screen reading`() {
        val ctx = context()
        ctx.record("recipient", "Priya", "spoken", FactSource.USER_SPOKEN)
        val fact = ctx.fact("recipient")
        assertNotNull(fact)
        assertTrue(fact!!.verified)
        assertEquals(-1, fact.screenRevision)
    }
}
