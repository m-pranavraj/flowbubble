package com.flowbubble.app.core

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.flowbubble.app.notifications.NotificationCenter

data class PermissionState(
    val microphone: Boolean,
    val overlay: Boolean,
    val accessibility: Boolean,
    val notifications: Boolean,
    val contacts: Boolean,
    val dndAccess: Boolean,
    val batteryExempt: Boolean,
    val notificationAccess: Boolean
) {
    val coreReady: Boolean get() = microphone && overlay && accessibility

    val enabledCount: Int
        get() = listOf(
            microphone, overlay, accessibility, notifications, contacts,
            dndAccess, batteryExempt, notificationAccess
        ).count { it }
}

object Permissions {
    fun snapshot(context: Context): PermissionState = PermissionState(
        microphone = hasPermission(context, Manifest.permission.RECORD_AUDIO),
        overlay = canDrawOverlays(context),
        accessibility = isAccessibilityEnabled(context),
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        contacts = hasPermission(context, Manifest.permission.READ_CONTACTS),
        dndAccess = context.getSystemService(NotificationManager::class.java)
            ?.isNotificationPolicyAccessGranted == true,
        batteryExempt = context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true,
        notificationAccess = NotificationCenter.isAccessGranted(context)
    )

    fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isAccessibilityEnabled(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo?.serviceInfo?.packageName == context.packageName }
    }

    fun openOverlaySettings(context: Context) = launch(
        context,
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
    )

    fun openAccessibilitySettings(context: Context) =
        launch(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun openNotificationSettings(context: Context) = launch(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    )

    fun openDndSettings(context: Context) =
        launch(context, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))

    fun openNotificationListenerSettings(context: Context) =
        launch(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    fun openBatterySettings(context: Context) = launch(
        context,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
    )

    fun openAppSettings(context: Context) = launch(
        context,
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
    )

    fun openSystemSettings(context: Context) = launch(context, Intent(Settings.ACTION_SETTINGS))

    private fun launch(context: Context, intent: Intent) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
