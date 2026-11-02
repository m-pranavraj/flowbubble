package com.flowbubble.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowbubble.app.BuildConfig
import com.flowbubble.app.core.HistoryEntry
import com.flowbubble.app.core.Prefs
import com.flowbubble.app.core.RoutineStore
import com.flowbubble.app.service.OverlayService
import com.flowbubble.app.ui.theme.BubbleShapes
import com.flowbubble.app.ui.theme.Ink
import com.flowbubble.app.voice.EngineChoice
import com.flowbubble.app.voice.InstallEvent
import com.flowbubble.app.voice.LocalSpeechModel
import com.flowbubble.app.voice.ModelStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A picker the design system owns: an unlit Smoke pill when off, a lavender-to-burgundy glow with a
 * lit ring when on. Used by the language and speech-engine rows.
 */
@Composable
private fun OptionPill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(BubbleShapes.Button)
            .background(
                if (selected) {
                    Brush.horizontalGradient(listOf(Ink.LavenderDeep, Ink.Burgundy))
                } else {
                    Brush.horizontalGradient(listOf(Ink.Smoke, Ink.Onyx))
                }
            )
            .border(
                1.dp,
                if (selected) Ink.Lavender.copy(alpha = 0.55f) else Ink.Veil.copy(alpha = 0.8f),
                BubbleShapes.Button
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (selected) Ink.Mist else Ink.Whisper)
        )
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Ink.Mist else Ink.Faint,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            lineHeight = 18.sp
        )
    }
}

/** A 1dp hairline that fades out at both ends, used to divide rows inside a card. */
@Composable
private fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Ink.Lavender.copy(alpha = 0f),
                        Ink.Lavender.copy(alpha = 0.22f),
                        Ink.Burgundy.copy(alpha = 0.35f),
                        Ink.Burgundy.copy(alpha = 0f)
                    )
                )
            )
    )
}

@Composable
private fun Bullet(leading: Color) {
    Box(
        Modifier
            .padding(top = 8.dp)
            .width(3.dp)
            .height(13.dp)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(leading, leading.copy(alpha = 0.05f))))
    )
}

