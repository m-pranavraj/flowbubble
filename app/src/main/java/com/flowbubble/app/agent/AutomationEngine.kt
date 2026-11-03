package com.flowbubble.app.agent

import com.flowbubble.app.voice.Command
import com.flowbubble.app.voice.CommandParser
import java.util.Calendar
import java.util.Locale
import java.util.UUID

enum class TriggerKind { TIME, APP_OPENED, NOTIFICATION_MATCHES, PHRASE }

/**
 * When this trigger happens, run these steps. [params] holds only the trigger's own fields, keyed by
 * the names on [AutomationTrigger] so an old rule with a missing key still reads.
 */
data class AutomationTrigger(
    val kind: TriggerKind,
    val params: Map<String, String> = emptyMap()
) {
    fun param(key: String): String = params[key].orEmpty().trim()

    fun param(key: String, fallback: String): String = param(key).ifBlank { fallback }

    fun minutes(key: String, fallback: Int): Int =
        param(key).toIntOrNull()?.coerceIn(MIN_WINDOW_MINUTES, MAX_WINDOW_MINUTES) ?: fallback

    companion object {
        const val AT = "at"
        const val DAILY = "daily"
        const val WINDOW = "windowMinutes"
        const val PACKAGE = "packageQuery"
        const val PHRASE = "phraseQuery"
        const val TITLE = "titleQuery"
        const val BODY = "bodyQuery"
        const val SENDER = "senderQuery"

        const val MIN_WINDOW_MINUTES = 1
        const val MAX_WINDOW_MINUTES = 720
        const val DEFAULT_WINDOW_MINUTES = 15

        /** Every key a kind can carry, so a screen can render a form without guessing. */
        fun keysFor(kind: TriggerKind): List<String> = when (kind) {
            TriggerKind.TIME -> listOf(AT, DAILY, WINDOW)
            TriggerKind.APP_OPENED -> listOf(PACKAGE, WINDOW)
            TriggerKind.PHRASE -> listOf(PHRASE, WINDOW)
            TriggerKind.NOTIFICATION_MATCHES -> listOf(SENDER, TITLE, BODY, WINDOW)
        }

        fun defaults(kind: TriggerKind): Map<String, String> = mapOf(
            AT to "07:00",
            DAILY to "true",
            WINDOW to DEFAULT_WINDOW_MINUTES.toString(),
            PACKAGE to "",
            PHRASE to "",
            TITLE to "",
            BODY to "",
            SENDER to ""
        ).filterKeys { it in keysFor(kind) }
    }
}

/** One notification as the engine is allowed to see it: matched on, never stored or logged. */
data class NotificationSignal(
    val key: String = "",
    val packageName: String = "",
    val appLabel: String = "",
    val title: String = "",
    val text: String = "",
    val postedAtMillis: Long = 0L
)

/** What the engine may know right now. No Android types, so a rule can be decided in a test. */
data class AutomationSignal(
    val nowMillis: Long,
    val bubbleRunning: Boolean,
    val foregroundPackage: String = "",
    /** When the current app came to the front; 0 means no open was observed, so APP_OPENED cannot match. */
    val foregroundSinceMillis: Long = 0L,
    val notification: NotificationSignal? = null,
    val spokenPhrase: String = "",
    /** When it was said; 0 means nothing was just said, so PHRASE cannot match. */
    val spokenAtMillis: Long = 0L,
    val screenLocked: Boolean = false,
    val awaitingAuthentication: Boolean = false
)

/**
 * A saved rule. [firedKeys] is the small ledger that stops one trigger instance firing twice, and
 * [requiresConfirmation] is derived from the steps rather than trusted from a stored flag.
 */
data class AutomationRule(
    val id: String,
    val name: String,
    val trigger: AutomationTrigger,
    val steps: List<String>,
    val enabled: Boolean = true,
    val lastRunAtMillis: Long = 0L,
    val lastTriggerKey: String = "",
    val lastResult: String = "",
    val firedKeys: List<String> = emptyList(),
    val createdAtMillis: Long = 0L
) {
    val requiresConfirmation: Boolean get() = steps.any { AutomationEngine.stepNeedsConfirmation(it) }

    fun withFiredKey(key: String, limit: Int = AutomationEngine.MAX_FIRED_KEYS): AutomationRule =
        copy(firedKeys = (listOf(key) + firedKeys).distinct().take(limit))
}

/** The engine's answer for one rule at one moment. */
sealed interface AutomationDecision {
    val rule: AutomationRule
    val triggerKey: String

    /** Fire now: every step is safe, or gated by the normal command layer the bubble already uses. */
    data class Run(
        override val rule: AutomationRule,
        override val triggerKey: String,
        val matched: TriggerKind
    ) : AutomationDecision

