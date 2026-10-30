package com.flowbubble.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import com.flowbubble.app.R
import kotlin.math.hypot

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

    private val accent = context.getColor(R.color.flow_accent)
    private val readyColor = Color.parseColor("#34D399")
    private val errorColor = Color.parseColor("#F87171")
    private val bodyColor = Color.parseColor("#0F1720")
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.4f)
        strokeCap = Paint.Cap.ROUND
    }
    private val micFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val micStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.6f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var state = State.IDLE
    private var phase = 0f
    private var amplitude = 0f
    private var animator: ValueAnimator? = null

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

    init {
        isClickable = true
        isFocusable = false
        contentDescription = idleDescription()
    }

    fun setState(newState: State) {
        if (state == newState) return
        state = newState
        if (newState != State.LISTENING) amplitude = 0f
        updateAnimation()
        contentDescription = when (newState) {
            State.IDLE -> idleDescription()
            State.LISTENING -> "FlowBubble is listening. Release to finish."
            State.PROCESSING -> "FlowBubble is processing your request."
            State.READY -> "FlowBubble finished the task."
            State.ERROR -> "FlowBubble ran into a problem."
        }
        invalidate()
    }

    fun setAmplitude(value: Float) {
        amplitude = value.coerceIn(0f, 1f)
        if (state == State.LISTENING) invalidate()
    }

    private fun idleDescription() = "FlowBubble microphone. Hold to talk, tap for options."

    private fun updateAnimation() {
        val shouldAnimate = state == State.LISTENING || state == State.PROCESSING
        if (shouldAnimate && animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1400
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    phase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else if (!shouldAnimate) {
            animator?.cancel()
            animator = null
            phase = 0f
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(72f).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val bodyRadius = width / 2f - dp(9f)
        val color = when (state) {
            State.READY -> readyColor
            State.ERROR -> errorColor
            else -> accent
        }

        val glowStrength = when (state) {
            State.LISTENING -> 0.42f + amplitude * 0.4f
            State.PROCESSING -> 0.3f
            State.READY -> 0.32f
            State.ERROR -> 0.32f
            State.IDLE -> 0.16f
        }
        val pulse = if (state == State.LISTENING) (1f + (phase * 2f % 1f) * 0.22f) else 1f
        val glowRadius = bodyRadius * 1.65f * pulse
        glowPaint.shader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(
                Color.argb((glowStrength * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowRadius, glowPaint)

        bodyPaint.color = bodyColor
        canvas.drawCircle(cx, cy, bodyRadius, bodyPaint)
        bodyPaint.style = Paint.Style.STROKE
        bodyPaint.strokeWidth = dp(1.2f)
        bodyPaint.color = Color.argb(
            if (state == State.LISTENING) 210 else 120,
            Color.red(color), Color.green(color), Color.blue(color)
        )
        canvas.drawCircle(cx, cy, bodyRadius, bodyPaint)
        bodyPaint.style = Paint.Style.FILL

        when (state) {
            State.IDLE -> {
                drawMic(canvas, cx, cy, accent)
            }
            State.LISTENING -> {
                drawPulseRing(canvas, cx, cy, bodyRadius, color)
                drawMic(canvas, cx, cy, Color.WHITE)
            }
            State.PROCESSING -> {
                val ringRadius = bodyRadius - dp(1f)
                val start = phase * 360f
                ringPaint.color = color
                canvas.drawArc(
                    RectF(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius),
                    start, 92f, false, ringPaint
                )
                ringPaint.color = Color.argb(80, Color.red(color), Color.green(color), Color.blue(color))
                canvas.drawArc(
                    RectF(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius),
                    start + 180f, 92f, false, ringPaint
                )
                drawMic(canvas, cx, cy, accent)
            }
            State.READY -> {
                glyphPaint.color = readyColor
                val path = android.graphics.Path().apply {
                    moveTo(cx - dp(9f), cy)
                    lineTo(cx - dp(2.5f), cy + dp(7f))
                    lineTo(cx + dp(10f), cy - dp(7f))
                }
                canvas.drawPath(path, glyphPaint)
            }
            State.ERROR -> {
                glyphPaint.color = errorColor
                canvas.drawLine(cx, cy - dp(10f), cx, cy + dp(3f), glyphPaint)
                micFill.color = errorColor
                canvas.drawCircle(cx, cy + dp(10f), dp(1.8f), micFill)
            }
        }
    }

    private fun drawPulseRing(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        val expansion = (phase * 2f) % 1f
        val ringRadius = radius + dp(2f) + expansion * dp(7f)
        ringPaint.color = Color.argb(
            ((1f - expansion) * 150).toInt(),
            Color.red(color), Color.green(color), Color.blue(color)
        )
        ringPaint.strokeWidth = dp(2f)
        canvas.drawCircle(cx, cy, ringRadius, ringPaint)
        ringPaint.strokeWidth = dp(2.4f)
    }

    private fun drawMic(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        micFill.color = color
        val capsuleWidth = dp(10f)
        val capsuleTop = cy - dp(15f)
        val capsuleBottom = cy + dp(2f)
        canvas.drawRoundRect(
            RectF(cx - capsuleWidth / 2f, capsuleTop, cx + capsuleWidth / 2f, capsuleBottom),
            capsuleWidth / 2f, capsuleWidth / 2f, micFill
        )
        micStroke.color = color
        val cradle = RectF(cx - dp(9f), cy - dp(15f), cx + dp(9f), cy + dp(4f))
        canvas.drawArc(cradle, 0f, 180f, false, micStroke)
        canvas.drawLine(cx, cy + dp(4f), cx, cy + dp(9f), micStroke)
        canvas.drawLine(cx - dp(6f), cy + dp(9f), cx + dp(6f), cy + dp(9f), micStroke)
    }

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
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onTap?.invoke()
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(holdRunnable)
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
