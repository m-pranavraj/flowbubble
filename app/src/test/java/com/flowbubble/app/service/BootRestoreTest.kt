package com.flowbubble.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * README promises Automations survive a reboot. The arming code only ran while OverlayService was
 * already up, and nothing brought OverlayService up after a reboot, so every time rule silently stopped
 * existing the first time the phone restarted. Restoring the bubble is the smallest thing that makes
 * that sentence true — and only for the phones that need it.
 */
class BootRestoreTest {
    @Test
    fun `a reboot brings the bubble back when the user left it on and a rule is waiting`() {
        assertTrue(BootRestore.shouldRelaunch(bubbleOn = true, overlayGranted = true, automationsArmed = true))
    }

    @Test
    fun `the off switch survives a reboot`() {
        assertFalse(BootRestore.shouldRelaunch(bubbleOn = false, overlayGranted = true, automationsArmed = true))
    }

    @Test
    fun `a phone that cannot host an overlay is not started into a crash`() {
        assertFalse(BootRestore.shouldRelaunch(bubbleOn = true, overlayGranted = false, automationsArmed = true))
    }

    /**
     * Without this the app would start an overlay service on every boot for every user, which nobody
     * asked for. Automations are the only promise that needs the bubble alive with no user present.
     */
    @Test
    fun `no armed rule is no reason to start`() {
        assertFalse(BootRestore.shouldRelaunch(bubbleOn = true, overlayGranted = true, automationsArmed = false))
    }
}