    /** The trigger matched but a person must approve first, and an unanswered prompt is never approval. */
    data class Ask(
        override val rule: AutomationRule,
        override val triggerKey: String,
        val matched: TriggerKind,
        val question: String
    ) : AutomationDecision

    /** Refused, and why. Nothing is run and nothing is recorded as run. */
    data class Blocked(
        override val rule: AutomationRule,
        val reason: String,
        override val triggerKey: String = ""
    ) : AutomationDecision
}

/**
 * Decides *when [trigger], do [steps]*. Pure Kotlin on purpose: it is handed the moment, the front app,
 * the newest notification and the last thing said, and it only returns a decision. Execution belongs to
 * the command layer, so an automation never carries its own action language or its own safety rules.
 */
object AutomationEngine {
    const val MAX_FIRED_KEYS = 6
    const val MAX_STEPS = 8
    const val MAX_NAME = 40
    const val MAX_PHRASE = 300

    private const val MINUTE_MS = 60_000L
    private const val ALARM_JITTER_MS = 90_000L

    fun newId(): String = "auto-" + UUID.randomUUID().toString().take(8)

    fun evaluate(rule: AutomationRule, signal: AutomationSignal): AutomationDecision {
        val refusal = refuse(rule, signal)
        if (refusal != null) return refusal
        val matched = matchingTrigger(rule, signal)
            ?: return AutomationDecision.Blocked(rule, "Nothing has asked for it yet.")
        val key = triggerKey(matched, rule, signal)
        if (key.isBlank()) {
            return AutomationDecision.Blocked(rule, "This trigger cannot be recognised yet.")
        }
        if (rule.firedKeys.contains(key) || rule.lastTriggerKey == key) {
            return AutomationDecision.Blocked(rule, "Already ran for this ${describe(matched)}.", key)
        }
        val bypass = bypassReason(rule)
        if (bypass != null) return AutomationDecision.Blocked(rule, bypass, key)
        return if (rule.requiresConfirmation) {
            AutomationDecision.Ask(rule, key, matched, question(rule))
        } else {
            AutomationDecision.Run(rule, key, matched)
        }
    }

    /** True only for a rule that may run right now without asking anyone first. */
    fun shouldFire(rule: AutomationRule, signal: AutomationSignal): Boolean =
        evaluate(rule, signal) is AutomationDecision.Run

    /** Which of the four triggers the signal satisfies, or null when none of them do. */
    fun matchingTrigger(rule: AutomationRule, signal: AutomationSignal): TriggerKind? {
        if (rule.steps.isEmpty()) return null
        return when (rule.trigger.kind) {
            TriggerKind.TIME -> if (matchesTime(rule, signal)) TriggerKind.TIME else null
            TriggerKind.APP_OPENED -> if (matchesAppOpen(rule, signal)) TriggerKind.APP_OPENED else null
            TriggerKind.PHRASE -> if (matchesPhrase(rule, signal)) TriggerKind.PHRASE else null
            TriggerKind.NOTIFICATION_MATCHES ->
                if (matchesNotification(rule, signal)) TriggerKind.NOTIFICATION_MATCHES else null
        }
    }

    /** Why a rule will not run before any trigger is even looked at. */
    private fun refuse(rule: AutomationRule, signal: AutomationSignal): AutomationDecision.Blocked? = when {
        !signal.bubbleRunning ->
            AutomationDecision.Blocked(rule, "The bubble is off, so automations are stopped.")
        !rule.enabled -> AutomationDecision.Blocked(rule, "This automation is turned off.")
        rule.steps.isEmpty() -> AutomationDecision.Blocked(rule, "This automation has no steps.")
        signal.screenLocked ->
            AutomationDecision.Blocked(rule, "Your screen is locked, and an automation won't unlock it.")
        signal.awaitingAuthentication ->
            AutomationDecision.Blocked(rule, "Android is asking you to sign in, so nothing runs.")
        else -> null
    }

    /** A step that names a credential, PIN or CAPTCHA would be a bypass, so the whole rule is refused. */
    private fun bypassReason(rule: AutomationRule): String? {
        val bypassed = rule.steps.any { step ->
            SafetyPolicy.isCredentialLabel(step) ||
                (CommandParser.parse(step)?.let { isCredentialCommand(it) } == true)
        }
        return if (bypassed) "A step touches a password, PIN or verification check, so it will never run." else null
    }

    private fun isCredentialCommand(command: Command): Boolean = when (command) {
        is Command.Tap -> SafetyPolicy.isCredentialLabel(command.target)
        is Command.AgentTask -> SafetyPolicy.isCredentialLabel(command.goal)
        is Command.TypeText -> SafetyPolicy.isCredentialLabel(command.text)
        else -> false
    }

