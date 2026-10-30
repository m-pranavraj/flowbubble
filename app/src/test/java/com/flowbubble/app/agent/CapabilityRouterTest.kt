package com.flowbubble.app.agent

import com.flowbubble.app.voice.Command
import com.flowbubble.app.voice.FollowUpKind
import com.flowbubble.app.voice.SearchScope
import com.flowbubble.app.voice.VolumeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityRouterTest {

    private val off = CapabilityContext()
    private val on = CapabilityContext(
        accessibility = true,
        screenCapture = true,
        notificationAccess = true,
        dndPolicy = true,
        contacts = true,
        aiConfigured = true
    )

    private fun gated(command: Command, context: CapabilityContext = off): Route.Gated {
        val route = CapabilityRouter.route(command, context)
        assertTrue("expected a gate for $command, got $route", route is Route.Gated)
        route as Route.Gated
        assertTrue("$command gated with no gap to open", route.gaps.isNotEmpty())
        return route
    }

    private fun ready(command: Command, context: CapabilityContext = on): Route.Ready {
        val route = CapabilityRouter.route(command, context)
        assertTrue("expected $command to be ready, got $route", route is Route.Ready)
        route as Route.Ready
        assertFalse("$command has no mechanism", route.mechanism.isBlank())
        return route
    }

    @Test
    fun `launching an app needs no switch and never gates`() {
        val route = ready(Command.OpenApp("chrome"), off)
        assertEquals("open_app", route.capability)
        assertEquals("package launch", route.mechanism)
    }

    @Test
    fun `an empty app name is unsupported rather than a guess`() {
        val route = CapabilityRouter.route(Command.OpenApp("   "), off)
        assertTrue(route is Route.Unsupported)
        assertTrue((route as Route.Unsupported).reason.isNotBlank())
    }

    @Test
    fun `every screen touching command waits for the accessibility switch`() {
        val commands = listOf(
            Command.Tap("login"),
            Command.Scroll(true, 2),
            Command.Back,
            Command.Home,
            Command.Recents,
            Command.ReadScreen,
            Command.ScreenActions,
            Command.Screenshot,
            Command.TypeText("hello"),
            Command.AgentTask("scroll until the buy button appears")
        )
        for (command in commands) {
            val gap = gated(command).gaps.first()
            assertEquals(SettingsTarget.ACCESSIBILITY, gap.target)
            assertFalse(gap.title.isBlank())
            assertFalse(gap.detail.isBlank())
        }
    }

    @Test
    fun `a service that is enabled but no longer sees windows is reported as stale, not as off`() {
        val stale = CapabilityContext(accessibility = true, accessibilityResponsive = false)
        val gap = gated(Command.ReadScreen, stale).gaps.first()
        assertEquals(SettingsTarget.ACCESSIBILITY, gap.target)
        assertTrue(gap.title.contains("stopped responding"))
        assertTrue(gap.detail.contains("off and on"))
    }

    @Test
    fun `screen commands are ready once accessibility is on and say whether the screen is visible`() {
        val withoutCapture = ready(Command.ReadScreen, CapabilityContext(accessibility = true))
        assertEquals("accessibility tree", withoutCapture.mechanism)
        assertNotNull(withoutCapture.note)
        assertTrue(withoutCapture.note!!.contains("Screen access is off"))

        val withCapture = ready(Command.ReadScreen)
        assertEquals("accessibility + screen reading", withCapture.mechanism)
        assertNull(withCapture.note)
    }

    @Test
    fun `a web search runs without any switch but searching inside an app needs the screen`() {
        assertEquals("web_search", ready(Command.Search("lofi", SearchScope.WEB), off).capability)
        assertEquals(SettingsTarget.ACCESSIBILITY, gated(Command.Search("lofi", SearchScope.CURRENT)).gaps.first().target)
        assertEquals("search_here", ready(Command.Search("lofi", SearchScope.CURRENT)).capability)
        assertEquals("search_app", ready(Command.Search("lofi", SearchScope.CURRENT, "YouTube")).capability)
    }

    @Test
    fun `calls and messages stay gated until contacts are allowed, then still hand the last tap to the user`() {
        assertEquals(SettingsTarget.CONTACTS, gated(Command.Call("mom")).gaps.first().target)
        assertEquals(SettingsTarget.CONTACTS, gated(Command.SendMessage("mom", "on my way")).gaps.first().target)

        val call = ready(Command.Call("mom"))
        assertTrue(call.note!!.contains("tap"))
        val message = ready(Command.SendMessage("mom", "on my way"))
        assertTrue(message.note!!.contains("send"))
    }

    @Test
    fun `opening a chat needs contacts, and the user still writes the message`() {
        assertEquals(SettingsTarget.CONTACTS, gated(Command.OpenChat("Pranav")).gaps.first().target)

        val chat = ready(Command.OpenChat("Pranav", "whatsapp"))
        assertEquals("open_chat", chat.capability)
        assertTrue(chat.note!!.contains("write"))
    }

    @Test
    fun `notifications and do not disturb each need their own switch`() {
        assertEquals(SettingsTarget.NOTIFICATION_LISTENER, gated(Command.ReadNotifications).gaps.first().target)
        assertEquals(SettingsTarget.NOTIFICATION_LISTENER, gated(Command.ClearNotifications).gaps.first().target)
        assertEquals("notifications", ready(Command.ReadNotifications).capability)

        assertEquals(SettingsTarget.DO_NOT_DISTURB, gated(Command.Dnd(true, 30)).gaps.first().target)
        assertEquals("do_not_disturb", ready(Command.Dnd(true, 30)).capability)
    }

    @Test
    fun `answering about the screen needs the screen first and then a model`() {
        val question = Command.AskScreen("what's the total")

        assertEquals(SettingsTarget.ACCESSIBILITY, gated(question).gaps.first().target)

        val noModel = gated(question, CapabilityContext(accessibility = true))
        assertEquals(SettingsTarget.AI_SETTINGS, noModel.gaps.first().target)
        assertEquals("ask_screen", noModel.capability)

        val route = ready(question)
        assertEquals("ask_screen", route.capability)
        assertTrue(route.note!!.contains("sent"))

        assertEquals(SettingsTarget.AI_SETTINGS, gated(Command.TranslateScreen("hi"), on.copy(aiConfigured = false)).gaps.first().target)
        assertEquals("translate_screen", ready(Command.TranslateScreen("hi")).capability)
    }

    @Test
    fun `undo and replay never need to see the screen but re-editing it does`() {
        assertEquals("follow_up", ready(Command.FollowUp(FollowUpKind.UNDO), off).capability)
        assertEquals("follow_up", ready(Command.FollowUp(FollowUpKind.AGAIN), off).capability)
        assertEquals(SettingsTarget.ACCESSIBILITY, gated(Command.FollowUp(FollowUpKind.TIDY)).gaps.first().target)
    }

    @Test
    fun `routines are always routable because their own steps carry the gates`() {
        assertEquals("routine", ready(Command.SaveRoutine("morning brief"), off).capability)
        assertEquals("routine", ready(Command.RunRoutine("morning brief"), off).capability)
        assertEquals("routine", ready(Command.ListRoutines, off).capability)
    }

    @Test
    fun `plain system intents are ready with nothing granted`() {
        val commands = listOf(
            Command.OpenUrl("https://example.com"),
            Command.WebSearch("weather"),
            Command.SetTimer(60),
            Command.SetAlarm(7, 30),
            Command.NewEvent("standup"),
            Command.ComposeEmail("mom", "hello"),
            Command.Navigate("airport"),
            Command.OpenSettings,
            Command.BatteryStatus,
            Command.Flashlight(true),
            Command.Volume(VolumeAction.UP, 2),
            Command.MediaToggle
        )
        for (command in commands) {
            val route = ready(command, off)
            assertFalse("$command routed to a blank capability", route.capability.isBlank())
        }
    }

    @Test
    fun `a gate always names a screen the app can actually open`() {
        val commands = listOf(
            Command.Tap("ok"),
            Command.Call("mom"),
            Command.Dnd(false),
            Command.ReadNotifications,
            Command.AskScreen("what is this")
        )
        for (command in commands) {
            for (gap in gated(command).gaps) {
                assertNotNull(gap.target)
            }
        }
    }

    @Test
    fun `no command is silently dropped because every branch returns a route`() {
        val context = CapabilityContext(accessibility = true)
        val commands: List<Command> = listOf(
            Command.OpenApp("maps"), Command.SetTimer(5), Command.SetAlarm(6, 7),
            Command.Call("mom"), Command.SendMessage("mom", "hi"), Command.Navigate("home"),
            Command.Tap("x"), Command.Scroll(false), Command.Back, Command.Home, Command.Recents,
            Command.ReadScreen, Command.OpenSettings, Command.Dnd(true), Command.TypeText("t"),
            Command.Screenshot, Command.ReadNotifications, Command.ClearNotifications,
            Command.OpenUrl("https://a"), Command.WebSearch("q"), Command.Search("q"),
            Command.AgentTask("g"), Command.AskScreen("q?"), Command.TranslateScreen("en"),
            Command.MediaToggle, Command.FollowUp(FollowUpKind.COPY), Command.SaveRoutine("n"),
            Command.RunRoutine("n"), Command.DeleteRoutine("n"), Command.ListRoutines,
            Command.Flashlight(false), Command.Volume(VolumeAction.MUTE), Command.BatteryStatus,
            Command.ComposeEmail("a", "b"), Command.NewEvent("c"), Command.ScreenActions,
            Command.OpenChat("mom", "whatsapp")
        )
        for (command in commands) {
            val route = CapabilityRouter.route(command, context)
            when (route) {
                is Route.Ready -> assertFalse(route.capability.isBlank())
                is Route.Gated -> assertTrue(route.gaps.isNotEmpty())
                is Route.Unsupported -> assertFalse(route.reason.isBlank())
            }
        }
        assertEquals(37, commands.size)
    }
}
