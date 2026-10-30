package com.flowbubble.app.core

import android.content.Context
import androidx.core.content.edit
import com.flowbubble.app.voice.EngineChoice

object Prefs {
    private const val FILE = "flowbubble_prefs"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun language(context: Context): String = sp(context).getString("language", "en-IN") ?: "en-IN"

    fun setLanguage(context: Context, value: String) = sp(context).edit { putString("language", value) }

    fun engineChoice(context: Context): EngineChoice =
        EngineChoice.from(sp(context).getString("speech_engine", EngineChoice.AUTO.id))

    fun setEngineChoice(context: Context, value: EngineChoice) =
        sp(context).edit { putString("speech_engine", value.id) }

    fun aiCleanupEnabled(context: Context): Boolean = sp(context).getBoolean("ai_cleanup", true)

    fun setAiCleanupEnabled(context: Context, value: Boolean) =
        sp(context).edit { putBoolean("ai_cleanup", value) }

    fun aiBaseUrl(context: Context): String =
        sp(context).getString("ai_base_url", "https://api.openai.com/v1")
            ?: "https://api.openai.com/v1"

    fun aiApiKey(context: Context): String = sp(context).getString("ai_api_key", "") ?: ""

    fun aiModel(context: Context): String =
        sp(context).getString("ai_model", "gpt-4o-mini") ?: "gpt-4o-mini"

    fun agentLlmEnabled(context: Context): Boolean = sp(context).getBoolean("agent_llm", false)

    fun setAgentLlmEnabled(context: Context, value: Boolean) =
        sp(context).edit { putBoolean("agent_llm", value) }

    fun setAiConfig(context: Context, baseUrl: String, apiKey: String, model: String) =
        sp(context).edit {
            putString("ai_base_url", baseUrl.trim())
            putString("ai_api_key", apiKey.trim())
            putString("ai_model", model.trim())
        }

    fun bubbleX(context: Context): Int = sp(context).getInt("bubble_x", -1)

    fun bubbleY(context: Context): Int = sp(context).getInt("bubble_y", -1)

    fun dndRevertAt(context: Context): Long = sp(context).getLong("dnd_revert_at", 0L)

    fun setDndRevertAt(context: Context, time: Long) =
        sp(context).edit { putLong("dnd_revert_at", time) }

    fun setBubblePosition(context: Context, x: Int, y: Int) =
        sp(context).edit {
            putInt("bubble_x", x)
            putInt("bubble_y", y)
        }
}
