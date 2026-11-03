package com.flowbubble.app.overlay.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import com.flowbubble.app.R

/**
 * The rendered face the companion is wearing. Each one is a still of the same character, drawn by
 * [CompanionLook.spritePose] from the state and expression the controller is in.
 */
enum class SpritePose { Idle, Listening, Thinking, Happy, Worried, Sleepy, Surprised }

/**
 * Supplies the 3D character art behind the bubble.
 *
 * The frames live in `drawable-nodpi` at 384 px and are decoded once, kept for the life of the
 * service, and never touched again: [bitmapFor] must be safe to call from every `onDraw`, so it does
 * no disk work after the first hit per pose. The first bubble is the only frame that can pay a
 * decode, and [prewarm] takes even that off the draw path by decoding the rest on one background
 * thread and handing the bitmaps back on the main thread — which is also why no lock is needed here.
 *
 * A pose that fails to decode returns null, and the caller falls back to the vector character in
 * [CompanionRenderer], so a broken asset costs looks and never costs the bubble.
 */
class CompanionSprites(context: Context) {

    private val resources = context.applicationContext.resources
    private val main = Handler(Looper.getMainLooper())
    private val cache = HashMap<SpritePose, Bitmap>()
    private var warming = false

    fun bitmapFor(pose: SpritePose): Bitmap? {
        cache[pose]?.let { return it }
        return decode(pose)?.also { cache[pose] = it }
    }

    /** Decodes every pose that is not already in the cache, off the main thread. */
    fun prewarm() {
        if (warming) return
        warming = true
        val missing = SpritePose.values().filter { cache[it] == null }
        if (missing.isEmpty()) {
            warming = false
            return
        }
        Thread {
            val decoded = missing.mapNotNull { pose -> decode(pose)?.let { pose to it } }
            main.post {
                decoded.forEach { (pose, bitmap) -> cache.putIfAbsent(pose, bitmap) }
                warming = false
            }
        }.apply { name = "companion-sprites"; isDaemon = true }.start()
    }

    private fun decode(pose: SpritePose): Bitmap? = runCatching {
        BitmapFactory.decodeResource(
            resources,
            resId(pose),
            BitmapFactory.Options().apply { inScaled = false }
        )
    }.getOrNull()

    private fun resId(pose: SpritePose): Int = when (pose) {
        SpritePose.Idle -> R.drawable.companion_idle
        SpritePose.Listening -> R.drawable.companion_listening
        SpritePose.Thinking -> R.drawable.companion_thinking
        SpritePose.Happy -> R.drawable.companion_happy
        SpritePose.Worried -> R.drawable.companion_worried
        SpritePose.Sleepy -> R.drawable.companion_sleepy
        SpritePose.Surprised -> R.drawable.companion_surprised
    }
}
