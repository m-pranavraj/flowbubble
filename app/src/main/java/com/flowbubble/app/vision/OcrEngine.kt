package com.flowbubble.app.vision

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object OcrEngine {
    data class Result(val lines: List<OcrLine>, val error: String? = null)

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognize(bitmap: Bitmap, screenWidth: Int, screenHeight: Int): Result {
        if (bitmap.isRecycled) return Result(emptyList(), "The captured frame was already released.")
        return recognizeIn(bitmap, 0, 0, bitmap.width, bitmap.height, screenWidth, screenHeight)
    }

    /**
     * Reads only part of a frame. [region] is in screen pixels, the same space every box in a
     * [ScreenModel] uses, and the lines come back in that space too.
     *
     * This is what makes a live session affordable: a scroll moves one band of the screen, and
     * running the recognizer over that band instead of the whole frame is the difference between
     * keeping up and falling behind.
     */
    suspend fun recognizeRegion(
        bitmap: Bitmap,
        screenWidth: Int,
        screenHeight: Int,
        region: ScreenBox
    ): Result {
        if (bitmap.isRecycled) return Result(emptyList(), "The captured frame was already released.")
        val mapX = screenWidth.toFloat() / bitmap.width.coerceAtLeast(1)
        val mapY = screenHeight.toFloat() / bitmap.height.coerceAtLeast(1)
        if (mapX <= 0f || mapY <= 0f) return recognize(bitmap, screenWidth, screenHeight)
        val left = (region.left / mapX).toInt().coerceIn(0, bitmap.width - 1)
        val top = (region.top / mapY).toInt().coerceIn(0, bitmap.height - 1)
        val right = (region.right / mapX).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (region.bottom / mapY).toInt().coerceIn(top + 1, bitmap.height)
        if (right - left >= bitmap.width && bottom - top >= bitmap.height) {
            return recognize(bitmap, screenWidth, screenHeight)
        }
        val crop = runCatching { Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top) }
            .getOrNull() ?: return recognize(bitmap, screenWidth, screenHeight)
        return try {
            recognizeIn(crop, left, top, crop.width, crop.height, screenWidth, screenHeight)
        } finally {
            if (!crop.isRecycled) crop.recycle()
        }
    }

    /**
     * The whole recognizer pass. [originX]/[originY] say where inside the full frame this bitmap
     * came from, so a cropped region's boxes still land in the screen's own coordinates.
     */
    private suspend fun recognizeIn(
        bitmap: Bitmap,
        originX: Int,
        originY: Int,
        cropWidth: Int,
        cropHeight: Int,
        screenWidth: Int,
        screenHeight: Int
    ): Result = runCatching {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognized = process(image)
        val mapX = screenWidth.toFloat() / cropWidth.coerceAtLeast(1)
        val mapY = screenHeight.toFloat() / cropHeight.coerceAtLeast(1)
        val lines = ArrayList<OcrLine>()
        recognized.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                if (lines.size >= 180) return@forEach
                val box = map(line.boundingBox, mapX, mapY, originX, originY) ?: return@forEach
                val text = line.text.trim()
                if (text.isEmpty()) return@forEach
                val words = line.elements.mapNotNull { element ->
                    val wordText = element.text.trim()
                    val wordBox = map(element.boundingBox, mapX, mapY, originX, originY)
                    if (wordText.isEmpty() || wordBox == null) null else OcrWord(wordText, wordBox)
                }
                lines.add(OcrLine(text, box, words))
            }
        }
        Result(lines.sortedWith(compareBy({ it.box.top }, { it.box.left })))
    }.getOrElse { error ->
        Result(emptyList(), "On-device text recognition failed: ${error.message ?: "unknown error"}")
    }

    private suspend fun process(image: InputImage): Text = suspendCancellableCoroutine { continuation ->
        recognizer.process(image)
            .addOnSuccessListener { text -> if (continuation.isActive) continuation.resume(text) }
            .addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
    }

    private fun map(
        rect: Rect?,
        mapX: Float,
        mapY: Float,
        originX: Int = 0,
        originY: Int = 0
    ): ScreenBox? {
        if (rect == null || rect.width() <= 0 || rect.height() <= 0) return null
        return ScreenBox(
            left = ((rect.left + originX) * mapX).toInt(),
            top = ((rect.top + originY) * mapY).toInt(),
            right = ((rect.right + originX) * mapX).toInt(),
            bottom = ((rect.bottom + originY) * mapY).toInt()
        ).takeIf { it.isValid }
    }
}
