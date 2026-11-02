package com.flowbubble.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowbubble.app.core.LocalDownloadEvent
import com.flowbubble.app.core.LocalModelCatalog
import com.flowbubble.app.core.LocalModelSpec
import com.flowbubble.app.core.LocalModelStatus
import com.flowbubble.app.core.LocalModelStore
import com.flowbubble.app.core.LocalVerdict
import com.flowbubble.app.ui.theme.BubbleShapes
import com.flowbubble.app.ui.theme.Ink
import com.flowbubble.app.voice.AiConfig
import com.flowbubble.app.voice.AiEngineKind
import com.flowbubble.app.voice.AiProfile
import com.flowbubble.app.voice.AiProviders
import com.flowbubble.app.voice.AiRole
import com.flowbubble.app.voice.AiSettings
import com.flowbubble.app.voice.LlmClient
import com.flowbubble.app.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every AI setting in one place: the models FlowBubble can call, whether each one lives on somebody
 * else's server or inside this phone, which one answers which job, and a round trip that proves a
 * model works before any of your text depends on it.
 */
@Composable
internal fun AiModelsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(LlmClient.settings(context)) }
    var cleanup by remember { mutableStateOf(Prefs.aiCleanupEnabled(context)) }
    var planning by remember { mutableStateOf(Prefs.agentLlmEnabled(context)) }
    var draft by remember { mutableStateOf<AiProfile?>(null) }
    var openRoleMenu by remember { mutableStateOf<String?>(null) }
    var localStatuses by remember { mutableStateOf<List<LocalModelStatus>>(emptyList()) }
    var progress by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var storageRefresh by remember { mutableStateOf(0) }

    // Reading the filesystem and the memory figures is not something to do while drawing a frame.
    LaunchedEffect(storageRefresh, settings) {
        val snapshot = withContext(Dispatchers.IO) { LocalModelStore.statuses(context) }
        localStatuses = snapshot
    }

    FlowCard {
        SectionLabel("AI models")
        Spacer(Modifier.height(8.dp))
        BodyText(
            "Two kinds of model answer here. A hosted one is an OpenAI-compatible endpoint on " +
                "someone else's computer: Groq, OpenRouter, Gemini, Mistral, DeepSeek, Together, " +
                "xAI, Cerebras, OpenAI, or a server on your own network through Ollama or LM Studio. " +
                "A local one is a model file inside this app's own storage, and your text never " +
                "leaves the phone."
        )
        Spacer(Modifier.height(8.dp))
        BodyText(
            "Nothing is sent until you ask for it. Dictation cleanup is the only automatic request, " +
                "and you can switch it off below. Assigning a job to a model that is not working " +
                "turns that job off instead of quietly using another company's model."
        )
        Spacer(Modifier.height(14.dp))

        if (settings.profiles.isEmpty()) {
            BodyText(
                "No model added yet. FlowBubble still cleans dictation, edits text, and runs tasks " +
                    "with on-device rules without one."
            )
            Spacer(Modifier.height(10.dp))
        } else {
            settings.profiles.forEach { profile ->
                ProfileRow(
                    profile = profile,
                    settings = settings,
                    localReady = { LocalModelStore.isReadyNow(context, it) },
                    isDefault = profile.id == settings.defaultProfileId,
                    onEdit = { draft = profile },
                    onMakeDefault = {
                        settings = settings.copy(defaultProfileId = profile.id)
                        LlmClient.save(context, settings)
                    },
                    onDelete = {
                        settings = dropProfile(settings, profile.id)
                        LlmClient.save(context, settings)
                        if (draft?.id == profile.id) draft = null
                    }
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        if (draft == null) {
            FlowGhostButton(
                text = if (settings.profiles.isEmpty()) "Add a model" else "Add another model",
                onClick = { draft = newProfile(settings) }
            )
        } else {
            ProfileEditor(
                draft = draft!!,
                settings = settings,
                localStatuses = localStatuses,
                onSave = { profile, apiKey ->
                    settings = putProfile(settings, profile, apiKey)
                    LlmClient.save(context, settings)
                    draft = null
                },
                onCancel = { draft = null }
            )
        }

        Spacer(Modifier.height(20.dp))
        OnDeviceSection(
            statuses = localStatuses,
            busy = busy,
            progress = progress,
            settings = settings,
            onAttach = { LocalModelStore.attach(context) },
            onDownload = { spec ->
                busy = spec.id
                progress = progress + (spec.id to "Starting…")
                scope.launch {
                    val result = LocalModelStore.download(context, spec.id) { event ->
                        progress = progress + (
                            spec.id to when (event) {
                                is LocalDownloadEvent.Started ->
                                    "0 of ${LocalModelCatalog.formatBytes(event.totalBytes)}"
                                is LocalDownloadEvent.Progress ->
                                    "${LocalModelCatalog.formatBytes(event.downloadedBytes)} of " +
                                        LocalModelCatalog.formatBytes(event.totalBytes)
                                LocalDownloadEvent.Completed -> "Finishing…"
                                is LocalDownloadEvent.Failed -> event.reason
                            }
                            )
                    }
                    result.onSuccess {
                        progress = progress - spec.id
                        if (settings.profiles.none { it.isOnDevice }) {
                            val profile = AiConfig.onDeviceProfile(
                                id = AiConfig.nextProfileId(settings),
                                label = spec.label,
                                modelId = spec.id
                            )
                            settings = putProfile(settings, profile, "")
                            LlmClient.save(context, settings)
                        }
                    }
                    result.onFailure { error ->
                        progress = progress + (spec.id to (error.message ?: "Download failed."))
                    }
                    busy = null
                    storageRefresh++
                }
            },
            onDelete = { spec ->
                scope.launch {
                    val stillUsed = settings.profiles.any { it.isOnDevice && it.localModelId == spec.id }
                    val removed = withContext(Dispatchers.IO) { LocalModelStore.delete(context, spec.id) }
                    if (removed && stillUsed) {
                        Toast.makeText(
                            context,
                            "${spec.label} is deleted, so its jobs are off until you download it again.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    storageRefresh++
                }
            }
        )

        if (settings.profiles.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            SectionLabel("Which model answers what")
            Spacer(Modifier.height(8.dp))
            BodyText(
                "A job with no choice uses your default model. A local model has to be downloaded " +
                    "before a job can use it."
            )
            Spacer(Modifier.height(6.dp))
            AiRole.values().forEach { role ->
                RoleRow(
                    role = role,
                    settings = settings,
                    localReady = { LocalModelStore.isReadyNow(context, it) },
                    menuOpen = openRoleMenu == role.id,
                    onOpen = { openRoleMenu = role.id },
                    onDismiss = { openRoleMenu = null },
                    onPick = { profileId ->
                        openRoleMenu = null
                        settings = assign(settings, role, profileId)
                        LlmClient.save(context, settings)
                    }
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SettingRow(
            title = "AI cleanup for dictation",
            detail = "Sends the transcribed words to your dictation model before inserting them. " +
                "When that model runs on this phone, the words stay here.",
            checked = cleanup,
            onCheckedChange = {
                cleanup = it
                Prefs.setAiCleanupEnabled(context, it)
            }
        )
        Spacer(Modifier.height(14.dp))
        SettingRow(
            title = "AI planning for free-form tasks",
            detail = "Lets the agent ask your planning model to work out unfamiliar multi-step tasks. " +
                "Every AI plan asks before sending visible screen text, and says where it is going.",
            checked = planning,
            onCheckedChange = {
                planning = it
                Prefs.setAgentLlmEnabled(context, it)
            }
        )
        if (!LlmClient.keysAreEncrypted(context)) {
            Spacer(Modifier.height(14.dp))
            BodyText(
                "This device could not open its encrypted key storage, so hosted keys are kept in " +
                    "ordinary app storage. Use a restricted key, or a model on this phone that needs " +
                    "no key at all."
            )
        }
    }
}

/** The on-device half of the settings: real sizes, real free space, and a button per model. */
@Composable
private fun OnDeviceSection(
    statuses: List<LocalModelStatus>,
    busy: String?,
    progress: Map<String, String>,
    settings: AiSettings,
    onAttach: () -> Unit,
    onDownload: (LocalModelSpec) -> Unit,
    onDelete: (LocalModelSpec) -> Unit
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Runs on this phone")
            Spacer(Modifier.width(10.dp))
            StatusChip(
                text = if (statuses.any { it.installed }) "Local model installed" else "No local model yet",
                color = if (statuses.any { it.installed }) ReadyGreen else NeedAmber
            )
        }
        Spacer(Modifier.height(8.dp))
        BodyText(
            "A model here is downloaded once, by you, and then answers without a key, an account, " +
                "or a network. The sizes below are the real published file sizes, and they are shown " +
                "before anything is fetched."
        )
        Spacer(Modifier.height(8.dp))
        BodyText(
            "What is honest about them: the smallest model that can actually route commands and tidy " +
                "text is 284 MB, the 165 MB one only makes embeddings, and a model that reasons about " +
                "a screen costs several gigabytes of storage and gigabytes of memory while it runs."
        )
        Spacer(Modifier.height(12.dp))

        if (statuses.isEmpty()) {
            BodyText("Reading this phone's storage…")
            onAttach()
        } else {
            val bytesUsed = statuses.filter { it.installed }.sumOf { it.onDiskBytes }
            BodyText(
                "FlowBubble's models use ${LocalModelCatalog.formatBytes(bytesUsed)}, and this " +
                    "phone has ${LocalModelCatalog.formatBytes(LocalModelStore.freeDiskBytes(context))} " +
                    "free."
            )
            Spacer(Modifier.height(10.dp))
            statuses.forEach { status ->
                ModelRow(
                    status = status,
                    progress = progress[status.spec.id],
                    busy = busy == status.spec.id,
                    usedBy = settings.profiles.filter { it.isOnDevice && it.localModelId == status.spec.id }
                        .map { it.label },
                    onDownload = { onDownload(status.spec) },
                    onDelete = { onDelete(status.spec) }
                )
                Spacer(Modifier.height(14.dp))
            }
        }
    }
}

@Composable
private fun ModelRow(
    status: LocalModelStatus,
    progress: String?,
    busy: Boolean,
    usedBy: List<String>,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    val spec = status.spec
    val tone = when {
        status.installed && status.verdict is LocalVerdict.Ready -> ReadyGreen
        status.canDownload -> Ink.Lavender
        else -> WarnRed
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = spec.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Ink.Mist,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = LocalModelCatalog.formatBytes(spec.sizeBytes),
                style = MaterialTheme.typography.labelMedium,
                color = tone
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = spec.oneLine,
            style = MaterialTheme.typography.bodySmall,
            color = Ink.Faint,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = status.verdict.reasonFor(spec),
            style = MaterialTheme.typography.bodySmall,
            color = tone,
            lineHeight = 17.sp
        )
        if (usedBy.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = "Assigned to ${usedBy.joinToString()}",
                style = MaterialTheme.typography.bodySmall,
                color = Ink.Lavender,
                lineHeight = 17.sp
            )
        }
        progress?.let {
            Spacer(Modifier.height(6.dp))
            DownloadMeter(it)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (status.installed) {
                FlowGhostButton(text = "Delete", danger = true, enabled = !busy, onClick = onDelete)
                if (status.verdict is LocalVerdict.Ready) {
                    StatusChip(text = "On this phone", color = ReadyGreen)
                }
            } else if (status.canDownload) {
                FlowPrimaryButton(
                    text = "Download ${LocalModelCatalog.formatBytes(spec.sizeBytes)}",
                    modifier = Modifier.height(44.dp),
                    enabled = !busy,
                    onClick = onDownload
                )
            }
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = spec.honestNote,
            style = MaterialTheme.typography.bodySmall,
            color = Ink.Whisper,
            lineHeight = 17.sp
        )
    }
}

@Composable
private fun DownloadMeter(label: String) {
    val fraction = Regex("(\\d+) of (\\d+)").find(label.replace(",", ""))
        ?.let { (it.groupValues[1].toLongOrNull() ?: 0L).toDouble() /
            ((it.groupValues[2].toLongOrNull() ?: 1L).coerceAtLeast(1L)) }
        ?: -1.0
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(CircleShape)
            .background(Ink.Smoke)
    ) {
        if (fraction >= 0.0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.toFloat().coerceIn(0.02f, 1f))
                    .fillMaxHeight()
                    .background(Ink.LavenderDeep)
            )
        }
    }
    Spacer(Modifier.height(3.dp))
    Text(text = label, style = MaterialTheme.typography.bodySmall, color = Ink.Lavender)
}

