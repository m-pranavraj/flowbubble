package com.flowbubble.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Setup tells the user the bubble is ready, and the bubble then does nothing. The list of *enabled*
 * accessibility services is written by Settings the moment a user flips the switch, while the service
 * only becomes usable once Android has bound it — and a service Android has crashed stays enabled.
 */
class PermissionStateTest {
    private fun state(
        microphone: Boolean = true,
        overlay: Boolean = true,
        accessibility: Boolean = true,
        accessibilityBound: Boolean = true
    ) = PermissionState(
        microphone = microphone,
        overlay = overlay,
        accessibility = accessibility,
        accessibilityBound = accessibilityBound,
        notifications = false,
        contacts = false,
        camera = false,
        dndAccess = false,
        batteryExempt = false,
        notificationAccess = false
    )

    @Test
    fun `an enabled but unbound service is not ready`() {
        assertFalse(state(accessibilityBound = false).coreReady)
    }

    @Test
    fun `a bound service is ready`() {
        assertTrue(state().coreReady)
    }

    @Test
    fun `a missing microphone still blocks readiness`() {
        assertFalse(state(microphone = false).coreReady)
    }
}