@Composable
internal fun SettingsScreen(onClearHistory: () -> Unit) {
    val context = LocalContext.current
    var handsFree by remember { mutableStateOf(Prefs.handsFreeEnabled(context)) }
    var language by remember { mutableStateOf(Prefs.language(context)) }
    var confirmClear by remember { mutableStateOf(false) }
    var routines by remember { mutableStateOf(RoutineStore.list(context)) }
    var pendingRoutineDelete by remember { mutableStateOf<RoutineStore.Routine?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenIntro(
            title = "Settings",
            copy = "Everything on this screen is optional. Without an AI model, Your Bubble still cleans dictation and edits with on-device rules."
        )

        AiModelsCard()

        FlowCard(accent = Ink.LavenderDeep) {
            SectionLabel("Voice language")
            Spacer(Modifier.height(8.dp))
            BodyText("Uses your device's speech recognition. Install the matching offline language pack for the most reliable results.")
            Spacer(Modifier.height(6.dp))
            BodyText("Hindi and Hinglish commands are understood for opening apps, search, reading and scrolling the screen, taps, calls, chats, messages, timers, torch, volume and navigation keys. Other speech in the selected language is dictated as usual.")
            Spacer(Modifier.height(14.dp))
            val languages = listOf(
                "en-IN" to "English (India)",
                "en-US" to "English (US)",
                "en-GB" to "English (UK)",
                "hi-IN" to "हिन्दी (India)"
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                languages.chunked(2).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { (code, label) ->
                            OptionPill(
                                label = label,
                                selected = language == code,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    language = code
                                    Prefs.setLanguage(context, code)
                                }
                            )
                        }
                    }
                }
            }
        }

        SpeechEngineCard(language)

        FlowCard(accent = if (handsFree) Ink.Jade else Ink.LavenderDeep) {
            SectionLabel("Hands-free")
            Spacer(Modifier.height(8.dp))
            BodyText("Say \u201CHello Bubble\u201D, then your command. Your Bubble listens only while this switch is on and the screen is on, so nothing in another app can trigger a command by accident.")
            Spacer(Modifier.height(8.dp))
            Hairline()
            FlowRow(
                title = "Listen for \u201CHello Bubble\u201D",
                detail = "Spoken confirmations accept yes or no. Silence never approves a message, purchase, or destructive action.",
                trailing = {
                    FlowToggle(
                        checked = handsFree,
                        onCheckedChange = {
                            handsFree = it
                            Prefs.setHandsFreeEnabled(context, it)
                            if (OverlayService.isRunning) OverlayService.start(context)
                        }
                    )
                }
            )
            Hairline()
            Spacer(Modifier.height(8.dp))
            StatusChip(
                text = if (handsFree) "Listening for the wake phrase" else "Wake phrase off",
                color = if (handsFree) ReadyGreen else Ink.Faint
            )
        }

        FlowCard(accent = Ink.Burgundy) {
            SectionLabel("Voice routines")
            Spacer(Modifier.height(8.dp))
            BodyText("Save a command you repeat and replay it by name. Every routine runs through the same checks and confirmations as speaking it yourself.")
            Spacer(Modifier.height(14.dp))
            if (routines.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(BubbleShapes.Field)
                        .background(Ink.Veil.copy(alpha = 0.28f))
                        .border(1.dp, Ink.Lavender.copy(alpha = 0.16f), BubbleShapes.Field)
                        .padding(horizontal = 14.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = "Nothing saved yet. Try saying \u201Csave this as routine message mom\u201D, then \u201Crun routine message mom\u201D.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink.Faint,
                        lineHeight = 21.sp
                    )
                }
            } else {
                routines.forEachIndexed { index, routine ->
                    if (index != 0) {
                        Spacer(Modifier.height(10.dp))
                        Hairline()
                        Spacer(Modifier.height(4.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Bullet(leading = Ink.Lavender)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = routine.name.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.titleMedium,
                                color = Ink.Mist
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = routine.phrase,
                                style = MaterialTheme.typography.bodySmall,
                                color = Ink.Faint,
                                lineHeight = 17.sp
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        FlowGhostButton(
                            text = "Delete",
                            danger = true,
                            onClick = { pendingRoutineDelete = routine }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                BodyText("Say \u201Clist routines\u201D to hear them, or say the routine's name with \u201Crun routine\u201D.")
            }
        }

        FlowCard(accent = Ink.Crimson) {
            SectionLabel("Privacy & data", color = Ink.CrimsonSoft)
            Spacer(Modifier.height(12.dp))
            listOf(
                "Audio is handled by Android's speech recognizer only while you hold the bubble, or while hands-free is on and listening.",
                "Screen frames are read on-device, kept in memory, and never saved.",
                "Notifications are kept in memory for this session only.",
                "History stores command summaries, never transcripts, audio, or screen text.",
                "AI requests go only to the endpoint you configure."
            ).forEach { line ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Bullet(leading = Ink.CrimsonSoft)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = line,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink.Mist.copy(alpha = 0.9f),
                        lineHeight = 21.sp
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Hairline()
            Spacer(Modifier.height(10.dp))
            BodyText("Turn the bubble off, or stop screen access in Setup, to end everything immediately.")
        }

        FlowCard(accent = Ink.BurgundyDeep) {
            SectionLabel("History")
            Spacer(Modifier.height(8.dp))
            BodyText("Command summaries are stored only on this device, up to the last 100 entries. Audio and screen text are never saved to history.")
            Spacer(Modifier.height(14.dp))
            FlowPrimaryButton(
                text = "Clear history",
                tone = FlowButtonTone.Danger,
                onClick = { confirmClear = true }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Ink.Jade)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Your Bubble ${BuildConfig.VERSION_NAME} \u00B7 No analytics, no background uploads",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 0.8.sp,
                color = Ink.Whisper
            )
        }
        Spacer(Modifier.height(4.dp))
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Clear history?",
            message = "This removes every saved command summary from this device.",
            confirmLabel = "Clear",
            onConfirm = {
                onClearHistory()
                confirmClear = false
            },
            onDismiss = { confirmClear = false }
        )
    }

    pendingRoutineDelete?.let { routine ->
        ConfirmDialog(
            title = "Delete routine?",
            message = "\u201C${routine.name}\u201D will be removed from this device. You can save it again later.",
            confirmLabel = "Delete",
            onConfirm = {
                RoutineStore.delete(context, routine.name)
                routines = RoutineStore.list(context)
                pendingRoutineDelete = null
            },
            onDismiss = { pendingRoutineDelete = null }
        )
    }
}

/** The one destructive-confirmation surface: Obsidian slab, crimson verb, lavender escape. */
@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = BubbleShapes.Card,
        containerColor = Ink.Onyx,
        tonalElevation = 0.dp,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Ink.Mist
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = Ink.Faint,
                lineHeight = 21.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    color = Ink.Crimson,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Cancel",
                    color = Ink.Lavender,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    )
}