    /**
     * The vocabulary is the spoken one, so the confirmation rule is read off what the phrase parses to:
     * anything that sends, calls, spends, deletes, or taps a control the safety layer flags.
     * A step that parses to nothing is a dictated sentence nobody can vet, so it asks too.
     */
    fun stepNeedsConfirmation(raw: String): Boolean = when (val command = CommandParser.parse(raw)) {
        null -> true
        is Command.SendMessage -> true
        is Command.Call -> true
        is Command.Tap -> SafetyPolicy.assessTap(command.target).level != RiskLevel.SAFE
        is Command.Point -> true
        is Command.AgentTask -> SafetyPolicy.assessTap(command.goal).level != RiskLevel.SAFE
        is Command.ClearNotifications -> true
        is Command.DeleteRoutine -> true
        else -> false
    }

    /** Steps the command layer will refuse to replay, so the screen can warn before a silent no-op. */
    fun unreplayableSteps(steps: List<String>): List<String> = steps.filter { raw ->
        val command = CommandParser.parse(raw)
        command == null ||
            command is Command.SaveRoutine ||
            command is Command.RunRoutine ||
            command is Command.DeleteRoutine ||
            command is Command.FollowUp ||
            command == Command.ListRoutines
    }

    fun describe(kind: TriggerKind): String = when (kind) {
        TriggerKind.TIME -> "time"
        TriggerKind.APP_OPENED -> "app open"
        TriggerKind.PHRASE -> "phrase"
        TriggerKind.NOTIFICATION_MATCHES -> "notification"
    }

    /** The one line a person reads to know what a rule waits for. */
    fun summary(rule: AutomationRule): String {
        val trigger = rule.trigger
        val window = trigger.minutes(AutomationTrigger.WINDOW, AutomationTrigger.DEFAULT_WINDOW_MINUTES)
        return when (trigger.kind) {
            TriggerKind.TIME -> {
                val at = trigger.param(AutomationTrigger.AT, "07:00")
                val daily = trigger.param(AutomationTrigger.DAILY, "true").toBooleanStrictOrNull() != false
                if (daily) "Every day at $at" else "Once at $at"
            }
            TriggerKind.APP_OPENED ->
                "When ${trigger.param(AutomationTrigger.PACKAGE, "an app")} is opened" + windowTail(window, trigger)
            TriggerKind.PHRASE ->
                "When you say \u201C${trigger.param(AutomationTrigger.PHRASE, "\u2026")}\u201D"
            TriggerKind.NOTIFICATION_MATCHES -> {
                val from = trigger.param(AutomationTrigger.SENDER)
                val needle = trigger.param(AutomationTrigger.TITLE).ifBlank { trigger.param(AutomationTrigger.BODY) }
                "When a ${if (from.isBlank()) "notification" else "$from notification"}" +
                    (if (needle.isBlank()) "" else " mentions \u201C$needle\u201D") + " arrives"
            }
        }
    }

    private fun windowTail(window: Int, trigger: AutomationTrigger): String =
        if (trigger.param(AutomationTrigger.PACKAGE).isBlank() || window == AutomationTrigger.DEFAULT_WINDOW_MINUTES) {
            ""
        } else {
            " (at most once every $window min)"
        }

    /** What the user is asked before a confirming rule runs. Carries no screen or notification text. */
    fun question(rule: AutomationRule): String {
        val plural = if (rule.steps.size == 1) "step" else "steps"
        return "\u201C${rule.name}\u201D wants to run ${rule.steps.size} $plural"
    }

