package com.flowbubble.app.overlay.companion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The character's brain. Everything in this file is pure Kotlin — no Android types, no Canvas, no
 * colour constants — so the whole personality of the companion can be reasoned about and unit tested
 * on the JVM. [CompanionRenderer] consumes a [CompanionFrame] and turns it into pixels.
 *
 * Time is always supplied by the caller (`SystemClock.uptimeMillis()` in production, a literal in
 * tests) so every channel is a deterministic function of time rather than a hidden clock.
 */

/** The five observable moods of behaviour the bubble can be in. */
enum class CompanionState { Idle, Listening, Thinking, Happy, Worried }

/** The overlay-able expression layer. Independent from [CompanionState]: a worried thinking face, a sleepy idle face. */
enum class CompanionMood { Neutral, Happy, Curious, Sleepy, Surprised }

/** Which Ink palette family the drawing should pull from. Kept as an index so the renderer pre-bakes shaders. */
enum class CompanionTint { Calm, Record, Focus, Success, Error }

/** Every cadence the character moves to, in one place so the idle loop stays boring-cheap. */
object CompanionTiming {
    const val BobPeriodMs = 4600L
    const val BreathPeriodMs = 5600L
    const val BlinkIntervalMs = 4200L
    const val BlinkDurationMs = 190L
    const val GazePeriodMs = 9700L
    const val GazeHoldMs = 900L
    const val SwayPeriodMs = 7300L
    const val RingPeriodMs = 1350L
    const val OrbitPeriodMs = 2300L
    const val WigglePeriodMs = 1500L
    const val FlutterPeriodMs = 620L
    const val HappyFullRateMs = 1100L
    const val HappyBounceMs = 900L
    const val BadgeHoldMs = 1400L
    const val BadgeFadeMs = 700L
    const val AmpSmoothingMs = 70L
    const val AmpHoldMs = 90L
    const val AmpReleaseMs = 320L
    const val MaxFrameDeltaMs = 64L
    const val FullRateTickMs = 16L
    const val BlinkTickMs = 32L
    const val IdleTickMs = 125L
    const val PokeMs = 900L

    const val MinStage = 1
    const val MaxStage = 4
    const val DefaultStage = 2
}

/** Small numeric helpers that all refuse to produce NaN, infinity or out-of-range values. */
object CompanionMath {

    fun unit(value: Float): Float =
        if (value.isNaN() || value.isInfinite()) 0f else value.coerceIn(0f, 1f)

    fun signed(value: Float): Float =
        if (value.isNaN() || value.isInfinite()) 0f else value.coerceIn(-1f, 1f)

    fun between(value: Float, from: Float, to: Float): Float =
        if (value.isNaN() || value.isInfinite()) from else value.coerceIn(from, to)

    fun mix(from: Float, to: Float, t: Float): Float = from + (to - from) * unit(t)

    fun smooth(t: Float): Float {
        val x = unit(t)
        return x * x * (3f - 2f * x)
    }

    fun time(nowMs: Long): Long = if (nowMs < 0L) 0L else nowMs

    fun cycle(nowMs: Long, periodMs: Long): Long = time(nowMs) / periodMs.coerceAtLeast(1L)

    fun local(nowMs: Long, periodMs: Long): Long {
        val period = periodMs.coerceAtLeast(1L)
        return time(nowMs) - (time(nowMs) / period) * period
    }

    /** Smooth -1..1 sine of wall-clock time. */
    fun wave(nowMs: Long, periodMs: Long, offset: Float = 0f): Float =
        sin(2.0 * PI * ((local(nowMs, periodMs).toDouble() / periodMs.coerceAtLeast(1L)) + offset)).toFloat()

    /** 0..1 ramp that wraps, used for orbits and expanding rings. */
    fun ramp(nowMs: Long, periodMs: Long): Float = unit(local(nowMs, periodMs).toFloat() / periodMs.coerceAtLeast(1L))

    /**
     * Deterministic 0..1 hash. Idle motion needs irregularity — a metronome blink looks broken —
     * but it must stay reproducible so the tests can pin the cadence down.
     */
    fun hash01(index: Long): Float {
        var x = (index * 2654435761L) xor 0x9E3779B9L
        x = x xor (x ushr 14)
        x *= 1597334677L
        x = x xor (x ushr 11)
        return ((x ushr 16) and 0xFFFFL).toFloat() / 65535f
    }
}

