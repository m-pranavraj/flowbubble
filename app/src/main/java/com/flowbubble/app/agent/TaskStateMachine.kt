package com.flowbubble.app.agent

/**
 * Where one user task has got to. Every other question about a run — may I tap, is this over, can I
 * pick this back up — is answered from this and nothing else.
 */
enum class TaskState {
    IDLE,
    PLANNING,
    ACTING,
    AWAITING_CONFIRMATION,
    PAUSED,
    COMPLETED,
    PARTIALLY_COMPLETED,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean get() = when (this) {
        COMPLETED, PARTIALLY_COMPLETED, FAILED, CANCELLED -> true
        IDLE, PLANNING, ACTING, AWAITING_CONFIRMATION, PAUSED -> false
    }

    /** True while the task still expects to move. */
    val isLive: Boolean get() = this == PLANNING || this == ACTING || this == AWAITING_CONFIRMATION || this == PAUSED

    /** In words, for the panel and for a refusal a user can read out loud. */
    fun describe(): String = when (this) {
        IDLE -> "not started"
        PLANNING -> "working out the steps"
        ACTING -> "carrying out the steps"
        AWAITING_CONFIRMATION -> "waiting for you to confirm"
        PAUSED -> "paused"
        COMPLETED -> "done"
        PARTIALLY_COMPLETED -> "partly done"
        FAILED -> "failed"
        CANCELLED -> "cancelled"
    }
}

/** Something that happens to a task. Only the events a state lists are accepted by that state. */
enum class TaskEvent {
    PLAN,
    PLAN_READY,
    NEED_CONFIRM,
    CONFIRM,
    DECLINE,
    PAUSE,
    RESUME,
    FINISH,
    FINISH_PARTIALLY,
    FAIL,
    CANCEL;

    fun describe(): String = when (this) {
        PLAN -> "starting to plan"
        PLAN_READY -> "finishing the plan"
        NEED_CONFIRM -> "asking for confirmation"
        CONFIRM -> "being confirmed"
        DECLINE -> "being refused"
        PAUSE -> "pausing"
        RESUME -> "resuming"
        FINISH -> "finishing"
        FINISH_PARTIALLY -> "finishing partway"
        FAIL -> "failing"
        CANCEL -> "cancelling"
    }
}

/** The kind of action a caller wants to take, since only some of them are harmless. */
enum class TaskActionKind {
    /** Reads the screen. Changes nothing. */
    OBSERVE,

    /** Taps, types, scrolls, opens: anything the user can see happen. */
    CHANGE_SCREEN
}

/** The one-call answer to "may I take this action right now", with the reason to say if not. */
data class ActionPermission(val allowed: Boolean, val state: TaskState, val reason: String) {
    val refused: Boolean get() = !allowed
}

/** What an event did to the machine: where it landed, or the plain reason it stayed put. */
data class StateChange(
    val from: TaskState,
    val to: TaskState,
    val event: TaskEvent,
    val moved: Boolean,
    val reason: String = ""
) {
    val accepted: Boolean get() = moved
    val refused: Boolean get() = !moved
}

/** One move that was taken. `detail` is a cause in the caller's words, never screen text. */
data class TaskTransition(
    val from: TaskState,
    val to: TaskState,
    val event: TaskEvent,
    val detail: String,
    val atMs: Long
)

/**
 * The lifecycle of one task, as pure data: legal moves are listed per state, everything else is
 * refused with a reason, a paused task remembers where it left off and why it stopped, and no Android
 * or coroutine type is involved, so the answer to "may I tap now" is testable without a device.
 */
class TaskStateMachine(private val clock: () -> Long = { System.currentTimeMillis() }) {

    var state: TaskState = TaskState.IDLE
        private set

    /** Where PAUSED will return to. Only meaningful while the task is paused. */
    var pausedFrom: TaskState? = null
        private set

    /** Why the task stopped, in the caller's words. */
    var pauseReason: String = ""
        private set

    /** Where an unanswered confirmation came from, so a yes returns there and not to a guess. */
    var confirmingFrom: TaskState? = null
        private set

