package com.flowbubble.app.agent

import com.flowbubble.app.voice.Command
import com.flowbubble.app.voice.CommandParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

private const val MINUTE = 60_000L

/**
 * The engine is pure Kotlin, so every rule here is decided with a hand-built moment rather than a
 * device: no Robolectric, no running service, nothing that could make a real tap or send a message.
 */
class AutomationEngineTest {

    private fun clockAt(hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun rule(
        kind: TriggerKind,
        params: Map<String, String> = emptyMap(),
        steps: List<String> = listOf("open YouTube"),
        enabled: Boolean = true
    ): AutomationRule = AutomationRule(
        id = "auto-test",
        name = "Test rule",
        trigger = AutomationTrigger(kind, params),
        steps = steps,
        enabled = enabled
    )

    private fun signal(
        nowMillis: Long,
        bubbleRunning: Boolean = true,
        foregroundPackage: String = "",
        foregroundSinceMillis: Long = 0L,
        notification: NotificationSignal? = null,
        spokenPhrase: String = "",
        spokenAtMillis: Long = 0L,
        screenLocked: Boolean = false,
        awaitingAuthentication: Boolean = false
    ) = AutomationSignal(
        nowMillis = nowMillis,
        bubbleRunning = bubbleRunning,
        foregroundPackage = foregroundPackage,
        foregroundSinceMillis = foregroundSinceMillis,
        notification = notification,
        spokenPhrase = spokenPhrase,
        spokenAtMillis = spokenAtMillis,
        screenLocked = screenLocked,
        awaitingAuthentication = awaitingAuthentication
    )

    @Test
    fun `a time rule fires when its clock arrives`() {
        val now = clockAt(7, 30)
        val decision = AutomationEngine.evaluate(
            rule(TriggerKind.TIME, mapOf(AutomationTrigger.AT to "07:30")),
            signal(now)
        )
        assertTrue(decision is AutomationDecision.Run)
        assertEquals(TriggerKind.TIME, (decision as AutomationDecision.Run).matched)
        assertTrue(AutomationEngine.shouldFire(rule(TriggerKind.TIME, mapOf(AutomationTrigger.AT to "07:30")), signal(now)))
    }

    @Test
    fun `a time rule waits for its own clock and not for any other`() {
        val blocked = AutomationEngine.evaluate(
            rule(TriggerKind.TIME, mapOf(AutomationTrigger.AT to "07:30")),
            signal(clockAt(9, 0))
        )
        assertTrue(blocked is AutomationDecision.Blocked)
        assertFalse(AutomationEngine.shouldFire(rule(TriggerKind.TIME, mapOf(AutomationTrigger.AT to "07:30")), signal(clockAt(9, 0))))
    }

    @Test
    fun `an app rule fires for the app it watches and ignores every other`() {
        val now = clockAt(12, 0)
        val watched = rule(
            TriggerKind.APP_OPENED,
            mapOf(AutomationTrigger.PACKAGE to "spotify")
        )
        val opened = AutomationEngine.evaluate(
            watched,
            signal(now, foregroundPackage = "com.spotify.music", foregroundSinceMillis = now - MINUTE)
        )
        assertTrue(opened is AutomationDecision.Run)
        assertEquals(TriggerKind.APP_OPENED, (opened as AutomationDecision.Run).matched)

        val otherApp = AutomationEngine.evaluate(
            watched,
            signal(now, foregroundPackage = "org.telegram.messenger", foregroundSinceMillis = now - MINUTE)
        )
        assertTrue(otherApp is AutomationDecision.Blocked)

        // A poll that has never seen the app arrive cannot claim it just opened.
        val noEpisode = AutomationEngine.evaluate(watched, signal(now, foregroundPackage = "com.spotify.music"))
        assertTrue(noEpisode is AutomationDecision.Blocked)
    }

    @Test
    fun `a notification rule fires only when the newest notification matches`() {
        val now = clockAt(15, 0)
        val watcher = rule(
            TriggerKind.NOTIFICATION_MATCHES,
            mapOf(AutomationTrigger.SENDER to "WhatsApp", AutomationTrigger.TITLE to "delivery")
        )
        val matched = AutomationEngine.evaluate(
            watcher,
            signal(
                now,
                notification = NotificationSignal(
                    key = "0|1234",
                    packageName = "com.whatsapp",
                    appLabel = "WhatsApp",
                    title = "Delivery update",
                    text = "your parcel is outside",
                    postedAtMillis = now - 2 * MINUTE
                )
            )
        )
        assertTrue(matched is AutomationDecision.Run)
        assertEquals(TriggerKind.NOTIFICATION_MATCHES, (matched as AutomationDecision.Run).matched)

        val wrongWords = AutomationEngine.evaluate(
            watcher,
            signal(
                now,
                notification = NotificationSignal(
                    key = "0|9999",
                    packageName = "com.whatsapp",
                    appLabel = "WhatsApp",
                    title = "New message",
                    text = "are you there",
                    postedAtMillis = now - 2 * MINUTE
                )
            )
        )
        assertTrue(wrongWords is AutomationDecision.Blocked)

        val nothingArrived = AutomationEngine.evaluate(watcher, signal(now))
        assertTrue(nothingArrived is AutomationDecision.Blocked)
    }

    @Test
    fun `a phrase rule fires for the words you said just now`() {
        val now = clockAt(20, 0)
        val watcher = rule(TriggerKind.PHRASE, mapOf(AutomationTrigger.PHRASE to "movie time"))

        val said = AutomationEngine.evaluate(
            watcher,
            signal(now, spokenPhrase = "okay movie time", spokenAtMillis = now - MINUTE)
        )
        assertTrue(said is AutomationDecision.Run)
        assertEquals(TriggerKind.PHRASE, (said as AutomationDecision.Run).matched)

        val stale = AutomationEngine.evaluate(
            watcher,
            signal(now, spokenPhrase = "movie time", spokenAtMillis = now - 60 * MINUTE)
        )
        assertTrue(stale is AutomationDecision.Blocked)

        val neverSaid = AutomationEngine.evaluate(watcher, signal(now))
        assertTrue(neverSaid is AutomationDecision.Blocked)
    }

    @Test
    fun `every trigger is refused while the bubble is off`() {
        val now = clockAt(7, 30)
        val cases = listOf(
            rule(TriggerKind.TIME, mapOf(AutomationTrigger.AT to "07:30")) to signal(
                now,
                bubbleRunning = false
            ),
            rule(TriggerKind.APP_OPENED, mapOf(AutomationTrigger.PACKAGE to "spotify")) to signal(
                now,
                bubbleRunning = false,
                foregroundPackage = "com.spotify.music",
                foregroundSinceMillis = now
            ),
            rule(TriggerKind.PHRASE, mapOf(AutomationTrigger.PHRASE to "movie time")) to signal(
                now,
                bubbleRunning = false,
                spokenPhrase = "movie time",
                spokenAtMillis = now
            ),
            rule(TriggerKind.NOTIFICATION_MATCHES, mapOf(AutomationTrigger.BODY to "parcel")) to signal(
                now,
                bubbleRunning = false,
                notification = NotificationSignal(
                    key = "0|77",
                    packageName = "com.whatsapp",
                    appLabel = "WhatsApp",
                    title = "Delivery",
                    text = "your parcel is outside",
                    postedAtMillis = now
                )
            )
        )

        cases.forEach { (watcher, moment) ->
            val decision = AutomationEngine.evaluate(watcher, moment)
            assertTrue("${watcher.trigger.kind} ran with the bubble off", decision is AutomationDecision.Blocked)
            assertTrue(
                decision is AutomationDecision.Blocked &&
                    decision.reason.contains("bubble is off")
            )
            assertFalse(AutomationEngine.shouldFire(watcher, moment))
        }
    }

    @Test
    fun `a turned off rule stays asleep even at its own moment`() {
        val decision = AutomationEngine.evaluate(
            rule(TriggerKind.TIME, mapOf(AutomationTrigger.AT to "07:30"), enabled = false),
            signal(clockAt(7, 30))
        )
        assertTrue(decision is AutomationDecision.Blocked)
        assertTrue((decision as AutomationDecision.Blocked).reason.contains("turned off"))
    }

    @Test
    fun `no rule runs through a lock screen or a sign in prompt`() {
        val now = clockAt(7, 30)
        val watcher = rule(TriggerKind.TIME, mapOf(AutomationTrigger.AT to "07:30"))

        val locked = AutomationEngine.evaluate(watcher, signal(now, screenLocked = true))
        assertTrue(locked is AutomationDecision.Blocked)
        assertTrue((locked as AutomationDecision.Blocked).reason.contains("locked"))

        val authenticating = AutomationEngine.evaluate(watcher, signal(now, awaitingAuthentication = true))
        assertTrue(authenticating is AutomationDecision.Blocked)
        assertTrue((authenticating as AutomationDecision.Blocked).reason.contains("sign in"))
    }

    @Test
    fun `a rule that sends a message asks instead of running`() {
        val watcher = rule(
            TriggerKind.TIME,
            mapOf(AutomationTrigger.AT to "07:30"),
            steps = listOf("message Priya saying hi")
        )
        assertTrue(watcher.requiresConfirmation)
        assertTrue(AutomationEngine.stepNeedsConfirmation("message Priya saying hi"))

        val decision = AutomationEngine.evaluate(watcher, signal(clockAt(7, 30)))
        assertTrue(decision is AutomationDecision.Ask)
        assertEquals("\u201CTest rule\u201D wants to run 1 step", (decision as AutomationDecision.Ask).question)
        assertFalse(AutomationEngine.shouldFire(watcher, signal(clockAt(7, 30))))
    }

    @Test
    fun `a rule of steps the command layer treats as safe runs on its own`() {
        val watcher = rule(
            TriggerKind.TIME,
            mapOf(AutomationTrigger.AT to "07:30"),
            steps = listOf("open YouTube", "set a timer for ten minutes", "read this screen")
        )
        assertFalse(watcher.requiresConfirmation)
        assertTrue(AutomationEngine.shouldFire(watcher, signal(clockAt(7, 30))))
    }

    @Test
    fun `a tap the safety layer flags asks too`() {
        assertTrue(AutomationEngine.stepNeedsConfirmation("tap Send"))
        assertTrue(
            rule(
                TriggerKind.PHRASE,
                mapOf(AutomationTrigger.PHRASE to "movie time"),
                steps = listOf("tap Send")
            ).requiresConfirmation
        )
        // An unrecognised sentence is a dictated paragraph nobody can vet, so it asks as well.
        assertTrue(AutomationEngine.stepNeedsConfirmation("I will reach in twenty minutes"))
    }

    @Test
    fun `a step that only opens an app is safe however it is worded`() {
        listOf("open YouTube", "open the clock app", "go to the camera app").forEach { step ->
            val parsed = CommandParser.parse(step)
            assertTrue("\u201C$step\u201D parses to $parsed", parsed is Command.OpenApp)
            assertFalse("\u201C$step\u201D parses to $parsed", AutomationEngine.stepNeedsConfirmation(step))
            assertEquals(emptyList<String>(), AutomationEngine.unreplayableSteps(listOf(step)))
        }
    }

    @Test
    fun `one trigger instance fires a rule once and never again`() {
        val now = clockAt(7, 30)
        val first = AutomationEngine.evaluate(
            rule(TriggerKind.TIME, mapOf(AutomationTrigger.AT to "07:30")),
            signal(now)
        )
        assertTrue(first is AutomationDecision.Run)

        val stamped = (first as AutomationDecision.Run).rule.withFiredKey(first.triggerKey)
        val again = AutomationEngine.evaluate(stamped, signal(now))
        assertTrue(again is AutomationDecision.Blocked)
        assertTrue((again as AutomationDecision.Blocked).triggerKey == first.triggerKey)

        // The same open, seen by a later poll, is still the same trigger instance.
        val openRule = rule(TriggerKind.APP_OPENED, mapOf(AutomationTrigger.PACKAGE to "spotify"))
        val opened = AutomationEngine.evaluate(
            openRule,
            signal(now, foregroundPackage = "com.spotify.music", foregroundSinceMillis = now - MINUTE)
        ) as AutomationDecision.Run
        val secondLook = AutomationEngine.evaluate(
            opened.rule.withFiredKey(opened.triggerKey),
            signal(now + 3 * MINUTE, foregroundPackage = "com.spotify.music", foregroundSinceMillis = now - MINUTE)
        )
        assertTrue(secondLook is AutomationDecision.Blocked)
    }

    @Test
    fun `a step that touches a credential is refused outright`() {
        val watcher = rule(
            TriggerKind.TIME,
            mapOf(AutomationTrigger.AT to "07:30"),
            steps = listOf("tap the password field")
        )
        val decision = AutomationEngine.evaluate(watcher, signal(clockAt(7, 30)))
        assertTrue(decision is AutomationDecision.Blocked)
        assertTrue(
            decision is AutomationDecision.Blocked &&
                decision.reason.contains("password")
        )
    }

    @Test
    fun `a rule with no steps has nothing to run`() {
        val decision = AutomationEngine.evaluate(
            rule(TriggerKind.TIME, mapOf(AutomationTrigger.AT to "07:30"), steps = emptyList()),
            signal(clockAt(7, 30))
        )
        assertTrue(decision is AutomationDecision.Blocked)
    }

    @Test
    fun `steps speak the same command language the bubble already uses`() {
        val bad = AutomationEngine.unreplayableSteps(
            listOf(
                "open YouTube",
                "run the routine morning",
                "list my routines",
                "save this as a routine called Morning"
            )
        )
        assertFalse(bad.contains("open YouTube"))
        assertTrue(bad.contains("run the routine morning"))
        assertTrue(bad.contains("list my routines"))
        assertTrue(bad.contains("save this as a routine called Morning"))
    }

    @Test
    fun `a summary says in one line what the rule waits for`() {
        assertEquals(
            "Every day at 07:30",
            AutomationEngine.summary(rule(TriggerKind.TIME, mapOf(AutomationTrigger.AT to "07:30", AutomationTrigger.DAILY to "true")))
        )
        assertEquals(
            "Once at 07:30",
            AutomationEngine.summary(rule(TriggerKind.TIME, mapOf(AutomationTrigger.AT to "07:30", AutomationTrigger.DAILY to "false")))
        )
        assertEquals(
            "When spotify is opened",
            AutomationEngine.summary(rule(TriggerKind.APP_OPENED, mapOf(AutomationTrigger.PACKAGE to "spotify")))
        )
        assertEquals(
            "When you say \u201Cmovie time\u201D",
            AutomationEngine.summary(rule(TriggerKind.PHRASE, mapOf(AutomationTrigger.PHRASE to "movie time")))
        )
        assertEquals(
            "When a WhatsApp notification mentions \u201Cdelivery\u201D arrives",
            AutomationEngine.summary(
                rule(
                    TriggerKind.NOTIFICATION_MATCHES,
                    mapOf(AutomationTrigger.SENDER to "WhatsApp", AutomationTrigger.TITLE to "delivery")
                )
            )
        )
    }

    @Test
    fun `only a time rule is worth an alarm and it points at the next one`() {
        val now = clockAt(6, 0)
        val due = AutomationEngine.nextRunAtMillis(
            rule(TriggerKind.TIME, mapOf(AutomationTrigger.AT to "07:30")),
            now
        )
        assertEquals(now + 90 * MINUTE, due)

        assertNull(
            AutomationEngine.nextRunAtMillis(
                rule(TriggerKind.APP_OPENED, mapOf(AutomationTrigger.PACKAGE to "spotify")),
                now
            )
        )
        assertNull(AutomationEngine.nextRunAtMillis(rule(TriggerKind.TIME, mapOf(AutomationTrigger.AT to "07:30"), enabled = false), now))
    }

    @Test
    fun `the question never carries the content of a step`() {
        val watcher = rule(
            TriggerKind.PHRASE,
            mapOf(AutomationTrigger.PHRASE to "movie time"),
            steps = listOf("message Priya saying hi")
        )
        val question = AutomationEngine.question(watcher)
        assertFalse(question.contains("Priya"))
        assertFalse(question.contains("movie time"))
        assertTrue(question.contains("1 step"))
    }
}
