package com.flowbubble.app.agent

import com.flowbubble.app.voice.Command
import com.flowbubble.app.voice.FollowUpKind
import com.flowbubble.app.voice.SearchScope

/**
 * Everything the router needs to know about the device right now, as plain booleans, so the
 * whole routing table is unit testable without Android.
 */
data class CapabilityContext(
    val accessibility: Boolean = false,
    /**
     * False only when the service is connected but can no longer see any window. Enablement and
     * liveness are different facts, and conflating them told the user their app "didn't start".
     */
    val accessibilityResponsive: Boolean = true,
    val screenCapture: Boolean = false,
    val notificationAccess: Boolean = false,
    val dndPolicy: Boolean = false,
    val contacts: Boolean = false,
    val aiConfigured: Boolean = false
)

/** Where to send the user when a route needs a switch they haven't opened yet. */
enum class SettingsTarget { ACCESSIBILITY, NOTIFICATION_LISTENER, DO_NOT_DISTURB, CONTACTS, AI_SETTINGS }

/** A gate the user still has to open, phrased the same way everywhere it appears. */
data class Gap(val title: String, val detail: String, val target: SettingsTarget)

sealed interface Route {
    /** Runnable with the switches already granted. `mechanism` names how it actually acts. */
    data class Ready(val capability: String, val mechanism: String, val note: String? = null) : Route

    /** Runnable in principle, but a system gate has to be opened first. */
    data class Gated(val capability: String, val gaps: List<Gap>) : Route

    /** This build genuinely cannot do it, and saying so is the answer. */
    data class Unsupported(val capability: String, val reason: String) : Route
}

/**
 * The spine: the one place that decides which route a request takes and what it needs first.
 * Nothing here performs an action; it only classifies, so the gate panel, the history entry, and
 * the outcome vocabulary all agree about what was actually possible.
 */
object CapabilityRouter {

    fun route(command: Command, context: CapabilityContext): Route = when (command) {
        // Intents and system reads: no special switch needed beyond the app being installed.
        is Command.OpenApp ->
            if (command.query.isBlank()) Route.Unsupported("open_app", "Which app did you mean?")
            else Route.Ready("open_app", "package launch")
        is Command.OpenUrl -> Route.Ready("open_url", "browser intent")
        is Command.WebSearch -> Route.Ready("web_search", "browser intent")
        is Command.SetTimer -> Route.Ready("timer", "clock intent")
        is Command.SetAlarm -> Route.Ready("alarm", "clock intent")
        is Command.NewEvent -> Route.Ready("calendar", "calendar intent")
        is Command.ComposeEmail -> Route.Ready("email", "mail draft")
        is Command.Navigate -> Route.Ready("navigate", "maps intent")
        Command.OpenSettings -> Route.Ready("settings", "settings intent")
        Command.BatteryStatus -> Route.Ready("battery", "system query")
        is Command.Flashlight -> Route.Ready("flashlight", "camera torch")
        is Command.Volume -> Route.Ready("volume", "audio manager")
        Command.MediaToggle -> Route.Ready("media", "media session")

        // Routines replay other commands, so they inherit their own gates when they run.
        is Command.SaveRoutine, is Command.RunRoutine, is Command.DeleteRoutine, Command.ListRoutines ->
            Route.Ready("routine", "stored command replay")

        is Command.Call ->
            if (!context.contacts) Route.Gated("call", listOf(contactsGap()))
            else Route.Ready("call", "dialer", "The dialer still needs your own tap to place the call.")

        is Command.SendMessage ->
            if (!context.contacts) Route.Gated("message", listOf(contactsGap()))
            else Route.Ready("message", "sms draft", "Your Bubble opens the draft; you press send.")

        is Command.OpenChat ->
            if (!context.contacts) Route.Gated("open_chat", listOf(contactsGap()))
            else Route.Ready(
                "open_chat",
                "chat deep link",
                "Your Bubble opens the conversation; you write and send."
            )

        is Command.Dnd ->
            if (!context.dndPolicy) Route.Gated("do_not_disturb", listOf(dndGap()))
            else Route.Ready("do_not_disturb", "ring policy")

        Command.ReadNotifications, Command.ClearNotifications ->
            if (context.notificationAccess) Route.Ready("notifications", "notification listener")
            else Route.Gated("notifications", listOf(notificationGap()))

        is Command.Search ->
            if (command.scope == SearchScope.WEB) Route.Ready("web_search", "browser intent")
            else needsScreen(searchCapability(command), context)

        is Command.AskScreen, is Command.TranslateScreen ->
            if (!context.accessibility) needsScreen(labelFor(command), context)
            else if (!context.aiConfigured) Route.Gated(labelFor(command), listOf(aiGap()))
            else Route.Ready(
                labelFor(command),
                "screen text + your AI endpoint",
                "Only the visible text you approve is sent."
            )

        is Command.FollowUp ->
            if (command.kind == FollowUpKind.UNDO || command.kind == FollowUpKind.AGAIN) {
                Route.Ready("follow_up", "last action replay")
            } else {
                needsScreen("follow_up", context)
            }

        // Everything that touches another app's screen.
        is Command.Tap, is Command.Scroll, Command.Back, Command.Home, Command.Recents,
        Command.ReadScreen, Command.ScreenActions, Command.Screenshot, is Command.TypeText,
        is Command.AgentTask, is Command.Point ->
            needsScreen(labelFor(command), context)

        // Watching is the same access the screen reader already uses, and needs no AI either way.
        is Command.Watch -> needsScreen("watch_screen", context)

        // A clarification asks the user, which only needs the overlay that is already running.
        is Command.Clarify -> Route.Ready("clarify", "choice panel")
    }

