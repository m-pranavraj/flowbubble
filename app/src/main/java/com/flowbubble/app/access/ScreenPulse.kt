package com.flowbubble.app.access

import java.util.concurrent.atomic.AtomicLong

/**
 * A monotonic count of accessibility changes, shared between the service and anything waiting for
 * a screen to react. Waiters compare [count] between polls instead of sleeping for a fixed
 * duration, so a fast screen is not waited on longer than it needs and a slow one is not cut short.
 *
 * Only a counter is exposed on purpose. It cannot say whether the screen is "quiet right now":
 * stillness that happened before a caller started watching tells it nothing about the next frame,
 * and waiters that trusted it resumed against half-built screens. Each waiter times stillness from
 * the moment it starts observing.
 */
object ScreenPulse {
    private val beats = AtomicLong(0L)

    /** Increases once per reported change. Cheap enough to read in a tight loop. */
    fun count(): Long = beats.get()

    fun beat() {
        beats.incrementAndGet()
    }

    /** Returns the pulse to its startup state. Only tests need this; chains must never reset it. */
    fun reset() {
        beats.set(0L)
    }
}