/** Time-shaped channels: the slow body motion, the blink schedule, the glances, the orbits. */
object CompanionChannels {

    fun bob(nowMs: Long): Float = CompanionMath.wave(nowMs, CompanionTiming.BobPeriodMs)

    fun breath(nowMs: Long): Float = CompanionMath.wave(nowMs, CompanionTiming.BreathPeriodMs, 0.22f)

    fun sway(nowMs: Long): Float = CompanionMath.wave(nowMs, CompanionTiming.SwayPeriodMs, 0.4f) * 0.5f

    fun wiggle(nowMs: Long): Float =
        CompanionMath.wave(nowMs, CompanionTiming.WigglePeriodMs) * 0.85f +
            CompanionMath.wave(nowMs, CompanionTiming.WigglePeriodMs / 3L, 0.31f) * 0.15f

    fun flutter(nowMs: Long): Float = CompanionMath.wave(nowMs, CompanionTiming.FlutterPeriodMs)

    fun ringProgress(nowMs: Long): Float = CompanionMath.ramp(nowMs, CompanionTiming.RingPeriodMs)

    fun orbit(nowMs: Long): Float = CompanionMath.ramp(nowMs, CompanionTiming.OrbitPeriodMs)

    /** Where inside its cycle blink number [cycle] starts. */
    fun blinkStartMs(cycle: Long): Long {
        val slack = (CompanionTiming.BlinkIntervalMs - CompanionTiming.BlinkDurationMs - 240L).coerceAtLeast(0L)
        val windowStart = (slack * 0.30f).toLong()
        val jitter = (CompanionMath.hash01(cycle) * slack * 0.4f).toLong()
        return windowStart + jitter
    }

    /** How shut this blink gets — some are lazy half-blinks, which stops the loop looking mechanical. */
    fun blinkDepth(cycle: Long): Float =
        CompanionMath.between(0.62f + CompanionMath.hash01(cycle xor 77L) * 0.6f, 0f, 1f)

    /** Time inside cycle [cycle] where the eyelids are fully down. */
    fun blinkPeakMs(cycle: Long): Long =
        CompanionTiming.BlinkIntervalMs * cycle + blinkStartMs(cycle) + CompanionTiming.BlinkDurationMs / 2L

    /** 0 = eyes wide open, 1 = eyes fully shut. At most one blink per [CompanionTiming.BlinkIntervalMs]. */
    fun blinkAt(nowMs: Long): Float {
        val cycle = CompanionMath.cycle(nowMs, CompanionTiming.BlinkIntervalMs)
        val local = CompanionMath.local(nowMs, CompanionTiming.BlinkIntervalMs)
        val start = blinkStartMs(cycle)
        val t = local - start
        if (t < 0L || t >= CompanionTiming.BlinkDurationMs) return 0f
        val half = CompanionTiming.BlinkDurationMs / 2f
        val shape = if (t < half) {
            CompanionMath.smooth(t / half)
        } else {
            1f - CompanionMath.smooth((t - half) / half)
        }
        return CompanionMath.unit(blinkDepth(cycle) * shape)
    }

    fun blinkActive(nowMs: Long): Boolean = blinkAt(nowMs) > 0.01f

    /** Milliseconds until the next blink actually begins; used to keep the idle loop honest. */
    fun nextBlinkInMs(nowMs: Long): Long {
        val cycle = CompanionMath.cycle(nowMs, CompanionTiming.BlinkIntervalMs)
        val local = CompanionMath.local(nowMs, CompanionTiming.BlinkIntervalMs)
        val start = blinkStartMs(cycle)
        return when {
            local < start -> start - local
            local < start + CompanionTiming.BlinkDurationMs -> 0L
            else -> (CompanionTiming.BlinkIntervalMs - local) + blinkStartMs(cycle + 1L)
        }
    }

    /** Glance left/right and back. 0 for most of the cycle so idle is mostly still. */
    fun gazeX(nowMs: Long): Float {
        val cycle = CompanionMath.cycle(nowMs, CompanionTiming.GazePeriodMs)
        val local = CompanionMath.local(nowMs, CompanionTiming.GazePeriodMs)
        if (local > CompanionTiming.GazeHoldMs) return 0f
        val direction = CompanionMath.hash01(cycle xor 1332L) * 2f - 1f
        val envelope = sin(PI * (local.toFloat() / CompanionTiming.GazeHoldMs)).toFloat()
        return CompanionMath.signed(direction * envelope)
    }

