package com.flowbubble.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Hindi request is still a request. The rewrite only produces phrases the English grammar already
 * accepts, so safety gates, routing and verification stay in one place.
 */
class HindiCommandsTest {

    @Test
    fun `devanagari is detected per character`() {
        assertTrue(HindiCommands.containsDevanagari("\u092F\u0942\u091F\u094D\u092F\u0942\u092C"))
        assertFalse(HindiCommands.containsDevanagari("youtube"))
        assertFalse(HindiCommands.containsDevanagari(""))
    }

    @Test
    fun `names lose the trailing schwa so they match latin contacts`() {
        assertEquals("pranav", HindiCommands.latinize("\u092A\u094D\u0930\u0923\u0935"))
        assertEquals("priya", HindiCommands.latinize("\u092A\u094D\u0930\u093F\u092F\u093E"))
    }

    @Test
    fun `popular apps use the label the device knows`() {
        assertEquals("youtube", HindiCommands.latinize("\u092F\u0942\u091F\u094D\u092F\u0942\u092C"))
        assertEquals("chrome", HindiCommands.latinize("\u0915\u094D\u0930\u094B\u092E"))
        assertEquals("whatsapp", HindiCommands.latinize("\u0935\u094D\u0939\u093E\u091F\u094D\u0938\u090F\u092A"))
    }

    @Test
    fun `a hindi open phrase becomes the english open command`() {
        assertEquals(
            "open youtube",
            HindiCommands.rewrite("\u092F\u0942\u091F\u094D\u092F\u0942\u092C \u0916\u094B\u0932\u094B")
        )
        assertEquals(
            "open chrome",
            HindiCommands.rewrite("\u0915\u094D\u0930\u094B\u092E \u0916\u094B\u0932 \u0926\u094B")
        )
    }

    @Test
    fun `the romanised form works without devanagari`() {
        assertEquals("open youtube", HindiCommands.rewrite("youtube kholo"))
        assertEquals("open camera", HindiCommands.rewrite("camera khol do"))
    }

    @Test
    fun `an ordinary hindi sentence is not rewritten`() {
        assertNull(HindiCommands.rewrite("\u092E\u0948\u0902 \u0915\u0932 \u0918\u0930 \u091C\u093E\u090A\u0902\u0917\u093E"))
    }

    @Test
    fun `search names the app it should happen in`() {
        assertEquals(
            "search \u092E\u094C\u0938\u092E in chrome",
            HindiCommands.rewrite(
                "\u0915\u094D\u0930\u094B\u092E \u092E\u0947\u0902 \u092E\u094C\u0938\u092E \u0916\u094B\u091C\u094B"
            )
        )
        assertEquals(
            "search \u092E\u094C\u0938\u092E in this app",
            HindiCommands.rewrite(
                "\u0907\u0938 \u0910\u092A \u092E\u0947\u0902 \u092E\u094C\u0938\u092E \u0916\u094B\u091C\u094B"
            )
        )
        assertEquals(
            "search \u092E\u094C\u0938\u092E",
            HindiCommands.rewrite("\u092E\u094C\u0938\u092E \u0916\u094B\u091C\u094B")
        )
        assertEquals("search mausam in chrome", HindiCommands.rewrite("chrome mein mausam khojo"))
    }

    @Test
    fun `read and scroll carry the direction`() {
        assertEquals(
            "read this screen",
            HindiCommands.rewrite("\u0938\u094D\u0915\u094D\u0930\u0940\u0928 \u092A\u0922\u093C\u094B")
        )
        assertEquals("read this screen", HindiCommands.rewrite("screen padho"))
        assertEquals(
            "scroll down",
            HindiCommands.rewrite(
                "\u0928\u0940\u091A\u0947 \u0938\u094D\u0915\u094D\u0930\u0949\u0932 \u0915\u0930\u094B"
            )
        )
        assertEquals(
            "scroll up",
            HindiCommands.rewrite(
                "\u090A\u092A\u0930 \u0938\u094D\u0915\u094D\u0930\u0949\u0932 \u0915\u0930\u094B"
            )
        )
        assertEquals("scroll down", HindiCommands.rewrite("neeche scroll karo"))
    }

