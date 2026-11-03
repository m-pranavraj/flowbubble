package com.flowbubble.app.vision

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * The colour family of one coarse grid cell, named in words a user can actually say.
 *
 * Deliberately coarse: eight families plus black, white and grey. A recognizer that reported the
 * exact ARGB of a pixel would be useless for "tap the blue button", and a button is blue to
 * everyone even when its hex changes with the app's theme.
 */
enum class ScreenHue(val word: String) {
    DARK("black"),
    LIGHT("white"),
    NEUTRAL("grey"),
    RED("red"),
    ORANGE("orange"),
    YELLOW("yellow"),
    GREEN("green"),
    CYAN("cyan"),
    BLUE("blue"),
    PURPLE("purple");

    companion object {
        /** Coarse colour family of a colour, by hue bands and lightness, never by exact match. */
        fun of(color: Int): ScreenHue {
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val hi = maxOf(r, g, b)
            val lo = minOf(r, g, b)
            if (hi < 46) return DARK
            if (lo > 186 && hi - lo < 44) return LIGHT
            if (hi - lo < 34) return NEUTRAL
            val hue = when (hi) {
                r -> ((g - b) * 60) / (hi - lo)
                g -> 120 + ((b - r) * 60) / (hi - lo)
                else -> 240 + ((r - g) * 60) / (hi - lo)
            }.let { if (it < 0) it + 360 else it }
            return when {
                hue < 16 || hue >= 340 -> RED
                hue < 42 -> ORANGE
                hue < 70 -> YELLOW
                hue < 165 -> GREEN
                hue < 200 -> CYAN
                // 250 rather than the textbook 270: this app's whole accent set is lavender, which
                // sits at 254-258, and "the purple one" spoken at a lavender button means this one.
                hue < 250 -> BLUE
                else -> PURPLE
            }
        }

        /** Colour words a user may speak, including the common mishearings. */
        fun spoken(raw: String): ScreenHue? = when (TextMatcher.normalize(raw)) {
            "black", "dark" -> DARK
            "white", "light" -> LIGHT
            "grey", "gray", "greyish", "grayish" -> NEUTRAL
            "red", "maroon" -> RED
            "orange", "saffron" -> ORANGE
            "yellow" -> YELLOW
            "green" -> GREEN
            "cyan", "turquoise", "teal" -> CYAN
            "blue" -> BLUE
            "purple", "violet", "pink" -> PURPLE
            else -> null
        }
    }
}

/**
 * A fingerprint of one frame: the screen cut into a fixed grid of luminance and colour buckets.
 *
 * This is the cheap half of a live session. Comparing two of these costs a few hundred integer
 * comparisons instead of 100 000 pixel reads, so the session can look at every frame and still
 * only spend money on the ones that actually differ. Colour is kept because pointing at
 * "the blue button" is impossible from brightness alone.
 */
