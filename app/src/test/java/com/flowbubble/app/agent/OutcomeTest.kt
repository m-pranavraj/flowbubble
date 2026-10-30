package com.flowbubble.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutcomeTest {

    @Test
    fun `each outcome writes the history status the log already uses`() {
        assertEquals("done", OutcomeKind.COMPLETED.historyStatus())
        assertEquals("unverified", OutcomeKind.PARTIAL.historyStatus())
        assertEquals("awaiting user", OutcomeKind.AWAITING_USER.historyStatus())
        assertEquals("unsupported", OutcomeKind.UNSUPPORTED.historyStatus())
        assertEquals("failed", OutcomeKind.FAILED.historyStatus())
    }

    @Test
    fun `old history strings read back as the same outcome`() {
        assertEquals(OutcomeKind.COMPLETED, OutcomeKind.fromHistoryStatus("done"))
        assertEquals(OutcomeKind.COMPLETED, OutcomeKind.fromHistoryStatus("copied"))
        assertEquals(OutcomeKind.PARTIAL, OutcomeKind.fromHistoryStatus("unverified"))
        assertEquals(OutcomeKind.PARTIAL, OutcomeKind.fromHistoryStatus("requested"))
        assertEquals(OutcomeKind.AWAITING_USER, OutcomeKind.fromHistoryStatus("setup needed"))
        assertEquals(OutcomeKind.AWAITING_USER, OutcomeKind.fromHistoryStatus("permission missing"))
        assertEquals(OutcomeKind.UNSUPPORTED, OutcomeKind.fromHistoryStatus("not found"))
        assertEquals(OutcomeKind.FAILED, OutcomeKind.fromHistoryStatus("failed"))
    }

    @Test
    fun `an unknown status never becomes a success`() {
        assertEquals(OutcomeKind.PARTIAL, OutcomeKind.fromHistoryStatus("something new"))
        assertEquals(OutcomeKind.PARTIAL, OutcomeKind.fromHistoryStatus(""))
    }

    @Test
    fun `a round trip through history keeps the outcome`() {
        for (kind in OutcomeKind.entries) {
            assertEquals(kind, OutcomeKind.fromHistoryStatus(kind.historyStatus()))
        }
    }

    @Test
    fun `evidence is the only thing that makes a step verified`() {
        assertFalse(TraceStep("tap", "Login").verified)
        assertTrue(TraceStep("tap", "Login", "the screen changed after the tap").verified)

        assertTrue(TraceStep("tap", "Login").render().contains("not confirmed by the system"))
        assertTrue(TraceStep("tap", "Login", "the screen changed").render().contains("confirmed: the screen changed"))
        assertTrue(TraceStep("tap").render().startsWith("tap\n"))
    }

    @Test
    fun `a chain is complete only when every step was observed`() {
        val allSeen = ExecutionTrace(
            asked = "open chrome and search",
            route = "agent",
            steps = listOf(
                TraceStep("open", "Chrome", "the foreground changed to Chrome"),
                TraceStep("type", "weather", "the field now contains the text")
            )
        )
        assertTrue(allSeen.allVerified)
        assertEquals(OutcomeKind.COMPLETED, allSeen.outcomeFor(stepsDone = true))

        val halfSeen = allSeen.copy(
            steps = listOf(
                TraceStep("open", "Chrome", "the foreground changed to Chrome"),
                TraceStep("tap", "Allow notifications")
            )
        )
        assertFalse(halfSeen.allVerified)
        assertEquals(OutcomeKind.PARTIAL, halfSeen.outcomeFor(stepsDone = true))
    }

    @Test
    fun `a wait that ran out does not count against an otherwise verified run`() {
        val waiting = TraceStep(
            "Wait for the screen",
            "The screen was still updating after 3800 ms",
            informational = true
        )
        assertTrue(waiting.verified)
        assertFalse(waiting.render().contains("not confirmed"))
        assertTrue(waiting.render().contains("still updating"))

        val trace = ExecutionTrace(
            asked = "open chrome and search",
            route = "agent",
            steps = listOf(
                TraceStep("open", "Chrome", "the foreground changed to Chrome"),
                waiting,
                TraceStep("type", "weather", "the field now contains the text")
            )
        )
        assertTrue(trace.allVerified)
        assertEquals(OutcomeKind.COMPLETED, trace.outcomeFor(stepsDone = true))
    }

    @Test
    fun `a request android refused keeps the failure the caller recorded`() {
        val stopped = ExecutionTrace(
            asked = "scroll to the buy button",
            route = "agent",
            steps = listOf(TraceStep("scroll", "down", "2 of 5 scrolls accepted")),
            outcome = OutcomeKind.FAILED
        )
        assertEquals(OutcomeKind.FAILED, stopped.outcomeFor(stepsDone = false))
    }

    @Test
    fun `a run with nothing to observe keeps the recorded outcome`() {
        val empty = ExecutionTrace(asked = "read notifications", route = "notification listener")
        assertFalse(empty.allVerified)
        assertEquals(OutcomeKind.PARTIAL, empty.outcomeFor(stepsDone = true))

        val awaiting = empty.copy(outcome = OutcomeKind.AWAITING_USER)
        assertEquals(OutcomeKind.AWAITING_USER, awaiting.outcomeFor(stepsDone = true))
    }

    @Test
    fun `a rendered trace numbers each step and folds the rest`() {
        val steps = (1..10).map { TraceStep("step $it") }
        val trace = ExecutionTrace(asked = "a", route = "agent", steps = steps, headline = "Finished with notes")
        val body = trace.renderBody(maxSteps = 8)

        assertTrue(body.startsWith("Finished with notes\n\n"))
        assertTrue(body.contains("1. step 1"))
        assertTrue(body.contains("8. step 8"))
        assertFalse(body.contains("9. step 9"))
        assertTrue(body.contains("2 more steps"))
    }

    @Test
    fun `an agent plan reports the same outcomes as the rest of the app`() {
        val trace = ExecutionTrace(
            asked = "open youtube",
            route = "open_app",
            steps = listOf(TraceStep("open", "YouTube", "the foreground changed to YouTube"))
        )
        val outcome = AgentOutcome.Completed("Opened YouTube", trace)
        assertTrue(outcome is AgentOutcome.Completed)
        assertEquals(OutcomeKind.COMPLETED, trace.outcomeFor(stepsDone = true))
        assertEquals("done", trace.outcomeFor(stepsDone = true).historyStatus())
    }
}