    fun gazeY(nowMs: Long): Float = CompanionMath.wave(nowMs, CompanionTiming.GazePeriodMs / 2L, 0.15f) * 0.35f

    /** Celebration shape for the READY state: two decaying hops, 0 outside the window. */
    fun bounce(sinceEnterMs: Long): Float {
        if (sinceEnterMs < 0L) return 0f
        val x = CompanionMath.unit(sinceEnterMs.toFloat() / CompanionTiming.HappyBounceMs)
        if (x <= 0f) return 0f
        val hops = abs(sin(PI * x * 2.0)).toFloat()
        return CompanionMath.unit(hops * (1f - x).pow(1.3f))
    }

    /** The tick that says "the task landed" stays a while then fades instead of popping off. */
    fun badge(sinceEnterMs: Long): Float {
        if (sinceEnterMs < 0L) return 0f
        return when {
            sinceEnterMs <= CompanionTiming.BadgeHoldMs -> 1f
            sinceEnterMs >= CompanionTiming.BadgeHoldMs + CompanionTiming.BadgeFadeMs -> 0f
            else -> 1f - CompanionMath.smooth(
                (sinceEnterMs - CompanionTiming.BadgeHoldMs).toFloat() / CompanionTiming.BadgeFadeMs
            )
        }
    }
}

/** state x mood x stage x amplitude  ->  the actual visual numbers. All of these clamp. */
object CompanionLook {

    fun clampStage(stage: Int): Int = stage.coerceIn(CompanionTiming.MinStage, CompanionTiming.MaxStage)

    /** Tolerant parse so a persisted preference can never crash the overlay. */
    fun moodNamed(raw: String?): CompanionMood = when (raw?.trim()?.lowercase()) {
        "happy" -> CompanionMood.Happy
        "curious" -> CompanionMood.Curious
        "sleepy" -> CompanionMood.Sleepy
        "surprised" -> CompanionMood.Surprised
        else -> CompanionMood.Neutral
    }

    fun tintFor(state: CompanionState, mood: CompanionMood): CompanionTint = when (state) {
        CompanionState.Listening -> CompanionTint.Record
        CompanionState.Thinking -> CompanionTint.Focus
        CompanionState.Happy -> CompanionTint.Success
        CompanionState.Worried -> CompanionTint.Error
        CompanionState.Idle -> if (mood == CompanionMood.Happy) CompanionTint.Success else CompanionTint.Calm
    }

    /**
     * Which rendered face the art layer shows for this state and expression.
     *
     * The four working states own the face because they are what the user is waiting on — a worried
     * bubble must not look merely curious. Only the resting face is free to be coloured by mood, so a
     * sleepy or startled companion still reads as itself while nothing is happening.
     */
    fun spritePose(state: CompanionState, mood: CompanionMood): SpritePose = when (state) {
        CompanionState.Worried -> SpritePose.Worried
        CompanionState.Listening -> SpritePose.Listening
        CompanionState.Thinking -> SpritePose.Thinking
        CompanionState.Happy -> SpritePose.Happy
        CompanionState.Idle -> when (mood) {
            CompanionMood.Sleepy -> SpritePose.Sleepy
            CompanionMood.Surprised -> SpritePose.Surprised
            CompanionMood.Curious -> SpritePose.Listening
            CompanionMood.Happy -> SpritePose.Happy
            CompanionMood.Neutral -> SpritePose.Idle
        }
    }

    /** Base lid openness before blink. 1 is a normal eye, above 1 is a startled wide eye. */
    fun eyeOpenBase(state: CompanionState, mood: CompanionMood, stage: Int): Float {
        val byState = when (state) {
            CompanionState.Idle -> 0.86f
            CompanionState.Listening -> 1.06f
            CompanionState.Thinking -> 0.18f
            CompanionState.Happy -> 0.42f
            CompanionState.Worried -> 0.72f
        }
        val byMood = when (mood) {
            CompanionMood.Neutral -> 1f
            CompanionMood.Happy -> 0.88f
            CompanionMood.Curious -> 1.14f
            CompanionMood.Sleepy -> 0.3f
            CompanionMood.Surprised -> 1.34f
        }
        // A stage-1 blob sleeps with its eyes shut, but wakes up the moment it is talked to.
        val byStage = if (stage <= CompanionTiming.MinStage && state == CompanionState.Idle) 0.08f else 1f
        return CompanionMath.between(byState * byMood * byStage, 0f, 1.4f)
    }

