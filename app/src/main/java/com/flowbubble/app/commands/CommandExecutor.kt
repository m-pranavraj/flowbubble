package com.flowbubble.app.commands

import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.widget.Toast
import com.flowbubble.app.MainActivity
import com.flowbubble.app.access.FlowAccessibilityService
import com.flowbubble.app.access.TextInsertResult
import com.flowbubble.app.agent.ActionParsing
import com.flowbubble.app.agent.AgentAction
import com.flowbubble.app.agent.AgentPlan
import com.flowbubble.app.agent.AgentRunner
import com.flowbubble.app.agent.AgentStep
import com.flowbubble.app.agent.OutcomeKind
import com.flowbubble.app.agent.PlanSource
import com.flowbubble.app.agent.RiskLevel
import com.flowbubble.app.agent.SafetyPolicy
import com.flowbubble.app.capture.ScreenCapture
import com.flowbubble.app.capture.ScreenSession
import com.flowbubble.app.core.DndRevert
import com.flowbubble.app.core.HistoryStore
import com.flowbubble.app.core.LastActionMemory
import com.flowbubble.app.core.Permissions
import com.flowbubble.app.core.Prefs
import com.flowbubble.app.core.RoutineStore
import com.flowbubble.app.core.TorchController
import com.flowbubble.app.core.VolumeController
import com.flowbubble.app.notifications.FlowNotificationService
import com.flowbubble.app.notifications.NotificationCenter
import com.flowbubble.app.overlay.OverlayUi
import com.flowbubble.app.overlay.PanelAction
import com.flowbubble.app.overlay.PanelContent
import com.flowbubble.app.vision.ActionEntity
import com.flowbubble.app.vision.ActionEntityKind
import com.flowbubble.app.vision.PointResolution
import com.flowbubble.app.vision.ScreenFusion
import com.flowbubble.app.vision.ScreenModel
import com.flowbubble.app.vision.ScreenModelFactory
import com.flowbubble.app.vision.SmartActions
import com.flowbubble.app.vision.TargetLocator
import com.flowbubble.app.vision.TextMatcher
import com.flowbubble.app.voice.AiRole
import com.flowbubble.app.voice.Command
import com.flowbubble.app.voice.CommandParser
import com.flowbubble.app.voice.FollowUpKind
import com.flowbubble.app.voice.LlmClient
import com.flowbubble.app.voice.PhraseOutcome
import com.flowbubble.app.voice.PhraseResolver
import com.flowbubble.app.voice.SearchScope
import com.flowbubble.app.voice.TextProcessor
import com.flowbubble.app.voice.VocabularyProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A cold start can take several seconds on this class of device, so this is a cap rather than a
 * sleep: the wait returns on the first read that shows the app. Your Bubble's own overlay is not
 * an app window, and the foreground read ignores it.
 */
private const val APP_OPEN_TIMEOUT_MS = 20_000L
private const val APP_OPEN_POLL_MS = 250L

/**
 * Chat apps can cold-start slower than a plain foreground switch, and the name only appears after
 * the conversation itself is drawn. These are caps, not sleeps: both waits return on the first
 * matching state, so a warm app costs a couple of hundred milliseconds.
 */
private const val CHAT_FOREGROUND_TIMEOUT_MS = 12_000L
private const val CHAT_NAME_TIMEOUT_MS = 6_000L
private const val CHAT_POLL_MS = 200L

