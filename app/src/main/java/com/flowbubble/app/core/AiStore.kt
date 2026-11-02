package com.flowbubble.app.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.flowbubble.app.voice.AiConfig
import com.flowbubble.app.voice.AiEngineKind
import com.flowbubble.app.voice.AiProfile
import com.flowbubble.app.voice.AiSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Model profiles and their keys.
 *
 * Endpoints, model names and the role-to-profile map are not secret and live in ordinary
 * preferences. API keys live in a separate file encrypted with a key from the Android Keystore, and
 * nothing here ever writes a key into a log, the history store, or a crash message. If the encrypted
 * file cannot be opened the keys fall back to ordinary preferences and [keysAreEncrypted] reports
 * false, so the settings screen can say that out loud instead of pretending.
 */
object AiStore {
    private const val FILE = "flowbubble_ai"
    private const val KEY_FILE = "flowbubble_ai_keys"
    private const val KEY_FALLBACK_FILE = "flowbubble_ai_keys_unencrypted"

    @Volatile
    private var cachedKeyStore: SharedPreferences? = null

    @Volatile
    private var encryptionUnavailable = false

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun keyStore(context: Context): SharedPreferences =
        cachedKeyStore ?: synchronized(this) {
            cachedKeyStore ?: openKeyStore(context).also { cachedKeyStore = it }
        }

    private fun openKeyStore(context: Context): SharedPreferences =
        if (encryptionUnavailable) {
            fallbackKeyStore(context)
        } else {
            runCatching {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    KEY_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }.getOrElse {
                encryptionUnavailable = true
                fallbackKeyStore(context)
            }
        }

    private fun fallbackKeyStore(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(KEY_FALLBACK_FILE, Context.MODE_PRIVATE)

    fun keysAreEncrypted(context: Context): Boolean {
        keyStore(context)
        return !encryptionUnavailable
    }

    fun settings(context: Context): AiSettings {
        val store = prefs(context)
        if (!store.getBoolean("migrated", false)) migrateLegacyConfig(context, store)

        val profiles = decodeProfiles(store.getString("profiles", "[]"))
        val roles = decodeMap(store.getString("roles", "{}"))
        val defaultProfileId = store.getString("default_profile", "").orEmpty()
        val keys = keyStore(context).all
            .mapNotNull { (id, value) -> (value as? String)?.takeIf { it.isNotEmpty() }?.let { id to it } }
            .toMap()
        return AiSettings(profiles, keys, roles, defaultProfileId)
    }

    fun save(context: Context, settings: AiSettings) {
        prefs(context).edit {
            putString("profiles", encodeProfiles(settings.profiles))
            putString("roles", encodeMap(settings.roles))
            putString("default_profile", settings.defaultProfileId)
        }
        val ids = settings.profiles.map { it.id }.toSet()
        val store = keyStore(context)
        store.edit {
            store.all.keys.filter { it !in ids }.forEach { remove(it) }
            settings.profiles.forEach { profile ->
                // A local profile has no key to keep, and an old one left under its id is removed
                // rather than encrypted forever for a model that never talks to a server.
                val key = if (profile.isOnDevice) "" else settings.keys[profile.id].orEmpty()
                if (key.isEmpty()) remove(profile.id) else putString(profile.id, key)
            }
        }
    }

    fun setKey(context: Context, profileId: String, apiKey: String) {
        val store = keyStore(context)
        store.edit {
            if (apiKey.isBlank()) remove(profileId) else putString(profileId, apiKey.trim())
        }
    }

    private fun migrateLegacyConfig(context: Context, store: SharedPreferences) {
        val legacyKey = Prefs.aiApiKey(context)
        val migrated = if (legacyKey.isNotBlank()) {
            AiConfig.migrate(Prefs.aiBaseUrl(context), legacyKey, Prefs.aiModel(context))
        } else {
            AiSettings()
        }
        store.edit(commit = true) {
            putString("profiles", encodeProfiles(migrated.profiles))
            putString("roles", "{}")
            putString("default_profile", migrated.defaultProfileId)
            putBoolean("migrated", true)
        }
        if (migrated.profiles.isNotEmpty()) {
            setKey(context, migrated.profiles.first().id, legacyKey)
            Prefs.clearLegacyAiConfig(context)
        }
    }

    private fun encodeProfiles(profiles: List<AiProfile>): String =
        JSONArray().apply {
            profiles.forEach {
                put(
                    JSONObject()
                        .put("id", it.id)
                        .put("label", it.label)
                        .put("provider", it.providerId)
                        .put("baseUrl", it.baseUrl)
                        .put("model", it.model)
                        .put("engine", encodeEngine(it.engine))
                )
            }
        }.toString()

    /** Only the local engine is written out; a hosted profile stays exactly the old shape on disk. */
    private fun encodeEngine(engine: AiEngineKind): String =
        if (engine == AiEngineKind.OnDevice) "on-device" else "hosted"

    private fun decodeProfiles(raw: String?): List<AiProfile> = runCatching {
        val array = JSONArray(raw ?: "[]")
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            AiProfile(
                id = item.optString("id"),
                label = item.optString("label"),
                providerId = item.optString("provider"),
                baseUrl = item.optString("baseUrl"),
                model = item.optString("model"),
                // A profile saved before this field existed has no engine key, and no engine key has
                // always meant a hosted endpoint.
                engine = AiEngineKind.from(item.optString("engine", "hosted"))
            )
        }.filter { it.id.isNotEmpty() }
    }.getOrDefault(emptyList())

    private fun encodeMap(values: Map<String, String>): String =
        JSONObject(values as Map<*, *>).toString()

    private fun decodeMap(raw: String?): Map<String, String> = runCatching {
        val root = JSONObject(raw ?: "{}")
        root.keys().asSequence().associateWith { root.optString(it) }
    }.getOrDefault(emptyMap())
}
