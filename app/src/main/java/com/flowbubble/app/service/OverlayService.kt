package com.flowbubble.app.service

import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.TileService
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.flowbubble.app.BuildConfig
import com.flowbubble.app.MainActivity
import com.flowbubble.app.R
import com.flowbubble.app.access.FlowAccessibilityService
import com.flowbubble.app.access.SelectionInfo
import com.flowbubble.app.access.TextInsertResult
import com.flowbubble.app.agent.CapabilityContext
import com.flowbubble.app.agent.CapabilityRouter
import com.flowbubble.app.agent.Gap
import com.flowbubble.app.agent.Route
import com.flowbubble.app.agent.SettingsTarget
import com.flowbubble.app.capture.ScreenCapture
import com.flowbubble.app.commands.CommandExecutor
import com.flowbubble.app.core.DndRevert
import com.flowbubble.app.core.HistoryStore
import com.flowbubble.app.core.LastActionMemory
import com.flowbubble.app.core.Notifications
import com.flowbubble.app.core.Permissions
import com.flowbubble.app.core.Prefs
import com.flowbubble.app.overlay.BubbleView
import com.flowbubble.app.overlay.OverlayPanel
import com.flowbubble.app.overlay.OverlayUi
import com.flowbubble.app.overlay.PanelAction
import com.flowbubble.app.overlay.PanelContent
import com.flowbubble.app.voice.Command
import com.flowbubble.app.voice.CommandParser
import com.flowbubble.app.voice.EngineKind
import com.flowbubble.app.voice.LlmClient
import com.flowbubble.app.voice.SpeechController
import com.flowbubble.app.voice.TextProcessor
import com.flowbubble.app.voice.TranscriptionFailure
import com.flowbubble.app.voice.TranscriptionListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

class OverlayService : Service(), OverlayUi {

    companion object {
        const val ACTION_START = "com.flowbubble.app.action.START"
        const val ACTION_STOP = "com.flowbubble.app.action.STOP"
        const val ACTION_SIMULATE = "com.flowbubble.app.action.SIMULATE"
        const val EXTRA_SIMULATED_TEXT = "simulated_text"

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java).setAction(ACTION_START)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var panel: OverlayPanel
    private lateinit var speech: SpeechController
    private lateinit var executor: CommandExecutor
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private var bubble: BubbleView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var pill: TextView? = null
    private var pillParams: WindowManager.LayoutParams? = null

    private var sessionActive = false
    private var awaitingResult = false
    private var selectionSnapshot: SelectionInfo? = null
    private var lastPartial = ""
    private var engineLabel: String? = null
    private var pendingEngineNote: String? = null

    private var resultWatchdog: Runnable? = null
    private var listenWatchdog: Runnable? = null

    private val density get() = resources.displayMetrics.density
    private fun dp(value: Float) = (value * density).toInt()

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        panel = OverlayPanel(this, windowManager)
        executor = CommandExecutor(this, this)
        speech = SpeechController(this).also { it.listener = speechListener }
        speech.refreshEngineStatus(Prefs.language(this))
        Notifications.ensureChannel(this)

