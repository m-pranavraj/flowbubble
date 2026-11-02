package com.flowbubble.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowbubble.app.MainActivity
import com.flowbubble.app.core.HistoryEntry
import com.flowbubble.app.core.PermissionState
import com.flowbubble.app.core.Permissions
import com.flowbubble.app.notifications.NotificationCenter

@Composable
fun FlowBubbleApp(
    screen: String,
    onNavigate: (String) -> Unit,
    permissions: PermissionState,
    bubbleRunning: Boolean,
    captureActive: Boolean,
    captureError: String?,
    history: List<HistoryEntry>,
    onRefresh: () -> Unit,
    onToggleBubble: () -> Unit,
    onEnableCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onClearHistory: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
        ) {
            AppHeader(bubbleRunning)
            NavTabs(screen, onNavigate)
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when (screen) {
                    MainActivity.SCREEN_SETUP -> SetupScreen(
                        permissions = permissions,
                        bubbleRunning = bubbleRunning,
                        captureActive = captureActive,
                        captureError = captureError,
                        onRefresh = onRefresh,
                        onToggleBubble = onToggleBubble,
                        onEnableCapture = onEnableCapture,
                        onStopCapture = onStopCapture
                    )
                    MainActivity.SCREEN_SETTINGS -> SettingsScreen(onClearHistory = onClearHistory)
                    MainActivity.SCREEN_HISTORY -> HistoryScreen(
                        history = history,
                        onClearHistory = onClearHistory
                    )
                    else -> HomeScreen(
                        permissions = permissions,
                        bubbleRunning = bubbleRunning,
                        captureActive = captureActive,
                        captureError = captureError,
                        onToggleBubble = onToggleBubble,
                        onEnableCapture = onEnableCapture,
                        onStopCapture = onStopCapture,
                        onNavigate = onNavigate
                    )
                }
            }
        }
    }
}