    fun happyArc(state: CompanionState, mood: CompanionMood): Float = when {
        state == CompanionState.Happy -> 1f
        state == CompanionState.Thinking && mood == CompanionMood.Sleepy -> 0.7f
        state == CompanionState.Idle && mood == CompanionMood.Happy -> 0.85f
        else -> 0f
    }

    fun mouthCurve(state: CompanionState, mood: CompanionMood): Float {
        val byState = when (state) {
            CompanionState.Idle -> 0.34f
            CompanionState.Listening -> 0.12f
            CompanionState.Thinking -> 0.16f
            CompanionState.Happy -> 0.95f
            CompanionState.Worried -> -0.7f
        }
        val byMood = when (mood) {
            CompanionMood.Neutral -> 0f
            CompanionMood.Happy -> 0.3f
            CompanionMood.Curious -> 0.12f
            CompanionMood.Sleepy -> -0.12f
            CompanionMood.Surprised -> -0.05f
        }
        return CompanionMath.signed(byState + byMood)
    }

    fun mouthOpen(state: CompanionState, mood: CompanionMood, speaking: Boolean, level: Float): Float {
        if (speaking) {
            val talk = CompanionMath.unit(level)
            return CompanionMath.between(0.16f + talk * 0.72f, 0f, 1f)
        }
        val base = when (state) {
            CompanionState.Idle -> 0.04f
            CompanionState.Listening -> 0.1f
            CompanionState.Thinking -> 0.06f
            CompanionState.Happy -> 0.42f
            CompanionState.Worried -> 0.24f
        }
        val moodBias = if (mood == CompanionMood.Surprised) 0.4f else 0f
        return CompanionMath.unit(base + moodBias)
    }

    /** Loud sounds push the pupils wider and the ring further out. Never outside 0..1 in, never wild out. */
    fun pupilScale(amplitude: Float): Float =
        CompanionMath.between(0.92f + CompanionMath.unit(amplitude) * 0.34f, 0.8f, 1.4f)

    fun ringRadius(progress: Float, amplitude: Float): Float {
        val amp = CompanionMath.unit(amplitude)
        return 0.98f + CompanionMath.unit(progress) * (0.14f + amp * 0.12f)
    }

    fun ringAlpha(progress: Float, amplitude: Float): Float {
        val amp = CompanionMath.unit(amplitude)
        return CompanionMath.unit((1f - CompanionMath.unit(progress)).pow(1.5f) * (0.34f + amp * 0.62f))
    }

    fun glowFor(state: CompanionState, mood: CompanionMood, stage: Int, amplitude: Float): Float {
        val amp = CompanionMath.unit(amplitude)
        val byState = when (state) {
            CompanionState.Idle -> 0.2f
            CompanionState.Listening -> 0.44f + amp * 0.46f
            CompanionState.Thinking -> 0.34f
            CompanionState.Happy -> 0.42f
            CompanionState.Worried -> 0.16f
        }
        val byStage = (clampStage(stage) - CompanionTiming.MinStage) * 0.05f
        val byMood = if (mood == CompanionMood.Sleepy) -0.07f else 0f
        return CompanionMath.between(byState + byStage + byMood, 0f, 1f)
    }

    /** -1 leans back, +1 leans toward the user. */
    fun leanFor(state: CompanionState, amplitude: Float): Float = when (state) {
        CompanionState.Listening -> CompanionMath.between(0.45f + CompanionMath.unit(amplitude) * 0.5f, 0f, 1f)
        CompanionState.Thinking -> -0.22f
        CompanionState.Happy -> 0.18f
        CompanionState.Worried -> -0.34f
        CompanionState.Idle -> 0f
    }

    fun antennaDroop(state: CompanionState, mood: CompanionMood): Float = when {
        state == CompanionState.Worried -> 1f
        state == CompanionState.Happy -> 0.08f
        mood == CompanionMood.Sleepy -> 0.78f
        state == CompanionState.Idle -> 0.16f
        else -> 0f
    }