        val notification = Notifications.build(this, "Bubble is ready. Hold the mic to talk.")
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                Notifications.ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                }
            )
        }.isSuccess
        if (!started) {
            Toast.makeText(this, "FlowBubble needs microphone permission.", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "FlowBubble needs the overlay permission.", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        isRunning = true
        addBubble()
        DndRevert.restore(this)
        refreshTile()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (BuildConfig.DEBUG && intent?.action == ACTION_SIMULATE) {
            val text = intent.getStringExtra(EXTRA_SIMULATED_TEXT)
            android.util.Log.i("FlowBubbleDebug", "simulate text=$text session=$sessionActive")
            if (!text.isNullOrBlank() && !sessionActive) {
                selectionSnapshot = FlowAccessibilityService.instance?.captureSelection()
                awaitingResult = true
                handleFinal(text)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.post {
            val view = bubble ?: return@post
            val params = bubbleParams ?: return@post
            val (x, y) = clamp(params.x, params.y)
            params.x = x
            params.y = y
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        isRunning = false
        refreshTile()
        sessionActive = false
        awaitingResult = false
        removeWatchdogs()
        scope.cancel()
        runCatching { speech.cancel() }
        speech.destroy()
        executor.stop()
        panel.close()
        hidePill()
        bubble?.let { view -> runCatching { windowManager.removeView(view) } }
        bubble = null
        bubbleParams = null
        ScreenCapture.stop(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun shutdown() {
        cleanup()
        stopSelf()
    }

    private fun refreshTile() {
        runCatching {
            TileService.requestListeningState(
                this,
                ComponentName(this, FlowTileService::class.java)
            )
        }
    }

    private fun addBubble() {
        val size = dp(72f)
        val (screenWidth, screenHeight) = screenSize()
        val savedX = Prefs.bubbleX(this)
        val savedY = Prefs.bubbleY(this)
        val (x, y) = clamp(
            if (savedX >= 0) savedX else screenWidth - size - dp(10f),
            if (savedY >= 0) savedY else (screenHeight * 0.62f).toInt(),
            size
        )

        val view = BubbleView(this)
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            title = "FlowBubble"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        view.onHoldStart = { beginListening() }
        view.onHoldEnd = { endListening() }
        view.onTap = { showQuickMenu() }
        view.onDrag = { dx, dy ->
            val p = bubbleParams
            if (p != null) {
                val (newX, newY) = clamp((p.x + dx).toInt(), (p.y + dy).toInt(), size)
                p.x = newX
                p.y = newY
                bubble?.let { runCatching { windowManager.updateViewLayout(it, p) } }
            }
        }
        view.onDragEnd = {
            bubbleParams?.let { Prefs.setBubblePosition(this, it.x, it.y) }
        }

        val added = runCatching { windowManager.addView(view, params) }.isSuccess
        if (!added) {
            Toast.makeText(this, "Couldn't show the bubble.", Toast.LENGTH_LONG).show()
            shutdown()
            return
        }
        bubble = view
        bubbleParams = params
    }

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = resources.displayMetrics
            metrics.widthPixels to metrics.heightPixels
        }
    }

    private fun clamp(x: Int, y: Int, size: Int = dp(72f)): Pair<Int, Int> {
        val (width, height) = screenSize()
        return x.coerceIn(0, max(0, width - size)) to y.coerceIn(0, max(0, height - size))
    }

    private fun beginListening() {
        if (sessionActive || awaitingResult) return
        val permissions = Permissions.snapshot(this)
        if (!permissions.microphone) {
            showSetupPanel("Microphone permission needed", "Android requires microphone access for voice commands.")
            return
        }
        if (!permissions.accessibility) {
            showSetupPanel(
                "Accessibility is off",
                "FlowBubble needs its accessibility service to type into fields and act on screen. Voice commands won't reach your apps until it's enabled.",
                primaryLabel = "Open accessibility settings",
                primaryAction = { Permissions.openAccessibilitySettings(this) }
            )
            return
        }

        selectionSnapshot = FlowAccessibilityService.instance?.captureSelection()
        lastPartial = ""
        sessionActive = true
        awaitingResult = true
        panel.close()
        bubble?.setState(BubbleView.State.LISTENING)
        showPill("Listening...")
        updateNotification("Listening...")

        val language = Prefs.language(this)
        val started = speech.start(language)
        if (!started) {
            sessionActive = false
            awaitingResult = false
            selectionSnapshot = null
            resetBubble()
            hidePill()
            return
        }
        removeWatchdogs()
        listenWatchdog = Runnable {
            if (sessionActive) speech.stop()
        }.also { handler.postDelayed(it, 30_000) }
    }

    private fun endListening() {
        if (!sessionActive) return
        sessionActive = false
        lastPartial = lastPartial.trim()
        bubble?.setState(BubbleView.State.PROCESSING)
        speech.stop()
        listenWatchdog?.let { handler.removeCallbacks(it) }
        listenWatchdog = null

        resultWatchdog = Runnable {
            val pending = lastPartial.trim()
            if (pending.isNotEmpty()) {
                handleFinal(pending)
            } else {
                awaitingResult = false
                hidePill()
                resetBubble()
                showMessage("Didn't catch that", "I didn't hear anything. Hold the bubble and try again.")
            }
        }.also { handler.postDelayed(it, 5500) }
        updateNotification("Processing...")
    }

    private fun removeWatchdogs() {
        resultWatchdog?.let { handler.removeCallbacks(it) }
        resultWatchdog = null
        listenWatchdog?.let { handler.removeCallbacks(it) }
        listenWatchdog = null
    }

    private val speechListener = object : TranscriptionListener {
        override fun onReady() {
            if (!sessionActive) return
            showPill(if (lastPartial.isBlank()) pendingEngineNote ?: "Listening..." else lastPartial)
        }

        override fun onEngine(kind: EngineKind, note: String?) {
            engineLabel = kind.label
            pendingEngineNote = note
            if (!sessionActive) return
            showPill(note ?: "Listening...")
            updateNotification("Listening on the ${kind.label}...")
        }

        override fun onPartial(text: String) {
            if (!awaitingResult) return
            lastPartial = text
            pendingEngineNote = null
            showPill(text)
        }

        override fun onFinal(text: String) {
            if (!awaitingResult) return
            lastPartial = text
            handleFinal(text)
        }

        override fun onAmplitude(level: Float) {
            bubble?.setAmplitude(level)
        }

        override fun onError(message: String, failure: TranscriptionFailure) {
            if (!awaitingResult) return
            val fallback = lastPartial.trim()
            if (fallback.isNotEmpty()) {
                handleFinal(fallback)
                return
            }
            sessionActive = false
            awaitingResult = false
            pendingEngineNote = null
            removeWatchdogs()
            selectionSnapshot = null
            bubble?.setState(BubbleView.State.ERROR)
            hidePill()
            updateNotification("Bubble is ready.")
            showMessage("Didn't catch that", message)
            handler.postDelayed({ resetBubble() }, 1600)
        }
    }

    private fun handleFinal(raw: String) {
        if (!awaitingResult) return
        sessionActive = false
        awaitingResult = false
        speech.cancel()
        removeWatchdogs()
        lastPartial = ""
        pendingEngineNote = null

        val text = raw.trim()
        val selection = selectionSnapshot
        selectionSnapshot = null

        if (text.isEmpty()) {
            hidePill()
            resetBubble()
            showMessage("Didn't catch that", "I didn't hear anything. Hold the bubble and try again.")
            return
        }

        val command = CommandParser.parse(text)
        val editsSelection = command is Command.FollowUp && command.kind.editsText
        if (selection != null && selection.hasSelection && (command == null || editsSelection)) {
            bubble?.setState(BubbleView.State.PROCESSING)
            showPill("Editing selection...")
            updateNotification("Working on it...")
            applyEdit(selection, text)
            return
        }

        if (command != null) {
            val route = CapabilityRouter.route(command, capabilityContext())
            if (route is Route.Gated) {
                hidePill()
                resetBubble()
                showGate(route.gaps.first())
                return
            }
            if (route is Route.Unsupported) {
                hidePill()
                resetBubble()
                HistoryStore.add(this, "Request", route.reason, "unsupported")
                showMessage("Can't do that", route.reason)
                return
            }
            hidePill()
            bubble?.setState(BubbleView.State.PROCESSING)
            updateNotification("Carrying out your request...")
            executor.execute(command, text)
            handler.postDelayed({
                if (!sessionActive) {
                    resetBubble()
                }
            }, 1000)
            return
        }

        bubble?.setState(BubbleView.State.PROCESSING)
        showPill("Writing...")
        updateNotification("Working on it...")
        applyDictation(text)
    }

    /** What the router is allowed to assume about this device right now. */
    private fun capabilityContext(): CapabilityContext {
        val permissions = Permissions.snapshot(this)
        return CapabilityContext(
            accessibility = permissions.accessibility,
            accessibilityResponsive = FlowAccessibilityService.instance?.isResponsive() != false,
            screenCapture = ScreenCapture.isActive,
            notificationAccess = permissions.notificationAccess,
            dndPolicy = permissions.dndAccess,
            contacts = permissions.contacts,
            aiConfigured = LlmClient.isConfigured(this)
        )
    }

    /**
     * A request that needs a switch the user hasn't opened. One place says it, so the wording,
     * the history entry, and the deep link always agree.
     */
    private fun showGate(gap: Gap) {
        HistoryStore.add(this, "Request", gap.title, "permission missing")
        panel.show(
            PanelContent(
                title = gap.title,
                body = gap.detail,
                actions = listOf(
                    PanelAction("Open settings", primary = true) {
                        openGateSettings(gap.target)
                        panel.close()
                    },
                    PanelAction("Not now") { panel.close() }
                )
            )
        )
    }

    private fun openGateSettings(target: SettingsTarget) {
        when (target) {
            SettingsTarget.ACCESSIBILITY -> Permissions.openAccessibilitySettings(this)
            SettingsTarget.NOTIFICATION_LISTENER -> Permissions.openNotificationListenerSettings(this)
            SettingsTarget.DO_NOT_DISTURB -> Permissions.openDndSettings(this)
            SettingsTarget.CONTACTS -> Permissions.openAppSettings(this)
            SettingsTarget.AI_SETTINGS -> MainActivity.open(this, MainActivity.SCREEN_SETTINGS)
        }
    }

    private fun applyDictation(raw: String) {
        scope.launch {
            val cleaned = withContext(Dispatchers.Default) { TextProcessor.clean(raw) }
            if (cleaned.isBlank()) {
                hidePill()
                resetBubble()
                showMessage("Nothing to insert", "I couldn't turn that into text.")
                return@launch
            }

            var result = cleaned
            if (Prefs.aiCleanupEnabled(this@OverlayService) && LlmClient.isConfigured(this@OverlayService)) {
                LlmClient.cleanup(cleaned, LlmClient.config(this@OverlayService))
                    .onSuccess { improved ->
                        if (improved.isNotBlank()) result = improved.trim()
                    }
            }

            val service = FlowAccessibilityService.instance
            if (service == null) {
                hidePill()
                resetBubble()
                accessibilityMissing("insert text")
                return@launch
            }

            // Nowhere to write: a misheard "open YouTube" is far more likely than dictation.
            if (service.focusedEditable() == null) {
                hidePill()
                resetBubble()
                if (!executor.offerAppGuess(raw)) {
                    HistoryStore.add(this@OverlayService, "Dictation", "Nowhere to type", "setup needed")
                    panel.show(
                        PanelContent(
                            title = "Nowhere to type",
                            body = "Tap a text field first, then hold the bubble. " +
                                "The text is copied so you can paste it.\n\n\u201C${result.take(240)}\u201D",
                            actions = listOf(
                                PanelAction("Copy text", primary = true) { copy(result) },
                                PanelAction("Close") { panel.close() }
                            )
                        )
                    )
                }
                return@launch
            }

            when (val inserted = service.insertText(result)) {
                is TextInsertResult.Inserted -> {
                    delay(240)
                    var now = service.currentFieldText()
                    var tries = 0
                    while (tries < 4 && now != null && now == inserted.previousText) {
                        delay(180)
                        now = service.currentFieldText()
                        tries++
                    }
                    val changed = now == null || now != inserted.previousText
                    hidePill()
                    resetBubble()
                    if (changed) {
                        LastActionMemory.rememberEdit(
                            inserted.previousText,
                            inserted.newText,
                            service.focusedFieldLabel()
                        )
                        HistoryStore.add(this@OverlayService, "Dictation", "Inserted ${result.take(80)}", "done")
                        showInsertedPanel(result, inserted.previousText, inserted.newText)
                    } else {
                        HistoryStore.add(this@OverlayService, "Dictation", "Insertion unconfirmed", "unverified")
                        panel.show(
                            PanelContent(
                                title = "Couldn't confirm the text",
                                body = "The field didn't change. The text is copied to your clipboard so you can paste it.\n\n\u201C${result.take(240)}\u201D",
                                actions = listOf(
                                    PanelAction("Copy text", primary = true) { copy(result) },
                                    PanelAction("Close") { panel.close() }
                                )
                            )
                        )
                    }
                }
                is TextInsertResult.Copied -> {
                    hidePill()
                    resetBubble()
                    HistoryStore.add(this@OverlayService, "Dictation", "Copied to clipboard", "copied")
                    panel.show(
                        PanelContent(
                            title = "Text copied",
                            body = inserted.message,
                            copyText = result,
                            actions = listOf(PanelAction("Close", primary = true) { panel.close() })
                        )
                    )
                }
                is TextInsertResult.Failed -> {
                    hidePill()
                    resetBubble()
                    HistoryStore.add(this@OverlayService, "Dictation", "Insertion failed", "failed")
                    if (!Permissions.snapshot(this@OverlayService).accessibility) {
                        accessibilityMissing("insert text")
                    } else {
                        panel.show(
                            PanelContent(
                                title = "Couldn't insert text",
                                body = inserted.message,
                                copyText = result,
                                actions = listOf(PanelAction("Close", primary = true) { panel.close() })
                            )
                        )
                    }
                }
            }
        }
    }

    private fun showInsertedPanel(text: String, previous: String, expectedNew: String) {
        panel.show(
            PanelContent(
                title = "Text inserted",
                body = "\u201C${text.take(240)}\u201D",
                dismissible = true,
                autoDismissMs = 9000,
                actions = listOf(
                    PanelAction("Undo", primary = true) { performUndo(previous, expectedNew) }
                )
            )
        )
    }

    private fun applyEdit(selection: SelectionInfo, instruction: String) {
        val selected = selection.selectedText
        if (selected.isBlank()) {
            hidePill()
            resetBubble()
            showMessage("No selection", "Select some text first, then hold the bubble and say how to change it.")
            return
        }

        val local = TextProcessor.localEdit(instruction, selected)
        if (local != null) {
            commitEdit(selection, local)
            return
        }

        if (!LlmClient.isConfigured(this)) {
            hidePill()
            resetBubble()
            panel.show(
                PanelContent(
                    title = "AI model needed",
                    body = "That edit needs an AI model. Add an API key in FlowBubble settings. Bullet lists, grammar, professional, and casual rewrites work without one.",
                    actions = listOf(
                        PanelAction("Open settings", primary = true) {
                            panel.close()
                            MainActivity.open(this, MainActivity.SCREEN_SETTINGS)
                        },
                        PanelAction("Close") { panel.close() }
                    )
                )
            )
            return
        }

        scope.launch {
            val result = LlmClient.edit(selected, instruction, LlmClient.config(this@OverlayService))
            result.onSuccess { edited ->
                if (edited.isBlank()) {
                    hidePill()
                    resetBubble()
                    panel.show(
                        PanelContent(
                            title = "No change returned",
                            body = "The AI model returned an empty edit.",
                            actions = listOf(PanelAction("Close", primary = true) { panel.close() })
                        )
                    )
                } else {
                    commitEdit(selection, edited.trim())
                }
            }.onFailure { error ->
                hidePill()
                resetBubble()
                HistoryStore.add(this@OverlayService, "Edit", instruction.take(60), "failed")
                panel.show(
                    PanelContent(
                        title = "Edit failed",
                        body = "The AI request failed: ${error.message ?: "unknown error"}. Your text was not changed.",
                        actions = listOf(PanelAction("Close", primary = true) { panel.close() })
                    )
                )
            }
        }
    }

    private fun commitEdit(selection: SelectionInfo, replacement: String) {
        val service = FlowAccessibilityService.instance
        if (service == null) {
            hidePill()
            resetBubble()
            accessibilityMissing("edit text")
            return
        }
        when (val result = service.replaceRange(selection.fullText, selection.start, selection.end, replacement)) {
            is TextInsertResult.Inserted -> {
                LastActionMemory.rememberEdit(result.previousText, result.newText, service.focusedFieldLabel())
                hidePill()
                resetBubble()
                HistoryStore.add(this@OverlayService, "Edit", replacement.take(80), "done")
                panel.show(
                    PanelContent(
                        title = "Selection replaced",
                        body = "\u201C${replacement.take(240)}\u201D",
                        autoDismissMs = 9000,
                        actions = listOf(
                            PanelAction("Undo", primary = true) {
                                performUndo(result.previousText, result.newText)
                            }
                        )
                    )
                )
            }
            is TextInsertResult.Copied -> {
                hidePill()
                resetBubble()
                HistoryStore.add(this@OverlayService, "Edit", "Result copied", "copied")
                panel.show(
                    PanelContent(
                        title = "Edit copied",
                        body = result.message,
                        copyText = replacement,
                        actions = listOf(PanelAction("Close", primary = true) { panel.close() })
                    )
                )
            }
            is TextInsertResult.Failed -> {
                hidePill()
                resetBubble()
                HistoryStore.add(this@OverlayService, "Edit", "Edit failed", "failed")
                panel.show(
                    PanelContent(
                        title = "Couldn't edit",
                        body = result.message,
                        actions = listOf(PanelAction("Close", primary = true) { panel.close() })
                    )
                )
            }
        }
    }

    private fun performUndo(previous: String, expectedNew: String) {
        val service = FlowAccessibilityService.instance
        val undone = service?.undoInsert(previous, expectedNew) == true
        panel.close()
        if (undone) {
            HistoryStore.add(this, "Undo", "Reverted the last insertion", "done")
            panel.show(PanelContent(title = "Undone", autoDismissMs = 1400))
        } else {
            panel.show(
                PanelContent(
                    title = "Couldn't undo",
                    body = "The text field changed or lost focus, so FlowBubble won't overwrite it.",
                    actions = listOf(PanelAction("Close", primary = true) { panel.close() })
                )
            )
        }
    }

    private fun showQuickMenu() {
        if (sessionActive || awaitingResult) return
        val permissions = Permissions.snapshot(this)
        panel.show(
            PanelContent(
                title = "FlowBubble",
                body = if (permissions.coreReady) {
                    "Hold the bubble and speak. Tap to see this menu."
                } else {
                    "Some permissions are still missing. Finish setup to unlock voice control."
                },
                actions = buildList {
                    add(
                        PanelAction(
                            if (permissions.coreReady) "Open FlowBubble" else "Finish setup",
                            primary = true
                        ) {
                            panel.close()
                            MainActivity.open(
                                this@OverlayService,
                                if (permissions.coreReady) MainActivity.SCREEN_HOME else MainActivity.SCREEN_SETUP
                            )
                        }
                    )
                    add(
                        PanelAction("Turn off bubble") {
                            panel.close()
                            shutdown()
                        }
                    )
                    add(PanelAction("Close") { panel.close() })
                }
            )
        )
    }

    private fun showSetupPanel(
        title: String,
        body: String,
        primaryLabel: String = "Open setup",
        primaryAction: (() -> Unit)? = null
    ) {
        panel.show(
            PanelContent(
                title = title,
                body = body,
                actions = listOf(
                    PanelAction(primaryLabel, primary = true) {
                        panel.close()
                        primaryAction?.invoke()
                            ?: MainActivity.open(this, MainActivity.SCREEN_SETUP)
                    },
                    PanelAction("Close") { panel.close() }
                )
            )
        )
    }

    private fun accessibilityMissing(action: String) {
        showSetupPanel(
            "Accessibility is off",
            "FlowBubble needs its accessibility service to $action. Android controls this permission.",
            primaryLabel = "Open accessibility settings",
            primaryAction = { Permissions.openAccessibilitySettings(this) }
        )
    }

    private fun showMessage(title: String, body: String) {
        panel.show(
            PanelContent(
                title = title,
                body = body,
                actions = listOf(PanelAction("Close", primary = true) { panel.close() })
            )
        )
    }

    private fun resetBubble() {
        bubble?.setState(BubbleView.State.IDLE)
        bubble?.setAmplitude(0f)
        updateNotification("Bubble is ready. Hold the mic to talk.")
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(Notifications.ID, Notifications.build(this, text)) }
    }

    private fun showPill(text: String) {
        val view = ensurePill() ?: return
        view.text = text
        view.contentDescription = text
        val params = pillParams ?: return
        if (view.parent == null) {
            runCatching { windowManager.addView(view, params) }
        } else {
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    private fun ensurePill(): TextView? {
        pill?.let { return it }
        val (screenWidth) = screenSize()
        val view = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14.5f
            maxLines = 3
            maxWidth = screenWidth - dp(48f)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(22f).toFloat()
                setColor(Color.parseColor("#F0121922"))
                setStroke(dp(1f), Color.parseColor("#665EEAD4"))
            }
            contentDescription = "Listening"
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(120f)
            title = "FlowBubble transcript"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        pill = view
        pillParams = params
        return view
    }

    private fun hidePill() {
        pill?.let { view ->
            if (view.parent != null) runCatching { windowManager.removeView(view) }
        }
        pill = null
        pillParams = null
    }

    private fun copy(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("FlowBubble", text))
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    override fun showPanel(content: PanelContent) {
        panel.show(content)
    }

    override fun closePanel() {
        panel.close()
    }

    override fun hideForScreenRead() {
        panel.hideForCapture()
        hidePill()
        bubble?.visibility = View.INVISIBLE
    }

    override fun restoreAfterScreenRead() {
        bubble?.visibility = View.VISIBLE
        panel.restoreAfterCapture()
    }
}
