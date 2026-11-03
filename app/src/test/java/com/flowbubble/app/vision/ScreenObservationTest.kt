package com.flowbubble.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A planner deciding the next tap needs more than a list of words: where the cursor is, which fields
 * will actually take text, and how far the reading itself can be trusted. These are the defaults and
 * the arithmetic of that, with nothing focused and everything focused both tried.
 */
class ScreenObservationTest {

    private fun box(left: Int, top: Int, right: Int, bottom: Int) = ScreenBox(left, top, right, bottom)

    private fun node(
        label: String,
        box: ScreenBox,
        clickable: Boolean = true,
        editable: Boolean = false,
        password: Boolean = false,
        focused: Boolean = false
    ) = ScreenNode(label, box, clickable, editable, password, false, "com.example", focused)

    /** Built the way a producer that knows nothing about the new fields would build it. */
    private fun model(
        nodes: List<ScreenNode>,
        ocr: List<OcrLine> = emptyList(),
        ocrUsed: Boolean = false,
        secureLikely: Boolean = false,
        passwordFocused: Boolean = false,
        captureError: String? = null,
        ocrError: String? = null
    ) = ScreenModel(
        "com.example",
        0L,
        1080,
        2400,
        nodes,
        ocr,
        ocrUsed,
        secureLikely,
        passwordFocused,
        captureError,
        ocrError
    )

    @Test
    fun `a model built before the extras still compiles and reads empty`() {
        val bare = model(listOf(node("Search", box(0, 100, 400, 160))))
        assertNull(bare.focus)
        assertTrue(bare.editableFields.isEmpty())
        assertEquals(ScreenConfidence.Unmeasured, bare.confidence)
        assertTrue(!bare.confidence.isMeasured)
        assertEquals(1, bare.targets.size)
        assertNull(bare.focusedField)
    }

    @Test
    fun `nothing focused stays nothing focused`() {
        val enriched = ScreenDetails.enrich(model(listOf(node("Search", box(0, 100, 400, 160)))))
        assertNull(enriched.focus)
        assertTrue(enriched.editableFields.isEmpty())
        assertTrue(enriched.writableFields.isEmpty())
        assertNull(enriched.focusedField)
        assertTrue(enriched.confidence.isMeasured)
        assertEquals(ConfidenceLevel.LOW, enriched.confidence.level)
        assertTrue(enriched.confidence.score in 0f..1f)
        assertTrue(enriched.confidence.reasons.isNotEmpty())
    }

    @Test
    fun `the node the app flagged as focused is the focus`() {
        val fieldBox = box(0, 700, 400, 760)
        val tree = model(
            listOf(
                node("Send", box(0, 900, 200, 960)),
                node("Message Priya", fieldBox, clickable = false, editable = true, focused = true)
            )
        )
        val enriched = ScreenDetails.enrich(tree)
        assertNotNull(enriched.focus)
        assertEquals("Message Priya", enriched.focus!!.label)
        assertTrue(enriched.focus!!.editable)
        assertEquals(fieldBox, enriched.focus!!.box)
        assertEquals(1, enriched.editableFields.size)
        assertTrue(enriched.editableFields.first().focused)
        assertEquals("Message Priya", enriched.focusedField?.label)
        assertTrue(enriched.targets.first { it.editable }.focused)
    }

    @Test
    fun `a focused password box is reported without its label`() {
        val secret = node(
            "Corporate password",
            box(0, 500, 400, 560),
            clickable = false,
            editable = true,
            password = true,
            focused = true
        )
        val enriched = ScreenDetails.enrich(model(listOf(node("Sign in", box(0, 700, 400, 760)), secret)))
        assertNotNull(enriched.focus)
        assertTrue(enriched.focus!!.password)
        assertEquals("", enriched.focus!!.label)
        assertTrue(enriched.editableFields.first().password)
        assertTrue(enriched.writableFields.isEmpty())
        assertNull(enriched.focusedField)
    }

    @Test
    fun `a password field that reports focus only through the window is still found`() {
        val tree = model(
            listOf(node("Sign in", box(0, 200, 200, 260)), node("Password", box(0, 500, 400, 560), editable = true, password = true)),
            passwordFocused = true
        )
        val enriched = ScreenDetails.enrich(tree)
        assertNotNull(enriched.focus)
        assertTrue(enriched.focus!!.password)
        assertEquals("", enriched.focus!!.label)
        assertTrue(enriched.confidence.reasons.any { it.contains("password") })
    }