    fun antennaPerk(state: CompanionState, mood: CompanionMood): Float = when {
        state == CompanionState.Listening -> 0.85f
        mood == CompanionMood.Curious -> 1f
        state == CompanionState.Happy -> 0.55f
        mood == CompanionMood.Surprised -> 0.8f
        else -> 0.2f
    }

    fun sparkCount(state: CompanionState, stage: Int): Int = when {
        state == CompanionState.Thinking -> min(4, clampStage(stage) + 1)
        state == CompanionState.Happy && clampStage(stage) >= 3 -> 3
        clampStage(stage) >= CompanionTiming.MaxStage -> 1
        else -> 0
    }

    fun haloFor(state: CompanionState, stage: Int, flutter: Float): Float {
        if (clampStage(stage) < CompanionTiming.MaxStage) return 0f
        val pulse = 0.72f + 0.28f * CompanionMath.unit((flutter + 1f) / 2f)
        return CompanionMath.unit(when (state) {
            CompanionState.Thinking -> pulse
            CompanionState.Happy -> pulse
            else -> pulse * 0.55f
        })
    }

    fun feetFor(stage: Int): Float = if (clampStage(stage) >= 3) 1f else 0f

    fun bodyScaleFor(stage: Int): Float = when (clampStage(stage)) {
        1 -> 0.72f
        2 -> 0.84f
        3 -> 0.94f
        else -> 1.02f
    }

    fun eyeScaleFor(stage: Int): Float = when (clampStage(stage)) {
        1 -> 1.12f
        2 -> 1.04f
        3 -> 0.98f
        else -> 0.92f
    }

    fun flushFor(state: CompanionState, amplitude: Float, flutter: Float): Float {
        val amp = CompanionMath.unit(amplitude)
        val breathe = ((flutter + 1f) / 2f) * 0.14f
        return CompanionMath.unit(when (state) {
            CompanionState.Worried -> 0.52f + breathe
            CompanionState.Listening -> 0.26f + amp * 0.3f
            CompanionState.Thinking -> 0.08f
            else -> 0f
        })
    }

    /** How much the whole character dims. Sleepy and worried sink into the disc. */
    fun dimFor(state: CompanionState, mood: CompanionMood): Float = CompanionMath.unit(
        when (state) {
            CompanionState.Worried -> 0.22f
            CompanionState.Thinking -> 0.06f
            else -> 0f
        } + if (mood == CompanionMood.Sleepy) 0.34f else 0f
    )

    /** 0 = staring straight at you, 1 = looking away/up. */
    fun gazeLockFor(state: CompanionState): Float = when (state) {
        CompanionState.Listening -> 0f
        CompanionState.Thinking -> 0.85f
        CompanionState.Worried -> 0.55f
        else -> 0f
    }
}

/**
 * A single snapshot of the character. Mutable and reused on purpose: the overlay draws 60 times a
 * second for hours, so the frame object is allocated once by [CompanionController] and refilled in
 * place on every frame.
 *
 * Ranges are documented per field and enforced by the controller. Every position-ish value is a
 * fraction of the bubble radius so the whole creature scales with the overlay window.
 */
class CompanionFrame {
    var tint: CompanionTint = CompanionTint.Calm
    /** What the art layer needs to pick a face; the scalars below are what the vector layer needs. */
    var state: CompanionState = CompanionState.Idle
    var mood: CompanionMood = CompanionMood.Neutral
    var bob: Float = 0f            // -1..1 vertical drift
    var breath: Float = 0f         // -1..1 squash and stretch
    var sway: Float = 0f           // -1..1 side drift
    var wiggle: Float = 0f         // -1..1 fast wriggle
    var blink: Float = 0f          // 0 open .. 1 shut
    var eyeOpen: Float = 0f        // 0..1.4 lid openness
    var happyArc: Float = 0f       // 0..1 blend toward ^-shaped happy eyes
    var gazeX: Float = 0f          // -1..1
    var gazeY: Float = 0f          // -1..1
    var pupilScale: Float = 1f     // 0.8..1.4
    var eyeScale: Float = 1f       // 0.8..1.2
    var mouthCurve: Float = 0f     // -1 frown .. 1 smile
    var mouthOpen: Float = 0f      // 0..1
    var tilt: Float = 0f           // -1..1 head tilt
    var lean: Float = 0f           // -1..1 forward lean
    var antennaDroop: Float = 0f   // 0..1
    var antennaPerk: Float = 0f    // 0..1
    var aura: Float = 0f           // 0..1
    var flush: Float = 0f          // 0..1
    var dim: Float = 0f            // 0..1
    var ringProgress: Float = 0f   // 0..1
    var ringRadius: Float = 0f     // 0.98..1.24 of radius
    var ringAlpha: Float = 0f      // 0..1
    var orbit: Float = 0f          // 0..1
    var sparkCount: Int = 0        // 0..4
    var halo: Float = 0f           // 0..1
    var feet: Float = 0f           // 0..1
    var bounce: Float = 0f         // 0..1
    var badge: Float = 0f          // 0..1
    var bodyScale: Float = 1f      // 0.72..1.02
    var amplitude: Float = 0f      // 0..1 smoothed input level

