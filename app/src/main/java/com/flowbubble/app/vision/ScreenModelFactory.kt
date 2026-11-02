package com.flowbubble.app.vision

import com.flowbubble.app.access.FlowAccessibilityService
import com.flowbubble.app.capture.ScreenCapture

object ScreenModelFactory {
    suspend fun build(service: FlowAccessibilityService, includePixels: Boolean): ScreenModel {
        val tree = readTree(service)
        var ocrUsed = false
        var ocrError: String? = null
        var captureError: String? = null
        var secureLikely = false
        var ocr = emptyList<OcrLine>()

        if (includePixels) {
            when {
                tree.passwordFocused -> {
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

        return assemble(tree, ocr, ocrUsed, secureLikely, captureError, ocrError)
    }

    /**
     * The accessibility half of a model, on its own, so a live session can diff the tree before it
     * spends anything on pixels.
     */
    suspend fun readTree(service: FlowAccessibilityService): TreeRead {
        val (screenWidth, screenHeight) = service.screenSize()
        return TreeRead(
            packageName = service.currentPackage() ?: "unknown",
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            nodes = service.visibleNodes(),
            passwordFocused = service.isPasswordFocused()
        )
    }

    fun assemble(
        tree: TreeRead,
        ocr: List<OcrLine>,
        ocrUsed: Boolean,
        secureLikely: Boolean,
        captureError: String? = null,
        ocrError: String? = null
    ): ScreenModel = ScreenModel(
        packageName = tree.packageName,
        capturedAt = System.currentTimeMillis(),
        screenWidth = tree.screenWidth,
        screenHeight = tree.screenHeight,
        nodes = tree.nodes,
        ocr = ocr,
        ocrUsed = ocrUsed,
        secureLikely = secureLikely,
        passwordFocused = tree.passwordFocused,
        captureError = captureError,
        ocrError = ocrError
    )

    /** What the accessibility tree alone can say about the screen right now. */
    data class TreeRead(
        val packageName: String,
        val screenWidth: Int,
        val screenHeight: Int,
        val nodes: List<ScreenNode>,
        val passwordFocused: Boolean
    ) {
        /** A cheap fingerprint of the tree, used to decide whether anything is worth re-reading. */
        fun textHash(): Int = ScreenFusion.treeHash(nodes)
    }
}
