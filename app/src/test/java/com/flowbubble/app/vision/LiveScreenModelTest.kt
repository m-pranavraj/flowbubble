package com.flowbubble.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind continuous watching: a cheap grid diff decides whether the expensive work
 * (OCR, tree reads, model fusion) is allowed to run, and a pointer phrase is resolved against the
 * ordered target list the session maintains.
 *
 * Everything here is pure, which is the point: the session feeds these functions real pixels and
 * real targets on a device, but the decisions that keep a live run from either spinning on a static
 * screen or silently guessing which "blue one" the user meant are checkable without one.
 */
class LiveScreenModelTest {

    private fun box(left: Int, top: Int, right: Int, bottom: Int) = ScreenBox(left, top, right, bottom)

    private fun node(text: String, box: ScreenBox) = ScreenNode(
        text, box, clickable = true, editable = false, password = false, scrollable = false,
        packageName = "com.example"
    )

    private fun model(vararg labels: String) = ScreenModel(
        packageName = "com.example",
        capturedAt = 0L,
        screenWidth = 400,
        screenHeight = 800,
        nodes = labels.mapIndexed { index, text -> node(text, box(0, 40 + index * 100, 400, 100 + index * 100)) },
        ocr = emptyList(),
        ocrUsed = false,
        secureLikely = false,
        passwordFocused = false
    )

    /** A flat frame in one colour, or a block of it in another. */
    private fun flat(color: Int): (Int, Int) -> Int = { _, _ -> color }

    private fun patch(base: Int, spot: Int, left: Int, top: Int, width: Int, height: Int): (Int, Int) -> Int =
        { x, y -> if (x >= left && x < left + width && y >= top && y < top + height) spot else base }

    private fun patch(base: Int, spot: Int, left: Int, top: Int): (Int, Int) -> Int =
        patch(base, spot, left, top, 40, 40)

    @Test
    fun `sampling buckets brightness and picks the majority colour family`() {
        val white = FrameDiff.sample(12, 24, 400, 800, flat(0xFFFFFFFF.toInt()))
        val black = FrameDiff.sample(12, 24, 400, 800, flat(0xFF000000.toInt()))
        assertEquals(7, white.luminance[0])
        assertEquals(0, black.luminance[0])
        val red = FrameDiff.sample(12, 24, 400, 800, flat(0xFFFF0000.toInt()))
        assertEquals(ScreenHue.RED.ordinal, red.hues[0])
        assertEquals(ScreenHue.RED, red.hueAt(box(0, 0, 400, 800)))
    }

    @Test
    fun `an unchanged frame costs nothing and a changed one is bounded`() {
        val a = FrameDiff.sample(12, 24, 400, 800, flat(0xFF101010.toInt()))
        val same = FrameDiff.sample(12, 24, 400, 800, flat(0xFF101010.toInt()))
        assertEquals(0f, FrameDiff.changedFraction(same, a), 0f)

        val moved = FrameDiff.sample(12, 24, 400, 800, patch(0xFF101010.toInt(), 0xFFFAFAFA.toInt(), 10, 10))
        val fraction = FrameDiff.changedFraction(moved, a)
        assertTrue("a single popup should move a few cells, not the screen: $fraction", fraction in 0.001f..0.2f)
        val region = FrameDiff.changedRegion(moved, a, paddingPx = 90)
        assertTrue("changed region was null", region != null)
        assertTrue(region!!.right <= 400 && region.bottom <= 800 && region.left < region.right)
    }

    @Test
    fun `colour families land where a person would name them`() {
        assertEquals(ScreenHue.RED, ScreenHue.of(0xFFFF3B30.toInt()))
        assertEquals(ScreenHue.ORANGE, ScreenHue.of(0xFFFF9500.toInt()))
        assertEquals(ScreenHue.YELLOW, ScreenHue.of(0xFFFFCC00.toInt()))
        assertEquals(ScreenHue.GREEN, ScreenHue.of(0xFF22D8A2.toInt()))
        assertEquals(ScreenHue.CYAN, ScreenHue.of(0xFF00BCD4.toInt()))
        assertEquals(ScreenHue.BLUE, ScreenHue.of(0xFF1E90FF.toInt()))
        assertEquals(ScreenHue.PURPLE, ScreenHue.of(0xFF8B5CF6.toInt()))
        assertEquals(ScreenHue.DARK, ScreenHue.of(0xFF050308.toInt()))
        assertEquals(ScreenHue.NEUTRAL, ScreenHue.of(0xFF808080.toInt()))
    }

    @Test
    fun `colour words a user speaks map to a family`() {
        assertEquals(ScreenHue.GREEN, ScreenHue.spoken("green"))
        assertEquals(ScreenHue.BLUE, ScreenHue.spoken("blue"))
        assertEquals(ScreenHue.CYAN, ScreenHue.spoken("teal"))
        assertEquals(ScreenHue.PURPLE, ScreenHue.spoken("pink"))
        assertEquals(ScreenHue.NEUTRAL, ScreenHue.spoken("GREY"))
        assertNull(ScreenHue.spoken("chartreuse"))
    }

