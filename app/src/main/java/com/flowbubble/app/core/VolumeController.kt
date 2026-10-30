package com.flowbubble.app.core

import android.content.Context
import android.media.AudioManager

/**
 * Media-stream volume control. Every result reports the level Android actually
 * reports after the change so callers can verify instead of assuming.
 */
object VolumeController {
    data class Result(
        val before: Int,
        val after: Int,
        val max: Int,
        val detail: String? = null
    ) {
        val changed: Boolean get() = before != after
        val verified: Boolean get() = detail == null
    }

    private const val STREAM = AudioManager.STREAM_MUSIC

    fun raise(context: Context, steps: Int): Result? =
        change(context, steps.coerceIn(1, 10), mute = null)

    fun lower(context: Context, steps: Int): Result? =
        change(context, -steps.coerceIn(1, 10), mute = null)

    fun mute(context: Context): Result? = change(context, 0, mute = true)

    fun unmute(context: Context): Result? = change(context, 0, mute = false)

    private fun change(context: Context, steps: Int, mute: Boolean?): Result? {
        val audio = context.getSystemService(AudioManager::class.java) ?: return null
        val max = runCatching { audio.getStreamMaxVolume(STREAM) }.getOrDefault(15).coerceAtLeast(1)
        val before = runCatching { audio.getStreamVolume(STREAM) }.getOrDefault(0)
        val action = runCatching {
            when (mute) {
                true -> audio.setStreamVolume(STREAM, 0, 0)
                false -> audio.setStreamVolume(STREAM, maxOf(1, max / 3), 0)
                null -> repeat(kotlin.math.abs(steps)) {
                    audio.adjustStreamVolume(
                        STREAM,
                        if (steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                        0
                    )
                }
            }
        }
        action.exceptionOrNull()?.let { error ->
            return Result(
                before,
                before,
                max,
                error.message ?: "Android didn't allow the volume change."
            )
        }
        val after = runCatching { audio.getStreamVolume(STREAM) }.getOrDefault(before)
        return Result(before, after, max)
    }
}
