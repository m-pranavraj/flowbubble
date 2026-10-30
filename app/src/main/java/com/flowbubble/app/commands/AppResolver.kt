package com.flowbubble.app.commands

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

data class AppMatch(val label: String, val packageName: String)

object AppResolver {

    /** Every launchable app on the device. Call off the main thread. */
    fun installed(context: Context): List<AppMatch> {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching { pm.queryIntentActivities(launcherIntent, 0) }.getOrDefault(emptyList())
        return resolved.asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .map {
                AppMatch(
                    label = runCatching { it.loadLabel(pm).toString() }.getOrDefault(it.activityInfo.packageName),
                    packageName = it.activityInfo.packageName
                )
            }
            .distinctBy { it.packageName }
            .toList()
    }

    fun find(context: Context, query: String, limit: Int = 5): List<AppMatch> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val apps = installed(context)
        val exact = apps.filter { it.label.lowercase() == q }
        val startsWith = apps.filter { it.label.lowercase().startsWith(q) }
        val contains = apps.filter { it.label.lowercase().contains(q) }
        val literal = (exact + startsWith + contains)
            .distinctBy { it.packageName }
        if (literal.isNotEmpty()) return literal.take(limit)
        return aliasMatches(apps, q).take(limit)
    }

    /** Nickname lookup ("fb", "mobile match") used only when nothing matched the spoken name. */
    private fun aliasMatches(apps: List<AppMatch>, query: String): List<AppMatch> {
        val keywords = AppAliases.keywordsFor(query)
        if (keywords.isEmpty()) return emptyList()
        return keywords.flatMap { keyword ->
            val key = keyword.lowercase()
            apps.filter { it.label.lowercase() == key } + apps.filter { it.label.lowercase().contains(key) }
        }.distinctBy { it.packageName }
    }

    fun openStore(context: Context, packageName: String): Boolean {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(market) }.isSuccess) return true
        val web = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(web) }.isSuccess
    }

    fun launch(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)
}