@Composable
private fun SpeechEngineCard(language: String) {
    val context = LocalContext.current
    var choice by remember { mutableStateOf(Prefs.engineChoice(context)) }
    var status by remember { mutableStateOf(ModelStatus.UNKNOWN) }
    var detail by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var probeToken by remember { mutableStateOf(0) }
    var installToken by remember { mutableStateOf(0) }
    var probing by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var shownLanguage by remember { mutableStateOf(language) }
    val speechModel = remember { LocalSpeechModel() }
    val busy = probing || installing

    DisposableEffect(Unit) {
        onDispose { speechModel.close() }
    }

    LaunchedEffect(language, probeToken) {
        probing = true
        shownLanguage = language
        progress = null
        val state = speechModel.refresh(language)
        if (shownLanguage == language) {
            status = state.status
            detail = state.detail
            probing = false
        }
    }

    LaunchedEffect(installToken) {
        if (installToken == 0) return@LaunchedEffect
        val target = language
        installing = true
        progress = "Starting the download…"
        val state = speechModel.install(target) { event ->
            progress = when (event) {
                is InstallEvent.Started ->
                    if (event.bytesToDownload > 0) "Downloading ${formatBytes(event.bytesToDownload)}…"
                    else "Downloading…"
                is InstallEvent.Progress -> "${formatBytes(event.bytesDownloaded)} downloaded"
                InstallEvent.Completed -> "Finishing the install…"
                is InstallEvent.Failed -> event.reason
            }
        }
        if (shownLanguage == target) {
            status = state.status
            detail = state.detail
            progress = if (state.status == ModelStatus.FAILED) state.detail else null
        }
        installing = false
    }

    FlowCard(accent = Ink.Lavender) {
        SectionLabel("Speech engine")
        Spacer(Modifier.height(8.dp))
        BodyText(
            "Which recognizer turns your voice into text. The on-device model is more accurate " +
                "offline and never sends audio away, but it is a separate download per language."
        )
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EngineChoice.entries.forEach { option ->
                OptionPill(
                    label = option.label,
                    selected = choice == option,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        choice = option
                        Prefs.setEngineChoice(context, option)
                    }
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Hairline()
        Spacer(Modifier.height(10.dp))
        val (chip, chipColor) = when (status) {
            ModelStatus.READY -> "On-device model ready" to ReadyGreen
            ModelStatus.INSTALLABLE -> "Model downloadable" to NeedAmber
            ModelStatus.INSTALLING -> "Download in progress" to NeedAmber
            ModelStatus.UNAVAILABLE -> "Not available on this device" to WarnRed
            ModelStatus.FAILED -> "Install failed" to WarnRed
            ModelStatus.UNKNOWN -> "Checking…" to Ink.Lavender
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(chip, chipColor)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            FlowGhostButton(
                text = "Check again",
                enabled = !busy,
                onClick = { probeToken++ }
            )
        }
        Spacer(Modifier.height(10.dp))
        val note = when {
            progress != null -> progress!!
            detail != null -> detail!!
            status == ModelStatus.READY ->
                "$language is ready to use on-device. Holding the bubble needs no network for transcription."
            status == ModelStatus.INSTALLABLE ->
                "The model for $language is available. Download it over Wi-Fi if you can."
            status == ModelStatus.UNAVAILABLE ->
                "The on-device model doesn't offer $language on this device, so the system recognizer is used."
            choice == EngineChoice.SYSTEM ->
                "Your Bubble uses your device's recognizer. Nothing new is downloaded."
            else ->
                "Automatic uses the on-device model whenever it is ready, and the system recognizer otherwise."
        }
        val troubled = status == ModelStatus.FAILED || status == ModelStatus.UNAVAILABLE
        if (troubled) {
            Notice(message = note, tone = WarnRed)
        } else {
            BodyText(note)
        }
        if (status == ModelStatus.INSTALLABLE) {
            Spacer(Modifier.height(14.dp))
            FlowPrimaryButton(
                text = "Download the on-device model",
                enabled = !busy,
                onClick = { installToken++ }
            )
            Spacer(Modifier.height(10.dp))
            BodyText(
                "Google Play services performs the download. Your Bubble never starts it on its own, " +
                    "and turning the engine back to System recognizer leaves the model untouched."
            )
        }
    }
}

/** A tinted notice slab used where a state can go wrong: amber for pending, crimson for failed. */
@Composable
private fun Notice(message: String, tone: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BubbleShapes.Field)
            .background(
                Brush.horizontalGradient(
                    listOf(tone.copy(alpha = 0.16f), Ink.Obsidian)
                )
            )
            .border(1.dp, tone.copy(alpha = 0.4f), BubbleShapes.Field)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .padding(top = 4.dp)
                .size(7.dp)
                .clip(CircleShape)
                .background(tone)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = if (tone == WarnRed) Ink.CrimsonSoft else Ink.Mist,
            lineHeight = 18.sp
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
    else -> "$bytes B"
}

