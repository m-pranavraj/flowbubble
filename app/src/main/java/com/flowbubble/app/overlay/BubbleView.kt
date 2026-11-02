package com.flowbubble.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import com.flowbubble.app.overlay.companion.CompanionController
import com.flowbubble.app.overlay.companion.CompanionMood
import com.flowbubble.app.overlay.companion.CompanionRenderer
import com.flowbubble.app.overlay.companion.CompanionState as CompanionPose
import com.flowbubble.app.overlay.companion.CompanionTiming
import kotlin.math.hypot
import kotlin.math.min

/**
 * The system-overlay bubble. It used to be a dark disc with a microphone glyph; now it is a small
 * creature that lives on top of every app and behaves like a companion: it breathes and blinks when
 * left alone, leans in and pulses when you hold it, drifts its eyes and orbits a spark while it
 * thinks, smiles when it lands the task and droops when it fails.
 *
 * Deliberate constraints baked into this file:
 *  - the drawing is pure Canvas (no image assets, no animation libraries) because this thing has to
 *    survive for hours inside a foreground service on a phone;
 *  - nothing allocates per frame — see [CompanionRenderer];
 *  - idle runs at roughly 8 fps and only full-frame rate is spent on the states a human is watching;
 *  - every animation driver is torn down in [onDetachedFromWindow] and refuses to run while hidden.
 *
 * The gesture layer below is device-verified behaviour (hold after 140 ms, drag past touch slop,
 * quick tap, clean ACTION_CANCEL unwind) and is kept exactly as it was.
 */
class BubbleView(context: Context) : View(context) {

    enum class State { IDLE, LISTENING, PROCESSING, READY, ERROR }

