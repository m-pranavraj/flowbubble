package com.flowbubble.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The line between a tap and a point.
 *
 * [Command.Point] resolves against the ordered targets a live session maintains, which is the only
 * way "the blue one" or "that chat" can work at all. It becomes a regression the moment it also
 * claims phrases [Command.Tap] already handles, because ordinal and label taps are the paths every
 * release since 1.0.0 has been device-verified against. These cases pin both halves of that line,
 * so widening the pointer grammar fails here instead of quietly replacing proven behaviour.
 */
class PointingAndWatchParseTest {

    @Test
    fun `a pointer needs something only the screen can answer`() {
        assertEquals(Command.Point("that chat"), CommandParser.parse("tap that chat"))
        assertEquals(Command.Point("blue button"), CommandParser.parse("tap the blue button"))
        assertEquals(Command.Point("one at the top"), CommandParser.parse("click the one at the top"))
    }

    @Test
    fun `an order or a bare kind stays a verified label tap`() {
        assertEquals(Command.Tap("second result", 2), CommandParser.parse("tap the second result"))
        assertEquals(Command.Tap("button"), CommandParser.parse("tap the button"))
        assertEquals(Command.Tap("Continue"), CommandParser.parse("tap Continue"))
        assertEquals(Command.Tap("third option", 3), CommandParser.parse("select the third option"))
    }

    @Test
    fun `watching opens and closes by word`() {
        assertEquals(Command.Watch(true, false), CommandParser.parse("keep watching this screen"))
        assertEquals(Command.Watch(true, true), CommandParser.parse("watch the screen until it's done"))
        assertEquals(Command.Watch(false), CommandParser.parse("stop watching"))
        assertEquals(Command.Watch(false), CommandParser.parse("eyes off"))
    }

    @Test
    fun `a reference with no verb still points when nothing names the target`() {
        assertEquals(Command.Point("third one"), CommandParser.parse("the third one"))
        assertEquals(Command.Point("that chat at the top"), CommandParser.parse("that chat at the top"))
    }

    @Test
    fun `a sentence that only happens to start with the is not a pointer`() {
        assertEquals(null, CommandParser.parse("the weather is nice today"))
    }
}
