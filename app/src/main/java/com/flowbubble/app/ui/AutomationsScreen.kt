package com.flowbubble.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowbubble.app.MainActivity
import com.flowbubble.app.agent.AutomationEngine
import com.flowbubble.app.agent.AutomationRule
import com.flowbubble.app.agent.AutomationTrigger
import com.flowbubble.app.agent.TriggerKind
import com.flowbubble.app.core.AutomationStore
import com.flowbubble.app.service.AutomationReceiver
import com.flowbubble.app.ui.theme.BubbleShapes
import com.flowbubble.app.ui.theme.Ink
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TriggerKinds = listOf(
    TriggerKind.TIME to "At a time",
    TriggerKind.APP_OPENED to "When an app opens",
    TriggerKind.NOTIFICATION_MATCHES to "When a notification arrives",
    TriggerKind.PHRASE to "When you say"
)

/** A step placeholder that shows the vocabulary automations share with voice routines. */
private val StepExamples = listOf(
    "open YouTube",
    "set a timer for ten minutes",
    "read this screen",
    "turn on Do Not Disturb for one hour"
)

/**
 * Every saved automation, and the create and edit flow for one. Steps are typed as plain commands,
 * because an automation replays them through the same parser and the same confirmations as speech.
 */
@Composable
internal fun AutomationsScreen(
    bubbleRunning: Boolean,
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    var rules by remember { mutableStateOf(AutomationStore.list(context)) }
    var editing by remember { mutableStateOf<AutomationRule?>(null) }
    var pendingDelete by remember { mutableStateOf<AutomationRule?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = Ink.Void) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
        ) {
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
                AutomationHeader(bubbleRunning)
                NavigationRow(current = MainActivity.SCREEN_AUTOMATIONS, onNavigate = onNavigate)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ScreenIntro(
                    title = "Automations",
                    copy = "When something happens, do these steps. An automation runs only while the bubble is on, never while your screen is locked, and always asks you first before it sends, spends, or deletes anything."
                )

                if (!bubbleRunning) {
                    Notice(
                        message = "The bubble is off, so no automation will run. Turn it on in Home.",
                        tone = NeedAmber
                    )
                }

                val draft = editing
                if (draft == null) {
                    if (rules.isEmpty()) {
                        EmptyAutomations()
                    } else {
                        rules.forEach { rule ->
                            AutomationCard(
                                rule = rule,
                                onToggle = { enabled ->
                                    AutomationStore.setEnabled(context, rule.id, enabled)
                                    AutomationReceiver.reschedule(context)
                                    rules = AutomationStore.list(context)
                                },
                                onEdit = { editing = rule },
                                onDelete = { pendingDelete = rule }
                            )
                        }
                    }
                    FlowPrimaryButton(
                        text = "New automation",
                        onClick = { editing = AutomationStore.draft(TriggerKind.TIME) }
                    )
                } else {
                    EditorCard(
                        start = draft,
                        onSave = {
                            AutomationReceiver.reschedule(context)
                            rules = AutomationStore.list(context)
                            editing = null
                        },
                        onCancel = { editing = null }
                    )
                }
            }
        }
    }

    pendingDelete?.let { rule ->
        ConfirmDialog(
            title = "Delete automation?",
            message = "\u201C${rule.name}\u201D will be removed from this device. You can create it again later.",
            confirmLabel = "Delete",
            onConfirm = {
                AutomationStore.delete(context, rule.id)
                AutomationReceiver.reschedule(context)
                rules = AutomationStore.list(context)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

/** The same header the other screens use, kept here because this route sits outside the shared slab. */
@Composable
private fun AutomationHeader(bubbleRunning: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(BubbleShapes.Button)
                .background(
                    Brush.linearGradient(listOf(Ink.Lavender, Ink.LavenderDeep, Ink.Burgundy))
                )
                .border(1.dp, Ink.Lavender.copy(alpha = 0.4f), BubbleShapes.Button),
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
                    withStyle(SpanStyle(brush = Brush.horizontalGradient(listOf(Ink.Mist, Ink.Lavender)))) {
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
private fun AutomationCard(
    rule: AutomationRule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val accent = when {
        !rule.enabled -> Ink.Whisper
        rule.requiresConfirmation -> Ink.Burgundy
        else -> Ink.LavenderDeep
    }
    FlowCard(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = Ink.Mist
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = AutomationEngine.summary(rule),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink.Lavender,
                    lineHeight = 21.sp
                )
            }
            Spacer(Modifier.width(10.dp))
            FlowToggle(checked = rule.enabled, onCheckedChange = onToggle)
        }
        Spacer(Modifier.height(12.dp))
        Hairline()
        Spacer(Modifier.height(12.dp))
        rule.steps.forEachIndexed { index, step ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = "${index + 1}",
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Ink.Smoke)
                        .padding(top = 2.dp),
                    color = Ink.Faint,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = step,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink.Mist,
                    lineHeight = 21.sp
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(
                text = if (rule.requiresConfirmation) "Asks you first" else "Runs on its own",
                color = if (rule.requiresConfirmation) Ink.CrimsonSoft else ReadyGreen
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = lastLine(rule),
                style = MaterialTheme.typography.labelSmall,
                color = Ink.Whisper
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowGhostButton(text = "Edit", modifier = Modifier.weight(1f), onClick = onEdit)
            FlowGhostButton(text = "Delete", danger = true, modifier = Modifier.weight(1f), onClick = onDelete)
        }
    }
}

@Composable
private fun EditorCard(start: AutomationRule, onSave: (AutomationRule) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var rule by remember(start.id) { mutableStateOf(start) }
    var notice by remember(start.id) { mutableStateOf<String?>(null) }
    val unreplayable = AutomationEngine.unreplayableSteps(rule.steps)

    FlowCard(accent = Ink.Lavender) {
        SectionLabel(if (start.name.isBlank()) "New automation" else "Edit automation")
        Spacer(Modifier.height(12.dp))
        FieldRow(label = "Name", value = rule.name, placeholder = "Morning briefing") { name ->
            rule = rule.copy(name = name)
        }
        Spacer(Modifier.height(14.dp))
        SectionLabel("When")
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TriggerKinds.forEach { (kind, label) ->
                OptionRow(
                    label = label,
                    selected = rule.trigger.kind == kind,
                    onClick = {
                        rule = rule.copy(
                            trigger = AutomationTrigger(kind, AutomationTrigger.defaults(kind))
                        )
                    }
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        TriggerFields(rule) { trigger -> rule = rule.copy(trigger = trigger) }
        Spacer(Modifier.height(14.dp))
        Hairline()
        Spacer(Modifier.height(12.dp))
        SectionLabel("Do these steps")
        Spacer(Modifier.height(8.dp))
        BodyText("Say them the way you would speak them to the bubble. One step per line, run in order.")
        Spacer(Modifier.height(10.dp))
        rule.steps.forEachIndexed { index, step ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    FieldRow(
                        label = "Step ${index + 1}",
                        value = step,
                        placeholder = StepExamples[index % StepExamples.size],
                        onValueChange = { phrase ->
                            rule = rule.copy(steps = rule.steps.mapIndexed { i, old ->
                                if (i == index) phrase else old
                            })
                        }
                    )
                }
                Spacer(Modifier.width(8.dp))
                FlowGhostButton(
                    text = "Remove",
                    danger = true,
                    onClick = { rule = rule.copy(steps = rule.steps.filterIndexed { i, _ -> i != index }) }
                )
            }
        }
        if (rule.steps.size < AutomationEngine.MAX_STEPS) {
            FlowGhostButton(
                text = "Add a step",
                modifier = Modifier.fillMaxWidth(),
                onClick = { rule = rule.copy(steps = rule.steps + "") }
            )
        }
        if (unreplayable.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Notice(
                message = "Your Bubble has no command for ${unreplayable.size} of these steps, so it will ask you rather than guess.",
                tone = NeedAmber
            )
        }
        if (rule.requiresConfirmation) {
            Spacer(Modifier.height(12.dp))
            Notice(
                message = "A step sends, spends, or deletes something, so this automation asks you every time before it runs.",
                tone = Ink.Crimson
            )
        }
        notice?.let { message ->
            Spacer(Modifier.height(12.dp))
            Notice(message = message, tone = WarnRed)
        }
        Spacer(Modifier.height(18.dp))
        FlowPrimaryButton(
            text = "Save automation",
            enabled = rule.name.isNotBlank() && rule.steps.any { it.isNotBlank() },
            onClick = {
                val cleaned = rule.copy(steps = rule.steps.filter { it.isNotBlank() })
                val result = AutomationStore.save(context, cleaned)
                when (result) {
                    is AutomationStore.SaveResult.Saved -> onSave(result.rule)
                    is AutomationStore.SaveResult.Rejected -> notice = result.reason
                }
            }
        )
        Spacer(Modifier.height(10.dp))
        FlowGhostButton(text = "Cancel", modifier = Modifier.fillMaxWidth(), onClick = onCancel)
    }
}

@Composable
private fun TriggerFields(rule: AutomationRule, onChange: (AutomationTrigger) -> Unit) {
    val trigger = rule.trigger
    fun set(key: String, value: String) =
        onChange(trigger.copy(params = trigger.params + (key to value)))

    when (trigger.kind) {
        TriggerKind.TIME -> {
            FieldRow(label = "Time", value = trigger.param(AutomationTrigger.AT), placeholder = "07:30") {
                set(AutomationTrigger.AT, it)
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(
                title = "Every day",
                detail = "Off means it runs once at that time and then stops.",
                trailing = {
                    FlowToggle(
                        checked = trigger.param(AutomationTrigger.DAILY, "true").toBooleanStrictOrNull() != false,
                        onCheckedChange = { set(AutomationTrigger.DAILY, it.toString()) }
                    )
                }
            )
        }
        TriggerKind.APP_OPENED -> FieldRow(
            label = "App",
            value = trigger.param(AutomationTrigger.PACKAGE),
            placeholder = "Spotify, or com.spotify.music"
        ) { set(AutomationTrigger.PACKAGE, it) }
        TriggerKind.PHRASE -> FieldRow(
            label = "Phrase",
            value = trigger.param(AutomationTrigger.PHRASE),
            placeholder = "movie time"
        ) { set(AutomationTrigger.PHRASE, it) }
        TriggerKind.NOTIFICATION_MATCHES -> {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FieldRow(
                    label = "From",
                    value = trigger.param(AutomationTrigger.SENDER),
                    placeholder = "WhatsApp, or com.google.android.gms"
                ) { set(AutomationTrigger.SENDER, it) }
                FieldRow(
                    label = "Title contains",
                    value = trigger.param(AutomationTrigger.TITLE),
                    placeholder = "Delivery"
                ) { set(AutomationTrigger.TITLE, it) }
                FieldRow(
                    label = "Text contains",
                    value = trigger.param(AutomationTrigger.BODY),
                    placeholder = "arriving today"
                ) { set(AutomationTrigger.BODY, it) }
            }
            Spacer(Modifier.height(10.dp))
            BodyText("Your Bubble matches the notification without storing or reading it back to you.")
        }
    }
}

@Composable
private fun FieldRow(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.4.sp,
            color = Ink.Lavender
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(BubbleShapes.Field)
                .background(Ink.Onyx)
                .border(1.dp, Ink.Veil, BubbleShapes.Field)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (value.isEmpty()) {
                Text(text = placeholder, style = MaterialTheme.typography.bodyMedium, color = Ink.Whisper)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = TextStyle(
                    color = Ink.Mist,
                    fontSize = 16.sp,
                    lineHeight = 22.sp
                ),
                cursorBrush = Brush.verticalGradient(listOf(Ink.Lavender, Ink.Crimson)),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
        }
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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

@Composable
private fun EmptyAutomations() {
    FlowCard(accent = Ink.Lavender) {
        SectionLabel("Nothing scheduled")
        Spacer(Modifier.height(14.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .clip(BubbleShapes.Field)
                .background(Brush.verticalGradient(listOf(Ink.Veil.copy(alpha = 0.35f), Ink.Obsidian)))
                .border(1.dp, Ink.Lavender.copy(alpha = 0.14f), BubbleShapes.Field),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Ink.LavenderDeep, Ink.Burgundy)))
                    .border(1.dp, Ink.Lavender.copy(alpha = 0.35f), CircleShape)
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = "An automation waits for one thing \u2014 a time, an app, a notification, or a phrase \u2014 " +
                "then runs the steps you wrote. Make one and it takes over from there.",
            style = MaterialTheme.typography.bodyMedium,
            color = Ink.Faint,
            lineHeight = 21.sp
        )
    }
}

/** The tab row every screen shares, kept here because Automations is reached from outside the app too. */
@Composable
private fun NavigationRow(current: String, onNavigate: (String) -> Unit) {
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
                            Brush.horizontalGradient(listOf(Ink.Smoke.copy(alpha = 0.35f), Ink.Veil.copy(alpha = 0.35f)))
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
private fun Hairline() {
    Box(
        modifier = Modifier
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
private fun Notice(message: String, tone: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BubbleShapes.Field)
            .background(Brush.horizontalGradient(listOf(tone.copy(alpha = 0.16f), Ink.Obsidian)))
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

/** The one destructive-confirmation surface, matching the settings and history screens. */
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
            Text(text = title, style = MaterialTheme.typography.titleLarge, color = Ink.Mist)
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
                Text(text = confirmLabel, color = Ink.Crimson, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel", color = Ink.Lavender, fontWeight = FontWeight.SemiBold)
            }
        }
    )
}

private fun lastLine(rule: AutomationRule): String {
    val time = SimpleDateFormat("d MMM \u00B7 HH:mm", Locale.getDefault())
    return when {
        rule.lastResult.isBlank() -> "never run"
        rule.lastRunAtMillis > 0L -> "${rule.lastResult} \u00B7 ${time.format(Date(rule.lastRunAtMillis))}"
        else -> rule.lastResult
    }
}
