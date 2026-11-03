package com.flowbubble.app.overlay

import com.flowbubble.app.overlay.companion.CompanionController
import com.flowbubble.app.overlay.companion.CompanionFrame
import com.flowbubble.app.overlay.companion.CompanionLook
import com.flowbubble.app.overlay.companion.CompanionMood
import com.flowbubble.app.overlay.companion.CompanionState
import com.flowbubble.app.overlay.companion.CompanionTint
import com.flowbubble.app.overlay.companion.CompanionTiming
import com.flowbubble.app.overlay.companion.SpritePose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The companion is a state machine over arithmetic channels, so everything a renderer would need can
 * be asserted without a Canvas. The important product guarantees here are: nothing leaves its range,
 * the idle loop stays cheap, and full frame rate is only spent where a human is looking.
 */
class CompanionControllerTest {

    private val controller = CompanionController()

    @Test
    fun it_starts_alive_and_ignores_a_repeat_state() {
        assertEquals(CompanionState.Idle, controller.state)
        assertEquals(CompanionMood.Neutral, controller.mood)
        assertEquals(CompanionTiming.DefaultStage, controller.stage)
        controller.setState(CompanionState.Idle, 5_000L)
        assertEquals(0L, controller.stateEnteredAtMs)
        controller.setState(CompanionState.Listening, 6_000L)
        assertEquals(CompanionState.Listening, controller.state)
        assertEquals(6_000L, controller.stateEnteredAtMs)
        controller.setState(CompanionState.Listening, 9_000L)
        assertEquals(6_000L, controller.stateEnteredAtMs)
    }

    @Test
    fun leaving_listening_drops_the_audio_level() {
        controller.setState(CompanionState.Listening, 1_000L)
        controller.setAmplitude(0.8f, 1_000L)
        assertEquals(0.8f, controller.targetAmplitude, 0.0001f)
        controller.update(1_016L)
        controller.setState(CompanionState.Thinking, 1_100L)
        assertEquals(0f, controller.targetAmplitude, 0.0001f)
    }

