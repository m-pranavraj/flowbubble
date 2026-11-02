package com.flowbubble.app.voice

import java.net.URI
import java.util.Locale

/**
 * The jobs that can each be given their own model. Six roles, matching the six things FlowBubble
 * asks a model to do; a user can route them all to one cheap local model or spread them over
 * several providers.
 */
enum class AiRole(val id: String, val label: String) {
    Dictation("dictation", "Dictation cleanup"),
    Edit("edit", "Inline editing"),
    Summarize("summarize", "Screen summary"),
    ScreenAnswer("screen_answer", "Answer about the screen"),
    Translate("translate", "Translate the screen"),
    Plan("plan", "Agent planning");

    companion object {
        fun from(id: String?): AiRole? = values().firstOrNull { it.id == id }
    }
}

/**
 * Where a profile's model actually runs. [Hosted] is an OpenAI-compatible endpoint on somebody
 * else's computer, reached over the network with a base URL and usually a key. [OnDevice] is a
 * LiteRT-LM model file inside this app's private storage: no base URL, no key, and no network call,
 * so the text being processed never leaves the phone.
 */
enum class AiEngineKind {
    Hosted,
    OnDevice;

    companion object {
        fun from(raw: String?): AiEngineKind =
            if (raw?.trim()?.lowercase(Locale.US) == "on-device") OnDevice else Hosted
    }
}

data class ProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val model: String,
    val models: String,
    val engine: AiEngineKind = AiEngineKind.Hosted
)

/**
 * Known OpenAI-compatible endpoints. Every one of these is a base URL that only needs
 * `/chat/completions` appended, so the client itself stays provider-agnostic.
 */
object AiProviders {

    /** The pseudo-provider every on-device profile belongs to; it has no URL and no key. */
    const val ON_DEVICE_ID = "on-device"

    val all: List<ProviderPreset> = listOf(

        ProviderPreset(
            "openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini",
            "gpt-4o-mini, gpt-4.1-mini, gpt-4o"
        ),
        ProviderPreset(
            "groq", "Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile",
            "llama-3.3-70b-versatile, llama-3.1-8b-instant"
        ),
        ProviderPreset(
            "openrouter", "OpenRouter", "https://openrouter.ai/api/v1",
            "meta-llama/llama-3.3-70b-instruct",
            "meta-llama/llama-3.3-70b-instruct, openai/gpt-4o-mini, anthropic/claude-3.5-sonnet"
        ),
        ProviderPreset(
            "gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
            "gemini-2.5-flash", "gemini-2.5-flash, gemini-2.5-pro"
        ),
        ProviderPreset(
            "mistral", "Mistral", "https://api.mistral.ai/v1", "mistral-small-latest",
            "mistral-small-latest, mistral-large-latest, open-mistral-nemo"
        ),
        ProviderPreset(
            "deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-chat",
            "deepseek-chat, deepseek-reasoner"
        ),
        ProviderPreset(
            "together", "Together AI", "https://api.together.xyz/v1",
            "meta-llama/Llama-3.3-70B-Instruct-Turbo", "any Together model id"
        ),
        ProviderPreset("xai", "xAI Grok", "https://api.x.ai/v1", "grok-3-mini", "grok-4, grok-3-mini"),
        ProviderPreset(
            "cerebras", "Cerebras", "https://api.cerebras.ai/v1", "llama-3.1-8b",
            "llama-3.1-8b, qwen-3-32b"
        ),
        ProviderPreset(
            "ollama", "Ollama (local)", "http://localhost:11434/v1", "llama3.1",
            "any model name you have pulled"
        ),
        ProviderPreset(
            "lmstudio", "LM Studio (local)", "http://localhost:1234/v1", "qwen2.5-7b-instruct",
            "the model currently loaded in LM Studio"
        ),
        ProviderPreset("custom", "Custom endpoint", "", "", "whatever your server expects")
    )

    fun find(id: String?): ProviderPreset? {
        val key = id?.trim()?.lowercase(Locale.US) ?: return null
        if (key == ON_DEVICE_ID) return onDevice
        return all.firstOrNull { it.id == key }
    }

    /**
     * The on-device engine as a picker entry. It is deliberately not in [all], because [all] is the
     * list of endpoints that must each normalise to a working `/chat/completions` URL; this one has
     * no URL at all. The settings screen lists it beside the hosted presets so local and hosted look
     * like peers rather than like a hidden extra.
     */
    val onDevice = ProviderPreset(
        id = ON_DEVICE_ID,
        label = "On this phone",
        baseUrl = "",
        model = "",
        models = "choose a downloaded model below",
        engine = AiEngineKind.OnDevice
    )

    /** The preset that owns this URL, so a pasted base URL keeps its label and model list. */
    fun matchBaseUrl(baseUrl: String): ProviderPreset? {
        val normalized = AiConfig.normalizeBaseUrl(baseUrl).lowercase(Locale.US)
        if (normalized.isEmpty()) return null
        return all.firstOrNull {
            it.id != "custom" && AiConfig.normalizeBaseUrl(it.baseUrl).lowercase(Locale.US) == normalized
        }
    }
}

