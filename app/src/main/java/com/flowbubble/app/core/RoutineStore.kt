package com.flowbubble.app.core

import android.content.Context
import androidx.core.content.edit
import java.net.URLEncoder

/**
 * Saved voice phrases under a name. Stored in app-private preferences; every
 * routine replays through CommandParser and the normal safety gates.
 */
object RoutineStore {
    private const val FILE = "flowbubble_routines"
    private const val KEY_PREFIX = "r:"
    private const val SEPARATOR = "\u001F"
    private const val MAX_ROUTINES = 20
    private const val MAX_NAME = 40
    private const val MAX_PHRASE = 300

    data class Routine(val name: String, val phrase: String)

    sealed interface SaveResult {
        data class Saved(val routine: Routine) : SaveResult
        data class Rejected(val reason: String) : SaveResult
    }

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun list(context: Context): List<Routine> =
        sp(context).all.mapNotNull { (key, value) ->
            if (!key.startsWith(KEY_PREFIX)) return@mapNotNull null
            val raw = value as? String ?: return@mapNotNull null
            val parts = raw.split(SEPARATOR, limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val name = parts[0]
            val phrase = parts[1]
            if (name.isBlank() || phrase.isBlank()) null else Routine(name, phrase)
        }.sortedBy { it.name.lowercase() }

    fun find(context: Context, query: String): Routine? {
        val needle = query.trim().lowercase()
        if (needle.isBlank()) return null
        val all = list(context)
        return all.firstOrNull { it.name.lowercase() == needle }
            ?: all.filter { it.name.lowercase().contains(needle) }.takeIf { it.size == 1 }?.first()
    }

    fun save(context: Context, rawName: String, rawPhrase: String): SaveResult {
        val name = rawName.trim().trimEnd('.', '!', '?', ',').take(MAX_NAME).trim()
        val phrase = rawPhrase.trim().take(MAX_PHRASE).trim()
        if (name.isBlank()) return SaveResult.Rejected("The routine needs a name.")
        if (phrase.isBlank()) return SaveResult.Rejected("The routine needs a command to replay.")
        val existing = list(context)
        val duplicate = existing.firstOrNull { it.name.lowercase() == name.lowercase() }
        if (duplicate == null && existing.size >= MAX_ROUTINES) {
            return SaveResult.Rejected("You already have $MAX_ROUTINES routines. Delete one before saving another.")
        }
        val key = KEY_PREFIX + URLEncoder.encode(name.lowercase(), "UTF-8")
        sp(context).edit { putString(key, name + SEPARATOR + phrase) }
        return SaveResult.Saved(Routine(name, phrase))
    }

    fun delete(context: Context, name: String): Boolean {
        val routine = find(context, name) ?: return false
        val key = KEY_PREFIX + URLEncoder.encode(routine.name.lowercase(), "UTF-8")
        sp(context).edit { remove(key) }
        return true
    }
}
