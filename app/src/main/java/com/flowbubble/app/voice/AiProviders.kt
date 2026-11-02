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

data class ProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val model: String,
    val models: String
)

/**
 * Known OpenAI-compatible endpoints. Every one of these is a base URL that only needs
 * `/chat/completions` appended, so the client itself stays provider-agnostic.
 */
object AiProviders {
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

    fun find(id: String?): ProviderPreset? =
        all.firstOrNull { it.id == id?.trim()?.lowercase(Locale.US) }

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
    val model: String
) {
    val preset: ProviderPreset? get() = AiProviders.find(providerId)
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

data class ResolvedAi(val profile: AiProfile, val apiKey: String, val endpoint: String)

object AiConfig {

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

    fun validate(baseUrl: String, model: String, apiKey: String): List<String> {
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

    fun isUsable(profile: AiProfile, apiKey: String): Boolean =
        validate(profile.baseUrl, profile.model, apiKey).isEmpty()

    /**
     * Which profile serves this role. A role pointed at a broken profile returns nothing rather
     * than quietly using another provider, because another provider means another company receiving
     * the text.
     */
    fun resolve(settings: AiSettings, role: AiRole): ResolvedAi? {
        val assignedId = settings.roles[role.id]
        val id = if (assignedId != null && settings.profiles.any { it.id == assignedId }) {
            assignedId
        } else {
            settings.defaultProfileId
        }
        val profile = settings.profiles.firstOrNull { it.id == id } ?: return null
        val key = settings.keys[profile.id].orEmpty()
        if (!isUsable(profile, key)) return null
        return ResolvedAi(profile, key, chatEndpoint(profile.baseUrl))
    }

    fun anyUsable(settings: AiSettings): Boolean =
        settings.profiles.any { isUsable(it, settings.keys[it.id].orEmpty()) }

    /** The host named in a consent dialog: where the text goes, never the key. */
    fun displayEndpoint(settings: AiSettings, role: AiRole): String {
        val resolved = resolve(settings, role) ?: return ""
        return hostOf(resolved.profile.baseUrl)
    }

    fun maskKey(key: String): String {
        val trimmed = key.trim()
        return when {
            trimmed.isEmpty() -> ""
            trimmed.length <= 4 -> "••••"
            else -> "••••${trimmed.takeLast(4)}"
        }
    }

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
