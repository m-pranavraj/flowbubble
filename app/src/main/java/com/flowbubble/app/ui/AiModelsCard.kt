package com.flowbubble.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowbubble.app.core.Prefs
import com.flowbubble.app.voice.AiConfig
import com.flowbubble.app.voice.AiProfile
import com.flowbubble.app.voice.AiProviders
import com.flowbubble.app.voice.AiRole
import com.flowbubble.app.voice.AiSettings
import com.flowbubble.app.voice.LlmClient
import kotlinx.coroutines.launch

/**
 * Every AI setting in one place: the models FlowBubble can call, which one answers which job, and a
 * round trip that proves a model works before any of your text depends on it.
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

    FlowCard {
        SectionLabel("AI models")
        Spacer(Modifier.height(6.dp))
        BodyText(
            "Any OpenAI-compatible endpoint works: Groq, OpenRouter, Gemini, Mistral, DeepSeek, " +
                "Together, xAI, Cerebras, OpenAI, or a model on your own machine through Ollama or " +
                "LM Studio. Add as many as you like and choose which one answers each job."
        )
        Spacer(Modifier.height(4.dp))
        BodyText(
            "Nothing is sent until you ask for it. Dictation cleanup is the only automatic request, " +
                "and you can switch it off below."
        )
        Spacer(Modifier.height(12.dp))

        if (settings.profiles.isEmpty()) {
            BodyText("No model added yet. FlowBubble still cleans dictation, edits text, and runs " +
                "tasks with on-device rules without one.")
            Spacer(Modifier.height(10.dp))
        } else {
            settings.profiles.forEach { profile ->
                ProfileRow(
                    profile = profile,
                    settings = settings,
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
                Spacer(Modifier.height(10.dp))
            }
        }

        if (draft == null) {
            TextButton(onClick = { draft = newProfile(settings) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (settings.profiles.isEmpty()) "Add your first model" else "Add another model")
            }
        } else {
            ProfileEditor(
                draft = draft!!,
                settings = settings,
                onSave = { profile, apiKey ->
                    settings = putProfile(settings, profile, apiKey)
                    LlmClient.save(context, settings)
                    draft = null
                },
                onCancel = { draft = null }
            )
        }

        if (settings.profiles.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            SectionLabel("Which model answers what")
            Spacer(Modifier.height(6.dp))
            BodyText("A job with no choice uses your default model. Assigning a job to a model that " +
                "needs a key you removed turns that job off rather than quietly using another company.")
            AiRole.values().forEach { role ->
                RoleRow(
                    role = role,
                    settings = settings,
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

        Spacer(Modifier.height(14.dp))
        SwitchRow(
            title = "AI cleanup for dictation",
            detail = "Sends the transcribed words to your dictation model before inserting them.",
            checked = cleanup,
            onCheckedChange = {
                cleanup = it
                Prefs.setAiCleanupEnabled(context, it)
            }
        )
        Spacer(Modifier.height(12.dp))
        SwitchRow(
            title = "AI planning for free-form tasks",
            detail = "Lets the agent ask your planning model to work out unfamiliar multi-step tasks. " +
                "Every AI plan asks before sending visible screen text.",
            checked = planning,
            onCheckedChange = {
                planning = it
                Prefs.setAgentLlmEnabled(context, it)
            }
        )
        if (!LlmClient.keysAreEncrypted(context)) {
            Spacer(Modifier.height(12.dp))
            BodyText(
                "This device could not open its encrypted key storage, so keys are kept in ordinary " +
                    "app storage. Use a restricted key, or a local model that needs none."
            )
        }
    }
}

@Composable
private fun ProfileRow(
    profile: AiProfile,
    settings: AiSettings,
    isDefault: Boolean,
    onEdit: () -> Unit,
    onMakeDefault: () -> Unit,
    onDelete: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var testing by remember(profile.id) { mutableStateOf(false) }
    var outcome by remember(profile.id) { mutableStateOf<String?>(null) }
    val apiKey = settings.keys[profile.id].orEmpty()
    val problems = AiConfig.validate(profile.baseUrl, profile.model, apiKey)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.label + if (isDefault) "  (default)" else "",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (isDefault) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${profile.model} \u00B7 ${AiConfig.hostOf(profile.baseUrl)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp
                )
                Text(
                    text = when {
                        apiKey.isNotBlank() -> "Key ${AiConfig.maskKey(apiKey)}"
                        AiConfig.isLocal(profile.baseUrl) -> "No key needed on your own machine"
                        else -> "Needs an API key"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (problems.isEmpty() || AiConfig.isLocal(profile.baseUrl)) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    lineHeight = 17.sp
                )
                problems.forEach { problem ->
                    Text(
                        text = problem,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        lineHeight = 17.sp
                    )
                }
                outcome?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (text.startsWith("Worked")) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        lineHeight = 17.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                enabled = !testing && problems.isEmpty(),
                onClick = {
                    testing = true
                    outcome = "Asking ${profile.label}\u2026"
                    val config = LlmClient.Config(profile.baseUrl, apiKey, profile.model, profile.label)
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
            ) { Text(if (testing) "Testing\u2026" else "Test") }
            TextButton(onClick = onEdit) { Text("Edit") }
            if (!isDefault) {
                TextButton(onClick = onMakeDefault) { Text("Default") }
            }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

@Composable
private fun ProfileEditor(
    draft: AiProfile,
    settings: AiSettings,
    onSave: (AiProfile, String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var label by remember(draft.id) { mutableStateOf(draft.label) }
    var providerId by remember(draft.id) { mutableStateOf(draft.providerId) }
    var baseUrl by remember(draft.id) { mutableStateOf(draft.baseUrl) }
    var model by remember(draft.id) { mutableStateOf(draft.model) }
    var keyInput by remember(draft.id) { mutableStateOf("") }
    var dropKey by remember(draft.id) { mutableStateOf(false) }
    var providerMenu by remember { mutableStateOf(false) }
    var errors by remember(draft.id) { mutableStateOf(listOf<String>()) }

    val storedKey = settings.keys[draft.id].orEmpty()
    val effectiveKey = when {
        dropKey -> ""
        keyInput.isNotBlank() -> keyInput.trim()
        else -> storedKey
    }
    val candidate = draft.copy(
        label = label.ifBlank { AiProviders.find(providerId)?.label ?: "Model" }.trim(),
        providerId = providerId,
        baseUrl = baseUrl.trim(),
        model = model.trim()
    )

    Spacer(Modifier.height(6.dp))
    Box(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { providerMenu = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Provider: ${AiProviders.find(providerId)?.label ?: "Custom endpoint"}")
        }
        DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
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
                        providerId = preset.id
                        if (preset.baseUrl.isNotEmpty()) baseUrl = preset.baseUrl
                        if (preset.model.isNotEmpty()) model = preset.model
                        val untouched = label.isBlank() || AiProviders.all.any { it.label == label }
                        if (untouched) label = preset.label
                    }
                )
            }
        }
    }

    OutlinedTextField(
        value = label,
        onValueChange = { label = it },
        label = { Text("Name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
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
        supportingText = AiProviders.find(providerId)?.models?.let { hint ->
            { Text(hint) }
        },
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
                    AiConfig.isLocal(baseUrl) && storedKey.isEmpty() -> "Leave blank for a model on your own machine"
                    storedKey.isNotEmpty() && keyInput.isEmpty() -> "Blank keeps the stored key"
                    else -> "Stored encrypted on this device"
                }
            )
        },
        modifier = Modifier.fillMaxWidth()
    )
    if (storedKey.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Stored ${AiConfig.maskKey(storedKey)}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { dropKey = !dropKey }) {
                Text(if (dropKey) "Keep it" else "Remove")
            }
        }
    }
    errors.forEach { problem ->
        Text(
            text = problem,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            lineHeight = 17.sp
        )
    }
    Spacer(Modifier.height(10.dp))
    FlowPrimaryButton(
        text = "Save model",
        onClick = {
            val problems = AiConfig.validate(candidate.baseUrl, candidate.model, effectiveKey)
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
private fun RoleRow(
    role: AiRole,
    settings: AiSettings,
    menuOpen: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit
) {
    val assignedId = settings.roles[role.id]
    val assigned = settings.profiles.firstOrNull { it.id == assignedId }
    val fallback = settings.profiles.firstOrNull { it.id == settings.defaultProfileId }
    val serving = AiConfig.resolve(settings, role)

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = role.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = when {
                    assigned != null -> "${assigned.label} \u00B7 ${assigned.model}"
                    serving != null -> "Default: ${fallback?.label ?: "none"}"
                    else -> "No model can answer this right now"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (serving == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 17.sp
            )
        }
        Spacer(Modifier.width(6.dp))
        Box {
            TextButton(onClick = onOpen) { Text("Change") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = onDismiss) {
                DropdownMenuItem(
                    text = { Text("Default model") },
                    onClick = { onPick(null) }
                )
                settings.profiles.forEach { profile ->
                    DropdownMenuItem(
                        text = { Text("${profile.label} \u00B7 ${profile.model}") },
                        onClick = { onPick(profile.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 17.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun newProfile(settings: AiSettings) = AiProfile(
    id = AiConfig.nextProfileId(settings),
    label = "",
    providerId = "custom",
    baseUrl = "",
    model = ""
)

private fun assign(settings: AiSettings, role: AiRole, profileId: String?): AiSettings =
    settings.copy(
        roles = if (profileId == null) settings.roles - role.id else settings.roles + (role.id to profileId)
    )

private fun putProfile(settings: AiSettings, profile: AiProfile, apiKey: String): AiSettings {
    val profiles = (settings.profiles.filterNot { it.id == profile.id } + profile).sortedBy { it.id }
    val ids = profiles.map { it.id }
    val defaultProfileId = settings.defaultProfileId.takeIf { it in ids } ?: profile.id
    return settings.copy(
        profiles = profiles,
        keys = settings.keys.filterKeys { it in ids } + mapOf(profile.id to apiKey),
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
