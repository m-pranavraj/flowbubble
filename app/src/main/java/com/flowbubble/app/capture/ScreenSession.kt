package com.flowbubble.app.capture

import android.content.Context
import android.graphics.Bitmap
import com.flowbubble.app.access.FlowAccessibilityService
import com.flowbubble.app.vision.FrameDiff
import com.flowbubble.app.vision.GridSignature
import com.flowbubble.app.vision.LiveConfig
import com.flowbubble.app.vision.LiveDiff
import com.flowbubble.app.vision.LiveScreenModel
import com.flowbubble.app.vision.LiveSnapshot
import com.flowbubble.app.vision.OcrEngine
import com.flowbubble.app.vision.OcrLine
import com.flowbubble.app.vision.ScreenBox
import com.flowbubble.app.vision.ScreenHue
import com.flowbubble.app.vision.ScreenModel
import com.flowbubble.app.vision.ScreenModelFactory
import com.flowbubble.app.vision.TargetLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Continuously watches the screen while FlowBubble works.
 *
 * It consumes the frame stream [ScreenCaptureService] already produces and turns it into versioned
 * [LiveSnapshot]s: every frame is fingerprinted on a coarse grid (a few hundred integer reads, on
 * the capture thread), and the expensive part — recognizing text in the pixels — only runs when the
 * fingerprint actually moved, and then only over the band that moved. Each publish fuses those
 * pixels with a fresh accessibility tree, so a revision is a whole understanding of the screen
 * rather than a screenshot with text in it.
 *
 * Two rules are absolute. Nothing here outlives a stop: bitmaps are recycled, the coroutine worker
 * is cancelled, and if this session asked for screen access then stopping releases the
 * MediaProjection. And nothing is ever persisted or logged: no frame path, no frame bytes, no
 * recognized text.
 *
 * Without screen access it still works, on the accessibility tree alone at [TREE_TICK_MS] — that is
 * the "good analysis without AI" path, and it never needs a key.
 */
object ScreenSession {
    /** How often the tree is re-read to keep the live model honest. */
    private const val TREE_TICK_MS = 420L

    /** Frames stop arriving when the screen is static; after this the tree loop takes the wheel. */
    private const val FRAME_OWNED_MS = 1200L

    private val model = LiveScreenModel(LiveConfig())

    private val lock = Any()

    private var worker: CoroutineScope? = null
    private var ticker: Job? = null
    private val observers = CopyOnWriteArraySet<(LiveSnapshot) -> Unit>()
    private val busy = AtomicBoolean(false)

    @Volatile
    private var latestSignature: GridSignature? = null

    /**
     * The last tree the session read, as a cache for the capture thread. Null until the loop (or the
     * first frame) gets one.
     */
    @Volatile
    private var lastTree: ScreenModelFactory.TreeRead? = null

    @Volatile
    private var lastTreeReadAt = 0L

    @Volatile
    private var lastFrameAt = 0L

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var framesSeen = 0

    private val treeRefreshInFlight = AtomicBoolean(false)

    /**
     * True while a publish is being assembled, so the two publishers — the frame listener on the
     * capture thread and [runTreeLoop] on the worker — never cross over each other's revision.
     */
    @Volatile
    private var pixelPassInFlight = false

    /** The user asked for this session by voice; it survives a task finishing until they say stop. */
    @Volatile
    var pinnedByUser: Boolean = false
        private set

    /** True when this session, and only this session, asked Android for screen access. */
    private var projectionRequestedBySession = false

    private var startedAt = 0L

