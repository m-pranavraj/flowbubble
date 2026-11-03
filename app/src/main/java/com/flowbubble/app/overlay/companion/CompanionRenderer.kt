package com.flowbubble.app.overlay.companion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.flowbubble.app.ui.theme.Ink
import androidx.compose.ui.graphics.toArgb
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws one [CompanionFrame]: the rendered character when [CompanionSprites] holds the face this
 * state needs, and the vector creature built from the same frame when it does not.
 *
 * The overlay this runs inside lives for hours on top of every app, so the rule here is strict:
 * every [Paint], [Path], [RectF], [Matrix] and gradient is built once in the constructor and reused.
 * A frame only writes numbers into those objects — `path.reset()`, `paint.alpha = n`,
 * `shader.setLocalMatrix(m)` — and never allocates. The character art is decoded once by
 * [CompanionSprites] and drawn as it is; nothing here touches disk during a frame.
 */
class CompanionRenderer(
    private val density: Float,
    private val sprites: CompanionSprites? = null
) {

    private fun dp(value: Float) = value * density

    // ---- Ink palette, resolved once. -------------------------------------------------------
    private val lavender = Ink.Lavender.toArgb()
    private val lavenderDeep = Ink.LavenderDeep.toArgb()
    private val crimson = Ink.Crimson.toArgb()
    private val crimsonSoft = Ink.CrimsonSoft.toArgb()
    private val burgundy = Ink.Burgundy.toArgb()
    private val burgundyDeep = Ink.BurgundyDeep.toArgb()
    private val jade = Ink.Jade.toArgb()
    private val amber = Ink.Amber.toArgb()
    private val voidInk = Ink.Void.toArgb()
    private val onyx = Ink.Onyx.toArgb()
    private val smoke = Ink.Smoke.toArgb()
    private val veil = Ink.Veil.toArgb()
    private val mist = Ink.Mist.toArgb()
    private val whisper = Ink.Whisper.toArgb()

    /** Per-tint accents. Indexed by [CompanionTint.ordinal] so the draw loop never switches on enums. */
    private class Accent(val glow: Int, val rim: Int, val spark: Int, val disc: Int, val flush: Int)

    private val accents: Array<Accent>

    private fun mix(color: Int, other: Int, weight: Float): Int {
        val t = weight.coerceIn(0f, 1f)
        return Color.argb(
            255,
            (Color.red(color) + (Color.red(other) - Color.red(color)) * t).toInt(),
            (Color.green(color) + (Color.green(other) - Color.green(color)) * t).toInt(),
            (Color.blue(color) + (Color.blue(other) - Color.blue(color)) * t).toInt()
        )
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha.coerceIn(0f, 1f) * 255f).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun a(color: Int, alpha: Int): Int = Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    // ---- Shaders, one per tint, built at unit size and positioned by a matrix per frame. ----
    private val auraShaders: Array<RadialGradient>
    private val bodyShaders: Array<LinearGradient>
    private val glossShader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(a(mist, 150), a(mist, 0)),
        floatArrayOf(0f, 1f),
        Shader.TileMode.CLAMP
    )

    init {
        val tints = CompanionTint.values()
        accents = Array(tints.size) { Accent(lavender, lavender, mist, voidInk, crimson) }
        accents[CompanionTint.Calm.ordinal] =
            Accent(lavender, lavender, mist, mix(voidInk, onyx, 0.78f), crimsonSoft)
        accents[CompanionTint.Record.ordinal] =
            Accent(crimson, crimsonSoft, crimson, mix(voidInk, burgundyDeep, 0.86f), crimson)
        accents[CompanionTint.Focus.ordinal] =
            Accent(lavenderDeep, lavender, amber, mix(voidInk, veil, 0.86f), crimsonSoft)
        accents[CompanionTint.Success.ordinal] =
            Accent(jade, jade, mist, mix(voidInk, smoke, 0.8f), jade)
        accents[CompanionTint.Error.ordinal] =
            Accent(crimson, crimsonSoft, crimsonSoft, mix(voidInk, burgundy, 0.9f), crimson)

        val stops = floatArrayOf(0f, 0.42f, 1f)
        auraShaders = Array(tints.size) { index ->
            val glow = accents[index].glow
            RadialGradient(
                0f, 0f, 1f,
                intArrayOf(withAlpha(glow, 0.92f), withAlpha(glow, 0.34f), Color.TRANSPARENT),
                stops,
                Shader.TileMode.CLAMP
            )
        }
        bodyShaders = Array(tints.size) { index ->
            val top = if (index == CompanionTint.Record.ordinal || index == CompanionTint.Error.ordinal) {
                mix(lavender, crimsonSoft, 0.34f)
            } else {
                lavender
            }
            val bottom = if (index == CompanionTint.Success.ordinal) mix(lavenderDeep, jade, 0.4f) else lavenderDeep
            LinearGradient(0f, -1f, 0f, 1f, intArrayOf(top, bottom), null, Shader.TileMode.CLAMP)
        }
    }

    // ---- Reused geometry ---------------------------------------------------------------------
    private val matrix = Matrix()
    private val blob = Path()
    private val mouthPath = Path()
    private val antennaPath = Path()
    private val badgePath = Path().apply {
        moveTo(-dp(4.6f), dp(0.4f))
        lineTo(-dp(1.4f), dp(3.2f))
        lineTo(dp(4.8f), dp(-3.0f))
    }
    private val rBody = RectF()
    private val rBelly = RectF()
    private val rEyeL = RectF()
    private val rEyeR = RectF()
    private val rPupilL = RectF()
    private val rPupilR = RectF()
    private val rMouth = RectF()
    private val rFootL = RectF()
    private val rFootR = RectF()
    private val rBlushL = RectF()
    private val rBlushR = RectF()
    private val rHalo = RectF()

    // ---- Paints ------------------------------------------------------------------------------
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val flushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bellyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val lashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.7f)
        strokeCap = Paint.Cap.ROUND
    }
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.9f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val mouthFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val antennaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.1f)
        strokeCap = Paint.Cap.ROUND
    }
    private val antennaTipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
        strokeCap = Paint.Cap.ROUND
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.6f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val limbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val spriteRect = RectF()

    /** The art is cropped a hair wider than the disc so the baby's hand is not cut by the rim. */
    private val spriteSide = 1.06f

    /**
     * Draws the creature inside a circle of radius [radius] centred on ([cx], [cy]).
     * [frame] is the controller's shared instance; it is read, never mutated.
     */
    fun draw(canvas: Canvas, frame: CompanionFrame, cx: Float, cy: Float, radius: Float) {
        if (radius <= 0f) return
        val r = radius
        val accent = accents[frame.tint.ordinal]
        val bobPx = frame.bob * r * 0.045f
        val breath = frame.breath

        drawAura(canvas, cx, cy + bobPx, r, frame)
        // The rendered face brings its own dark plate, so the vector disc is only drawn behind it
        // when there is no art for this pose.
        val art = sprites?.bitmapFor(CompanionLook.spritePose(frame.state, frame.mood))
        if (art == null) drawDisc(canvas, cx, cy, r, accent, frame)
        drawRim(canvas, cx, cy, r, accent, frame)
        drawPulseRing(canvas, cx, cy, r, accent, frame)

        val leanPx = frame.lean * r * 0.05f
        val swayPx = frame.sway * r * 0.035f
        val bouncePx = -frame.bounce * r * 0.17f
        canvas.save()
        canvas.translate(cx + leanPx + swayPx, cy + bobPx * 1.6f + bouncePx)
        canvas.rotate(frame.tilt * 7f)

        if (art != null) {
            drawSprite(canvas, r, art, frame)
        } else {
            drawShadow(canvas, r, frame)
            drawFeet(canvas, r, frame)
            drawBlob(canvas, r, frame, breath)
            drawBelly(canvas, r, frame)
            drawGloss(canvas, r, frame)
            drawAntenna(canvas, r, frame, accent)
            drawEyes(canvas, r, frame)
            drawMouth(canvas, r, frame)
            drawBlush(canvas, r, frame)
        }
        drawHalo(canvas, r, frame, accent)
        drawSparks(canvas, r, frame, accent)
        drawBadge(canvas, r, frame)
        canvas.restore()
    }

    /**
     * The character art, wearing the same motion as the vector body: it leans, sways, bounces and
     * tilts with the frame, and breathes by stretching a couple of percent instead of redrawing.
     */
    private fun drawSprite(canvas: Canvas, r: Float, art: Bitmap, frame: CompanionFrame) {
        val stretch = 1f + frame.breath * 0.02f + frame.amplitude * 0.035f
        val squeeze = 1f - frame.breath * 0.015f
        val side = r * 2f * spriteSide
        canvas.scale(squeeze, stretch)
        spriteRect.set(-side / 2f, -side / 2f, side / 2f, side / 2f)
        spritePaint.alpha = (255 - frame.dim * 90f).toInt().coerceIn(120, 255)
        canvas.drawBitmap(art, null, spriteRect, spritePaint)
        spritePaint.alpha = 255
    }

    // ------------------------------------------------------------------------------------------
    // pieces
    // ------------------------------------------------------------------------------------------

    private fun drawAura(canvas: Canvas, cx: Float, cy: Float, r: Float, frame: CompanionFrame) {
        val strength = frame.aura
        if (strength <= 0.01f) return
        val radius = r * (1.04f + strength * 0.2f)
        val shader = auraShaders[frame.tint.ordinal]
        setUnitMatrix(shader, cx, cy, radius)
        auraPaint.shader = shader
        auraPaint.alpha = (110f + strength * 145f).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, radius, auraPaint)
        auraPaint.shader = null
    }

    private fun drawDisc(canvas: Canvas, cx: Float, cy: Float, r: Float, accent: Accent, frame: CompanionFrame) {
        // Solid dark plate: the bubble floats over arbitrary app content and needs its own contrast.
        discPaint.color = accent.disc
        canvas.drawCircle(cx, cy, r, discPaint)
        if (frame.flush > 0.01f) {
            flushPaint.color = withAlpha(accent.flush, frame.flush * 0.42f)
            canvas.drawCircle(cx, cy, r, flushPaint)
        }
    }

    private fun drawRim(canvas: Canvas, cx: Float, cy: Float, r: Float, accent: Accent, frame: CompanionFrame) {
        val alpha = (90 + (frame.aura * 120f + frame.amplitude * 60f)).toInt().coerceIn(60, 235)
        rimPaint.color = a(accent.rim, alpha)
        canvas.drawCircle(cx, cy, r - rimPaint.strokeWidth / 2f, rimPaint)
    }

    private fun drawPulseRing(canvas: Canvas, cx: Float, cy: Float, r: Float, accent: Accent, frame: CompanionFrame) {
        if (frame.ringAlpha <= 0.01f) return
        ringPaint.strokeWidth = dp(1.8f) + frame.amplitude * dp(1.4f)
        ringPaint.color = a(accent.glow, (frame.ringAlpha * 210f).toInt().coerceIn(0, 230))
        canvas.drawCircle(cx, cy, r * frame.ringRadius, ringPaint)
        // A second, half-phase ring so loud moments read as a throb instead of a spinner.
        val ghost = (frame.ringProgress + 0.5f) % 1f
        ringPaint.color = a(accent.rim, (frame.ringAlpha * 70f * (1f - ghost)).toInt().coerceIn(0, 140))
        canvas.drawCircle(cx, cy, r * CompanionLook.ringRadius(ghost, frame.amplitude), ringPaint)
        ringPaint.strokeWidth = dp(2f)
    }

    private fun drawShadow(canvas: Canvas, r: Float, frame: CompanionFrame) {
        val hy = r * 0.44f * frame.bodyScale
        shadowPaint.color = a(voidInk, 130)
        rBody.set(-hy * 0.72f, hy * 0.86f, hy * 0.72f, hy * 1.06f)
        canvas.drawOval(rBody, shadowPaint)
    }

    private fun drawFeet(canvas: Canvas, r: Float, frame: CompanionFrame) {
        if (frame.feet <= 0.01f) return
        val half = r * 0.115f * frame.bodyScale
        val top = r * 0.36f * frame.bodyScale
        val lift = frame.bounce * r * 0.05f
        limbPaint.color = a(mix(lavender, lavenderDeep, 0.45f), (frame.feet * 255f).toInt().coerceIn(0, 255))
        rFootL.set(-half * 1.5f - half * 0.35f, top - lift, -half * 0.55f + half * 0.35f, top + half * 0.62f - lift)
        rFootR.set(half * 0.55f - half * 0.35f, top - lift, half * 1.5f + half * 0.35f, top + half * 0.62f - lift)
        canvas.drawOval(rFootL, limbPaint)
        canvas.drawOval(rFootR, limbPaint)
    }

    private fun drawBlob(canvas: Canvas, r: Float, frame: CompanionFrame, breath: Float) {
        val hy = r * 0.44f * frame.bodyScale * (1f + breath * 0.045f)
        val wx = r * 0.40f * frame.bodyScale * (1f - breath * 0.04f)
        blob.reset()
        blob.moveTo(0f, -hy)
        blob.cubicTo(wx * 0.6f, -hy, wx, -hy * 0.26f, wx * 0.95f, hy * 0.2f)
        blob.cubicTo(wx * 0.9f, hy * 0.8f, wx * 0.52f, hy, 0f, hy)
        blob.cubicTo(-wx * 0.52f, hy, -wx * 0.9f, hy * 0.8f, -wx * 0.95f, hy * 0.2f)
        blob.cubicTo(-wx, -hy * 0.26f, -wx * 0.6f, -hy, 0f, -hy)
        blob.close()
        val shader = bodyShaders[frame.tint.ordinal]
        matrix.reset()
        matrix.setScale(wx, hy)
        shader.setLocalMatrix(matrix)
        bodyPaint.shader = shader
        bodyPaint.alpha = (255f - frame.dim * 90f).toInt().coerceIn(90, 255)
        canvas.drawPath(blob, bodyPaint)
        bodyPaint.shader = null
    }

    private fun drawBelly(canvas: Canvas, r: Float, frame: CompanionFrame) {
        val hy = r * 0.44f * frame.bodyScale
        val wx = r * 0.40f * frame.bodyScale
        bellyPaint.color = a(mist, (28f + frame.amplitude * 24f).toInt().coerceIn(0, 80))
        rBelly.set(-wx * 0.5f, hy * 0.02f, wx * 0.5f, hy * 0.74f)
        canvas.drawOval(rBelly, bellyPaint)
    }

    private fun drawGloss(canvas: Canvas, r: Float, frame: CompanionFrame) {
        val hy = r * 0.44f * frame.bodyScale
        glossPaint.alpha = (70f - frame.dim * 30f).toInt().coerceIn(20, 120)
        setUnitMatrix(glossShader, -hy * 0.3f, -hy * 0.44f, hy * 0.55f)
        glossPaint.shader = glossShader
        canvas.drawCircle(-hy * 0.3f, -hy * 0.44f, hy * 0.55f, glossPaint)
        glossPaint.shader = null
    }

    private fun drawAntenna(canvas: Canvas, r: Float, frame: CompanionFrame, accent: Accent) {
        val hy = r * 0.44f * frame.bodyScale
        val droop = frame.antennaDroop
        val perk = frame.antennaPerk
        val length = r * (0.19f + perk * 0.07f - droop * 0.03f)
        val baseX = frame.sway * r * 0.04f
        val baseY = -hy * 0.97f
        val tipX = baseX + droop * r * 0.2f + frame.sway * r * 0.05f
        val tipY = baseY - length * (1f - droop * 0.82f)
        antennaPath.reset()
        antennaPath.moveTo(baseX, baseY)
        antennaPath.quadTo(baseX + tipX * 0.3f, baseY - length * 0.62f, tipX, tipY)
        antennaPaint.color = a(mix(lavender, whisper, 0.25f), (200f - droop * 70f).toInt().coerceIn(80, 230))
        canvas.drawPath(antennaPath, antennaPaint)
        val tipRadius = r * (0.05f + perk * 0.012f)
        antennaTipPaint.color = a(accent.spark, 235)
        canvas.drawCircle(tipX, tipY, tipRadius, antennaTipPaint)
        glossPaint.alpha = (60f + perk * 90f).toInt().coerceIn(0, 160)
        setUnitMatrix(glossShader, tipX, tipY, tipRadius * 2.6f)
        glossPaint.shader = glossShader
        canvas.drawCircle(tipX, tipY, tipRadius * 2.6f, glossPaint)
        glossPaint.shader = null
    }

    private fun drawEyes(canvas: Canvas, r: Float, frame: CompanionFrame) {
        val hy = r * 0.44f * frame.bodyScale
        val wx = r * 0.40f * frame.bodyScale
        val openness = frame.eyeOpen
        val halfW = r * 0.118f * frame.eyeScale * (0.92f + min(openness, 1.2f) * 0.1f)
        val halfH = halfW * openness.coerceIn(0.07f, 1.3f)
        val eyeY = -hy * 0.08f + frame.gazeY * r * 0.028f
        val spread = wx * 0.42f
        val lashAlpha = (255f - frame.dim * 60f).toInt().coerceIn(120, 255)

        val arcAlpha = frame.happyArc
        if (arcAlpha > 0.01f) {
            lashPaint.color = a(voidInk, (arcAlpha * lashAlpha).toInt().coerceIn(0, 255))
            lashPaint.strokeWidth = dp(2.1f)
            val arcW = halfW * 1.05f
            val arcH = max(dp(1.6f), halfW * 0.82f)
            val top = eyeY - arcH
            rEyeL.set(-spread - arcW, top, -spread + arcW, eyeY + halfH * 0.2f)
            canvas.drawArc(rEyeL, 200f, 140f, false, lashPaint)
            rEyeR.set(spread - arcW, top, spread + arcW, eyeY + halfH * 0.2f)
            canvas.drawArc(rEyeR, 200f, 140f, false, lashPaint)
        }

        val openAlpha = (1f - arcAlpha)
        if (openAlpha > 0.01f) {
            val alpha = (openAlpha * lashAlpha).toInt().coerceIn(0, 255)
            eyePaint.color = a(voidInk, alpha)
            rEyeL.set(-spread - halfW, eyeY - halfH, -spread + halfW, eyeY + halfH)
            rEyeR.set(spread - halfW, eyeY - halfH, spread + halfW, eyeY + halfH)
            canvas.drawOval(rEyeL, eyePaint)
            canvas.drawOval(rEyeR, eyePaint)

            val pupilR = halfW * 0.62f * frame.pupilScale
            val px = frame.gazeX * halfW * 0.32f
            val py = frame.gazeY * halfH * 0.34f
            pupilPaint.color = a(mix(voidInk, lavenderDeep, 0.28f), alpha)
            rPupilL.set(-spread + px - pupilR, eyeY + py - pupilR * 1.05f, -spread + px + pupilR, eyeY + py + pupilR * 1.05f)
            rPupilR.set(spread + px - pupilR, eyeY + py - pupilR * 1.05f, spread + px + pupilR, eyeY + py + pupilR * 1.05f)
            canvas.drawOval(rPupilL, pupilPaint)
            canvas.drawOval(rPupilR, pupilPaint)

            val glintR = max(dp(0.7f), pupilR * 0.36f)
            glintPaint.color = a(mist, (alpha * 0.9f).toInt().coerceIn(0, 230))
            canvas.drawCircle(-spread + px - pupilR * 0.45f, eyeY + py - pupilR * 0.5f, glintR, glintPaint)
            canvas.drawCircle(spread + px - pupilR * 0.45f, eyeY + py - pupilR * 0.5f, glintR, glintPaint)
            canvas.drawCircle(-spread + px + pupilR * 0.4f, eyeY + py + pupilR * 0.42f, glintR * 0.5f, glintPaint)
            canvas.drawCircle(spread + px + pupilR * 0.4f, eyeY + py + pupilR * 0.42f, glintR * 0.5f, glintPaint)
        }

        // Half-closed lids read as "sleepy" far better than a squashed circle alone.
        if (frame.blink > 0.35f && openAlpha > 0.01f) {
            lashPaint.strokeWidth = dp(1.6f)
            lashPaint.color = a(mix(lavender, whisper, 0.5f), (frame.blink * 150f * openAlpha).toInt().coerceIn(0, 200))
            canvas.drawLine(-spread - halfW, eyeY - halfH * 0.9f, -spread + halfW, eyeY - halfH * 0.5f, lashPaint)
            canvas.drawLine(spread - halfW, eyeY - halfH * 0.5f, spread + halfW, eyeY - halfH * 0.9f, lashPaint)
        }
    }

    private fun drawMouth(canvas: Canvas, r: Float, frame: CompanionFrame) {
        val hy = r * 0.44f * frame.bodyScale
        val y = hy * 0.34f
        val curve = frame.mouthCurve
        val half = r * 0.088f * (1f + abs(curve) * 0.3f)
        val open = frame.mouthOpen
        val ink = a(mix(voidInk, burgundy, 0.3f), (235f - frame.dim * 70f).toInt().coerceIn(120, 255))

        if (open < 0.62f) {
            mouthPaint.color = ink
            mouthPath.reset()
            mouthPath.moveTo(-half, y - open * r * 0.02f)
            mouthPath.quadTo(0f, y + curve * r * 0.11f + open * r * 0.04f, half, y - open * r * 0.02f)
            mouthPaint.alpha = ((1f - open / 0.62f) * 235f).toInt().coerceIn(0, 255)
            canvas.drawPath(mouthPath, mouthPaint)
        }
        if (open > 0.08f) {
            val w = half * (1f - open * 0.2f)
            val h = r * 0.03f + open * r * 0.075f
            mouthFillPaint.color = ink
            mouthFillPaint.alpha = (min(1f, open / 0.5f) * 230f).toInt().coerceIn(0, 255)
            rMouth.set(-w, y - h * 0.35f, w, y + h)
            canvas.drawOval(rMouth, mouthFillPaint)
            // Tiny tongue for the celebratory open smile.
            if (open > 0.5f && curve > 0f) {
                mouthFillPaint.color = a(crimsonSoft, 210)
                rMouth.set(-w * 0.5f, y + h * 0.25f, w * 0.5f, y + h * 0.95f)
                canvas.drawOval(rMouth, mouthFillPaint)
            }
        }
    }

    private fun drawBlush(canvas: Canvas, r: Float, frame: CompanionFrame) {
        val hy = r * 0.44f * frame.bodyScale
        val wx = r * 0.40f * frame.bodyScale
        val strength = 0.24f + frame.happyArc * 0.4f + frame.amplitude * 0.24f + frame.flush * 0.3f
        blushPaint.color = withAlpha(crimsonSoft, strength)
        val rx = r * 0.07f
        val ry = r * 0.045f
        rBlushL.set(-wx * 0.78f - rx, hy * 0.12f - ry, -wx * 0.78f + rx, hy * 0.12f + ry)
        rBlushR.set(wx * 0.78f - rx, hy * 0.12f - ry, wx * 0.78f + rx, hy * 0.12f + ry)
        canvas.drawOval(rBlushL, blushPaint)
        canvas.drawOval(rBlushR, blushPaint)
    }

    private fun drawHalo(canvas: Canvas, r: Float, frame: CompanionFrame, accent: Accent) {
        if (frame.halo <= 0.01f) return
        val hy = r * 0.44f * frame.bodyScale
        val rx = r * 0.24f
        val ry = r * 0.062f
        val cy = -hy * 1.32f
        haloPaint.color = a(accent.spark, (frame.halo * 210f).toInt().coerceIn(0, 235))
        rHalo.set(-rx, cy - ry, rx, cy + ry)
        canvas.drawOval(rHalo, haloPaint)
        haloPaint.color = a(mist, (frame.halo * 90f).toInt().coerceIn(0, 120))
        canvas.drawOval(rHalo, haloPaint)
    }

    private fun drawSparks(canvas: Canvas, r: Float, frame: CompanionFrame, accent: Accent) {
        val count = frame.sparkCount
        if (count <= 0) return
        val orbitRx = r * 0.63f
        val orbitRy = r * 0.3f
        val centreY = -r * 0.06f
        for (i in 0 until count) {
            val angle = ((frame.orbit + i.toFloat() / count) * 2.0 * PI).toFloat()
            val x = cos(angle) * orbitRx
            val y = centreY + sin(angle) * orbitRy
            val depth = (sin(angle) + 1f) * 0.5f
            val size = r * (0.021f + depth * 0.016f)
            sparkPaint.color = a(accent.spark, (90 + depth * 150f).toInt().coerceIn(0, 245))
            canvas.drawCircle(x, y, size, sparkPaint)
            glossPaint.alpha = (depth * 60f).toInt().coerceIn(0, 90)
            setUnitMatrix(glossShader, x, y, size * 3f)
            glossPaint.shader = glossShader
            canvas.drawCircle(x, y, size * 3f, glossPaint)
            glossPaint.shader = null
        }
    }

    private fun drawBadge(canvas: Canvas, r: Float, frame: CompanionFrame) {
        if (frame.badge <= 0.01f) return
        val scale = r / dp(30f)
        canvas.save()
        canvas.translate(r * 0.52f, -r * 0.56f)
        canvas.scale(scale, scale)
        badgePaint.color = a(jade, (frame.badge * 245f).toInt().coerceIn(0, 255))
        canvas.drawPath(badgePath, badgePaint)
        canvas.restore()
    }

    /** Maps the unit shader (radius 1 at the origin) onto a circle without allocating. */
    private fun setUnitMatrix(shader: Shader, cx: Float, cy: Float, radius: Float) {
        matrix.reset()
        matrix.setScale(max(0.001f, radius), max(0.001f, radius))
        matrix.postTranslate(cx, cy)
        shader.setLocalMatrix(matrix)
    }
}
