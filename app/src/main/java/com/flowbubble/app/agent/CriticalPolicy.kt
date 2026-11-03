package com.flowbubble.app.agent

import java.util.Locale

/**
 * The severity ladder the loop reads off a safety decision. SAFE acts freely, CONFIRM asks first,
 * BLOCKED is a hard wall the agent never passes, and CRITICAL sits strictly above all of them: the
 * agent may still act, but only on an explicit, fresh, per-action confirmation - never a batch, a
 * stale approval, or a confirmation that timed out.
 */
enum class RiskTier {
    SAFE, CONFIRM, BLOCKED, CRITICAL;

    companion object {
        fun forLevel(level: RiskLevel): RiskTier = when (level) {
            RiskLevel.SAFE -> RiskTier.SAFE
            RiskLevel.CONFIRM -> RiskTier.CONFIRM
            RiskLevel.BLOCKED -> RiskTier.BLOCKED
        }
    }
}

/** Which CRITICAL rule an action tripped, kept as data so callers can name the reason. */
enum class CriticalCategory { SPEND_MONEY, TRANSFER_MONEY, IRREVERSIBLE_DELETE, PASSWORD_CHANGE, SEND_TO_UNNAMED }

data class CriticalMatch(val category: CriticalCategory, val reason: String) {
    val tier: RiskTier get() = RiskTier.CRITICAL
}

/**
 * The rule set that lifts a confirmation-required action above CONFIRM into CRITICAL. Kept apart
 * from SafetyPolicy as one readable list so the extra-strict rules can be checked on a single page.
 * It only ever names categories that already need confirmation; it never lowers a tier.
 */
object CriticalPolicy {
    // Committing or spending the user's money.
    private val spendMoney = setOf(
        "pay", "payment", "purchase", "buy", "checkout", "subscribe", "subscription",
        "renew", "renewal", "bid", "book", "reserve", "rent", "order", "reorder", "tip", "donate"
    )
    // Moving the user's own balance out, which a purchase does not cover.
    private val transferMoney = setOf(
        "transfer", "wire", "remittance", "withdraw", "cashout", "zelle", "venmo", "paypal"
    )
    // Deleting user data for good, deliberately narrower than the loose "clear" / "dismiss" words.
    private val eraseWords = setOf("erase", "wipe", "purge", "shred", "format")
    private val deleteVerbs = setOf("delete", "remove", "destroy")
    private val dataObjects = setOf(
        "account", "data", "file", "files", "photo", "photos", "message", "messages",
        "chat", "chats", "history", "backup", "backups", "contact", "contacts", "everything", "all"
    )
    // A password change is critical as an intent. Actually typing a password stays blocked upstream.
    private val passwordChangePhrases = listOf(
        "change password", "reset password", "new password", "set password", "update password"
    )

    /** Classify an action from its own label. Returns null when nothing here clears the CRITICAL bar. */
    fun classify(label: String): CriticalMatch? {
        val normalized = normalize(label)
        if (normalized.isEmpty()) return null
        val tokens = normalized.split(' ').filter { it.isNotBlank() }
        return when {
            passwordChangePhrases.any { normalized.contains(it) } ->
                CriticalMatch(CriticalCategory.PASSWORD_CHANGE, "This changes a password.")
            tokens.any { it in transferMoney } || normalized.contains("send money") ->
                CriticalMatch(CriticalCategory.TRANSFER_MONEY, "This moves money out of your account.")
            deletesForever(normalized, tokens) ->
                CriticalMatch(CriticalCategory.IRREVERSIBLE_DELETE, "This permanently deletes data you can't get back.")
            tokens.any { it in spendMoney } ->
                CriticalMatch(CriticalCategory.SPEND_MONEY, "This spends or commits your money.")
            else -> null
        }
    }

    /**
     * Sending to a person is only critical when the recipient was not named by the user in this turn.
     * On-screen and spoken text is treated as untrusted, so an unnamed recipient never earns a pass.
     */
    fun classifySendToPerson(recipient: String, userNamedRecipients: List<String>): CriticalMatch? {
        val wanted = normalize(recipient)
        if (wanted.isEmpty()) return null
        val named = userNamedRecipients.map { normalize(it) }.toSet()
        return if (wanted in named) null else CriticalMatch(
            CriticalCategory.SEND_TO_UNNAMED,
            "This sends to someone you didn't name in this request."
        )
    }

    private fun deletesForever(normalized: String, tokens: List<String>): Boolean =
        tokens.any { it in eraseWords } ||
            (tokens.any { it in deleteVerbs } && tokens.any { it in dataObjects }) ||
            normalized.contains("factory reset") || normalized.contains("reset device")

    private fun normalize(raw: String): String =
        raw.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
}