data class AiProfile(
    val id: String,
    val label: String,
    val providerId: String,
    val baseUrl: String,
    val model: String,
    val engine: AiEngineKind = AiEngineKind.Hosted
) {
    val preset: ProviderPreset? get() = AiProviders.find(providerId)

    /** True when this profile is answered by a model file inside this app's own storage. */
    val isOnDevice: Boolean get() = engine == AiEngineKind.OnDevice

    /** For a local profile [model] is a [com.flowbubble.app.core.LocalModelSpec] id, not a server name. */
    val localModelId: String get() = model.trim()
}

/**
 * Everything the AI layer needs to decide where a request goes. [keys] is the only secret here; the
 * store keeps it out of the file the other fields live in, and nothing in this type is ever logged.
 */
data class AiSettings(
    val profiles: List<AiProfile> = emptyList(),
    val keys: Map<String, String> = emptyMap(),
    val roles: Map<String, String> = emptyMap(),
    val defaultProfileId: String = ""
)

data class ResolvedAi(val profile: AiProfile, val apiKey: String, val endpoint: String) {
    val isOnDevice: Boolean get() = profile.isOnDevice
}

object AiConfig {

    /** What a local profile shows wherever an endpoint would normally be named. */
    const val LOCAL_ENDPOINT_TEXT = "Runs on this phone — nothing leaves the device"

    /**
     * The same promise phrased so it drops into the middle of a consent sentence
     * ("this goes to your own phone — the text never leaves the device").
     */
    const val LOCAL_CONSENT_PHRASE = "your own phone — the text never leaves the device"

    fun normalizeBaseUrl(raw: String): String {
        val input = raw.trim()
        if (input.isEmpty()) return ""
        val hasScheme = input.contains("://")
        val body = if (hasScheme) input.substringAfter("://") else input
        val scheme = if (hasScheme) input.substringBefore("://").lowercase(Locale.US) else defaultScheme(body)
        if (scheme != "http" && scheme != "https") return ""
        if (body.isEmpty() || parseHost("$scheme://$body").isEmpty()) return ""
        return "$scheme://$body".trimEnd('/')
    }

