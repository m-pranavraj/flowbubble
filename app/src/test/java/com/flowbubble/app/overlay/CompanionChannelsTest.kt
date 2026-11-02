package com.flowbubble.app.overlay

import com.flowbubble.app.overlay.companion.CompanionChannels
import com.flowbubble.app.overlay.companion.CompanionLook
import com.flowbubble.app.overlay.companion.CompanionMath
import com.flowbubble.app.overlay.companion.CompanionMood
import com.flowbubble.app.overlay.companion.CompanionState
import com.flowbubble.app.overlay.companion.CompanionTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The character's timing is arithmetic, not keyframes, so the cadence can be asserted directly.
 * These tests pin the idle loop down: one blink per cycle, always in range, never a 60 fps spin cycle.
 */
class CompanionChannelsTest {

    @Test
    fun blink_reaches_its_full_depth_exactly_once_per_cycle() {
        for (cycle in 0L until 40L) {
            val peak = CompanionChannels.blinkPeakMs(cycle)
            assertEquals(CompanionChannels.blinkDepth(cycle), CompanionChannels.blinkAt(peak), 0.0001f)
            // The eyelid is only fully down at the peak; everywhere else in the cycle it is open.
            assertEquals(0f, CompanionChannels.blinkAt(peak - CompanionTiming.BlinkDurationMs), 0.0001f)
            assertEquals(0f, CompanionChannels.blinkAt(peak + CompanionTiming.BlinkDurationMs), 0.0001f)
        }
    }

    @Test
    fun blinks_are_sparse_but_never_far_apart() {
        var previous = CompanionChannels.blinkPeakMs(0L)
        var total = 0L
        var samples = 0
        for (cycle in 1L until 200L) {
            val peak = CompanionChannels.blinkPeakMs(cycle)
            val gap = peak - previous
            assertTrue("blink gap $gap ms is too long to look alive", gap in 2400L..6000L)
            total += gap
            samples++
            previous = peak
        }
        val mean = total / samples
        assertTrue("mean blink gap $mean ms drifted off the ${CompanionTiming.BlinkIntervalMs} ms cycle",
            kotlin.math.abs(mean - CompanionTiming.BlinkIntervalMs) < 150L)
    }

    @Test
    fun blink_depth_is_a_half_shut_sometimes_and_never_leaves_0_1() {
        for (cycle in 0L until 500L) {
            val depth = CompanionChannels.blinkDepth(cycle)
            assertTrue("blink depth $depth out of range", depth in 0f..1f)
            assertTrue(CompanionChannels.blinkAt(CompanionChannels.blinkPeakMs(cycle)) in 0f..1f)
        }
    }

    @Test
    fun blinks_stay_inside_their_own_cycle() {
        for (cycle in 0L until 300L) {
            val start = CompanionChannels.blinkStartMs(cycle)
            assertTrue("blink $cycle starts too late", start + CompanionTiming.BlinkDurationMs < CompanionTiming.BlinkIntervalMs)
            assertTrue("blink $cycle starts too early", start >= 0L)
        }
    }

    @Test
    fun the_idle_ticker_can_sleep_until_the_next_blink() {
        for (now in longArrayOf(0L, 500L, 1800L, 4199L, 9000L, 123456L)) {
            val remaining = CompanionChannels.nextBlinkInMs(now)
            assertTrue("negative countdown at $now", remaining >= 0L)
            assertTrue("countdown $remaining ms at $now overshoots a whole cycle", remaining <= CompanionTiming.BlinkIntervalMs)
            if (remaining > 0L) {
                assertTrue("a blink should be underway right after the countdown lands",
                    CompanionChannels.blinkActive(now + remaining + 20L))
                assertEquals("the countdown overshot the blink start",
                    0f, CompanionChannels.blinkAt(now + remaining - 20L), 0.0001f)
            } else {
                assertTrue("a zero countdown only happens mid-blink", CompanionChannels.blinkActive(now))
            }
        }
    }

    @Test
    fun a_blink_is_held_for_its_full_duration_and_closed_outside_it() {
        val cycle = 3L
        val start = CompanionChannels.blinkStartMs(cycle) + CompanionTiming.BlinkIntervalMs * cycle
        assertTrue(CompanionChannels.blinkActive(start + 90L))
        assertEquals(0f, CompanionChannels.blinkAt(start + CompanionTiming.BlinkDurationMs + 500L), 0.0001f)
    }

