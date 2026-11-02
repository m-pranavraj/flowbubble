package com.flowbubble.app.service

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
import com.flowbubble.app.capture.ScreenSession
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
import com.flowbubble.app.overlay.companion.CompanionMood
import com.flowbubble.app.voice.AiRole
import com.flowbubble.app.voice.Command
import com.flowbubble.app.voice.CommandParser
import com.flowbubble.app.voice.EngineKind
import com.flowbubble.app.voice.HandsFree
import com.flowbubble.app.voice.LlmClient
import com.flowbubble.app.voice.Speaker
import com.flowbubble.app.voice.SpeechController
import com.flowbubble.app.voice.TextProcessor
import com.flowbubble.app.voice.TranscriptionFailure
import com.flowbubble.app.voice.TranscriptionListener
import com.flowbubble.app.voice.VoiceAnswer
import com.flowbubble.app.voice.VoiceAnswerParser
import com.flowbubble.app.voice.WakeResult
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

        private const val VOICE_RETRY_MS = 6_000L
        private const val LISTEN_WINDOW_MS = 20_000L
        private const val CONFIRM_WINDOW_MS = 12_000L

        /**
         * The overlay window owns the character's size: BubbleView measures at most as large as the
         * window it is given, so changing this is the only way to change how big the companion is.
         * Both the window and the drag clamp read it from here, because a mismatch lets the character
         * be sized at 72 dp while the clamp still reserves 96.
         */
        private const val BUBBLE_SIZE_DP = 96f
        private const val WAKE_RETRY_BASE_MS = 600L

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

    private enum class VoiceMode { OFF, WAKE, COMMAND, CONFIRM }

    private var handsFree = false
    private var voiceMode = VoiceMode.OFF
    private var voiceConfirm: PanelContent? = null
    private var voiceConfirmAttempts = 0
    private var speaker: Speaker? = null
    private var screenOff = false
    private var silentWakeStreak = 0
    private var voiceStartFailures = 0

    private val waitWake = Runnable { startWakeListening() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOff = true
                    handler.removeCallbacks(waitWake)
                    if (voiceMode == VoiceMode.WAKE || voiceMode == VoiceMode.COMMAND) resetVoiceTurn()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOff = false
                    if (handsFree) scheduleWakeListen(900)
                }
            }
        }
    }

    private val density get() = resources.displayMetrics.density
    private fun dp(value: Float) = (value * density).toInt()

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        panel = OverlayPanel(this, windowManager).also { it.onClosed = ::onPanelClosed }
        executor = CommandExecutor(this, this)
        speech = SpeechController(this).also { it.listener = speechListener }
        speech.refreshEngineStatus(Prefs.language(this))
        speaker = Speaker(this)
        screenOff = getSystemService(PowerManager::class.java)?.isInteractive == false
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
            Toast.makeText(this, "Your Bubble needs microphone permission.", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Your Bubble needs the overlay permission.", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        isRunning = true
        addBubble()
        DndRevert.restore(this)
        refreshTile()
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (Prefs.handsFreeEnabled(this)) setHandsFree(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (BuildConfig.DEBUG && intent?.action == ACTION_SIMULATE) {
            val text = intent.getStringExtra(EXTRA_SIMULATED_TEXT)
            android.util.Log.i("FlowBubbleDebug", "simulate text=$text session=$sessionActive handsFree=$handsFree")
            if (!text.isNullOrBlank()) {
                if (voiceMode == VoiceMode.CONFIRM) {
                    // Test hook: speak the answer a microphone would deliver to a pending prompt.
                    handleConfirmFinal(text)
                    return START_NOT_STICKY
                }
                if (voiceMode != VoiceMode.OFF) resetVoiceTurn()
                if (!sessionActive) {
                    selectionSnapshot = FlowAccessibilityService.instance?.captureSelection()
                    awaitingResult = true
                    // A simulated utterance still obeys the wake rule while hands-free is on,
                    // so injection exercises the same path as the microphone.
                    val spoken = if (handsFree) {
                        when (val wake = HandsFree.parse(text)) {
                            is WakeResult.Command -> wake.text
                            else -> ""
                        }
                    } else {
                        text
                    }
                    if (spoken.isNotBlank()) {
                        handleFinal(spoken)
                    } else {
                        awaitingResult = false
                    }
                }
            }
        }
        if (intent == null || intent.action == ACTION_START) {
            val wanted = Prefs.handsFreeEnabled(this)
            if (wanted != handsFree) setHandsFree(wanted)
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
        handsFree = false
        voiceMode = VoiceMode.OFF
        voiceConfirm = null
        handler.removeCallbacks(waitWake)
        runCatching { unregisterReceiver(screenReceiver) }
        speaker?.shutdown()
        speaker = null
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
        val size = dp(BUBBLE_SIZE_DP)
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
            title = "Your Bubble"
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

    private fun clamp(x: Int, y: Int, size: Int = dp(BUBBLE_SIZE_DP)): Pair<Int, Int> {
        val (width, height) = screenSize()
        return x.coerceIn(0, max(0, width - size)) to y.coerceIn(0, max(0, height - size))
    }

    private fun beginListening() {
        if (voiceMode != VoiceMode.OFF) resetVoiceTurn()
        if (sessionActive || awaitingResult) return
        val permissions = Permissions.snapshot(this)
        if (!permissions.microphone) {
            showSetupPanel("Microphone permission needed", "Android requires microphone access for voice commands.")
            return
        }
        if (!permissions.accessibility) {
            showSetupPanel(
                "Accessibility is off",
                "Your Bubble needs its accessibility service to type into fields and act on screen. Voice commands won't reach your apps until it's enabled.",
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
                if (handsFree) scheduleWakeListen(2200)
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

    // ---- Hands-free listening loop -------------------------------------------------------
    // One recognizer turn at a time. Every turn ends with a single terminal callback
    // (onFinal or onError) that routes by voiceMode and decides what to listen for next.

    private fun setHandsFree(enabled: Boolean) {
        if (enabled == handsFree) {
            if (enabled) scheduleWakeListen(400)
            return
        }
        handsFree = enabled
        if (!enabled) {
            handler.removeCallbacks(waitWake)
            voiceConfirm = null
            voiceConfirmAttempts = 0
            resetVoiceTurn()
            updateNotification("Bubble is ready. Hold the mic to talk.")
            Toast.makeText(this, "Hands-free off", Toast.LENGTH_SHORT).show()
            return
        }
        val permissions = Permissions.snapshot(this)
        if (!permissions.microphone || !permissions.accessibility) {
            handsFree = false
            Prefs.setHandsFreeEnabled(this, false)
            showPanel(
                PanelContent(
                    title = "Finish setup for hands-free",
                    body = "Hands-free needs the microphone and accessibility so it can hear \u201CHello Bubble\u201D and act in apps.",
                    actions = listOf(
                        PanelAction("Open setup", primary = true) {
                            panel.close()
                            MainActivity.open(this, MainActivity.SCREEN_SETUP)
                        },
                        PanelAction("Close") { panel.close() }
                    )
                )
            )
            return
        }
        resetVoiceTurn()
        updateNotification("Hands-free is on. Say \u201CHello Bubble\u201D.")
        speakThen("Hands-free is on.") { scheduleWakeListen(600) }
    }

    private fun scheduleWakeListen(delayMs: Long = 650) {
        handler.removeCallbacks(waitWake)
        if (!handsFree) return
        handler.postDelayed(waitWake, delayMs)
    }

    private fun startWakeListening() {
        if (!handsFree || screenOff) return
        if (voiceMode != VoiceMode.OFF || sessionActive || awaitingResult || panel.isShowing) return
        val permissions = Permissions.snapshot(this)
        if (!permissions.microphone || !permissions.accessibility) return
        beginVoiceSession(VoiceMode.WAKE)
    }

    private fun startCommandListening() {
        if (!handsFree || screenOff || panel.isShowing) return
        if (voiceMode != VoiceMode.OFF || sessionActive || awaitingResult) return
        beginVoiceSession(VoiceMode.COMMAND)
    }

    private fun startConfirmListening() {
        if (!handsFree || screenOff) return
        val content = voiceConfirm ?: return
        if (!panel.isShowing || content.onVoiceApprove == null) return
        if (voiceMode != VoiceMode.OFF || sessionActive || awaitingResult) return
        beginVoiceSession(VoiceMode.CONFIRM)
    }

    private fun beginVoiceSession(mode: VoiceMode) {
        selectionSnapshot = null
        lastPartial = ""
        pendingEngineNote = null
        sessionActive = true
        awaitingResult = true
        voiceMode = mode
        bubble?.setState(BubbleView.State.LISTENING)
        if (mode == VoiceMode.WAKE) {
            updateNotification("Hands-free: say \u201CHello Bubble\u201D.")
        } else {
            showPill("Listening...")
            updateNotification("Listening...")
        }
        if (!speech.start(Prefs.language(this))) {
            resetVoiceTurn()
            voiceStartFailures++
            if (voiceStartFailures >= 4) {
                voiceStartFailures = 0
                Prefs.setHandsFreeEnabled(this, false)
                setHandsFree(false)
                showPanel(
                    PanelContent(
                        title = "Voice engine unavailable",
                        body = "Hands-free stopped because the speech recognizer would not start. Check the engine and language in settings, then turn hands-free back on.",
                        actions = listOf(
                            PanelAction("Open settings", primary = true) {
                                panel.close()
                                MainActivity.open(this, MainActivity.SCREEN_SETTINGS)
                            },
                            PanelAction("Close") { panel.close() }
                        )
                    )
                )
            } else if (handsFree) {
                scheduleWakeListen(VOICE_RETRY_MS)
            }
            return
        }
        voiceStartFailures = 0
        removeWatchdogs()
        val window = if (mode == VoiceMode.CONFIRM) CONFIRM_WINDOW_MS else LISTEN_WINDOW_MS
        listenWatchdog = Runnable {
            if (voiceMode != mode) return@Runnable
            speech.stop()
            handler.postDelayed({
                if (voiceMode == mode && awaitingResult && lastPartial.isBlank()) {
                    finishVoiceTurnWithoutSpeech()
                }
            }, 1200)
        }.also { handler.postDelayed(it, window) }
    }

    private fun resetVoiceTurn() {
        sessionActive = false
        awaitingResult = false
        voiceMode = VoiceMode.OFF
        selectionSnapshot = null
        pendingEngineNote = null
        lastPartial = ""
        removeWatchdogs()
        runCatching { speech.cancel() }
        hidePill()
        resetBubble()
    }

    private fun finishVoiceTurnWithoutSpeech() {
        val mode = voiceMode
        if (mode == VoiceMode.OFF) return
        resetVoiceTurn()
        when (mode) {
            VoiceMode.WAKE -> {
                silentWakeStreak++
                scheduleWakeListen(wakeRetryDelay())
            }
            VoiceMode.COMMAND -> speakThen("I didn't hear a command.") { scheduleWakeListen(700) }
            VoiceMode.CONFIRM -> onConfirmUnheard()
            VoiceMode.OFF -> Unit
        }
    }

    private fun wakeRetryDelay(): Long =
        (WAKE_RETRY_BASE_MS + silentWakeStreak * 400L).coerceAtMost(4_000L)

    private fun handleWakeFinal(raw: String) {
        if (voiceMode != VoiceMode.WAKE) return
        silentWakeStreak = 0
        when (val result = HandsFree.parse(raw)) {
            WakeResult.None -> {
                resetVoiceTurn()
                scheduleWakeListen(wakeRetryDelay())
            }
            WakeResult.WakeOnly -> {
                resetVoiceTurn()
                speakThen("Yes?") { startCommandListening() }
            }
            is WakeResult.Command -> {
                voiceMode = VoiceMode.OFF
                dispatchFinal(result.text)
                if (handsFree) scheduleWakeListen(1200)
            }
        }
    }

    private fun handleConfirmFinal(raw: String) {
        val content = voiceConfirm
        val spoken = when (val wake = HandsFree.parse(raw)) {
            is WakeResult.Command -> wake.text
            else -> raw
        }
        val answer = VoiceAnswerParser.parse(spoken)
        resetVoiceTurn()
        if (content == null) {
            scheduleWakeListen(650)
            return
        }
        when (answer) {
            VoiceAnswer.YES -> {
                content.onVoiceApprove?.invoke()
                scheduleWakeListen(1500)
            }
            VoiceAnswer.NO -> {
                content.onVoiceDeny?.invoke()
                speakThen("Okay, cancelled.") { scheduleWakeListen(1500) }
            }
            null -> {
                if (voiceConfirmAttempts < 2 && panel.isShowing) {
                    voiceConfirmAttempts++
                    speakThen("Please say yes or no.") { startConfirmListening() }
                } else {
                    speakThen("I didn't catch an answer.") { startConfirmListening() }
                }
            }
        }
    }

    private fun onConfirmUnheard() {
        if (voiceConfirm == null || !panel.isShowing) {
            scheduleWakeListen(650)
            return
        }
        voiceConfirmAttempts++
        if (voiceConfirmAttempts > 3) {
            speakThen("Say yes or no when you're ready.") { }
            return
        }
        speakThen("Say yes or no.") { startConfirmListening() }
    }

    private fun speakThen(text: String, then: () -> Unit) {
        val engine = speaker
        if (engine == null) {
            then()
            return
        }
        var resumed = false
        val once = {
            if (!resumed) {
                resumed = true
                bubble?.setSpeaking(false)
                then()
            }
        }
        // The character's mouth moves while the answer is being read out, and stops with it. Every
        // spoken reply goes through here, so this is the only place that has to know about it.
        bubble?.setSpeaking(true)
        engine.say(text, Prefs.language(this)) { handler.post(once) }
        // An engine that never reports completion must not freeze the loop.
        handler.postDelayed(once, 8000)
    }

    private fun onPanelClosed() {
        voiceConfirm = null
        voiceConfirmAttempts = 0
        if (voiceMode != VoiceMode.OFF) resetVoiceTurn()
        if (handsFree) scheduleWakeListen(650)
    }

    private val speechListener = object : TranscriptionListener {
        override fun onReady() {
            if (!sessionActive) return
            if (voiceMode == VoiceMode.WAKE) return
            showPill(if (lastPartial.isBlank()) pendingEngineNote ?: "Listening..." else lastPartial)
        }

        override fun onEngine(kind: EngineKind, note: String?) {
            engineLabel = kind.label
            pendingEngineNote = note
            if (!sessionActive) return
            if (voiceMode == VoiceMode.WAKE) {
                if (!screenOff) updateNotification("Hands-free: say \u201CHello Bubble\u201D.")
                return
            }
            showPill(note ?: "Listening...")
            updateNotification("Listening on the ${kind.label}...")
        }

        override fun onPartial(text: String) {
            if (!awaitingResult) return
            if (voiceMode == VoiceMode.WAKE) return
            lastPartial = text
            pendingEngineNote = null
            showPill(text)
        }

        override fun onFinal(text: String) {
            if (!awaitingResult) return
            lastPartial = text
            when (voiceMode) {
                VoiceMode.WAKE -> handleWakeFinal(text)
                VoiceMode.CONFIRM -> handleConfirmFinal(text)
                VoiceMode.COMMAND, VoiceMode.OFF -> handleFinal(text)
            }
        }

        override fun onAmplitude(level: Float) {
            bubble?.setAmplitude(level)
        }

        override fun onError(message: String, failure: TranscriptionFailure) {
            if (!awaitingResult) return
            if (voiceMode != VoiceMode.OFF) {
                handleVoiceError(message, failure)
                return
            }
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
            if (handsFree) scheduleWakeListen(2200)
        }
    }

    private fun handleVoiceError(message: String, failure: TranscriptionFailure) {
        val partial = lastPartial.trim()
        if (partial.isNotEmpty()) {
            when (voiceMode) {
                VoiceMode.WAKE -> handleWakeFinal(partial)
                VoiceMode.CONFIRM -> handleConfirmFinal(partial)
                else -> handleFinal(partial)
            }
            return
        }
        val mode = voiceMode
        resetVoiceTurn()
        if (!handsFree) return
        if (failure == TranscriptionFailure.PERMISSION) {
            Prefs.setHandsFreeEnabled(this, false)
            setHandsFree(false)
            showMessage("Microphone unavailable", "Hands-free stopped because Android would not record audio. $message")
            return
        }
        when (mode) {
            VoiceMode.WAKE -> {
                silentWakeStreak++
                scheduleWakeListen(wakeRetryDelay())
            }
            VoiceMode.COMMAND -> speakThen("I didn't hear a command.") { scheduleWakeListen(700) }
            VoiceMode.CONFIRM -> onConfirmUnheard()
            VoiceMode.OFF -> Unit
        }
    }

    private fun handleFinal(raw: String) {
        if (!awaitingResult) return
        voiceMode = VoiceMode.OFF
        dispatchFinal(raw)
        if (handsFree) scheduleWakeListen(1200)
    }

    private fun dispatchFinal(raw: String) {
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
        showPanel(
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
            if (Prefs.aiCleanupEnabled(this@OverlayService) &&
                LlmClient.isConfigured(this@OverlayService, AiRole.Dictation)) {
                LlmClient.cleanup(cleaned, LlmClient.config(this@OverlayService, AiRole.Dictation))
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
                    showPanel(
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
                        showPanel(
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
                    showPanel(
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
                        showPanel(
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
        showPanel(
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

        if (!LlmClient.isConfigured(this, AiRole.Edit)) {
            hidePill()
            resetBubble()
            showPanel(
                PanelContent(
                    title = "AI model needed",
                    body = "That edit needs an AI model. Add an API key in Your Bubble settings. Bullet lists, grammar, professional, and casual rewrites work without one.",
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
            val result = LlmClient.edit(selected, instruction, LlmClient.config(this@OverlayService, AiRole.Edit))
            result.onSuccess { edited ->
                if (edited.isBlank()) {
                    hidePill()
                    resetBubble()
                    showPanel(
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
                showPanel(
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
                showPanel(
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
                showPanel(
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
                showPanel(
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
            showPanel(PanelContent(title = "Undone", autoDismissMs = 1400))
        } else {
            showPanel(
                PanelContent(
                    title = "Couldn't undo",
                    body = "The text field changed or lost focus, so Your Bubble won't overwrite it.",
                    actions = listOf(PanelAction("Close", primary = true) { panel.close() })
                )
            )
        }
    }

    private fun showQuickMenu() {
        if (voiceMode != VoiceMode.OFF) resetVoiceTurn()
        if (sessionActive || awaitingResult) return
        val permissions = Permissions.snapshot(this)
        showPanel(
            PanelContent(
                title = "Your Bubble",
                body = when {
                    !permissions.coreReady ->
                        "Some permissions are still missing. Finish setup to unlock voice control."
                    Prefs.handsFreeEnabled(this) ->
                        "Hands-free is on. Just say \u201CHello Bubble\u201D and then your command. Hold the bubble to talk without saying it."
                    else ->
                        "Hold the bubble and speak, or turn on hands-free and just say \u201CHello Bubble\u201D."
                },
                actions = buildList {
                    add(
                        PanelAction(
                            if (permissions.coreReady) "Open Your Bubble" else "Finish setup",
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
                        PanelAction(
                            if (Prefs.handsFreeEnabled(this@OverlayService)) "Turn off hands-free"
                            else "Turn on hands-free"
                        ) {
                            val enable = !Prefs.handsFreeEnabled(this@OverlayService)
                            Prefs.setHandsFreeEnabled(this@OverlayService, enable)
                            panel.close()
                            setHandsFree(enable)
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
        showPanel(
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
            "Your Bubble needs its accessibility service to $action. Android controls this permission.",
            primaryLabel = "Open accessibility settings",
            primaryAction = { Permissions.openAccessibilitySettings(this) }
        )
    }

    private fun showMessage(title: String, body: String) {
        showPanel(
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
        // The only mood with a real signal to react to: while the screen is being watched the
        // companion looks curious, and it relaxes as soon as watching stops.
        bubble?.setMood(if (ScreenSession.isWatching()) CompanionMood.Curious else CompanionMood.Neutral)
        updateNotification(
            if (handsFree) "Hands-free is on. Say \u201CHello Bubble\u201D."
            else "Bubble is ready. Hold the mic to talk."
        )
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
            title = "Your Bubble transcript"
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
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Your Bubble", text))
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    override fun showPanel(content: PanelContent) {
        panel.show(content)
        val prompt = content.voicePrompt
        if (prompt == null) {
            voiceConfirm = null
            if (voiceMode != VoiceMode.OFF) resetVoiceTurn()
            return
        }
        voiceConfirm = content
        voiceConfirmAttempts = 0
        if (!handsFree || screenOff) return
        resetVoiceTurn()
        speakThen(prompt) { startConfirmListening() }
    }

    override fun announce(text: String) {
        if (!handsFree || screenOff) return
        val phrase = text.trim().take(160)
        if (phrase.isEmpty()) return
        // The spoken confirmation prompt already says what this panel is for.
        if (voiceMode == VoiceMode.CONFIRM) return
        val interrupted = voiceMode != VoiceMode.OFF
        if (interrupted) resetVoiceTurn()
        speakThen(phrase) {
            if (handsFree && voiceMode == VoiceMode.OFF && !panel.isShowing) scheduleWakeListen(500)
        }
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