    private val moves = ArrayList<TaskTransition>()

    /** The moves actually taken, oldest first, trimmed to the tail. */
    val transitions: List<TaskTransition> get() = moves.toList()

    /** Drives the machine. An event the current state does not list is refused, never forced. */
    fun attempt(event: TaskEvent, detail: String = ""): StateChange {
        val from = state
        val to = next(from, event)
            ?: return StateChange(from, from, event, false, refusal(from, event))
        moveTo(from, to, event, detail)
        return StateChange(from, to, event, true)
    }

    fun planRequested(): StateChange = attempt(TaskEvent.PLAN)

    fun planReady(): StateChange = attempt(TaskEvent.PLAN_READY)

    fun confirmNeeded(): StateChange = attempt(TaskEvent.NEED_CONFIRM)

    fun confirmed(): StateChange = attempt(TaskEvent.CONFIRM)

    fun declined(): StateChange = attempt(TaskEvent.DECLINE)

    /** Stops where the task stands and records why. [reason] is a cause in plain words, not screen text. */
    fun pause(reason: String): StateChange = attempt(TaskEvent.PAUSE, reason)

    fun resume(): StateChange = attempt(TaskEvent.RESUME)

    fun completed(): StateChange = attempt(TaskEvent.FINISH)

    fun partiallyCompleted(): StateChange = attempt(TaskEvent.FINISH_PARTIALLY)

    fun failed(reason: String = ""): StateChange = attempt(TaskEvent.FAIL, reason)

    fun cancelled(reason: String = ""): StateChange = attempt(TaskEvent.CANCEL, reason)

    /** Every move this state accepts, so a caller can ask what it may still do. */
    fun legalEvents(): Set<TaskEvent> = legalEventsOf(state)

    /**
     * Whether an action may be taken in this very moment. Reading the screen is held to a looser bar
     * than changing it, but a paused or finished task gets neither: nothing continues behind a pause.
     */
    fun mayAct(kind: TaskActionKind = TaskActionKind.CHANGE_SCREEN): ActionPermission = when (state) {
        TaskState.ACTING -> ActionPermission(true, state, "the task is running and this is its next step")
        TaskState.PLANNING -> if (kind == TaskActionKind.OBSERVE) {
            ActionPermission(true, state, "the plan is allowed one more look at the screen")
        } else {
            ActionPermission(false, state, "the plan isn't settled, so nothing on screen should be touched yet")
        }
        TaskState.AWAITING_CONFIRMATION -> if (kind == TaskActionKind.OBSERVE) {
            ActionPermission(true, state, "looking at the screen while you decide changes nothing")
        } else {
            ActionPermission(false, state, "waiting for you to confirm, so nothing may be touched")
        }
        TaskState.PAUSED -> ActionPermission(false, state, "paused: $pauseReason")
        TaskState.IDLE -> ActionPermission(false, state, "no task has been started, so there is nothing to act for")
        else -> ActionPermission(false, state, "the task is already ${state.describe()}")
    }

    /** Back to a clean machine, for the next thing the user asks for. */
    fun reset() {
        state = TaskState.IDLE
        pausedFrom = null
        pauseReason = ""
        confirmingFrom = null
        moves.clear()
    }