    fun chatEndpoint(rawBaseUrl: String): String {
        val base = normalizeBaseUrl(rawBaseUrl)
        if (base.isEmpty()) return ""
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    fun hostOf(rawBaseUrl: String): String {
        val base = normalizeBaseUrl(rawBaseUrl)
        return if (base.isEmpty()) "" else parseHost(base)
    }

    /** A model running on the user's own machine or LAN needs no credentials and may use plain http. */
    fun isLocal(rawBaseUrl: String): Boolean = isLocalHost(hostOf(rawBaseUrl))

    /**
     * An on-device profile has neither a URL nor a key, so only the model id can be wrong here.
     * [localModelReady] is how the caller says whether that model file is actually on this phone;
     * it is a parameter rather than a filesystem read so this decision can be tested on a JVM.
     */
    fun validate(
        baseUrl: String,
        model: String,
        apiKey: String,
        engine: AiEngineKind = AiEngineKind.Hosted
    ): List<String> {
        if (engine == AiEngineKind.OnDevice) {
            return if (model.isBlank()) listOf("Pick a model to run on this phone.") else emptyList()
        }
        val base = normalizeBaseUrl(baseUrl)
        if (base.isEmpty()) return listOf("Enter a base URL like https://api.groq.com/openai/v1")
        val errors = mutableListOf<String>()
        if (model.isBlank()) errors += "Enter the model name this provider uses."
        if (apiKey.isBlank() && !isLocal(base)) {
            errors += "This provider needs an API key. Only a model running on your own machine " +
                "(localhost, 10.0.2.2, a LAN address, or a .local name) works without one."
        }
        if (base.startsWith("http://") && !isLocal(base)) {
            errors += "Unencrypted http:// would carry your text across the internet in the clear. " +
                "Use https:// for ${hostOf(base)}, or host the model on your own network."
        }
        return errors
    }

    fun validate(profile: AiProfile, apiKey: String): List<String> =
        validate(profile.baseUrl, profile.model, apiKey, profile.engine)

    fun isUsable(
        profile: AiProfile,
        apiKey: String,
        localModelReady: (String) -> Boolean = { true }
    ): Boolean {
        if (validate(profile, apiKey).isNotEmpty()) return false
        return !profile.isOnDevice || localModelReady(profile.localModelId)
    }

    fun isUsable(profile: AiProfile, apiKey: String): Boolean = isUsable(profile, apiKey) { true }

    /**
     * Which profile serves this role. A role pointed at a broken profile returns nothing rather
     * than quietly using another provider, because another provider means another company receiving
     * the text. A local model that is not downloaded counts as broken for the same reason: the job
     * switches off instead of sending the text somewhere the user never chose.
     */
    fun resolve(
        settings: AiSettings,
        role: AiRole,
        localModelReady: (String) -> Boolean = { true }
    ): ResolvedAi? {
        val assignedId = settings.roles[role.id]
        val id = if (assignedId != null && settings.profiles.any { it.id == assignedId }) {
            assignedId
        } else {
            settings.defaultProfileId
        }
        val profile = settings.profiles.firstOrNull { it.id == id } ?: return null
        // A local profile keeps no key: whatever was stored for it is ignored and never sent.
        val key = if (profile.isOnDevice) "" else settings.keys[profile.id].orEmpty()
        if (!isUsable(profile, key, localModelReady)) return null
        return ResolvedAi(profile, key, chatEndpoint(profile.baseUrl))
    }

    fun resolve(settings: AiSettings, role: AiRole): ResolvedAi? =
        resolve(settings, role) { true }

    fun anyUsable(settings: AiSettings, localModelReady: (String) -> Boolean = { true }): Boolean =
        settings.profiles.any { isUsable(it, settings.keys[it.id].orEmpty(), localModelReady) }

    fun anyUsable(settings: AiSettings): Boolean = anyUsable(settings) { true }

    /**
     * Where this role's text goes, in the user's own terms: a hostname for a hosted profile, and
     * the flat statement that nothing leaves the phone for a local one.
     */
    fun displayEndpoint(settings: AiSettings, role: AiRole): String {
        val resolved = resolve(settings, role) ?: return ""
        return if (resolved.isOnDevice) {
            LOCAL_ENDPOINT_TEXT
        } else {
            hostOf(resolved.profile.baseUrl)
        }
    }

    /**
     * The same answer shaped to sit inside a consent sentence. Held apart from [displayEndpoint]
     * only because consent text reads as prose while settings reads as a label.
     */
    fun consentEndpoint(settings: AiSettings, role: AiRole): String {
        val resolved = resolve(settings, role) ?: return ""
        return if (resolved.isOnDevice) LOCAL_CONSENT_PHRASE else hostOf(resolved.profile.baseUrl)
    }

    fun maskKey(key: String): String {
        val trimmed = key.trim()
        return when {
            trimmed.isEmpty() -> ""
            trimmed.length <= 4 -> "••••"
            else -> "••••${trimmed.takeLast(4)}"
        }
    }

    /** A local profile has no key to show, so it says so instead of rendering an empty mask. */
    fun keyLine(profile: AiProfile, apiKey: String): String = when {
        profile.isOnDevice -> "No key, no account, no network"
        apiKey.isNotBlank() -> "Key ${maskKey(apiKey)}"
        isLocal(profile.baseUrl) -> "No key needed on your own machine"
        else -> "Needs an API key"
    }

    /** What a profile row shows where a hosted row shows a hostname. */
    fun endpointLine(profile: AiProfile): String =
        if (profile.isOnDevice) LOCAL_ENDPOINT_TEXT else hostOf(profile.baseUrl)

    /** A profile for a model file on this phone: no base URL, no key, engine marked local. */
    fun onDeviceProfile(id: String, label: String, modelId: String): AiProfile = AiProfile(
        id = id,
        label = label,
        providerId = AiProviders.ON_DEVICE_ID,
        baseUrl = "",
        model = modelId,
        engine = AiEngineKind.OnDevice
    )

    /** The pre-multi-profile single endpoint, lifted into one default profile. */
    fun migrate(baseUrl: String, apiKey: String, model: String): AiSettings {
        if (baseUrl.isBlank() && apiKey.isBlank()) return AiSettings()
        val preset = AiProviders.matchBaseUrl(baseUrl)
        val profile = AiProfile(
            id = "profile-1",
            label = preset?.label ?: "Default",
            providerId = preset?.id ?: "custom",
            baseUrl = baseUrl.trim(),
            model = model.ifBlank { preset?.model ?: "gpt-4o-mini" }
        )
        val keys = if (apiKey.isBlank()) emptyMap() else mapOf(profile.id to apiKey.trim())
        return AiSettings(listOf(profile), keys, emptyMap(), profile.id)
    }

    fun nextProfileId(settings: AiSettings): String {
        var next = (settings.profiles.mapNotNull { it.id.removePrefix("profile-").toIntOrNull() }
            .maxOrNull() ?: 0) + 1
        while (settings.profiles.any { it.id == "profile-$next" }) next++
        return "profile-$next"
    }

    private fun defaultScheme(body: String): String =
        if (isLocalHost(parseHost("http://$body"))) "http" else "https"

    private fun parseHost(url: String): String =
        runCatching {
            URI(url).host?.lowercase(Locale.US)?.trim('[', ']')
        }.getOrNull().orEmpty()

    private fun isLocalHost(host: String): Boolean =
        host.isNotEmpty() && (
            host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
                host == "::1" || host.startsWith("fe80") || host.startsWith("127.") ||
                isPrivateIpv4(host)
            )

    private fun isPrivateIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it < 0 || it > 255 }) return false
        return octets[0] == 10 ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && octets[1] in 16..31)
    }
}
