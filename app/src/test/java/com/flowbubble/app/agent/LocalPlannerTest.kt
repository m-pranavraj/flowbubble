package com.flowbubble.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlannerTest {

    @Test
    fun `plans an app open followed by an in-app search`() {
        val plan = LocalPlanner.plan("open WhatsApp and search for Sarah")
        assertNotNull(plan)
        val steps = plan!!.steps
        assertEquals(PlanSource.LOCAL, plan.source)
        assertEquals(3, steps.size)
        assertEquals(AgentAction.OpenApp("WhatsApp"), steps[0].action)
        assertTrue(steps[1].action is AgentAction.Wait)
        val type = steps[2].action as AgentAction.TypeText
        assertEquals("Sarah", type.text)
        assertTrue(type.pressEnter)
        assertTrue(type.searchFieldOnly)
    }

    @Test
    fun `plans a url open and a follow-up search`() {
        val plan = LocalPlanner.plan("open youtube.com then search for lo-fi beats")
        assertNotNull(plan)
        val steps = plan!!.steps
        assertEquals(AgentAction.OpenUrl("https://youtube.com"), steps[0].action)
        val type = steps.last().action as AgentAction.TypeText
        assertEquals("lo-fi beats", type.text)
        assertTrue(type.searchFieldOnly)
    }

    @Test
    fun `bare searches go to the web without app context`() {
        val plan = LocalPlanner.plan("search for protein powder")
        assertNotNull(plan)
        val action = plan!!.steps.first().action as AgentAction.OpenUrl
        assertTrue(action.url.startsWith("https://www.google.com/search?q="))
        assertTrue(action.url.contains("protein"))
    }

    @Test
    fun `google for strips the filler word`() {
        val plan = LocalPlanner.plan("google for weather")
        val action = plan!!.steps.first().action as AgentAction.OpenUrl
        assertTrue(action.url.contains("weather"))
        assertTrue(!action.url.contains("for+weather"))
    }

    @Test
    fun `plans ordinal taps and repeated scrolls`() {
        val tap = LocalPlanner.plan("tap the second result")!!
        assertEquals(AgentAction.TapTarget("second result", 2), tap.steps.first().action)

        val scroll = LocalPlanner.plan("scroll down twice")!!
        assertEquals(AgentAction.Scroll(true, 2), scroll.steps.first().action)
    }

    @Test
    fun `plans scroll until a label is visible`() {
        val plan = LocalPlanner.plan("scroll down until you see contact info")!!
        val action = plan.steps.first().action as AgentAction.ScrollUntil
        assertEquals("contact info", action.label)
        assertEquals(false, action.tapAfter)

        val find = LocalPlanner.plan("find the settings by scrolling down")!!
        val findAction = find.steps.first().action as AgentAction.ScrollUntil
        assertEquals("the settings", findAction.label)
    }

    @Test
    fun `scroll until upgrades to a tap when asked`() {
        val plan = LocalPlanner.plan("scroll to refund policy and tap it")!!
        assertEquals(1, plan.steps.size)
        val action = plan.steps.first().action as AgentAction.ScrollUntil
        assertEquals("refund policy", action.label)
        assertTrue(action.tapAfter)
    }

    @Test
    fun `plans screen questions for the AI step`() {
        val plan = LocalPlanner.plan("translate this screen to Spanish")!!
        assertTrue(plan.steps.first().action is AgentAction.AskAi)
    }

    @Test
    fun `returns null for dictation instructions`() {
        assertNull(LocalPlanner.plan("make this sound professional"))
        assertNull(LocalPlanner.plan(""))
    }

    @Test
    fun `rejects an overlong chain`() {
        assertNull(
            LocalPlanner.plan(
                "open A then open B then open C then open D then open E"
            )
        )
    }

    @Test
    fun `canonicalizes spoken and plain domains`() {
        assertEquals("https://youtube.com", ActionParsing.canonicalUrl("youtube.com"))
        assertEquals("https://openai.com", ActionParsing.canonicalUrl("openai dot com"))
        assertEquals("https://example.com/path", ActionParsing.canonicalUrl("https://example.com/path"))
        assertNull(ActionParsing.canonicalUrl("not a domain"))
        assertNull(ActionParsing.canonicalUrl("ftp://example.com"))
    }

    @Test
    fun `plans side swipes without stealing vertical scrolls`() {
        assertEquals(
            AgentAction.Swipe(toRight = false, times = 1),
            LocalPlanner.plan("swipe left")!!.steps.single().action
        )
        assertEquals(
            AgentAction.Swipe(toRight = true, times = 2),
            LocalPlanner.plan("swipe to the right twice")!!.steps.single().action
        )
        assertTrue(LocalPlanner.plan("swipe down")!!.steps.single().action is AgentAction.Scroll)
    }

    @Test
    fun `plans the menu, close, and clipboard controls`() {
        assertEquals(AgentAction.OpenMenu, LocalPlanner.plan("open the three dot menu")!!.steps.single().action)
        assertEquals(AgentAction.OpenMenu, LocalPlanner.plan("tap more options")!!.steps.single().action)
        assertEquals(AgentAction.Dismiss, LocalPlanner.plan("dismiss the popup")!!.steps.single().action)
        assertEquals(AgentAction.Dismiss, LocalPlanner.plan("close this")!!.steps.single().action)
        assertEquals(AgentAction.CopySelection, LocalPlanner.plan("copy")!!.steps.single().action)
        assertEquals(AgentAction.Paste, LocalPlanner.plan("paste it")!!.steps.single().action)
        assertEquals(AgentAction.SelectAll, LocalPlanner.plan("select all")!!.steps.single().action)
        assertEquals(AgentAction.ClearField, LocalPlanner.plan("clear the search box")!!.steps.single().action)
    }

    @Test
    fun `plans a long press with hold time and ordinal`() {
        val plain = LocalPlanner.plan("long press the message")!!.steps.single().action
        assertEquals(AgentAction.LongPress("message", null, 650), plain)

        val timed = LocalPlanner.plan("press and hold the blue button for 2 seconds")!!
            .steps.single().action as AgentAction.LongPress
        assertEquals("blue button", timed.label)
        assertEquals(2000, timed.holdMs)

        val numbered = LocalPlanner.plan("long press the second item")!!
            .steps.single().action as AgentAction.LongPress
        assertEquals(2, numbered.ordinal)
    }

    @Test
    fun `waits after a gesture that changes the screen`() {
        val plan = LocalPlanner.plan("long press the first item and tap delete")
        assertNotNull(plan)
        val actions = plan!!.steps.map { it.action }
        assertTrue(actions[0] is AgentAction.LongPress)
        assertTrue(actions[1] is AgentAction.Wait)
        assertTrue(actions[2] is AgentAction.TapTarget)

        val chained = LocalPlanner.plan("open the menu and swipe left")
        assertNotNull(chained)
        val gestureSteps = chained!!.steps.map { it.action }
        assertEquals(AgentAction.OpenMenu, gestureSteps[0])
        assertTrue(gestureSteps[1] is AgentAction.Wait)
        assertEquals(AgentAction.Swipe(toRight = false, times = 1), gestureSteps[2])
    }

    @Test
    fun `splits copy and paste but keeps press and hold together`() {
        assertEquals(
            listOf<AgentAction>(AgentAction.CopySelection, AgentAction.Paste),
            LocalPlanner.plan("copy and paste")!!.steps.map { it.action }
        )
        val held = LocalPlanner.plan("press and hold the blue button")!!.steps.single().action
        assertEquals(AgentAction.LongPress("blue button", null, 650), held)
    }

    @Test
    fun `recognizes control phrases but not ordinary taps`() {
        assertTrue(LocalPlanner.isControlPhrase("swipe left"))
        assertTrue(LocalPlanner.isControlPhrase("open the menu"))
        assertTrue(LocalPlanner.isControlPhrase("paste"))
        assertFalse(LocalPlanner.isControlPhrase("close the app"))
        assertFalse(LocalPlanner.isControlPhrase("tap the button"))
        assertFalse(LocalPlanner.isControlPhrase("copy the whole paragraph please"))
        assertFalse(LocalPlanner.isControlPhrase(""))
    }
}
