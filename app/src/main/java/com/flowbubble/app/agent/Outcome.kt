package com.flowbubble.app.agent

/**
 * The one vocabulary every route uses to say what happened. Actions that Android never confirms
 * are PARTIAL or AWAITING_USER, never COMPLETED.
 */
enum class OutcomeKind {
    COMPLETED,
    PARTIAL,
    AWAITING_USER,
    UNSUPPORTED,
    FAILED;

    /** The string written to history, so old entries and new ones stay readable. */
    fun historyStatus(): String = when (this) {
        COMPLETED -> "done"
        PARTIAL -> "unverified"
        AWAITING_USER -> "awaiting user"
        UNSUPPORTED -> "unsupported"
        FAILED -> "failed"
    }

    companion object {
        fun fromHistoryStatus(status: String): OutcomeKind = when (status.lowercase()) {
            "done", "copied" -> COMPLETED
            "unverified", "requested" -> PARTIAL
            "awaiting user", "setup needed", "permission missing" -> AWAITING_USER
            "unsupported", "not found" -> UNSUPPORTED
            "failed" -> FAILED
            else -> PARTIAL
        }
    }
}

/**
 * One line of the execution trace: what was attempted and what was actually observed.
 * `evidence` is only non-null when something verifiable changed, which is what keeps the
 * product honest — a gesture that Android never confirms is recorded with no evidence.
 */
data class TraceStep(
    val label: String,
    val detail: String = "",
    val evidence: String? = null,
    /** Pauses and waits are planning scaffolding, not actions; they never need proof. */
    val informational: Boolean = false
) {
    val verified: Boolean get() = evidence != null || informational

    fun render(): String = buildString {
        append(label)
        if (detail.isNotBlank()) append(" — ").append(detail)
        if (informational) return@buildString
        if (evidence != null) {
            append("\n   confirmed: ").append(evidence)
        } else {
            append("\n   not confirmed by the system")
        }
    }
}

/** The whole answer to a request: the outcome, plus the trace and evidence that justify it. */
data class ExecutionTrace(
    val asked: String,
    val route: String,
    val steps: List<TraceStep> = emptyList(),
    val outcome: OutcomeKind = OutcomeKind.PARTIAL,
    val headline: String = ""
) {
    val allVerified: Boolean get() = steps.isNotEmpty() && steps.all { it.verified }

    /**
     * A run is only COMPLETED when every step produced evidence; otherwise the honest word is
     * PARTIAL even if nothing failed.
     */
    fun outcomeFor(stepsDone: Boolean): OutcomeKind = when {
        !stepsDone -> outcome
        steps.isEmpty() -> outcome
        allVerified -> OutcomeKind.COMPLETED
        else -> OutcomeKind.PARTIAL
    }

    fun renderBody(maxSteps: Int = 8): String = buildString {
        if (headline.isNotBlank()) append(headline).append("\n\n")
        steps.take(maxSteps).forEachIndexed { index, step ->
            append("${index + 1}. ").append(step.render()).append("\n")
        }
        if (steps.size > maxSteps) append("… ${steps.size - maxSteps} more steps\n")
    }
}