@Composable
internal fun HistoryScreen(history: List<HistoryEntry>, onClearHistory: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenIntro(
            title = "History",
            copy = "A local record of what Your Bubble understood and did. Full transcripts, audio, and screen text are not stored."
        )

        if (history.isEmpty()) {
            FlowCard(accent = Ink.Lavender) {
                SectionLabel("Nothing yet")
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .clip(BubbleShapes.Field)
                        .background(
                            Brush.verticalGradient(
                                listOf(Ink.Veil.copy(alpha = 0.35f), Ink.Obsidian)
                            )
                        )
                        .border(1.dp, Ink.Lavender.copy(alpha = 0.14f), BubbleShapes.Field),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(listOf(Ink.LavenderDeep, Ink.Burgundy))
                            )
                            .border(1.dp, Ink.Lavender.copy(alpha = 0.35f), CircleShape)
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Hold the bubble and speak. Your completed actions will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink.Faint,
                    lineHeight = 21.sp
                )
            }
        } else {
            FlowCard(accent = Ink.LavenderDeep) {
                SectionLabel("${history.size} recent ${if (history.size == 1) "entry" else "entries"}")
                Spacer(Modifier.height(12.dp))
                history.forEachIndexed { index, entry ->
                    HistoryRow(entry)
                    if (index != history.lastIndex) {
                        Spacer(Modifier.height(10.dp))
                        Hairline()
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
            FlowPrimaryButton(
                text = "Clear history",
                tone = FlowButtonTone.Danger,
                onClick = { confirmClear = true }
            )
        }
        Spacer(Modifier.height(4.dp))
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Clear history?",
            message = "This removes every saved command summary from this device.",
            confirmLabel = "Clear",
            onConfirm = {
                onClearHistory()
                confirmClear = false
            },
            onDismiss = { confirmClear = false }
        )
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry) {
    val time = remember(entry.time) {
        SimpleDateFormat("d MMM \u00B7 HH:mm", Locale.getDefault()).format(Date(entry.time))
    }
    val tone = statusColor(entry.status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(tone, tone.copy(alpha = 0.1f))))
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.kind.ifBlank { "Action" },
                    style = MaterialTheme.typography.labelMedium,
                    letterSpacing = 0.8.sp,
                    color = Ink.Lavender,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.Whisper
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = entry.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = Ink.Mist,
                lineHeight = 21.sp
            )
        }
        Spacer(Modifier.width(10.dp))
        StatusChip(
            text = entry.status.replaceFirstChar { it.uppercase() }.ifBlank { "Logged" },
            color = tone
        )
    }
}

private fun statusColor(status: String): Color = when (status.lowercase()) {
    "done" -> ReadyGreen
    "awaiting user", "requested", "copied", "unverified", "setup needed" -> NeedAmber
    "failed", "not found", "permission missing", "unsupported" -> WarnRed
    else -> Ink.Lavender
}