class GridSignature(
    val cols: Int,
    val rows: Int,
    val luminance: IntArray,
    val hues: IntArray,
    val screenWidth: Int,
    val screenHeight: Int
) {
    val cellCount: Int = cols * rows

    /** Majority colour family inside a screen-coordinate box, or null when the box is off-grid. */
    fun hueAt(box: ScreenBox): ScreenHue? {
        if (cellCount == 0 || screenWidth <= 0 || screenHeight <= 0) return null
        val tally = IntArray(ScreenHue.entries.size)
        var seen = 0
        for (row in cellRows(box)) {
            for (col in cellCols(box)) {
                val index = row * cols + col
                if (index < 0 || index >= cellCount) continue
                tally[hues[index]]++
                seen++
            }
        }
        if (seen == 0) return null
        var best = 0
        var bestCount = -1
        tally.forEachIndexed { index, count -> if (count > bestCount) { bestCount = count; best = index } }
        return ScreenHue.entries[best]
    }

    fun luminanceAt(box: ScreenBox): Int {
        val cols = cellCols(box)
        val rows = cellRows(box)
        if (cols.isEmpty() || rows.isEmpty()) return 0
        var total = 0L
        var seen = 0
        for (row in rows) {
            for (col in cols) {
                val index = row * this.cols + col
                if (index in 0 until cellCount) {
                    total += luminance[index]
                    seen++
                }
            }
        }
        return if (seen == 0) 0 else (total / seen).toInt()
    }

    private fun cellCols(box: ScreenBox): IntRange {
        if (screenWidth <= 0) return 0..-1
        val left = (box.left.toFloat() / screenWidth * cols).toInt().coerceIn(0, cols - 1)
        val right = ((box.right - 1).toFloat() / screenWidth * cols).toInt().coerceIn(0, cols - 1)
        return left..max(left, right)
    }

    private fun cellRows(box: ScreenBox): IntRange {
        if (screenHeight <= 0) return 0..-1
        val top = (box.top.toFloat() / screenHeight * rows).toInt().coerceIn(0, rows - 1)
        val bottom = ((box.bottom - 1).toFloat() / screenHeight * rows).toInt().coerceIn(0, rows - 1)
        return top..max(top, bottom)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GridSignature) return false
        return cols == other.cols && rows == other.rows &&
            screenWidth == other.screenWidth && screenHeight == other.screenHeight &&
            luminance.contentEquals(other.luminance) && hues.contentEquals(other.hues)
    }

    override fun hashCode(): Int {
        var result = cols
        result = 31 * result + rows
        result = 31 * result + luminance.contentHashCode()
        result = 31 * result + hues.contentHashCode()
        result = 31 * result + screenWidth
        result = 31 * result + screenHeight
        return result
    }

    override fun toString(): String = "GridSignature(${cols}x$rows, ${screenWidth}x$screenHeight)"
}

/**
 * Frame sampling and the diff that decides whether a frame is worth real work.
 *
 * `pixelAt` is a function rather than a Bitmap so the arithmetic is unit testable without Android;
 * the session hands it `bitmap::getPixel`.
 */
object FrameDiff {
    const val DEFAULT_COLS = 12
    const val DEFAULT_ROWS = 24

    /** Buckets differing by at least this much count as a changed cell. */
    const val CELL_DELTA = 2