class CommandExecutor(
    private val context: Context,
    private val ui: OverlayUi
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val agent = AgentRunner(context, ui)
    private val vocabulary = VocabularyProvider(context)
    private var job: Job? = null
    private var dndRevertJob: Job? = null
    private var pendingConfirm: CompletableDeferred<Boolean>? = null
    private val emailAddressRegex = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun execute(command: Command, raw: String? = null) {
        pendingConfirm?.complete(false)
        pendingConfirm = null
        agent.stop()
        job?.cancel()
        job = scope.launch {
            runCommand(command, raw)
        }
    }

    private suspend fun runCommand(command: Command, raw: String? = null) {
        remember(command, raw)
        when (command) {
            is Command.OpenApp -> openApp(command.query)
            is Command.SetTimer -> setTimer(command.seconds)
            is Command.SetAlarm -> setAlarm(command.hour, command.minute)
            is Command.Call -> call(command.query)
            is Command.SendMessage -> sendMessage(command)
            is Command.OpenChat -> openChat(command)
            is Command.Navigate -> navigate(command.place)
            is Command.Tap -> tap(command)
            is Command.Scroll -> scroll(command.forward, command.times)
            Command.Back -> globalAction("Went back") {
                FlowAccessibilityService.instance?.pressBack()
            }
            Command.Home -> globalAction("Went home") {
                FlowAccessibilityService.instance?.pressHome()
            }
            Command.Recents -> globalAction("Opened recent apps") {
                FlowAccessibilityService.instance?.pressRecents()
            }
            Command.ReadScreen -> readScreen()
            Command.OpenSettings -> openSettings()
            is Command.Dnd -> setDnd(command.enable, command.minutes)
            is Command.TypeText -> typeText(command.text)
            Command.Screenshot -> screenshot()
            Command.ReadNotifications -> readNotifications()
            Command.ClearNotifications -> clearNotifications()
            is Command.OpenUrl -> openUrl(command.url)
            is Command.WebSearch -> openUrl(ActionParsing.searchUrl(command.query), "Search for \u201C${command.query}\u201D")
            is Command.Search -> search(command)
            is Command.AgentTask -> agent.start(scope, command.goal)
            is Command.AskScreen -> askScreen(command.question)
            is Command.TranslateScreen -> translateScreen(command.language)
            Command.MediaToggle -> mediaToggle()
            is Command.FollowUp -> followUp(command)
            is Command.SaveRoutine -> saveRoutine(command)
            is Command.RunRoutine -> runRoutine(command.name)
            is Command.DeleteRoutine -> deleteRoutine(command.name)
            Command.ListRoutines -> listRoutines()
            is Command.Flashlight -> flashlight(command.on)
            is Command.Volume -> changeVolume(command)
            Command.BatteryStatus -> batteryStatus()
            is Command.ComposeEmail -> composeEmail(command.to, command.subject)
            is Command.NewEvent -> newEvent(command.title)
            Command.ScreenActions -> screenActions()
            is Command.Watch -> watch(command)
            is Command.Point -> point(command)
            is Command.Clarify -> clarify(command)
        }
    }

    private fun remember(command: Command, raw: String?) {
        if (raw.isNullOrBlank()) return
        when (command) {
            is Command.FollowUp, is Command.SaveRoutine, is Command.RunRoutine,
            is Command.DeleteRoutine, Command.ListRoutines -> Unit
            else -> LastActionMemory.rememberRaw(raw)
        }
    }

    fun stop() {
        pendingConfirm?.complete(false)
        pendingConfirm = null
        agent.stop()
        job?.cancel()
        dndRevertJob?.cancel()
        scope.cancel()
    }

    private fun record(kind: String, summary: String, status: String) {
        HistoryStore.add(context, kind, summary, status)
        if (status == "done" || status == "failed" || status == "unverified" || status == "copied") {
            ui.announce(summary)
        }
    }

    private suspend fun openApp(query: String) {
        val matches = withContext(Dispatchers.IO) { AppResolver.find(context, query) }
        when {
            matches.isEmpty() -> appNotFound(query)
            matches.size == 1 -> launchApp(matches.first())
            else -> ui.showPanel(
                PanelContent(
                    title = "Which app?",
                    body = "I found ${matches.size} apps matching \"$query\".",
                    actions = matches.take(4).map { match ->
                        PanelAction(match.label) { launchApp(match) }
                    } + PanelAction("Cancel") { ui.closePanel() }
                )
            )
        }
    }

    private fun launchApp(match: AppMatch) {
        ui.closePanel()
        val opened = AppResolver.launch(context, match.packageName)
        if (opened) {
            ui.showPanel(
                PanelContent(
                    title = "Opening ${match.label}",
                    autoDismissMs = 1200
                )
            )
            scope.launch { confirmForeground(match) }
        } else {
            record("App", "Open ${match.label}", "failed")
            ui.showPanel(
                PanelContent(
                    title = "Couldn't open ${match.label}",
                    body = "Android refused to launch this app.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
        }
    }

    /**
     * An app only counts as opened when Android puts it in the foreground. Your Bubble's own
     * overlay can briefly own the active window, so seeing ourselves is inconclusive, not a failure.
     */
    private suspend fun confirmForeground(match: AppMatch) {
        val service = FlowAccessibilityService.instance
        if (service == null) {
            record("App", "Opened ${match.label}", "unverified")
            return
        }
        if (awaitForeground(service, match.packageName)) {
            record("App", "Opened ${match.label}", "done")
            return
        }
        val observedPackage = service.foregroundPackage()
            ?.takeIf { it != context.packageName }
        val observed = observedPackage?.let { packageName ->
            withContext(Dispatchers.IO) {
                AppResolver.installed(context).firstOrNull { it.packageName == packageName }?.label ?: packageName
            }
        }
        record("App", "Open ${match.label}", "unverified")
        ui.showPanel(
            PanelContent(
                title = "Couldn't confirm ${match.label}",
                body = "Android never put ${match.label} in the foreground" +
                    (observed?.let { ", and the screen still shows $it" } ?: "") +
                    ". Tap to try again, or open it yourself.",
                actions = listOf(
                    PanelAction("Try again", primary = true) { launchApp(match) },
                    PanelAction("Close") { ui.closePanel() }
                )
            )
        )
    }

    private suspend fun awaitForeground(
        service: FlowAccessibilityService,
        packageName: String
    ): Boolean {
        val deadline = System.currentTimeMillis() + APP_OPEN_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (service.foregroundPackage() == packageName) return true
            delay(APP_OPEN_POLL_MS)
        }
        return service.foregroundPackage() == packageName
    }

    /** No literal match: rescore what was heard against the installed app names. */
    private suspend fun appNotFound(query: String): Boolean {
        val names = withContext(Dispatchers.IO) { vocabulary.appNames() }
        val guesses = phraseGuesses(query, names)
        val storePackage = withContext(Dispatchers.IO) {
            val pkg = AppAliases.storePackageFor(query)
            if (pkg != null && !AppResolver.isInstalled(context, pkg)) pkg else null
        }
        if (guesses.isEmpty()) {
            record("App", "Look up \"$query\"", "not found")
            ui.showPanel(
                PanelContent(
                    title = "App not found",
                    body = "I couldn't find an installed app matching \"$query\". It may not be installed.",
                    actions = listOfNotNull(
                        storePackage?.let { pkg ->
                            PanelAction("Get it on Google Play", primary = true) {
                                ui.closePanel()
                                record("App", "Play Store for $query", "requested")
                                AppResolver.openStore(context, pkg)
                            }
                        },
                        PanelAction("Search the web", primary = storePackage == null) {
                            ui.closePanel()
                            openUrl(ActionParsing.searchUrl(query), "Search for \u201C$query\u201D")
                        },
                        PanelAction("Close") { ui.closePanel() }
                    )
                )
            )
            return false
        }
        record("App", "Look up \"$query\"", "did you mean ${guesses.first()}")
        ui.showPanel(
            PanelContent(
                title = if (guesses.size == 1) "Did you mean ${guesses.first()}?" else "Did you mean one of these?",
                body = "I heard \"$query\". No installed app has that name, so nothing was opened yet.",
                actions = guesses.take(3).map { name ->
                    PanelAction("Open $name", primary = name == guesses.first()) { openByLabel(name) }
                } + listOfNotNull(
                    storePackage?.let { pkg ->
                        PanelAction("Get it on Google Play") {
                            ui.closePanel()
                            record("App", "Play Store for $query", "requested")
                            AppResolver.openStore(context, pkg)
                        }
                    },
                    PanelAction("Search the web") {
                        ui.closePanel()
                        openUrl(ActionParsing.searchUrl(query), "Search for \u201C$query\u201D")
                    },
                    PanelAction("Cancel") { ui.closePanel() }
                )
            )
        )
        return true
    }

    /**
     * A phrase with no command and nowhere to write it. Before calling it a failure, rescore it
     * against the installed app names: that is where a misheard "open YouTube" actually lands.
     *
     * Returns true when the utterance has been answered as an app request, including when nothing
     * matched: the panel says so and offers the store. Returning false there would make the caller
     * try to insert text into a field that does not exist and record a second, unrelated failure.
     */
    /**
     * Only claims the utterance when there is something to act on. Ordinary dictation spoken with
     * no field focused must not end in an "App not found" panel that then fails to insert.
     */
    suspend fun offerAppGuess(text: String): Boolean {
        val phrase = text.trim().takeIf { it.isNotEmpty() && it.length <= 40 } ?: return false
        if (!CommandParser.startsWithActionVerb(phrase)) {
            val names = withContext(Dispatchers.IO) { vocabulary.appNames() }
            if (phraseGuesses(phrase, names).isEmpty()) return false
        }
        // appNotFound always answers with a panel, so the utterance is claimed either way.
        appNotFound(phrase)
        return true
    }

    private fun openByLabel(label: String) {
        ui.closePanel()
        scope.launch {
            val match = withContext(Dispatchers.IO) {
                AppResolver.installed(context).firstOrNull { it.label.equals(label, ignoreCase = true) }
            }
            if (match == null) {
                record("App", "Open $label", "failed")
                ui.showPanel(
                    PanelContent(
                        title = "$label isn't installed",
                        body = "It was in the list a moment ago but Android can't find it now.",
                        actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                    )
                )
            } else {
                launchApp(match)
            }
        }
    }

    /**
     * A misheard verb must not dilute the name it came with: "opem youube" is scored as "youube",
     * and only falls back to the whole phrase when the stripped reading matches nothing at all.
     */
    private fun phraseGuesses(spoken: String, vocabularyNames: List<String>): List<String> {
        val name = CommandParser.stripActionVerb(spoken)
        if (name == spoken) return guessesIn(name, vocabularyNames)
        return guessesIn(name, vocabularyNames).ifEmpty { guessesIn(spoken, vocabularyNames) }
    }

    private fun guessesIn(phrase: String, vocabularyNames: List<String>): List<String> =
        when (val outcome = PhraseResolver.resolve(phrase, vocabularyNames)) {
            is PhraseOutcome.Resolved -> listOf(outcome.text)
            is PhraseOutcome.Suggest -> outcome.options.map { it.text }
            PhraseOutcome.Unknown -> emptyList()
        }

    private fun setTimer(seconds: Int) {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (start(intent)) {
            val label = formatDuration(seconds)
            record("Timer", "Timer for $label", "requested")
            ui.showPanel(
                PanelContent(
                    title = "Timer sent",
                    body = "Your clock app was asked to start a $label timer.",
                    autoDismissMs = 2200
                )
            )
        } else {
            record("Timer", "Timer for ${formatDuration(seconds)}", "unsupported")
            ui.showPanel(
                PanelContent(
                    title = "No timer app",
                    body = "I couldn't find a clock app that accepts timers on this device.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
        }
    }

    private fun setAlarm(hour: Int, minute: Int) {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (start(intent)) {
            val label = String.format("%02d:%02d", hour, minute)
            record("Alarm", "Alarm at $label", "requested")
            ui.showPanel(
                PanelContent(
                    title = "Alarm sent",
                    body = "Your clock app was asked to set an alarm for $label.",
                    autoDismissMs = 2200
                )
            )
        } else {
            record("Alarm", "Alarm at $hour:$minute", "unsupported")
            ui.showPanel(
                PanelContent(
                    title = "No alarm app",
                    body = "I couldn't find a clock app that accepts alarms on this device.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
        }
    }

    private suspend fun call(query: String) {
        val contacts = withContext(Dispatchers.IO) { ContactResolver.find(context, query, 3) }
        val match = contacts.firstOrNull()
        val number = match?.number
            ?: query.filter { it.isDigit() || it == '+' }.takeIf { it.count(Char::isDigit) >= 3 }
        if (number.isNullOrBlank()) {
            record("Call", "Find \"$query\"", "not found")
            ui.showPanel(
                PanelContent(
                    title = "No contact found",
                    body = "I couldn't find \"$query\" in your contacts. Say a number, or add the contact first.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
            return
        }
        val label = match?.name ?: number
        val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}"))
        if (start(dial)) {
            record("Call", "Dialer opened for $label", "awaiting user")
            ui.showPanel(
                PanelContent(
                    title = "Dialer opened",
                    body = "The number for $label is ready. Press the call button to start.",
                    autoDismissMs = 2400
                )
            )
        } else {
            record("Call", "Dial $label", "failed")
            ui.showPanel(
                PanelContent(
                    title = "No dialer",
                    body = "I couldn't open a phone app on this device.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
        }
    }

    private suspend fun sendMessage(command: Command.SendMessage) {
        if (!ContactResolver.hasPermission(context)) {
            record("Message", "Find \"${command.contact}\"", "permission missing")
            ui.showPanel(
                PanelContent(
                    title = "Contacts permission needed",
                    body = "Your Bubble needs contact access to find ${command.contact}. You can grant it in the app setup.",
                    actions = listOf(
                        PanelAction("Open Your Bubble setup", primary = true) {
                            ui.closePanel()
                            MainActivity.open(context, MainActivity.SCREEN_SETUP)
                        },
                        PanelAction("Cancel") { ui.closePanel() }
                    )
                )
            )
            return
        }

        val matches = withContext(Dispatchers.IO) { ContactResolver.find(context, command.contact) }
        when {
            matches.isEmpty() -> {
                record("Message", "Find \"${command.contact}\"", "not found")
                ui.showPanel(
                    PanelContent(
                        title = "No contact found",
                        body = "I couldn't find \"${command.contact}\" in your contacts. The message was not prepared.",
                        actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                    )
                )
            }
            matches.size == 1 -> confirmMessage(matches.first(), command.message)
            else -> ui.showPanel(
                PanelContent(
                    title = "Which contact?",
                    body = "I found ${matches.size} contacts matching \"${command.contact}\".",
                    actions = matches.take(4).map { match ->
                        PanelAction(contactLabel(match)) { confirmMessage(match, command.message) }
                    } + PanelAction("Cancel") { ui.closePanel() }
                )
            )
        }
    }

    private fun confirmMessage(contact: ContactMatch, message: String) {
        val number = ContactResolver.e164(context, contact.number) ?: contact.number
        val target = draftAppFor(number)
        record("Message", "Confirm draft to ${contact.name}", "awaiting user")
        ui.showPanel(
            PanelContent(
                title = "Message to ${contact.name}",
                body = "\u201C$message\u201D\n\n" + when (target) {
                    null -> "No messaging app can open a draft to this number, but you can copy the text."
                    else -> "${target.label} will open with this text ready. You still press Send."
                },
                scrollable = message.length > 180,
                actions = buildList {
                    if (target != null) {
                        add(
                            PanelAction("Open ${target.label}", primary = true) {
                                ui.closePanel()
                                openMessageDraft(contact, message)
                            }
                        )
                    }
                    add(
                        PanelAction("Copy text") {
                            copyToClipboard(message)
                            record("Message", "Text to ${contact.name} copied", "copied")
                        }
                    )
                    add(PanelAction("Cancel") { ui.closePanel() })
                }
            )
        )
    }

    private fun openMessageDraft(contact: ContactMatch, message: String) {
        // wa.me needs E.164; raw national digits sent the draft to no chat at all.
        val number = ContactResolver.e164(context, contact.number) ?: contact.number
        val target = draftAppFor(number)
        if (target == null) {
            copyToClipboard(message)
            record("Message", "Text to ${contact.name} copied", "copied")
            ui.showPanel(
                PanelContent(
                    title = "Message copied",
                    body = "No messaging app can open a draft to this number, so the text is copied to your clipboard.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
            return
        }
        val encoded = Uri.encode(message)
        val intent = when (target) {
            ChatApp.MESSAGES -> Intent(Intent.ACTION_SENDTO, Uri.parse(ChatLinks.smsLink(number))).apply {
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            else -> Intent(
                Intent.ACTION_VIEW,
                Uri.parse("${ChatLinks.whatsappLink(number)}?text=$encoded")
            ).apply {
                setPackage(target.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        val started = runCatching { context.startActivity(intent) }.isSuccess

        if (started) {
            record("Message", "Draft to ${contact.name}", "awaiting user")
            ui.showPanel(
                PanelContent(
                    title = "${target.label} opened",
                    body = "Review the message for ${contact.name}, then press Send yourself.",
                    autoDismissMs = 3000
                )
            )
        } else {
            copyToClipboard(message)
            record("Message", "Text to ${contact.name} copied", "copied")
            ui.showPanel(
                PanelContent(
                    title = "Message copied",
                    body = "No messaging app opened, so the text is copied to your clipboard.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
        }
    }

    /** The app that can address this number right now, or null when none can. */
    private fun draftAppFor(number: String): ChatApp? {
        fun canOpen(app: ChatApp): Boolean {
            val packageName = app.packageName ?: return smsWorks(number)
            if (!ChatLinks.isInternational(number)) return false
            if (!AppResolver.isInstalled(context, packageName)) return false
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ChatLinks.whatsappLink(number)))
                .setPackage(packageName)
            return intent.resolveActivity(context.packageManager) != null
        }
        return ChatLinks.draftApp(
            international = ChatLinks.isInternational(number),
            hasWhatsApp = canOpen(ChatApp.WHATSAPP),
            hasWhatsAppBusiness = canOpen(ChatApp.WHATSAPP_BUSINESS),
            hasSms = smsWorks(number)
        )
    }

    /**
     * Opens an existing conversation rather than a draft. Android has no "find this person's chat"
     * API, so this uses each app's published deep link, then requires two independent facts before
     * calling it done: the chat app is in front, and the person's name is visible in it.
     */
    private suspend fun openChat(command: Command.OpenChat) {
        if (!ContactResolver.hasPermission(context)) {
            record("Chat", "Find \"${command.contact}\"", "permission missing")
            ui.showPanel(
                PanelContent(
                    title = "Contacts permission needed",
                    body = "Your Bubble needs contact access to find ${command.contact}. You can grant it in the app setup.",
                    actions = listOf(
                        PanelAction("Open Your Bubble setup", primary = true) {
                            ui.closePanel()
                            MainActivity.open(context, MainActivity.SCREEN_SETUP)
                        },
                        PanelAction("Cancel") { ui.closePanel() }
                    )
                )
            )
            return
        }

        val people = withContext(Dispatchers.IO) { ContactResolver.findPeople(context, command.contact) }
        val reachable = people.filter { it.primaryNumber != null }
        when {
            reachable.size == 1 -> launchChat(reachable.first(), command.app)
            reachable.size > 1 -> ui.showPanel(
                PanelContent(
                    title = "Which contact?",
                    body = "I found ${reachable.size} contacts matching \"${command.contact}\".",
                    actions = reachable.take(4).map { person ->
                        PanelAction(contactLabel(person)) { launchChat(person, command.app) }
                    } + PanelAction("Cancel") { ui.closePanel() }
                )
            )
            else -> {
                val withoutNumber = people.firstOrNull()
                record("Chat", "Find \"${command.contact}\"", if (withoutNumber != null) "no number" else "not found")
                ui.showPanel(
                    PanelContent(
                        title = if (withoutNumber != null) "No number for ${withoutNumber.name}" else "No contact found",
                        body = if (withoutNumber != null) {
                            "${withoutNumber.name} has no phone number saved, and a chat can only be opened by number."
                        } else {
                            "I couldn't find \"${command.contact}\" in your contacts, so no chat was opened."
                        },
                        actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                    )
                )
            }
        }
    }

    private fun launchChat(person: ContactPerson, appPhrase: String?) {
        val number = person.primaryNumber ?: return
        ui.closePanel()
        val requested = ChatApp.named(appPhrase)
        val chosen = requested ?: availableChatApps(number).firstOrNull()
        if (chosen == null) {
            noChatApp(person, number)
            return
        }
        val blocker = chatBlocker(chosen, number)
        if (blocker != null) {
            record("Chat", "Open ${person.name} on ${chosen.label}", "unsupported")
            val alternates = availableChatApps(number)
            ui.showPanel(
                PanelContent(
                    title = "${chosen.label} isn't available",
                    body = blocker,
                    actions = alternates.take(2).map { app ->
                        PanelAction("Open ${app.label} instead", primary = true) { launchChat(person, app.label) }
                    } + PanelAction("Cancel") { ui.closePanel() }
                )
            )
            return
        }

        val intent = chatIntent(chosen, number)
        if (!start(intent)) {
            record("Chat", "Open ${person.name} on ${chosen.label}", "failed")
            ui.showPanel(
                PanelContent(
                    title = "Couldn't open ${chosen.label}",
                    body = "Android didn't accept the chat link for ${person.name}.",
                    actions = listOf(
                        PanelAction("Try again", primary = true) { launchChat(person, chosen.label) },
                        PanelAction("Close") { ui.closePanel() }
                    )
                )
            )
            return
        }

        // The request is a fact the moment Android accepts the link; confirmation replaces it later,
        // and history should show the run while it is still opening rather than one task late.
        record("Chat", "Open ${person.name} on ${chosen.label}", "requested")
        val service = FlowAccessibilityService.instance
        if (service == null) {
            ui.showPanel(
                PanelContent(
                    title = "${chosen.label} chat",
                    body = "Asked ${chosen.label} to open the conversation with ${person.name}. " +
                        "Accessibility is off, so I can't confirm it.",
                    autoDismissMs = 2600
                )
            )
            return
        }
        scope.launch { verifyChat(person, chosen, service, chatHandler(intent, chosen)) }
    }

    /**
     * The package that actually receives the chat intent. For SMS that is the user's messaging app,
     * not a name we can assume, and knowing it lets the foreground wait work for every app.
     */
    private fun chatHandler(intent: Intent, app: ChatApp): String? =
        app.packageName ?: runCatching { intent.resolveActivity(context.packageManager)?.packageName }
            .getOrNull()

    private suspend fun verifyChat(
        person: ContactPerson,
        app: ChatApp,
        service: FlowAccessibilityService,
        target: String?
    ) {
        val inFront = when {
            target == null -> true
            service.foregroundPackage() == target -> true
            else -> awaitPackage(service, target, CHAT_FOREGROUND_TIMEOUT_MS)
        }
        if (!inFront) {
            record("Chat", "Open ${person.name} on ${app.label}", "unverified")
            ui.showPanel(
                PanelContent(
                    title = "Couldn't confirm ${app.label}",
                    body = "Android accepted the request, but ${app.label} never came to the front, " +
                        "so the chat wasn't opened.",
                    actions = listOf(
                        PanelAction("Try again", primary = true) { launchChat(person, app.label) },
                        PanelAction("Close") { ui.closePanel() }
                    )
                )
            )
            return
        }
        if (awaitName(service, person.name)) {
            record("Chat", "Opened ${person.name}'s chat on ${app.label}", "done")
            ui.showPanel(
                PanelContent(
                    title = "${app.label} chat",
                    body = "Opened the conversation with ${person.name}.",
                    autoDismissMs = 2200
                )
            )
        } else {
            record("Chat", "Opened ${app.label} for ${person.name}", "unverified")
            ui.showPanel(
                PanelContent(
                    title = "Chat opened, contact not confirmed",
                    body = "${app.label} is on screen, but I couldn't see ${person.name}'s name in it. " +
                        if (app == ChatApp.MESSAGES) "The conversation may still be loading."
                        else "That number may not be registered in ${app.label}.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
        }
    }

    private suspend fun awaitPackage(
        service: FlowAccessibilityService,
        packageName: String,
        timeoutMs: Long
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (service.foregroundPackage() == packageName) return true
            delay(CHAT_POLL_MS)
        }
        return service.foregroundPackage() == packageName
    }

    private suspend fun awaitName(service: FlowAccessibilityService, name: String): Boolean {
        val deadline = System.currentTimeMillis() + CHAT_NAME_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (ChatLinks.mentions(service.screenText(3000), name)) return true
            delay(CHAT_POLL_MS)
        }
        return ChatLinks.mentions(service.screenText(3000), name)
    }

    private fun availableChatApps(number: String): List<ChatApp> =
        listOf(ChatApp.WHATSAPP, ChatApp.WHATSAPP_BUSINESS, ChatApp.MESSAGES)
            .filter { chatBlocker(it, number) == null }

    /** Why [app] cannot open a chat to [number] right now, or null when it can. */
    private fun chatBlocker(app: ChatApp, number: String): String? {
        if (app == ChatApp.MESSAGES) {
            return if (smsWorks(number)) null
            else "This device has no messaging app that can address that number."
        }
        val packageName = app.packageName ?: return null
        if (!AppResolver.isInstalled(context, packageName)) {
            return "I couldn't find ${app.label} on this device, so no chat was opened."
        }
        if (!ChatLinks.isInternational(number)) {
            return "${app.label} opens chats by international number, and this contact's saved number isn't one. " +
                "Add the country code to the contact, or use Messages."
        }
        return null
    }

    private fun smsWorks(number: String): Boolean {
        if (ChatLinks.digits(number).length < 3) return false
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(ChatLinks.smsLink(number)))
        return intent.resolveActivity(context.packageManager) != null
    }

    private fun chatIntent(app: ChatApp, number: String): Intent = when (app) {
        ChatApp.MESSAGES -> Intent(Intent.ACTION_SENDTO, Uri.parse(ChatLinks.smsLink(number)))
        else -> Intent(Intent.ACTION_VIEW, Uri.parse(ChatLinks.whatsappLink(number))).apply {
            setPackage(app.packageName)
        }
    }

    private fun noChatApp(person: ContactPerson, number: String) {
        record("Chat", "Open ${person.name}", "unsupported")
        ui.showPanel(
            PanelContent(
                title = "No chat app",
                body = "This device has no app that can open a conversation with ${person.name}.",
                actions = listOf(
                    PanelAction("Dial instead", primary = true) {
                        ui.closePanel()
                        if (start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")))) {
                            record("Call", "Dialer opened for ${person.name}", "awaiting user")
                        }
                    },
                    PanelAction("Copy number") { copyToClipboard(number) },
                    PanelAction("Cancel") { ui.closePanel() }
                )
            )
        )
    }

    private fun navigate(place: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(place)}"))
        if (start(intent)) {
            record("Maps", "Open \"$place\"", "awaiting user")
            ui.showPanel(
                PanelContent(
                    title = "Opening maps",
                    body = "Showing \"$place\" in your maps app. Start navigation when you're ready.",
                    autoDismissMs = 2400
                )
            )
        } else {
            record("Maps", "Open \"$place\"", "unsupported")
            ui.showPanel(
                PanelContent(
                    title = "No maps app",
                    body = "I couldn't find an app that opens map locations.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
        }
    }

    // ---- Watching and pointing ---------------------------------------------------------------
    // "keep watching this screen" opens the live session, and "the third one" / "that chat at the
    // top" / "the blue button" is answered from the ordered targets it maintains. Both degrade
    // honestly: with no session a pointing phrase falls back to the ordinal/label tap it used to do,
    // and an ambiguous reference becomes a question instead of a guess.

    /** Revision the watch opened at, so "that" means what appeared since the user started looking. */
    private var pointingBaselineRevision = 0

    private fun watch(command: Command.Watch) {
        if (command.enabled) {
            // "watch until it's done" is eyes for one task: the agent releases an unpinned session when
            // the run finishes, while a plain "keep watching" is pinned until the user says stop.
            ScreenSession.start(context, fromUser = !command.untilDone)
            if (!ScreenSession.isWatching()) {
                record("Screen", "Start watching the screen", "failed")
                ui.showPanel(
                    PanelContent(
                        title = "I couldn't start watching",
                        body = ScreenSession.error() ?: "Your Bubble needs accessibility access to read the screen.",
                        actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                    )
                )
                return
            }
            pointingBaselineRevision = ScreenSession.revision()
            record(
                "Screen",
                if (command.untilDone) "Watching until the task is done" else "Watching the screen",
                "done"
            )
            ui.showPanel(
                PanelContent(
                    title = "Watching the screen",
                    body = ScreenSession.status() + "\n\n" +
                        (if (command.untilDone) {
                            "I'll stop when the task finishes."
                        } else {
                            "Say \u201Cstop watching\u201D to end it. Frames stay in memory only, " +
                                "are never saved, and are only read when something on screen changes."
                        }),
                    scrollable = true,
                    actions = listOf(
                        PanelAction("Stop watching") {
                            ScreenSession.stop(context)
                            record("Screen", "Stopped watching the screen", "done")
                            ui.showPanel(PanelContent(title = "Stopped watching", autoDismissMs = 1200))
                        },
                        PanelAction("Close", primary = true) { ui.closePanel() }
                    )
                )
            )
            return
        }
        val wasWatching = ScreenSession.isWatching()
        ScreenSession.stop(context)
        record("Screen", "Stopped watching the screen", if (wasWatching) "done" else "unverified")
        ui.showPanel(
            PanelContent(
                title = if (wasWatching) "Stopped watching" else "I wasn't watching",
                body = if (wasWatching) {
                    "Screen reading is off and anything it was holding is released."
                } else {
                    "Nothing was watching this screen, so nothing changed."
                },
                autoDismissMs = 1600
            )
        )
    }

    private suspend fun point(command: Command.Point) {
        val service = FlowAccessibilityService.instance
        if (service == null) {
            accessibilityBlocked("tap on screen")
            return
        }
        val appeared = appearedForPointing()
        val resolution = ScreenSession.resolvePointing(command.phrase, appeared)
            ?: run {
                val model = buildModel(service, includePixels = ScreenCapture.isActive)
                    ?: return@run null
                TargetLocator.resolve(
                    phrase = command.phrase,
                    targets = model.targets,
                    appeared = appeared,
                    hueOf = { box -> ScreenSession.hueAt(box) },
                    screenWidth = model.screenWidth,
                    screenHeight = model.screenHeight
                )
            }
        when (resolution) {
            null -> tap(Command.Tap(command.phrase, TargetLocator.parse(command.phrase).ordinal))
            is PointResolution.Found -> {
                if (resolution.target.label.isBlank()) {
                    record("Screen", "Point at ${command.phrase}", "unverified")
                    ui.showPanel(
                        PanelContent(
                            title = "I can't name that one",
                            body = "I think you mean the control ${resolution.evidence}, but it has no " +
                                "readable label, so I won't tap a blind coordinate.",
                            actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                        )
                    )
                    return
                }
                ui.showPanel(
                    PanelContent(
                        title = "\u201C${command.phrase}\u201D",
                        body = "I read it as \u201C${resolution.target.label}\u201D - ${resolution.evidence}.",
                        autoDismissMs = 1200
                    )
                )
                delay(300)
                tap(Command.Tap(resolution.target.label))
            }
            is PointResolution.Ambiguous -> clarify(
                Command.Clarify(
                    question = resolution.question,
                    body = resolution.reason,
                    choices = resolution.candidates.map { it.label }.filter { it.isNotBlank() }
                )
            )
            is PointResolution.NotFound -> {
                // Nothing matched, but the old label/ordinal tap may still know: a screen with no
                // actionable targets at all shouldn't lose the behaviour it had before this existed.
                record("Screen", "Point at ${command.phrase}", "unverified")
                ui.showPanel(
                    PanelContent(
                        title = "I couldn't tell which one",
                        body = resolution.reason + " Say \u201Ckeep watching this screen\u201D and try again, " +
                            "or name the button itself.",
                        actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                    )
                )
            }
        }
    }

    private fun appearedForPointing(): Set<String> {
        if (!ScreenSession.isWatching()) return emptySet()
        return ScreenSession.since(pointingBaselineRevision).appeared.toSet()
    }

    private fun clarify(command: Command.Clarify) {
        val choices = command.choices.filter { it.isNotBlank() }.take(4)
        if (choices.isEmpty()) {
            ui.showPanel(
                PanelContent(
                    title = "Which one?",
                    body = command.body.ifBlank { "I can see more than one match and I won't guess." },
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
            record("Screen", "Asked which one", OutcomeKind.AWAITING_USER.historyStatus())
            return
        }
        ui.showPanel(
            PanelContent(
                title = command.question.ifBlank { "Which one?" },
                body = command.body + " I won't tap anything until you pick.",
                actions = choices.map { choice ->
                    PanelAction(choice.take(28)) {
                        val next = CommandParser.parse(command.template.replace("%s", choice))
                            ?: Command.Tap(choice)
                        execute(next, command.template.replace("%s", choice))
                    }
                } + PanelAction("Cancel") { ui.closePanel() }
            )
        )
        record("Screen", "Asked which one: ${choices.joinToString(", ").take(80)}", OutcomeKind.AWAITING_USER.historyStatus())
    }

    private suspend fun tap(command: Command.Tap) {
        val service = FlowAccessibilityService.instance
        if (service == null) {
            accessibilityBlocked("tap on screen")
            return
        }
        val risk = SafetyPolicy.assessTap(command.target)
        when (risk.level) {
            RiskLevel.BLOCKED -> {
                record("Screen", "Tap ${command.target}", "blocked")
                ui.showPanel(
                    PanelContent(
                        title = "I won't tap that",
                        body = risk.reason,
                        actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                    )
                )
                return
            }
            RiskLevel.CONFIRM -> {
                val approved = confirm(
                    title = "Tap \u201C${command.target}\u201D?",
                    body = risk.reason + " Your Bubble will only tap it if you approve.",
                    approveLabel = "Tap it"
                )
                if (!approved) {
                    record("Screen", "Tap ${command.target}", "cancelled")
                    return
                }
                // The confirmation window holds focus while it closes; let the app
                // reclaim the screen before resolving the control's label.
                delay(250)
            }
            RiskLevel.SAFE -> Unit
        }
        val result = if (command.ordinal != null) {
            service.tapOrdinal(command.ordinal)
        } else {
            service.tapByText(command.target, controlsOnly = risk.level != RiskLevel.SAFE)
        }
        if (result.success) {
            val label = result.label ?: command.target
            record("Screen", "Tapped $label", "done")
            ui.showPanel(PanelContent(title = "Tapped \"$label\"", autoDismissMs = 1200))
        } else {
            record("Screen", "Tap ${command.target}", "failed")
            ui.showPanel(
                PanelContent(
                    title = "Couldn't tap that",
                    body = result.note ?: "I couldn't find a tappable match.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
        }
    }

    private fun scroll(forward: Boolean, times: Int = 1) {
        val service = FlowAccessibilityService.instance
        if (service == null) {
            accessibilityBlocked("scroll")
            return
        }
        var scrolled = false
        repeat(times.coerceIn(1, 5)) {
            if (service.scroll(forward)) scrolled = true
        }
        val direction = if (forward) "down" else "up"
        val suffix = if (times > 1) " $times times" else ""
        if (scrolled) {
            record("Screen", "Scrolled $direction$suffix", "done")
            ui.showPanel(PanelContent(title = "Scrolled $direction$suffix", autoDismissMs = 900))
        } else {
            record("Screen", "Scroll $direction", "failed")
            ui.showPanel(
                PanelContent(
                    title = "Couldn't scroll",
                    body = "This screen didn't accept a scroll action.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
        }
    }

    private fun globalAction(label: String, action: () -> Boolean?) {
        val service = FlowAccessibilityService.instance
        if (service == null) {
            accessibilityBlocked(label.lowercase())
            return
        }
        val done = action() == true
        if (done) {
            record("Screen", label, "done")
            ui.showPanel(PanelContent(title = label, autoDismissMs = 800))
        } else {
            record("Screen", label, "failed")
            ui.showPanel(
                PanelContent(
                    title = "That didn't work",
                    body = "Android didn't accept the navigation action.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
        }
    }

    private suspend fun readScreen() {
        val service = FlowAccessibilityService.instance
        if (service == null) {
            accessibilityBlocked("read the screen")
            return
        }
        val model = buildModel(service, includePixels = true)
        val text = model?.readableText(5000).orEmpty()
        if (text.isBlank()) {
            val ownScreen = (model?.packageName ?: service.currentPackage()) == context.packageName
            val detail = model?.captureError
                ?: model?.ocrError
                ?: if (ownScreen) {
                    "This is Your Bubble's own screen, and I skip my own interface through accessibility. " +
                        "Open another app, or turn on screen access so I can read pixels."
                } else {
                    "I couldn't read any text on this screen."
                }
            record("Read", "Read screen", "nothing found")
            ui.showPanel(
                PanelContent(
                    title = "Nothing readable here",
                    body = detail,
                    actions = screenAccessActions()
                )
            )
            return
        }
        val source = model?.describeSource() ?: "accessibility"
        val partial = model?.captureError?.let { "\n\n$it" }.orEmpty()
        LastActionMemory.rememberOutput(text)
        record("Read", "Read ${text.length} characters via $source", "done")
        if (LlmClient.isConfigured(context, AiRole.Summarize)) {
            ui.showPanel(
                PanelContent(
                    title = "Read this screen",
                    body = "I found ${text.length} characters using $source.$partial\n\n" +
                        "Summarize with your configured AI model? Screen text goes to ${LlmClient.endpointLabel(context, AiRole.Summarize)}.",
                    actions = listOf(
                        PanelAction("Summarize with AI", primary = true) { summarize(text) },
                        PanelAction("Show text") { showScreenText(text) },
                        PanelAction("Cancel") { ui.closePanel() }
                    )
                )
            )
        } else {
            showScreenText(text)
        }
    }

    private fun screenAccessActions(): List<PanelAction> = buildList {
        if (!ScreenCapture.isActive) {
            add(
                PanelAction("Turn on screen access", primary = true) {
                    ui.closePanel()
                    ScreenCapture.requestConsent(context)
                }
            )
        }
        add(PanelAction(if (ScreenCapture.isActive) "Close" else "Cancel") { ui.closePanel() })
    }

    private fun showScreenText(text: String) {
        LastActionMemory.rememberOutput(text)
        ui.showPanel(
            PanelContent(
                title = "Screen text",
                body = text.take(3500),
                scrollable = true,
                copyText = text,
                actions = listOf(
                    PanelAction("Copy", primary = true) { copyToClipboard(text) },
                    PanelAction("Close") { ui.closePanel() }
                )
            )
        )
    }

    private fun typeText(text: String) {
        if (FlowAccessibilityService.instance == null) {
            accessibilityBlocked("type into this screen")
            return
        }
        agent.startWithPlan(
            scope,
            AgentPlan(
                goal = "Type \u201C${text.take(40)}\u201D",
                steps = listOf(
                    AgentStep(
                        AgentAction.TypeText(text),
                        "Type into the focused field"
                    )
                ),
                source = PlanSource.LOCAL
            )
        )
    }

    private suspend fun search(command: Command.Search) {
        command.app?.let { target ->
            searchNamedApp(command.query, target)
            return
        }
        val query = command.query
        val service = FlowAccessibilityService.instance
        if (service == null) {
            if (command.scope == SearchScope.CURRENT) {
                accessibilityBlocked("search this screen")
            } else {
                openUrl(ActionParsing.searchUrl(query), "Search for \u201C$query\u201D")
            }
            return
        }
        val model = buildModel(service, includePixels = false)
        val hasField = model != null && ScreenFusion.searchField(model.targets) != null
        when {
            hasField -> agent.startWithPlan(
                scope,
                AgentPlan(
                    goal = "Search for \u201C$query\u201D",
                    steps = listOf(
                        AgentStep(
                            AgentAction.TypeText(query, pressEnter = true, searchFieldOnly = true),
                            "Search this screen for \u201C$query\u201D"
                        )
                    ),
                    source = PlanSource.LOCAL
                )
            )
            command.scope == SearchScope.CURRENT -> {
                record("Screen", "Search this screen for \"$query\"", "not found")
                val extra = if (!ScreenCapture.isActive) {
                    " Turn on screen access so I can see controls that accessibility can't read."
                } else {
                    ""
                }
                ui.showPanel(
                    PanelContent(
                        title = "No search field here",
                        body = "I don't see a search box on this screen.$extra",
                        actions = screenAccessActions()
                    )
                )
            }
            else -> openUrl(ActionParsing.searchUrl(query), "Search for \u201C$query\u201D")
        }
    }

    /**
     * "search movies in bookmyshow": open the named app, then drive its own search box. If the
     * word after "in" turns out to name no installed app, this is a normal web search instead —
     * "find a restaurant in Hyderabad" must not look like a failed app launch.
     */
    private suspend fun searchNamedApp(query: String, appName: String) {
        val matches = withContext(Dispatchers.IO) { AppResolver.find(context, appName) }
        val phrase = "$query in $appName"
        when {
            matches.isEmpty() -> openUrl(ActionParsing.searchUrl(phrase), "Search for \u201C$phrase\u201D")
            matches.size == 1 -> searchInside(matches.first(), query)
            else -> ui.showPanel(
                PanelContent(
                    title = "Which app?",
                    body = "I found ${matches.size} apps matching \"$appName\".",
                    actions = matches.take(4).map { match ->
                        PanelAction("Search ${match.label}", primary = match == matches.first()) {
                            scope.launch { searchInside(match, query) }
                        }
                    } + PanelAction("Search the web instead") {
                        ui.closePanel()
                        openUrl(ActionParsing.searchUrl(phrase), "Search for \u201C$phrase\u201D")
                    } + PanelAction("Cancel") { ui.closePanel() }
                )
            )
        }
    }

    private suspend fun searchInside(target: AppMatch, query: String) {
        val phrase = "Search ${target.label} for \"$query\""
        ui.closePanel()
        if (!AppResolver.launch(context, target.packageName)) {
            record("Search", phrase, "failed")
            ui.showPanel(
                PanelContent(
                    title = "Couldn't open ${target.label}",
                    body = "Android refused to launch this app.",
                    actions = listOf(
                        PanelAction("Search the web", primary = true) {
                            ui.closePanel()
                            openUrl(ActionParsing.searchUrl(query), "Search for \u201C$query\u201D")
                        },
                        PanelAction("Close") { ui.closePanel() }
                    )
                )
            )
            return
        }
        ui.showPanel(PanelContent(title = "Opening ${target.label}", autoDismissMs = 1200))
        if (!waitForForeground(target.packageName)) {
            record("Search", phrase, "unverified")
            ui.showPanel(
                PanelContent(
                    title = "Couldn't confirm ${target.label}",
                    body = "Android never brought it to the front, so I didn't type anything.",
                    actions = listOf(
                        PanelAction("Try again", primary = true) { scope.launch { searchInside(target, query) } },
                        PanelAction("Search the web") {
                            ui.closePanel()
                            openUrl(ActionParsing.searchUrl(query), "Search for \u201C$query\u201D")
                        },
                        PanelAction("Close") { ui.closePanel() }
                    )
                )
            )
            return
        }
        val service = FlowAccessibilityService.instance
        if (service == null) {
            accessibilityBlocked("search inside ${target.label}")
            return
        }
        record("Search", phrase, "requested")
        agent.startWithPlan(
            scope,
            AgentPlan(
                goal = "Search ${target.label} for \u201C$query\u201D",
                steps = listOf(
                    AgentStep(
                        AgentAction.TypeText(query, pressEnter = true, searchFieldOnly = true),
                        "Type the search into ${target.label}"
                    )
                ),
                source = PlanSource.LOCAL
            )
        )
    }

    private suspend fun waitForForeground(packageName: String): Boolean {
        val service = FlowAccessibilityService.instance ?: return false
        return awaitForeground(service, packageName)
    }

    private fun openUrl(url: String, label: String = url) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (start(intent)) {
            record("Web", label, "awaiting user")
            ui.showPanel(
                PanelContent(
                    title = "Opening",
                    body = label,
                    autoDismissMs = 1800
                )
            )
        } else {
            record("Web", label, "unsupported")
            ui.showPanel(
                PanelContent(
                    title = "No browser",
                    body = "I couldn't find an app that opens links on this device.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
        }
    }

    private fun screenshot() {
        val service = FlowAccessibilityService.instance
        if (service == null) {
            accessibilityBlocked("take a screenshot")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            record("System", "Screenshot", "unsupported")
            ui.showPanel(
                PanelContent(
                    title = "Screenshot needs Android 11",
                    body = "Android only lets apps request a system screenshot from Android 11 onward. " +
                        "Use the power and volume keys on this device.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
            return
        }
        if (!service.takeScreenshot()) {
            record("System", "Screenshot", "failed")
            ui.showPanel(
                PanelContent(
                    title = "Screenshot didn't start",
                    body = "Android didn't accept the screenshot action. The system may be busy, or this app blocks capture.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
            return
        }
        record("System", "Screenshot requested", "requested")
        ui.showPanel(
            PanelContent(
                title = "Screenshot requested",
                body = "Android is taking the screenshot and shows its own save or share sheet. " +
                    "Some apps block screenshots, and I can't confirm the result.",
                autoDismissMs = 3000
            )
        )
    }

    private fun readNotifications() {
        if (!NotificationCenter.isAccessGranted(context)) {
            notificationAccessBlocked()
            return
        }
        if (!NotificationCenter.isConnected()) {
            record("Notifications", "Read notifications", "not connected")
            ui.showPanel(
                PanelContent(
                    title = "Notification access is starting",
                    body = "Android granted access, but the listener hasn't connected yet. " +
                        "Toggle notification access off and on, then try again.",
                    actions = notificationAccessActions()
                )
            )
            return
        }
        val entries = NotificationCenter.recent()
        if (entries.isEmpty()) {
            record("Notifications", "Read notifications", "nothing found")
            ui.showPanel(
                PanelContent(
                    title = "No recent notifications",
                    body = "Your Bubble keeps notifications from this session in memory only, and none have arrived yet. " +
                        "It never reads your notification history.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
            return
        }
        val digest = NotificationCenter.digest(entries)
        record("Notifications", "Read ${entries.size} notifications", "done")
        ui.showPanel(
            PanelContent(
                title = "Recent notifications",
                body = digest,
                scrollable = true,
                copyText = digest,
                actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
            )
        )
    }

    private suspend fun clearNotifications() {
        if (!NotificationCenter.isAccessGranted(context)) {
            notificationAccessBlocked()
            return
        }
        val service = FlowNotificationService.instance
        if (service == null) {
            record("Notifications", "Clear notifications", "not connected")
            ui.showPanel(
                PanelContent(
                    title = "Notification access is starting",
                    body = "Android granted access, but the listener hasn't connected yet. Try again in a moment.",
                    actions = notificationAccessActions()
                )
            )
            return
        }
        val entries = NotificationCenter.recent()
        val dismissible = entries.count { !it.ongoing }
        if (dismissible == 0) {
            record("Notifications", "Clear notifications", "nothing found")
            ui.showPanel(
                PanelContent(
                    title = "Nothing to clear",
                    body = "I only dismiss notifications that arrived while Your Bubble was connected. " +
                        "Ongoing ones, like music or navigation, are never closed.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
            return
        }
        record("Notifications", "Clear notifications", "awaiting user")
        val approved = confirm(
            title = "Clear notifications?",
            body = "I'll dismiss $dismissible notification${if (dismissible == 1) "" else "s"} that arrived since " +
                "Your Bubble connected. Ongoing ones stay. This can't be undone.",
            approveLabel = "Dismiss"
        )
        if (!approved) return
        val dismissed = runCatching { service.dismissRecorded() }.getOrDefault(0)
        if (dismissed == 0) {
            record("Notifications", "Clear notifications", "failed")
            ui.showPanel(
                PanelContent(
                    title = "Couldn't clear notifications",
                    body = "Android didn't dismiss them. Some apps or device policies block dismissal.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
        } else {
            record("Notifications", "Dismissed $dismissed notifications", "done")
            ui.showPanel(
                PanelContent(
                    title = "Cleared",
                    body = "Dismissed $dismissed notification${if (dismissed == 1) "" else "s"}.",
                    autoDismissMs = 1600
                )
            )
        }
    }

    private fun notificationAccessBlocked() {
        record("Notifications", "Read notifications", "permission missing")
        ui.showPanel(
            PanelContent(
                title = "Notification access needed",
                body = "Android hides notifications from apps unless you grant notification access in system settings. " +
                    "Your Bubble only keeps what arrives after you grant it, and never stores it.",
                actions = notificationAccessActions()
            )
        )
    }

    private fun notificationAccessActions(): List<PanelAction> = listOf(
        PanelAction("Open notification access", primary = true) {
            ui.closePanel()
            NotificationCenter.openSettings(context)
        },
        PanelAction("Cancel") { ui.closePanel() }
    )

    private suspend fun askScreen(question: String) {
        screenAi(
            role = AiRole.ScreenAnswer,
            actionLabel = "Ask AI about the screen",
            requestDescription = "answer \u201C${question.take(80)}\u201D",
            resultTitle = "Answer",
            request = { text -> LlmClient.askAboutScreen(question, text, LlmClient.config(context, AiRole.ScreenAnswer)) }
        )
    }

    private suspend fun translateScreen(language: String) {
        screenAi(
            role = AiRole.Translate,
            actionLabel = "Translate the screen",
            requestDescription = "translate it to $language",
            resultTitle = "Translation",
            request = { text -> LlmClient.translateScreen(language, text, LlmClient.config(context, AiRole.Translate)) }
        )
    }

    private suspend fun screenAi(
        role: AiRole,
        actionLabel: String,
        requestDescription: String,
        resultTitle: String,
        request: suspend (String) -> Result<String>
    ) {
        if (!LlmClient.isConfigured(context, role)) {
            record("AI", actionLabel, "setup needed")
            ui.showPanel(
                PanelContent(
                    title = "AI model not configured",
                    body = "Add an OpenAI-compatible endpoint and key in Your Bubble settings. " +
                        "Screen answers and translation stay off until you do.",
                    actions = listOf(
                        PanelAction("Open Your Bubble settings", primary = true) {
                            ui.closePanel()
                            MainActivity.open(context, MainActivity.SCREEN_SETTINGS)
                        },
                        PanelAction("Cancel") { ui.closePanel() }
                    )
                )
            )
            return
        }
        val service = FlowAccessibilityService.instance
        if (service == null) {
            accessibilityBlocked(actionLabel.lowercase())
            return
        }
        val model = buildModel(service, includePixels = true)
        val text = model?.readableText(6000).orEmpty()
        if (text.isBlank()) {
            record("AI", actionLabel, "nothing found")
            ui.showPanel(
                PanelContent(
                    title = "Nothing readable here",
                    body = model?.captureError
                        ?: "I couldn't read enough text on this screen to work with.",
                    actions = screenAccessActions()
                )
            )
            return
        }
        val endpoint = LlmClient.endpointLabel(context, role)
        val approved = confirm(
            title = "Send screen text to AI?",
            body = "To $requestDescription, I'll send ${text.length} characters of visible text to $endpoint. " +
                "Audio and screenshots are not sent, and nothing is stored.",
            approveLabel = "Send"
        )
        if (!approved) {
            record("AI", actionLabel, "awaiting user")
            return
        }
        ui.showPanel(
            PanelContent(
                title = "Asking your AI model",
                body = "This can take a few seconds.",
                dismissible = false
            )
        )
        val result = request(text)
        result.fold(
            onSuccess = { answer ->
                LastActionMemory.rememberOutput(answer)
                record("AI", actionLabel, "done")
                ui.showPanel(
                    PanelContent(
                        title = resultTitle,
                        body = answer,
                        scrollable = true,
                        copyText = answer,
                        actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                    )
                )
            },
            onFailure = { error ->
                record("AI", actionLabel, "failed")
                ui.showPanel(
                    PanelContent(
                        title = "AI request failed",
                        body = error.message ?: "The model didn't answer.",
                        actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                    )
                )
            }
        )
    }

    private fun mediaToggle() {
        val service = FlowAccessibilityService.instance
        if (service == null) {
            accessibilityBlocked("control media")
            return
        }
        if (!service.mediaPlayPause()) {
            record("Media", "Toggle playback", "unsupported")
            ui.showPanel(
                PanelContent(
                    title = "No media to control",
                    body = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        "Playback control needs Android 12 or newer."
                    } else {
                        "Android didn't report an active media session."
                    },
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
            return
        }
        record("Media", "Toggled playback", "requested")
        ui.showPanel(
            PanelContent(
                title = "Playback toggled",
                body = "I sent play or pause to the active media session. " +
                    "Android doesn't report the resulting state, so check the player.",
                autoDismissMs = 2200
            )
        )
    }

    private suspend fun followUp(command: Command.FollowUp) {
        when (command.kind) {
            FollowUpKind.UNDO -> undoLastEdit()
            FollowUpKind.AGAIN -> replayLast()
            FollowUpKind.COPY -> copyLastText()
            else -> rewriteLast(command.kind, command.language)
        }
    }

    private fun undoLastEdit() {
        val edit = LastActionMemory.edit
        if (edit == null) {
            record("Undo", "Undo the last edit", "nothing found")
            ui.showPanel(
                PanelContent(
                    title = "Nothing to undo",
                    body = "I don't have an insertion or edit from this session to undo. " +
                        "The Undo button on the result panel works too.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
            return
        }
        val service = FlowAccessibilityService.instance
        if (service == null) {
            accessibilityBlocked("undo the last edit")
            return
        }
        if (service.undoInsert(edit.previous, edit.current)) {
            LastActionMemory.forgetEdit()
            record("Undo", "Reverted the last edit", "done")
            ui.showPanel(PanelContent(title = "Undone", autoDismissMs = 1400))
        } else {
            record("Undo", "Undo the last edit", "failed")
            ui.showPanel(
                PanelContent(
                    title = "Couldn't undo",
                    body = "The text field changed or lost focus, so Your Bubble won't overwrite it.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
        }
    }

    private suspend fun replayLast() {
        val raw = LastActionMemory.raw
        val parsed = raw?.let { CommandParser.parse(it) }
        val blocked = raw.isNullOrBlank() || parsed == null || parsed is Command.FollowUp ||
            parsed is Command.SaveRoutine || parsed is Command.RunRoutine ||
            parsed is Command.DeleteRoutine || parsed == Command.ListRoutines
        if (blocked) {
            record("Repeat", "Repeat the last command", "nothing found")
            ui.showPanel(
                PanelContent(
                    title = "Nothing to repeat",
                    body = "I don't have a recent command to run again. Try a command once, then say \u201Ctry that again\u201D.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
            return
        }
        runCommand(parsed, raw)
    }

    private fun copyLastText() {
        val text = LastActionMemory.latestText()?.first
            ?: FlowAccessibilityService.instance?.currentFieldText()
        if (text.isNullOrBlank()) {
            record("Clipboard", "Copy the last text", "nothing found")
            ui.showPanel(
                PanelContent(
                    title = "Nothing to copy",
                    body = "I don't have a recent answer or edit to copy yet.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
            return
        }
        copyToClipboard(text)
        record("Clipboard", "Copied the last text", "copied")
        ui.showPanel(
            PanelContent(
                title = "Copied",
                body = text.take(240),
                autoDismissMs = 2000
            )
        )
    }

    private suspend fun rewriteLast(kind: FollowUpKind, language: String?) {
        val latest = LastActionMemory.latestText()
        if (latest == null) {
            record("Rewrite", rewriteLabel(kind), "nothing found")
            ui.showPanel(
                PanelContent(
                    title = "Nothing to rewrite yet",
                    body = "Dictate or edit some text first, then \u201C${examplePhrase(kind)}\u201D works on it. " +
                        "Follow-ups also work on the last answer I showed you.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
            return
        }
        val (source, fromField) = latest
        val local = localRewrite(kind, source)
        if (local == null && !LlmClient.isConfigured(context, AiRole.Edit)) {
            record("Rewrite", rewriteLabel(kind), "setup needed")
            ui.showPanel(
                PanelContent(
                    title = "AI model needed",
                    body = "That rewrite needs an AI model. Add an OpenAI-compatible endpoint in Your Bubble settings. " +
                        "Professional, casual, and grammar rewrites work on-device without one.",
                    actions = listOf(
                        PanelAction("Open Your Bubble settings", primary = true) {
                            ui.closePanel()
                            MainActivity.open(context, MainActivity.SCREEN_SETTINGS)
                        },
                        PanelAction("Cancel") { ui.closePanel() }
                    )
                )
            )
            return
        }
        ui.showPanel(
            PanelContent(
                title = "Rewriting",
                body = "Working on the last text.",
                dismissible = false
            )
        )
        val rewritten = local ?: run {
            val result = LlmClient.edit(source, aiInstruction(kind, language), LlmClient.config(context, AiRole.Edit))
            result.getOrElse { error ->
                record("Rewrite", rewriteLabel(kind), "failed")
                ui.showPanel(
                    PanelContent(
                        title = "Rewrite failed",
                        body = error.message ?: "The AI model didn't answer. Your text is unchanged.",
                        actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                    )
                )
                return
            }.trim()
        }
        if (rewritten.isBlank()) {
            record("Rewrite", rewriteLabel(kind), "nothing found")
            ui.showPanel(
                PanelContent(
                    title = "No change returned",
                    body = "The rewrite came back empty, so your text is unchanged.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
            return
        }
        if (rewritten == source) {
            record("Rewrite", rewriteLabel(kind), "done")
            ui.showPanel(
                PanelContent(
                    title = "No change needed",
                    body = "That text already looks like the version you asked for.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
            return
        }
        if (fromField) {
            val memory = LastActionMemory.edit
            if (memory != null) {
                val fullReplacement = memory.prefix + rewritten + memory.suffix
                if (applyRewrite(kind, memory.current, fullReplacement, rewritten, memory.fieldLabel)) return
            }
        }
        LastActionMemory.rememberOutput(rewritten)
        record("Rewrite", rewriteLabel(kind), "done")
        ui.showPanel(
            PanelContent(
                title = rewriteTitle(kind),
                body = rewritten.take(3000),
                scrollable = rewritten.length > 400,
                copyText = rewritten,
                actions = listOf(
                    PanelAction("Copy", primary = true) { copyToClipboard(rewritten) },
                    PanelAction("Close") { ui.closePanel() }
                )
            )
        )
    }

    private fun applyRewrite(
        kind: FollowUpKind,
        expectedFieldText: String,
        fullReplacement: String,
        display: String,
        label: String?
    ): Boolean {
        val service = FlowAccessibilityService.instance ?: return false
        return when (val result = service.replaceFocusedFieldIf(expectedFieldText, fullReplacement)) {
            is TextInsertResult.Inserted -> {
                LastActionMemory.rememberEdit(result.previousText, result.newText, label)
                record("Rewrite", rewriteLabel(kind), "done")
                ui.showPanel(
                    PanelContent(
                        title = rewriteTitle(kind),
                        body = display.take(3000),
                        scrollable = display.length > 400,
                        copyText = display,
                        actions = listOf(
                            PanelAction("Undo", primary = true) { undoLastEdit() },
                            PanelAction("Copy") { copyToClipboard(display) },
                            PanelAction("Close") { ui.closePanel() }
                        )
                    )
                )
                true
            }
            is TextInsertResult.Copied -> {
                LastActionMemory.rememberOutput(display)
                record("Rewrite", "${rewriteLabel(kind)} (copied)", "copied")
                ui.showPanel(
                    PanelContent(
                        title = rewriteTitle(kind),
                        body = result.message + "\n\n\u201C${display.take(800)}\u201D",
                        scrollable = display.length > 400,
                        copyText = display,
                        actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                    )
                )
                true
            }
            is TextInsertResult.Failed -> false
        }
    }

    private fun localRewrite(kind: FollowUpKind, text: String): String? = when (kind) {
        FollowUpKind.TIDY -> TextProcessor.clean(text)
        FollowUpKind.PROFESSIONAL -> TextProcessor.professional(text)
        FollowUpKind.CASUAL -> TextProcessor.casual(text)
        else -> null
    }

    private fun aiInstruction(kind: FollowUpKind, language: String?): String = when (kind) {
        FollowUpKind.SHORTER ->
            "Make the text shorter while keeping its meaning and key details. Return only the rewritten text."
        FollowUpKind.LONGER ->
            "Expand the text with a little more detail without inventing facts. Return only the rewritten text."
        FollowUpKind.SUMMARIZE ->
            "Summarize the text in at most three short sentences. Return only the summary."
        FollowUpKind.TRANSLATE ->
            "Translate the text into ${language ?: "the requested language"}. Return only the translation."
        else -> "Rewrite the text as asked. Return only the rewritten text."
    }

    private fun rewriteLabel(kind: FollowUpKind): String = when (kind) {
        FollowUpKind.SHORTER -> "Shorter"
        FollowUpKind.LONGER -> "Longer"
        FollowUpKind.PROFESSIONAL -> "Professional tone"
        FollowUpKind.CASUAL -> "Casual tone"
        FollowUpKind.TIDY -> "Grammar fix"
        FollowUpKind.SUMMARIZE -> "Summary"
        FollowUpKind.TRANSLATE -> "Translated"
        else -> "Rewrite"
    }

    private fun rewriteTitle(kind: FollowUpKind): String = when (kind) {
        FollowUpKind.SHORTER -> "Shorter version"
        FollowUpKind.LONGER -> "Longer version"
        FollowUpKind.SUMMARIZE -> "Summary"
        FollowUpKind.TRANSLATE -> "Translation"
        else -> "Rewritten"
    }

    private fun examplePhrase(kind: FollowUpKind): String = when (kind) {
        FollowUpKind.SHORTER -> "make it shorter"
        FollowUpKind.LONGER -> "make it longer"
        FollowUpKind.PROFESSIONAL -> "make it professional"
        FollowUpKind.CASUAL -> "make it casual"
        FollowUpKind.TIDY -> "fix the grammar"
        FollowUpKind.SUMMARIZE -> "summarize that"
        FollowUpKind.TRANSLATE -> "translate that to Hindi"
        else -> "make it shorter"
    }

    private fun saveRoutine(command: Command.SaveRoutine) {
        val phrase = command.phrase?.trim()?.trimEnd('.', '!')
            ?: LastActionMemory.raw?.trim()
        if (phrase.isNullOrBlank()) {
            record("Routine", "Save ${command.name}", "nothing found")
            ui.showPanel(
                PanelContent(
                    title = "Nothing to save yet",
                    body = "Run a command once, then say \u201Csave this as routine ${command.name}\u201D. " +
                        "You can also save one directly: \u201Csave routine work for open Slack\u201D.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
            return
        }
        if (!isReplayable(phrase)) {
            record("Routine", "Save ${command.name}", "unsupported")
            ui.showPanel(
                PanelContent(
                    title = "Routine not saved",
                    body = "A routine has to replay a command I recognize, like \u201Copen Slack\u201D or " +
                        "\u201Copen Chrome and search for weather\u201D.\n\n" +
                        "\u201C${phrase.take(160)}\u201D isn't one I can replay.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
            return
        }
        when (val result = RoutineStore.save(context, command.name, phrase)) {
            is RoutineStore.SaveResult.Saved -> {
                record("Routine", "Saved ${result.routine.name}", "done")
                ui.showPanel(
                    PanelContent(
                        title = "Routine saved",
                        body = "\u201C${result.routine.name}\u201D will run: ${result.routine.phrase}\n\n" +
                            "Say \u201Crun routine ${result.routine.name}\u201D. Messages, deletions, and risky taps still ask you first.",
                        actions = listOf(
                            PanelAction("Run it now", primary = true) {
                                execute(Command.RunRoutine(result.routine.name))
                            },
                            PanelAction("Close") { ui.closePanel() }
                        )
                    )
                )
            }
            is RoutineStore.SaveResult.Rejected -> {
                record("Routine", "Save ${command.name}", "failed")
                ui.showPanel(
                    PanelContent(
                        title = "Routine not saved",
                        body = result.reason,
                        actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                    )
                )
            }
        }
    }

    private suspend fun runRoutine(name: String) {
        val routine = RoutineStore.find(context, name)
        if (routine == null) {
            record("Routine", "Run \"$name\"", "not found")
            val saved = RoutineStore.list(context)
            val guesses = phraseGuesses(name, saved.map { it.name })
            val body = when {
                saved.isEmpty() ->
                    "You haven't saved any routines yet. Try \u201Csave routine work for open Slack\u201D."
                guesses.isNotEmpty() -> "I heard \"$name\". No saved routine has that name, so nothing ran."
                else -> "I know these routines: ${saved.joinToString { it.name }}."
            }
            ui.showPanel(
                PanelContent(
                    title = if (guesses.size == 1) "Did you mean ${guesses.first()}?" else "Routine not found",
                    body = body,
                    actions = guesses.take(3).map { guess ->
                        PanelAction("Run $guess", primary = guess == guesses.first()) {
                            ui.closePanel()
                            scope.launch { runRoutine(guess) }
                        }
                    } + PanelAction("Close") { ui.closePanel() }
                )
            )
            return
        }
        val parsed = CommandParser.parse(routine.phrase)
        if (!isReplayable(routine.phrase) || parsed == null) {
            record("Routine", "Run ${routine.name}", "failed")
            ui.showPanel(
                PanelContent(
                    title = "That routine can't run",
                    body = "\u201C${routine.phrase}\u201D isn't a command I can replay anymore. " +
                        "Delete it and save it again.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
            return
        }
        record("Routine", "Ran ${routine.name}", "requested")
        runCommand(parsed, routine.phrase)
    }

    private fun isReplayable(phrase: String): Boolean {
        val parsed = CommandParser.parse(phrase) ?: return false
        return parsed !is Command.FollowUp && parsed !is Command.SaveRoutine &&
            parsed !is Command.RunRoutine && parsed !is Command.DeleteRoutine &&
            parsed != Command.ListRoutines
    }

    private suspend fun deleteRoutine(name: String) {
        val routine = RoutineStore.find(context, name)
        if (routine == null) {
            record("Routine", "Delete \"$name\"", "not found")
            ui.showPanel(
                PanelContent(
                    title = "Routine not found",
                    body = "I don't have a routine called \u201C$name\u201D.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
            return
        }
        record("Routine", "Delete ${routine.name}", "awaiting user")
        val approved = confirm(
            title = "Delete routine \u201C${routine.name}\u201D?",
            body = "This removes the saved phrase \u201C${routine.phrase}\u201D. It can't be undone, " +
                "but you can save the routine again later.",
            approveLabel = "Delete"
        )
        if (!approved) return
        if (RoutineStore.delete(context, routine.name)) {
            record("Routine", "Deleted ${routine.name}", "done")
            ui.showPanel(PanelContent(title = "Routine deleted", autoDismissMs = 1500))
        } else {
            record("Routine", "Delete ${routine.name}", "failed")
            ui.showPanel(
                PanelContent(
                    title = "Couldn't delete that routine",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
        }
    }

    private fun listRoutines() {
        val saved = RoutineStore.list(context)
        if (saved.isEmpty()) {
            record("Routine", "List routines", "nothing found")
            ui.showPanel(
                PanelContent(
                    title = "No routines yet",
                    body = "Say \u201Csave routine work for open Slack and search for design\u201D to create one. " +
                        "Routines stay on this device and replay through the normal safety checks.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
            return
        }
        record("Routine", "Listed ${saved.size} routines", "done")
        ui.showPanel(
            PanelContent(
                title = "Your routines",
                body = saved.joinToString("\n\n") { "\u2022 ${it.name}\n   ${it.phrase}" },
                scrollable = true,
                actions = listOf(
                    PanelAction("Manage", primary = true) {
                        ui.closePanel()
                        MainActivity.open(context, MainActivity.SCREEN_SETTINGS)
                    },
                    PanelAction("Close") { ui.closePanel() }
                )
            )
        )
    }

    private suspend fun flashlight(on: Boolean) {
        val result = TorchController.set(context, on)
        when (result.state) {
            TorchController.State.ON, TorchController.State.OFF -> {
                record("System", if (on) "Flashlight on" else "Flashlight off", "done")
                ui.showPanel(
                    PanelContent(
                        title = if (on) "Flashlight on" else "Flashlight off",
                        autoDismissMs = 1200
                    )
                )
            }
            TorchController.State.REQUESTED -> {
                record("System", "Flashlight ${if (on) "on" else "off"}", "requested")
                ui.showPanel(
                    PanelContent(
                        title = "Flashlight requested",
                        body = result.detail ?: "Android accepted the request. Check the phone.",
                        actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                    )
                )
            }
            TorchController.State.NO_FLASH -> {
                record("System", "Flashlight", "unsupported")
                ui.showPanel(
                    PanelContent(
                        title = "No flashlight",
                        body = result.detail ?: "I couldn't find a camera flash on this device.",
                        actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                    )
                )
            }
            TorchController.State.FAILED -> {
                record("System", "Flashlight", "failed")
                ui.showPanel(
                    PanelContent(
                        title = "Flashlight didn't change",
                        body = result.detail ?: "Android refused to change the flashlight.",
                        actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                    )
                )
            }
        }
    }

    private fun changeVolume(command: Command.Volume) {
        val result = when (command.action) {
            com.flowbubble.app.voice.VolumeAction.UP -> VolumeController.raise(context, command.steps)
            com.flowbubble.app.voice.VolumeAction.DOWN -> VolumeController.lower(context, command.steps)
            com.flowbubble.app.voice.VolumeAction.MUTE -> VolumeController.mute(context)
            com.flowbubble.app.voice.VolumeAction.UNMUTE -> VolumeController.unmute(context)
        }
        if (result == null) {
            record("Volume", "Change media volume", "unsupported")
            ui.showPanel(
                PanelContent(
                    title = "No audio control",
                    body = "Android didn't expose an audio manager on this device.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
            return
        }
        val level = "${result.after} of ${result.max}"
        val denied = result.detail
        if (denied != null) {
            volumeFailed(denied, level)
            return
        }
        when (command.action) {
            com.flowbubble.app.voice.VolumeAction.MUTE -> {
                if (result.after == 0) {
                    record("Volume", "Muted media", "done")
                    ui.showPanel(
                        PanelContent(
                            title = "Media muted",
                            body = "Media volume is 0 of ${result.max}.",
                            actions = listOf(
                                PanelAction("Unmute", primary = true) {
                                    execute(Command.Volume(com.flowbubble.app.voice.VolumeAction.UNMUTE))
                                },
                                PanelAction("Close") { ui.closePanel() }
                            )
                        )
                    )
                } else {
                    volumeFailed("Android didn't silence media.", level)
                }
            }
            com.flowbubble.app.voice.VolumeAction.UNMUTE -> {
                if (result.after > 0) {
                    record("Volume", "Unmuted media", "done")
                    ui.showPanel(
                        PanelContent(
                            title = "Media unmuted",
                            body = "Media volume is $level.",
                            actions = listOf(
                                PanelAction("Mute") {
                                    execute(Command.Volume(com.flowbubble.app.voice.VolumeAction.MUTE))
                                },
                                PanelAction("Done", primary = true) { ui.closePanel() }
                            )
                        )
                    )
                } else {
                    volumeFailed("Media is still silent.", level)
                }
            }
            com.flowbubble.app.voice.VolumeAction.UP -> when {
                result.after > result.before -> {
                    record("Volume", "Media volume up", "done")
                    ui.showPanel(
                        PanelContent(
                            title = "Media volume $level",
                            actions = listOf(
                                PanelAction("Mute") {
                                    execute(Command.Volume(com.flowbubble.app.voice.VolumeAction.MUTE))
                                },
                                PanelAction("Done", primary = true) { ui.closePanel() }
                            )
                        )
                    )
                }
                result.after >= result.max -> {
                    record("Volume", "Media volume at maximum", "done")
                    ui.showPanel(
                        PanelContent(
                            title = "Already at maximum",
                            body = "Media volume is already at the maximum ($level).",
                            actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                        )
                    )
                }
                else -> volumeFailed("Android accepted the request but didn't change the volume.", level)
            }
            com.flowbubble.app.voice.VolumeAction.DOWN -> when {
                result.after < result.before -> {
                    record("Volume", "Media volume down", "done")
                    ui.showPanel(
                        PanelContent(
                            title = "Media volume $level",
                            actions = listOf(
                                PanelAction("Mute") {
                                    execute(Command.Volume(com.flowbubble.app.voice.VolumeAction.MUTE))
                                },
                                PanelAction("Done", primary = true) { ui.closePanel() }
                            )
                        )
                    )
                }
                result.after == 0 -> {
                    record("Volume", "Media volume at zero", "done")
                    ui.showPanel(
                        PanelContent(
                            title = "Media is already muted",
                            body = "Media volume is 0 of ${result.max}.",
                            actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                        )
                    )
                }
                else -> volumeFailed("Android accepted the request but didn't change the volume.", level)
            }
        }
    }

    private fun volumeFailed(body: String, level: String) {
        record("Volume", "Change media volume", "failed")
        ui.showPanel(
            PanelContent(
                title = "Volume didn't change",
                body = "$body Media volume is $level.",
                actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
            )
        )
    }

    private fun batteryStatus() {
        val status = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (status == null || level < 0 || scale <= 0) {
            record("Battery", "Battery status", "unsupported")
            ui.showPanel(
                PanelContent(
                    title = "Battery status unavailable",
                    body = "Android didn't return a battery reading.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
            return
        }
        val percent = (level * 100) / scale
        val state = status.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = state == BatteryManager.BATTERY_STATUS_CHARGING ||
            state == BatteryManager.BATTERY_STATUS_FULL
        val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val source = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> null
        }
        val body = when {
            charging && source != null -> "$percent% and charging on $source power."
            charging -> "$percent% and charging."
            else -> "$percent% and not charging."
        }
        record("Battery", "$percent%${if (charging) ", charging" else ""}", "done")
        ui.showPanel(
            PanelContent(
                title = "Battery",
                body = body,
                actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
            )
        )
    }

    private suspend fun composeEmail(to: String, subject: String) {
        val address = if (emailAddressRegex.matches(to)) {
            to
        } else {
            withContext(Dispatchers.IO) { ContactResolver.findEmail(context, to).firstOrNull()?.email }
        }
        if (address.isNullOrBlank()) {
            record("Email", "Compose to \"$to\"", "not found")
            val needsContacts = !ContactResolver.hasPermission(context)
            ui.showPanel(
                PanelContent(
                    title = "No email address",
                    body = if (needsContacts) {
                        "I need contact access to look up an email address for \u201C$to\u201D. " +
                            "Grant contacts access in Your Bubble setup, or say the full address."
                    } else {
                        "I couldn't find an email address for \u201C$to\u201D. Say the full address, or add it to your contacts."
                    },
                    actions = if (needsContacts) {
                        listOf(
                            PanelAction("Open Your Bubble setup", primary = true) {
                                ui.closePanel()
                                MainActivity.open(context, MainActivity.SCREEN_SETUP)
                            },
                            PanelAction("Cancel") { ui.closePanel() }
                        )
                    } else {
                        listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                    }
                )
            )
            return
        }
        if (openMailto(address, subject)) {
            record("Email", "Draft email", "awaiting user")
            ui.showPanel(
                PanelContent(
                    title = "Email draft opened",
                    body = "Subject: ${subject.take(120)}\n\nReview the draft and press Send yourself. " +
                        "Your Bubble never sends email.",
                    autoDismissMs = 2800
                )
            )
        } else {
            record("Email", "Compose email", "unsupported")
            ui.showPanel(
                PanelContent(
                    title = "No email app",
                    body = "I couldn't find an app that handles email drafts on this device.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
        }
    }

    private fun openMailto(address: String, subject: String?): Boolean {
        val suffix = subject?.let { "?subject=${Uri.encode(it)}" }.orEmpty()
        return start(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$address$suffix")))
    }

    private fun newEvent(title: String) {
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
        if (start(intent)) {
            record("Calendar", "New event draft", "awaiting user")
            ui.showPanel(
                PanelContent(
                    title = "Calendar opened",
                    body = "I added the title \u201C${title.take(120)}\u201D. Set the time and press Save yourself. " +
                        "Your Bubble never saves events.",
                    autoDismissMs = 2800
                )
            )
        } else {
            record("Calendar", "New event", "unsupported")
            ui.showPanel(
                PanelContent(
                    title = "No calendar app",
                    body = "I couldn't find a calendar app that accepts new events on this device.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
        }
    }

    private suspend fun screenActions() {
        val service = FlowAccessibilityService.instance
        if (service == null) {
            accessibilityBlocked("look for actions on this screen")
            return
        }
        val model = buildModel(service, includePixels = true)
        val text = model?.readableText(6000).orEmpty()
        if (text.isBlank()) {
            record("Actions", "Scan this screen", "nothing found")
            ui.showPanel(
                PanelContent(
                    title = "Nothing to act on here",
                    body = model?.captureError ?: "I couldn't read any text on this screen.",
                    actions = screenAccessActions()
                )
            )
            return
        }
        val entities = SmartActions.detect(text)
        if (entities.isEmpty()) {
            record("Actions", "Scan this screen", "nothing found")
            ui.showPanel(
                PanelContent(
                    title = "No quick actions here",
                    body = "I looked for phone numbers, email addresses, links, and street addresses " +
                        "in the visible text, and found none.",
                    actions = screenAccessActions()
                )
            )
            return
        }
        record("Actions", "Found ${entities.size} actions", "done")
        val body = entities.joinToString("\n\n") {
            "${entityKindLabel(it.kind)} \u00B7 ${it.display.take(160)}"
        }
        ui.showPanel(
            PanelContent(
                title = "Actions on this screen",
                body = "$body\n\nI won't do anything until you choose.",
                scrollable = true,
                copyText = entities.joinToString("\n") { it.value },
                actions = entities.take(3).mapIndexed { index, entity ->
                    PanelAction(entityActionLabel(entity), primary = index == 0) { runEntityAction(entity) }
                } + PanelAction("Close") { ui.closePanel() }
            )
        )
    }

    private fun runEntityAction(entity: ActionEntity) {
        ui.closePanel()
        when (entity.kind) {
            ActionEntityKind.PHONE -> {
                val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(entity.value)}"))
                if (start(dial)) {
                    record("Actions", "Opened the dialer", "awaiting user")
                    ui.showPanel(
                        PanelContent(
                            title = "Dialer opened",
                            body = "Press the call button to dial. Your Bubble never places calls itself.",
                            autoDismissMs = 2400
                        )
                    )
                } else {
                    record("Actions", "Open dialer", "unsupported")
                    ui.showPanel(
                        PanelContent(
                            title = "No phone app",
                            body = "I couldn't find a dialer on this device.",
                            actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                        )
                    )
                }
            }
            ActionEntityKind.EMAIL -> {
                if (openMailto(entity.value, null)) {
                    record("Actions", "Opened a mail draft", "awaiting user")
                    ui.showPanel(
                        PanelContent(
                            title = "Email draft opened",
                            body = "Review the draft and press Send yourself.",
                            autoDismissMs = 2400
                        )
                    )
                } else {
                    record("Actions", "Compose email", "unsupported")
                    ui.showPanel(
                        PanelContent(
                            title = "No email app",
                            body = "I couldn't find an app that handles email drafts on this device.",
                            actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                        )
                    )
                }
            }
            ActionEntityKind.URL -> openUrl(entity.value)
            ActionEntityKind.ADDRESS -> {
                val maps = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(entity.value)}"))
                if (start(maps)) {
                    record("Actions", "Opened maps", "awaiting user")
                    ui.showPanel(
                        PanelContent(
                            title = "Opening maps",
                            body = entity.display,
                            autoDismissMs = 2000
                        )
                    )
                } else {
                    record("Actions", "Open maps", "unsupported")
                    ui.showPanel(
                        PanelContent(
                            title = "No maps app",
                            body = "I couldn't find an app that opens map locations.",
                            actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                        )
                    )
                }
            }
        }
    }

    private fun entityKindLabel(kind: ActionEntityKind): String = when (kind) {
        ActionEntityKind.PHONE -> "Phone"
        ActionEntityKind.EMAIL -> "Email"
        ActionEntityKind.URL -> "Link"
        ActionEntityKind.ADDRESS -> "Address"
    }

    private fun entityActionLabel(entity: ActionEntity): String = when (entity.kind) {
        ActionEntityKind.PHONE -> "Call"
        ActionEntityKind.EMAIL -> "Email"
        ActionEntityKind.URL -> "Open link"
        ActionEntityKind.ADDRESS -> "Directions"
    }

    private suspend fun buildModel(
        service: FlowAccessibilityService,
        includePixels: Boolean
    ): ScreenModel? {
        val pixels = includePixels && ScreenCapture.isActive
        if (pixels) ui.hideForScreenRead()
        return try {
            if (pixels) delay(130)
            ScreenModelFactory.build(service, pixels)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            null
        } finally {
            if (pixels) ui.restoreAfterScreenRead()
        }
    }

    private suspend fun confirm(
        title: String,
        body: String,
        approveLabel: String = "Continue"
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingConfirm?.complete(false)
        pendingConfirm = deferred
        val approve: () -> Unit = {
            ui.closePanel()
            deferred.complete(true)
        }
        val deny: () -> Unit = {
            ui.closePanel()
            deferred.complete(false)
        }
        ui.showPanel(
            PanelContent(
                title = title,
                body = body,
                dismissible = false,
                actions = listOf(
                    PanelAction(approveLabel, primary = true, onClick = approve),
                    PanelAction("Cancel", onClick = deny)
                ),
                voicePrompt = voicePrompt(title, body),
                onVoiceApprove = approve,
                onVoiceDeny = deny
            )
        )
        return try {
            deferred.await()
        } finally {
            if (pendingConfirm === deferred) pendingConfirm = null
        }
    }

    private fun voicePrompt(title: String, body: String): String =
        "$title. ${body.take(180)}".replace('\n', ' ')

    private fun summarize(text: String) {
        ui.showPanel(
            PanelContent(
                title = "Summarizing",
                body = "Asking your AI model. This can take a few seconds.",
                dismissible = false
            )
        )
        job = scope.launch {
            val result = LlmClient.summarize(text, LlmClient.config(context, AiRole.Summarize))
            result.onSuccess { summary ->
                LastActionMemory.rememberOutput(summary)
                record("Read", "AI summary of screen", "done")
                ui.showPanel(
                    PanelContent(
                        title = "Screen summary",
                        body = summary,
                        scrollable = true,
                        copyText = summary,
                        actions = listOf(
                            PanelAction("Copy", primary = true) { copyToClipboard(summary) },
                            PanelAction("Close") { ui.closePanel() }
                        )
                    )
                )
            }.onFailure { error ->
                record("Read", "AI summary failed", "failed")
                showScreenText(text)
                Toast.makeText(context, "AI summary failed: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openSettings() {
        val intent = Intent(Settings.ACTION_SETTINGS)
        if (start(intent)) {
            record("System", "Opened Android settings", "done")
            ui.showPanel(PanelContent(title = "Opening settings", autoDismissMs = 1000))
        } else {
            ui.showPanel(
                PanelContent(
                    title = "Couldn't open settings",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
        }
    }

    private fun setDnd(enable: Boolean, minutes: Int?) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (notificationManager == null || !notificationManager.isNotificationPolicyAccessGranted) {
            record("Do Not Disturb", if (enable) "Turn on" else "Turn off", "permission missing")
            ui.showPanel(
                PanelContent(
                    title = "Do Not Disturb access needed",
                    body = "Android requires special access before an app can change Do Not Disturb.",
                    actions = listOf(
                        PanelAction("Grant access", primary = true) {
                            ui.closePanel()
                            Permissions.openDndSettings(context)
                        },
                        PanelAction("Cancel") { ui.closePanel() }
                    )
                )
            )
            return
        }

        val target = if (enable) {
            NotificationManager.INTERRUPTION_FILTER_PRIORITY
        } else {
            NotificationManager.INTERRUPTION_FILTER_ALL
        }
        val applied = runCatching {
            notificationManager.setInterruptionFilter(target)
            notificationManager.currentInterruptionFilter
        }.getOrNull()

        val verified = if (enable) {
            applied != null && applied != NotificationManager.INTERRUPTION_FILTER_ALL
        } else {
            applied == NotificationManager.INTERRUPTION_FILTER_ALL
        }

        if (!verified) {
            record("Do Not Disturb", if (enable) "Turn on" else "Turn off", "failed")
            ui.showPanel(
                PanelContent(
                    title = "Do Not Disturb didn't change",
                    body = "Android did not confirm the new state. Another app or a device policy may be controlling it.",
                    actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                )
            )
            return
        }

        if (enable) {
            if (minutes != null) DndRevert.schedule(context, minutes) else DndRevert.cancel(context)
            record(
                "Do Not Disturb",
                if (minutes != null) "On for ${formatDuration(minutes * 60)}" else "On",
                "done"
            )
            ui.showPanel(
                PanelContent(
                    title = "Do Not Disturb is on",
                    body = if (minutes != null) {
                        "It will turn off automatically in ${formatDuration(minutes * 60)}."
                    } else {
                        "Notifications are silenced."
                    },
                    actions = listOf(
                        PanelAction("Turn off") { setDnd(false, null) },
                        PanelAction("Done", primary = true) { ui.closePanel() }
                    )
                )
            )
            dndRevertJob?.cancel()
            if (minutes != null) {
                dndRevertJob = scope.launch {
                    delay(minutes * 60_000L)
                    setDnd(false, null)
                }
            }
        } else {
            DndRevert.cancel(context)
            dndRevertJob?.cancel()
            record("Do Not Disturb", "Off", "done")
            ui.showPanel(
                PanelContent(
                    title = "Do Not Disturb is off",
                    autoDismissMs = 1600
                )
            )
        }
    }

    private fun accessibilityBlocked(action: String) {
        record("Screen", "Need accessibility for $action", "permission missing")
        ui.showPanel(
            PanelContent(
                title = "Accessibility is off",
                body = "Your Bubble needs its accessibility service enabled to $action. Android controls this permission.",
                actions = listOf(
                    PanelAction("Open accessibility settings", primary = true) {
                        ui.closePanel()
                        Permissions.openAccessibilitySettings(context)
                    },
                    PanelAction("Cancel") { ui.closePanel() }
                )
            )
        )
    }

    private fun start(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolved = intent.resolveActivity(context.packageManager)
        if (resolved == null) {
            android.util.Log.w("YourBubble", "unresolved intent: $intent")
            return false
        }
        return runCatching { context.startActivity(intent) }
            .onFailure { android.util.Log.w("YourBubble", "start failed for $intent", it) }
            .isSuccess
    }

    private fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Your Bubble", text))
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun contactLabel(contact: ContactMatch): String {
        val tail = ChatLinks.tail(contact.number)
        return if (tail.length == 4) "${contact.name} \u00B7 \u2022$tail" else contact.name
    }

    private fun contactLabel(contact: ContactPerson): String {
        val tail = contact.primaryNumber?.let(ChatLinks::tail)
        return if (tail != null && tail.length == 4) "${contact.name} \u00B7 \u2022$tail" else contact.name
    }

    private fun formatDuration(seconds: Int): String = when {
        seconds % 3600 == 0 -> "${seconds / 3600} hr"
        seconds % 60 == 0 -> "${seconds / 60} min"
        else -> "$seconds sec"
    }
}
