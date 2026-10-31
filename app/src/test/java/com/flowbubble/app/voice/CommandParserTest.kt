package com.flowbubble.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandParserTest {

    @Test
    fun `opens an app by name`() {
        assertEquals(Command.OpenApp("YouTube"), CommandParser.parse("open YouTube"))
        assertEquals(Command.OpenApp("Google Maps"), CommandParser.parse("launch Google Maps"))
    }

    @Test
    fun `open settings uses the system route`() {
        assertEquals(Command.OpenSettings, CommandParser.parse("open settings"))
        assertEquals(Command.OpenSettings, CommandParser.parse("settings"))
    }

    @Test
    fun `parses a message with saying`() {
        assertEquals(
            Command.SendMessage("Sarah", "I'm on my way"),
            CommandParser.parse("text Sarah saying I'm on my way")
        )
    }

    @Test
    fun `parses a message with that says`() {
        assertEquals(
            Command.SendMessage("Priya", "I will be late"),
            CommandParser.parse("send a message to Priya that says I will be late")
        )
    }

    @Test
    fun `parses a compound open and text command`() {
        assertEquals(
            Command.SendMessage("Sarah", "I'm on my way"),
            CommandParser.parse("open WhatsApp and text Sarah I'm on my way")
        )
    }

    @Test
    fun `parses WhatsApp prefix`() {
        assertEquals(
            Command.SendMessage("Arjun", "running late"),
            CommandParser.parse("whatsapp Arjun running late")
        )
    }

    @Test
    fun `go to chat of a person opens the conversation, not an app lookup`() {
        assertEquals(Command.OpenChat("Pranav"), CommandParser.parse("go to chat of Pranav"))
        assertEquals(Command.OpenChat("Pranav"), CommandParser.parse("go to the chat with Pranav"))
        assertEquals(Command.OpenChat("Pranav"), CommandParser.parse("open chat with Pranav"))
        assertEquals(Command.OpenChat("Mom"), CommandParser.parse("chat with Mom"))
    }

    @Test
    fun `a chat request names the app before or after the person`() {
        assertEquals(Command.OpenChat("Pranav", "whatsapp"), CommandParser.parse("chat with Pranav on WhatsApp"))
        assertEquals(
            Command.OpenChat("Pranav", "whatsapp business"),
            CommandParser.parse("open WhatsApp Business chat with Pranav")
        )
        assertEquals(
            Command.OpenChat("Priya", "messages"),
            CommandParser.parse("go to messages chat with Priya")
        )
    }

    @Test
    fun `a bare whatsapp name with no body opens that chat instead of a draft`() {
        assertEquals(Command.OpenChat("Pranav", "whatsapp"), CommandParser.parse("whatsapp Pranav"))
        assertEquals(
            Command.SendMessage("Arjun", "running late"),
            CommandParser.parse("whatsapp Arjun running late")
        )
    }

    @Test
    fun `chat with a pronoun stays dictation instead of becoming a contact lookup`() {
        assertNull(CommandParser.parse("chat with you later"))
    }

    @Test
    fun `a chat phrase does not swallow a message being sent`() {
        assertEquals(
            Command.SendMessage("Sarah", "I'm on my way"),
            CommandParser.parse("text Sarah I'm on my way")
        )
    }

    @Test
    fun `parses numeric and spoken timer durations`() {
        assertEquals(Command.SetTimer(600), CommandParser.parse("set a timer for 10 minutes"))
        assertEquals(Command.SetTimer(600), CommandParser.parse("set a timer for ten minutes"))
        assertEquals(Command.SetTimer(3600), CommandParser.parse("timer for one hour"))
    }

    @Test
    fun `rejects a zero timer`() {
        assertNull(CommandParser.parse("set a timer for 0 minutes"))
    }

    @Test
    fun `parses alarms in 12 and 24 hour form`() {
        assertEquals(Command.SetAlarm(7, 30), CommandParser.parse("set an alarm for 7:30 am"))
        assertEquals(Command.SetAlarm(21, 0), CommandParser.parse("set an alarm for 9 pm"))
    }

    @Test
    fun `parses do not disturb with duration`() {
        assertEquals(Command.Dnd(true, 60), CommandParser.parse("turn on do not disturb for one hour"))
        assertEquals(Command.Dnd(true, 30), CommandParser.parse("turn on dnd for 30 minutes"))
        assertEquals(Command.Dnd(false, null), CommandParser.parse("turn off dnd"))
    }

    @Test
    fun `parses ordinal taps`() {
        assertEquals(Command.Tap("second result", 2), CommandParser.parse("tap the second result"))
        assertEquals(Command.Tap("third option", 3), CommandParser.parse("select the third option"))
    }

    @Test
    fun `parses a tap by label`() {
        assertEquals(Command.Tap("Continue"), CommandParser.parse("tap Continue"))
    }

    @Test
    fun `parses scroll and navigation commands`() {
        assertEquals(Command.Scroll(true), CommandParser.parse("scroll down"))
        assertEquals(Command.Scroll(false), CommandParser.parse("scroll up"))
        assertEquals(Command.Back, CommandParser.parse("go back"))
        assertEquals(Command.Home, CommandParser.parse("home"))
        assertEquals(Command.Recents, CommandParser.parse("recent apps"))
    }

    @Test
    fun `parses read screen`() {
        assertEquals(Command.ReadScreen, CommandParser.parse("read this screen"))
        assertEquals(Command.ReadScreen, CommandParser.parse("what's on my screen"))
    }

    @Test
    fun `parses navigate and call`() {
        assertEquals(Command.Navigate("the nearest petrol station"), CommandParser.parse("navigate to the nearest petrol station"))
        assertEquals(Command.Call("mom"), CommandParser.parse("call mom"))
    }

    @Test
    fun `leaves free text for dictation or editing`() {
        assertNull(CommandParser.parse("I will reach in twenty minutes"))
        assertNull(CommandParser.parse("open"))
        assertNull(CommandParser.parse("let us meet near the station"))
    }

    @Test
    fun `parses follow-up rewrites`() {
        assertEquals(Command.FollowUp(FollowUpKind.SHORTER), CommandParser.parse("make it shorter"))
        assertEquals(Command.FollowUp(FollowUpKind.LONGER), CommandParser.parse("make this a bit longer"))
        assertEquals(
            Command.FollowUp(FollowUpKind.PROFESSIONAL),
            CommandParser.parse("make this sound professional")
        )
        assertEquals(Command.FollowUp(FollowUpKind.CASUAL), CommandParser.parse("make that more casual"))
        assertEquals(Command.FollowUp(FollowUpKind.TIDY), CommandParser.parse("fix the grammar"))
        assertEquals(Command.FollowUp(FollowUpKind.SUMMARIZE), CommandParser.parse("summarize that"))
        assertEquals(
            Command.FollowUp(FollowUpKind.TRANSLATE, "Spanish"),
            CommandParser.parse("translate this to Spanish")
        )
        assertEquals(
            Command.FollowUp(FollowUpKind.TRANSLATE, "Hindi"),
            CommandParser.parse("translate that into Hindi")
        )
    }

    @Test
    fun `parses undo repeat and copy follow-ups`() {
        assertEquals(Command.FollowUp(FollowUpKind.UNDO), CommandParser.parse("undo"))
        assertEquals(Command.FollowUp(FollowUpKind.UNDO), CommandParser.parse("undo that"))
        assertEquals(Command.FollowUp(FollowUpKind.AGAIN), CommandParser.parse("try that again"))
        assertEquals(Command.FollowUp(FollowUpKind.AGAIN), CommandParser.parse("do it again"))
        assertEquals(Command.FollowUp(FollowUpKind.COPY), CommandParser.parse("copy that"))
    }

    @Test
    fun `routes screen controls to the agent`() {
        assertEquals(Command.AgentTask("swipe left"), CommandParser.parse("swipe left"))
        assertEquals(Command.AgentTask("open the menu"), CommandParser.parse("open the menu"))
        assertEquals(Command.AgentTask("copy"), CommandParser.parse("copy"))
        assertEquals(Command.AgentTask("paste"), CommandParser.parse("paste"))
        assertEquals(Command.AgentTask("select all"), CommandParser.parse("select all"))
        assertEquals(Command.AgentTask("clear the field"), CommandParser.parse("clear the field"))
        assertEquals(Command.AgentTask("dismiss"), CommandParser.parse("dismiss"))
        assertEquals(
            Command.AgentTask("long press the message"),
            CommandParser.parse("long press the message")
        )
    }

    @Test
    fun `keeps control words out of the tap and app paths only when they name a gesture`() {
        assertEquals(Command.Tap("button"), CommandParser.parse("tap the button"))
        assertEquals(Command.OpenApp("YouTube"), CommandParser.parse("open YouTube"))
        assertEquals(Command.Scroll(true, 1), CommandParser.parse("scroll down"))
        assertNull(CommandParser.parse("close the app"))
    }

    @Test
    fun `parses routine management`() {
        assertEquals(
            Command.SaveRoutine("morning", "open YouTube"),
            CommandParser.parse("save a routine called morning for open YouTube")
        )
        assertEquals(
            Command.SaveRoutine("commute", null),
            CommandParser.parse("save this as routine commute")
        )
        assertEquals(Command.RunRoutine("morning"), CommandParser.parse("run routine morning"))
        assertEquals(Command.DeleteRoutine("morning"), CommandParser.parse("delete routine morning"))
        assertEquals(Command.ListRoutines, CommandParser.parse("list my routines"))
    }

    @Test
    fun `parses os controls`() {
        assertEquals(Command.Flashlight(true), CommandParser.parse("turn on the flashlight"))
        assertEquals(Command.Flashlight(false), CommandParser.parse("flashlight off"))
        assertEquals(Command.Volume(VolumeAction.UP), CommandParser.parse("volume up"))
        assertEquals(Command.Volume(VolumeAction.DOWN, 3), CommandParser.parse("turn the volume down three"))
        assertEquals(Command.Volume(VolumeAction.MUTE), CommandParser.parse("mute"))
        assertEquals(Command.Volume(VolumeAction.UNMUTE), CommandParser.parse("unmute the media volume"))
        assertEquals(Command.BatteryStatus, CommandParser.parse("how's my battery"))
        assertEquals(Command.BatteryStatus, CommandParser.parse("battery status"))
    }

    @Test
    fun `parses email calendar and screen actions`() {
        assertEquals(
            Command.ComposeEmail("sarah@example.com", "trip plans"),
            CommandParser.parse("compose an email to sarah@example.com about trip plans")
        )
        assertEquals(
            Command.ComposeEmail("mom", "dinner"),
            CommandParser.parse("email mom about dinner")
        )
        assertEquals(
            Command.NewEvent("lunch with Sam"),
            CommandParser.parse("create an event called lunch with Sam")
        )
        assertEquals(Command.ScreenActions, CommandParser.parse("what can i do here"))
        assertEquals(Command.ScreenActions, CommandParser.parse("show me quick actions"))
    }

    @Test
    fun `routes scroll to find through the agent`() {
        assertEquals(
            Command.AgentTask("scroll down until you see contact info"),
            CommandParser.parse("scroll down until you see contact info")
        )
        assertEquals(
            Command.AgentTask("find the settings by scrolling down"),
            CommandParser.parse("find the settings by scrolling down")
        )
    }

    @Test
    fun `parses dictation typing`() {
        assertEquals(Command.TypeText("hello there"), CommandParser.parse("type hello there"))
        assertEquals(Command.TypeText("on my way"), CommandParser.parse("type on my way"))
    }

    @Test
    fun `parses screenshots and media controls`() {
        assertEquals(Command.Screenshot, CommandParser.parse("take a screenshot"))
        assertEquals(Command.Screenshot, CommandParser.parse("screenshot"))
        assertEquals(Command.MediaToggle, CommandParser.parse("play music"))
        assertEquals(Command.MediaToggle, CommandParser.parse("toggle playback"))
    }

    @Test
    fun `parses notification commands`() {
        assertEquals(Command.ReadNotifications, CommandParser.parse("read my notifications"))
        assertEquals(Command.ReadNotifications, CommandParser.parse("what did i miss"))
        assertEquals(Command.ClearNotifications, CommandParser.parse("clear all notifications"))
    }

    @Test
    fun `parses search scopes`() {
        assertEquals(Command.WebSearch("protein powder"), CommandParser.parse("search the web for protein powder"))
        assertEquals(
            Command.Search("protein powder", SearchScope.CURRENT),
            CommandParser.parse("search for protein powder in this app")
        )
        assertEquals(
            Command.Search("insurance", SearchScope.AUTO),
            CommandParser.parse("search for insurance")
        )
    }

    @Test
    fun `parses URLs and navigation to sites`() {
        assertEquals(Command.OpenUrl("https://youtube.com"), CommandParser.parse("open youtube.com"))
        assertEquals(Command.OpenUrl("https://example.com/path"), CommandParser.parse("go to example.com/path"))
    }

    @Test
    fun `parses screen AI commands`() {
        assertEquals(Command.TranslateScreen("Hindi"), CommandParser.parse("translate this screen to Hindi"))
        assertEquals(Command.AskScreen("what is this"), CommandParser.parse("ask ai what is this"))
        assertEquals(
            Command.AskScreen("What does this screen say about refunds?"),
            CommandParser.parse("what does this screen say about refunds")
        )
    }

    @Test
    fun `routes action chains to the agent`() {
        assertEquals(
            Command.AgentTask("open Chrome then search for weather"),
            CommandParser.parse("open Chrome then search for weather")
        )
        assertEquals(
            Command.AgentTask("open WhatsApp and search for Sarah"),
            CommandParser.parse("open WhatsApp and search for Sarah")
        )
    }

    @Test
    fun `parses repeated scrolls`() {
        assertEquals(Command.Scroll(true, 2), CommandParser.parse("scroll down twice"))
        assertEquals(Command.Scroll(false, 3), CommandParser.parse("scroll up three times"))
    }

    @Test
    fun `names the app when the search says where to look`() {
        assertEquals(
            Command.Search("movies", SearchScope.AUTO, "bookmyshow"),
            CommandParser.parse("search movies in bookmyshow")
        )
        assertEquals(
            Command.Search("my order", SearchScope.AUTO, "amazon"),
            CommandParser.parse("find my order in amazon")
        )
        assertEquals(
            Command.Search("headphones", SearchScope.AUTO, "flipkart"),
            CommandParser.parse("look for headphones in flipkart app")
        )
    }

    @Test
    fun `leaves searches that name no app to the plain search path`() {
        assertEquals(
            Command.Search("protein powder", SearchScope.AUTO),
            CommandParser.parse("search for protein powder")
        )
    }

    @Test
    fun `close and cancel dismiss whatever is showing`() {
        for (phrase in listOf("close", "close it", "cancel", "cancel it")) {
            assertEquals(phrase, Command.Back, CommandParser.parse(phrase))
        }
    }

    @Test
    fun `close inside a sentence is still dictation`() {
        assertNull(CommandParser.parse("close the door on your way out"))
    }

    @Test
    fun `send it taps the visible send control instead of typing`() {
        for (phrase in listOf(
            "send it", "send this", "send now", "send the message",
            "send this message", "send the email", "press send", "hit send"
        )) {
            assertEquals(phrase, Command.Tap("send"), CommandParser.parse(phrase))
        }
    }

    @Test
    fun `sending to a named person is still a message command`() {
        assertEquals(
            Command.SendMessage("Priya", "I'll be there"),
            CommandParser.parse("text Priya saying I'll be there")
        )
    }
}