    /**
     * Self-description of every animated channel with the range it must stay inside. Production code
     * never calls this; the JVM tests sweep it so a new channel cannot quietly escape its bounds.
     */
    fun channels(): List<Channel> = listOf(
        Channel("bob", bob, -1f, 1f),
        Channel("breath", breath, -1f, 1f),
        Channel("sway", sway, -1f, 1f),
        Channel("wiggle", wiggle, -1f, 1f),
        Channel("blink", blink, 0f, 1f),
        Channel("eyeOpen", eyeOpen, 0f, 1.4f),
        Channel("happyArc", happyArc, 0f, 1f),
        Channel("gazeX", gazeX, -1f, 1f),
        Channel("gazeY", gazeY, -1f, 1f),
        Channel("pupilScale", pupilScale, 0.8f, 1.4f),
        Channel("eyeScale", eyeScale, 0.8f, 1.2f),
        Channel("mouthCurve", mouthCurve, -1f, 1f),
        Channel("mouthOpen", mouthOpen, 0f, 1f),
        Channel("tilt", tilt, -1f, 1f),
        Channel("lean", lean, -1f, 1f),
        Channel("antennaDroop", antennaDroop, 0f, 1f),
        Channel("antennaPerk", antennaPerk, 0f, 1f),
        Channel("aura", aura, 0f, 1f),
        Channel("flush", flush, 0f, 1f),
        Channel("dim", dim, 0f, 1f),
        Channel("ringProgress", ringProgress, 0f, 1f),
        Channel("ringRadius", ringRadius, 0.98f, 1.24f),
        Channel("ringAlpha", ringAlpha, 0f, 1f),
        Channel("orbit", orbit, 0f, 1f),
        Channel("halo", halo, 0f, 1f),
        Channel("feet", feet, 0f, 1f),
        Channel("bounce", bounce, 0f, 1f),
        Channel("badge", badge, 0f, 1f),
        Channel("bodyScale", bodyScale, 0.72f, 1.02f),
        Channel("amplitude", amplitude, 0f, 1f),
        Channel("sparkCount", sparkCount.toFloat(), 0f, 4f)
    )

    /** [name] failed its range on the last frame. */
    data class Channel(val name: String, val value: Float, val min: Float, val max: Float) {
        fun inRange(): Boolean = !value.isNaN() && !value.isInfinite() && value >= min && value <= max
    }
}

/**
 * The state machine. Feed it state changes, an amplitude per audio frame and the wall clock, then
 * read [update] for the picture. Holds exactly one [CompanionFrame] so nothing is allocated per frame.
 */
class CompanionController(startState: CompanionState = CompanionState.Idle) {

    var state: CompanionState = startState
        private set
    var mood: CompanionMood = CompanionMood.Neutral
        private set
    var stage: Int = CompanionTiming.DefaultStage
        private set
    var speaking: Boolean = false
        private set

    /** The level the app reported before smoothing. */
    var targetAmplitude: Float = 0f
        private set

    /** The level the character actually draws with. */
    var smoothedAmplitude: Float = 0f
        private set

    var stateEnteredAtMs: Long = 0L
        private set

    private val frame = CompanionFrame()
    private var lastUpdateMs: Long = -1L
    private var lastAmplitudeMs: Long = 0L
    private var pokedMood: CompanionMood = CompanionMood.Neutral
    private var pokedUntilMs: Long = -1L

