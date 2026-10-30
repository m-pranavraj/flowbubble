package com.flowbubble.app.vision

import com.flowbubble.app.access.FlowAccessibilityService
import com.flowbubble.app.capture.ScreenCapture

object ScreenModelFactory {
    suspend fun build(service: FlowAccessibilityService, includePixels: Boolean): ScreenModel {
        val (screenWidth, screenHeight) = service.screenSize()
        val nodes = service.visibleNodes()
        val packageName = service.currentPackage() ?: "unknown"
        val passwordFocused = service.isPasswordFocused()

        var ocrUsed = false
        var ocrError: String? = null
        var captureError: String? = null
        var secureLikely = false
        var ocr = emptyList<OcrLine>()

        if (includePixels) {
            when {
                passwordFocused -> {
                    captureError = "A password field is focused, so screen capture was skipped."
                    secureLikely = true
                }
                !ScreenCapture.isActive -> {
                    captureError = ScreenCapture.lastError
                        ?: "Screen access isn't on for this session. Grant it to let FlowBubble read pixels."
                }
                else -> {
                    val frame = ScreenCapture.awaitFrame()
                    if (frame == null) {
                        captureError = ScreenCapture.lastError
                            ?: "No screen frame arrived. Try again once the screen changes."
                    } else {
                        try {
                            secureLikely = frame.blackLikely
                            val result = OcrEngine.recognize(frame.bitmap, frame.screenWidth, frame.screenHeight)
                            ocrUsed = true
                            ocr = result.lines
                            ocrError = result.error
                            if (frame.blackLikely && ocr.isEmpty()) {
                                captureError = "The captured screen is black. This app may block screen capture " +
                                    "(FLAG_SECURE). Accessibility text is still available."
                            }
                        } finally {
                            frame.bitmap.recycle()
                        }
                    }
                }
            }
        }

        return ScreenModel(
            packageName = packageName,
            capturedAt = System.currentTimeMillis(),
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            nodes = nodes,
            ocr = ocr,
            ocrUsed = ocrUsed,
            secureLikely = secureLikely,
            passwordFocused = passwordFocused,
            captureError = captureError,
            ocrError = ocrError
        )
    }
}
