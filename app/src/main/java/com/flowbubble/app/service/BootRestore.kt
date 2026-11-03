package com.flowbubble.app.service

/**
 * Whether a reboot should bring the bubble back on its own.
 *
 * README promises that Automations survive a reboot. Time rules do survive — AlarmManager keeps the
 * schedule — but a rule that wakes into a phone with no bubble is blocked by the off switch and
 * records nothing, which is not surviving. Restarting the service is what makes the sentence true.
 *
 * All three conditions are load-bearing. The off switch stays a kill switch across a restart, a phone
 * that revoked the overlay permission must not be started into a service that cannot draw, and a user
 * with no armed rule gets no new background process on every boot.
 */
object BootRestore {
    fun shouldRelaunch(bubbleOn: Boolean, overlayGranted: Boolean, automationsArmed: Boolean): Boolean =
        bubbleOn && overlayGranted && automationsArmed
}