    /**
     * Runs on the capture thread, so it does the cheap part only: fingerprint the frame and decide
     * whether it moved. It never touches the accessibility service, because [ScreenCapture] dispatches
     * listeners while holding its own lock and a binder walk there would stall the frame pipeline for
     * every other consumer. The tree is refreshed by [runTreeLoop] and read here as a cache.
     */
    private val frameListener = ScreenCapture.FrameListener { frame ->
        if (!model.isWatching || pixelPassInFlight) return@FrameListener
        val signature = runCatching {
            FrameDiff.sample(
                cols = model.config.cols,
                rows = model.config.rows,
                screenWidth = frame.screenWidth,
                screenHeight = frame.screenHeight
            ) { x, y -> frame.bitmap.getPixel(x, y) }
        }.getOrNull() ?: return@FrameListener
        latestSignature = signature
        framesSeen++
        lastFrameAt = frame.capturedAt
        val tree = lastTree
        if (tree == null) {
            requestTreeRefresh()
            return@FrameListener
        }
        val analysis = model.analyze(signature, tree.textHash(), frame.capturedAt)
        if (!analysis.publish) return@FrameListener
        if (tree.passwordFocused || frame.blackLikely) {
            // Tree-only publish: cheap, and the honest path whenever pixels are not safe to read.
            pixelPassInFlight = true
            try {
                publishTreeOnly(
                    tree = tree,
                    signature = signature,
                    atMs = frame.capturedAt,
                    secure = true
                )
            } finally {
                pixelPassInFlight = false
            }
            return@FrameListener
        }
        if (!busy.compareAndSet(false, true)) return@FrameListener
        pixelPassInFlight = true
        val copy = runCatching { regionCopy(frame, analysis.region) }.getOrNull()
        pixelPassInFlight = false
        if (copy == null) {
            busy.set(false)
            return@FrameListener
        }
        val scope = worker ?: run {
            busy.set(false)
            if (!copy.isRecycled) copy.recycle()
            return@FrameListener
        }
        val frameWidth = frame.screenWidth
        val frameHeight = frame.screenHeight
        val blackLikely = frame.blackLikely
        scope.launch {
            try {
                val result = OcrEngine.recognizeRegion(
                    copy, frameWidth, frameHeight,
                    analysis.region ?: ScreenBox(0, 0, frameWidth, frameHeight)
                )
                val fresh = readTreeNow() ?: tree
                val now = System.currentTimeMillis()
                if (fresh.passwordFocused) {
                    // Pixels are dropped, not fused: a focused password field means the screen may be
                    // holding a secret, and the one-shot reader has always refused to look then.
                    publishTreeOnly(fresh, signature, now, secure = true)
                } else {
                    val fused = ScreenModelFactory.assemble(
                        tree = fresh,
                        ocr = if (result.lines.isEmpty()) carriedOcr(fresh) else mergeOcr(carriedOcr(fresh), result.lines),
                        ocrUsed = true,
                        secureLikely = blackLikely,
                        captureError = null,
                        ocrError = result.error
                    )
                    notify(model.publish(fused, signature, now, true, analysis.region))
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lastError = error.message ?: "The live screen read failed."
            } finally {
                if (!copy.isRecycled) copy.recycle()
                busy.set(false)
            }
        }
    }

    /** Whether a live understanding of the screen is being maintained right now. */
    fun isWatching(): Boolean = model.isWatching

    fun revision(): Int = model.revision

    fun snapshot(): LiveSnapshot? = model.snapshot()

    fun currentModel(): ScreenModel? = model.snapshot()?.model

    fun since(revision: Int): LiveDiff = model.since(revision)

    /**
     * The colour family under a box, from the last fingerprint the session took. Answering this from
     * the retained grid rather than a bitmap is what lets "the blue button" work without the session
     * having to hold a frame alive.
     */
    fun hueAt(box: ScreenBox): ScreenHue? = latestSignature?.hueAt(box)

    fun error(): String? = lastError

    fun addObserver(observer: (LiveSnapshot) -> Unit) {
        observers.add(observer)
    }

    fun removeObserver(observer: (LiveSnapshot) -> Unit) {
        observers.remove(observer)
    }

    /**
     * Starts watching. Safe to call twice; a task that needs eyes on the screen can ask for them
     * without stealing the user's own "keep watching" session.
     *
     * [fromUser] pins the session, so it outlives the task that started it and only stops when the
     * user says stop or the bubble goes off. Screen access is requested when it is missing, but the
     * session starts either way: the accessibility tree alone still gives a versioned screen model.
     */
    fun start(context: Context?, fromUser: Boolean) {
        synchronized(lock) {
            if (model.isWatching) {
                if (fromUser) pinnedByUser = true
                return
            }
            lastError = null
            framesSeen = 0
            lastFrameAt = 0L
            startedAt = System.currentTimeMillis()
            worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            model.start()
            ScreenCapture.addFrameListener(frameListener)
            if (fromUser) pinnedByUser = true
            if (!ScreenCapture.isActive && context != null) {
                projectionRequestedBySession = true
                ScreenCapture.requestConsent(context)
            }
            ticker = worker?.launch { runTreeLoop() }
        }
    }

    /**
     * Stops watching and releases everything it held.
     *
     * [releaseProjection] is only ever true for access this session asked for, so "stop watching"
     * never tears down a projection the user granted for something else.
     */
    fun stop(context: Context? = null, releaseProjection: Boolean = true) {
        val release: Boolean
        synchronized(lock) {
            if (!model.isWatching) return
            model.stop()
            ScreenCapture.removeFrameListener(frameListener)
            ticker?.cancel()
            ticker = null
            worker?.cancel()
            worker = null
            latestSignature = null
            lastTree = null
            lastFrameAt = 0L
            pinnedByUser = false
            release = releaseProjection && projectionRequestedBySession
            projectionRequestedBySession = false
            busy.set(false)
            treeRefreshInFlight.set(false)
            pixelPassInFlight = false
        }
        if (release && context != null) ScreenCapture.stop(context)
    }

    /** Stops a session a task started for itself, leaving the user's own watch running alone. */
    fun releaseIfUnpinned(context: Context? = null) {
        if (!pinnedByUser) stop(context)
    }

    /**
     * Waits for the screen understanding to move past [minimum] revision.
     *
     * The reactive loop needs this: after a tap it has to see the screen that the tap produced, not
     * the one it tapped on. Falls back to a plain timeout when watching was never started, so a
     * caller that has no live session still gets an answer instead of hanging.
     */
    suspend fun awaitRevision(minimum: Int, timeoutMs: Long): LiveSnapshot? {
        if (!model.isWatching) return null
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = model.snapshot()
        while (System.currentTimeMillis() < deadline) {
            val current = model.snapshot()
            if (current != null && current.revision > minimum) return current
            last = current ?: last
            delay(40)
        }
        return last?.takeIf { it.revision > minimum }
    }

    /** Resolves a pointing phrase against the live screen. Never guesses: ambiguity comes back. */
    fun resolvePointing(
        phrase: String,
        appearedSince: Set<String> = emptySet()
    ): com.flowbubble.app.vision.PointResolution? {
        val current = model.snapshot() ?: return null
        val width = current.model.screenWidth
        val height = current.model.screenHeight
        return TargetLocator.resolve(
            phrase = phrase,
            targets = current.targets,
            appeared = appearedSince,
            hueOf = { box -> hueAt(box) },
            screenWidth = width,
            screenHeight = height
        )
    }

    /** One honest line about what the session can see, for a panel or the notification. */
    fun status(): String {
        if (!model.isWatching) return "Not watching the screen."
        val age = System.currentTimeMillis() - startedAt
        val pixels = ScreenCapture.isActive && framesSeen > 0
        val base = when {
            pixels -> "Watching the screen: pixels and accessibility, revision ${model.revision}."
            ScreenCapture.isActive -> "Screen access is on but no frame has arrived yet."
            else -> "Watching the app's own text only, revision ${model.revision}. Screen access is off."
        }
        val quiet = System.currentTimeMillis() - maxOf(lastFrameAt, lastTreeReadAt)
        val still = if (quiet > STALE_MS) " Nothing new on screen for ${quiet / 1000}s." else ""
        val note = lastError?.let { " $it" } ?: ""
        return "$base$note$still (${age / 1000}s)"
    }

    /**
     * Reads the accessibility tree and caches it as the session's current view of the app. Only ever
     * called from the worker, since a tree walk is binder IPC and the capture thread must stay cheap.
     */
    private suspend fun readTreeNow(): ScreenModelFactory.TreeRead? {
        val service = FlowAccessibilityService.instance ?: return lastTree
        val read = runCatching { ScreenModelFactory.readTree(service) }.getOrNull() ?: return lastTree
        lastTree = read
        lastTreeReadAt = System.currentTimeMillis()
        return read
    }

    /**
     * Asks the worker for a tree read if the cache is empty. Called from the capture thread when the
     * first frame lands, so a session with eyes doesn't wait a whole tick to know what app it is
     * looking at.
     */
    private fun requestTreeRefresh() {
        val scope = worker ?: return
        if (!treeRefreshInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                readTreeNow()
            } finally {
                treeRefreshInFlight.set(false)
            }
        }
    }

