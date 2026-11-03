package com.flowbubble.app.core

import android.content.Context
import androidx.core.content.edit
import com.flowbubble.app.agent.AutomationEngine
import com.flowbubble.app.agent.AutomationRule
import com.flowbubble.app.agent.AutomationTrigger
import com.flowbubble.app.agent.TriggerKind
import java.net.URLEncoder

/**
 * Saved automations: when a trigger happens, run these steps. One preference key per rule holding one
 * delimiter-joined record, the shape RoutineStore keeps. Steps stay plain command phrases so they
 * replay through the same parser and the same gates a spoken routine does: no second command language.
 */
object AutomationStore {
    private const val FILE = "flowbubble_automations"
    private const val KEY_PREFIX = "a:"
    private const val MAX_RULES = 20

    private val FIELD: String = Char(0x1F).toString()
    private val ITEM: String = Char(0x1E).toString()
    private val PAIR: String = Char(0x1D).toString()
    private val KV: String = Char(0x1C).toString()

    sealed interface SaveResult {
        data class Saved(val rule: AutomationRule) : SaveResult
        data class Rejected(val reason: String) : SaveResult
    }

    /** The only thing the store needs from a device: read the records, write one, drop one. */
    interface Records {
        fun readAll(): Map<String, String>
        fun write(id: String, raw: String)
        fun drop(id: String)
    }

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun records(context: Context): Records = object : Records {
        override fun readAll(): Map<String, String> =
            sp(context).all
                .filter { (key, value) -> key.startsWith(KEY_PREFIX) && value is String }
                .mapKeys { (key, _) -> key.removePrefix(KEY_PREFIX) }
                .mapValues { (_, value) -> value as String }

        // Written synchronously: a rule the user just saved has an alarm behind it, and an async
        // apply() that is still in flight when the process dies loses both the rule and its schedule.
        override fun write(id: String, raw: String) {
            sp(context).edit(commit = true) { putString(KEY_PREFIX + encodeKey(id), raw) }
        }

        override fun drop(id: String) {
            sp(context).edit(commit = true) { remove(KEY_PREFIX + encodeKey(id)) }
        }
    }

    /** The same store over a map, so the JVM tests exercise the real read and write path. */
    fun inMemory(): Records = object : Records {
        private val map = LinkedHashMap<String, String>()

        override fun readAll(): Map<String, String> = LinkedHashMap(map)

        override fun write(id: String, raw: String) {
            map[id] = raw
        }

        override fun drop(id: String) {
            map.remove(id)
        }
    }

    fun list(context: Context): List<AutomationRule> = list(records(context))

    fun list(records: Records): List<AutomationRule> =
        decodeAll(records.readAll()).sortedBy { it.name.lowercase() }

    /** By id, then by an exact name, then by a name only one rule matches — the RoutineStore rule. */
    fun find(context: Context, query: String): AutomationRule? = find(records(context), query)

    fun find(records: Records, query: String): AutomationRule? {
        val needle = query.trim().lowercase()
        if (needle.isBlank()) return null
        val all = list(records)
        return all.firstOrNull { it.id == query.trim() }
            ?: all.firstOrNull { it.name.lowercase() == needle }
            ?: all.filter { it.name.lowercase().contains(needle) }.takeIf { it.size == 1 }?.first()
    }

    fun save(context: Context, rule: AutomationRule): SaveResult = save(records(context), rule)

    fun save(records: Records, rule: AutomationRule): SaveResult {
        val cleaned = sanitize(rule)
        val problems = reason(cleaned)
        if (problems != null) return SaveResult.Rejected(problems)
        val existing = list(records)
        if (existing.none { it.id == cleaned.id } && existing.size >= MAX_RULES) {
            return SaveResult.Rejected("You already have $MAX_RULES automations. Delete one before saving another.")
        }
        records.write(cleaned.id, encode(cleaned))
        return SaveResult.Saved(cleaned)
    }

    fun delete(context: Context, id: String): Boolean = delete(records(context), id)

    fun delete(records: Records, id: String): Boolean {
        val rule = find(records, id) ?: return false
        records.drop(rule.id)
        return true
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean): Boolean =
        setEnabled(records(context), id, enabled)

    fun setEnabled(records: Records, id: String, enabled: Boolean): Boolean {
        val rule = find(records, id) ?: return false
        records.write(rule.id, encode(rule.copy(enabled = enabled)))
        return true
    }

    /** Stamps a rule that really ran, so the same trigger instance can never fire it twice. */
    fun recordRun(context: Context, id: String, triggerKey: String, atMillis: Long, result: String): Boolean =
        recordRun(records(context), id, triggerKey, atMillis, result)

    fun recordRun(records: Records, id: String, triggerKey: String, atMillis: Long, result: String): Boolean {
        val rule = find(records, id) ?: return false
        records.write(
            rule.id,
            encode(
                rule.copy(
                    lastRunAtMillis = atMillis,
                    lastTriggerKey = triggerKey,
                    lastResult = result
                ).withFired(triggerKey)
            )
        )
        return true
    }

    /**
     * A confirming rule asked instead of running. The key is recorded so the same trigger instance does
     * not stack prompts, and nothing is written as a run: an unanswered prompt never becomes a run.
     */
    fun recordAsked(context: Context, id: String, triggerKey: String, result: String): Boolean =
        recordAsked(records(context), id, triggerKey, result)

    fun recordAsked(records: Records, id: String, triggerKey: String, result: String): Boolean {
        val rule = find(records, id) ?: return false
        records.write(rule.id, encode(rule.copy(lastTriggerKey = triggerKey, lastResult = result).withFired(triggerKey)))
        return true
    }

