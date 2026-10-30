package com.flowbubble.app.agent

import com.flowbubble.app.voice.LlmClient
import org.json.JSONArray
import org.json.JSONObject

object LlmPlanner {
    private const val MAX_STEPS = 6

    private val SYSTEM = """
        You plan actions on an Android screen. Reply with a JSON object only, using this shape:
        {"steps":[{"action":"open_app","query":"Chrome"},{"action":"type","text":"weather","press_enter":true,"search_field":true}]}

        Allowed actions:
        {"action":"open_app","query":"app name"}
        {"action":"open_url","url":"https://..."}
        {"action":"tap","label":"exact visible text","ordinal":1}
        {"action":"long_press","label":"exact visible text","ordinal":1,"hold_ms":700}
        {"action":"type","text":"...","press_enter":true|false,"search_field":true|false}
        {"action":"scroll","direction":"down"|"up","times":1}
        {"action":"swipe","direction":"left"|"right","times":1}
        {"action":"scroll_until","label":"exact visible text","tap":true|false,"max":6}
        {"action":"open_menu"}
        {"action":"clear_field"}
        {"action":"select_all"}
        {"action":"copy"}
        {"action":"paste"}
        {"action":"dismiss"}
        {"action":"back"}
        {"action":"wait","ms":800}
        {"action":"ask_ai","instruction":"what to answer from the screen"}

        Rules:
        - Use at most 6 steps and only labels that appear on the screen text provided.
        - Prefer open_app and open_url over long chains of taps.
        - Use open_menu for a three-dot or overflow control instead of tapping an unreadable icon.
        - Use dismiss only to close a dialog, popup, or keyboard.
        - Never enter passwords, PINs, card numbers, one-time codes, or personal credentials.
        - Never tap through a sign-in, payment, or CAPTCHA prompt. Stop and leave those to the user.
        - Never buy, book, transfer money, delete data, or send a message. If the goal needs that, plan the steps up to the final button; the app will ask the user before tapping it.
        - Use "wait" after actions that load content.
        - Reply with JSON only. No prose and no code fences.
    """.trimIndent()

    suspend fun plan(
        goal: String,
        screenText: String,
        config: LlmClient.Config
    ): Result<AgentPlan> = runCatching {
        val answer = LlmClient.completeRaw(
            SYSTEM,
            "Goal: $goal\n\nVisible screen text:\n${screenText.take(3200)}",
            config
        ).getOrThrow()
        val json = extractJson(answer)
        val root = JSONObject(json)
        val array = root.optJSONArray("steps") ?: JSONArray()
        val steps = ArrayList<AgentStep>()
        for (index in 0 until minOf(array.length(), MAX_STEPS)) {
            val step = array.optJSONObject(index) ?: continue
            steps.add(parseStep(step))
        }
        require(steps.isNotEmpty()) { "The AI model returned no usable steps" }
        AgentPlan(goal.trim(), steps, PlanSource.AI)
    }

    private fun parseStep(step: JSONObject): AgentStep {
        val action = step.optString("action").lowercase()
        return when (action) {
            "open_app" -> {
                val query = step.optString("query").trim()
                require(query.isNotEmpty()) { "open_app needs a query" }
                AgentStep(AgentAction.OpenApp(query.take(80)), "Open $query")
            }
            "open_url" -> {
                val url = ActionParsing.canonicalUrl(step.optString("url"))
                    ?: throw IllegalArgumentException("open_url needs an http(s) URL")
                AgentStep(AgentAction.OpenUrl(url), "Open $url")
            }
            "tap" -> {
                val label = step.optString("label").trim()
                require(label.isNotEmpty()) { "tap needs a label" }
                val ordinal = step.optInt("ordinal", 0).takeIf { it in 1..20 }
                AgentStep(
                    AgentAction.TapTarget(label.take(120), ordinal),
                    "Tap \u201C${label.take(48)}\u201D"
                )
            }
            "long_press" -> {
                val label = step.optString("label").trim()
                require(label.isNotEmpty()) { "long_press needs a label" }
                val ordinal = step.optInt("ordinal", 0).takeIf { it in 1..20 }
                val hold = step.optInt("hold_ms", 650).coerceIn(400, 1500)
                AgentStep(
                    AgentAction.LongPress(label.take(120), ordinal, hold),
                    "Hold \u201C${label.take(48)}\u201D down"
                )
            }
            "type" -> {
                val text = step.optString("text")
                require(text.isNotBlank()) { "type needs text" }
                val enter = step.optBoolean("press_enter", false)
                val searchField = step.optBoolean("search_field", false)
                AgentStep(
                    AgentAction.TypeText(text.take(500), enter, searchField),
                    if (searchField) "Search for \u201C${text.take(40)}\u201D"
                    else "Type \u201C${text.take(40)}\u201D"
                )
            }
            "scroll" -> {
                val forward = !step.optString("direction").equals("up", true)
                val times = step.optInt("times", 1).coerceIn(1, 4)
                AgentStep(
                    AgentAction.Scroll(forward, times),
                    "Scroll ${if (forward) "down" else "up"}"
                )
            }
            "scroll_until" -> {
                val label = step.optString("label").trim()
                require(label.isNotEmpty()) { "scroll_until needs a label" }
                val tap = step.optBoolean("tap", false)
                val max = step.optInt("max", 6).coerceIn(1, 12)
                AgentStep(
                    AgentAction.ScrollUntil(label.take(120), tap, max),
                    if (tap) "Scroll until \u201C${label.take(48)}\u201D and tap it"
                    else "Scroll until \u201C${label.take(48)}\u201D"
                )
            }
            "swipe" -> {
                val toRight = step.optString("direction").equals("right", true)
                val times = step.optInt("times", 1).coerceIn(1, 4)
                AgentStep(
                    AgentAction.Swipe(toRight, times),
                    "Swipe ${if (toRight) "right" else "left"}"
                )
            }
            "open_menu" -> AgentStep(AgentAction.OpenMenu, "Open the menu")
            "clear_field" -> AgentStep(AgentAction.ClearField, "Clear the field")
            "select_all" -> AgentStep(AgentAction.SelectAll, "Select all the text")
            "copy" -> AgentStep(AgentAction.CopySelection, "Copy the selection")
            "paste" -> AgentStep(AgentAction.Paste, "Paste it")
            "dismiss" -> AgentStep(AgentAction.Dismiss, "Close what's on screen")
            "back" -> AgentStep(AgentAction.Back, "Go back")
            "wait" -> {
                val ms = step.optInt("ms", 800).coerceIn(200, 4000).toLong()
                AgentStep(AgentAction.Wait(ms), "Wait briefly")
            }
            "ask_ai" -> {
                val instruction = step.optString("instruction").trim()
                require(instruction.isNotEmpty()) { "ask_ai needs an instruction" }
                AgentStep(AgentAction.AskAi(instruction.take(300)), "Ask AI about this screen")
            }
            else -> throw IllegalArgumentException("Unsupported action \"$action\"")
        }
    }

    private fun extractJson(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```json").removePrefix("```")
            text = text.removeSuffix("```").trim()
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        require(start >= 0 && end > start) { "The AI model didn't return JSON" }
        return text.substring(start, end + 1)
    }
}