    /**
     * Text already read from pixels stays usable while the pixels have not moved. The region diff
     * means only part of the screen gets re-read, so the lines covering the untouched part are kept
     * and the new lines replace what they overlap — dropping a stale label the moment something is
     * drawn over it is what keeps "what disappeared" honest.
     */
    private fun mergeOcr(kept: List<OcrLine>, fresh: List<OcrLine>): List<OcrLine> {
        if (kept.isEmpty()) return fresh
        val out = ArrayList<OcrLine>(kept.size + fresh.size)
        out.addAll(fresh)
        kept.forEach { old ->
            val covered = fresh.any { new -> new.box.overlapRatio(old.box) > 0.45f }
            if (!covered) out.add(old)
        }
        return out.sortedWith(compareBy({ it.box.top }, { it.box.left })).take(MAX_OCR_LINES)
    }

    private fun carriedOcr(tree: ScreenModelFactory.TreeRead?): List<OcrLine> {
        val current = model.snapshot()?.model ?: return emptyList()
        if (tree != null && current.packageName != tree.packageName) return emptyList()
        return current.ocr
    }

    private fun publish(
        tree: ScreenModelFactory.TreeRead,
        ocr: List<OcrLine>,
        pixelsRefreshed: Boolean,
        region: ScreenBox?,
        signature: GridSignature?,
        atMs: Long,
        secure: Boolean
    ) {
        val fused = ScreenModelFactory.assemble(
            tree = tree,
            ocr = ocr,
            ocrUsed = ocr.isNotEmpty(),
            secureLikely = secure,
            captureError = if (secure) {
                "Pixels were not read: the screen is either password-protected or blocks capture."
            } else {
                null
            },
            ocrError = null
        )
        notify(model.publish(fused, signature, atMs, pixelsRefreshed, region))
    }

