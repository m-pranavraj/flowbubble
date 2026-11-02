package com.flowbubble.app.voice

import com.flowbubble.app.core.LocalModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An on-device profile has to be a first-class citizen of the routing layer: no key, no URL, and the
 * same "a broken profile serves nothing" rule that hosted profiles already obey. Each test here pins
 * one of those decisions, because the wrong answer either sends a user's text to a company they never
 * chose, or claims a piece of text stayed on the phone when it did not.
 *
 * Nothing here needs an Android runtime. Whether a model file is actually on the phone arrives as a
 * predicate, which is exactly why that decision was left injectable in [AiConfig].
 */
class LocalLlmRoutingTest {

    private val localModel = LocalModelCatalog.roleAssignable.first().id

    private fun hosted(
        id: String,
        baseUrl: String = "https://api.groq.com/openai/v1",
        model: String = "llama-3.3-70b-versatile",
        providerId: String = "groq"
    ) = AiProfile(id, id, providerId, baseUrl, model)

    private fun local(id: String, modelId: String = localModel) =
        AiConfig.onDeviceProfile(id, id, modelId)

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

    /** Records which model ids readiness was asked about, so a test can see what the resolver checked. */
    private class Probe(private val ready: Set<String>) {
        val asked = mutableListOf<String>()
        val predicate: (String) -> Boolean = { id -> asked += id; ready.contains(id) }
    }

    // ---- engine kind ----

    @Test
    fun `only the exact stored word makes a profile on-device`() {
        assertEquals(AiEngineKind.OnDevice, AiEngineKind.from("on-device"))
        assertEquals(AiEngineKind.OnDevice, AiEngineKind.from("  On-Device "))
        assertEquals(AiEngineKind.Hosted, AiEngineKind.from("hosted"))
        assertEquals(AiEngineKind.Hosted, AiEngineKind.from(""))
        assertEquals(AiEngineKind.Hosted, AiEngineKind.from(null))
        assertEquals(AiEngineKind.Hosted, AiEngineKind.from("local"))
    }

    @Test
    fun `a profile built the old way is still hosted`() {
        val legacy = AiProfile("profile-1", "Groq", "groq", "https://api.groq.com/openai/v1", "m")
        assertFalse(legacy.isOnDevice)
        assertEquals(AiEngineKind.Hosted, legacy.engine)
    }

    @Test
    fun `an on-device profile has no url and no model name of the server kind`() {
        val profile = local("profile-2")
        assertTrue(profile.isOnDevice)
        assertEquals("", profile.baseUrl)
        assertEquals(AiProviders.ON_DEVICE_ID, profile.providerId)
        assertEquals(AiEngineKind.OnDevice, profile.preset?.engine)
        assertEquals(localModel, profile.localModelId)
        assertEquals(localModel, AiConfig.onDeviceProfile("x", "x", "  $localModel  ").localModelId)
    }

    @Test
    fun `on-device is a picker entry but not an endpoint`() {
        val preset = AiProviders.find("on-device")
        assertNotNull(preset)
        assertEquals("", preset!!.baseUrl)
        assertEquals(AiEngineKind.OnDevice, preset.engine)
        assertFalse("it must not be treated as a base URL", AiProviders.all.any { it.engine == AiEngineKind.OnDevice })
        assertNull(AiProviders.matchBaseUrl(""))
        assertEquals("groq", AiProviders.find("GROQ")?.id)
    }

    // ---- validation ----

    @Test
    fun `a local profile needs no key and no url`() {
        assertEquals(emptyList<String>(), AiConfig.validate("", localModel, "", AiEngineKind.OnDevice))
        assertEquals(
            "a key left over from an earlier provider is ignored, not required",
            emptyList<String>(),
            AiConfig.validate("", localModel, "gsk-leftover", AiEngineKind.OnDevice)
        )
        assertTrue(AiConfig.validate(local("profile-1"), "").isEmpty())
    }