@Composable
private fun ProfileRow(
    profile: AiProfile,
    settings: AiSettings,
    localReady: (String) -> Boolean,
    isDefault: Boolean,
    onEdit: () -> Unit,
    onMakeDefault: () -> Unit,
    onDelete: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var testing by remember(profile.id) { mutableStateOf(false) }
    var outcome by remember(profile.id) { mutableStateOf<String?>(null) }
    val apiKey = if (profile.isOnDevice) "" else settings.keys[profile.id].orEmpty()
    val problems = AiConfig.validate(profile, apiKey) +
        if (profile.isOnDevice && !localReady(profile.localModelId)) {
            listOf("Not downloaded on this phone yet, so its jobs are off.")
        } else {
            emptyList()
        }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = profile.label + if (isDefault) "  · default" else "",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isDefault) FontWeight.SemiBold else FontWeight.Normal,
                color = Ink.Mist,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            StatusChip(
                text = if (profile.isOnDevice) "On this phone" else "Hosted",
                color = if (profile.isOnDevice) ReadyGreen else Ink.Lavender
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = "${profile.model}   ${AiConfig.endpointLine(profile)}",
            style = MaterialTheme.typography.bodySmall,
            color = Ink.Faint,
            lineHeight = 17.sp
        )
        Text(
            text = AiConfig.keyLine(profile, apiKey),
            style = MaterialTheme.typography.bodySmall,
            color = if (problems.isEmpty()) Ink.Faint else WarnRed,
            lineHeight = 17.sp
        )
        problems.forEach { problem ->
            Text(text = problem, style = MaterialTheme.typography.bodySmall, color = WarnRed, lineHeight = 17.sp)
        }
        outcome?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (text.startsWith("Worked")) ReadyGreen else WarnRed,
                lineHeight = 17.sp
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FlowGhostButton(
                text = if (testing) "Testing…" else "Test",
                enabled = !testing && problems.isEmpty(),
                onClick = {
                    testing = true
                    outcome = if (profile.isOnDevice) {
                        "Loading ${profile.label} on this phone…"
                    } else {
                        "Asking ${profile.label}…"
                    }
                    val config = LlmClient.Config(
                        baseUrl = profile.baseUrl,
                        apiKey = apiKey,
                        model = profile.model,
                        label = profile.label,
                        engine = profile.engine
                    )
                    scope.launch {
                        val started = System.currentTimeMillis()
                        LlmClient.test(config)
                            .onSuccess {
                                val ms = System.currentTimeMillis() - started
                                outcome = "Worked in ${ms}ms: ${it.take(40)}"
                            }
                            .onFailure { error ->
                                outcome = error.message ?: "The request did not go through"
                            }
                        testing = false
                    }
                }
            )
            FlowGhostButton(text = "Edit", onClick = onEdit)
            if (!isDefault) FlowGhostButton(text = "Default", onClick = onMakeDefault)
            FlowGhostButton(text = "Delete", danger = true, onClick = onDelete)
        }
        if (profile.isOnDevice && testing) {
            Spacer(Modifier.height(6.dp))
            BodyText(
                "The first local test loads the model into memory and can take several seconds. " +
                    "After that the same model stays warm."
            )
        }
    }
}

