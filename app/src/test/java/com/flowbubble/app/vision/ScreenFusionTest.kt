package com.flowbubble.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenFusionTest {

    private fun box(left: Int, top: Int, right: Int, bottom: Int) =
        ScreenBox(left, top, right, bottom)

    private fun node(
        label: String,
        box: ScreenBox,
        clickable: Boolean = true,
        editable: Boolean = false,
        password: Boolean = false
    ) = ScreenNode(label, box, clickable, editable, password, false, "com.example")

    @Test
    fun `ocr lines duplicating accessibility text are suppressed`() {
        val shared = box(0, 100, 400, 150)
        val targets = ScreenFusion.targets(
            nodes = listOf(node("Search", shared)),
            ocr = listOf(OcrLine("Search", shared))
        )
        assertEquals(1, targets.size)
        assertEquals(TargetSource.ACCESSIBILITY, targets.first().source)
    }

    @Test
    fun `password nodes never become targets`() {
        val targets = ScreenFusion.targets(
            nodes = listOf(node("Password", box(0, 0, 400, 60), editable = true, password = true)),
            ocr = emptyList()
        )
        assertTrue(targets.isEmpty())
    }

    @Test
    fun `find target prefers clickable accessibility matches`() {
        val targets = listOf(
            ScreenTarget("Continue", box(0, 300, 400, 360), TargetSource.OCR, clickable = true),
            ScreenTarget("Continue", box(0, 700, 400, 760), TargetSource.ACCESSIBILITY, clickable = true)
        )
        val found = ScreenFusion.findTarget("continue", targets)
        assertNotNull(found)
        assertEquals(TargetSource.ACCESSIBILITY, found!!.source)
    }

    @Test
    fun `ordinal selection uses the clickable pool`() {
        val targets = listOf(
            ScreenTarget("One", box(0, 0, 100, 50), TargetSource.ACCESSIBILITY, clickable = true),
            ScreenTarget("Two", box(0, 60, 100, 110), TargetSource.ACCESSIBILITY, clickable = true),
            ScreenTarget("Three", box(0, 120, 100, 170), TargetSource.ACCESSIBILITY, clickable = true)
        )
        assertEquals("Two", ScreenFusion.findTarget("", targets, 2)?.label)
        assertNull(ScreenFusion.findTarget("", targets, 9))
    }

    @Test
    fun `search field prefers hint and search-like labels`() {
        val targets = listOf(
            ScreenTarget("Message Priya", box(0, 0, 400, 60), TargetSource.ACCESSIBILITY, clickable = true, editable = true),
            ScreenTarget("Search YouTube", box(0, 70, 400, 130), TargetSource.ACCESSIBILITY, clickable = true, editable = true)
        )
        assertEquals("Search YouTube", ScreenFusion.searchField(targets)?.label)
        assertEquals("Message Priya", ScreenFusion.searchField(targets, listOf("message"))?.label)
    }

    @Test
    fun `search field returns null when nothing is editable`() {
        val targets = listOf(
            ScreenTarget("Search", box(0, 0, 100, 50), TargetSource.OCR, clickable = true)
        )
        assertNull(ScreenFusion.searchField(targets))
    }

    @Test
    fun `a search icon is the way into search when the app shows no field`() {
        val targets = ScreenFusion.targets(
            nodes = listOf(
                node("Home", box(0, 2200, 200, 2300)),
                node("Search YouTube", box(900, 100, 1000, 200)),
                node("Account", box(1000, 100, 1100, 200))
            ),
            ocr = emptyList()
        )
        assertNull(ScreenFusion.searchField(targets))
        val affordance = ScreenFusion.searchAffordance(targets)
        assertNotNull(affordance)
        assertEquals("Search YouTube", affordance!!.label)
    }

    @Test
    fun `a search label on a non-clickable text node still counts`() {
        // Settings puts "Search settings" on a TextView and makes its parent the clickable node,
        // so requiring the label itself to be clickable would never find it.
        val targets = ScreenFusion.targets(
            nodes = listOf(node("Search settings", box(200, 632, 553, 703), clickable = false)),
            ocr = emptyList()
        )
        assertEquals("Search settings", ScreenFusion.searchAffordance(targets)?.label)
    }

    @Test
    fun `an editable field is never reported as an affordance`() {
        val targets = ScreenFusion.targets(
            nodes = listOf(node("Search", box(0, 300, 800, 380), editable = true)),
            ocr = emptyList()
        )
        assertNotNull(ScreenFusion.searchField(targets))
        assertNull(ScreenFusion.searchAffordance(targets))
    }

    @Test
    fun `body text and long labels are not affordances`() {
        val prose = ScreenFusion.targets(
            nodes = listOf(node("Search results for lofi beats", box(0, 400, 900, 460), clickable = false)),
            ocr = emptyList()
        )
        assertNull(ScreenFusion.searchAffordance(prose))

        val sentence = ScreenFusion.targets(
            nodes = listOf(node("search the whole catalogue today", box(0, 400, 900, 460))),
            ocr = emptyList()
        )
        assertNull(ScreenFusion.searchAffordance(sentence))
    }

    @Test
    fun `text hash changes when visible text changes`() {
        fun model(text: String) = ScreenModel(
            packageName = "com.example",
            capturedAt = 0L,
            screenWidth = 1080,
            screenHeight = 2400,
            nodes = listOf(node(text, box(0, 0, 400, 60))),
            ocr = emptyList(),
            ocrUsed = false,
            secureLikely = false,
            passwordFocused = false
        )
        assertTrue(ScreenFusion.textHash(model("Inbox")) != ScreenFusion.textHash(model("Sent")))
    }
}