    @Test
    fun `the cursor found through the window marks the matching field`() {
        val fieldBox = box(0, 700, 400, 760)
        val field = node("Message", fieldBox, clickable = false, editable = true)
        val enriched = ScreenDetails.enrich(model(listOf(field)), focusedNode = field.copy(focused = true))
        assertEquals("Message", enriched.focus?.label)
        assertTrue(enriched.editableFields.first().focused)
    }

    @Test
    fun `editable fields are named in reading order`() {
        val tree = model(
            listOf(
                node("Subject", box(0, 800, 400, 860), clickable = false, editable = true),
                node("Body", box(0, 300, 400, 360), clickable = false, editable = true),
                node("PIN", box(0, 1200, 400, 1260), clickable = false, editable = true, password = true)
            )
        )
        val fields = ScreenFusion.editableFields(tree.nodes)
        assertEquals(3, fields.size)
        // The credential's own label is the one thing a reading must not carry away.
        assertEquals(listOf("Body", "Subject", ""), fields.map { it.label })
        assertTrue(fields.last().password)
        val enriched = ScreenDetails.enrich(tree)
        assertEquals(3, enriched.editableFields.size)
        assertEquals(2, enriched.writableFields.size)
    }

    @Test
    fun `a field off the grid is not offered as a field`() {
        val fields = ScreenFusion.editableFields(listOf(node("Ghost", box(0, 0, 0, 0), editable = true)))
        assertTrue(fields.isEmpty())
    }

    @Test
    fun `a full tree with pixels and a cursor reads as a confident picture`() {
        val tree = model(
            listOf(
                node("Search", box(0, 100, 300, 160)),
                node("Inbox", box(0, 300, 400, 360), clickable = false, editable = true, focused = true),
                node("Compose", box(0, 500, 200, 560)),
                node("Settings", box(0, 700, 200, 760))
            ),
            ocr = listOf(OcrLine("3 new", box(0, 200, 120, 240)), OcrLine("Starred", box(0, 600, 120, 640))),
            ocrUsed = true
        )
        val enriched = ScreenDetails.enrich(tree)
        assertEquals(ConfidenceLevel.HIGH, enriched.confidence.level)
        assertTrue(enriched.confidence.score in 0.75f..1f)
        assertTrue(enriched.confidence.reasons.any { it.contains("controls") })
    }

    @Test
    fun `a black frame with nothing in the tree reads as no confidence at all`() {
        val blocked = model(
            emptyList(),
            ocrUsed = true,
            secureLikely = true,
            captureError = "The captured screen is black."
        )
        val enriched = ScreenDetails.enrich(blocked)
        assertNull(enriched.focus)
        assertEquals(ConfidenceLevel.LOW, enriched.confidence.level)
        assertTrue(enriched.confidence.score <= 0.05f)
        assertTrue(enriched.confidence.reasons.any { it.contains("black") })
    }

    @Test
    fun `what a producer already said is never overruled`() {
        val named = model(listOf(node("Body", box(0, 300, 400, 360), editable = true)))
            .copy(
                focus = FocusedControl("Body", box(0, 300, 400, 360), editable = true, password = false),
                confidence = ScreenConfidence(ConfidenceLevel.MEDIUM, 0.5f, listOf("counted it myself"))
            )
        val enriched = ScreenDetails.enrich(named)
        assertEquals("counted it myself", enriched.confidence.reasons.first())
        assertEquals(ConfidenceLevel.MEDIUM, enriched.confidence.level)
        assertEquals("Body", enriched.focus?.label)
    }

    @Test
    fun `the live session publishes an enriched screen`() {
        val fieldBox = box(0, 700, 400, 760)
        val live = LiveScreenModel()
        val published = live.publish(
            model(listOf(node("Send", box(0, 900, 200, 960)), node("Message", fieldBox, editable = true))),
            null,
            atMs = 1_000L,
            pixelsRefreshed = false,
            focusedNode = node("Message", fieldBox, clickable = false, editable = true, focused = true)
        )
        assertEquals(1, published.revision)
        assertEquals("Message", published.focus?.label)
        assertTrue(published.editableFields.first().focused)
        assertTrue(published.confidence.isMeasured)
        assertEquals(2, published.targets.size)
    }

    @Test
    fun `fusion marks the control that holds the cursor`() {
        val targets = ScreenFusion.targets(
            nodes = listOf(
                node("Search", box(0, 100, 300, 160)),
                node("Query", box(0, 300, 400, 360), editable = true, focused = true)
            ),
            ocr = emptyList()
        )
        assertEquals(1, targets.count { it.focused })
        assertEquals("Query", targets.first { it.focused }.label)
    }
}
