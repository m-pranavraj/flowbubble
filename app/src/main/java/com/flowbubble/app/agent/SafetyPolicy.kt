package com.flowbubble.app.agent

import java.util.Locale

enum class RiskLevel { SAFE, CONFIRM, BLOCKED }

data class RiskAssessment(val level: RiskLevel, val reason: String = "", val tier: RiskTier = RiskTier.forLevel(level)) {
    companion object {
        val Safe = RiskAssessment(RiskLevel.SAFE)
    }
}

/**
 * Conservative gate for actions the agent is about to take on screen.
 * Any ambiguity resolves toward asking the user.
 */
object SafetyPolicy {
    private val credentialPhrases = listOf(
        "password", "pass code", "passcode", "pin", "cvv", "cvc",
        "card number", "credit card", "debit card", "security code",
        "verification code", "one time code", "otp", "ssn",
        "social security", "routing number", "iban", "seed phrase",
        "recovery phrase", "wallet key",
        "captcha", "not a robot", "human verification", "security check",
        "biometric", "fingerprint", "face unlock", "pattern unlock"
    )

    private val moneyTokens = setOf(
        "pay", "payment", "purchase", "buy", "checkout", "subscribe",
        "subscription", "renew", "renewal", "transfer", "donate", "bid",
        "book", "reserve", "rent", "order", "tip"
    )
    private val sendTokens = setOf(
        "send", "submit", "post", "publish", "share", "reply", "confirm",
        "accept", "allow", "grant", "install", "update", "download",
        "register", "signup"
    )
    private val destructiveTokens = setOf(
        "delete", "remove", "erase", "clear", "uninstall", "disable",
        "block", "report", "reset", "wipe", "logout", "dismiss",
        "discard", "unsubscribe"
    )
    private val callTokens = setOf("call", "dial")

    private val searchHints = listOf(
        "search", "find", "query", "ask", "type to", "look up"
    )
    private val composeHints = listOf(
        "message", "reply", "comment", "email", "caption", "post", "chat"
    )

    fun assessTap(label: String): RiskAssessment {
        val normalized = normalize(label)
        if (normalized.isEmpty()) return RiskAssessment.Safe

        credentialPhrases.firstOrNull { phrase -> containsPhrase(normalized, phrase) }?.let { phrase ->
            return RiskAssessment(
                RiskLevel.BLOCKED,
                "This looks like a credential field (\"$phrase\"). Your Bubble won't touch passwords, PINs, or payment details."
            )
        }

        // CRITICAL is consulted before the ordinary confirmation tiers so money, transfers and
        // irreversible deletions land on the strictest gate, never on the looser one below.
        CriticalPolicy.classify(label)?.let { return criticalAssessment(it, label, "Tapping") }

        val tokens = tokens(normalized)
        if (tokens.any { it in moneyTokens }) {
            return RiskAssessment(
                RiskLevel.CONFIRM,
                "Tapping \"$label\" could start a payment, purchase, booking, or subscription."
            )
        }
        if (tokens.any { it in sendTokens }) {
            return RiskAssessment(
                RiskLevel.CONFIRM,
                "Tapping \"$label\" could send, submit, allow, or install something."
            )
        }
        if (tokens.any { it in destructiveTokens }) {
            return RiskAssessment(
                RiskLevel.CONFIRM,
                "Tapping \"$label\" could delete or undo something."
            )
        }
        if (tokens.any { it in callTokens }) {
            return RiskAssessment(
                RiskLevel.CONFIRM,
                "Tapping \"$label\" could place a call."
            )
        }
        return RiskAssessment.Safe
    }

    fun assessSubmission(fieldLabel: String): RiskAssessment {
        val normalized = normalize(fieldLabel)
        credentialPhrases.firstOrNull { phrase -> containsPhrase(normalized, phrase) }?.let { phrase ->
            return RiskAssessment(
                RiskLevel.BLOCKED,
                "This looks like a credential field (\"$phrase\"). Your Bubble won't type or submit here."
            )
        }
        CriticalPolicy.classify(fieldLabel)?.let { return criticalAssessment(it, fieldLabel, "Submitting") }
        if (normalized.isEmpty()) {
            return RiskAssessment(
                RiskLevel.CONFIRM,
                "I can't tell what this field is for. Pressing Enter could submit or send."
            )
        }
        if (searchHints.any { containsPhrase(normalized, it) }) return RiskAssessment.Safe
        if (composeHints.any { containsPhrase(normalized, it) }) {
            return RiskAssessment(
                RiskLevel.CONFIRM,
                "This field looks like a message composer. Pressing Enter could send it."
            )
        }
        return RiskAssessment(
            RiskLevel.CONFIRM,
            "Pressing Enter here could submit a form or send a message."
        )
    }

    fun isCredentialLabel(label: String): Boolean {
        val normalized = normalize(label)
        return credentialPhrases.any { phrase -> containsPhrase(normalized, phrase) }
    }

    /**
     * Sending to a person the user did not name this turn is CRITICAL. The recipient comes from the
     * trusted user request, never from on-screen text, so an unnamed recipient always raises the gate.
     */
    fun assessSendToPerson(recipient: String, userNamedRecipients: List<String>): RiskAssessment =
        CriticalPolicy.classifySendToPerson(recipient, userNamedRecipients)?.let { match ->
            RiskAssessment(
                RiskLevel.CONFIRM,
                "Sending to \u201C$recipient\u201D is critical: ${match.reason.lowercase(Locale.ROOT)} " +
                    "Approve this exact send and nothing else.",
                tier = RiskTier.CRITICAL
            )
        } ?: RiskAssessment(
            RiskLevel.CONFIRM,
            "Sending could reach someone; approve this send."
        )

    /**
     * Whether the loop may run a step given the confirmation it holds. A CRITICAL step needs an
     * explicit approval for this exact id, taken fresh this turn; a timeout, a denial, a reused
     * approval or none at all never opens the gate, and BLOCKED is never opened by anything.
     */
    fun mayExecute(actionId: String, tier: RiskTier, confirmation: ActionConfirmation?): Boolean = when (tier) {
        RiskTier.SAFE -> true
        RiskTier.BLOCKED -> false
        RiskTier.CONFIRM ->
            confirmation?.actionId == actionId && confirmation?.status == ConfirmationStatus.APPROVED
        RiskTier.CRITICAL ->
            confirmation?.actionId == actionId &&
                confirmation?.status == ConfirmationStatus.APPROVED &&
                confirmation?.freshThisTurn == true
    }

    private fun criticalAssessment(match: CriticalMatch, label: String, verb: String): RiskAssessment =
        RiskAssessment(
            RiskLevel.CONFIRM,
            "$verb \u201C$label\u201D is a critical step: ${match.reason.lowercase(Locale.ROOT)} " +
                "Your Bubble will only do this exact action if you approve it now.",
            tier = RiskTier.CRITICAL
        )

    private fun normalize(raw: String): String =
        raw.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun tokens(normalized: String): List<String> =
        normalized.split(' ').filter { it.isNotBlank() }

    private fun containsPhrase(normalized: String, phrase: String): Boolean {
        val needle = phrase.lowercase(Locale.ROOT)
        if (needle.isEmpty()) return false
        if (needle.contains(' ')) return normalized.contains(needle)
        return tokens(normalized).any { it == needle }
    }
}