    /** Samples a frame into a grid: four points per cell, averaged for brightness. */
    fun sample(
        cols: Int,
        rows: Int,
        screenWidth: Int,
        screenHeight: Int,
        pixelAt: (x: Int, y: Int) -> Int
    ): GridSignature {
        val luminance = IntArray(cols * rows)
        val hues = IntArray(cols * rows)
        if (screenWidth <= 0 || screenHeight <= 0) {
            return GridSignature(cols, rows, luminance, hues, screenWidth, screenHeight)
        }
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                var sum = 0L
                var counted = 0
                val tally = IntArray(ScreenHue.entries.size)
                for (fy in 0 until 2) {
                    for (fx in 0 until 2) {
                        val x = (((col * 2 + fx + 1) / (cols * 2f)) * screenWidth).toInt()
                            .coerceIn(0, screenWidth - 1)
                        val y = (((row * 2 + fy + 1) / (rows * 2f)) * screenHeight).toInt()
                            .coerceIn(0, screenHeight - 1)
                        val color = pixelAt(x, y)
                        val r = (color shr 16) and 0xFF
                        val g = (color shr 8) and 0xFF
                        val b = color and 0xFF
                        sum += (30L * r + 59L * g + 11L * b) / 100L
                        tally[ScreenHue.of(color).ordinal]++
                        counted++
                    }
                }
                val index = row * cols + col
                luminance[index] = if (counted == 0) 0 else (sum / counted / 32).toInt().coerceIn(0, 7)
                var best = ScreenHue.NEUTRAL.ordinal
                var bestCount = -1
                tally.forEachIndexed { position, count -> if (count > bestCount) { bestCount = count; best = position } }
                hues[index] = best
            }
        }
        return GridSignature(cols, rows, luminance, hues, screenWidth, screenHeight)
    }

    /** Fraction of cells that moved by at least [CELL_DELTA] brightness buckets. */
    fun changedFraction(current: GridSignature, baseline: GridSignature, delta: Int = CELL_DELTA): Float {
        if (current.cellCount != baseline.cellCount) return 1f
        var changed = 0
        for (index in 0 until current.cellCount) {
            if (abs(current.luminance[index] - baseline.luminance[index]) >= delta) changed++
        }
        return if (current.cellCount == 0) 0f else changed.toFloat() / current.cellCount
    }

    /**
     * Screen-space box covering every changed cell, padded for the antialiased edges OCR needs.
     * Null means "the change is everywhere" — a whole-frame pass is then cheaper than a huge band.
     */
    fun changedRegion(
        current: GridSignature,
        baseline: GridSignature,
        paddingPx: Int = 90,
        delta: Int = CELL_DELTA
    ): ScreenBox? {
        if (current.cellCount != baseline.cellCount ||
            current.screenWidth != baseline.screenWidth ||
            current.screenHeight != baseline.screenHeight
        ) {
            return null
        }
        var minCol = Int.MAX_VALUE
        var maxCol = -1
        var minRow = Int.MAX_VALUE
        var maxRow = -1
        var changed = 0
        for (index in 0 until current.cellCount) {
            if (abs(current.luminance[index] - baseline.luminance[index]) < delta) continue
            changed++
            val row = index / current.cols
            val col = index % current.cols
            if (col < minCol) minCol = col
            if (col > maxCol) maxCol = col
            if (row < minRow) minRow = row
            if (row > maxRow) maxRow = row
        }
        if (changed == 0) return null
        // Scrolling moves nearly every row; a band that wide costs more than the whole frame.
        if (changed.toFloat() / current.cellCount > WHOLE_FRAME_FRACTION) return null
        val cellW = current.screenWidth.toFloat() / current.cols
        val cellH = current.screenHeight.toFloat() / current.rows
        return ScreenBox(
            left = (minCol * cellW).toInt() - paddingPx,
            top = (minRow * cellH).toInt() - paddingPx,
            right = ((maxCol + 1) * cellW).toInt() + paddingPx,
            bottom = ((maxRow + 1) * cellH).toInt() + paddingPx
        )
    }

    private const val WHOLE_FRAME_FRACTION = 0.55f
}

/** What a live analysis decided about one frame. */
data class LiveAnalysis(
    val publish: Boolean,
    val reason: String,
    /** Null region means the whole frame; a box means only that part needs pixels read. */
    val region: ScreenBox? = null,
    val changedFraction: Float = 0f,
    /** True when something changed but the throttle said "wait for the next frame". */
    val deferred: Boolean = false
) {
    companion object {
        val Idle = LiveAnalysis(false, "not watching")
    }
}

/** One published understanding of the screen. */
data class LiveSnapshot(
    val revision: Int,
    val model: ScreenModel,
    val publishedAt: Long,
    /** False when only the accessibility tree moved and the last OCR lines were reused. */
    val pixelsRefreshed: Boolean,
    val region: ScreenBox? = null
) {
    val packageName: String get() = model.packageName
    val textHash: Int get() = ScreenFusion.textHash(model)
    val targets: List<ScreenTarget> get() = model.targets
    val focus: FocusedControl? get() = model.focus
    val editableFields: List<EditableField> get() = model.editableFields
    val confidence: ScreenConfidence get() = model.confidence
}

/** What came on screen and what left it between two revisions. */
data class LiveDiff(
    val fromRevision: Int,
    val toRevision: Int,
    val appeared: List<String> = emptyList(),
    val disappeared: List<String> = emptyList(),
    /** The asked-for revision had already aged out of history, so this is a floor, not a lie. */
    val partial: Boolean = false
) {
    val changed: Boolean get() = appeared.isNotEmpty() || disappeared.isNotEmpty() || fromRevision != toRevision
}