    @Test
    fun gaze_glances_and_comes_back_without_leaving_its_range() {
        assertEquals(0f, CompanionChannels.gazeX(0L), 0.0001f)
        val midHold = CompanionTiming.GazeHoldMs / 2L
        var strongest = 0f
        for (cycle in 1L..30L) {
            val glance = CompanionChannels.gazeX(cycle * CompanionTiming.GazePeriodMs + midHold)
            assertTrue("glance $glance out of range", glance in -1f..1f)
            strongest = maxOf(strongest, kotlin.math.abs(glance))
            assertEquals("the eyes should be back on the user after the hold",
                0f, CompanionChannels.gazeX(cycle * CompanionTiming.GazePeriodMs + CompanionTiming.GazeHoldMs + 5L), 0.0001f)
        }
        assertTrue("some glance over thirty cycles should actually look away, strongest was $strongest",
            strongest > 0.3f)
        for (now in 0L..60000L step 61L) {
            assertTrue(CompanionChannels.gazeX(now) in -1f..1f)
            assertTrue(CompanionChannels.gazeY(now) in -1f..1f)
        }
    }

    @Test
    fun celebration_bounce_starts_and_ends_at_rest() {
        assertEquals(0f, CompanionChannels.bounce(-1L), 0.0001f)
        assertEquals(0f, CompanionChannels.bounce(0L), 0.0001f)
        assertTrue(CompanionChannels.bounce(CompanionTiming.HappyBounceMs / 4L) > 0.3f)
        assertEquals(0f, CompanionChannels.bounce(CompanionTiming.HappyBounceMs), 0.0001f)
        assertEquals(0f, CompanionChannels.bounce(CompanionTiming.HappyBounceMs * 4L), 0.0001f)
        for (since in 0L..4000L step 17L) {
            assertTrue("bounce $since out of range", CompanionChannels.bounce(since) in 0f..1f)
        }
    }

    @Test
    fun the_success_tick_holds_then_fades_instead_of_popping() {
        assertEquals(1f, CompanionChannels.badge(0L), 0.0001f)
        assertEquals(1f, CompanionChannels.badge(CompanionTiming.BadgeHoldMs), 0.0001f)
        val middle = CompanionChannels.badge(CompanionTiming.BadgeHoldMs + CompanionTiming.BadgeFadeMs / 2L)
        assertTrue("badge should be mid-fade, was $middle", middle in 0.2f..0.8f)
        assertEquals(0f, CompanionChannels.badge(CompanionTiming.BadgeHoldMs + CompanionTiming.BadgeFadeMs), 0.0001f)
        assertEquals(0f, CompanionChannels.badge(999_999L), 0.0001f)
    }

