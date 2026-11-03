package com.flowbubble.app.agent

import java.util.Locale

/**
 * How a fact about the screen was established. What is drawn on a screen is input, not proof, so only
 * the channels an app or the system vouch for are counted as verified.
 */
enum class FactSource {
    ACCESSIBILITY_TEXT,
    OCR,
    FOREGROUND_PACKAGE,
    USER_SPOKEN,
    INFERRED;

    /** Pixels can show anything and a guess vouches for nothing, so those two stay unverified. */
    val verified: Boolean get() = this == ACCESSIBILITY_TEXT || this == FOREGROUND_PACKAGE || this == USER_SPOKEN

    fun describe(): String = when (this) {
        ACCESSIBILITY_TEXT -> "read from the app's own text"
        OCR -> "read off the screen pixels, which the app never confirmed"
        FOREGROUND_PACKAGE -> "confirmed by the system as the app in front"
        USER_SPOKEN -> "said by you out loud"
        INFERRED -> "guessed from what else is known"
    }
}

/**
 * One thing a task learned. [value] lives in memory for the length of that task and is never
 * rendered: a trace or a panel gets [describe], which names the fact without carrying its text.
 */
data class TaskFact(
    val key: String,
    val value: String,
    val packageName: String,
    val source: FactSource,
    val observedAtMs: Long,
    /** The live-model revision the value was read at, or -1 when the fact never came off a screen. */
    val screenRevision: Int = -1
) {
    val verified: Boolean get() = source.verified

    fun ageMs(now: Long): Long = (now - observedAtMs).coerceAtLeast(0L)

    fun isFresh(ttlMs: Long, now: Long): Boolean = ageMs(now) <= ttlMs

    fun describe(): String = "$key (${source.describe()}, in $packageName)"
}

/** Whether a fact was allowed into the store, and why not when it wasn't. */
sealed interface FactRecord {
    data class Stored(val fact: TaskFact) : FactRecord
    data class Refused(val reason: String) : FactRecord
}

/** Whether a fact learned in one app may be spent in another. */
sealed interface FactHandoff {
    data class Usable(val fact: TaskFact, val crossedApps: Boolean) : FactHandoff
    data class NeedsRecheck(val fact: TaskFact, val reason: String) : FactHandoff
    data class Missing(val key: String, val reason: String) : FactHandoff
}

/**
 * What one task has verified so far, carried across apps: a confirmation number read in mail can be
 * typed into the app that asked for it. Three bounds keep it honest — every fact expires, the store
 * belongs to one task id, and it is emptied when the task ends, because a fact that outlives its task
 * is just a rumour about somebody else's screen.
 */
class TaskContext(
    val taskId: String,
    val clock: () -> Long = { System.currentTimeMillis() },
    val factTtlMs: Long = DEFAULT_TTL_MS,
    val maxFacts: Int = DEFAULT_MAX_FACTS
) {

    private val stored = ArrayList<TaskFact>()

    /** Non-null once the task is over. After that the store reads as empty and nothing enters it. */
    var closedReason: String? = null
        private set

    val isOpen: Boolean get() = closedReason == null

    /** How much the task currently knows. */
    fun size(): Int = stored.size

    fun keys(): List<String> = stored.map { it.key }

    /**
     * Keeps what was observed under [key], naming the app it came from and how it was verified. The
     * newest reading of a key replaces the older one, because the screen moved on.
     */
    fun record(
        key: String,
        value: String,
        packageName: String,
        source: FactSource,
        screenRevision: Int = -1
    ): FactRecord {
        if (!isOpen) return FactRecord.Refused("this task's facts were closed: $closedReason")
        val cleanKey = normalize(key)
        val cleanValue = value.trim()
        when {
            cleanKey.isEmpty() -> return FactRecord.Refused("a fact needs a name to be found under")
            cleanValue.isEmpty() -> return FactRecord.Refused("nothing was actually observed for \"$cleanKey\"")
            SafetyPolicy.isCredentialLabel(cleanKey) || SafetyPolicy.isCredentialLabel(cleanValue) ->
                return FactRecord.Refused("\"$cleanKey\" looks like a credential, and those are never kept")
        }
        expire()
        val fact = TaskFact(
            key = cleanKey,
            value = cleanValue.take(MAX_VALUE_CHARS),
            packageName = packageName.trim(),
            source = source,
            observedAtMs = clock(),
            screenRevision = screenRevision
        )
        stored.removeAll { it.key == cleanKey }
        stored.add(fact)
        while (stored.size > maxFacts) stored.removeAt(0)
        return FactRecord.Stored(fact)
    }

    /** The current answer for [key], or null when there is none or it has aged out. */
    fun fact(key: String, verifiedOnly: Boolean = false): TaskFact? {
        if (!isOpen) return null
        val cleanKey = normalize(key)
        val now = clock()
        return stored.lastOrNull {
            it.key == cleanKey && it.isFresh(factTtlMs, now) && (!verifiedOnly || it.verified)
        }
    }

    /** Everything still current, newest last. [verifiedOnly] drops what the app never confirmed. */
    fun facts(verifiedOnly: Boolean = false, fromApp: String? = null): List<TaskFact> {
        if (!isOpen) return emptyList()
        val now = clock()
        val wanted = fromApp?.let { normalize(it) }
        return stored.filter {
            it.isFresh(factTtlMs, now) &&
                (!verifiedOnly || it.verified) &&
                (wanted == null || normalize(it.packageName) == wanted)
        }
    }

    /**
     * The gate for spending a fact somewhere else. An unverified one is handed back for checking
     * rather than typed, since the only thing that ever vouched for it was a picture.
     */
    fun handoff(key: String, toPackageName: String): FactHandoff {
        val cleanKey = normalize(key)
        val found = fact(cleanKey)
            ?: return FactHandoff.Missing(cleanKey, "nothing current was recorded for \"$cleanKey\"")
        if (!found.verified) {
            return FactHandoff.NeedsRecheck(
                found,
                "${found.source.describe()}, so it should be checked before it is typed anywhere"
            )
        }
        return FactHandoff.Usable(found, crossedApps = normalize(found.packageName) != normalize(toPackageName))
    }

    /** A different task reading through this reference gets nothing. */
    fun viewFor(taskId: String): List<TaskFact> = if (this.taskId == taskId) facts() else emptyList()

    /** Forgets what has aged out. Returns how much went, so a caller can say it out loud. */
    fun expire(now: Long = clock()): Int {
        val stale = stored.filterNot { it.isFresh(factTtlMs, now) }
        if (stale.isEmpty()) return 0
        stored.removeAll(stale)
        return stale.size
    }

    /** Ends the task's memory. Called on every terminal outcome, including a stop. */
    fun close(reason: String = "the task ended") {
        closedReason = reason
        stored.clear()
    }

    companion object {
        /** Long enough for one multi-app task, short enough that yesterday's number stays yesterday's. */
        const val DEFAULT_TTL_MS = 300_000L
        const val DEFAULT_MAX_FACTS = 40
        private const val MAX_VALUE_CHARS = 160

        private fun normalize(raw: String): String = raw.trim().lowercase(Locale.ROOT)
    }
}