    @Test
    fun `a local profile without a chosen model is refused with a reason`() {
        val errors = AiConfig.validate("", "   ", "", AiEngineKind.OnDevice)
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("phone"))
        assertTrue(AiConfig.validate(local("profile-1", "  "), "").isNotEmpty())
    }

    @Test
    fun `a local profile is never measured against the url rules`() {
        // The hosted rules would call an empty base URL an error; a local profile must not inherit that.
        assertTrue(AiConfig.validate("not a url", localModel, "", AiEngineKind.Hosted).isNotEmpty())
        assertEquals(emptyList<String>(), AiConfig.validate("not a url", localModel, "", AiEngineKind.OnDevice))
    }

    // ---- readiness gating ----

    @Test
    fun `an undownloaded model makes its role resolve to nothing`() {
        val probe = Probe(emptySet())
        val settings = settings(local("profile-1"), keys = mapOf("profile-1" to "should-be-ignored"))
        assertNull(AiConfig.resolve(settings, AiRole.Dictation, probe.predicate))
        assertEquals("the resolver checks the model this profile names, by id", listOf(localModel), probe.asked)
        assertFalse(AiConfig.anyUsable(settings, probe.predicate))
    }

    @Test
    fun `a downloaded model resolves and still carries no key`() {
        val settings = settings(local("profile-1"), keys = mapOf("profile-1" to "gsk-stale-value"))
        val resolved = AiConfig.resolve(settings, AiRole.Edit) { id -> id == localModel }
        assertNotNull(resolved)
        assertTrue(resolved!!.isOnDevice)
        assertEquals("", resolved.apiKey)
        assertEquals("", resolved.endpoint)
    }

    @Test
    fun `a missing local model never falls through to the hosted default`() {
        val groq = hosted("profile-1")
        val phone = local("profile-2")
        val settings = settings(
            groq,
            phone,
            keys = mapOf("profile-1" to "gsk-1"),
            roles = mapOf(AiRole.Summarize.id to "profile-2"),
            default = "profile-1"
        )
        val ready = Probe(setOf("a-model-that-is-not-the-one-assigned"))
        assertNull(AiConfig.resolve(settings, AiRole.Summarize, ready.predicate))
        // The other roles keep their hosted profile, and the hosted route keeps its key.
        assertEquals("profile-1", AiConfig.resolve(settings, AiRole.Edit, ready.predicate)!!.profile.id)
        assertTrue(AiConfig.anyUsable(settings, ready.predicate))
    }

    @Test
    fun `one local model can serve every role once it is on the phone`() {
        val settings = settings(local("profile-1"))
        for (role in AiRole.values()) {
            assertNotNull("role ${role.id} has no local model", AiConfig.resolve(settings, role) { true })
        }
        assertTrue(AiConfig.anyUsable(settings) { true })
        assertFalse(AiConfig.isUsable(local("profile-1"), "") { false })
    }

    @Test
    fun `a local default does not hijack the role a user gave to a hosted profile`() {
        val groq = hosted("profile-1")
        val phone = local("profile-2")
        val settings = settings(
            phone,
            groq,
            keys = mapOf("profile-1" to "gsk-1"),
            roles = mapOf(AiRole.Translate.id to "profile-1"),
            default = "profile-2"
        )
        val translate = AiConfig.resolve(settings, AiRole.Translate) { it == localModel }
        assertEquals("profile-1", translate!!.profile.id)
        assertFalse(translate.isOnDevice)
        assertEquals("api.groq.com", AiConfig.hostOf(translate.profile.baseUrl))
        val summarize = AiConfig.resolve(settings, AiRole.Summarize) { it == localModel }
        assertEquals("profile-2", summarize!!.profile.id)
    }

    // ---- what the user is told ----

    @Test
    fun `a local role says the text stays on the phone`() {
        val settings = settings(local("profile-1"))
        assertEquals(
            AiConfig.LOCAL_ENDPOINT_TEXT,
            AiConfig.displayEndpoint(settings, AiRole.ScreenAnswer)
        )
        assertEquals(
            AiConfig.LOCAL_CONSENT_PHRASE,
            AiConfig.consentEndpoint(settings, AiRole.ScreenAnswer)
        )
        val shown = AiConfig.displayEndpoint(settings, AiRole.Dictation)
        assertFalse(shown.contains("http"))
        assertTrue(shown.contains("nothing leaves the device"))
        assertTrue(AiConfig.consentEndpoint(settings, AiRole.Dictation).contains("never leaves the device"))
    }

    @Test
    fun `an unconfigured role shows no destination rather than inventing one`() {
        // displayEndpoint only answers for a role that resolves; whether the model file is on this
        // phone is decided by the caller through resolve(), and the settings row shows download state.
        assertEquals("", AiConfig.displayEndpoint(AiSettings(), AiRole.Dictation))
        assertEquals("", AiConfig.consentEndpoint(AiSettings(), AiRole.Dictation))
    }

    @Test
    fun `a hosted role still shows the company it goes to`() {
        val settings = settings(hosted("profile-1"), keys = mapOf("profile-1" to "gsk-secret-value"))
        assertEquals("api.groq.com", AiConfig.displayEndpoint(settings, AiRole.Edit))
        assertEquals("api.groq.com", AiConfig.consentEndpoint(settings, AiRole.Edit))
    }

    @Test
    fun `a local profile is described as having no key at all`() {
        val line = AiConfig.keyLine(local("profile-1"), "gsk-stale-secret")
        assertEquals("No key, no account, no network", line)
        assertFalse(line.contains("gsk"))
        assertFalse(line.contains(AiConfig.maskKey("gsk-stale-secret")))
        assertEquals("Needs an API key", AiConfig.keyLine(hosted("profile-1"), ""))
        assertEquals("Key ••••2345", AiConfig.keyLine(hosted("profile-1"), "gsk-abc12345"))
        assertEquals(AiConfig.LOCAL_ENDPOINT_TEXT, AiConfig.endpointLine(local("profile-1")))
        assertEquals("api.groq.com", AiConfig.endpointLine(hosted("profile-1")))
    }

    @Test
    fun `an empty key is never masked into something that looks like a secret`() {
        assertEquals("", AiConfig.maskKey(""))
        assertEquals("", AiConfig.maskKey("   "))
        assertEquals("••••", AiConfig.maskKey("ab"))
        assertFalse(AiConfig.maskKey("abcd").contains("abcd"))
        assertFalse(AiConfig.keyLine(local("profile-1"), "abcd").contains("abcd"))
    }

    // ---- the seam every job goes through ----

    @Test
    fun `the client config carries the engine kind so complete and test can branch on it`() {
        val localConfig = LlmClient.Config("", "", localModel, "On this phone", AiEngineKind.OnDevice)
        assertTrue(localConfig.isOnDevice)
        val hostedConfig = LlmClient.Config("https://api.groq.com/openai/v1", "gsk-1", "llama-3.3-70b-versatile")
        assertFalse(hostedConfig.isOnDevice)
        assertEquals(AiEngineKind.Hosted, hostedConfig.engine)
        val empty = LlmClient.Config("", "", "", "")
        assertFalse("an unconfigured job must not be sent to the local engine", empty.isOnDevice)
    }

    @Test
    fun `a model id the catalog does not know is not a model`() {
        assertNull(LocalModelCatalog.find("gpt-4o-mini"))
        assertNull(LocalModelCatalog.find(null))
        assertFalse(AiConfig.isUsable(local("profile-1", "gpt-4o-mini"), "") { id -> LocalModelCatalog.find(id) != null })
    }

    // ---- the hosted path is untouched ----

    @Test
    fun `groq still normalises to the openai compatible url`() {
        val groq = AiProviders.find("groq")!!
        assertEquals("https://api.groq.com/openai/v1", groq.baseUrl)
        assertEquals(AiEngineKind.Hosted, groq.engine)
        assertEquals(
            "https://api.groq.com/openai/v1/chat/completions",
            AiConfig.chatEndpoint(groq.baseUrl)
        )
        assertEquals(
            "https://api.groq.com/openai/v1/chat/completions",
            AiConfig.chatEndpoint("  https://api.groq.com/openai/v1/  ")
        )
        assertEquals(
            "https://api.groq.com/openai/v1/chat/completions",
            AiConfig.chatEndpoint("api.groq.com/openai/v1")
        )
    }

    @Test
    fun `every hosted preset is still a valid endpoint with a model and a key`() {
        for (preset in AiProviders.all.filter { it.id != "custom" }) {
            assertEquals(
                "${preset.label} should need no correction: ",
                emptyList<String>(),
                AiConfig.validate(preset.baseUrl, preset.model, "test-key", preset.engine)
            )
        }
    }

    @Test
    fun `a hosted profile still refuses an unencrypted url and a missing key`() {
        assertTrue(AiConfig.validate("http://api.groq.com/openai/v1", "m", "gsk-1").isNotEmpty())
        assertTrue(AiConfig.validate("https://api.groq.com/openai/v1", "m", "").isNotEmpty())
        assertTrue(AiConfig.isUsable(hosted("profile-1"), "gsk-1"))
        assertFalse(AiConfig.isUsable(hosted("profile-1"), ""))
    }

    @Test
    fun `the legacy config still migrates to one hosted default`() {
        val migrated = AiConfig.migrate("https://api.groq.com/openai/v1", "gsk-1", "llama-3.3-70b-versatile")
        assertEquals(1, migrated.profiles.size)
        assertFalse(migrated.profiles[0].isOnDevice)
        assertNotNull(AiConfig.resolve(migrated, AiRole.Dictation))
        assertEquals("gsk-1", AiConfig.resolve(migrated, AiRole.Dictation)!!.apiKey)
    }
}