@Composable
private fun ProfileEditor(
    draft: AiProfile,
    settings: AiSettings,
    localStatuses: List<LocalModelStatus>,
    onSave: (AiProfile, String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var label by remember(draft.id) { mutableStateOf(draft.label) }
    var engine by remember(draft.id) { mutableStateOf(draft.engine) }
    var providerId by remember(draft.id) { mutableStateOf(draft.providerId) }
    var baseUrl by remember(draft.id) { mutableStateOf(draft.baseUrl) }
    var model by remember(draft.id) { mutableStateOf(draft.model) }
    var keyInput by remember(draft.id) { mutableStateOf("") }
    var dropKey by remember(draft.id) { mutableStateOf(false) }
    var providerMenu by remember { mutableStateOf(false) }
    var localMenu by remember { mutableStateOf(false) }
    var errors by remember(draft.id) { mutableStateOf(listOf<String>()) }

    val storedKey = settings.keys[draft.id].orEmpty()
    val effectiveKey = when {
        engine == AiEngineKind.OnDevice -> ""
        dropKey -> ""
        keyInput.isNotBlank() -> keyInput.trim()
        else -> storedKey
    }
    val candidate = AiProfile(
        id = draft.id,
        label = label.ifBlank {
            if (engine == AiEngineKind.OnDevice) {
                LocalModelCatalog.find(model)?.label ?: "On this phone"
            } else {
                AiProviders.find(providerId)?.label ?: "Model"
            }
        }.trim(),
        providerId = if (engine == AiEngineKind.OnDevice) AiProviders.ON_DEVICE_ID else providerId,
        baseUrl = if (engine == AiEngineKind.OnDevice) "" else baseUrl.trim(),
        model = model.trim(),
        engine = engine
    )

    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EngineTab(
            text = "Hosted endpoint",
            picked = engine == AiEngineKind.Hosted,
            modifier = Modifier.weight(1f),
            onClick = { engine = AiEngineKind.Hosted }
        )
        EngineTab(
            text = "On this phone",
            picked = engine == AiEngineKind.OnDevice,
            modifier = Modifier.weight(1f),
            onClick = {
                engine = AiEngineKind.OnDevice
                providerId = AiProviders.ON_DEVICE_ID
                baseUrl = ""
                if (LocalModelCatalog.find(model) == null) model = ""
            }
        )
    }
    Spacer(Modifier.height(12.dp))

    if (engine == AiEngineKind.OnDevice) {
        Box(modifier = Modifier.fillMaxWidth()) {
            FlowGhostButton(
                text = "Model: " + (LocalModelCatalog.find(model)?.label ?: "pick one"),
                onClick = { localMenu = true }
            )
            DropdownMenu(expanded = localMenu, onDismissRequest = { localMenu = false }) {
                LocalModelCatalog.roleAssignable.forEach { spec ->
                    val installed = localStatuses.firstOrNull { it.spec.id == spec.id }?.installed == true
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("${spec.label} · ${LocalModelCatalog.formatBytes(spec.sizeBytes)}")
                                Text(
                                    text = if (installed) "on this phone" else spec.oneLine,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            localMenu = false
                            model = spec.id
                            if (label.isBlank()) label = spec.label
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        LocalModelCatalog.find(model)?.let { spec ->
            BodyText(
                "Runs on this phone. No API key, no account, no network request: the text being " +
                    "cleaned or summarized never leaves the device."
            )
            Spacer(Modifier.height(6.dp))
            val found = localStatuses.firstOrNull { it.spec.id == spec.id }
            if (found?.installed != true) {
                BodyText(
                    "This one is ${LocalModelCatalog.formatBytes(spec.sizeBytes)} and is not " +
                        "downloaded yet. Download it in the section below, then it can answer."
                )
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { providerMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Provider: ${AiProviders.find(providerId)?.label ?: "Custom endpoint"}",
                    color = Ink.Lavender
                )
            }
            DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                listOf(AiProviders.onDevice)
                    .forEach { preset ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(preset.label)
                                    Text(
                                        text = "A model file inside this phone — no key, nothing uploaded",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                providerMenu = false
                                engine = AiEngineKind.OnDevice
                                providerId = preset.id
                                baseUrl = ""
                                model = ""
                            }
                        )
                    }
                AiProviders.all.forEach { preset ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(preset.label)
                                if (preset.baseUrl.isNotEmpty()) {
                                    Text(
                                        text = preset.baseUrl,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        onClick = {
                            providerMenu = false
                            engine = AiEngineKind.Hosted
                            providerId = preset.id
                            if (preset.baseUrl.isNotEmpty()) baseUrl = preset.baseUrl
                            if (preset.model.isNotEmpty()) model = preset.model
                            val untouched = label.isBlank() ||
                                AiProviders.all.any { it.label == label } ||
                                LocalModelCatalog.all.any { it.label == label }
                            if (untouched) label = preset.label
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            singleLine = true,
            supportingText = {
                val endpoint = AiConfig.chatEndpoint(baseUrl)
                Text(if (endpoint.isEmpty()) "ends at /chat/completions" else endpoint)
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model") },
            singleLine = true,
            supportingText = AiProviders.find(providerId)?.models?.let { hint -> { Text(hint) } },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = {
                Text(
                    when {
                        dropKey -> "The stored key will be removed"
                        AiConfig.isLocal(baseUrl) && storedKey.isEmpty() ->
                            "Leave blank for a model on your own machine"
                        storedKey.isNotEmpty() && keyInput.isEmpty() -> "Blank keeps the stored key"
                        else -> "Stored encrypted on this device"
                    }
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        if (storedKey.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Stored ${AiConfig.maskKey(storedKey)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.Faint
                )
                TextButton(onClick = { dropKey = !dropKey }) {
                    Text(if (dropKey) "Keep it" else "Remove")
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = label,
        onValueChange = { label = it },
        label = { Text("Name this model") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    errors.forEach { problem ->
        Spacer(Modifier.height(4.dp))
        Text(text = problem, style = MaterialTheme.typography.bodySmall, color = WarnRed, lineHeight = 17.sp)
    }
    Spacer(Modifier.height(12.dp))
    FlowPrimaryButton(
        text = "Save model",
        onClick = {
            val problems = AiConfig.validate(candidate, effectiveKey)
            if (problems.isNotEmpty()) {
                errors = problems
                Toast.makeText(context, problems.first(), Toast.LENGTH_LONG).show()
            } else {
                onSave(candidate, effectiveKey)
            }
        }
    )
    Spacer(Modifier.height(6.dp))
    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
}

@Composable
private fun EngineTab(
    text: String,
    picked: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(BubbleShapes.Button)
            .background(if (picked) Ink.Burgundy else Ink.Smoke.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = text,
                color = if (picked) Ink.Lavender else Ink.Faint,
                fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun RoleRow(
    role: AiRole,
    settings: AiSettings,
    localReady: (String) -> Boolean,
    menuOpen: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit
) {
    val assignedId = settings.roles[role.id]
    val assigned = settings.profiles.firstOrNull { it.id == assignedId }
    val fallback = settings.profiles.firstOrNull { it.id == settings.defaultProfileId }
    val serving = AiConfig.resolve(settings, role, localReady)

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = role.label,
                style = MaterialTheme.typography.titleSmall,
                color = Ink.Mist
            )
            Text(
                text = when {
                    assigned != null -> "${assigned.label} · ${assigned.model}" +
                        if (assigned.isOnDevice) " · on this phone" else ""
                    serving != null -> "Default: ${fallback?.label ?: "none"}"
                    else -> "No model can answer this right now"
                },
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    serving == null -> WarnRed
                    serving.isOnDevice -> ReadyGreen
                    else -> Ink.Faint
                },
                lineHeight = 17.sp
            )
        }
        Spacer(Modifier.width(6.dp))
        Box {
            TextButton(onClick = onOpen) { Text("Change", color = Ink.Lavender) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = onDismiss) {
                DropdownMenuItem(text = { Text("Default model") }, onClick = { onPick(null) })
                settings.profiles.forEach { profile ->
                    DropdownMenuItem(
                        text = {
                            Text("${profile.label} · ${profile.model}" +
                                if (profile.isOnDevice) " · local" else "")
                        },
                        onClick = { onPick(profile.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, detail: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    FlowRow(title = title, detail = detail, trailing = { FlowToggle(checked = checked, onCheckedChange = onCheckedChange) })
}

private fun newProfile(settings: AiSettings) = AiProfile(
    id = AiConfig.nextProfileId(settings),
    label = "",
    providerId = "custom",
    baseUrl = "",
    model = "",
    engine = AiEngineKind.Hosted
)

private fun assign(settings: AiSettings, role: AiRole, profileId: String?): AiSettings =
    settings.copy(
        roles = if (profileId == null) settings.roles - role.id else settings.roles + (role.id to profileId)
    )

private fun putProfile(settings: AiSettings, profile: AiProfile, apiKey: String): AiSettings {
    val profiles = (settings.profiles.filterNot { it.id == profile.id } + profile).sortedBy { it.id }
    val ids = profiles.map { it.id }
    val defaultProfileId = settings.defaultProfileId.takeIf { it in ids } ?: profile.id
    // A local profile carries no key, whatever was typed into the field.
    val key = if (profile.isOnDevice) "" else apiKey
    return settings.copy(
        profiles = profiles,
        keys = settings.keys.filterKeys { it in ids } + mapOf(profile.id to key),
        roles = settings.roles.filterValues { it in ids },
        defaultProfileId = defaultProfileId
    )
}

private fun dropProfile(settings: AiSettings, profileId: String): AiSettings {
    val profiles = settings.profiles.filterNot { it.id == profileId }
    return settings.copy(
        profiles = profiles,
        keys = settings.keys.filterKeys { it != profileId },
        roles = settings.roles.filterValues { it != profileId },
        defaultProfileId = if (settings.defaultProfileId == profileId) {
            profiles.firstOrNull()?.id ?: ""
        } else {
            settings.defaultProfileId
        }
    )
}
