package com.flowbubble.app.core

import com.flowbubble.app.agent.AutomationDecision
import com.flowbubble.app.agent.AutomationEngine
import com.flowbubble.app.agent.AutomationRule
import com.flowbubble.app.agent.AutomationSignal
import com.flowbubble.app.agent.AutomationTrigger
import com.flowbubble.app.agent.TriggerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * The store is tested through the same read and write path the device uses, just over an in-memory
 * record set instead of a preference file, so nothing here needs Android or a fake context.
 */
class AutomationStoreTest {

    private val field = Char(0x1F).toString()
    private val item = Char(0x1E).toString()

    private fun store() = AutomationStore.inMemory()

    private fun clockAt(hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun rule(
        id: String = "auto-1",
        name: String = "Morning briefing",
        kind: TriggerKind = TriggerKind.TIME,
        params: Map<String, String> = mapOf(AutomationTrigger.AT to "07:30", AutomationTrigger.DAILY to "true"),
        steps: List<String> = listOf("read my notifications", "open YouTube")
    ) = AutomationRule(
        id = id,
        name = name,
        trigger = AutomationTrigger(kind, params),
        steps = steps
    )

    @Test
    fun `a saved rule reads back exactly what it was`() {
        val store = store()
        val original = AutomationRule(
            id = "auto-morning",
            name = "Morning briefing",
            trigger = AutomationTrigger(
                TriggerKind.TIME,
                mapOf(
                    AutomationTrigger.AT to "07:30",
                    AutomationTrigger.DAILY to "true",
                    AutomationTrigger.WINDOW to "20"
                )
            ),
            steps = listOf("read my notifications", "open YouTube"),
            enabled = true,
            lastRunAtMillis = 1_700_000_000_000L,
            lastTriggerKey = "time:2026-11-03T07:30",
            lastResult = "Started TIME",
            firedKeys = listOf("time:2026-11-03T07:30", "time:2026-11-02T07:30"),
            createdAtMillis = 1_690_000_000_000L
        )

        val saved = AutomationStore.save(store, original)
        assertTrue(saved is AutomationStore.SaveResult.Saved)
        val stored = (saved as AutomationStore.SaveResult.Saved).rule

        assertEquals(listOf(stored), AutomationStore.list(store))
        assertEquals(stored, AutomationStore.find(store, "auto-morning"))
        assertEquals(stored, AutomationStore.find(store, "Morning briefing"))
        assertEquals(stored, AutomationStore.decode(AutomationStore.encode(stored)))
        assertEquals(1_700_000_000_000L, AutomationStore.find(store, "auto-morning")?.lastRunAtMillis)
        assertEquals(listOf("time:2026-11-03T07:30", "time:2026-11-02T07:30"), stored.firedKeys)
    }

    @Test
    fun `a rule that sends a message still asks after a round trip`() {
        val store = store()
        val sending = rule(steps = listOf("message Priya saying hi"))
        val saved = AutomationStore.save(store, sending) as AutomationStore.SaveResult.Saved

        val readBack = AutomationStore.find(store, saved.rule.id)
        assertNotNull(readBack)
        assertTrue(readBack!!.requiresConfirmation)
        assertEquals(saved.rule, readBack)
    }

    @Test
    fun `a rule that is not safe to store is rejected with a plain reason`() {
        val store = store()

        assertTrue(
            (AutomationStore.save(store, rule(name = "   ")) as AutomationStore.SaveResult.Rejected)
                .reason.contains("name")
        )
        assertTrue(
            (AutomationStore.save(store, rule(steps = emptyList())) as AutomationStore.SaveResult.Rejected)
                .reason.contains("step")
        )
        assertTrue(
            (
                AutomationStore.save(
                    store,
                    rule(params = mapOf(AutomationTrigger.AT to "7pm"))
                ) as AutomationStore.SaveResult.Rejected
                ).reason.contains("07:30")
        )
        assertTrue(
            (
                AutomationStore.save(
                    store,
                    rule(kind = TriggerKind.APP_OPENED, params = mapOf(AutomationTrigger.PACKAGE to ""))
                ) as AutomationStore.SaveResult.Rejected
                ).reason.contains("app")
        )
        assertTrue(
            (
                AutomationStore.save(
                    store,
                    rule(kind = TriggerKind.PHRASE, params = mapOf(AutomationTrigger.PHRASE to ""))
                ) as AutomationStore.SaveResult.Rejected
                ).reason.contains("phrase")
        )
        assertTrue(
            (
                AutomationStore.save(
                    store,
                    rule(kind = TriggerKind.NOTIFICATION_MATCHES, params = mapOf(AutomationTrigger.SENDER to ""))
                ) as AutomationStore.SaveResult.Rejected
                ).reason.contains("match")
        )
        assertTrue(AutomationStore.list(store).isEmpty())
    }

    @Test
    fun `a stored record cannot be split by the characters that divide it`() {
        val store = store()
        val smuggled = rule(
            name = "Bad" + field + "Name",
            steps = listOf("open YouTube" + item + "set a timer for ten minutes")
        )

        val saved = AutomationStore.save(store, smuggled) as AutomationStore.SaveResult.Saved
        val readBack = AutomationStore.list(store)

        assertEquals(1, readBack.size)
        assertEquals("BadName", readBack.first().name)
        assertEquals(listOf(saved.rule), readBack)
        assertEquals(1, saved.rule.steps.size)
    }

    @Test
    fun `a damaged record is skipped instead of breaking the list`() {
        val store = store()
        AutomationStore.save(store, rule())
        store.write("auto-short", "only one field")
        store.write(
            "auto-ghost",
            listOf("auto-ghost", "Ghost rule", "NOT_A_TRIGGER", "", "open YouTube", "true", "0", "", "", "", "0")
                .joinToString(field)
        )

        val rules = AutomationStore.list(store)
        assertEquals(1, rules.size)
        assertEquals("Morning briefing", rules.first().name)
    }

    @Test
    fun `turning one rule off leaves its steps alone`() {
        val store = store()
        val saved = AutomationStore.save(store, rule()) as AutomationStore.SaveResult.Saved

        assertTrue(AutomationStore.setEnabled(store, saved.rule.id, false))
        val disabled = AutomationStore.find(store, saved.rule.id)
        assertNotNull(disabled)
        assertFalse(disabled!!.enabled)
        assertEquals(saved.rule.steps, disabled.steps)
        assertEquals(saved.rule.trigger, disabled.trigger)

        assertTrue(AutomationStore.setEnabled(store, saved.rule.id, true))
        assertTrue(AutomationStore.find(store, saved.rule.id)?.enabled == true)
        assertFalse(AutomationStore.setEnabled(store, "auto-missing", true))
    }

    @Test
    fun `a run is stamped so the same trigger cannot fire again`() {
        val store = store()
        val now = clockAt(7, 30)
        val saved = AutomationStore.save(store, rule()) as AutomationStore.SaveResult.Saved
        val fresh = saved.rule

        val first = AutomationEngine.evaluate(fresh, AutomationSignal(nowMillis = now, bubbleRunning = true))
        assertTrue(first is AutomationDecision.Run)
        val key = (first as AutomationDecision.Run).triggerKey

        assertTrue(AutomationStore.recordRun(store, fresh.id, key, now, "Started TIME"))
        val stamped = AutomationStore.find(store, fresh.id)!!
        assertEquals(now, stamped.lastRunAtMillis)
        assertEquals(key, stamped.lastTriggerKey)
        assertTrue(stamped.firedKeys.contains(key))

        val second = AutomationEngine.evaluate(stamped, AutomationSignal(nowMillis = now, bubbleRunning = true))
        assertTrue(second is AutomationDecision.Blocked)
    }

    @Test
    fun `an unanswered ask is never recorded as a run`() {
        val store = store()
        val now = clockAt(7, 30)
        val sending = rule(steps = listOf("message Priya saying hi"))
        val saved = AutomationStore.save(store, sending) as AutomationStore.SaveResult.Saved

        val ask = AutomationEngine.evaluate(saved.rule, AutomationSignal(nowMillis = now, bubbleRunning = true))
        assertTrue(ask is AutomationDecision.Ask)
        val key = (ask as AutomationDecision.Ask).triggerKey

        // The prompt was shown and nobody tapped it: the rule stays unrun.
        assertTrue(AutomationStore.recordAsked(store, saved.rule.id, key, "Needs your OK"))
        val asked = AutomationStore.find(store, saved.rule.id)!!
        assertEquals(0L, asked.lastRunAtMillis)
        assertEquals("Needs your OK", asked.lastResult)
        assertTrue(asked.firedKeys.contains(key))

        val again = AutomationEngine.evaluate(asked, AutomationSignal(nowMillis = now, bubbleRunning = true))
        assertTrue(again is AutomationDecision.Blocked)
    }

    @Test
    fun `a deleted rule goes and the rest stay`() {
        val store = store()
        AutomationStore.save(store, rule(id = "auto-a", name = "Alarm routine"))
        AutomationStore.save(store, rule(id = "auto-b", name = "Evening wind down"))

        assertTrue(AutomationStore.delete(store, "auto-a"))
        assertFalse(AutomationStore.delete(store, "auto-a"))
        assertEquals(listOf("Evening wind down"), AutomationStore.list(store).map { it.name })
    }

    @Test
    fun `find matches an id, an exact name, and only a unique word`() {
        val store = store()
        AutomationStore.save(store, rule(id = "auto-1", name = "Movie night"))
        AutomationStore.save(store, rule(id = "auto-2", name = "Movie morning"))

        assertEquals("auto-1", AutomationStore.find(store, "auto-1")?.id)
        assertEquals("auto-2", AutomationStore.find(store, "MOVIE MORNING")?.id)
        assertNull(AutomationStore.find(store, "movie"))
        assertNull(AutomationStore.find(store, "  "))
        assertEquals("auto-1", AutomationStore.find(store, "night")?.id)
    }

    @Test
    fun `the list keeps to a plain order and a hard limit`() {
        val store = store()
        (1..20).forEach { index ->
            val saved = AutomationStore.save(store, rule(id = "auto-$index", name = "Rule $index"))
            assertTrue(saved is AutomationStore.SaveResult.Saved)
        }
        val rejected = AutomationStore.save(store, rule(id = "auto-21", name = "Rule 21"))
        assertTrue(rejected is AutomationStore.SaveResult.Rejected)
        assertTrue((rejected as AutomationStore.SaveResult.Rejected).reason.contains("20"))

        val names = AutomationStore.list(store).map { it.name }
        assertEquals(names.sorted(), names)
        assertEquals(20, names.size)
    }

    @Test
    fun `a draft starts with the defaults its trigger needs`() {
        val time = AutomationStore.draft(TriggerKind.TIME)
        assertEquals("07:00", time.trigger.param(AutomationTrigger.AT))
        assertEquals("true", time.trigger.param(AutomationTrigger.DAILY))
        assertTrue(time.enabled)
        assertTrue(time.name.isBlank())
        assertTrue(time.steps.isEmpty())
        assertTrue(time.id.isNotBlank())

        val notification = AutomationStore.draft(TriggerKind.NOTIFICATION_MATCHES)
        assertEquals(
            listOf(
                AutomationTrigger.SENDER,
                AutomationTrigger.TITLE,
                AutomationTrigger.BODY,
                AutomationTrigger.WINDOW
            ).sorted(),
            notification.trigger.params.keys.sorted()
        )
        assertNotEquals(time.id, notification.id)
    }

    @Test
    fun `a stored rule keeps the steps in the order they were written`() {
        val store = store()
        val steps = listOf("open YouTube", "set a timer for ten minutes", "take a screenshot")
        val saved = AutomationStore.save(store, rule(steps = steps)) as AutomationStore.SaveResult.Saved

        assertEquals(steps, AutomationStore.decode(AutomationStore.encode(saved.rule))?.steps)
        assertEquals(steps, AutomationStore.find(store, saved.rule.id)?.steps)
    }
}
