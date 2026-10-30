package com.flowbubble.app.core

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val time: Long,
    val kind: String,
    val summary: String,
    val status: String
)

object HistoryStore {
    private const val FILE = "flowbubble_history"
    private const val KEY = "entries"
    private const val MAX = 100

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): List<HistoryEntry> {
        val raw = sp(context).getString(KEY, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        HistoryEntry(
                            time = item.optLong("time"),
                            kind = item.optString("kind"),
                            summary = item.optString("summary"),
                            status = item.optString("status")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, kind: String, summary: String, status: String) {
        val entry = HistoryEntry(System.currentTimeMillis(), kind, summary.take(180), status)
        val entries = (listOf(entry) + load(context)).take(MAX)
        val array = JSONArray()
        entries.forEach { item ->
            array.put(
                JSONObject()
                    .put("time", item.time)
                    .put("kind", item.kind)
                    .put("summary", item.summary)
                    .put("status", item.status)
            )
        }
        sp(context).edit { putString(KEY, array.toString()) }
    }

    fun clear(context: Context) = sp(context).edit { remove(KEY) }
}
