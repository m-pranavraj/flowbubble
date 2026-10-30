package com.flowbubble.app.agent

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
