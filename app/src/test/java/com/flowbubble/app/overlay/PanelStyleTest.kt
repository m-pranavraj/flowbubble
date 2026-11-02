package com.flowbubble.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overlay card's visual grammar, tested without a device: which tone a panel wears, how its
 * body is broken up, and which role each button gets.
 */
class PanelStyleTest {

    private fun panel(
        title: String,
        body: String = "",
        actions: List<PanelAction> = emptyList(),
        dismissible: Boolean = true,
        autoDismissMs: Long? = null,
        scrollable: Boolean = false,
        copyText: String? = null,
        voicePrompt: String? = null
    ) = PanelContent(
        title = title,
        body = body,
        actions = actions,
        dismissible = dismissible,
        autoDismissMs = autoDismissMs,
        scrollable = scrollable,
        copyText = copyText,
        voicePrompt = voicePrompt,
        onVoiceApprove = if (voicePrompt != null) { { } } else null,
        onVoiceDeny = if (voicePrompt != null) { { } } else null
    )

    // ---------------------------------------------------------------------------------------------
    // Tone: the state has to be readable before the words are
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a blocking approval reads as awaiting the user`() {
        val content = panel(
            title = "Tap \u201CSend\u201D?",
            body = "This posts to the chat.",
            actions = listOf(PanelAction("Approve", primary = true) {}, PanelAction("Cancel") {}),
            dismissible = false,
            voicePrompt = "Tap send? Say yes or no."
        )
        assertEquals(PanelTone.Await, PanelStyle.toneOf(content))
        assertEquals("Needs your OK", PanelStyle.eyebrow(PanelTone.Await))
    }

    @Test
    fun `a step in flight reads as working`() {
        val content = panel(
            title = "Step 2 of 5",
            body = "Opening the conversation.",
            actions = listOf(PanelAction("Stop") {}),
            dismissible = false
        )
        assertEquals(PanelTone.Working, PanelStyle.toneOf(content))
    }

    @Test
    fun `a progress line with no buttons still reads as working`() {
        val content = panel(title = "Summarizing", body = "Asking your AI model.", dismissible = false)
        assertEquals(PanelTone.Working, PanelStyle.toneOf(content))
    }

    @Test
    fun `a fully verified trace reads as verified`() {
        val content = panel(
            title = "Task complete",
            body = "Sent the message.\n\n1. Open WhatsApp\n   confirmed: the app came to the front\n" +
                "2. Tap \"Send\"\n   confirmed: the field cleared",
            scrollable = true
        )
        assertEquals(PanelTone.Verified, PanelStyle.toneOf(content))
        assertEquals("Verified", PanelStyle.eyebrow(PanelTone.Verified))
    }

    @Test
    fun `a trace holding an unconfirmed step steps down to caution`() {
        val content = panel(
            title = "Task complete",
            body = "1. Tap \"Send\"\n   not confirmed by the system",
            scrollable = true
        )
        assertEquals(PanelTone.Caution, PanelStyle.toneOf(content))
    }

    @Test
    fun `a refusal reads as alert not info`() {
        val content = panel(
            title = "Couldn't insert text",
            body = "No text field has focus.",
            actions = listOf(PanelAction("Close", primary = true) {})
        )
        assertEquals(PanelTone.Alert, PanelStyle.toneOf(content))
    }

    @Test
    fun `a one line acknowledgement wears no eyebrow`() {
        assertEquals("", PanelStyle.eyebrow(PanelTone.Info))
        val content = panel(title = "Your Bubble", body = "Hold the bubble and speak.")
        assertEquals(PanelTone.Info, PanelStyle.toneOf(content))
    }

    // ---------------------------------------------------------------------------------------------
    // Shape
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a self erasing line with no buttons becomes a pill`() {
        val content = panel(title = "Tapped \"Send\"", autoDismissMs = 1200L)
        assertEquals(PanelShape.Pill, PanelStyle.shapeOf(content))
    }