/** Tuning for a live session. All of it is about cost, not correctness. */
data class LiveConfig(
    val cols: Int = FrameDiff.DEFAULT_COLS,
    val rows: Int = FrameDiff.DEFAULT_ROWS,
    /** Fraction of grid cells that must move before pixels are re-read. */
    val minChangedFraction: Float = 0.012f,
    /** Absolute cell count that also counts as a change, for a small popup in a corner. */
    val minChangedCells: Int = 2,
    /** Shortest gap between two publishes, so a busy animation can't drive OCR every frame. */
    val minPublishIntervalMs: Long = 260L,
    /** A tree change is published sooner than a pixel change: it is nearly free. */
    val treePublishIntervalMs: Long = 120L,
    val paddingPx: Int = 90,
    val historySize: Int = 24
)

/**
 * Fills the three things a planner needs that a producer may not have said: where the cursor is,
 * which fields can take text, and how far to trust the reading. It is a judgement rather than a
 * match, so it lives beside the live session that publishes it and not inside fusion.
 */
object ScreenDetails {

    /**
     * Returns the model with the extras filled in. Anything the producer already said is kept as it
     * was, so a service that reported its own focus is never overruled by a guess.
     */
    fun enrich(model: ScreenModel, focusedNode: ScreenNode? = null): ScreenModel {
        val focus = model.focus
            ?: focusedNode?.let {
                FocusedControl(
                    label = if (it.password) "" else it.label.trim(),
                    box = it.box,
                    editable = it.editable,
                    password = it.password
                )
            }
            ?: ScreenFusion.focusOf(model.nodes, model.passwordFocused)
        val fields = fieldsOf(model).map { field ->
            // The tree flags focus node by node; the cursor position says the same thing when it
            // arrived from the window instead, and the two must not disagree about one box.
            if (focus != null && !field.focused && field.box == focus.box) field.copy(focused = true) else field
        }
        val confidence = if (model.confidence.isMeasured) model.confidence else confidenceOf(model, focus, fields)
        return model.copy(focus = focus, editableFields = fields, confidence = confidence)
    }

    /**
     * How much of this screen was actually seen. Pessimistic by design: pixels alone, a blocked
     * capture, or a tree with no controls in it must not read as a confident picture.
     */
    fun confidenceOf(
        model: ScreenModel,
        focus: FocusedControl? = model.focus,
        fields: List<EditableField> = model.editableFields
    ): ScreenConfidence {
        val reasons = ArrayList<String>()
        val named = model.nodes.count { it.box.isValid && it.label.isNotBlank() && !it.password }
        val actionable = model.nodes.count { it.box.isValid && (it.clickable || it.editable) }
        var score = when {
            actionable >= MIN_RICH_TREE -> { reasons.add("the app named $actionable controls itself"); 0.6f }
            actionable >= 1 -> { reasons.add("the app named only $actionable control(s)"); 0.4f }
            else -> { reasons.add("the app's own control list came back empty"); 0.1f }
        }
        if (named > actionable) score += 0.05f
        score += when {
            model.ocr.isNotEmpty() -> { reasons.add("${model.ocr.size} lines were read off the pixels"); 0.2f }
            model.ocrUsed -> { reasons.add("the screen was captured but gave no text back"); 0.05f }
            else -> { reasons.add("no pixels were read, so anything drawn is invisible"); 0f }
        }
        if (focus != null) score += 0.1f
        if (fields.isNotEmpty()) score += 0.05f
        if (model.screenWidth <= 0 || model.screenHeight <= 0) {
            score -= 0.1f
            reasons.add("the screen size was unknown")
        }
        if (model.secureLikely) {
            score -= 0.25f
            reasons.add("the captured frame came back black, so this app blocks screen reading")
        }
        if (model.passwordFocused) {
            score -= 0.2f
            reasons.add("a password field is focused, so nothing was captured around it")
        }
        if (!model.captureError.isNullOrBlank()) {
            score -= 0.15f
            reasons.add("screen capture reported a problem")
        }
        if (!model.ocrError.isNullOrBlank()) {
            score -= 0.1f
            reasons.add("text recognition reported a problem")
        }
        val clamped = score.coerceIn(0f, 1f)
        return ScreenConfidence(levelFor(clamped), clamped, reasons)
    }

