package com.flowbubble.app.capture

import android.content.Context
import android.graphics.Bitmap
import com.flowbubble.app.capture.ScreenCaptureService.Companion.stopCapture
import kotlinx.coroutines.delay
import java.util.concurrent.CopyOnWriteArraySet

object ScreenCapture {
    data class Frame(
        val bitmap: Bitmap,
        val capturedAt: Long,
        val screenWidth: Int,
        val screenHeight: Int,
        val blackLikely: Boolean
    )

    /**
     * Called for every streamed frame, on the capture thread, while the frame is still valid.
     *
     * The bitmap belongs to the capture pipeline and is recycled when the next frame lands, so a
     * listener has to finish with it inside this callback: sample it, or copy the part it needs.
     * A listener must stay quick and must never call back into ScreenCapture, which is holding a
     * lock while it dispatches. Frames are memory-only and are never written to disk or logged.
     */
    fun interface FrameListener {
        fun onFrame(frame: Frame)
    }

    @Volatile
    var isActive: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    private val lock = Any()
    private var latest: Frame? = null
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val frameListeners = CopyOnWriteArraySet<FrameListener>()

    fun requestConsent(context: Context) {
        val intent = android.content.Intent(context, CaptureConsentActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { setError("Couldn't open the screen access dialog.") }
    }

    fun stop(context: Context) = stopCapture(context)

    fun setActive(active: Boolean, error: String? = null) {
        isActive = active
        if (active) lastError = error else if (error != null) lastError = error
        if (!active) clearLatest()
        listeners.forEach { runCatching { it() } }
    }

    fun setError(message: String) {
        lastError = message
        isActive = false
        clearLatest()
        listeners.forEach { runCatching { it() } }
    }

    fun postFrame(bitmap: Bitmap, screenWidth: Int, screenHeight: Int, blackLikely: Boolean) {
        val frame = Frame(bitmap, System.currentTimeMillis(), screenWidth, screenHeight, blackLikely)
        synchronized(lock) {
            // The previous frame is only released here, and dispatch is single-threaded: every
            // listener finishes with a bitmap before the next one is allowed to recycle it.
            latest?.bitmap?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
            latest = frame
            frameListeners.forEach { listener ->
                runCatching { listener.onFrame(frame) }
            }
        }
    }

    suspend fun awaitFrame(timeoutMs: Long = 2200): Frame? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            synchronized(lock) {
                val frame = latest
                if (frame != null && !frame.bitmap.isRecycled) {
                    val copy = frame.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    if (copy != null) {
                        return Frame(copy, frame.capturedAt, frame.screenWidth, frame.screenHeight, frame.blackLikely)
                    }
                }
            }
            delay(45)
        }
        return null
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** Subscribes to the live frame stream. See [FrameListener] for the ownership rules. */
    fun addFrameListener(listener: FrameListener) {
        frameListeners.add(listener)
    }

    fun removeFrameListener(listener: FrameListener) {
        frameListeners.remove(listener)
    }

    fun clearError() {
        lastError = null
    }

    private fun clearLatest() {
        synchronized(lock) {
            latest?.bitmap?.takeIf { !it.isRecycled }?.recycle()
            latest = null
        }
    }
}
