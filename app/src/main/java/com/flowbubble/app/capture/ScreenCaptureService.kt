package com.flowbubble.app.capture

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.flowbubble.app.core.Notifications
import kotlin.math.roundToInt

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.flowbubble.app.capture.START"
        const val ACTION_STOP = "com.flowbubble.app.capture.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val MAX_DIMENSION = 1920f
        private const val FRAME_INTERVAL_MS = 170L

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopCapture(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var lastFrameAt = 0L
    private var closing = false
    private var foregroundStarted = false
    private var screenWidth = 0
    private var screenHeight = 0
    private var captureWidth = 0
    private var captureHeight = 0
    private var captureDensity = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (closing) return
            ScreenCapture.setError("Screen access was stopped. Grant it again to continue.")
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureCaptureChannel(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                startSession(code, data)
            }
        }
        return START_NOT_STICKY
    }

    private fun startSession(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            ScreenCapture.setError("Screen access was not granted.")
            stopSelf()
            return
        }

        val notification = Notifications.buildCapture(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        val foreground = runCatching {
            ServiceCompat.startForeground(this, Notifications.CAPTURE_ID, notification, type)
        }.isSuccess
        if (!foreground) {
            ScreenCapture.setError("Android refused the screen capture service.")
            stopSelf()
            return
        }
        foregroundStarted = true

        releaseSession(keepWorker = true)
        ScreenCapture.setActive(false)
        closing = false
        ScreenCapture.clearError()
        startWorker()

        val manager = getSystemService(MediaProjectionManager::class.java)
        val newProjection = runCatching { manager?.getMediaProjection(resultCode, data) }.getOrNull()
        if (newProjection == null) {
            ScreenCapture.setError("Android did not provide a screen capture session.")
            stopSelf()
            return
        }
        projection = newProjection
        newProjection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        val created = runCatching { createDisplay(newProjection) }
            .onFailure { error ->
                ScreenCapture.setError("Screen capture couldn't start: ${error.message ?: "unknown error"}")
            }
            .isSuccess
        if (!created) stopSelf()
    }

    private fun startWorker() {
        if (worker != null) return
        val thread = HandlerThread("FlowBubbleScreen").also { it.start() }
        worker = thread
        workerHandler = Handler(thread.looper)
    }

    private fun createDisplay(mediaProjection: MediaProjection) {
        measureScreen()
        calculateCaptureSize()
        val reader = createReader(captureWidth, captureHeight)
        imageReader = reader
        val display = mediaProjection.createVirtualDisplay(
            "Your Bubble screen",
            captureWidth,
            captureHeight,
            captureDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            workerHandler
        )
        if (display == null) {
            reader.close()
            imageReader = null
            throw IllegalStateException("Android returned no virtual display")
        }
        virtualDisplay = display
        ScreenCapture.setActive(true)
    }

    private fun createReader(width: Int, height: Int): ImageReader {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.uptimeMillis()
                if (now - lastFrameAt < FRAME_INTERVAL_MS) return@setOnImageAvailableListener
                lastFrameAt = now
                val bitmap = runCatching { imageToBitmap(image) }.getOrNull() ?: return@setOnImageAvailableListener
                val black = isMostlyBlack(bitmap)
                ScreenCapture.postFrame(bitmap, screenWidth, screenHeight, black)
            } finally {
                image.close()
            }
        }, workerHandler)
        return reader
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        buffer.rewind()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0) return null
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + if (rowPadding > 0) rowPadding / pixelStride else 0
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        if (paddedWidth == image.width) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    private fun isMostlyBlack(bitmap: Bitmap): Boolean {
        if (bitmap.width < 4 || bitmap.height < 4) return false
        var dark = 0
        var total = 0
        val stepX = maxOf(1, bitmap.width / 36)
        val stepY = maxOf(1, bitmap.height / 60)
        var y = stepY / 2
        while (y < bitmap.height) {
            var x = stepX / 2
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                if (r < 14 && g < 14 && b < 14) dark++
                total++
                x += stepX
            }
            y += stepY
        }
        return total > 0 && dark.toFloat() / total > 0.985f
    }

    private fun measureScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = getSystemService(WindowManager::class.java)
            val bounds = manager?.maximumWindowMetrics?.bounds
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                screenWidth = bounds.width()
                screenHeight = bounds.height()
                return
            }
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = getSystemService(WindowManager::class.java)?.defaultDisplay
        @Suppress("DEPRECATION")
        display?.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        screenHeight = metrics.heightPixels.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
    }

    private fun calculateCaptureSize() {
        val longest = maxOf(screenWidth, screenHeight).coerceAtLeast(1)
        val scale = minOf(1f, MAX_DIMENSION / longest)
        captureWidth = (screenWidth * scale).roundToInt().coerceAtLeast(1)
        captureHeight = (screenHeight * scale).roundToInt().coerceAtLeast(1)
        captureDensity = (resources.displayMetrics.densityDpi * scale).roundToInt().coerceAtLeast(1)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val display = virtualDisplay ?: return
        runCatching {
            measureScreen()
            calculateCaptureSize()
            val oldReader = imageReader
            val newReader = createReader(captureWidth, captureHeight)
            display.surface = newReader.surface
            display.resize(captureWidth, captureHeight, captureDensity)
            imageReader = newReader
            oldReader?.setOnImageAvailableListener(null, null)
            oldReader?.close()
        }.onFailure {
            ScreenCapture.setError("Screen access stopped after a display change. Grant it again.")
            stopSelf()
        }
    }

    override fun onDestroy() {
        releaseSession(keepWorker = false)
        ScreenCapture.setActive(false)
        if (foregroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        super.onDestroy()
    }

    private fun releaseSession(keepWorker: Boolean) {
        closing = true
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        val activeProjection = projection
        projection = null
        if (activeProjection != null) {
            runCatching { activeProjection.unregisterCallback(projectionCallback) }
            runCatching { activeProjection.stop() }
        }
        if (!keepWorker) {
            worker?.quitSafely()
            worker = null
            workerHandler = null
        }
        lastFrameAt = 0L
    }
}