    private fun levelFor(score: Float): ConfidenceLevel = when {
        score >= 0.75f -> ConfidenceLevel.HIGH
        score >= 0.45f -> ConfidenceLevel.MEDIUM
        else -> ConfidenceLevel.LOW
    }

    /** The fields the producer named, or the ones the tree can name for it. */
    private fun fieldsOf(model: ScreenModel): List<EditableField> =
        if (model.editableFields.isEmpty()) ScreenFusion.editableFields(model.nodes) else model.editableFields

    private const val MIN_RICH_TREE = 3
}

/**
 * The live understanding of the screen: a monotonic revision counter over fused snapshots, with
 * cheap grid diffs deciding when the expensive work is allowed to run.
 *
 * No Android types and no threading of its own — every method is synchronized because the frame
 * listener and the agent both touch it, and because a versioned view of the screen that could tear
 * under a reader would be worse than no view at all. Labels for the diff are the fused target set,
 * so "what appeared" means what the user can now see, not which pixels moved.
 */
class LiveScreenModel(val config: LiveConfig = LiveConfig()) {

    private val lock = Any()
    private var baseline: GridSignature? = null
    private var published: LiveSnapshot? = null
    private var labelsByRevision = LinkedHashMap<Int, Set<String>>()
    private var history = ArrayDeque<LiveSnapshot>()
    private var lastPublishAt = 0L
    private var lastTreeHash: Int? = null

    @Volatile
    var isWatching: Boolean = false
        private set

    /** Increases once per published snapshot. Never decreases and never skips while watching. */
    var revision: Int = 0
        private set

    fun start() {
        synchronized(lock) {
            isWatching = true
        }
    }

    /** Forgets everything. Frames are not retained here, so there is nothing to erase beyond this. */
    fun stop() {
        synchronized(lock) {
            isWatching = false
            baseline = null
            published = null
            labelsByRevision.clear()
            history.clear()
            lastTreeHash = null
            lastPublishAt = 0L
        }
    }

    fun snapshot(): LiveSnapshot? = synchronized(lock) { published }

    fun historyRevisions(): List<Int> = synchronized(lock) { history.map { it.revision } }

    /**
     * Decides whether this frame is worth real work.
     *
     * [pixels] is null when screen access is off: the session then runs on the accessibility tree
     * alone, which is the "good analysis without AI" path and must keep working.
     */
    fun analyze(pixels: GridSignature?, treeHash: Int?, atMs: Long): LiveAnalysis {
        synchronized(lock) {
            if (!isWatching) return LiveAnalysis.Idle
            if (published == null) {
                if (pixels == null && treeHash == null) return LiveAnalysis(false, "nothing to read yet")
                return LiveAnalysis(true, "first read of the screen", region = null)
            }
            val signature = baseline
            val fraction = if (pixels != null && signature != null) {
                FrameDiff.changedFraction(pixels, signature)
            } else {
                0f
            }
            val cells = (fraction * config.cols * config.rows)
            val pixelsChanged = pixels != null && signature != null &&
                (fraction >= config.minChangedFraction || cells >= config.minChangedCells)
            val treeChanged = treeHash != null && lastTreeHash != null && treeHash != lastTreeHash
            if (!pixelsChanged && !treeChanged) {
                return LiveAnalysis(false, "screen unchanged", changedFraction = fraction)
            }
            val interval = if (pixelsChanged) config.minPublishIntervalMs else config.treePublishIntervalMs
            if (atMs - lastPublishAt < interval) {
                return LiveAnalysis(
                    false,
                    "changed, but throttled",
                    changedFraction = fraction,
                    deferred = true
                )
            }
            val region = if (pixelsChanged && signature != null && pixels != null) {
                FrameDiff.changedRegion(pixels, signature, config.paddingPx)
            } else {
                null
            }
            val reason = when {
                pixelsChanged && treeChanged -> "the screen and the app's own text changed"
                pixelsChanged -> "the screen changed"
                else -> "the accessibility text changed"
            }
            return LiveAnalysis(
                publish = true,
                reason = reason,
                region = if (treeChanged && !pixelsChanged) null else region,
                changedFraction = fraction
            )
        }
    }