    fun nextRunAtMillis(rule: AutomationRule, afterMillis: Long): Long? {
        if (rule.trigger.kind != TriggerKind.TIME || !rule.enabled) return null
        val target = clockMinutes(rule.trigger.param(AutomationTrigger.AT, "07:00")) ?: return null
        val daily = rule.trigger.param(AutomationTrigger.DAILY, "true").toBooleanStrictOrNull() != false
        val next = Calendar.getInstance().apply {
            timeInMillis = afterMillis
            set(Calendar.HOUR_OF_DAY, target / 60)
            set(Calendar.MINUTE, target % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (next.timeInMillis <= afterMillis + 1_000L) next.add(Calendar.DAY_OF_YEAR, 1)
        if (!daily && rule.lastRunAtMillis >= next.timeInMillis - ALARM_JITTER_MS) return null
        return next.timeInMillis
    }

    private fun matchesTime(rule: AutomationRule, signal: AutomationSignal): Boolean {
        val target = clockMinutes(rule.trigger.param(AutomationTrigger.AT, "07:00")) ?: return false
        val window = rule.trigger.minutes(AutomationTrigger.WINDOW, AutomationTrigger.DEFAULT_WINDOW_MINUTES)
        val due = Calendar.getInstance().apply {
            timeInMillis = signal.nowMillis
            set(Calendar.HOUR_OF_DAY, target / 60)
            set(Calendar.MINUTE, target % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        // An alarm can land a minute early or a window late; either still counts as this due.
        val offset = signal.nowMillis - due
        return offset in -ALARM_JITTER_MS..(window * MINUTE_MS)
    }

    private fun matchesAppOpen(rule: AutomationRule, signal: AutomationSignal): Boolean {
        val want = rule.trigger.param(AutomationTrigger.PACKAGE).lowercase(Locale.ROOT)
        val seen = signal.foregroundPackage.lowercase(Locale.ROOT)
        if (want.isBlank() || seen.isBlank()) return false
        val window = rule.trigger.minutes(AutomationTrigger.WINDOW, AutomationTrigger.DEFAULT_WINDOW_MINUTES)
        if (signal.foregroundSinceMillis <= 0L) return false
        if (signal.nowMillis - signal.foregroundSinceMillis > window * MINUTE_MS) return false
        return appMatches(want, seen)
    }

    /** Accepts a package name or the loose word a person types for it ("spotify", "com.spotify.music"). */
    private fun appMatches(want: String, seen: String): Boolean =
        seen == want || seen.endsWith(".$want") || seen.contains(want) || want.contains(seen.substringAfterLast('.'))

    private fun matchesPhrase(rule: AutomationRule, signal: AutomationSignal): Boolean {
        val want = words(rule.trigger.param(AutomationTrigger.PHRASE))
        val said = words(signal.spokenPhrase)
        if (want.isBlank() || said.isBlank()) return false
        if (!isFresh(signal.spokenAtMillis, signal.nowMillis, rule)) return false
        return said == want || said.contains(want)
    }

    private fun matchesNotification(rule: AutomationRule, signal: AutomationSignal): Boolean {
        val note = signal.notification ?: return false
        if (!isFresh(note.postedAtMillis, signal.nowMillis, rule)) return false
        val sender = rule.trigger.param(AutomationTrigger.SENDER).lowercase(Locale.ROOT)
        if (sender.isNotBlank()) {
            val seen = "${note.packageName} ${note.appLabel}".lowercase(Locale.ROOT)
            if (!seen.contains(sender)) return false
        }
        val needles = listOf(
            rule.trigger.param(AutomationTrigger.TITLE).lowercase(Locale.ROOT) to note.title.lowercase(Locale.ROOT),
            rule.trigger.param(AutomationTrigger.BODY).lowercase(Locale.ROOT) to note.text.lowercase(Locale.ROOT)
        ).filter { (needle, _) -> needle.isNotBlank() }
        if (needles.isEmpty() && sender.isBlank()) return false
        return needles.all { (needle, haystack) -> haystack.contains(needle) }
    }

    private fun isFresh(atMillis: Long, nowMillis: Long, rule: AutomationRule): Boolean {
        if (atMillis <= 0L) return false
        val window = rule.trigger.minutes(AutomationTrigger.WINDOW, AutomationTrigger.DEFAULT_WINDOW_MINUTES)
        val age = nowMillis - atMillis
        return age in -ALARM_JITTER_MS..(window * MINUTE_MS)
    }

    private fun triggerKey(matched: TriggerKind, rule: AutomationRule, signal: AutomationSignal): String =
        when (matched) {
            TriggerKind.TIME -> {
                val at = rule.trigger.param(AutomationTrigger.AT, "07:00")
                val daily = rule.trigger.param(AutomationTrigger.DAILY, "true").toBooleanStrictOrNull() != false
                if (daily) "time:${dayOf(signal.nowMillis)}T$at" else "time:once:$at"
            }
            TriggerKind.APP_OPENED -> "app:${signal.foregroundPackage}@${signal.foregroundSinceMillis / MINUTE_MS}"
            TriggerKind.PHRASE ->
                "phrase:${words(rule.trigger.param(AutomationTrigger.PHRASE))}@${signal.spokenAtMillis / MINUTE_MS}"
            TriggerKind.NOTIFICATION_MATCHES -> notificationKey(signal)
        }

    private fun notificationKey(signal: AutomationSignal): String {
        val note = signal.notification ?: return ""
        val id = note.key.ifBlank { "${note.packageName}@${note.postedAtMillis}" }
        return if (id.isBlank() || id == "@0") "" else "notif:$id"
    }

    private fun dayOf(millis: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = millis }
        return "%04d-%02d-%02d".format(
            Locale.ROOT,
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun clockMinutes(raw: String): Int? {
        val parts = raw.trim().split(':')
        val hour = parts.firstOrNull()?.toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
    }

    private fun words(raw: String): String =
        raw.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
}