    /**
     * A short-lived expression that does not touch the persisted [mood] — used for the flinch when
     * the user taps or starts dragging the companion.
     */
    fun poke(nowMs: Long, expression: CompanionMood = CompanionMood.Surprised) {
        pokedMood = expression
        pokedUntilMs = CompanionMath.time(nowMs) + CompanionTiming.PokeMs
    }

    /** The expression actually on the face at [nowMs]: the poke wins while it lasts. */
    fun moodAt(nowMs: Long): CompanionMood =
        if (CompanionMath.time(nowMs) < pokedUntilMs) pokedMood else mood

    fun setState(next: CompanionState, nowMs: Long) {
        if (state == next) return
        state = next
        stateEnteredAtMs = CompanionMath.time(nowMs)
        if (next != CompanionState.Listening) {
            targetAmplitude = 0f
            lastAmplitudeMs = CompanionMath.time(nowMs)
        }
    }

    fun setMood(next: CompanionMood) {
        mood = next
    }

    fun setStage(next: Int) {
        stage = CompanionLook.clampStage(next)
    }

    fun setSpeaking(on: Boolean) {
        speaking = on
    }

    /** Values outside 0..1, NaN and infinities are all folded back into range here. */
    fun setAmplitude(value: Float, nowMs: Long) {
        targetAmplitude = CompanionMath.unit(value)
        val now = CompanionMath.time(nowMs)
        if (now >= lastAmplitudeMs) lastAmplitudeMs = now
    }

    fun reset(nowMs: Long) {
        state = CompanionState.Idle
        mood = CompanionMood.Neutral
        speaking = false
        pokedMood = CompanionMood.Neutral
        pokedUntilMs = -1L
        targetAmplitude = 0f
        smoothedAmplitude = 0f
        lastUpdateMs = -1L
        lastAmplitudeMs = CompanionMath.time(nowMs)
        stateEnteredAtMs = CompanionMath.time(nowMs)
    }

    /**
     * Recomputes every channel for [nowMs] and returns the shared frame. Call it once per draw; the
     * returned instance is the same object every time.
     */
    fun update(nowMs: Long): CompanionFrame {
        val now = CompanionMath.time(nowMs)
        val dt = if (lastUpdateMs < 0L) CompanionTiming.AmpSmoothingMs
        else (now - lastUpdateMs).coerceIn(0L, CompanionTiming.MaxFrameDeltaMs)
        lastUpdateMs = now

        val sinceEnter = now - stateEnteredAtMs
        val flutter = CompanionChannels.flutter(now)

        smoothAmplitude(dt, now)

        val amp = smoothedAmplitude
        val expression = moodAt(now)
        val eyeOpenBase = CompanionLook.eyeOpenBase(state, expression, stage)
        val happyArc = CompanionLook.happyArc(state, expression)
        val blink = if (state == CompanionState.Thinking && happyArc > 0.5f) {
            1f
        } else {
            CompanionChannels.blinkAt(now)
        }

        frame.tint = CompanionLook.tintFor(state, expression)
        frame.state = state
        frame.mood = expression
        frame.bob = CompanionMath.signed(CompanionChannels.bob(now))
        frame.breath = CompanionMath.signed(CompanionChannels.breath(now))
        frame.sway = CompanionMath.signed(CompanionChannels.sway(now))
        frame.wiggle = when (state) {
            CompanionState.Thinking -> CompanionMath.signed(CompanionChannels.wiggle(now))
            else -> 0f
        }
        frame.blink = CompanionMath.unit(1f - (1f - blink) * CompanionMath.unit(eyeOpenBase))
        frame.eyeOpen = CompanionMath.between(eyeOpenBase * (1f - blink), 0f, 1.4f)
        frame.happyArc = happyArc
        frame.gazeX = gazeX(now)
        frame.gazeY = gazeY(now)
        frame.pupilScale = when (state) {
            CompanionState.Listening -> CompanionLook.pupilScale(amp)
            else -> CompanionLook.pupilScale(0f)
        }
        frame.eyeScale = CompanionLook.eyeScaleFor(stage)
        frame.mouthCurve = CompanionLook.mouthCurve(state, expression)
        frame.mouthOpen = CompanionLook.mouthOpen(state, expression, speaking, amp)
        frame.tilt = tilt(now, expression)
        frame.lean = CompanionMath.signed(CompanionLook.leanFor(state, amp))
        frame.antennaDroop = CompanionLook.antennaDroop(state, expression)
        frame.antennaPerk = CompanionLook.antennaPerk(state, expression)
        frame.aura = CompanionLook.glowFor(state, expression, stage, amp)
        frame.flush = CompanionLook.flushFor(state, amp, flutter)
        frame.dim = CompanionLook.dimFor(state, expression)
        frame.ringProgress = if (state == CompanionState.Listening) CompanionChannels.ringProgress(now) else 0f
        frame.ringRadius = CompanionLook.ringRadius(frame.ringProgress, amp)
        frame.ringAlpha = CompanionLook.ringAlpha(frame.ringProgress, amp)
        frame.orbit = CompanionChannels.orbit(now)
        frame.sparkCount = CompanionLook.sparkCount(state, stage)
        frame.halo = CompanionLook.haloFor(state, stage, flutter)
        frame.feet = CompanionLook.feetFor(stage)
        frame.bounce = if (state == CompanionState.Happy) CompanionChannels.bounce(sinceEnter) else 0f
        frame.badge = if (state == CompanionState.Happy) CompanionChannels.badge(sinceEnter) else 0f
        frame.bodyScale = CompanionLook.bodyScaleFor(stage)
        frame.amplitude = amp
        return frame
    }