    /**
     * Commits a fused model as the new revision, enriched first so every revision a reader picks up
     * carries focus, fields and a confidence whether or not its producer knew about them.
     */
    fun publish(
        model: ScreenModel,
        pixels: GridSignature?,
        atMs: Long,
        pixelsRefreshed: Boolean,
        region: ScreenBox? = null,
        focusedNode: ScreenNode? = null
    ): LiveSnapshot = synchronized(lock) {
        val enriched = ScreenDetails.enrich(model, focusedNode)
        val next = revision + 1
        val snapshot = LiveSnapshot(next, enriched, atMs, pixelsRefreshed, region)
        revision = next
        published = snapshot
        baseline = pixels ?: baseline
        lastTreeHash = ScreenFusion.textHash(enriched)
        lastPublishAt = atMs
        labelsByRevision[next] = labelsOf(enriched)
        history.addLast(snapshot)
        while (history.size > config.historySize) {
            val oldest = history.removeFirst()
            labelsByRevision.remove(oldest.revision)
        }
        snapshot
    }

    /** Records the tree hash without publishing, so a tree read that found nothing is not a change. */
    fun noteTreeHash(hash: Int) {
        synchronized(lock) {
            if (lastTreeHash == null) lastTreeHash = hash
        }
    }

    /**
     * What came and went since [revision]. An unknown revision is reported as `partial` rather than
     * silently diffing against the oldest surviving one, because "nothing appeared" and
     * "I can't remember that far back" are different answers.
     */
    fun since(revision: Int): LiveDiff = synchronized(lock) {
        val current = published ?: return@since LiveDiff(revision, 0)
        if (revision == current.revision) {
            return@since LiveDiff(revision, current.revision)
        }
        val then = labelsByRevision[revision]
        if (then == null) {
            val oldest = labelsByRevision.keys.minOrNull()
            val reference = if (oldest != null && oldest < current.revision) {
                labelsByRevision[oldest].orEmpty()
            } else {
                emptySet()
            }
            val now = labelsOf(current.model)
            return@since LiveDiff(
                fromRevision = revision,
                toRevision = current.revision,
                appeared = (now - reference).sorted(),
                disappeared = (reference - now).sorted(),
                partial = true
            )
        }
        val now = labelsOf(current.model)
        LiveDiff(
            fromRevision = revision,
            toRevision = current.revision,
            appeared = (now - then).sorted(),
            disappeared = (then - now).sorted()
        )
    }

    private fun labelsOf(model: ScreenModel): Set<String> {
        val out = LinkedHashSet<String>()
        model.targets.forEach { target ->
            val label = TextMatcher.normalize(target.label)
            if (label.length >= 2) out.add(label)
            if (out.size >= MAX_DIFF_LABELS) return@forEach
        }
        return out
    }

    private companion object {
        const val MAX_DIFF_LABELS = 400
    }
}

/** The outcome of resolving a pointing phrase against the live screen. */
sealed interface PointResolution {
    data class Found(val target: ScreenTarget, val evidence: String) : PointResolution
    data class Ambiguous(val candidates: List<ScreenTarget>, val question: String, val reason: String) : PointResolution
    data class NotFound(val reason: String) : PointResolution
}

/**
 * Resolves "the third one", "that chat at the top", "the blue button", "click that" against the
 * live model's ordered, actionable targets.
 *
 * Two rules hold everywhere: the pool is always counted top-to-bottom, left-to-right, the same order
 * the fused targets are built in and the same order a person reads a list, and anything that is not
 * provably one target is asked back rather than guessed. A wrong tap costs the user more than a
 * question does.
 */
object TargetLocator {
    /** Words that name a kind of row, not the text on it; a chat row is labelled by a person. */
    private val GENERIC_NOUNS = setOf(
        "chat", "chats", "conversation", "conversations", "message", "messages", "one", "thing",
        "item", "items", "option", "options", "result", "results", "button", "buttons", "row",
        "rows", "entry", "entries", "card", "cards", "contact", "contacts", "file", "files",
        "photo", "photos", "picture", "pictures", "link", "links", "that", "this", "it"
    )

