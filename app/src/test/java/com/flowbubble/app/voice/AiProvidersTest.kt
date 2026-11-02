package com.flowbubble.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The provider layer has to answer three questions without touching the network: is this endpoint a
 * usable URL, may it be used without a key, and which profile serves this role. Each test below pins
 * one of those answers, because every one of them decides whether a user's text leaves the device.
 */
class AiProvidersTest {

    private fun profile(
        id: String,
        baseUrl: String,
        model: String = "test-model",
        providerId: String = "custom"
    ) = AiProfile(id, id, providerId, baseUrl, model)

    private fun settings(
        vararg profiles: AiProfile,
        keys: Map<String, String> = emptyMap(),
        roles: Map<String, String> = emptyMap(),
        default: String = ""
    ) = AiSettings(
        profiles = profiles.toList(),
        keys = keys,
        roles = roles,
        defaultProfileId = default.ifBlank { profiles.firstOrNull()?.id ?: "" }
    )

    // ---- registry ----

    @Test
    fun `every hosted preset is a valid endpoint with a model`() {
        for (preset in AiProviders.all.filter { it.id != "custom" }) {
            assertEquals(
                "${preset.label} should need no correction: ",
                emptyList<String>(),
                AiConfig.validate(preset.baseUrl, preset.model, "test-key")
            )
        }
    }

    @Test
    fun `groq uses the openai compatible path`() {
        val groq = AiProviders.find("groq")
        assertNotNull(groq)
        assertEquals("https://api.groq.com/openai/v1", groq!!.baseUrl)
        assertEquals(
            "https://api.groq.com/openai/v1/chat/completions",
            AiConfig.chatEndpoint(groq.baseUrl)
        )
    }

    @Test
    fun `ollama is a local endpoint that needs no key`() {
        val ollama = AiProviders.find("ollama")
        assertNotNull(ollama)
        assertTrue(AiConfig.isLocal(ollama!!.baseUrl))
        assertEquals(emptyList<String>(), AiConfig.validate(ollama.baseUrl, "llama3.1", ""))
    }

    @Test
    fun `a preset is recognised from the url the user pasted`() {
        assertEquals("groq", AiProviders.matchBaseUrl("https://api.groq.com/openai/v1/")?.id)
        assertEquals("openai", AiProviders.matchBaseUrl("https://api.openai.com/v1")?.id)
        assertNull(AiProviders.matchBaseUrl("https://example.com/v1"))
        assertNull(AiProviders.matchBaseUrl(""))
    }

    // ---- endpoint normalisation ----

    @Test
    fun `one endpoint comes out of every base url shape`() {
        val expected = "https://api.groq.com/openai/v1/chat/completions"
        assertEquals(expected, AiConfig.chatEndpoint("https://api.groq.com/openai/v1"))
        assertEquals(expected, AiConfig.chatEndpoint("  https://api.groq.com/openai/v1/  "))
        assertEquals(expected, AiConfig.chatEndpoint("https://api.groq.com/openai/v1/chat/completions"))
        assertEquals(expected, AiConfig.chatEndpoint("api.groq.com/openai/v1"))
    }

    @Test
    fun `a missing scheme becomes https in the cloud and http on a local machine`() {
        assertTrue(AiConfig.normalizeBaseUrl("api.openai.com/v1").startsWith("https://"))
        assertTrue(AiConfig.normalizeBaseUrl("10.0.2.2:8080/v1").startsWith("http://"))
        assertTrue(AiConfig.normalizeBaseUrl("localhost:11434/v1").startsWith("http://"))
    }

    @Test
    fun `an endpoint that cannot be reached is rejected instead of guessed`() {
        assertEquals("", AiConfig.normalizeBaseUrl(""))
        assertEquals("", AiConfig.normalizeBaseUrl("not a url"))
        assertEquals("", AiConfig.normalizeBaseUrl("ftp://example.com/v1"))
        assertEquals("", AiConfig.normalizeBaseUrl("https://"))
        assertEquals("", AiConfig.chatEndpoint("not a url"))
    }

    @Test
    fun `a bare host still gets the standard path appended`() {
        assertEquals("https://example.com/chat/completions", AiConfig.chatEndpoint("example.com"))
    }

    // ---- keys and cleartext ----

    @Test
    fun `only a machine on the local network may go without a key`() {
        assertTrue(AiConfig.isLocal("http://localhost:11434/v1"))
        assertTrue(AiConfig.isLocal("http://127.0.0.1:8000/v1"))
        assertTrue(AiConfig.isLocal("http://[::1]:11434/v1"))
        assertTrue(AiConfig.isLocal("http://10.0.2.2:8080/v1"))
        assertTrue(AiConfig.isLocal("http://192.168.1.20:11434/v1"))
        assertTrue(AiConfig.isLocal("http://172.16.0.5:11434/v1"))
        assertFalse(AiConfig.isLocal("http://172.32.0.5:11434/v1"))
        assertTrue(AiConfig.isLocal("http://mybox.local:11434/v1"))
        assertFalse(AiConfig.isLocal("https://api.groq.com/openai/v1"))
        assertFalse(AiConfig.isLocal("http://8.8.8.8/v1"))
    }

    @Test
    fun `plain http reaches a machine on the local network but never a hosted provider`() {
        assertEquals(emptyList<String>(), AiConfig.validate("http://localhost:11434/v1", "llama3.1", ""))
        assertEquals(emptyList<String>(), AiConfig.validate("http://10.0.2.2:8080/v1", "mock-model", ""))
        assertEquals(
            emptyList<String>(),
            AiConfig.validate("http://192.168.1.20:11434/v1", "llama3.1", "")
        )
        val errors = AiConfig.validate("http://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "gsk-1")
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("https"))
    }