    private fun next(from: TaskState, event: TaskEvent): TaskState? = when (from) {
        TaskState.IDLE -> when (event) {
            TaskEvent.PLAN -> TaskState.PLANNING
            TaskEvent.CANCEL -> TaskState.CANCELLED
            else -> null
        }
        TaskState.PLANNING -> when (event) {
            TaskEvent.PLAN_READY -> TaskState.ACTING
            TaskEvent.NEED_CONFIRM -> TaskState.AWAITING_CONFIRMATION
            TaskEvent.PAUSE -> TaskState.PAUSED
            TaskEvent.FAIL -> TaskState.FAILED
            TaskEvent.CANCEL -> TaskState.CANCELLED
            else -> null
        }
        TaskState.ACTING -> when (event) {
            TaskEvent.PLAN -> TaskState.PLANNING
            TaskEvent.NEED_CONFIRM -> TaskState.AWAITING_CONFIRMATION
            TaskEvent.PAUSE -> TaskState.PAUSED
            TaskEvent.FINISH -> TaskState.COMPLETED
            TaskEvent.FINISH_PARTIALLY -> TaskState.PARTIALLY_COMPLETED
            TaskEvent.FAIL -> TaskState.FAILED
            TaskEvent.CANCEL -> TaskState.CANCELLED
            else -> null
        }
        TaskState.AWAITING_CONFIRMATION -> when (event) {
            TaskEvent.CONFIRM -> confirmingFrom ?: TaskState.ACTING
            TaskEvent.DECLINE -> TaskState.CANCELLED
            TaskEvent.NEED_CONFIRM -> TaskState.AWAITING_CONFIRMATION
            TaskEvent.PAUSE -> TaskState.PAUSED
            TaskEvent.FAIL -> TaskState.FAILED
            TaskEvent.CANCEL -> TaskState.CANCELLED
            else -> null
        }
        TaskState.PAUSED -> when (event) {
            TaskEvent.RESUME -> pausedFrom
            TaskEvent.FAIL -> TaskState.FAILED
            TaskEvent.CANCEL -> TaskState.CANCELLED
            else -> null
        }
        else -> null
    }

    private fun moveTo(from: TaskState, to: TaskState, event: TaskEvent, detail: String) {
        when (event) {
            TaskEvent.PAUSE -> {
                pausedFrom = from
                pauseReason = causeOf(detail)
            }
            TaskEvent.RESUME -> {
                pausedFrom = null
                pauseReason = ""
            }
            TaskEvent.NEED_CONFIRM -> if (from != TaskState.AWAITING_CONFIRMATION) confirmingFrom = from
            TaskEvent.CONFIRM, TaskEvent.DECLINE -> confirmingFrom = null
            else -> Unit
        }
        state = to
        moves.add(TaskTransition(from, to, event, detail.trim().take(MAX_DETAIL), clock()))
        while (moves.size > MAX_LOG) moves.removeAt(0)
    }

    private fun refusal(from: TaskState, event: TaskEvent): String = when {
        from.isTerminal -> "the task is already ${from.describe()}, so ${event.describe()} can't happen to it"
        from == TaskState.PAUSED && event == TaskEvent.RESUME -> "the task isn't holding anywhere to resume back to"
        from == TaskState.IDLE && event == TaskEvent.PAUSE -> "nothing is running yet, so there is nothing to pause"
        else -> "${event.describe()} is not a move a task that is ${from.describe()} can make"
    }

    /** A cause worth keeping, capped: this text outlives the screen it came from. */
    private fun causeOf(detail: String): String =
        detail.trim().take(MAX_DETAIL).ifBlank { "no reason was given" }

    companion object {
        /** The whole legal table, in one place, for tests and for anything that draws the state. */
        fun legalEventsOf(state: TaskState): Set<TaskEvent> = when (state) {
            TaskState.IDLE -> setOf(TaskEvent.PLAN, TaskEvent.CANCEL)
            TaskState.PLANNING -> setOf(
                TaskEvent.PLAN_READY, TaskEvent.NEED_CONFIRM, TaskEvent.PAUSE, TaskEvent.FAIL, TaskEvent.CANCEL
            )
            TaskState.ACTING -> setOf(
                TaskEvent.PLAN, TaskEvent.NEED_CONFIRM, TaskEvent.PAUSE, TaskEvent.FINISH,
                TaskEvent.FINISH_PARTIALLY, TaskEvent.FAIL, TaskEvent.CANCEL
            )
            TaskState.AWAITING_CONFIRMATION -> setOf(
                TaskEvent.CONFIRM, TaskEvent.DECLINE, TaskEvent.NEED_CONFIRM, TaskEvent.PAUSE,
                TaskEvent.FAIL, TaskEvent.CANCEL
            )
            TaskState.PAUSED -> setOf(TaskEvent.RESUME, TaskEvent.FAIL, TaskEvent.CANCEL)
            else -> emptySet()
        }

        private const val MAX_DETAIL = 140
        private const val MAX_LOG = 64
    }
}