    private val ORDINAL_WORDS = mapOf(
        "first" to 1, "1st" to 1, "second" to 2, "2nd" to 2, "third" to 3, "3rd" to 3,
        "fourth" to 4, "4th" to 4, "fifth" to 5, "5th" to 5, "sixth" to 6, "6th" to 6,
        "seventh" to 7, "7th" to 7, "eighth" to 8, "8th" to 8, "ninth" to 9, "9th" to 9,
        "tenth" to 10, "10th" to 10, "eleventh" to 11, "twelfth" to 12
    )

    private val FILLER = setOf(
        "the", "a", "an", "on", "at", "of", "to", "from", "please", "just", "tap", "click",
        "press", "select", "choose", "open", "one", "it", "this", "that", "there", "here"
    )

    enum class Band { NONE, TOP, MIDDLE, BOTTOM, LEFT, RIGHT }

    data class Pointer(val ordinal: Int? = null, val band: Band = Band.NONE, val hue: ScreenHue? = null, val text: String = "")

    /** Reads every hint the phrase carries without losing the words that name the control. */
    fun parse(raw: String): Pointer {
        val words = TextMatcher.normalize(raw).split(' ').filter { it.isNotBlank() }
        var ordinal: Int? = null
        var band = Band.NONE
        var hue: ScreenHue? = null
        val rest = ArrayList<String>()
        var index = 0
        while (index < words.size) {
            val word = words[index]
            when {
                ORDINAL_WORDS.containsKey(word) -> ordinal = ORDINAL_WORDS[word]
                word == "top" || word == "bottom" || word == "middle" ||
                    word == "left" || word == "right" || word == "centre" -> {
                    band = when (word) {
                        "top" -> Band.TOP
                        "bottom" -> Band.BOTTOM
                        "left" -> Band.LEFT
                        "right" -> Band.RIGHT
                        else -> Band.MIDDLE
                    }
                }
                else -> {
                    val colour = ScreenHue.spoken(word)
                    if (colour != null && hue == null) hue = colour else rest.add(word)
                }
            }
            index++
        }
        ordinal = ordinal ?: rest.firstOrNull { it.length <= 2 && it.all(Char::isDigit) }?.toIntOrNull()
        val text = rest.filter { it !in FILLER }.joinToString(" ")
        return Pointer(ordinal, band, hue, text)
    }

