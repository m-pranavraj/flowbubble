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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowbubble.app.MainActivity
import com.flowbubble.app.core.HistoryEntry
import com.flowbubble.app.core.PermissionState
import com.flowbubble.app.core.Permissions
import com.flowbubble.app.notifications.NotificationCenter
import com.flowbubble.app.ui.theme.BubbleShapes
import com.flowbubble.app.ui.theme.Ink

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
        color = Ink.Void
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
        ) {
            /*
             * The header and the tab row sit on one lifted slab: Onyx fading into Obsidian, closed
             * off by a single luminous lavender-to-burgundy hairline. Content scrolls beneath it.
             */
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Ink.Onyx,
                            0.72f to Ink.Obsidian,
                            1f to Ink.Obsidian
                        )
                    )
                    .drawBehind {
                        val stroke = 1.dp.toPx()
                        drawLine(
                            brush = Brush.horizontalGradient(
                                0f to Ink.LavenderDeep.copy(alpha = 0f),
                                0.22f to Ink.LavenderDeep.copy(alpha = 0.8f),
                                0.58f to Ink.Crimson.copy(alpha = 0.5f),
                                1f to Ink.Burgundy.copy(alpha = 0f)
                            ),
                            start = Offset(0f, size.height - stroke / 2f),
                            end = Offset(size.width, size.height - stroke / 2f),
                            strokeWidth = stroke
                        )
                    }
            ) {
                AppHeader(bubbleRunning)
                NavTabs(screen, onNavigate)
            }
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
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(
                    Brush.linearGradient(listOf(Ink.Lavender, Ink.LavenderDeep, Ink.Burgundy))
                )
                .border(1.dp, Ink.Lavender.copy(alpha = 0.4f), RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(Ink.Void.copy(alpha = 0.6f))
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = buildAnnotatedString {
                    withStyle(
                        SpanStyle(brush = Brush.horizontalGradient(listOf(Ink.Mist, Ink.Lavender)))
                    ) {
                        append("Your Bubble")
                    }
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.1.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "A voice layer for Android",
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 0.7.sp,
                color = Ink.Faint
            )
        }
        Spacer(Modifier.width(10.dp))
        StatusChip(
            text = if (bubbleRunning) "Live" else "Idle",
            color = if (bubbleRunning) ReadyGreen else Ink.Faint
        )
    }
}

@Composable
private fun NavTabs(current: String, onNavigate: (String) -> Unit) {
    val tabs = listOf(
        "Home" to MainActivity.SCREEN_HOME,
        "Setup" to MainActivity.SCREEN_SETUP,
        "Automations" to MainActivity.SCREEN_AUTOMATIONS,
        "Settings" to MainActivity.SCREEN_SETTINGS,
        "History" to MainActivity.SCREEN_HISTORY
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tabs.forEach { (label, key) ->
            val selected = current == key
            Box(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clip(BubbleShapes.Chip)
                    .background(
                        if (selected) {
                            Brush.horizontalGradient(listOf(Ink.LavenderDeep, Ink.Burgundy))
                        } else {
                            Brush.horizontalGradient(
                                listOf(Ink.Smoke.copy(alpha = 0.35f), Ink.Veil.copy(alpha = 0.35f))
                            )
                        }
                    )
                    .border(
                        1.dp,
                        if (selected) Ink.Lavender.copy(alpha = 0.6f) else Ink.Veil.copy(alpha = 0.8f),
                        BubbleShapes.Chip
                    )
                    .clickable { onNavigate(key) }
                    .padding(horizontal = 18.dp, vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (selected) Ink.Mist else Ink.Faint,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                )
            }
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
                    .height(6.dp)
                    .clip(BubbleShapes.Chip)
                    .background(
                        if (enabled) {
                            Brush.horizontalGradient(
                                listOf(Ink.Jade.copy(alpha = 0.45f), Ink.Jade)
                            )
                        } else {
                            Brush.horizontalGradient(
                                listOf(Ink.Veil.copy(alpha = 0.5f), Ink.Veil.copy(alpha = 0.15f))
                            )
                        }
                    )
            )
        }
    }
}

