package com.flowbubble.app.agent

/**
 * How the user answered one confirmation. A missing or timed-out answer is not an approval.
 */
enum class ConfirmationStatus { APPROVED, DENIED, TIMED_OUT }

/**
 * The proof a step needs to run: the id of the exact action shown, the user's answer to it, and
 * whether that answer was fresh this turn. A stale or batched approval fails the CRITICAL gate.
 */
data class ActionConfirmation(
    val actionId: String,
    val status: ConfirmationStatus,
    val freshThisTurn: Boolean = true
) {
    companion object {
        fun approved(actionId: String) = ActionConfirmation(actionId, ConfirmationStatus.APPROVED)
        val TimedOut = ActionConfirmation("", ConfirmationStatus.TIMED_OUT, freshThisTurn = false)
    }
}

/**
 * A pure, immutable description of one executable step so the loop can assert on it before it acts
 * and check the screen against it after. Deliberately no Android types - a JVM test constructs it
 * directly, and the safety gate is only a function of [tier], not of what is on screen.
 */
data class ActionContract(
    val id: String,
    /** What the step points at: a control, a field, an app. */
    val target: String,
    /** What it changes, in plain words, so the confirmation and the trace can say the same thing. */
    val change: String,
    /** Where it sits on the [RiskTier] ladder; decides whether a confirmation is required at all. */
    val tier: RiskTier,
    /** A label or state worth looking for on the screen afterwards; blank means "any change counts". */
    val expectedEvidence: String = "",
    /** What to say when the screen cannot confirm it, so the loop never claims an unproven success. */
    val fallbackMessage: String,
    /** Only filled for send-like steps; drives the recipient-not-named rule. */
    val recipient: String = "",
    /** Recipients the user actually named this turn, kept beside the step so re-confirming is local. */
    val userNamedRecipients: List<String> = emptyList()
) {
    init {
        require(id.isNotBlank()) { "A contract needs an id so a confirmation can match one action" }
        require(change.isNotBlank()) { "A contract has to say what the step changes" }
        require(tier != RiskTier.CRITICAL || fallbackMessage.isNotBlank()) {
            "A CRITICAL step must say what to do when it can't be proven"
        }
    }

    /** Whether the loop has to ask before running this step; CRITICAL asks, BLOCKED never runs. */
    val requiresConfirmation: Boolean
        get() = tier == RiskTier.CONFIRM || tier == RiskTier.CRITICAL

    /** The strictest form of confirmation: fresh, this turn, for exactly this step. */
    val requiresFreshConfirmation: Boolean
        get() = tier == RiskTier.CRITICAL

    /** True when the loop may run the step given the answer it holds; never bypasses the gate. */
    fun mayRun(confirmation: ActionConfirmation?): Boolean =
        SafetyPolicy.mayExecute(id, tier, confirmation)

    /** True when the recipient rule pushes this step to CRITICAL because nobody was named. */
    fun unnamedRecipientMatch(): CriticalMatch? =
        if (recipient.isBlank()) null else CriticalPolicy.classifySendToPerson(recipient, userNamedRecipients)

    /** Whether the step is proven by the screen as it now reads; blank evidence means any change. */
    fun provenBy(appearedLabels: List<String>, screenChanged: Boolean): Boolean {
        if (expectedEvidence.isBlank()) return screenChanged
        return appearedLabels.asSequence()
            .filter { it.isNotBlank() }
            .any { it.contains(expectedEvidence, true) || expectedEvidence.contains(it, true) }
    }

    /** The honest headline once the step has run: a proof line, or the fallback, never a guess. */
    fun outcomeNote(appearedLabels: List<String>, screenChanged: Boolean): String =
        if (provenBy(appearedLabels, screenChanged)) "Did it: \u201C${shortLabel(target)}\u201D"
        else fallbackMessage

    companion object {
        /** The contract for a confirmation-required tap, at whatever tier SafetyPolicy already chose. */
        fun forTap(label: String, ordinal: Int?, assessment: RiskAssessment): ActionContract {
            val critical = CriticalPolicy.classify(label)
            val id = if (ordinal == null) "tap:$label" else "tap:$label#$ordinal"
            val fallback = if (critical != null) {
                "I tapped \u201C${shortLabel(label)}\u201D, but nothing proved it - ${critical.reason} " +
                    "I stopped instead of guessing."
            } else {
                "I tapped \u201C${shortLabel(label)}\u201D, but the screen didn't change, so I stopped instead of guessing."
            }
            return ActionContract(
                id = id,
                target = label,
                change = "taps \u201C${shortLabel(label)}\u201D",
                tier = if (critical != null) RiskTier.CRITICAL else assessment.tier,
                expectedEvidence = "",
                fallbackMessage = fallback
            )
        }
    }
}

private fun shortLabel(text: String, max: Int = 30): String =
    if (text.length <= max) text else text.take(max - 1) + "\u2026"