    @Test
    fun `a pill never swallows a long body`() {
        val content = panel(title = "Playback toggled", body = "x".repeat(400), autoDismissMs = 2200L)
        assertEquals(PanelShape.Sheet, PanelStyle.shapeOf(content))
    }

    @Test
    fun `anything the user can act on stays a full card`() {
        val content = panel(title = "Screen text", body = "hello", scrollable = true, actions = listOf(PanelAction("Copy") {}))
        assertEquals(PanelShape.Sheet, PanelStyle.shapeOf(content))
    }

    @Test
    fun `a blocking panel is never shrunk to a pill`() {
        val content = panel(title = "Working", body = "one step", dismissible = false, autoDismissMs = 4000L)
        assertEquals(PanelShape.Sheet, PanelStyle.shapeOf(content))
    }

    // ---------------------------------------------------------------------------------------------
    // Body
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a numbered trace is detected whatever the caller titled it`() {
        val trace = "1. Open WhatsApp\n   confirmed: app is foreground\n" +
            "2. Tap \"Send\"\n   confirmed: the field cleared"
        assertEquals(PanelBodyKind.Trace, PanelStyle.blockKind(trace, scrollable = true))
    }

    @Test
    fun `quoted screen text is detected as a quote`() {
        assertEquals(PanelBodyKind.Quote, PanelStyle.blockKind("\u201CPriya, on my way\u201D", scrollable = false))
    }

    @Test
    fun `a long unstructured run reads as reading material`() {
        val screenText = (1..12).joinToString("\n") { "Line $it of the screen" }
        assertEquals(PanelBodyKind.Reading, PanelStyle.blockKind(screenText, scrollable = true))
    }

    @Test
    fun `a single short line is a status line`() {
        assertEquals(PanelBodyKind.Status, PanelStyle.blockKind("Asking your AI model.", scrollable = false))
    }

    @Test
    fun `blank lines split the body into paragraphs and mark the last one`() {
        val blocks = PanelStyle.blocks("Headline\n\n1. Tap \"Send\"\n   confirmed: it cleared\n\nDone.", scrollable = true)
        assertEquals(3, blocks.size)
        assertEquals(PanelBodyKind.Status, blocks[0].kind)
        assertEquals(PanelBodyKind.Trace, blocks[1].kind)
        assertTrue(blocks[2].isLast)
    }

    @Test
    fun `an empty body has no blocks at all`() {
        assertTrue(PanelStyle.blocks("   \n", scrollable = true).isEmpty())
    }

    @Test
    fun `a short trailing paragraph is treated as a caption not content`() {
        val blocks = PanelStyle.blocks("Phone \u00B7 +91\n\nEmail \u00B7 a@b\n\nI won't do anything until you choose.", scrollable = true)
        assertTrue(PanelStyle.isCaption(blocks.last(), blocks.size))
        assertFalse(PanelStyle.isCaption(blocks.first(), blocks.size))
    }

    @Test
    fun `truncation keeps the head and never exceeds the budget`() {
        val long = (1..400).joinToString("\n") { "Step $it did a thing" }
        val clamped = PanelStyle.clampBody(long, 300)
        assertTrue(clamped.length <= 300)
        assertTrue(clamped.startsWith("Step 1 did a thing"))
        assertTrue(clamped.endsWith("\u2026"))
    }

    @Test
    fun `text inside the budget is returned untouched`() {
        assertEquals("Short line", PanelStyle.clampBody("Short line", 720))
    }

    @Test
    fun `only scrollable bodies get room to scroll into`() {
        assertTrue(PanelStyle.bodyMaxHeightDp(PanelBodyKind.Trace, scrollable = true) > 0f)
        assertEquals(0f, PanelStyle.bodyMaxHeightDp(PanelBodyKind.Trace, scrollable = false), 0f)
        assertEquals(0f, PanelStyle.bodyMaxHeightDp(PanelBodyKind.Status, scrollable = true), 0f)
    }

    // ---------------------------------------------------------------------------------------------
    // Buttons
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `approve is primary and cancel is a tinted outline`() {
        val approve = PanelStyle.roleOf("Approve", true, PanelTone.Await, "Tap \u201CSave\u201D?", "Saves the draft.", 2)
        val cancel = PanelStyle.roleOf("Cancel", false, PanelTone.Await, "Tap \u201CSave\u201D?", "Saves the draft.", 2)
        assertEquals(ActionRole.Primary, approve)
        assertEquals(ActionRole.Secondary, cancel)
    }

    @Test
    fun `an approval that sends data out is painted outbound`() {
        val role = PanelStyle.roleOf(
            "Approve", true, PanelTone.Await,
            "Send screen text to AI?", "120 characters go to api.example.com.", 2
        )
        assertEquals(ActionRole.Outbound, role)
    }

    @Test
    fun `the cancel beside an outbound approval never takes the outbound paint`() {
        val role = PanelStyle.roleOf(
            "Cancel", false, PanelTone.Await,
            "Send screen text to AI?", "120 characters go to api.example.com.", 2
        )
        assertEquals(ActionRole.Secondary, role)
    }

    @Test
    fun `an outbound verb in the label paints even after the work is done`() {
        val role = PanelStyle.roleOf("Call", false, PanelTone.Info, "Actions on this screen", "+91 90000 00000", 3)
        assertEquals(ActionRole.Outbound, role)
    }

    @Test
    fun `an app name in a choice list is not mistaken for an outbound verb`() {
        val role = PanelStyle.roleOf("WhatsApp", false, PanelTone.Await, "Which app?", "I found more than one match.", 3)
        assertEquals(ActionRole.Secondary, role)
    }

    @Test
    fun `a quoted body mentioning sending does not turn the copy button crimson`() {
        val role = PanelStyle.roleOf(
            "Copy", true, PanelTone.Verified, "Screen text",
            "\u201CSend me the file when you can\u201D", 2
        )
        assertEquals(ActionRole.Primary, role)
    }

    @Test
    fun `a dismissive neighbour of a primary action goes quiet`() {
        val role = PanelStyle.roleOf("Close", false, PanelTone.Verified, "Text copied", "Pasted into the field.", 2)
        assertEquals(ActionRole.Quiet, role)
    }

    @Test
    fun `a lone stop button is still a real button`() {
        val role = PanelStyle.roleOf("Stop", false, PanelTone.Working, "Step 2 of 5", "Opening the chat.", 1)
        assertEquals(ActionRole.Secondary, role)
    }

    @Test
    fun `the only way out of a result card is the primary button`() {
        val role = PanelStyle.roleOf("Close", true, PanelTone.Alert, "Couldn't undo", "The field changed.", 1)
        assertEquals(ActionRole.Primary, role)
    }

    @Test
    fun `two short buttons share a row and a long pair stacks`() {
        assertEquals(ActionLayout.Row, PanelStyle.layoutOf(listOf("Approve", "Cancel")))
        assertEquals(ActionLayout.Stack, PanelStyle.layoutOf(listOf("Open Your Bubble settings", "Cancel")))
        assertEquals(ActionLayout.Single, PanelStyle.layoutOf(listOf("Done")))
        assertEquals(ActionLayout.Single, PanelStyle.layoutOf(emptyList()))
    }

    @Test
    fun `a row gives the affirmative button the wider half`() {
        val weights = PanelStyle.weightsFor(listOf(ActionRole.Secondary, ActionRole.Primary))
        assertTrue(weights[1] > weights[0])
    }

    @Test
    fun `a row puts the committing button on the right`() {
        val order = PanelStyle.orderedFor(listOf(ActionRole.Primary, ActionRole.Secondary), PanelTone.Await)
        assertEquals(listOf(1, 0), order)
        val alreadyRight = PanelStyle.orderedFor(listOf(ActionRole.Secondary, ActionRole.Primary), PanelTone.Await)
        assertEquals(listOf(0, 1), alreadyRight)
    }

    @Test
    fun `choice lists keep the caller's order`() {
        val roles = listOf(ActionRole.Secondary, ActionRole.Secondary, ActionRole.Secondary)
        assertEquals(listOf(0, 1, 2), PanelStyle.orderedFor(roles, PanelTone.Await))
    }

    @Test
    fun `row labels are compact and stacked labels keep their wording`() {
        assertEquals("NOT NOW", PanelStyle.buttonLabel("Not now", ActionLayout.Row))
        assertEquals("Open Your Bubble settings", PanelStyle.buttonLabel("Open Your Bubble settings", ActionLayout.Stack))
    }

    @Test
    fun `destructive labels are recognised for tests and tools`() {
        assertTrue(PanelStyle.isDestructive("Send", "Send this message?", "Goes to Priya."))
        assertFalse(PanelStyle.isDestructive("Close", "Text copied", "Pasted into the field."))
    }

    // ---------------------------------------------------------------------------------------------
    // Fallbacks and timers
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the copy fallback appears only when nothing already says copy`() {
        assertTrue(PanelStyle.shouldAppendCopy("some text", listOf("Close")))
        assertFalse(PanelStyle.shouldAppendCopy("some text", listOf("copy")))
        assertFalse(PanelStyle.shouldAppendCopy(null, listOf("Close")))
        assertFalse(PanelStyle.shouldAppendCopy("", listOf("Close")))
    }

    @Test
    fun `a timer arms only when it is a real duration`() {
        assertFalse(PanelStyle.armsAutoDismiss(null))
        assertFalse(PanelStyle.armsAutoDismiss(0L))
        assertFalse(PanelStyle.armsAutoDismiss(-1L))
        assertTrue(PanelStyle.armsAutoDismiss(1200L))
    }

    @Test
    fun `remaining time after a screen read is never negative`() {
        assertEquals(0L, PanelStyle.remainingDismissMs(deadlineUptimeMs = 1000L, nowUptimeMs = 4000L))
        assertEquals(400L, PanelStyle.remainingDismissMs(deadlineUptimeMs = 1400L, nowUptimeMs = 1000L))
    }

    @Test
    fun `the hands free hint needs a prompt and a handler to answer it`() {
        assertTrue(PanelStyle.showHandsFreeHint(hasVoicePrompt = true, hasApproveHandler = true))
        assertFalse(PanelStyle.showHandsFreeHint(hasVoicePrompt = true, hasApproveHandler = false))
        assertFalse(PanelStyle.showHandsFreeHint(hasVoicePrompt = false, hasApproveHandler = true))
    }

    // ---------------------------------------------------------------------------------------------
    // Colour maths used by the view layer
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `alpha mixing keeps the hue and replaces only the alpha byte`() {
        val base = 0xFF112233.toInt()
        assertEquals(base, PanelStyle.withAlpha(base, 100))
        assertEquals(0x80112233.toInt(), PanelStyle.withAlpha(base, 50))
        assertEquals(0x00112233, PanelStyle.withAlpha(base, 0))
        assertEquals(0x00ABCDEF, PanelStyle.withAlpha(0x007ABCDEF, 0))
    }

    @Test
    fun `alpha percentages outside the range are clamped`() {
        assertEquals(0xFF112233.toInt(), PanelStyle.withAlpha(0x112233, 250))
        assertEquals(0x00112233, PanelStyle.withAlpha(0x112233, -20))
    }

    @Test
    fun `card geometry keeps the designed radii and touch floor`() {
        assertTrue(PanelStyle.CARD_RADIUS_DP >= 26f && PanelStyle.CARD_RADIUS_DP <= 30f)
        assertTrue(PanelStyle.BUTTON_HEIGHT_DP >= PanelStyle.MIN_TOUCH_DP)
        assertEquals(2, PanelStyle.titleMaxLines(PanelShape.Pill))
    }
}