@Composable
private fun AppHeader(bubbleRunning: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "FlowBubble",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "A voice layer for Android",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (bubbleRunning) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(ReadyGreen.copy(alpha = 0.14f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(ReadyGreen))
                Spacer(Modifier.width(6.dp))
                Text("Live", color = ReadyGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun NavTabs(current: String, onNavigate: (String) -> Unit) {
    val tabs = listOf(
        "Home" to MainActivity.SCREEN_HOME,
        "Setup" to MainActivity.SCREEN_SETUP,
        "Settings" to MainActivity.SCREEN_SETTINGS,
        "History" to MainActivity.SCREEN_HISTORY
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tabs.forEach { (label, key) ->
            val selected = current == key
            Text(
                text = label,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                    .clickable { onNavigate(key) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun PermissionDots(permissions: PermissionState) {
    val flags = listOf(
        permissions.microphone,
        permissions.overlay,
        permissions.accessibility,
        permissions.notifications,
        permissions.contacts,
        permissions.dndAccess,
        permissions.batteryExempt,
        permissions.notificationAccess
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        flags.forEach { enabled ->
            Box(
                Modifier
                    .weight(1f)
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
            )
        }
    }
}

@Composable
private fun HomeScreen(
    permissions: PermissionState,
    bubbleRunning: Boolean,
    captureActive: Boolean,
    captureError: String?,
    onToggleBubble: () -> Unit,
    onEnableCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onNavigate: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        FlowCard {
            SectionLabel(
                when {
                    bubbleRunning -> "Bubble active"
                    permissions.coreReady -> "Ready to start"
                    else -> "Setup needed"
                }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    bubbleRunning -> "Your voice layer is on"
                    permissions.coreReady -> "Everything is ready"
                    else -> "Give FlowBubble a voice"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            BodyText(
                when {
                    bubbleRunning ->
                        "Hold the floating bubble on any screen to speak. Drag it out of the way whenever you need."
                    permissions.coreReady ->
                        "The three required permissions are on. Start the bubble and hold it to talk."
                    else ->
                        "FlowBubble needs three Android permissions before it can appear above your apps."
                }
            )
            Spacer(Modifier.height(14.dp))
            PermissionDots(permissions)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${permissions.enabledCount} of 8 permissions on",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            FlowPrimaryButton(
                text = when {
                    bubbleRunning -> "Turn off bubble"
                    permissions.coreReady -> "Turn on bubble"
                    else -> "Finish setup"
                },
                onClick = {
                    if (!bubbleRunning && !permissions.coreReady) {
                        onNavigate(MainActivity.SCREEN_SETUP)
                    } else {
                        onToggleBubble()
                    }
                }
            )
            if (bubbleRunning) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { onNavigate(MainActivity.SCREEN_SETUP) },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(15.dp)
                ) {
                    Text("Manage permissions")
                }
            }
        }

        FlowCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Screen understanding")
                Spacer(Modifier.weight(1f))
                StatusChip(
                    if (captureActive) "On" else "Off",
                    if (captureActive) ReadyGreen else NeedAmber
                )
            }
            Spacer(Modifier.height(8.dp))
            BodyText(
                if (captureActive) {
                    "FlowBubble can read screen pixels with on-device OCR when you ask about the current screen. Frames stay in memory, password fields are skipped, and nothing is saved."
                } else {
                    "Optional. Turn this on so FlowBubble can read apps that hide their controls from accessibility \u2014 like some browsers, social apps, and games. Android shows a consent dialog each session, and you can stop it any time."
                }
            )
            if (captureError != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = captureError,
                    style = MaterialTheme.typography.bodySmall,
                    color = WarnRed,
                    lineHeight = 18.sp
                )
            }
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = if (captureActive) onStopCapture else onEnableCapture,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 8.dp,
                    vertical = 0.dp
                ),
                modifier = Modifier.height(36.dp)
            ) {
                Text(
                    if (captureActive) "Stop screen access" else "Turn on screen access",
                    fontSize = 13.sp
                )
            }
        }

        FlowCard {
            SectionLabel("Try saying")
            Spacer(Modifier.height(10.dp))
            listOf(
                "Draft a message to Priya saying I'll be there in ten minutes.",
                "Set a timer for twelve minutes.",
                "Turn on Do Not Disturb for one hour.",
                "Tap the second result, then scroll down.",
                "Long press the first message, then tap Pin.",
                "Search Flipkart for running shoes.",
                "Open the menu and swipe left.",
                "Select this paragraph and make it professional.",
                "Search this app for protein powder.",
                "What does this screen say about the refund policy?",
                "Read my notifications.",
                "Take a screenshot."
            ).forEach { example ->
                Row(modifier = Modifier.padding(vertical = 5.dp)) {
                    Box(
                        Modifier
                            .padding(top = 7.dp)
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = example,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 20.sp
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            BodyText("Speak normally. FlowBubble strips filler words and inserts clean text at the cursor, or runs the action you asked for.")
        }

        FlowCard {
            SectionLabel("How it runs")
            Spacer(Modifier.height(10.dp))
            listOf(
                "Turn on the bubble and grant the permissions it explains.",
                "Hold the bubble, speak, then release.",
                "FlowBubble types, edits, opens, or asks before anything consequential."
            ).forEachIndexed { index, line ->
                Row(modifier = Modifier.padding(vertical = 6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${index + 1}",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 20.sp
                    )
                }
            }
        }

        FlowCard {
            SectionLabel("What it won't do")
            Spacer(Modifier.height(8.dp))
            listOf(
                "Send messages or spend money without your final tap.",
                "Read password or payment fields, or bypass your lock screen.",
                "Claim an action worked when Android didn't confirm it."
            ).forEach { line ->
                Text(
                    text = "\u2014  $line",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 21.sp,
                    modifier = Modifier.padding(vertical = 3.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            BodyText("When a screen exposes nothing accessible, FlowBubble says so instead of guessing.")
        }
    }
}

@Composable
private fun SetupScreen(
    permissions: PermissionState,
    bubbleRunning: Boolean,
    captureActive: Boolean,
    captureError: String?,
    onRefresh: () -> Unit,
    onToggleBubble: () -> Unit,
    onEnableCapture: () -> Unit,
    onStopCapture: () -> Unit
) {
    val context = LocalContext.current
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { onRefresh() }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { onRefresh() }
    val contactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { onRefresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Permissions",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        BodyText("Android controls each capability below. FlowBubble enables a feature only after you grant it, and turns the feature off instead of pretending when access is missing.")

        FlowCard {
            SectionLabel("Required for the bubble")
            Spacer(Modifier.height(12.dp))
            PermissionRow(
                title = "Microphone",
                description = "Records only while you hold the bubble. Audio is sent to your device's speech recognizer.",
                granted = permissions.microphone,
                actionLabel = "Enable microphone",
                onAction = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
            )
            Spacer(Modifier.height(16.dp))
            PermissionRow(
                title = "Display over other apps",
                description = "Shows the floating bubble and result cards above your current app.",
                granted = permissions.overlay,
                actionLabel = "Open overlay settings",
                onAction = { Permissions.openOverlaySettings(context) }
            )
            Spacer(Modifier.height(16.dp))
            PermissionRow(
                title = "Accessibility service",
                description = "Lets FlowBubble read the focused field and on-screen controls, insert text, and perform the taps or scrolls you ask for. Passwords are skipped.",
                granted = permissions.accessibility,
                actionLabel = "Open accessibility settings",
                onAction = { Permissions.openAccessibilitySettings(context) }
            )
            if (!permissions.accessibility) {
                Text(
                    text = "In Android's accessibility list, choose \u201CFlowBubble voice access\u201D.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 18.dp, top = 4.dp)
                )
            }
        }

        FlowCard {
            SectionLabel("Recommended")
            Spacer(Modifier.height(12.dp))
            PermissionRow(
                title = "Notifications",
                description = "Keeps the microphone session visible and gives you a constant Stop control.",
                granted = permissions.notifications,
                actionLabel = "Allow notifications",
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        Permissions.openNotificationSettings(context)
                    }
                }
            )
            Spacer(Modifier.height(16.dp))
            PermissionRow(
                title = "Contacts",
                description = "Finds the person you name for message and call commands. FlowBubble never uploads your contacts.",
                granted = permissions.contacts,
                actionLabel = "Allow contacts",
                onAction = { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) }
            )
            Spacer(Modifier.height(16.dp))
            PermissionRow(
                title = "Notification access",
                description = "Enables \u201Cread my notifications\u201D and \u201Cclear notifications\u201D. FlowBubble keeps only notifications that arrive while it is connected, in memory. It never reads your notification history.",
                granted = permissions.notificationAccess,
                actionLabel = "Open notification access",
                onAction = { NotificationCenter.openSettings(context) }
            )
        }

        FlowCard {
            SectionLabel("Screen understanding")
            Spacer(Modifier.height(12.dp))
            PermissionRow(
                title = "Screen access",
                description = "Lets FlowBubble read screen pixels with on-device OCR for apps that hide their controls. Android asks for consent each session. Frames stay in memory, password fields are skipped, and nothing is saved.",
                granted = captureActive,
                actionLabel = if (captureActive) "Stop screen access" else "Turn on screen access",
                showActionWhenGranted = true,
                onAction = if (captureActive) onStopCapture else onEnableCapture
            )
            if (captureError != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = captureError,
                    style = MaterialTheme.typography.bodySmall,
                    color = WarnRed,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(start = 18.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            BodyText("Without screen access, FlowBubble only sees what Android's accessibility API exposes. It says so instead of guessing when an app hides its controls.")
        }

        FlowCard {
            SectionLabel("Optional power features")
            Spacer(Modifier.height(12.dp))
            PermissionRow(
                title = "Do Not Disturb access",
                description = "Required by Android before any app can change Do Not Disturb. FlowBubble verifies the new state.",
                granted = permissions.dndAccess,
                actionLabel = "Open access settings",
                onAction = { Permissions.openDndSettings(context) }
            )
            Spacer(Modifier.height(16.dp))
            PermissionRow(
                title = "Battery optimization",
                description = "Keeps the bubble and its microphone service alive during long sessions.",
                granted = permissions.batteryExempt,
                actionLabel = "Allow background use",
                onAction = { Permissions.openBatterySettings(context) }
            )
        }

        FlowPrimaryButton(
            text = when {
                bubbleRunning -> "Turn off bubble"
                permissions.coreReady -> "Turn on bubble"
                else -> "Finish the required steps"
            },
            enabled = permissions.coreReady || bubbleRunning,
            onClick = onToggleBubble
        )
        BodyText(
            "The required steps are microphone, overlay, and accessibility. Without accessibility, FlowBubble cannot type, edit, tap, or scroll. Screen and notification access are optional and can be turned off any time. You can change every permission later in Android settings."
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    actionLabel: String,
    showActionWhenGranted: Boolean = false,
    onAction: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (granted) ReadyGreen else NeedAmber)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            StatusChip(if (granted) "On" else "Off", if (granted) ReadyGreen else NeedAmber)
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = description,
            modifier = Modifier.padding(start = 18.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp
        )
        if (!granted || showActionWhenGranted) {
            TextButton(
                onClick = onAction,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 8.dp,
                    vertical = 0.dp
                ),
                modifier = Modifier.padding(start = 12.dp).height(34.dp)
            ) {
                Text(actionLabel, fontSize = 13.sp)
            }
        }
    }
}