    private fun searchCapability(command: Command.Search): String =
        if (command.app.isNullOrBlank()) "search_here" else "search_app"

    private fun needsScreen(capability: String, context: CapabilityContext): Route = when {
        !context.accessibility -> Route.Gated(capability, listOf(accessibilityGap()))
        !context.accessibilityResponsive -> Route.Gated(capability, listOf(accessibilityStaleGap()))
        else -> Route.Ready(
            capability,
            if (context.screenCapture) "accessibility + screen reading" else "accessibility tree",
            note = if (context.screenCapture) null else
                "Screen access is off, so apps that hide their controls stay invisible to me."
        )
    }

    private fun accessibilityGap() = Gap(
        "Voice access is off",
        "Your Bubble needs the accessibility switch to see or touch another app's screen.",
        SettingsTarget.ACCESSIBILITY
    )

    private fun accessibilityStaleGap() = Gap(
        "Voice access stopped responding",
        "Android still lists Your Bubble as enabled, but it can no longer see any window. " +
            "Switch it off and on in Accessibility to reconnect it.",
        SettingsTarget.ACCESSIBILITY
    )

    private fun contactsGap() = Gap(
        "Contacts access",
        "Matching a spoken name to the right number needs contacts access.",
        SettingsTarget.CONTACTS
    )

    private fun dndGap() = Gap(
        "Do Not Disturb access",
        "Android only lets an app change the ring policy after you grant that switch.",
        SettingsTarget.DO_NOT_DISTURB
    )

    private fun notificationGap() = Gap(
        "Notification access",
        "Android keeps every app's notifications behind a separate switch. " +
            "Your Bubble holds them in memory for this session only.",
        SettingsTarget.NOTIFICATION_LISTENER
    )

    private fun aiGap() = Gap(
        "No AI model configured",
        "Answering a question about the screen needs a model. Settings \u2192 AI model takes a minute, " +
            "and nothing leaves the device without your tap.",
        SettingsTarget.AI_SETTINGS
    )

    private fun labelFor(command: Command): String = when (command) {
        is Command.Tap -> "tap"
        is Command.Point -> "point"
        is Command.Watch -> "watch_screen"
        is Command.Clarify -> "clarify"
        is Command.Scroll -> "scroll"
        Command.Back -> "back"
        Command.Home -> "home"
        Command.Recents -> "recents"
        Command.ReadScreen -> "read_screen"
        Command.ScreenActions -> "screen_actions"
        Command.Screenshot -> "screenshot"
        is Command.TypeText -> "type"
        is Command.AgentTask -> "agent"
        is Command.AskScreen -> "ask_screen"
        is Command.TranslateScreen -> "translate_screen"
        else -> "screen"
    }
}
