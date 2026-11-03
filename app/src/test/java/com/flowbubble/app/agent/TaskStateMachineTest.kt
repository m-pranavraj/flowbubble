package com.flowbubble.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lifecycle a screen task has to have before it can span two apps: a pause that is a real state
 * rather than a killed run, an explicit table of what may follow what, and one question — may I act
 * now — that every route asks the same way.
 */
class TaskStateMachineTest {

    private fun machine(): TaskStateMachine = TaskStateMachine(clock = { 1_000L })

    /** Walks the only legal way into [state], so the machine is in it the way a run would be. */
    private fun reach(state: TaskState): TaskStateMachine {
        val path = when (state) {
            TaskState.IDLE -> emptyList()
            TaskState.PLANNING -> listOf(TaskEvent.PLAN)
            TaskState.ACTING -> listOf(TaskEvent.PLAN, TaskEvent.PLAN_READY)
            TaskState.AWAITING_CONFIRMATION ->
                listOf(TaskEvent.PLAN, TaskEvent.PLAN_READY, TaskEvent.NEED_CONFIRM)
            TaskState.PAUSED -> listOf(TaskEvent.PLAN, TaskEvent.PAUSE)
            TaskState.COMPLETED -> listOf(TaskEvent.PLAN, TaskEvent.PLAN_READY, TaskEvent.FINISH)
            TaskState.PARTIALLY_COMPLETED ->
                listOf(TaskEvent.PLAN, TaskEvent.PLAN_READY, TaskEvent.FINISH_PARTIALLY)
            TaskState.FAILED -> listOf(TaskEvent.PLAN, TaskEvent.PLAN_READY, TaskEvent.FAIL)
            TaskState.CANCELLED -> listOf(TaskEvent.PLAN, TaskEvent.PLAN_READY, TaskEvent.CANCEL)
        }
        val walked = machine()
        path.forEach { walked.attempt(it, "walked there on purpose") }
        assertEquals(state, walked.state)
        return walked
    }

    @Test
    fun `the table and the machine agree on every state and event`() {
        for (state in TaskState.entries) {
            for (event in TaskEvent.entries) {
                val legal = TaskStateMachine.legalEventsOf(state).contains(event)
                val change = reach(state).attempt(event, "driven by the table")
                assertEquals("$state + $event", legal, change.moved)
                if (!change.moved) {
                    assertEquals(state, change.to)
                    assertEquals(state, change.from)
                    assertTrue("$state refused $event without saying why", change.reason.isNotBlank())
                }
            }
        }
    }

    @Test
    fun `a task runs from asked to done`() {
        val run = machine()
        assertEquals(TaskState.IDLE, run.state)
        assertTrue(run.planRequested().accepted)
        assertEquals(TaskState.PLANNING, run.state)
        assertTrue(run.planReady().accepted)
        assertEquals(TaskState.ACTING, run.state)
        assertTrue(run.completed().accepted)
        assertEquals(TaskState.COMPLETED, run.state)
        assertTrue(run.state.isTerminal)
    }

    @Test
    fun `finishing a task that never started is refused`() {
        val run = machine()
        val change = run.completed()
        assertTrue(change.refused)
        assertEquals(TaskState.IDLE, run.state)
        assertTrue(change.reason.isNotBlank())
    }

    @Test
    fun `a plan that is already running cannot be declared ready again`() {
        val run = reach(TaskState.ACTING)
        assertTrue(run.attempt(TaskEvent.PLAN_READY).refused)
        assertEquals(TaskState.ACTING, run.state)
    }

    @Test
    fun `resuming something that never paused is refused`() {
        val run = reach(TaskState.ACTING)
        val change = run.resume()
        assertTrue(change.refused)
        assertTrue(change.reason.isNotBlank())
        assertEquals(TaskState.ACTING, run.state)
    }

    @Test
    fun `confirming without being asked is refused`() {
        val run = reach(TaskState.PLANNING)
        assertNull(run.confirmingFrom)
        assertTrue(run.attempt(TaskEvent.CONFIRM).refused)
        assertEquals(TaskState.PLANNING, run.state)
    }

    @Test
    fun `nothing happens to a task that is already done`() {
        val finished = listOf(
            TaskState.COMPLETED, TaskState.PARTIALLY_COMPLETED, TaskState.FAILED, TaskState.CANCELLED
        )
        for (state in finished) {
            val run = reach(state)
            assertTrue(state.toString(), TaskStateMachine.legalEventsOf(state).isEmpty())
            for (event in TaskEvent.entries) {
                val change = run.attempt(event)
                assertFalse("$state should not accept $event", change.moved)
                assertEquals(state, run.state)
            }
            assertFalse(run.mayAct().allowed)
            assertTrue(run.mayAct(TaskActionKind.OBSERVE).refused)
        }
    }