/** The one error treatment used by both Home and Setup, so a failure always reads the same. */
@Composable
private fun ErrorSurface(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(BubbleShapes.Field)
            .background(
                Brush.horizontalGradient(
                    listOf(Ink.BurgundyDeep.copy(alpha = 0.85f), Ink.Obsidian)
                )
            )
            .border(1.dp, Ink.Crimson.copy(alpha = 0.45f), BubbleShapes.Field)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .padding(top = 4.dp)
                .size(7.dp)
                .clip(CircleShape)
                .background(Ink.Crimson)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = Ink.CrimsonSoft,
            lineHeight = 18.sp
        )
    }
}

/** Title + luminous rule + one line of intent, used to open every screen. */
@Composable
internal fun ScreenIntro(title: String, copy: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = Ink.Mist
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .width(46.dp)
                .height(2.dp)
                .clip(BubbleShapes.Chip)
                .background(Brush.horizontalGradient(listOf(Ink.Lavender, Ink.Burgundy.copy(alpha = 0f))))
        )
        Spacer(Modifier.height(12.dp))
        BodyText(copy)
    }
}

/** A 1dp hairline that fades at both ends, used to divide permission rows inside one card. */
@Composable
private fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Ink.Lavender.copy(alpha = 0f),
                        Ink.Lavender.copy(alpha = 0.2f),
                        Ink.Burgundy.copy(alpha = 0.32f),
                        Ink.Burgundy.copy(alpha = 0f)
                    )
                )
            )
    )
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
        FlowCard(
            accent = when {
                bubbleRunning -> Ink.Jade
                permissions.coreReady -> Ink.LavenderDeep
                else -> Ink.Amber
            }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(
                    when {
                        bubbleRunning -> "Bubble active"
                        permissions.coreReady -> "Ready to start"
                        else -> "Setup needed"
                    },
                    color = when {
                        bubbleRunning -> Ink.Jade
                        permissions.coreReady -> Ink.Lavender
                        else -> Ink.Amber
                    }
                )
                Spacer(Modifier.weight(1f))
                StatusChip(
                    text = if (bubbleRunning) "Live" else "Idle",
                    color = if (bubbleRunning) ReadyGreen else Ink.Faint
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = when {
                    bubbleRunning -> "Your voice layer is on"
                    permissions.coreReady -> "Everything is ready"
                    else -> "Give Your Bubble a voice"
                },
                style = MaterialTheme.typography.headlineMedium,
                color = Ink.Mist
            )
            Spacer(Modifier.height(8.dp))
            BodyText(
                when {
                    bubbleRunning ->
                        "Hold the floating bubble on any screen to speak. Drag it out of the way whenever you need."
                    permissions.coreReady ->
                        "The three required permissions are on. Start the bubble and hold it to talk."
                    else ->
                        "Your Bubble needs three Android permissions before it can appear above your apps."
                }
            )
            Spacer(Modifier.height(18.dp))
            PermissionDots(permissions)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${permissions.enabledCount} of ${permissions.total} permissions on",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    letterSpacing = 0.6.sp,
                    color = if (permissions.enabledCount == permissions.total) ReadyGreen else Ink.Faint
                )
                Text(
                    text = if (permissions.coreReady) "Core ready" else "Core missing",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (permissions.coreReady) Ink.Jade else Ink.Amber
                )
            }
            Spacer(Modifier.height(18.dp))
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
                Spacer(Modifier.height(10.dp))
                FlowGhostButton(
                    text = "Manage permissions",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onNavigate(MainActivity.SCREEN_SETUP) }
                )
                Spacer(Modifier.height(10.dp))
                BodyText(
                    "Turning the bubble off is a kill switch: the overlay and its microphone session end immediately.",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        FlowCard(accent = if (captureActive) Ink.Jade else Ink.Amber) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Screen understanding")
                Spacer(Modifier.weight(1f))
                StatusChip(
                    if (captureActive) "On" else "Off",
                    if (captureActive) ReadyGreen else NeedAmber
                )
            }
            Spacer(Modifier.height(10.dp))
            BodyText(
                if (captureActive) {
                    "Your Bubble can read screen pixels with on-device OCR when you ask about the current screen. Frames stay in memory, password fields are skipped, and nothing is saved."
                } else {
                    "Optional. Turn this on so Your Bubble can read apps that hide their controls from accessibility \u2014 like some browsers, social apps, and games. Android shows a consent dialog each session, and you can stop it any time."
                }
            )
            if (captureError != null) {
                Spacer(Modifier.height(12.dp))
                ErrorSurface(captureError)
            }
            Spacer(Modifier.height(14.dp))
            FlowGhostButton(
                text = if (captureActive) "Stop screen access" else "Turn on screen access",
                danger = captureActive,
                modifier = Modifier.fillMaxWidth(),
                onClick = if (captureActive) onStopCapture else onEnableCapture
            )
        }

        FlowCard {
            SectionLabel("Try saying")
            Spacer(Modifier.height(12.dp))
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        Modifier
                            .padding(top = 8.dp)
                            .width(2.dp)
                            .height(14.dp)
                            .clip(BubbleShapes.Chip)
                            .background(Brush.verticalGradient(listOf(Ink.Lavender, Ink.Burgundy)))
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = example,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink.Mist,
                        lineHeight = 21.sp
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            BodyText("Speak normally. Your Bubble strips filler words and inserts clean text at the cursor, or runs the action you asked for.")
        }

        FlowCard(accent = Ink.Burgundy) {
            SectionLabel("How it runs")
            Spacer(Modifier.height(12.dp))
            listOf(
                "Turn on the bubble and grant the permissions it explains.",
                "Hold the bubble, speak, then release.",
                "Your Bubble types, edits, opens, or asks before anything consequential."
            ).forEachIndexed { index, line ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(listOf(Ink.LavenderDeep, Ink.Burgundy))
                            )
                            .border(1.dp, Ink.Lavender.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${index + 1}",
                            color = Ink.Mist,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = line,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink.Mist,
                        lineHeight = 21.sp
                    )
                }
            }
        }

        FlowCard(accent = Ink.Crimson) {
            SectionLabel("What it won't do", color = Ink.CrimsonSoft)
            Spacer(Modifier.height(12.dp))
            listOf(
                "Send messages or spend money without your final tap.",
                "Read password or payment fields, or bypass your lock screen.",
                "Claim an action worked when Android didn't confirm it."
            ).forEach { line ->
                Text(
                    text = "\u2014  $line",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink.Mist.copy(alpha = 0.88f),
                    lineHeight = 21.sp,
                    modifier = Modifier.padding(vertical = 3.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            BodyText("When a screen exposes nothing accessible, Your Bubble says so instead of guessing.")
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
    val cameraLauncher = rememberLauncherForActivityResult(
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
        ScreenIntro(
            title = "Permissions",
            copy = "Android controls each capability below. Your Bubble enables a feature only after you grant it, and turns the feature off instead of pretending when access is missing."
        )

        FlowCard(accent = Ink.LavenderDeep) {
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
            RowDivider()
            Spacer(Modifier.height(16.dp))
            PermissionRow(
                title = "Display over other apps",
                description = "Shows the floating bubble and result cards above your current app.",
                granted = permissions.overlay,
                actionLabel = "Open overlay settings",
                onAction = { Permissions.openOverlaySettings(context) }
            )
            Spacer(Modifier.height(16.dp))
            RowDivider()
            Spacer(Modifier.height(16.dp))
            PermissionRow(
                title = "Accessibility service",
                description = "Lets Your Bubble read the focused field and on-screen controls, insert text, and perform the taps or scrolls you ask for. Passwords are skipped.",
                granted = permissions.accessibility,
                actionLabel = "Open accessibility settings",
                onAction = { Permissions.openAccessibilitySettings(context) }
            )
            if (!permissions.accessibility) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(BubbleShapes.Field)
                        .background(Ink.Veil.copy(alpha = 0.32f))
                        .border(1.dp, Ink.Lavender.copy(alpha = 0.18f), BubbleShapes.Field)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "In Android's accessibility list, choose \u201CYour Bubble voice access\u201D.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.Lavender,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        FlowCard(accent = Ink.Burgundy) {
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
            RowDivider()
            Spacer(Modifier.height(16.dp))
            PermissionRow(
                title = "Contacts",
                description = "Finds the person you name for message and call commands. Your Bubble never uploads your contacts.",
                granted = permissions.contacts,
                actionLabel = "Allow contacts",
                onAction = { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) }
            )
            Spacer(Modifier.height(16.dp))
            RowDivider()
            Spacer(Modifier.height(16.dp))
            PermissionRow(
                title = "Camera",
                description = "Only for the flashlight. Your Bubble never opens the camera to take a picture or record video.",
                granted = permissions.camera,
                actionLabel = "Allow camera",
                onAction = { cameraLauncher.launch(Manifest.permission.CAMERA) }
            )
            Spacer(Modifier.height(16.dp))
            RowDivider()
            Spacer(Modifier.height(16.dp))
            PermissionRow(
                title = "Notification access",
                description = "Enables \u201Cread my notifications\u201D and \u201Cclear notifications\u201D. Your Bubble keeps only notifications that arrive while it is connected, in memory. It never reads your notification history.",
                granted = permissions.notificationAccess,
                actionLabel = "Open notification access",
                onAction = { NotificationCenter.openSettings(context) }
            )
        }

        FlowCard(accent = if (captureActive) Ink.Jade else Ink.Amber) {
            SectionLabel("Screen understanding")
            Spacer(Modifier.height(12.dp))
            PermissionRow(
                title = "Screen access",
                description = "Lets Your Bubble read screen pixels with on-device OCR for apps that hide their controls. Android asks for consent each session. Frames stay in memory, password fields are skipped, and nothing is saved.",
                granted = captureActive,
                actionLabel = if (captureActive) "Stop screen access" else "Turn on screen access",
                showActionWhenGranted = true,
                onAction = if (captureActive) onStopCapture else onEnableCapture
            )
            if (captureError != null) {
                Spacer(Modifier.height(10.dp))
                ErrorSurface(captureError)
            }
            Spacer(Modifier.height(10.dp))
            BodyText("Without screen access, Your Bubble only sees what Android's accessibility API exposes. It says so instead of guessing when an app hides its controls.")
        }

        FlowCard(accent = Ink.BurgundyDeep) {
            SectionLabel("Optional power features")
            Spacer(Modifier.height(12.dp))
            PermissionRow(
                title = "Do Not Disturb access",
                description = "Required by Android before any app can change Do Not Disturb. Your Bubble verifies the new state.",
                granted = permissions.dndAccess,
                actionLabel = "Open access settings",
                onAction = { Permissions.openDndSettings(context) }
            )
            Spacer(Modifier.height(16.dp))
            RowDivider()
            Spacer(Modifier.height(16.dp))
            PermissionRow(
                title = "Battery optimization",
                description = "Keeps the bubble and its microphone service alive during long sessions.",
                granted = permissions.batteryExempt,
                actionLabel = "Allow background use",
                onAction = { Permissions.openBatterySettings(context) }
            )
        }

        FlowCard(
            accent = when {
                bubbleRunning -> Ink.Jade
                permissions.coreReady -> Ink.LavenderDeep
                else -> Ink.Amber
            }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(
                    when {
                        bubbleRunning -> "Bubble active"
                        permissions.coreReady -> "Ready to start"
                        else -> "Setup needed"
                    }
                )
                Spacer(Modifier.weight(1f))
                StatusChip(
                    text = if (bubbleRunning) "Live" else "Idle",
                    color = if (bubbleRunning) ReadyGreen else Ink.Faint
                )
            }
            Spacer(Modifier.height(14.dp))
            FlowPrimaryButton(
                text = when {
                    bubbleRunning -> "Turn off bubble"
                    permissions.coreReady -> "Turn on bubble"
                    else -> "Finish the required steps"
                },
                enabled = permissions.coreReady || bubbleRunning,
                onClick = onToggleBubble
            )
            Spacer(Modifier.height(12.dp))
            BodyText(
                "The required steps are microphone, overlay, and accessibility. Without accessibility, Your Bubble cannot type, edit, tap, or scroll. Screen and notification access are optional and can be turned off any time. You can change every permission later in Android settings."
            )
        }
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
    val tint = if (granted) ReadyGreen else NeedAmber
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(BubbleShapes.Chip)
                .background(Brush.verticalGradient(listOf(tint, tint.copy(alpha = 0.1f))))
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink.Mist
                )
                Spacer(Modifier.width(10.dp))
                StatusChip(if (granted) "On" else "Off", tint)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Ink.Faint,
                lineHeight = 18.sp
            )
            if (!granted || showActionWhenGranted) {
                Spacer(Modifier.height(10.dp))
                FlowGhostButton(
                    text = actionLabel,
                    danger = granted,
                    onClick = onAction
                )
            }
        }
    }
}