    /** A blank rule the create flow can fill in. */
    fun draft(kind: TriggerKind): AutomationRule = AutomationRule(
        id = AutomationEngine.newId(),
        name = "",
        trigger = AutomationTrigger(kind, AutomationTrigger.defaults(kind)),
        steps = emptyList(),
        enabled = true,
        createdAtMillis = System.currentTimeMillis()
    )

    fun clear(context: Context) = sp(context).edit {
        sp(context).all.keys.filter { it.startsWith(KEY_PREFIX) }.forEach { remove(it) }
    }

    /** Trims to the same limits the engine publishes and drops control characters that would split a record. */
    private fun sanitize(rule: AutomationRule): AutomationRule = rule.copy(
        name = clean(rule.name).trim().trimEnd('.', '!', '?', ',').take(AutomationEngine.MAX_NAME).trim(),
        trigger = AutomationTrigger(
            rule.trigger.kind,
            rule.trigger.params
                .filterKeys { it in AutomationTrigger.keysFor(rule.trigger.kind) }
                .mapValues { (_, value) -> clean(value).take(AutomationEngine.MAX_PHRASE).trim() }
        ),
        steps = rule.steps.map { clean(it).trim().trimEnd('.', '!').take(AutomationEngine.MAX_PHRASE).trim() }
            .filter { it.isNotEmpty() }
            .take(AutomationEngine.MAX_STEPS),
        lastResult = clean(rule.lastResult).take(120).trim(),
        firedKeys = rule.firedKeys.takeLast(AutomationEngine.MAX_FIRED_KEYS)
    )

    private fun reason(rule: AutomationRule): String? = when {
        rule.name.isBlank() -> "The automation needs a name."
        rule.steps.isEmpty() -> "The automation needs at least one step to run."
        rule.steps.size > AutomationEngine.MAX_STEPS ->
            "An automation can run at most ${AutomationEngine.MAX_STEPS} steps."
        rule.trigger.kind == TriggerKind.TIME &&
            !rule.trigger.param(AutomationTrigger.AT).matchesClock() -> "A time automation needs a time like 07:30."
        rule.trigger.kind == TriggerKind.APP_OPENED && rule.trigger.param(AutomationTrigger.PACKAGE).isBlank() ->
            "Tell the automation which app to watch for."
        rule.trigger.kind == TriggerKind.PHRASE && rule.trigger.param(AutomationTrigger.PHRASE).isBlank() ->
            "Tell the automation which phrase to listen for."
        rule.trigger.kind == TriggerKind.NOTIFICATION_MATCHES &&
            rule.trigger.param(AutomationTrigger.SENDER).isBlank() &&
            rule.trigger.param(AutomationTrigger.TITLE).isBlank() &&
            rule.trigger.param(AutomationTrigger.BODY).isBlank() ->
            "A notification automation needs something to match: a sender, a title, or a word in it."
        else -> null
    }

    private fun String.matchesClock(): Boolean = Regex("^\\d{1,2}:\\d{2}$").matches(trim())

    private fun clean(raw: String): String = raw.replace(Regex("\\s+"), " ").filter { it.code >= 0x20 }

    private fun encodeKey(id: String): String = URLEncoder.encode(id.lowercase(), "UTF-8")

    fun encode(rule: AutomationRule): String = listOf(
        rule.id,
        rule.name,
        rule.trigger.kind.name,
        encodeParams(rule.trigger.params),
        rule.steps.joinToString(ITEM),
        rule.enabled.toString(),
        rule.lastRunAtMillis.toString(),
        rule.lastTriggerKey,
        rule.lastResult,
        rule.firedKeys.joinToString(ITEM),
        rule.createdAtMillis.toString()
    ).joinToString(FIELD)

    fun decode(raw: String): AutomationRule? {
        val parts = raw.split(FIELD)
        if (parts.size < 11) return null
        val kind = TriggerKind.entries.firstOrNull { it.name == parts[2] } ?: return null
        if (parts[0].isBlank() || parts[1].isBlank()) return null
        val fired = parts.getOrNull(9)?.split(ITEM)?.filter { it.isNotBlank() }.orEmpty()
        return AutomationRule(
            id = parts[0],
            name = parts[1],
            trigger = AutomationTrigger(kind, decodeParams(parts[3])),
            steps = parts[4].split(ITEM).filter { it.isNotBlank() },
            enabled = parts[5].toBooleanStrictOrNull() ?: true,
            lastRunAtMillis = parts[6].toLongOrNull() ?: 0L,
            lastTriggerKey = parts[7],
            lastResult = parts[8],
            firedKeys = fired,
            createdAtMillis = parts[10].toLongOrNull() ?: 0L
        )
    }

    private fun decodeAll(records: Map<String, String>): List<AutomationRule> =
        records.mapNotNull { (_, raw) -> decode(raw) }

    private fun encodeParams(params: Map<String, String>): String =
        params.entries.joinToString(PAIR) { (key, value) -> "$key$KV$value" }

    private fun decodeParams(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split(PAIR).mapNotNull { entry ->
            val parts = entry.split(KV, limit = 2)
            if (parts.size != 2 || parts[0].isBlank()) null else parts[0] to parts[1]
        }.toMap()
    }

    private fun AutomationRule.withFired(key: String): AutomationRule =
        copy(firedKeys = (firedKeys + key).distinct().takeLast(AutomationEngine.MAX_FIRED_KEYS))
}