    @Test
    fun `a pause returns to exactly the state it left and keeps the reason`() {
        val run = reach(TaskState.ACTING)
        val paused = run.pause("the app asked for a passcode")
        assertTrue(paused.accepted)
        assertEquals(TaskState.PAUSED, run.state)
        assertEquals(TaskState.ACTING, run.pausedFrom)
        assertEquals("the app asked for a passcode", run.pauseReason)
        assertTrue(run.mayAct().refused)
        assertTrue(run.mayAct().reason.contains("passcode"))
        assertTrue(run.mayAct(TaskActionKind.OBSERVE).refused)

        val resumed = run.resume()
        assertTrue(resumed.accepted)
        assertEquals(TaskState.ACTING, run.state)
        assertNull(run.pausedFrom)
        assertEquals("", run.pauseReason)
        assertTrue(run.mayAct().allowed)
    }

    @Test
    fun `a pause during a wait for confirmation returns to the wait`() {
        val run = reach(TaskState.AWAITING_CONFIRMATION)
        run.pause("you stepped away")
        assertEquals(TaskState.AWAITING_CONFIRMATION, run.pausedFrom)
        run.resume()
        assertEquals(TaskState.AWAITING_CONFIRMATION, run.state)
        assertTrue(run.mayAct().refused)
        assertTrue(run.confirmed().accepted)
        assertEquals(TaskState.ACTING, run.state)
    }

    @Test
    fun `a yes returns to the state that asked, even after a re-ask`() {
        val run = reach(TaskState.ACTING)
        run.confirmNeeded()
        assertEquals(TaskState.AWAITING_CONFIRMATION, run.state)
        assertEquals(TaskState.ACTING, run.confirmingFrom)
        // The parameters changed, so the same question is asked again: the origin must survive it.
        assertTrue(run.confirmNeeded().accepted)
        assertEquals(TaskState.ACTING, run.confirmingFrom)
        assertTrue(run.confirmed().accepted)
        assertEquals(TaskState.ACTING, run.state)
    }

    @Test
    fun `a plan can be re-made while the task is acting`() {
        val run = reach(TaskState.ACTING)
        assertTrue(run.planRequested().accepted)
        assertEquals(TaskState.PLANNING, run.state)
        assertTrue(run.planReady().accepted)
        assertEquals(TaskState.ACTING, run.state)
    }

    @Test
    fun `refusing a confirmation ends the task`() {
        val run = reach(TaskState.AWAITING_CONFIRMATION)
        assertTrue(run.declined().accepted)
        assertEquals(TaskState.CANCELLED, run.state)
    }

    @Test
    fun `reading the screen is held to a looser bar than changing it`() {
        val planning = reach(TaskState.PLANNING)
        assertTrue(planning.mayAct(TaskActionKind.OBSERVE).allowed)
        assertTrue(planning.mayAct(TaskActionKind.CHANGE_SCREEN).refused)

        val deciding = reach(TaskState.AWAITING_CONFIRMATION)
        assertTrue(deciding.mayAct(TaskActionKind.OBSERVE).allowed)
        assertTrue(deciding.mayAct().refused)

        val idle = machine()
        assertTrue(idle.mayAct(TaskActionKind.OBSERVE).refused)

        val running = reach(TaskState.ACTING)
        val permission = running.mayAct()
        assertTrue(permission.allowed)
        assertEquals(TaskState.ACTING, permission.state)
        assertTrue(permission.reason.isNotBlank())
    }

    @Test
    fun `the moves taken are kept with their cause`() {
        val run = reach(TaskState.PAUSED)
        val moves = run.transitions
        assertEquals(2, moves.size)
        assertEquals(TaskState.IDLE, moves.first().from)
        assertEquals(TaskEvent.PLAN, moves.first().event)
        assertEquals(TaskEvent.PAUSE, moves.last().event)
        assertEquals(TaskState.PLANNING, moves.last().from)
        assertEquals(1_000L, moves.last().atMs)
        assertTrue(moves.last().detail.isNotBlank())
    }

    @Test
    fun `a long pause reason is kept short`() {
        val run = reach(TaskState.ACTING)
        run.pause("x".repeat(400))
        assertTrue(run.pauseReason.length <= 140)
    }

    @Test
    fun `resetting gives the next task a clean machine`() {
        val run = reach(TaskState.COMPLETED)
        run.reset()
        assertEquals(TaskState.IDLE, run.state)
        assertTrue(run.transitions.isEmpty())
        assertNull(run.pausedFrom)
        assertEquals("", run.pauseReason)
        assertTrue(run.planRequested().accepted)
    }
}
