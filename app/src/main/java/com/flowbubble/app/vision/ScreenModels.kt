package com.flowbubble.app.vision

import java.util.Locale

data class ScreenBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Int get() = width * height
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
    val isValid: Boolean get() = width > 0 && height > 0

    fun intersects(other: ScreenBox): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun containsPoint(x: Int, y: Int): Boolean =
        x in left until right && y in top until bottom

    fun overlapRatio(other: ScreenBox): Float {
        if (!intersects(other)) return 0f
        val overlapWidth = minOf(right, other.right) - maxOf(left, other.left)
        val overlapHeight = minOf(bottom, other.bottom) - maxOf(top, other.top)
        val overlap = overlapWidth.toLong() * overlapHeight.toLong()
        val smaller = minOf(area.toLong(), other.area.toLong()).coerceAtLeast(1L)
        return (overlap.toFloat() / smaller).coerceIn(0f, 1f)
    }

    fun expanded(dx: Int, dy: Int): ScreenBox =
        ScreenBox(left - dx, top - dy, right + dx, bottom + dy)
}

data class ScreenNode(
    val label: String,
    val box: ScreenBox,
    val clickable: Boolean,
    val editable: Boolean,
    val password: Boolean,
    val scrollable: Boolean,
    val packageName: String?,
    /** The node the app holds its cursor in. Defaulted so producers that predate it stay valid. */
    val focused: Boolean = false
)

data class OcrWord(
    val text: String,
    val box: ScreenBox
)

data class OcrLine(
    val text: String,
    val box: ScreenBox,
    val words: List<OcrWord> = emptyList(),
    val confidence: Float? = null
)

enum class TargetSource { ACCESSIBILITY, OCR }

data class ScreenTarget(
    val label: String,
    val box: ScreenBox,
    val source: TargetSource,
    val clickable: Boolean,
    val editable: Boolean = false,
    val focused: Boolean = false
) {
    val normalized: String get() = TextMatcher.normalize(label)
}

/**
 * The control the app has its cursor in. A password box keeps its label to itself, because the name of
 * a credential is not something a reading should pass on.
 */
data class FocusedControl(
    val label: String,
    val box: ScreenBox,
    val editable: Boolean,
    val password: Boolean
)

/** One field text could be typed into, named by its label. A field's contents are never part of this. */
data class EditableField(
    val label: String,
    val box: ScreenBox,
    val password: Boolean = false,
    val focused: Boolean = false
)

enum class ConfidenceLevel { HIGH, MEDIUM, LOW, UNMEASURED }

/** How much of this screen the reading actually saw, with the reasons in words an agent can say. */
data class ScreenConfidence(
    val level: ConfidenceLevel,
    val score: Float,
    val reasons: List<String> = emptyList()
) {
    val isMeasured: Boolean get() = level != ConfidenceLevel.UNMEASURED

    companion object {
        /** A producer that never scored its reading. It is not the same thing as a low score. */
        val Unmeasured = ScreenConfidence(
            ConfidenceLevel.UNMEASURED,
            0f,
            listOf("this reading was never scored")
        )
    }
}

data class ScreenModel(
    val packageName: String,
    val capturedAt: Long,
    val screenWidth: Int,
    val screenHeight: Int,
    val nodes: List<ScreenNode>,
    val ocr: List<OcrLine>,
    val ocrUsed: Boolean,
    val secureLikely: Boolean,
    val passwordFocused: Boolean,
    val captureError: String? = null,
    val ocrError: String? = null,
    /** Null means the reading cannot say where the cursor is; it is not a claim that nothing is. */
    val focus: FocusedControl? = null,
    val editableFields: List<EditableField> = emptyList(),
    val confidence: ScreenConfidence = ScreenConfidence.Unmeasured
) {
    val targets: List<ScreenTarget> by lazy { ScreenFusion.targets(nodes, ocr) }

    /** Fields it is allowed to type into: editable, and not a password box. */
    val writableFields: List<EditableField> get() = editableFields.filterNot { it.password }

    /** The field holding the cursor, when that field is one this app may touch. */
    val focusedField: EditableField? get() = editableFields.firstOrNull { it.focused && !it.password }

    fun findTarget(query: String, ordinal: Int? = null): ScreenTarget? =
        ScreenFusion.findTarget(query, targets, ordinal)

    fun readableText(maxChars: Int = 6000): String {
        val labels = LinkedHashSet<String>()
        val classified = ArrayList<Pair<ScreenBox, String>>()

        nodes.asSequence()
            .filter { !it.password && it.box.isValid }
            .sortedWith(compareBy({ it.box.top }, { it.box.left }))
            .forEach { node ->
                val label = node.label.trim()
                if (label.length > 1 && labels.add(label.lowercase(Locale.ROOT))) {
                    classified.add(node.box to label)
                }
            }

        ocr.asSequence()
            .sortedWith(compareBy({ it.box.top }, { it.box.left }))
            .forEach { line ->
                val label = line.text.trim()
                if (label.length <= 1) return@forEach
                val key = label.lowercase(Locale.ROOT)
                if (labels.contains(key)) return@forEach
                val duplicate = classified.any { (box, existing) ->
                    box.overlapRatio(line.box) > 0.55f && existing.lowercase(Locale.ROOT) == key
                }
                if (!duplicate && labels.add(key)) {
                    classified.add(line.box to label)
                }
            }

        val builder = StringBuilder()
        for ((_, label) in classified.sortedWith(compareBy({ it.first.top }, { it.first.left }))) {
            if (builder.length + label.length + 1 > maxChars) break
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(label)
        }
        return builder.toString()
    }

    fun describeSource(): String = when {
        ocrUsed && ocr.isNotEmpty() -> "screen pixels + accessibility"
        ocrUsed -> "screen pixels"
        else -> "accessibility"
    }
}
