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
        return runCatching {
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognized = process(image)
            val mapX = screenWidth.toFloat() / bitmap.width.coerceAtLeast(1)
            val mapY = screenHeight.toFloat() / bitmap.height.coerceAtLeast(1)
            val lines = ArrayList<OcrLine>()
            recognized.textBlocks.forEach { block ->
                block.lines.forEach { line ->
                    if (lines.size >= 180) return@forEach
                    val box = map(line.boundingBox, mapX, mapY) ?: return@forEach
                    val text = line.text.trim()
                    if (text.isEmpty()) return@forEach
                    val words = line.elements.mapNotNull { element ->
                        val wordText = element.text.trim()
                        val wordBox = map(element.boundingBox, mapX, mapY)
                        if (wordText.isEmpty() || wordBox == null) null else OcrWord(wordText, wordBox)
                    }
                    lines.add(OcrLine(text, box, words))
                }
            }
            Result(lines.sortedWith(compareBy({ it.box.top }, { it.box.left })))
        }.getOrElse { error ->
            Result(emptyList(), "On-device text recognition failed: ${error.message ?: "unknown error"}")
        }
    }

    private suspend fun process(image: InputImage): Text = suspendCancellableCoroutine { continuation ->
        recognizer.process(image)
            .addOnSuccessListener { text -> if (continuation.isActive) continuation.resume(text) }
            .addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
    }

    private fun map(rect: Rect?, mapX: Float, mapY: Float): ScreenBox? {
        if (rect == null || rect.width() <= 0 || rect.height() <= 0) return null
        return ScreenBox(
            left = (rect.left * mapX).toInt(),
            top = (rect.top * mapY).toInt(),
            right = (rect.right * mapX).toInt(),
            bottom = (rect.bottom * mapY).toInt()
        ).takeIf { it.isValid }
    }
}