    fun resolve(
        phrase: String,
        targets: List<ScreenTarget>,
        appeared: Set<String> = emptySet(),
        hueOf: (ScreenBox) -> ScreenHue? = { null },
        screenWidth: Int = 0,
        screenHeight: Int = 0
    ): PointResolution {
        if (targets.isEmpty()) return PointResolution.NotFound("I can't see anything actionable on this screen yet.")
        val pointer = parse(phrase)
        val pool = targets.filter { it.box.isValid && (it.clickable || it.editable) }
        val ordered = pool.ifEmpty { targets.filter { it.box.isValid } }
        if (ordered.isEmpty()) return PointResolution.NotFound("Nothing here can be tapped.")

        val steps = mutableListOf<String>()
        var candidates = ordered

        // "click that" with no noun: the thing that just appeared is what a user means by "that".
        val deictic = pointer.text.isEmpty() && pointer.ordinal == null && pointer.band == Band.NONE &&
            pointer.hue == null
        if (deictic && appeared.isNotEmpty()) {
            val fresh = candidates.filter { TextMatcher.normalize(it.label) in appeared }
            if (fresh.isNotEmpty()) {
                candidates = fresh
                steps.add("it is the thing that just appeared on screen")
                return finish(phrase, candidates, pointer, steps)
            }
        }

        if (pointer.text.isNotEmpty()) {
            val scored = candidates.mapNotNull { target ->
                val score = TextMatcher.score(pointer.text, target.label)
                if (score >= MIN_LABEL_SCORE) target to score else null
            }
            if (scored.isNotEmpty()) {
                val best = scored.maxOf { it.second }
                candidates = scored.filter { it.second >= best - TIE_MARGIN }.map { it.first }
                steps.add("labelled like \u201C${pointer.text}\u201D")
            } else if (pointer.text.split(' ').none { it !in GENERIC_NOUNS }) {
                steps.add("\u201C${pointer.text}\u201D names a kind of row, not a label, so every control counted")
            } else {
                return PointResolution.NotFound(
                    "I can't find \u201C${pointer.text}\u201D on this screen. " +
                        "${if (candidates.size > 1) "There are ${candidates.size} other things here I could tap instead." else ""}"
                )
            }
        }

        pointer.hue?.let { wanted ->
            val coloured = candidates.filter { hueOf(it.box) == wanted }
            if (coloured.isEmpty()) {
                val plural = if (candidates.size == 1) "" else "s"
                return PointResolution.NotFound(
                    "I can see ${candidates.size} ${pointer.text.ifBlank { "tappable" }} thing$plural here, " +
                        "but none of them looks ${wanted.word}."
                )
            }
            candidates = coloured
            steps.add("the ${wanted.word} one")
        }

        if (pointer.band != Band.NONE && screenWidth > 0 && screenHeight > 0) {
            val banded = candidates.filter { inBand(it.box, pointer.band, screenWidth, screenHeight) }
            if (banded.isEmpty()) {
                return PointResolution.NotFound("There is nothing ${pointer.band.name.lowercase(Locale.ROOT)} on this screen.")
            }
            candidates = banded
            steps.add("in the ${pointer.band.name.lowercase(Locale.ROOT)} part of the screen")
        }

        return finish(phrase, candidates, pointer, steps)
    }

    private fun finish(
        phrase: String,
        candidates: List<ScreenTarget>,
        pointer: Pointer,
        steps: List<String>
    ): PointResolution {
        val qualifier = if (steps.isEmpty()) "on screen" else steps.joinToString(", ")
        if (pointer.ordinal != null) {
            if (candidates.size < pointer.ordinal) {
                return PointResolution.NotFound(
                    "There are only ${candidates.size} $qualifier, so there is no number ${pointer.ordinal}."
                )
            }
            val chosen = candidates[pointer.ordinal - 1]
            return PointResolution.Found(
                chosen,
                "the ${ordinalName(pointer.ordinal)} of ${candidates.size} $qualifier"
            )
        }
        if (candidates.size == 1) {
            return PointResolution.Found(candidates.first(), "the only $qualifier")
        }
        val named = candidates.filter { TextMatcher.normalize(it.label).isNotEmpty() }
        val ask = (named.ifEmpty { candidates }).take(MAX_ASK)
        return if (ask.size < 2) {
            PointResolution.Found(ask.first(), "the only $qualifier")
        } else {
            PointResolution.Ambiguous(
                candidates = ask,
                question = "Which one did you mean?",
                reason = "\u201C${phrase.trim()}\u201D matches ${candidates.size} $qualifier."
            )
        }
    }

    private fun inBand(box: ScreenBox, band: Band, width: Int, height: Int): Boolean {
        val thirdY = height / 3f
        val thirdX = width / 3f
        return when (band) {
            Band.TOP -> box.centerY < thirdY
            Band.MIDDLE -> box.centerY >= thirdY && box.centerY < thirdY * 2
            Band.BOTTOM -> box.centerY >= thirdY * 2
            Band.LEFT -> box.centerX < thirdX
            Band.RIGHT -> box.centerX >= width - thirdX
            Band.NONE -> true
        }
    }

    private fun ordinalName(ordinal: Int): String = when (ordinal) {
        1 -> "first"
        2 -> "second"
        3 -> "third"
        4 -> "fourth"
        5 -> "fifth"
        else -> "${ordinal}th"
    }

    private const val MIN_LABEL_SCORE = 420
    private const val TIE_MARGIN = 40
    private const val MAX_ASK = 4
}
