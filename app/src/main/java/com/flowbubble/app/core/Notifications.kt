package com.flowbubble.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.flowbubble.app.MainActivity
import com.flowbubble.app.R
import com.flowbubble.app.service.OverlayService

object Notifications {
    const val CHANNEL_ID = "flowbubble_session"
    const val ID = 101
    const val CAPTURE_CHANNEL_ID = "flowbubble_capture"
    const val CAPTURE_ID = 102

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        manager.createNotificationChannel(channel)
    }

    fun ensureCaptureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CAPTURE_CHANNEL_ID,
            context.getString(R.string.notification_capture_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_capture_channel_description)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        manager.createNotificationChannel(channel)
    }

    fun buildCapture(context: Context): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            context,
            2,
            Intent(context, com.flowbubble.app.capture.ScreenCaptureService::class.java)
                .setAction(com.flowbubble.app.capture.ScreenCaptureService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CAPTURE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(R.string.notification_capture_title))
            .setContentText(context.getString(R.string.notification_capture_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notification_capture_text)))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(open)
            .addAction(R.drawable.ic_mic, context.getString(R.string.notification_stop), stop)
            .build()
    }

    fun build(context: Context, text: String): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            context,
            1,
            Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(open)
            .addAction(R.drawable.ic_mic, context.getString(R.string.notification_stop), stop)
            .build()
    }
}