    @Test
    fun `tap and call keep what they act on`() {
        assertEquals(
            "tap \u0938\u0947\u091F\u093F\u0902\u0917\u094D\u0938",
            HindiCommands.rewrite(
                "\u0938\u0947\u091F\u093F\u0902\u0917\u094D\u0938 \u092A\u0930 \u091F\u0948\u092A \u0915\u0930\u094B"
            )
        )
        assertEquals("tap settings", HindiCommands.rewrite("settings par tap karo"))
        assertEquals(
            "call pranav",
            HindiCommands.rewrite(
                "\u092A\u094D\u0930\u0923\u0935 \u0915\u094B \u0915\u0949\u0932 \u0915\u0930\u094B"
            )
        )
        assertEquals("call pranav", HindiCommands.rewrite("pranav ko call karo"))
    }

    @Test
    fun `chat and message name the person once`() {
        assertEquals(
            "chat with pranav",
            HindiCommands.rewrite(
                "\u092A\u094D\u0930\u0923\u0935 \u0938\u0947 \u091A\u0948\u091F \u0915\u0930\u094B"
            )
        )
        assertEquals(
            "chat with pranav",
            HindiCommands.rewrite(
                "\u092A\u094D\u0930\u0923\u0935 \u0915\u0940 \u091A\u0948\u091F \u0916\u094B\u0932\u094B"
            )
        )
        assertEquals("chat with pranav", HindiCommands.rewrite("pranav se chat karo"))
    }

    @Test
    fun `a hindi message body is sent in its own script`() {
        assertEquals(
            "text pranav saying \u092E\u0948\u0902 \u0926\u0947\u0930 \u0938\u0947 \u0906\u090A\u0902\u0917\u093E",
            HindiCommands.rewrite(
                "\u092A\u094D\u0930\u0923\u0935 \u0915\u094B \u092E\u0948\u0938\u0947\u091C \u092D\u0947\u091C\u094B " +
                    "\u0915\u093F \u092E\u0948\u0902 \u0926\u0947\u0930 \u0938\u0947 \u0906\u090A\u0902\u0917\u093E"
            )
        )
        assertEquals(
            "text pranav saying main der se aaunga",
            HindiCommands.rewrite("pranav ko message bhejo ki main der se aaunga")
        )
        assertEquals(
            "chat with pranav",
            HindiCommands.rewrite(
                "\u092A\u094D\u0930\u0923\u0935 \u0915\u094B \u092E\u0948\u0938\u0947\u091C \u092D\u0947\u091C\u094B"
            )
        )
    }

    @Test
    fun `timers understand hindi numbers and units`() {
        assertEquals(
            "set a timer for 5 minutes",
            HindiCommands.rewrite(
                "\u092A\u093E\u0901\u091A \u092E\u093F\u0928\u091F \u0915\u093E \u091F\u093E\u0907\u092E\u0930 " +
                    "\u0932\u0917\u093E\u0913"
            )
        )
        assertEquals(
            "set a timer for 2 minutes",
            HindiCommands.rewrite(
                "\u0926\u094B \u092E\u093F\u0928\u091F \u0915\u093E \u091F\u093E\u0907\u092E\u0930 \u0932\u0917\u093E\u0913"
            )
        )
        assertEquals("set a timer for 5 minutes", HindiCommands.rewrite("5 minute ka timer lagao"))
    }

    @Test
    fun `torch volume and system reads work in hindi`() {
        assertEquals(
            "turn on the flashlight",
            HindiCommands.rewrite(
                "\u091F\u0949\u0930\u094D\u091A \u091A\u093E\u0932\u0942 \u0915\u0930\u094B"
            )
        )
        assertEquals(
            "turn off the flashlight",
            HindiCommands.rewrite(
                "\u092B\u094D\u0932\u0948\u0936\u0932\u093E\u0907\u091F \u092C\u0902\u0926 \u0915\u0930\u094B"
            )
        )
        assertEquals("turn off the flashlight", HindiCommands.rewrite("torch band karo"))
        assertEquals(
            "volume up",
            HindiCommands.rewrite(
                "\u0906\u0935\u093E\u091C\u093C \u092C\u0922\u093C\u093E\u0913"
            )
        )
        assertEquals("volume down", HindiCommands.rewrite("volume kam karo"))
        assertEquals(
            "battery status",
            HindiCommands.rewrite("\u092C\u0948\u091F\u0930\u0940 \u092C\u0924\u093E\u0913")
        )
        assertEquals(
            "read notifications",
            HindiCommands.rewrite(
                "\u0928\u094B\u091F\u093F\u092B\u093F\u0915\u0947\u0936\u0928 \u092A\u0922\u093C\u094B"
            )
        )
        assertEquals(
            "back",
            HindiCommands.rewrite("\u092A\u0940\u091B\u0947 \u091C\u093E\u0913")
        )
        assertEquals("home", HindiCommands.rewrite("home jao"))
    }

