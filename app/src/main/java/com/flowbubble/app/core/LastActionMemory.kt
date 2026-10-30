package com.flowbubble.app.core

import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory context for follow-up commands. Nothing here touches disk: it exists
 * only so phrases like "make it shorter" or "undo that" can act on what just happened.
 */
object LastActionMemory {
    data class TextEdit(
        val previous: String,
        val current: String,
        val fieldLabel: String?,
        val prefix: String,
        val oldSegment: String,
        val newSegment: String,
        val suffix: String,
        val seq: Long
    )

    data class LastOutput(val text: String, val seq: Long)

    private val sequence = AtomicLong()

    @Volatile
    private var editValue: TextEdit? = null

    @Volatile
    private var outputValue: LastOutput? = null

    @Volatile
    var raw: String? = null
        private set

    val edit: TextEdit? get() = editValue

    val output: LastOutput? get() = outputValue

    fun rememberEdit(previous: String, current: String, fieldLabel: String?) {
        if (current.isBlank()) return
        val prefixLength = commonPrefixLength(previous, current)
        val suffixLength = commonSuffixLength(previous, current, prefixLength)
        val oldEnd = (previous.length - suffixLength).coerceAtLeast(prefixLength)
        val newEnd = (current.length - suffixLength).coerceAtLeast(prefixLength)
        editValue = TextEdit(
            previous = previous,
            current = current,
            fieldLabel = fieldLabel,
            prefix = current.substring(0, prefixLength),
            oldSegment = previous.substring(prefixLength, oldEnd),
            newSegment = current.substring(prefixLength, newEnd),
            suffix = current.substring(newEnd),
            seq = sequence.incrementAndGet()
        )
    }

    fun rememberOutput(text: String) {
        if (text.isBlank()) return
        outputValue = LastOutput(text.take(8000), sequence.incrementAndGet())
    }

    fun rememberRaw(text: String) {
        if (text.isBlank()) return
        raw = text.take(300)
    }

    fun forgetEdit() {
        editValue = null
    }

    fun latestText(): Pair<String, Boolean>? {
        val currentEdit = editValue
        val currentOutput = outputValue
        return when {
            currentEdit == null && currentOutput == null -> null
            currentEdit != null && (currentOutput == null || currentEdit.seq >= currentOutput.seq) ->
                followUpText(currentEdit) to true
            currentOutput != null -> currentOutput.text to false
            else -> null
        }
    }

    fun followUpText(edit: TextEdit): String =
        if (edit.newSegment.any { it.isLetterOrDigit() }) edit.newSegment else edit.current

    fun clear() {
        editValue = null
        outputValue = null
        raw = null
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val max = minOf(a.length, b.length)
        var index = 0
        while (index < max && a[index] == b[index]) index++
        return index
    }

    private fun commonSuffixLength(a: String, b: String, prefixLength: Int): Int {
        val max = minOf(a.length, b.length) - prefixLength
        var length = 0
        while (length < max && a[a.length - 1 - length] == b[b.length - 1 - length]) length++
        return length
    }
}
