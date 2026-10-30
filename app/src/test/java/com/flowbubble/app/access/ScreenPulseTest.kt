package com.flowbubble.app.access

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ScreenPulseTest {

    @Before
    fun resetPulse() {
        ScreenPulse.reset()
    }

    @Test
    fun `a fresh pulse reports no changes and then starts counting`() {
        assertEquals(0L, ScreenPulse.count())
        ScreenPulse.beat()
        assertEquals(1L, ScreenPulse.count())
    }

    @Test
    fun `every beat moves the counter forward so waiters can spot changes cheaply`() {
        val before = ScreenPulse.count()
        ScreenPulse.beat()
        ScreenPulse.beat()
        assertEquals(before + 2, ScreenPulse.count())
    }

    @Test
    fun `a wait that starts after a change still sees the counter move`() {
        ScreenPulse.beat()
        val watching = ScreenPulse.count()
        ScreenPulse.beat()
        assertEquals(watching + 1, ScreenPulse.count())
    }
}
