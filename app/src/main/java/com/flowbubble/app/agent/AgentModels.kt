package com.flowbubble.app.agent

import com.flowbubble.app.vision.ScreenFusion
import com.flowbubble.app.vision.ScreenModel
import com.flowbubble.app.vision.ScreenTarget
import com.flowbubble.app.vision.TextMatcher

sealed interface AgentAction {
    data class OpenApp(val query: String) : AgentAction
    data class OpenUrl(val url: String) : AgentAction
    data class TapTarget(val label: String, val ordinal: Int? = null) : AgentAction
    data class TypeText(
        val text: String,
        val pressEnter: Boolean = false,
        val searchFieldOnly: Boolean = false
    ) : AgentAction
    data class Scroll(val forward: Boolean, val times: Int = 1) : AgentAction
    data class Swipe(val toRight: Boolean, val times: Int = 1) : AgentAction
    data class LongPress(val label: String, val ordinal: Int? = null, val holdMs: Int = 650) : AgentAction
    data object OpenMenu : AgentAction
    data object ClearField : AgentAction
    data object SelectAll : AgentAction
    data object CopySelection : AgentAction
    data object Paste : AgentAction
    data object Dismiss : AgentAction
    data class ScrollUntil(
        val label: String,
        val tapAfter: Boolean = false,
        val maxScrolls: Int = 6
    ) : AgentAction
    data object Back : AgentAction
    data class Wait(val ms: Long) : AgentAction
    data class AskAi(val instruction: String) : AgentAction
}

data class AgentStep(val action: AgentAction, val description: String)

enum class PlanSource { LOCAL, AI }

data class AgentPlan(
    val goal: String,
    val steps: List<AgentStep>,
    val source: PlanSource
) {
    init {
        require(steps.isNotEmpty()) { "A plan needs at least one step" }
    }
}

sealed interface AgentOutcome {
    data class Completed(val summary: String, val trace: ExecutionTrace? = null) : AgentOutcome
    data class Failed(val summary: String, val trace: ExecutionTrace? = null) : AgentOutcome
    data object Cancelled : AgentOutcome
}

/**
 * Everything the reactive loop can know about one moment of the screen, as plain data.
 *
 * It is deliberately small and comparable: the loop decides from what it can see, so the input has
 * to be something a unit test can construct without an Android runtime. [revision] comes from the
 * live session when one is running, which is what lets the trace say *which* screen it judged.
 */
data class ScreenObservation(
    val revision: Int,
    val packageName: String,
    val labels: List<String>,
    val textHash: Int,
    val fieldText: String = "",
    val actionableCount: Int = 0,
    val passwordFocused: Boolean = false,
    val pixelsRead: Boolean = false,
    /** The field holding the cursor, or blank when the reading could not tell. Never its contents. */
    val focusedLabel: String = "",
    /** Fields text may be typed into, password boxes excluded. Zero means a type step has nowhere to go. */
    val writableFieldCount: Int = 0,
    val readingConfidence: String = "",
    val note: String = ""
) {
    /** Two observations with the same fingerprint are the same screen as far as the loop can tell. */
    val fingerprint: String get() = "$packageName/$textHash"

    /**
     * Whether a typed step has anywhere to land. Only a reading that scored its own fields is allowed
     * to say no: a producer that never looked reports zero fields for every screen, and treating that
     * as a wall would stop typing everywhere.
     */
    val canReceiveText: Boolean
        get() = readingConfidence == "unmeasured" || writableFieldCount > 0 || focusedLabel.isNotBlank()

    /** True when [raw] names something on this screen, in either direction of containment. */
    fun shows(raw: String): Boolean {
        val needle = TextMatcher.normalize(raw)
        if (needle.isEmpty()) return false
        return labels.any { label ->
            val current = TextMatcher.normalize(label)
            current.isNotEmpty() && (current.contains(needle) || (current.length >= 4 && needle.contains(current)))
        }
    }

    companion object {
        fun of(model: ScreenModel, revision: Int = 0, fieldText: String = ""): ScreenObservation =
            ScreenObservation(
                revision = revision,
                packageName = model.packageName,
                labels = (model.targets.map { it.label } + model.nodes.map { it.label })
                    .filter { it.isNotBlank() && !it.contains("password", true) }
                    .distinct()
                    .take(MAX_LABELS),
                textHash = ScreenFusion.textHash(model),
                fieldText = fieldText,
                actionableCount = model.targets.count { it.clickable || it.editable },
                passwordFocused = model.passwordFocused,
                pixelsRead = model.ocrUsed,
                focusedLabel = model.focus?.label.orEmpty(),
                writableFieldCount = model.writableFields.size,
                readingConfidence = model.confidence.level.name.lowercase(),
                note = model.captureError.orEmpty()
            )

        private const val MAX_LABELS = 260
    }
}

