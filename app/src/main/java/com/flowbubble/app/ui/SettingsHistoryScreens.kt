package com.flowbubble.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import com.flowbubble.app.voice.EngineChoice
import com.flowbubble.app.voice.InstallEvent
import com.flowbubble.app.voice.LocalSpeechModel
import com.flowbubble.app.voice.ModelStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        BodyText("Everything on this screen is optional. Without an AI model, FlowBubble still cleans dictation and edits with on-device rules.")

        AiModelsCard()

        FlowCard {
            SectionLabel("Voice language")
            Spacer(Modifier.height(6.dp))
            BodyText("Uses your device's speech recognition. Install the matching offline language pack for the most reliable results.")
            Spacer(Modifier.height(6.dp))
            BodyText("Hindi and Hinglish commands are understood for opening apps, search, reading and scrolling the screen, taps, calls, chats, messages, timers, torch, volume and navigation keys. Other speech in the selected language is dictated as usual.")
            Spacer(Modifier.height(12.dp))
            val languages = listOf(
                "en-IN" to "English (India)",
                "en-US" to "English (US)",
                "en-GB" to "English (UK)",
                "hi-IN" to "हिन्दी (India)"
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                languages.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (code, label) ->
                            FilterChip(
                                selected = language == code,
                                onClick = {
                                    language = code
                                    Prefs.setLanguage(context, code)
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        }

        SpeechEngineCard(language)

        FlowCard {
            SectionLabel("Hands-free")
            Spacer(Modifier.height(6.dp))
            BodyText("Say \u201CHello Bubble\u201D, then your command. FlowBubble listens only while this switch is on and the screen is on, so nothing in another app can trigger a command by accident.")
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Listen for \u201CHello Bubble\u201D",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Spoken confirmations accept yes or no. Silence never approves a message, purchase, or destructive action.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 17.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = handsFree,
                    onCheckedChange = {
                        handsFree = it
                        Prefs.setHandsFreeEnabled(context, it)
                        if (OverlayService.isRunning) OverlayService.start(context)
                    }
                )
            }
        }

        FlowCard {
            SectionLabel("Voice routines")
            Spacer(Modifier.height(6.dp))
            BodyText("Save a command you repeat and replay it by name. Every routine runs through the same checks and confirmations as speaking it yourself.")
            Spacer(Modifier.height(12.dp))
            if (routines.isEmpty()) {
                Text(
                    text = "Nothing saved yet. Try saying \u201Csave this as routine message mom\u201D, then \u201Crun routine message mom\u201D.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 21.sp
                )
            } else {
                routines.forEachIndexed { index, routine ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = routine.name.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = routine.phrase,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 17.sp
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { pendingRoutineDelete = routine }) {
                            Text("Delete")
                        }
                    }
                    if (index != routines.lastIndex) {
                        Spacer(Modifier.height(6.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                BodyText("Say \u201Clist routines\u201D to hear them, or say the routine's name with \u201Crun routine\u201D.")
            }
        }

        FlowCard {
            SectionLabel("Privacy & data")
            Spacer(Modifier.height(10.dp))
            listOf(
                "Audio is handled by Android's speech recognizer only while you hold the bubble, or while hands-free is on and listening.",
                "Screen frames are read on-device, kept in memory, and never saved.",
                "Notifications are kept in memory for this session only.",
                "History stores command summaries, never transcripts, audio, or screen text.",
                "AI requests go only to the endpoint you configure."
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
            BodyText("Turn the bubble off, or stop screen access in Setup, to end everything immediately.")
        }

        FlowCard {
            SectionLabel("History")
            Spacer(Modifier.height(6.dp))
            BodyText("Command summaries are stored only on this device, up to the last 100 entries. Audio and screen text are never saved to history.")
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { confirmClear = true },
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(15.dp)
            ) {
                Text("Clear history")
            }
        }

        Text(
            text = "FlowBubble ${BuildConfig.VERSION_NAME} \u00B7 No analytics, no background uploads",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(Modifier.height(4.dp))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("This removes every saved command summary from this device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearHistory()
                        confirmClear = false
                    }
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }

    pendingRoutineDelete?.let { routine ->
        AlertDialog(
            onDismissRequest = { pendingRoutineDelete = null },
            title = { Text("Delete routine?") },
            text = { Text("\u201C${routine.name}\u201D will be removed from this device. You can save it again later.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        RoutineStore.delete(context, routine.name)
                        routines = RoutineStore.list(context)
                        pendingRoutineDelete = null
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRoutineDelete = null }) { Text("Cancel") }
            }
        )
    }
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

    FlowCard {
        SectionLabel("Speech engine")
        Spacer(Modifier.height(6.dp))
        BodyText(
            "Which recognizer turns your voice into text. The on-device model is more accurate " +
                "offline and never sends audio away, but it is a separate download per language."
        )
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EngineChoice.entries.forEach { option ->
                FilterChip(
                    selected = choice == option,
                    onClick = {
                        choice = option
                        Prefs.setEngineChoice(context, option)
                    },
                    label = { Text(option.label) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (chip, chipColor) = when (status) {
                ModelStatus.READY -> "On-device model ready" to ReadyGreen
                ModelStatus.INSTALLABLE -> "Model downloadable" to NeedAmber
                ModelStatus.INSTALLING -> "Download in progress" to NeedAmber
                ModelStatus.UNAVAILABLE -> "Not available on this device" to WarnRed
                ModelStatus.FAILED -> "Install failed" to WarnRed
                ModelStatus.UNKNOWN -> "Checking…" to Color(0xFF93A4B5)
            }
            StatusChip(chip, chipColor)
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = { probeToken++ },
                enabled = !busy
            ) { Text("Check again") }
        }
        Spacer(Modifier.height(4.dp))
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
                "FlowBubble uses your device's recognizer. Nothing new is downloaded."
            else ->
                "Automatic uses the on-device model whenever it is ready, and the system recognizer otherwise."
        }
        BodyText(note)
        if (status == ModelStatus.INSTALLABLE) {
            Spacer(Modifier.height(12.dp))
            FlowPrimaryButton(
                text = "Download the on-device model",
                enabled = !busy,
                onClick = { installToken++ }
            )
            Spacer(Modifier.height(8.dp))
            BodyText(
                "Google Play services performs the download. FlowBubble never starts it on its own, " +
                    "and turning the engine back to System recognizer leaves the model untouched."
            )
        }
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
        Text(
            text = "History",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        BodyText("A local record of what FlowBubble understood and did. Full transcripts, audio, and screen text are not stored.")

        if (history.isEmpty()) {
            FlowCard {
                SectionLabel("Nothing yet")
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Hold the bubble and speak. Your completed actions will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 21.sp
                )
            }
        } else {
            FlowCard {
                SectionLabel("${history.size} recent ${if (history.size == 1) "entry" else "entries"}")
                Spacer(Modifier.height(12.dp))
                history.forEachIndexed { index, entry ->
                    HistoryRow(entry)
                    if (index != history.lastIndex) {
                        Spacer(Modifier.height(14.dp))
                    }
                }
            }
            OutlinedButton(
                onClick = { confirmClear = true },
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(15.dp)
            ) {
                Text("Clear history")
            }
        }
        Spacer(Modifier.height(4.dp))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("This removes every saved command summary from this device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearHistory()
                        confirmClear = false
                    }
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry) {
    val time = remember(entry.time) {
        SimpleDateFormat("d MMM \u00B7 HH:mm", Locale.getDefault()).format(Date(entry.time))
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.kind.ifBlank { "Action" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = entry.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 20.sp
            )
        }
        Spacer(Modifier.width(10.dp))
        StatusChip(
            text = entry.status.replaceFirstChar { it.uppercase() }.ifBlank { "Logged" },
            color = statusColor(entry.status)
        )
    }
}

private fun statusColor(status: String): Color = when (status.lowercase()) {
    "done" -> ReadyGreen
    "awaiting user", "requested", "copied", "unverified", "setup needed" -> NeedAmber
    "failed", "not found", "permission missing", "unsupported" -> WarnRed
    else -> Color(0xFF93A4B5)
}
