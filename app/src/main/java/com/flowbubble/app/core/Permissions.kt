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
import com.flowbubble.app.access.FlowAccessibilityService
import com.flowbubble.app.notifications.NotificationCenter

data class PermissionState(
    val microphone: Boolean,
    val overlay: Boolean,
    val accessibility: Boolean,
    val accessibilityBound: Boolean,
    val notifications: Boolean,
    val contacts: Boolean,
    val camera: Boolean,
    val dndAccess: Boolean,
    val batteryExempt: Boolean,
    val notificationAccess: Boolean
) {
    /**
     * "Ready" has to mean the bubble will actually act, and an enabled service is not that. Settings
     * writes the enabled list the instant a user flips the switch; Android binds it later, or never —
     * a service that crashed keeps its entry. Reporting ready over an unbound service was how the app
     * reached "Everything is ready" and then did nothing.
     */
    val coreReady: Boolean get() = microphone && overlay && accessibility && accessibilityBound

    /** Every switch the Setup screen shows, so the count and its denominator cannot drift apart. */
    private val switches: List<Boolean>
        get() = listOf(
            microphone, overlay, accessibility, notifications, contacts, camera,
            dndAccess, batteryExempt, notificationAccess
        )

    val enabledCount: Int get() = switches.count { it }
    val total: Int get() = switches.size
}

object Permissions {
    fun snapshot(context: Context): PermissionState = PermissionState(
        microphone = hasPermission(context, Manifest.permission.RECORD_AUDIO),
        overlay = canDrawOverlays(context),
        accessibility = isAccessibilityEnabled(context),
        accessibilityBound = FlowAccessibilityService.isConnected(),
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        contacts = hasPermission(context, Manifest.permission.READ_CONTACTS),
        camera = hasPermission(context, Manifest.permission.CAMERA),
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

    /*
     * Several of these screens are not guaranteed to exist. ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
     * is absent on MIUI/HyperOS and several other ROMs, so an unguarded startActivity crashed the app on
     * the Setup screen the moment a user tapped "Allow background use". The app's own detail page is
     * always present and is the closest thing that can be opened instead.
     */
    private fun launch(context: Context, intent: Intent): Boolean {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK
        if (runCatching { context.startActivity(intent.addFlags(flags)) }.isSuccess) return true
        val fallback = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
        return runCatching { context.startActivity(fallback.addFlags(flags)) }.isSuccess
    }
}