/**
 * An observable condition that means the goal is genuinely done.
 *
 * Nothing here is "the plan ran out of steps" — a run only finishes when the screen itself says so,
 * which is the whole point of watching instead of firing a fixed chain into the dark.
 */
sealed interface GoalCondition {
    fun holds(now: ScreenObservation, before: ScreenObservation?): Boolean
    fun describe(): String

    data class Foreground(val packageName: String, val appName: String = "") : GoalCondition {
        override fun holds(now: ScreenObservation, before: ScreenObservation?) =
            now.packageName.equals(packageName, true) ||
                (appName.isNotBlank() && TextMatcher.normalize(now.packageName).contains(TextMatcher.normalize(appName)))

        override fun describe() = "the screen is in ${appName.ifBlank { packageName }}"
    }

    data class LabelAppeared(val label: String) : GoalCondition {
        override fun holds(now: ScreenObservation, before: ScreenObservation?) = now.shows(label)
        override fun describe() = "\u201C${abbreviate(label)}\u201D is on screen"
    }

    data class LabelDisappeared(val label: String) : GoalCondition {
        override fun holds(now: ScreenObservation, before: ScreenObservation?) = !now.shows(label)
        override fun describe() = "\u201C${abbreviate(label)}\u201D has gone away"
    }

    data class FieldContains(val text: String) : GoalCondition {
        override fun holds(now: ScreenObservation, before: ScreenObservation?): Boolean {
            val wanted = TextMatcher.normalize(text)
            if (wanted.isEmpty()) return false
            val field = TextMatcher.normalize(now.fieldText)
            return field.contains(wanted) || now.shows(text)
        }

        override fun describe() = "the field holds \u201C${abbreviate(text)}\u201D"
    }

    /** The screen moved away from the one captured when the goal was made. */
    data class ScreenChanged(
        val baselineHash: Int = NO_BASELINE,
        val baselinePackage: String = ""
    ) : GoalCondition {
        override fun holds(now: ScreenObservation, before: ScreenObservation?) =
            // An unstamped baseline is not a licence to claim the screen moved.
            baselineHash != NO_BASELINE &&
                (now.textHash != baselineHash || now.packageName != baselinePackage)

        override fun describe() = "the screen looks different from when you asked"

        companion object {
            const val NO_BASELINE = -1
        }
    }

    /** A tap the system confirmed with a visible change, for "click that" style goals. */
    data object TapVerified : GoalCondition {
        override fun holds(now: ScreenObservation, before: ScreenObservation?) = false
        override fun describe() = "the thing you pointed at was tapped"
    }
}

/** What the loop is trying to make true, derived from the words the user used. */
enum class GoalKind {
    FIND,
    TAP,
    POINT,
    DISMISS,
    BACK,
    TYPE,
    OPEN_APP,
    GENERIC
}

data class ReactiveGoal(
    val raw: String,
    val kind: GoalKind,
    val target: String = "",
    val text: String = "",
    val pointing: String = "",
    val appName: String = "",
    val packageName: String = "",
    /** True when the target may be off-screen and has to be scrolled to first. */
    val findFirst: Boolean = false,
    val maxScrolls: Int = 8,
    val doneWhen: List<GoalCondition> = emptyList()
) {
    /**
     * The goal is done only when every condition holds on the screen as it is now. [tapsVerified]
     * is the loop's memory of a tap that changed something, since a tap has no condition of its own
     * to read off a static screen.
     */
    fun satisfied(
        now: ScreenObservation,
        before: ScreenObservation?,
        tapsVerified: Boolean = false
    ): Boolean {
        if (doneWhen.isEmpty()) return false
        return doneWhen.all { condition ->
            when (condition) {
                GoalCondition.TapVerified -> tapsVerified
                else -> condition.holds(now, before)
            }
        }
    }

    fun evidence(): String = doneWhen.joinToString(", ") { it.describe() }

    /**
     * Stamps the screen the task started on into the goal, so "the screen changed" means changed
     * relative to what the user was looking at and not relative to some earlier step.
     */
    fun withBaseline(first: ScreenObservation): ReactiveGoal = copy(
        doneWhen = doneWhen.map { condition ->
            if (condition is GoalCondition.ScreenChanged) {
                condition.copy(baselineHash = first.textHash, baselinePackage = first.packageName)
            } else {
                condition
            }
        }
    )
}