    @Test
    fun every_amplitude_maps_the_ring_inside_the_bubble() {
        for (raw in floatArrayOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -5f, -0.01f, 0f, 0.5f, 1f, 3f)) {
            for (progress in floatArrayOf(0f, 0.25f, 0.75f, 1f, Float.NaN)) {
                val radius = CompanionLook.ringRadius(progress, raw)
                assertTrue("ring radius $radius for amp=$raw", radius in 0.98f..1.24f)
                assertTrue("ring alpha ${CompanionLook.ringAlpha(progress, raw)} out of range",
                    CompanionLook.ringAlpha(progress, raw) in 0f..1f)
            }
            val pupils = CompanionLook.pupilScale(raw)
            assertTrue("pupil scale $pupils for amp=$raw", pupils in 0.8f..1.4f)
        }
    }

    @Test
    fun louder_input_grows_the_ring_and_the_pupils() {
        val quiet = CompanionLook.pupilScale(0f)
        val loud = CompanionLook.pupilScale(1f)
        assertTrue("pupils should widen with volume: $quiet -> $loud", loud > quiet)
        val quietRing = CompanionLook.ringRadius(1f, 0f)
        val loudRing = CompanionLook.ringRadius(1f, 1f)
        assertTrue("the ring should reach further with volume: $quietRing -> $loudRing", loudRing > quietRing)
        assertTrue(CompanionLook.ringAlpha(0f, 1f) > CompanionLook.ringAlpha(0f, 0f))
    }

    @Test
    fun aura_and_flush_and_dim_never_blow_past_their_ceiling() {
        for (state in CompanionState.values()) {
            for (mood in CompanionMood.values()) {
                for (stage in -3 until 9) {
                    for (amp in floatArrayOf(Float.NaN, -1f, 0f, 0.7f, 1f, 4f)) {
                        assertTrue(CompanionLook.glowFor(state, mood, stage, amp) in 0f..1f)
                        assertTrue(CompanionLook.flushFor(state, amp, CompanionMath.wave(1234L, 620L)) in 0f..1f)
                        assertTrue(CompanionLook.dimFor(state, mood) in 0f..1f)
                        assertTrue(CompanionLook.eyeOpenBase(state, mood, stage) in 0f..1.4f)
                        assertTrue(CompanionLook.mouthCurve(state, mood) in -1f..1f)
                        assertTrue(CompanionLook.mouthOpen(state, mood, true, amp) in 0f..1f)
                        assertTrue(CompanionLook.mouthOpen(state, mood, false, amp) in 0f..1f)
                        assertTrue(CompanionLook.antennaDroop(state, mood) in 0f..1f)
                        assertTrue(CompanionLook.antennaPerk(state, mood) in 0f..1f)
                        assertTrue(CompanionLook.haloFor(state, stage, 1f) in 0f..1f)
                        assertTrue(CompanionLook.leanFor(state, amp) in -1f..1f)
                        assertTrue(CompanionLook.bodyScaleFor(stage) in 0.72f..1.02f)
                        assertTrue(CompanionLook.eyeScaleFor(stage) in 0.8f..1.2f)
                        assertTrue(CompanionLook.feetFor(stage) in 0f..1f)
                        val sparks = CompanionLook.sparkCount(state, stage)
                        assertTrue("stage $stage state $state gave $sparks sparks", sparks in 0..4)
                    }
                }
            }
        }
    }

    @Test
    fun growth_stage_adds_features_and_clamps_its_input() {
        assertEquals(1, CompanionLook.clampStage(-100))
        assertEquals(1, CompanionLook.clampStage(0))
        assertEquals(4, CompanionLook.clampStage(999))
        assertTrue(CompanionLook.bodyScaleFor(1) < CompanionLook.bodyScaleFor(4))
        assertEquals(0f, CompanionLook.haloFor(CompanionState.Idle, 3, 0f), 0.0001f)
        assertTrue(CompanionLook.haloFor(CompanionState.Idle, 4, 0f) > 0f)
        assertEquals(0f, CompanionLook.feetFor(2), 0.0001f)
        assertEquals(1f, CompanionLook.feetFor(4), 0.0001f)
        assertTrue(CompanionLook.glowFor(CompanionState.Idle, CompanionMood.Neutral, 4, 0f) >
            CompanionLook.glowFor(CompanionState.Idle, CompanionMood.Neutral, 1, 0f))
    }

    @Test
    fun a_stage_one_blob_sleeps_until_you_hold_it() {
        assertTrue(CompanionLook.eyeOpenBase(CompanionState.Idle, CompanionMood.Neutral, 1) < 0.2f)
        assertTrue(CompanionLook.eyeOpenBase(CompanionState.Listening, CompanionMood.Neutral, 1) > 0.5f)
    }

    @Test
    fun stored_mood_names_cannot_crash_the_overlay() {
        assertEquals(CompanionMood.Neutral, CompanionLook.moodNamed(null))
        assertEquals(CompanionMood.Neutral, CompanionLook.moodNamed("grumpy"))
        assertEquals(CompanionMood.Neutral, CompanionLook.moodNamed(""))
        assertEquals(CompanionMood.Sleepy, CompanionLook.moodNamed(" SLEEPY "))
        assertEquals(CompanionMood.Curious, CompanionLook.moodNamed("curious"))
    }

    @Test
    fun numeric_helpers_refuse_to_produce_nan_or_runaway_values() {
        assertEquals(0f, CompanionMath.unit(Float.NaN), 0f)
        assertEquals(0f, CompanionMath.unit(Float.POSITIVE_INFINITY), 0f)
        assertEquals(1f, CompanionMath.unit(7f), 0f)
        assertEquals(0f, CompanionMath.signed(Float.NaN), 0f)
        assertEquals(-1f, CompanionMath.signed(-40f), 0f)
        assertEquals(2f, CompanionMath.between(Float.NaN, 2f, 5f), 0f)
        for (index in -50L..50L) {
            val hash = CompanionMath.hash01(index)
            assertTrue("hash $hash for index $index", hash in 0f..1f)
            assertEquals("idle motion must stay reproducible", hash, CompanionMath.hash01(index), 0f)
        }
    }
}
