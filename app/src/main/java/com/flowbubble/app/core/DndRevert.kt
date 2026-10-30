package com.flowbubble.app.core

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object DndRevert {
    private const val REQUEST = 41

    fun schedule(context: Context, minutes: Int) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        Prefs.setDndRevertAt(context, at)
        arm(context, at)
    }

    fun cancel(context: Context) {
        Prefs.setDndRevertAt(context, 0L)
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context, PendingIntent.FLAG_NO_CREATE)
        if (pending != null) {
            manager.cancel(pending)
            pending.cancel()
        }
    }

    fun restore(context: Context) {
        val at = Prefs.dndRevertAt(context)
        if (at <= 0L) return
        if (at <= System.currentTimeMillis()) revertNow(context) else arm(context, at)
    }

    fun revertNow(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.isNotificationPolicyAccessGranted) {
            Prefs.setDndRevertAt(context, 0L)
            return false
        }
        runCatching { manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }
        val reverted = manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
        if (reverted) Prefs.setDndRevertAt(context, 0L)
        return reverted
    }

    private fun arm(context: Context, at: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        runCatching { manager.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, pending) }
    }

    private fun pendingIntent(context: Context, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        REQUEST,
        Intent(context, DndRevertReceiver::class.java),
        flags or PendingIntent.FLAG_IMMUTABLE
    )
}