    @Test
    fun hostile_amplitude_values_are_folded_into_0_1() {
        controller.setState(CompanionState.Listening, 0L)
        for (raw in floatArrayOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -3f, -0.2f, 1.7f, 40f)) {
            controller.setAmplitude(raw, 10L)
            assertTrue("target ${controller.targetAmplitude} for input $raw",
                controller.targetAmplitude in 0f..1f)
        }
        controller.setAmplitude(Float.NaN, 10L)
        assertEquals(0f, controller.targetAmplitude, 0.0001f)
        controller.setAmplitude(2f, 10L)
        assertEquals(1f, controller.targetAmplitude, 0.0001f)
    }

    @Test
    fun the_drawn_level_rises_with_voice_and_falls_back_to_quiet() {
        controller.setState(CompanionState.Listening, 0L)
        var t = 0L
        repeat(40) {
            t += 16L
            controller.setAmplitude(1f, t)
            controller.update(t)
            assertTrue("smoothed ${controller.smoothedAmplitude} left 0..1", controller.smoothedAmplitude in 0f..1f)
        }
        val loud = controller.smoothedAmplitude
        assertTrue("a held shout should read as loud, was $loud", loud > 0.8f)
        repeat(60) {
            t += 16L
            controller.update(t)
        }
        val quiet = controller.smoothedAmplitude
        assertTrue("the ring should relax once the mic goes quiet, was $quiet", quiet < 0.05f)
    }

    @Test
    fun listening_leans_in_and_the_ring_answers_the_volume() {
        controller.setState(CompanionState.Listening, 200L)
        var t = 200L
        repeat(30) {
            t += 16L
            controller.setAmplitude(0.9f, t)
            controller.update(t)
        }
        val frame = controller.update(t + 16L)
        val ringAlpha = frame.ringAlpha
        val radius = frame.ringRadius
        val pupils = frame.pupilScale
        val lean = frame.lean
        val eyes = frame.eyeOpen
        assertTrue("ring alpha $ringAlpha should be visible while listening", ringAlpha > 0.05f)
        assertTrue("a shout at the start of the pulse should light the ring up",
            CompanionLook.ringAlpha(0f, frame.amplitude) > 0.8f)
        assertTrue("ring radius $radius should sit just outside the disc", radius > 0.98f)
        assertTrue("pupils $pupils should widen when it is loud", pupils > 1.0f)
        assertTrue("lean $lean should tip toward the user", lean > 0.4f)
        assertTrue("eyes $eyes should be wide and attentive", eyes > 0.9f)
    }

    @Test
    fun thinking_looks_up_and_orbits_a_spark() {
        controller.setState(CompanionState.Thinking, 300L)
        val first = capture(controller.update(300L))
        val later = capture(controller.update(900L))
        assertTrue("thinking eyes should look up, gazeY was ${first.gazeY}", first.gazeY < -0.4f)
        assertTrue("thinking lids should mostly close, eyeOpen was ${first.eyeOpen}", first.eyeOpen < 0.3f)
        assertEquals(3, first.sparkCount)
        assertNotEquals(first.orbit, later.orbit, 0.01f)
        assertEquals(0f, first.ringProgress, 0.0001f)
    }

    @Test
    fun ready_is_happy_rather_than_a_check_mark() {
        controller.setState(CompanionState.Happy, 100L)
        val frame = capture(controller.update(200L))
        assertEquals(CompanionTint.Success, frame.tint)
        assertEquals(1f, frame.happyArc, 0.0001f)
        assertTrue("smile should curve up, was ${frame.mouthCurve}", frame.mouthCurve > 0.8f)
        assertTrue("it should hop once, bounce was ${frame.bounce}", frame.bounce > 0.1f)
        assertTrue("the success tick should show, badge was ${frame.badge}", frame.badge > 0.9f)
    }

    @Test
    fun error_droops_and_flushes_crimson() {
        controller.setState(CompanionState.Worried, 400L)
        val frame = capture(controller.update(500L))
        assertEquals(CompanionTint.Error, frame.tint)
        assertTrue("mouth should turn down, was ${frame.mouthCurve}", frame.mouthCurve < 0f)
        assertEquals(1f, frame.antennaDroop, 0.0001f)
        assertTrue("the disc should flush, was ${frame.flush}", frame.flush > 0.4f)
        assertTrue("a sad companion dims a little, dim was ${frame.dim}", frame.dim > 0f)
    }

    @Test
    fun growth_stage_clamps_and_changes_the_silhouette() {
        controller.setStage(-8)
        assertEquals(CompanionTiming.MinStage, controller.stage)
        controller.setStage(900)
        assertEquals(CompanionTiming.MaxStage, controller.stage)
        controller.setStage(1)
        val blob = capture(controller.update(1_000L))
        controller.setStage(4)
        val grown = capture(controller.update(1_000L))
        assertTrue("stage 4 should be bigger: ${blob.bodyScale} -> ${grown.bodyScale}", grown.bodyScale > blob.bodyScale)
        assertTrue("only the top stage earns a halo", blob.halo == 0f && grown.halo > 0f)
        assertEquals(0, blob.sparkCount)
        assertEquals(1, grown.sparkCount)
        assertTrue(grown.feet > blob.feet)
    }

    @Test
    fun a_poke_flashes_an_expression_without_overwriting_the_mood() {
        controller.setMood(CompanionMood.Sleepy)
        controller.poke(1_000L, CompanionMood.Surprised)
        assertEquals(CompanionMood.Surprised, controller.moodAt(1_400L))
        assertEquals(CompanionMood.Sleepy, controller.moodAt(1_000L + CompanionTiming.PokeMs + 1L))
        assertEquals(CompanionMood.Sleepy, controller.mood)
        val poked = capture(controller.update(1_100L))
        controller.reset(1_100L)
        val calm = capture(controller.update(1_100L))
        assertTrue("a poke should widen the eyes: ${poked.eyeOpen} vs ${calm.eyeOpen}",
            poked.eyeOpen > calm.eyeOpen)
        assertEquals(CompanionMood.Neutral, controller.moodAt(1_200L))
    }

    @Test
    fun only_the_states_you_watch_get_full_frame_rate() {
        controller.setState(CompanionState.Idle, 0L)
        assertTrue("idle must not spin at 60 fps", !controller.isFullRate(10L))
        controller.setState(CompanionState.Listening, 0L)
        assertTrue(controller.isFullRate(10L))
        controller.setState(CompanionState.Thinking, 0L)
        assertTrue(controller.isFullRate(10L))
        controller.setState(CompanionState.Happy, 0L)
        assertTrue("the celebration bounce needs smooth frames", controller.isFullRate(100L))
        assertTrue("but it may settle afterwards", !controller.isFullRate(CompanionTiming.HappyFullRateMs + 50L))
        controller.setState(CompanionState.Worried, 0L)
        assertTrue(!controller.isFullRate(10L))
        controller.setSpeaking(true)
        assertTrue("talking is full rate even while resting", controller.isFullRate(10L))
        controller.setSpeaking(false)
        controller.setState(CompanionState.Idle, 0L)
        assertTrue(!controller.isFullRate(10L))
    }

    @Test
    fun the_idle_heart_beat_stays_slow_and_bounded() {
        controller.setState(CompanionState.Idle, 0L)
        var shortest = Long.MAX_VALUE
        var longest = 0L
        var t = 0L
        while (t < 60_000L) {
            controller.update(t)
            val delay = controller.tickDelayMs(t)
            assertTrue("tick delay $delay ms at $t", delay in CompanionTiming.BlinkTickMs..CompanionTiming.IdleTickMs)
            shortest = minOf(shortest, delay)
            longest = maxOf(longest, delay)
            t += delay
        }
        assertTrue("idle should sleep between frames, fastest was $shortest ms", shortest >= CompanionTiming.BlinkTickMs)
        assertTrue("idle should mostly tick at the slow rate, slowest was $longest ms",
            longest <= CompanionTiming.IdleTickMs)
    }

    @Test
    fun idle_blinks_rarely_and_never_hangs_shut() {
        controller.setState(CompanionState.Idle, 0L)
        var shutFrames = 0
        var openFrames = 0
        var t = 0L
        while (t < 60_000L) {
            val frame = controller.update(t)
            if (frame.blink > 0.5f) shutFrames++ else openFrames++
            assertTrue("blink ${frame.blink} out of range at $t", frame.blink in 0f..1f)
            t += 16L
        }
        assertTrue("sixty idle seconds should blink a handful of times, got $shutFrames shut frames",
            shutFrames in 20..400)
        assertTrue(openFrames > shutFrames * 20)
    }

    @Test
    fun the_frame_object_is_reused_so_hours_of_overlay_cost_nothing() {
        val a = controller.update(10L)
        val b = controller.update(120L)
        val c = controller.update(1_000L)
        assertSame(a, b)
        assertSame(b, c)
    }

    @Test
    fun every_state_and_expression_lands_on_exactly_one_rendered_face() {
        // The art layer has one still per pose and nothing to fall back on, so a state that mapped to
        // no face would draw an empty bubble. The working states also own the face outright: a
        // worried bubble must not be recoloured into curiosity by a stray poke.
        for (pose in CompanionState.values()) {
            for (mood in CompanionMood.values()) {
                val sprite = CompanionLook.spritePose(pose, mood)
                assertTrue("$pose/$mood picked no face", sprite.name.isNotBlank())
                if (pose != CompanionState.Idle) {
                    assertSame(
                        "$pose recoloured by $mood",
                        CompanionLook.spritePose(pose, CompanionMood.Neutral),
                        sprite
                    )
                }
            }
        }
        assertEquals(SpritePose.Idle, CompanionLook.spritePose(CompanionState.Idle, CompanionMood.Neutral))
        assertEquals(SpritePose.Happy, CompanionLook.spritePose(CompanionState.Idle, CompanionMood.Happy))
        assertEquals(SpritePose.Sleepy, CompanionLook.spritePose(CompanionState.Idle, CompanionMood.Sleepy))
        assertEquals(SpritePose.Listening, CompanionLook.spritePose(CompanionState.Idle, CompanionMood.Curious))
    }

    @Test
    fun the_frame_carries_the_state_and_expression_the_art_layer_needs() {
        controller.setMood(CompanionMood.Curious)
        controller.setState(CompanionState.Thinking, 0L)
        val frame = controller.update(1_000L)
        assertEquals(CompanionState.Thinking, frame.state)
        assertEquals(CompanionMood.Curious, frame.mood)
    }

    @Test
    fun no_state_mood_stage_or_level_can_push_a_channel_out_of_range() {
        for (pose in CompanionState.values()) {
            for (mood in CompanionMood.values()) {
                for (stage in CompanionTiming.MinStage..CompanionTiming.MaxStage) {
                    controller.setStage(stage)
                    controller.setMood(mood)
                    controller.setState(pose, 0L)
                    controller.setSpeaking(pose == CompanionState.Idle)
                    var t = 0L
                    var step = 0
                    while (t < 30_000L) {
                        controller.setAmplitude(floatArrayOf(0f, 0.35f, 1f, Float.NaN)[step % 4], t)
                        val frame = controller.update(t)
                        for (channel in frame.channels()) {
                            assertTrue(
                                "$pose/$mood/stage$stage at ${t}ms left range ${channel.name}=${channel.value} " +
                                    "outside ${channel.min}..${channel.max}",
                                channel.inRange()
                            )
                        }
                        t += 137L
                        step++
                    }
                }
            }
        }
        assertEquals(CompanionTiming.MaxStage, controller.stage)
    }

    /** Copies the shared frame before the next update wipes it. */
    private fun capture(frame: CompanionFrame): Snapshot =
        Snapshot(
            tint = frame.tint,
            bob = frame.bob,
            blink = frame.blink,
            eyeOpen = frame.eyeOpen,
            happyArc = frame.happyArc,
            gazeX = frame.gazeX,
            gazeY = frame.gazeY,
            pupilScale = frame.pupilScale,
            mouthCurve = frame.mouthCurve,
            mouthOpen = frame.mouthOpen,
            lean = frame.lean,
            antennaDroop = frame.antennaDroop,
            aura = frame.aura,
            flush = frame.flush,
            dim = frame.dim,
            ringProgress = frame.ringProgress,
            ringRadius = frame.ringRadius,
            ringAlpha = frame.ringAlpha,
            orbit = frame.orbit,
            sparkCount = frame.sparkCount,
            halo = frame.halo,
            feet = frame.feet,
            bounce = frame.bounce,
            badge = frame.badge,
            bodyScale = frame.bodyScale,
            amplitude = frame.amplitude
        )

    private data class Snapshot(
        val tint: CompanionTint,
        val bob: Float,
        val blink: Float,
        val eyeOpen: Float,
        val happyArc: Float,
        val gazeX: Float,
        val gazeY: Float,
        val pupilScale: Float,
        val mouthCurve: Float,
        val mouthOpen: Float,
        val lean: Float,
        val antennaDroop: Float,
        val aura: Float,
        val flush: Float,
        val dim: Float,
        val ringProgress: Float,
        val ringRadius: Float,
        val ringAlpha: Float,
        val orbit: Float,
        val sparkCount: Int,
        val halo: Float,
        val feet: Float,
        val bounce: Float,
        val badge: Float,
        val bodyScale: Float,
        val amplitude: Float
    )
}