    @Test
    fun `a hindi open phrase reaches the same parser route`() {
        assertEquals(
            Command.OpenApp("youtube"),
            CommandParser.parse("\u092F\u0942\u091F\u094D\u092F\u0942\u092C \u0916\u094B\u0932\u094B")
        )
        assertEquals(
            Command.OpenApp("chrome"),
            CommandParser.parse("\u0915\u094D\u0930\u094B\u092E \u0916\u094B\u0932 \u0926\u094B")
        )
        assertEquals(Command.OpenApp("youtube"), CommandParser.parse("youtube kholo"))
    }

    @Test
    fun `a hindi chat and message reach the person`() {
        assertEquals(
            Command.OpenChat("pranav"),
            CommandParser.parse("\u092A\u094D\u0930\u0923\u0935 \u0938\u0947 \u091A\u0948\u091F \u0915\u0930\u094B")
        )
        assertEquals(
            Command.SendMessage("pranav", "\u092E\u0948\u0902 \u0926\u0947\u0930 \u0938\u0947 \u0906\u090A\u0902\u0917\u093E"),
            CommandParser.parse(
                "\u092A\u094D\u0930\u0923\u0935 \u0915\u094B \u092E\u0948\u0938\u0947\u091C \u092D\u0947\u091C\u094B \u0915\u093F " +
                    "\u092E\u0948\u0902 \u0926\u0947\u0930 \u0938\u0947 \u0906\u090A\u0902\u0917\u093E"
            )
        )
    }

    @Test
    fun `a hindi search keeps the query and names the app`() {
        assertEquals(
            Command.Search("\u092E\u094C\u0938\u092E", SearchScope.AUTO, "chrome"),
            CommandParser.parse(
                "\u0915\u094D\u0930\u094B\u092E \u092E\u0947\u0902 \u092E\u094C\u0938\u092E \u0916\u094B\u091C\u094B"
            )
        )
        assertEquals(
            Command.Search("\u092E\u094C\u0938\u092E", SearchScope.AUTO),
            CommandParser.parse("\u092E\u094C\u0938\u092E \u0916\u094B\u091C\u094B")
        )
    }

    @Test
    fun `hindi timers and toggles reach their commands`() {
        assertEquals(
            Command.SetTimer(300),
            CommandParser.parse(
                "\u092A\u093E\u0901\u091A \u092E\u093F\u0928\u091F \u0915\u093E \u091F\u093E\u0907\u092E\u0930 \u0932\u0917\u093E\u0913"
            )
        )
        assertEquals(
            Command.Flashlight(true),
            CommandParser.parse("\u091F\u0949\u0930\u094D\u091A \u091A\u093E\u0932\u0942 \u0915\u0930\u094B")
        )
        assertEquals(
            Command.Flashlight(false),
            CommandParser.parse("\u092B\u094D\u0932\u0948\u0936\u0932\u093E\u0907\u091F \u092C\u0902\u0926 \u0915\u0930\u094B")
        )
        assertEquals(Command.Back, CommandParser.parse("\u092A\u0940\u091B\u0947 \u091C\u093E\u0913"))
    }

    @Test
    fun `an english command is untouched by the hindi layer`() {
        assertEquals(Command.OpenApp("chrome"), CommandParser.parse("open chrome"))
        assertEquals(Command.Back, CommandParser.parse("back"))
        assertNull(HindiCommands.rewrite("open chrome"))
    }
}