    private fun notify(snapshot: LiveSnapshot) {
        observers.forEach { observer -> runCatching { observer(snapshot) } }
    }

    /**
     * Copies the changed band out of a frame the session does not own, so the recognizer can work on
     * it after the capture pipeline has moved on. The copy is recycled by the job that gets it.
     */
    private fun regionCopy(frame: ScreenCapture.Frame, region: ScreenBox?): Bitmap? {
        val source = frame.bitmap
        if (source.isRecycled) return null
        if (region == null) return runCatching { source.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        val mapX = frame.screenWidth.toFloat() / source.width.coerceAtLeast(1)
        val mapY = frame.screenHeight.toFloat() / source.height.coerceAtLeast(1)
        if (mapX <= 0f || mapY <= 0f) return runCatching { source.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        val left = (region.left / mapX).toInt().coerceIn(0, source.width - 1)
        val top = (region.top / mapY).toInt().coerceIn(0, source.height - 1)
        val right = (region.right / mapX).toInt().coerceIn(left + 1, source.width)
        val bottom = (region.bottom / mapY).toInt().coerceIn(top + 1, source.height)
        return runCatching { Bitmap.createBitmap(source, left, top, right - left, bottom - top) }.getOrNull()
    }

    /**
     * The heartbeat: re-reads the accessibility tree and publishes a revision whenever the tree moved
     * and no frames are arriving.
     *
     * With screen access on, the frame listener owns publishing and this loop only keeps the tree
     * cache warm. With screen access off — the "good analysis without AI, without a key" path — this
     * loop is the only thing that notices the screen changed, so revisions keep flowing either way.
     */
    private suspend fun runTreeLoop() {
        while (model.isWatching) {
            delay(TREE_TICK_MS)
            if (!model.isWatching) return
            val tree = readTreeNow()
            if (tree == null) {
                if (FlowAccessibilityService.instance == null) {
                    lastError = "Accessibility access isn't on, so FlowBubble can't read the screen."
                }
                continue
            }
            if (framesFlowing()) continue
            val now = System.currentTimeMillis()
            val analysis = model.analyze(null, tree.textHash(), now)
            if (!analysis.publish || pixelPassInFlight) continue
            pixelPassInFlight = true
            try {
                publishTreeOnly(tree, signature = null, atMs = now, secure = tree.passwordFocused)
            } finally {
                pixelPassInFlight = false
            }
        }
    }

    /** True when the pixel path is alive; it owns publishing so this loop doesn't duplicate it. */
    private fun framesFlowing(): Boolean =
        ScreenCapture.isActive && System.currentTimeMillis() - lastFrameAt < FRAME_OWNED_MS

    private fun publishTreeOnly(
        tree: ScreenModelFactory.TreeRead,
        signature: GridSignature?,
        atMs: Long,
        secure: Boolean
    ) {
        publish(
            tree = tree,
            ocr = carriedOcr(tree),
            pixelsRefreshed = false,
            region = null,
            signature = signature,
            atMs = atMs,
            secure = secure
        )
    }

    private const val MAX_OCR_LINES = 180

    /** When nothing has been read for this long, [status] says so instead of implying liveness. */
    private const val STALE_MS = 5000L
}