    @Test
    fun `the first read publishes and nothing to read publishes nothing`() {
        val live = LiveScreenModel()
        live.start()
        assertEquals(false, live.analyze(null, null, 1_000L).publish)
        assertTrue(live.analyze(null, 12345, 1_000L).publish)
        assertEquals(0, live.revision)
    }

    @Test
    fun `a busy animation cannot drive the expensive work every frame`() {
        val config = LiveConfig(minPublishIntervalMs = 260L, treePublishIntervalMs = 120L)
        val live = LiveScreenModel(config)
        live.start()
        val baseline = FrameDiff.sample(12, 24, 400, 800, flat(0xFF101010.toInt()))
        live.publish(model("Inbox"), baseline, atMs = 1_000L, pixelsRefreshed = true)

        val moved = FrameDiff.sample(
            12, 24, 400, 800,
            patch(0xFF101010.toInt(), 0xFFFAFAFA.toInt(), 0, 0, 160, 120)
        )
        val soon = live.analyze(moved, ScreenFusion.textHash(model("Inbox")), atMs = 1_100L)
        assertEquals(false, soon.publish)
        assertTrue("a throttled change must be re-tried, not dropped: $soon", soon.deferred)

        val later = live.analyze(moved, ScreenFusion.textHash(model("Inbox")), atMs = 1_300L)
        assertTrue(later.publish)
        assertEquals("the screen changed", later.reason)
        assertNotEquals(0, live.revision)
    }

    @Test
    fun `a tree change is nearly free so it publishes sooner than pixels`() {
        val live = LiveScreenModel(LiveConfig(treePublishIntervalMs = 120L))
        live.start()
        val signature = FrameDiff.sample(12, 24, 400, 800, flat(0xFF101010.toInt()))
        live.publish(model("Inbox"), signature, atMs = 1_000L, pixelsRefreshed = true)
        val result = live.analyze(
            signature,
            ScreenFusion.textHash(model("Inbox", "Compose")),
            atMs = 1_150L
        )
        assertTrue(result.publish)
        assertEquals("the accessibility text changed", result.reason)
        assertNull(result.region)
    }

    @Test
    fun `an unwatching model never does any work`() {
        val live = LiveScreenModel()
        val signature = FrameDiff.sample(12, 24, 400, 800, flat(0xFF101010.toInt()))
        val result = live.analyze(signature, 7, atMs = 1_000L)
        assertEquals(false, result.publish)
        assertEquals("not watching", result.reason)
    }

    @Test
    fun `what appeared since a revision is reported, and a stale revision says so`() {
        val live = LiveScreenModel()
        live.start()
        val signature = FrameDiff.sample(12, 24, 400, 800, flat(0xFF101010.toInt()))
        val first = live.publish(model("Inbox"), signature, atMs = 1_000L, pixelsRefreshed = true)
        live.publish(model("Inbox", "Compose"), signature, atMs = 2_000L, pixelsRefreshed = false)

        val diff = live.since(first.revision)
        assertEquals(true, diff.changed)
        assertTrue(diff.appeared.any { it.contains("compose", ignoreCase = true) })

        val aged = live.since(999)
        assertTrue("an unknown revision is a floor, not a claim that nothing changed", aged.partial)
    }

    @Test
    fun `an order and a place resolve to one target`() {
        val targets = listOf(
            ScreenTarget("Pranav", box(0, 40, 400, 100), TargetSource.ACCESSIBILITY, clickable = true),
            ScreenTarget("Rahul", box(0, 400, 400, 460), TargetSource.ACCESSIBILITY, clickable = true),
            ScreenTarget("Support", box(0, 700, 400, 760), TargetSource.ACCESSIBILITY, clickable = true)
        )
        val second = TargetLocator.resolve("second chat", targets, screenWidth = 400, screenHeight = 800)
        assertTrue("expected the second row, got $second", second is PointResolution.Found)
        assertEquals("Rahul", (second as PointResolution.Found).target.label)

        val top = TargetLocator.resolve("that chat at the top", targets, screenWidth = 400, screenHeight = 800)
        assertEquals("Pranav", (top as PointResolution.Found).target.label)

        val blue = TargetLocator.resolve(
            "the blue one",
            targets,
            hueOf = { box -> if (box.top == 400) ScreenHue.BLUE else ScreenHue.NEUTRAL },
            screenWidth = 400,
            screenHeight = 800
        )
        assertEquals("Rahul", (blue as PointResolution.Found).target.label)
    }

    @Test
    fun `an ambiguous pointer asks instead of guessing`() {
        val targets = listOf(
            ScreenTarget("Delete", box(0, 100, 400, 160), TargetSource.ACCESSIBILITY, clickable = true),
            ScreenTarget("Delete", box(0, 600, 400, 660), TargetSource.ACCESSIBILITY, clickable = true)
        )
        val result = TargetLocator.resolve("that delete", targets, screenWidth = 400, screenHeight = 800)
        assertTrue(
            "two identical labels and no order, colour or place must be a question, not a tap: $result",
            result is PointResolution.Ambiguous
        )
    }

    @Test
    fun `a pointer with nothing on screen says so`() {
        val result = TargetLocator.resolve("the third one", emptyList(), screenWidth = 400, screenHeight = 800)
        assertTrue(result is PointResolution.NotFound)
    }
}
