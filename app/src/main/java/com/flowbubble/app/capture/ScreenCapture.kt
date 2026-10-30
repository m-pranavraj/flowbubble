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

    @Volatile
    var isActive: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    private val lock = Any()
    private var latest: Frame? = null
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

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
        synchronized(lock) {
            latest?.bitmap?.takeIf { !it.isRecycled }?.recycle()
            latest = Frame(bitmap, System.currentTimeMillis(), screenWidth, screenHeight, blackLikely)
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