/** Hard ceilings. The loop stops and says why rather than wandering the screen forever. */
data class ReactiveBudget(
    val maxSteps: Int = 12,
    val maxWallClockMs: Long = 100_000L,
    val maxScrolls: Int = 10,
    val maxSameScreenWaits: Int = 3,
    val maxActionsOfOneKind: Int = 3
) {
    /** The honest reason the loop refused to take another step, or null when it may continue. */
    fun stopReason(ctx: LoopContext): String? = when {
        ctx.elapsedMs > maxWallClockMs ->
            "I stopped after ${ctx.elapsedMs / 1000}s because a screen task shouldn't run that long unattended."
        ctx.stepsUsed >= maxSteps ->
            "I stopped after $maxSteps steps without reaching \u201C${abbreviate(ctx.goal.raw)}\u201D."
        ctx.scrollsUsed >= maxScrolls.coerceAtMost(ctx.goal.maxScrolls) ->
            "I scrolled $ctx.scrollsUsed times and never saw what I was looking for."
        ctx.sameScreenStreak >= maxSameScreenWaits ->
            "The screen stopped changing, so doing the same thing again wouldn't help."
        ctx.mostRepeatedActionKind >= maxActionsOfOneKind ->
            "I stopped after repeating the same kind of action $maxActionsOfOneKind times, " +
                "because doing it again wasn't getting me closer to \u201C${abbreviate(ctx.goal.raw)}\u201D."
        else -> null
    }
}

/** A pointing phrase that resolved to one control, kept as data so the decision stays testable. */
data class ResolvedPointer(
    val label: String,
    val ordinal: Int? = null,
    val evidence: String = "",
    val target: ScreenTarget? = null
)

/** Everything the decision function is allowed to look at. */
data class LoopContext(
    val goal: ReactiveGoal,
    val now: ScreenObservation,
    val before: ScreenObservation? = null,
    val first: ScreenObservation? = null,
    val appeared: List<String> = emptyList(),
    val disappeared: List<String> = emptyList(),
    val resolvedPointer: ResolvedPointer? = null,
    val pointerQuestion: String? = null,
    val pointerOptions: List<String> = emptyList(),
    val pointerNote: String = "",
    val stepsUsed: Int = 0,
    val tapsUsed: Int = 0,
    val scrollsUsed: Int = 0,
    val dismissUsed: Int = 0,
    val backUsed: Int = 0,
    val typedUsed: Int = 0,
    val sameScreenStreak: Int = 0,
    val tapsVerified: Boolean = false,
    val openedApp: Boolean = false,
    val elapsedMs: Long = 0,
    val budget: ReactiveBudget = ReactiveBudget(),
    val lastAction: AgentAction? = null,
    /** How many times the loop has run each kind of action, so [ReactiveBudget.maxActionsOfOneKind] can bite. */
    val actionsByKind: Map<String, Int> = emptyMap()
) {
    /** The loop calls this after acting, so the same control can't be tapped without limit. */
    fun recordActionKind(kind: String): LoopContext =
        copy(actionsByKind = actionsByKind + (kind to (actionsByKind[kind] ?: 0) + 1))

    /** The busiest count in the tally; drives the same-kind ceiling without a second pass. */
    val mostRepeatedActionKind: Int get() = actionsByKind.values.maxOrNull() ?: 0
}

/** A stable name for the kind of a step, so the tally groups repeatable controls together. */
fun AgentAction.actionKind(): String = when (this) {
    is AgentAction.TapTarget -> "tap:${label.lowercase()}"
    is AgentAction.LongPress -> "long-press:${label.lowercase()}"
    is AgentAction.ScrollUntil -> "scroll-until:${label.lowercase()}"
    is AgentAction.Scroll -> "scroll"
    is AgentAction.Swipe -> "swipe"
    is AgentAction.TypeText -> "type"
    is AgentAction.OpenApp -> "open-app"
    is AgentAction.OpenUrl -> "open-url"
    is AgentAction.Wait -> "wait"
    is AgentAction.AskAi -> "ask-ai"
    AgentAction.OpenMenu -> "menu"
    AgentAction.ClearField -> "clear-field"
    AgentAction.SelectAll -> "select-all"
    AgentAction.CopySelection -> "copy"
    AgentAction.Paste -> "paste"
    AgentAction.Dismiss -> "dismiss"
    AgentAction.Back -> "back"
}

/** The one step the loop takes next, or the reason it is not taking one. */
sealed interface LoopDecision {
    data class Act(val action: AgentAction, val reason: String) : LoopDecision
    data class Ask(val question: String, val body: String, val options: List<String>) : LoopDecision
    data class Done(val evidence: String) : LoopDecision
    data class GiveUp(val reason: String) : LoopDecision
}

private fun abbreviate(text: String, max: Int = 40): String =
    if (text.length <= max) text else text.take(max - 1) + "\u2026"