    var onHoldStart: (() -> Unit)? = null
    var onHoldEnd: (() -> Unit)? = null
    var onTap: (() -> Unit)? = null
    var onDragStart: (() -> Unit)? = null
    var onDrag: ((Float, Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val controller = CompanionController()
    private val renderer = CompanionRenderer(density)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /**
     * Expression layer, independent from [State]. Optional: the companion defaults to
     * [CompanionMood.Neutral] and looks complete without anyone calling this.
     */
    val mood: CompanionMood get() = controller.mood

    /** Growth stage 1..4 — a small blob at 1, halo and stronger aura at 4. Clamped, optional. */
    val stage: Int get() = controller.stage

    private var state = State.IDLE
    private var speaking = false
    private var animator: ValueAnimator? = null
    private var ticking = false

    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var holding = false
    private var dragging = false

    private val holdRunnable = Runnable {
        if (!dragging && !holding) {
            holding = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onHoldStart?.invoke()
        }
    }

    /** The slow heartbeat of the idle character. Re-posts itself with the delay the controller asks for. */
    private val idleTicker = object : Runnable {
        override fun run() {
            ticking = false
            if (!shouldAnimate()) return
            ticking = true
            invalidate()
            postDelayed(this, controller.tickDelayMs(now()))
        }
    }

    init {
        isClickable = true
        isFocusable = false
        contentDescription = describe(State.IDLE)
    }

    // ---------------------------------------------------------------- public surface

    fun setState(newState: State) {
        if (state == newState) return
        state = newState
        controller.setState(newState.toPose(), now())
        if (newState != State.LISTENING) controller.setAmplitude(0f, now())
        contentDescription = describe(newState)
        updateAnimation()
        invalidate()
    }

    fun setAmplitude(value: Float) {
        controller.setAmplitude(value, now())
        if (state == State.LISTENING) invalidate()
    }

    /** Optional. Sets the expression overlay; safe to never call. */
    fun setMood(mood: CompanionMood = CompanionMood.Neutral) {
        controller.setMood(mood)
        invalidate()
    }

    /** Optional. Sets the growth stage, clamped to 1..4; safe to never call. */
    fun setStage(stage: Int = CompanionTiming.DefaultStage) {
        controller.setStage(stage)
        invalidate()
    }

    /**
     * Optional. True while text-to-speech is playing: the mouth then tracks the reported amplitude
     * instead of staying shut, and the character earns full frame rate.
     */
    fun setSpeaking(active: Boolean = true) {
        if (speaking == active) return
        speaking = active
        controller.setSpeaking(active)
        updateAnimation()
        invalidate()
    }

    // ---------------------------------------------------------------- drawing

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wanted = dp(96f)
        val specWidth = MeasureSpec.getSize(widthMeasureSpec)
        val specHeight = MeasureSpec.getSize(heightMeasureSpec)
        // The overlay window owns the size, so never measure larger than it asked for, and never
        // larger than 96 dp — the service positions the window against the screen edges.
        val cap = min(
            if (specWidth > 0) specWidth.toFloat() else wanted,
            if (specHeight > 0) specHeight.toFloat() else wanted
        )
        val side = min(wanted, cap).toInt().coerceAtLeast(dp(48f).toInt())
        setMeasuredDimension(side, side)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val half = min(width, height) / 2f
        val bodyRadius = half - dp(8f)
        if (bodyRadius <= 0f) return
        val frame = controller.update(now())
        renderer.draw(canvas, frame, width / 2f, height / 2f, bodyRadius)
    }

    private fun State.toPose(): CompanionPose = when (this) {
        State.IDLE -> CompanionPose.Idle
        State.LISTENING -> CompanionPose.Listening
        State.PROCESSING -> CompanionPose.Thinking
        State.READY -> CompanionPose.Happy
        State.ERROR -> CompanionPose.Worried
    }

    private fun describe(which: State): CharSequence = when (which) {
        State.IDLE -> "The bubble is resting. Hold to talk, tap for options."
        State.LISTENING -> "The bubble is listening. Release to finish."
        State.PROCESSING -> "The bubble is working on your request."
        State.READY -> "The bubble finished the task."
        State.ERROR -> "The bubble ran into a problem."
    }

    // ---------------------------------------------------------------- animation lifecycle

    private fun now(): Long = SystemClock.uptimeMillis()

    private fun shouldAnimate(): Boolean =
        isAttachedToWindow && visibility == VISIBLE && windowVisibility == VISIBLE

    private fun updateAnimation() {
        if (!shouldAnimate()) {
            stopFullRate()
            stopIdleTicker()
            return
        }
        if (controller.isFullRate(now())) {
            stopIdleTicker()
            startFullRate()
        } else {
            stopFullRate()
            startIdleTicker()
        }
    }

    /** Handed back to the slow heartbeat once a transient burst (the READY bounce) has settled. */
    private val rateDownRunnable = Runnable { updateAnimation() }

    private fun startFullRate() {
        if (animator != null) return
        // The animator is only a vsync metronome; the character reads wall-clock time itself, which
        // also means no boxed animated value is allocated every frame. It is never cancelled from
        // inside its own listener, so this field always reflects what is really running.
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CompanionTiming.OrbitPeriodMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (!shouldAnimate()) return@addUpdateListener
                if (controller.isFullRate(now())) {
                    invalidate()
                } else {
                    // A short-lived reason for full rate ran out: give the idle ticker over, off the
                    // animation callback, instead of spinning at 60 fps for nothing.
                    postDelayed(rateDownRunnable, CompanionTiming.IdleTickMs)
                }
            }
            start()
        }
    }

    private fun stopFullRate() {
        removeCallbacks(rateDownRunnable)
        animator?.cancel()
        animator = null
    }

    private fun startIdleTicker() {
        if (ticking) return
        // Cancel first: a pending callback and a fresh one must never coexist.
        removeCallbacks(idleTicker)
        ticking = true
        postDelayed(idleTicker, controller.tickDelayMs(now()))
    }

    private fun stopIdleTicker() {
        if (!ticking) return
        ticking = false
        removeCallbacks(idleTicker)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        controller.reset(now())
        controller.setState(state.toPose(), now())
        controller.setMood(mood)
        controller.setStage(stage)
        controller.setSpeaking(speaking)
        updateAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateAnimation()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateAnimation()
    }

    // ---------------------------------------------------------------- gestures (unchanged)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = downRawX
                lastRawY = downRawY
                holding = false
                dragging = false
                postDelayed(holdRunnable, 140)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (holding) return true
                val totalX = event.rawX - downRawX
                val totalY = event.rawY - downRawY
                if (!dragging && hypot(totalX.toDouble(), totalY.toDouble()) > touchSlop) {
                    dragging = true
                    removeCallbacks(holdRunnable)
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    controller.poke(now(), CompanionMood.Curious)
                    onDragStart?.invoke()
                }
                if (dragging) {
                    val dx = event.rawX - lastRawX
                    val dy = event.rawY - lastRawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    onDrag?.invoke(dx, dy)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(holdRunnable)
                if (holding) {
                    holding = false
                    onHoldEnd?.invoke()
                } else if (dragging) {
                    dragging = false
                    onDragEnd?.invoke()
                } else {
                    performClick()
                }
                updateAnimation()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdRunnable)
                if (holding) {
                    holding = false
                    onHoldEnd?.invoke()
                } else if (dragging) {
                    dragging = false
                    onDragEnd?.invoke()
                }
                updateAnimation()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        controller.poke(now(), CompanionMood.Surprised)
        invalidate()
        onTap?.invoke()
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(holdRunnable)
        stopIdleTicker()
        stopFullRate()
        super.onDetachedFromWindow()
    }
}