    /** Full frame rate is reserved for the states the user is actually watching. */
    fun isFullRate(nowMs: Long): Boolean {
        val sinceEnter = CompanionMath.time(nowMs) - stateEnteredAtMs
        return state == CompanionState.Listening ||
            state == CompanionState.Thinking ||
            speaking ||
            (state == CompanionState.Happy && sinceEnter in 0L..CompanionTiming.HappyFullRateMs)
    }

    /**
     * How long the idle loop may sleep before the next invalidate. Cheap by design: a slow bob only
     * needs 8 fps, a blink needs 30, and nothing here runs while the window is hidden.
     */
    fun tickDelayMs(nowMs: Long): Long = when {
        isFullRate(nowMs) -> CompanionTiming.FullRateTickMs
        CompanionChannels.blinkActive(nowMs) -> CompanionTiming.BlinkTickMs
        else -> max(CompanionTiming.BlinkTickMs, min(CompanionTiming.IdleTickMs, CompanionChannels.nextBlinkInMs(nowMs)))
    }

    private fun smoothAmplitude(dtMs: Long, nowMs: Long) {
        val age = (nowMs - lastAmplitudeMs).coerceAtLeast(0L)
        val release = if (age <= CompanionTiming.AmpHoldMs) 1f else {
            CompanionMath.unit(1f - (age - CompanionTiming.AmpHoldMs).toFloat() / CompanionTiming.AmpReleaseMs)
        }
        val effective = targetAmplitude * release
        val k = dtMs.toFloat() / (dtMs.toFloat() + CompanionTiming.AmpSmoothingMs)
        smoothedAmplitude = CompanionMath.unit(smoothedAmplitude + (effective - smoothedAmplitude) * k)
    }

    private fun gazeX(nowMs: Long): Float {
        val lock = CompanionLook.gazeLockFor(state)
        val idle = CompanionChannels.gazeX(nowMs)
        return CompanionMath.signed(
            when (state) {
                CompanionState.Listening -> idle * 0.1f
                CompanionState.Thinking -> -0.35f + idle * 0.2f * (1f - lock)
                CompanionState.Worried -> 0.25f + idle * 0.3f
                else -> idle
            }
        )
    }

    private fun gazeY(nowMs: Long): Float {
        val drift = CompanionChannels.gazeY(nowMs)
        return CompanionMath.signed(
            when (state) {
                CompanionState.Listening -> 0.12f + drift * 0.15f
                CompanionState.Thinking -> -0.75f + drift * 0.2f
                CompanionState.Worried -> 0.4f + drift * 0.2f
                CompanionState.Happy -> -0.1f + drift * 0.1f
                else -> drift
            }
        )
    }

    private fun tilt(nowMs: Long, expression: CompanionMood): Float {
        val curious = if (expression == CompanionMood.Curious) 0.45f else 0f
        val worried = if (state == CompanionState.Worried) -0.3f else 0f
        return CompanionMath.signed(
            CompanionChannels.sway(nowMs) * 0.5f + curious + worried +
                if (state == CompanionState.Thinking) CompanionChannels.wiggle(nowMs) * 0.18f else 0f
        )
    }
}