    @Test
    fun `a hosted provider without a key is refused with a reason`() {
        val errors = AiConfig.validate("https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "")
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("API key"))
    }

    @Test
    fun `a missing model name is refused`() {
        val errors = AiConfig.validate("https://api.groq.com/openai/v1", "  ", "gsk-1")
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("model"))
    }

    // ---- per-role resolution ----

    @Test
    fun `an assigned profile serves its role and the default serves the rest`() {
        val groq = profile("profile-1", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "groq")
        val ollama = profile("profile-2", "http://localhost:11434/v1", "llama3.1", "ollama")
        val settings = settings(
            groq,
            ollama,
            keys = mapOf("profile-1" to "gsk-1"),
            roles = mapOf(AiRole.Edit.id to "profile-2"),
            default = "profile-1"
        )

        val dictation = AiConfig.resolve(settings, AiRole.Dictation)
        assertEquals("profile-1", dictation!!.profile.id)
        assertEquals("gsk-1", dictation.apiKey)

        val edit = AiConfig.resolve(settings, AiRole.Edit)
        assertEquals("profile-2", edit!!.profile.id)
        assertEquals("", edit.apiKey)
        assertEquals("http://localhost:11434/v1/chat/completions", edit.endpoint)
    }

    @Test
    fun `an assigned profile that stopped working never switches provider silently`() {
        val groq = profile("profile-1", "https://api.groq.com/openai/v1")
        val ollama = profile("profile-2", "http://localhost:11434/v1")
        val settings = settings(
            groq,
            ollama,
            keys = emptyMap(),
            roles = mapOf(AiRole.Dictation.id to "profile-1"),
            default = "profile-1"
        )
        assertNull(AiConfig.resolve(settings, AiRole.Dictation))
        assertTrue(AiConfig.anyUsable(settings))
    }

    @Test
    fun `a role pointing at a deleted profile falls back to the default`() {
        val groq = profile("profile-1", "https://api.groq.com/openai/v1")
        val settings = settings(
            groq,
            keys = mapOf("profile-1" to "gsk-1"),
            roles = mapOf(AiRole.Summarize.id to "gone"),
            default = "profile-1"
        )
        assertEquals("profile-1", AiConfig.resolve(settings, AiRole.Summarize)!!.profile.id)
    }

    @Test
    fun `no profiles means nothing is resolved and nothing is configured`() {
        val settings = AiSettings()
        assertNull(AiConfig.resolve(settings, AiRole.Dictation))
        assertFalse(AiConfig.anyUsable(settings))
        assertEquals("", AiConfig.displayEndpoint(settings, AiRole.Dictation))
    }

    @Test
    fun `every role can be served`() {
        val one = profile("profile-1", "https://api.groq.com/openai/v1")
        val settings = settings(one, keys = mapOf("profile-1" to "gsk-1"))
        for (role in AiRole.values()) {
            assertNotNull("role ${role.id} has no model", AiConfig.resolve(settings, role))
        }
        assertEquals(AiRole.values().size, AiRole.values().distinctBy { it.id }.size)
    }

    @Test
    fun `the consent line names the host and never the key`() {
        val one = profile("profile-1", "https://api.groq.com/openai/v1")
        val settings = settings(one, keys = mapOf("profile-1" to "gsk-secret-value"))
        val shown = AiConfig.displayEndpoint(settings, AiRole.ScreenAnswer)
        assertEquals("api.groq.com", shown)
        assertFalse(shown.contains("gsk-secret-value"))
    }

    // ---- key display ----

    @Test
    fun `a stored key is shown only as its last characters`() {
        assertEquals("••••xyz9", AiConfig.maskKey("gsk_abc123xyz9"))
        assertEquals("••••", AiConfig.maskKey("abc"))
        assertEquals("", AiConfig.maskKey(""))
        assertFalse(AiConfig.maskKey("gsk_abc123xyz9").contains("abc12"))
    }

    // ---- migration and ids ----

    @Test
    fun `the single legacy config becomes one default profile`() {
        val migrated = AiConfig.migrate(
            baseUrl = "https://api.groq.com/openai/v1",
            apiKey = "gsk-1",
            model = "llama-3.3-70b-versatile"
        )
        assertEquals(1, migrated.profiles.size)
        assertEquals("groq", migrated.profiles[0].providerId)
        assertEquals(migrated.profiles[0].id, migrated.defaultProfileId)
        assertEquals("gsk-1", migrated.keys[migrated.profiles[0].id])
        assertTrue(migrated.roles.isEmpty())
        assertNotNull(AiConfig.resolve(migrated, AiRole.Dictation))
    }

    @Test
    fun `an untouched legacy config migrates to nothing`() {
        val migrated = AiConfig.migrate(baseUrl = "", apiKey = "   ", model = "gpt-4o-mini")
        assertTrue(migrated.profiles.isEmpty())
        assertNull(AiConfig.resolve(migrated, AiRole.Dictation))
    }

    @Test
    fun `new profile ids never collide`() {
        var settings = AiSettings()
        val ids = mutableListOf<String>()
        repeat(3) {
            val id = AiConfig.nextProfileId(settings)
            assertFalse(ids.contains(id))
            ids += id
            settings = settings.copy(profiles = settings.profiles + profile(id, "http://localhost:1/v1"))
        }
        assertEquals(3, ids.distinct().size)
    }
}
