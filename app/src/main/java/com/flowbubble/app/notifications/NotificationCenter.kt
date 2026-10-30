package com.flowbubble.app.notifications

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

data class RecentNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val ongoing: Boolean
) {
    fun line(): String {
        val head = when {
            title.isBlank() -> appLabel
            title.equals(appLabel, ignoreCase = true) -> appLabel
            else -> "$appLabel \u00B7 $title"
        }
        val body = text.trim().replace(Regex("\\s+"), " ")
        val trimmed = if (body.length > 160) body.take(157) + "\u2026" else body
        return if (trimmed.isBlank()) head else "$head\n$trimmed"
    }
}

object NotificationCenter {
    private const val MAX = 40
    private val items = ArrayDeque<RecentNotification>()

    @Volatile
    private var connected = false

    @Synchronized
    fun setConnected(value: Boolean) {
        connected = value
    }

    @Synchronized
    fun isConnected(): Boolean = connected

    @Synchronized
    fun record(notification: RecentNotification) {
        items.removeAll { it.key == notification.key }
        items.addFirst(notification)
        while (items.size > MAX) items.removeLast()
    }

    @Synchronized
    fun remove(key: String) {
        items.removeAll { it.key == key }
    }

    @Synchronized
    fun recent(limit: Int = 12): List<RecentNotification> = items.take(limit)

    @Synchronized
    fun clear() = items.clear()

    fun digest(entries: List<RecentNotification>, limit: Int = 10): String {
        val seen = HashSet<String>()
        val lines = ArrayList<String>()
        entries.sortedByDescending { it.postedAt }.forEach { entry ->
            val marker = "${entry.packageName}|${entry.title}|${entry.text}".lowercase()
            if (!seen.add(marker)) return@forEach
            lines.add(entry.line())
            if (lines.size >= limit) return@forEach
        }
        return lines.joinToString("\n\n")
    }

    fun isAccessGranted(context: Context): Boolean = runCatching {
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
    }.getOrDefault(false)

    fun openSettings(context: Context) {
        val detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        ComponentName(context, FlowNotificationService::class.java)
                    )
            }.getOrNull()
        } else {
            null
        }
        val intent = detail ?: Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
