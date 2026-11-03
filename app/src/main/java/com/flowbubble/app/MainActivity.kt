package com.flowbubble.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.flowbubble.app.capture.ScreenCapture
import com.flowbubble.app.core.HistoryEntry
import com.flowbubble.app.core.HistoryStore
import com.flowbubble.app.core.PermissionState
import com.flowbubble.app.core.Permissions
import com.flowbubble.app.service.AutomationReceiver
import com.flowbubble.app.service.OverlayService
import com.flowbubble.app.ui.AutomationsScreen
import com.flowbubble.app.ui.FlowBubbleApp
import com.flowbubble.app.ui.theme.FlowBubbleTheme

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SCREEN = "com.flowbubble.app.extra.SCREEN"
        const val SCREEN_HOME = "home"
        const val SCREEN_SETUP = "setup"
        const val SCREEN_SETTINGS = "settings"
        const val SCREEN_HISTORY = "history"
        const val SCREEN_AUTOMATIONS = "automations"

        fun open(context: Context, screen: String = SCREEN_HOME) {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_SCREEN, screen)
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val screenState = mutableStateOf(SCREEN_HOME)
    private val bubbleState = mutableStateOf(false)
    private val historyState = mutableStateOf<List<HistoryEntry>>(emptyList())
    private val captureState = mutableStateOf(ScreenCapture.isActive)
    private val captureErrorState = mutableStateOf(ScreenCapture.lastError)
    private val captureListener: () -> Unit = {
        captureState.value = ScreenCapture.isActive
        captureErrorState.value = ScreenCapture.lastError
    }
    private lateinit var permissionsState: MutableState<PermissionState>
    private val liveTick = object : Runnable {
        override fun run() {
            refreshLiveState()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        permissionsState = mutableStateOf(Permissions.snapshot(this))
        screenState.value = intent.getStringExtra(EXTRA_SCREEN) ?: SCREEN_HOME
        historyState.value = HistoryStore.load(this)

        setContent {
            FlowBubbleTheme {
                val navigate: (String) -> Unit = {
                    screenState.value = it
                    refreshState()
                }
                // Automations draws its own header and tab slab, because the shared one lives in another file.
                when (screenState.value) {
                    SCREEN_AUTOMATIONS -> AutomationsScreen(
                        bubbleRunning = bubbleState.value,
                        onNavigate = navigate
                    )
                    else -> FlowBubbleApp(
                        screen = screenState.value,
                        onNavigate = navigate,
                        permissions = permissionsState.value,
                        bubbleRunning = bubbleState.value,
                        captureActive = captureState.value,
                        captureError = captureErrorState.value,
                        history = historyState.value,
                        onRefresh = { refreshState() },
                        onToggleBubble = { toggleBubble() },
                        onEnableCapture = { ScreenCapture.requestConsent(this) },
                        onStopCapture = { ScreenCapture.stop(this) },
                        onClearHistory = {
                            HistoryStore.clear(this)
                            historyState.value = emptyList()
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_SCREEN)?.let { screenState.value = it }
    }

    override fun onStart() {
        super.onStart()
        ScreenCapture.addListener(captureListener)
        captureListener()
    }

    override fun onStop() {
        ScreenCapture.removeListener(captureListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
        // Coming back to the app is the moment the bubble may have been switched on or off elsewhere.
        AutomationReceiver.reschedule(this)
        handler.removeCallbacks(liveTick)
        handler.postDelayed(liveTick, 1000)
    }

    override fun onPause() {
        handler.removeCallbacks(liveTick)
        super.onPause()
    }

    private fun refreshState() {
        refreshLiveState()
        historyState.value = HistoryStore.load(this)
    }

    private fun refreshLiveState() {
        permissionsState.value = Permissions.snapshot(this)
        bubbleState.value = OverlayService.isRunning
        captureState.value = ScreenCapture.isActive
        captureErrorState.value = ScreenCapture.lastError
    }

    private fun toggleBubble() {
        if (OverlayService.isRunning || bubbleState.value) {
            OverlayService.stop(this)
            bubbleState.value = false
            AutomationReceiver.reschedule(this)
            return
        }
        if (!Permissions.snapshot(this).coreReady) {
            screenState.value = SCREEN_SETUP
            return
        }
        OverlayService.start(this)
        bubbleState.value = true
        handler.postDelayed({
            bubbleState.value = OverlayService.isRunning
            AutomationReceiver.reschedule(this)
        }, 700)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
