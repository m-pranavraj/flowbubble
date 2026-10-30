package com.flowbubble.app.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class FlowNotificationService : NotificationListenerService() {

    companion object {
        private const val MAX_DISMISS = 40

        @Volatile
        var instance: FlowNotificationService? = null
            private set
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        NotificationCenter.setConnected(true)
    }

    override fun onListenerDisconnected() {
        instance = null
        NotificationCenter.setConnected(false)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        instance = null
        NotificationCenter.setConnected(false)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val posted = sbn ?: return
        if (posted.packageName == packageName) return
        val notification = posted.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val body = (if (big.isNotEmpty()) big else text)
            .replace(Regex("\\s+"), " ")
            .trim()
        if (title.isEmpty() && body.isEmpty()) return

        NotificationCenter.record(
            RecentNotification(
                key = posted.key,
                packageName = posted.packageName,
                appLabel = appLabel(posted.packageName),
                title = title,
                text = body,
                postedAt = posted.postTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
                ongoing = posted.isOngoing
            )
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.key?.let(NotificationCenter::remove)
    }

    fun dismissRecorded(): Int {
        var dismissed = 0
        NotificationCenter.recent(MAX_DISMISS).forEach { entry ->
            if (!entry.ongoing) {
                val done = runCatching { cancelNotification(entry.key) }.isSuccess
                if (done) dismissed++
            }
        }
        NotificationCenter.clear()
        return dismissed
    }

    private fun appLabel(pkg: String): String = runCatching {
        val manager = packageManager
        manager.getApplicationLabel(manager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
