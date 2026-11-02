package com.flowbubble.app.voice

import android.content.Context
import android.os.SystemClock
import com.flowbubble.app.commands.AppResolver
import com.flowbubble.app.core.RoutineStore

/**
 * The names Your Bubble can actually act on. Rescoring a mishearing needs something to measure it
 * against, and an installed-apps lookup on every utterance is the expensive part, so it is cached.
 */
class VocabularyProvider(private val context: Context) {

    private var apps: List<String> = emptyList()
    private var fetchedAt = 0L

    fun appNames(force: Boolean = false): List<String> = cachedApps(force)

    fun routineNames(): List<String> = RoutineStore.list(context).map { it.name }

    fun names(force: Boolean = false): List<String> = (cachedApps(force) + routineNames()).distinct()

    fun invalidate() {
        fetchedAt = 0L
    }

    @Synchronized
    private fun cachedApps(force: Boolean): List<String> {
        val now = SystemClock.elapsedRealtime()
        if (force || apps.isEmpty() || now - fetchedAt > CACHE_MS) {
            apps = AppResolver.installed(context).map { it.label }.distinct()
            fetchedAt = now
        }
        return apps
    }

    private companion object {
        const val CACHE_MS = 60_000L
    }
}
