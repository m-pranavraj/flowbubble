package com.flowbubble.app.agent

import com.flowbubble.app.vision.TargetLocator
import com.flowbubble.app.vision.TextMatcher
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

object ActionParsing {
    fun canonicalUrl(raw: String): String? {
        var candidate = raw.trim()
            .replace(Regex("\\s+dot\\s+", RegexOption.IGNORE_CASE), ".")
            .replace(" ", "")
        if (candidate.isEmpty() || candidate.length > 300) return null
        val hasScheme = candidate.startsWith("http://", true) || candidate.startsWith("https://", true)
        if (!hasScheme) {
            if (!candidate.contains('.') || candidate.contains("..")) return null
            candidate = "https://$candidate"
        }
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (host.isEmpty() || host.any { it.isWhitespace() }) return null
        val tld = host.substringAfterLast('.', "")
        if (tld.length < 2 || !tld.all { it.isLetter() }) return null
        return candidate
    }

    fun searchUrl(query: String): String =
        "https://www.google.com/search?q=" + URLEncoder.encode(query.trim(), "UTF-8")
}

object LocalPlanner {
    private const val MAX_STEPS = 8
    private const val MAX_CLAUSES = 4

    private val chainSplitter = Regex(
        "\\s+(?:and\\s+)?then\\s+|\\s*;\\s*|\\s+and\\s+" +
            "(?=(?:search|find|tap|click|press|type|scroll|swipe|open|go to|visit|read|ask|translate|" +
            "dismiss|copy|paste|select|clear|long)\\b)",
        RegexOption.IGNORE_CASE
    )
    private val leadIn = Regex("^(?:please|can you|could you|would you)\\s+", RegexOption.IGNORE_CASE)

    private val openUrlRegex = Regex(
        "^(?:go to|visit|open|load)\\s+(?:the\\s+)?(?:(?:site|website|url|webpage|page)\\s+)?(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val bareDomainRegex = Regex("^([a-z0-9][a-z0-9-]*(?:\\.[a-z0-9-]+)+)(?:/\\S*)?$", RegexOption.IGNORE_CASE)
    private val openAppRegex = Regex(
        "^(?:open|launch|start)\\s+(?:the\\s+)?(.+?)(?:\\s+app)?$",
        RegexOption.IGNORE_CASE
    )
    private val webSearchRegex = Regex(
        "^(?:search\\s+(?:the\\s+)?(?:web|internet|online)|google|look\\s+up)\\s+(?:for\\s+)?(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val namedAppSearchRegex = Regex(
        "^(?:search|find|look)\\s+(?:for\\s+)?(.+?)\\s+in\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val inAppSearchPatterns = listOf(
        Regex("^search\\s+(?:in\\s+)?(?:this|the current)\\s+(?:app|screen)\\s+for\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^search\\s+(?:this|the current)\\s+(?:app|screen)\\s+for\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^(?:search|find|look)\\s+(?:for\\s+)?(.+?)\\s+(?:in this app|in the current app|on this page|on this screen|here)$", RegexOption.IGNORE_CASE)
    )
    private val bareSearchRegex = Regex(
        "^(?:search|find|look)\\s+(?:for\\s+)?(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val typeRegex = Regex("^(?:type|dictate)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val openResultRegex = Regex(
        "^(?:open|tap|click|press|select|choose)\\s+(?:the\\s+)?(first|1st|second|2nd|third|3rd|fourth|4th|fifth|5th)" +
            "\\s+(result|link|option|item|video|article|one)$",
        RegexOption.IGNORE_CASE
    )
    private val tapRegex = Regex(
        "^(?:tap|click|press|select|choose|open)\\s+(?:on\\s+)?(?:the\\s+)?(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val ordinalNouns = "result|results|option|options|item|items|link|one|message|" +
        "conversation|photo|photos|picture|row|card|button|contact|file|entry|choice"
    private val wordOrdinalRegex = Regex(
        "^(first|1st|second|2nd|third|3rd|fourth|4th|fifth|5th)(?:\\s+(?:$ordinalNouns))?$",
        RegexOption.IGNORE_CASE
    )
    private val ordinalOnlyRegex = Regex(
        "^(\\d{1,2})(?:st|nd|rd|th)?(?:\\s+(?:$ordinalNouns))?$",
        RegexOption.IGNORE_CASE
    )
    private val resultNumberRegex = Regex("^result\\s+(\\d{1,2})$", RegexOption.IGNORE_CASE)
    private val scrollRegex = Regex(
        "^(?:scroll|swipe)\\s+(down|up)(?:\\s+(twice|two times|2 times|three times|3 times|thrice))?$",
        RegexOption.IGNORE_CASE
    )
    private val swipeSideRegex = Regex(
        "^swipe\\s+(?:to\\s+the\\s+)?(left|right)(?:\\s+(twice|two times|2 times|three times|3 times|thrice))?$",
        RegexOption.IGNORE_CASE
    )
    private val longPressRegex = Regex(
        "^(?:long\\s*(?:press|tap)\\s*|press\\s+and\\s+hold\\s+|hold\\s+down\\s+on\\s+)(?:the\\s+)?(.+?)" +
            "(?:\\s+for\\s+(\\d+)\\s*(?:seconds?|secs?|s))?$",
        RegexOption.IGNORE_CASE
    )
    private val menuRegex = Regex(
        "^(?:open|show|tap|click|press|hit)\\s+(?:the\\s+)?(?:app\\s+)?(?:three[\\s-]*(?:dots?|lines?)|" +
            "overflow(?:\\s+menu)?|more\\s+options(?:\\s+menu)?|options\\s+menu|menu)(?:\\s+menu)?$" +
            "|^(?:the\\s+)?(?:three[\\s-]*(?:dots?|lines?)|overflow\\s+menu|more\\s+options)$",
        RegexOption.IGNORE_CASE
    )
    private val clearFieldRegex = Regex(
        "^(?:clear|erase|empty)\\s+(?:the\\s+|this\\s+)?(?:text\\s+|search\\s+)?" +
            "(?:field|box|bar|input|entry)$|^clear\\s+(?:it|this|that)$",
        RegexOption.IGNORE_CASE
    )
    private val selectAllRegex = Regex(
        "^(?:select\\s+(?:all|everything)|mark\\s+all|highlight\\s+all)$",
        RegexOption.IGNORE_CASE
    )
    private val copyRegex = Regex(
        "^(?:copy|copy\\s+(?:it|this|that|everything|the\\s+(?:selection|selected\\s+text|text)))$",
        RegexOption.IGNORE_CASE
    )
    private val pasteRegex = Regex(
        "^(?:paste|paste\\s+(?:it|this|that|here))$",
        RegexOption.IGNORE_CASE
    )
    private val dismissRegex = Regex(
        "^(?:dismiss|close|get\\s+rid\\s+of)(?:\\s+(?:the\\s+|this\\s+|that\\s+)?" +
            "(?:dialog|pop(?:-|u)?p|overlay|prompt|alert|window|banner|keyboard|menu|message|thing|" +
            "it|this|that))?\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val scrollUntilRegex = Regex(
        "^scroll\\s+(?:down\\s+)?(?:until\\s+(?:you\\s+)?(?:see|find|reach)|to)\\s+(.+?)" +
            "(?:\\s+and\\s+(?:tap|click|press|open)\\s+(?:it|that))?$",
        RegexOption.IGNORE_CASE
    )
    private val scrollFindByRegex = Regex(
        "^(?:find|look\\s+for)\\s+(.+?)\\s+by\\s+scrolling(?:\\s+(?:down|up))?" +
            "(?:\\s+and\\s+(?:tap|click|open)\\s+(?:it|that))?$",
        RegexOption.IGNORE_CASE
    )
    private val tapFollowUpRegex = Regex(
        "^(?:tap|click|press|open)\\s+(?:on\\s+)?(?:it|that)$",
        RegexOption.IGNORE_CASE
    )
    private val translateRegex = Regex(
        "^(?:translate)\\s+(?:this|the)\\s+(?:page|screen)\\s+(?:to|into)\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val summarizeRegex = Regex(
        "^(?:summarize|summarise|read|explain)\\s+(?:this|the)\\s+(?:page|screen)$",
        RegexOption.IGNORE_CASE
    )
    private val whatSaysRegex = Regex(
        "^(?:what does|what's on)\\s+(?:this|the)\\s+(?:page|screen)\\s*(?:say(?:\\s+about\\s+(.+))?)?$",
        RegexOption.IGNORE_CASE
    )
    private val askAiRegex = Regex("^ask(?:\\s+ai)?\\s+(?:about\\s+)?(.+)$", RegexOption.IGNORE_CASE)

    /**
     * True when the whole phrase is a screen-control gesture, so the voice router can
     * hand it to the agent instead of treating it as a tap target or dictation.
     */
    fun isControlPhrase(raw: String): Boolean {
        val text = raw.trim().replace(Regex("\\s+"), " ").trimEnd('.', '!', '?', ',')
        if (text.isEmpty()) return false
        return controlClause(text) != null
    }

    fun plan(rawGoal: String): AgentPlan? {
        var text = rawGoal.trim().replace(Regex("\\s+"), " ")
        text = leadIn.replace(text, "")
        text = text.trim().trimEnd('.', '!', '?', ',')
        if (text.isEmpty()) return null

        scrollUntilStep(text)?.let { step ->
            return AgentPlan(rawGoal.trim(), listOf(step), PlanSource.LOCAL)
        }

        val clauses = text.split(chainSplitter).map { it.trim() }.filter { it.isNotEmpty() }
        if (clauses.isEmpty() || clauses.size > MAX_CLAUSES) return null

        val steps = ArrayList<AgentStep>()
        var appContext = false
        for (clause in clauses) {
            if (tapFollowUpRegex.matches(clause)) {
                val previous = steps.lastOrNull()
                val action = previous?.action
                if (action is AgentAction.ScrollUntil) {
                    steps[steps.lastIndex] = previous.copy(
                        action = action.copy(tapAfter = true),
                        description = "Scroll until I see \u201C${abbreviate(action.label)}\u201D and tap it"
                    )
                    continue
                }
            }
            val clauseSteps = planClause(clause, appContext) ?: return null
            if (steps.isNotEmpty() && settles(steps.last())) {
                steps.add(AgentStep(AgentAction.Wait(900), "Wait for the screen"))
            }
            steps.addAll(clauseSteps)
            if (clauseSteps.any {
                    it.action is AgentAction.OpenApp || it.action is AgentAction.OpenUrl
                }
            ) {
                appContext = true
            }
            if (steps.size > MAX_STEPS) return null
        }
        if (steps.isEmpty()) return null
        return AgentPlan(rawGoal.trim(), steps, PlanSource.LOCAL)
    }

    private fun planClause(clause: String, appContext: Boolean): List<AgentStep>? {
        controlClause(clause)?.let { return listOf(it) }
        openResult(clause)?.let { return listOf(it) }
        openUrlClause(clause)?.let { return listOf(it) }
        openAppClause(clause)?.let { return listOf(it) }
        webSearchClause(clause)?.let { return listOf(it) }
        scrollUntilStep(clause)?.let { return listOf(it) }
        namedAppSearchClause(clause)?.let { return it }
        inAppSearchClause(clause)?.let { return listOf(it) }
        bareSearchClause(clause, appContext)?.let { return listOf(it) }
        typeClause(clause)?.let { return listOf(it) }
        tapClause(clause)?.let { return listOf(it) }
        scrollClause(clause)?.let { return listOf(it) }
        askAiClause(clause)?.let { return listOf(it) }
        if (clause.equals("back", true) || clause.equals("go back", true)) {
            return listOf(AgentStep(AgentAction.Back, "Go back"))
        }
        return null
    }

    private fun controlClause(clause: String): AgentStep? {
        swipeSideRegex.matchEntire(clause)?.let { match ->
            val toRight = match.groupValues[1].equals("right", true)
            val times = when (match.groupValues[2].lowercase(Locale.ROOT)) {
                "", "once" -> 1
                "twice", "two times", "2 times" -> 2
                else -> 3
            }
            return AgentStep(
                AgentAction.Swipe(toRight, times),
                "Swipe ${if (toRight) "right" else "left"}${if (times > 1) " $times times" else ""}"
            )
        }
        longPressRegex.matchEntire(clause)?.let { match ->
            val label = match.groupValues[1].trim().trimEnd('.', '!', '?', ',')
            if (label.isEmpty()) return null
            val seconds = match.groupValues[2].toIntOrNull()
            val hold = if (seconds != null) seconds * 1000 else 650
            return AgentStep(
                AgentAction.LongPress(label.take(120), ordinalOf(label), hold),
                "Hold \u201C${abbreviate(label)}\u201D down"
            )
        }
        if (menuRegex.matches(clause)) return AgentStep(AgentAction.OpenMenu, "Open the menu")
        if (selectAllRegex.matches(clause)) return AgentStep(AgentAction.SelectAll, "Select all the text")
        if (copyRegex.matches(clause)) return AgentStep(AgentAction.CopySelection, "Copy the selection")
        if (pasteRegex.matches(clause)) return AgentStep(AgentAction.Paste, "Paste it")
        if (clearFieldRegex.matches(clause)) return AgentStep(AgentAction.ClearField, "Clear the field")
        if (dismissRegex.matches(clause)) return AgentStep(AgentAction.Dismiss, "Close what's on screen")
        return null
    }

    private fun openUrlClause(clause: String): AgentStep? {
        if (bareDomainRegex.matches(clause)) {
            val url = ActionParsing.canonicalUrl(clause) ?: return null
            return AgentStep(AgentAction.OpenUrl(url), "Open $url")
        }
        val match = openUrlRegex.matchEntire(clause) ?: return null
        val target = match.groupValues[1].trim()
        if (target.isEmpty()) return null
        val url = ActionParsing.canonicalUrl(target) ?: return null
        return AgentStep(AgentAction.OpenUrl(url), "Open $url")
    }

    private fun openAppClause(clause: String): AgentStep? {
        val match = openAppRegex.matchEntire(clause) ?: return null
        val query = match.groupValues[1].trim()
        if (query.isEmpty()) return null
        val normalized = query.lowercase(Locale.ROOT)
        if (normalized in setOf("page", "site", "website", "url", "webpage")) return null
        if (normalized.startsWith("the ") && normalized.length <= 8) return null
        return AgentStep(AgentAction.OpenApp(query), "Open $query")
    }

    private fun webSearchClause(clause: String): AgentStep? {
        val match = webSearchRegex.matchEntire(clause) ?: return null
        val query = match.groupValues[1].trim()
        if (query.isEmpty()) return null
        return AgentStep(
            AgentAction.OpenUrl(ActionParsing.searchUrl(query)),
            "Search the web for \u201C$query\u201D"
        )
    }

    private fun namedAppSearchClause(clause: String): List<AgentStep>? {
        val match = namedAppSearchRegex.matchEntire(clause) ?: return null
        val query = match.groupValues[1].trim()
        val app = match.groupValues[2].trim()
        if (query.isEmpty() || app.isEmpty()) return null
        val appName = app.lowercase(Locale.ROOT)
        if (appName in setOf(
                "this app", "the app", "current app", "this screen", "the screen",
                "this page", "the page", "here", "the list"
            )
        ) {
            return null
        }
        return listOf(
            AgentStep(AgentAction.OpenApp(app), "Open $app"),
            AgentStep(AgentAction.Wait(900), "Wait for the screen"),
            AgentStep(
                AgentAction.TypeText(query, pressEnter = true, searchFieldOnly = true),
                "Search $app for \u201C$query\u201D"
            )
        )
    }

    private fun inAppSearchClause(clause: String): AgentStep? {
        val query = inAppSearchPatterns.firstNotNullOfOrNull { regex ->
            regex.matchEntire(clause)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        } ?: return null
        return AgentStep(
            AgentAction.TypeText(query, pressEnter = true, searchFieldOnly = true),
            "Search this app for \u201C$query\u201D"
        )
    }

    private fun bareSearchClause(clause: String, appContext: Boolean): AgentStep? {
        val query = bareSearchRegex.matchEntire(clause)?.groupValues?.getOrNull(1)?.trim()
        if (query.isNullOrEmpty()) return null
        return if (appContext) {
            AgentStep(
                AgentAction.TypeText(query, pressEnter = true, searchFieldOnly = true),
                "Search this app for \u201C$query\u201D"
            )
        } else {
            AgentStep(
                AgentAction.OpenUrl(ActionParsing.searchUrl(query)),
                "Search the web for \u201C$query\u201D"
            )
        }
    }

    private fun typeClause(clause: String): AgentStep? {
        val text = typeRegex.matchEntire(clause)?.groupValues?.getOrNull(1)?.trim()
        if (text.isNullOrEmpty()) return null
        return AgentStep(AgentAction.TypeText(text), "Type \u201C${abbreviate(text)}\u201D")
    }

    private fun openResult(clause: String): AgentStep? {
        val match = openResultRegex.matchEntire(clause) ?: return null
        val word = match.groupValues[1].trim()
        val noun = match.groupValues[2].trim()
        val ordinal = ordinalOf(word) ?: return null
        return AgentStep(
            AgentAction.TapTarget("$word $noun", ordinal),
            "Open the ${ordinalName(ordinal)} $noun"
        )
    }

    private fun tapClause(clause: String): AgentStep? {
        val target = tapRegex.matchEntire(clause)?.groupValues?.getOrNull(1)?.trim()
        if (target.isNullOrEmpty()) return null
        val ordinal = ordinalOf(target) ?: ordinalOnlyRegex.matchEntire(target)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (ordinal != null) {
            return AgentStep(AgentAction.TapTarget(target, ordinal), "Tap ${ordinalName(ordinal)} item")
        }
        return AgentStep(AgentAction.TapTarget(target), "Tap \u201C${abbreviate(target)}\u201D")
    }

    private fun scrollUntilStep(clause: String): AgentStep? {
        val match = scrollUntilRegex.matchEntire(clause)
            ?: scrollFindByRegex.matchEntire(clause)
            ?: return null
        val label = match.groupValues[1].trim().trimEnd('.', '!', '?', ',')
        if (label.isEmpty()) return null
        val tapAfter = Regex("\\band\\s+(?:tap|click|press|open)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(clause)
        return AgentStep(
            AgentAction.ScrollUntil(label.take(120), tapAfter),
            if (tapAfter) {
                "Scroll until I see \u201C${abbreviate(label)}\u201D and tap it"
            } else {
                "Scroll until I see \u201C${abbreviate(label)}\u201D"
            }
        )
    }

    private fun scrollClause(clause: String): AgentStep? {
        val match = scrollRegex.matchEntire(clause) ?: return null
        val forward = match.groupValues[1].equals("down", true)
        val times = when (match.groupValues[2].lowercase(Locale.ROOT)) {
            "", "once" -> 1
            "twice", "two times", "2 times" -> 2
            else -> 3
        }
        return AgentStep(
            AgentAction.Scroll(forward, times),
            "Scroll ${if (forward) "down" else "up"}${if (times > 1) " $times times" else ""}"
        )
    }

    private fun askAiClause(clause: String): AgentStep? {
        translateRegex.matchEntire(clause)?.let { match ->
            val language = match.groupValues[1].trim()
            if (language.isNotEmpty()) {
                return AgentStep(
                    AgentAction.AskAi("Translate all visible screen text into $language. Return only the translation."),
                    "Translate the screen into $language"
                )
            }
        }
        if (summarizeRegex.matches(clause)) {
            return AgentStep(
                AgentAction.AskAi("Summarize the visible screen in at most five short bullets."),
                "Summarize this screen"
            )
        }
        whatSaysRegex.matchEntire(clause)?.let { match ->
            val topic = match.groupValues[1].trim()
            val instruction = if (topic.isEmpty()) {
                "Explain what this screen shows in a few short sentences."
            } else {
                "What does this screen say about $topic? Use only the visible text."
            }
            return AgentStep(AgentAction.AskAi(instruction), "Ask AI about this screen")
        }
        askAiRegex.matchEntire(clause)?.let { match ->
            val question = match.groupValues[1].trim()
            if (question.isNotEmpty()) {
                return AgentStep(AgentAction.AskAi(question), "Ask AI: ${abbreviate(question)}")
            }
        }
        return null
    }

    private fun settles(step: AgentStep): Boolean = when (val action = step.action) {
        is AgentAction.TypeText -> action.pressEnter
        is AgentAction.ScrollUntil -> action.tapAfter
        is AgentAction.OpenApp, is AgentAction.OpenUrl, is AgentAction.LongPress,
        is AgentAction.Swipe, AgentAction.OpenMenu, AgentAction.Back, AgentAction.Dismiss -> true
        else -> false
    }

    private fun ordinalOf(raw: String): Int? {
        resultNumberRegex.matchEntire(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        ordinalOnlyRegex.matchEntire(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return when (wordOrdinalRegex.matchEntire(raw)?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT)) {
            "first", "1st" -> 1
            "second", "2nd" -> 2
            "third", "3rd" -> 3
            "fourth", "4th" -> 4
            "fifth", "5th" -> 5
            else -> null
        }
    }

    private fun ordinalName(ordinal: Int): String = when (ordinal) {
        1 -> "first"
        2 -> "second"
        3 -> "third"
        4 -> "fourth"
        5 -> "fifth"
        else -> "#$ordinal"
    }

    private fun abbreviate(text: String, max: Int = 48): String =
        if (text.length <= max) text else text.take(max - 1) + "\u2026"

    // ---- Reactive layer ---------------------------------------------------------------------
    // The plan is only the opening move. From there the loop reads the screen again, decides the one
    // next step from what it can actually see, takes it, and checks the screen once more. Every rule
    // here is deterministic, so a task like "scroll down until you see X and open it" works with no
    // AI key configured at all.

    /**
     * Turns the user's words into a condition the screen can be checked against, or null when the
     * request has no observable finish line. A null goal means the loop runs the planned steps and
     * stops with the ordinary verified-evidence verdict instead of pretending to know when it is done.
     */
    fun reactiveGoal(rawGoal: String): ReactiveGoal? {
        var text = rawGoal.trim().replace(Regex("\\s+"), " ")
        text = leadIn.replace(text, "").trim().trimEnd('.', '!', '?', ',')
        if (text.isEmpty()) return null

        scrollGoal(text)?.let { return it }

        val clauses = text.split(chainSplitter).map { it.trim() }.filter { it.isNotEmpty() }
        // A chain's finish line is its last clause. Matching the whole sentence instead lets the first
        // verb swallow the rest, so "open chrome and search for weather" waited for an app named
        // "chrome and search for weather" and called the job a failure after it had already succeeded.
        if (clauses.size < 2) return singleGoal(text)
        val last = clauses.last()
        if (tapFollowUpRegex.matches(last)) {
            // "scroll down to X and tap it" - the finish line is the tap, on the clause before it.
            val previous = clauses[clauses.size - 2]
            scrollGoal(previous)?.let { found ->
                if (found.kind == GoalKind.FIND) {
                    return found.copy(
                        kind = GoalKind.TAP,
                        doneWhen = listOf(GoalCondition.TapVerified, GoalCondition.ScreenChanged())
                    )
                }
            }
            singleGoal(previous)?.let { earlier ->
                if (earlier.target.isNotBlank()) {
                    return earlier.copy(
                        kind = GoalKind.TAP,
                        target = earlier.target,
                        doneWhen = listOf(GoalCondition.TapVerified, GoalCondition.ScreenChanged())
                    )
                }
            }
            return null
        }
        return singleGoal(last)
    }

    private fun scrollGoal(text: String): ReactiveGoal? {
        val match = scrollUntilRegex.matchEntire(text) ?: scrollFindByRegex.matchEntire(text) ?: return null
        val label = match.groupValues[1].trim().trimEnd('.', '!', '?', ',')
        if (label.isEmpty()) return null
        val tapAfter = Regex("\\band\\s+(?:tap|click|press|open)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(text) || Regex("\\bthen\\s+(?:tap|click|open|select)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(text)
        return ReactiveGoal(
            raw = text,
            kind = if (tapAfter) GoalKind.TAP else GoalKind.FIND,
            target = label.take(120),
            findFirst = true,
            maxScrolls = 8,
            doneWhen = if (tapAfter) {
                listOf(GoalCondition.TapVerified, GoalCondition.ScreenChanged())
            } else {
                listOf(GoalCondition.LabelAppeared(label.take(120)))
            }
        )
    }

    private fun singleGoal(text: String): ReactiveGoal? {
        if (dismissRegex.matches(text)) {
            return ReactiveGoal(
                raw = text,
                kind = GoalKind.DISMISS,
                doneWhen = listOf(GoalCondition.ScreenChanged())
            )
        }
        if (text.equals("back", true) || text.equals("go back", true)) {
            return ReactiveGoal(
                raw = text,
                kind = GoalKind.BACK,
                doneWhen = listOf(GoalCondition.ScreenChanged())
            )
        }
        typeRegex.matchEntire(text)?.let { match ->
            val content = match.groupValues[1].trim()
            if (content.isNotEmpty()) {
                return ReactiveGoal(
                    raw = text,
                    kind = GoalKind.TYPE,
                    text = content.take(400),
                    doneWhen = listOf(GoalCondition.FieldContains(content.take(400)))
                )
            }
        }
        openAppRegex.matchEntire(text)?.let { match ->
            val app = match.groupValues[1].trim()
            if (app.isNotEmpty() && !looksLikePointing(app)) {
                return ReactiveGoal(
                    raw = text,
                    kind = GoalKind.OPEN_APP,
                    appName = app.take(60),
                    target = app.take(60),
                    doneWhen = listOf(GoalCondition.Foreground("", app.take(60)))
                )
            }
        }
        val tapped = tapRegex.matchEntire(text)?.groupValues?.getOrNull(1)?.trim() ?: return null
        if (tapped.isEmpty()) return null
        if (looksLikePointing(tapped)) {
            return ReactiveGoal(
                raw = text,
                kind = GoalKind.POINT,
                pointing = tapped.take(120),
                target = tapped.take(120),
                doneWhen = listOf(GoalCondition.TapVerified, GoalCondition.ScreenChanged())
            )
        }
        return ReactiveGoal(
            raw = text,
            kind = GoalKind.TAP,
            target = tapped.take(120),
            maxScrolls = 2,
            doneWhen = listOf(GoalCondition.TapVerified, GoalCondition.ScreenChanged())
        )
    }

    /**
     * True when the phrase points instead of naming: an order, a place on the screen, a colour, or a
     * bare "that". Those only make sense against the live target list, so they go to the locator
     * rather than to label matching.
     */
    private fun looksLikePointing(phrase: String): Boolean {
        val normalized = TextMatcher.normalize(phrase)
        if (normalized.isEmpty()) return false
        if (normalized.split(' ').any { it in DEICTIC }) return true
        val pointer = TargetLocator.parse(phrase)
        return pointer.ordinal != null || pointer.band != TargetLocator.Band.NONE || pointer.hue != null
    }

    private val DEICTIC = setOf("that", "this", "it", "there", "here", "one")

    /**
     * The next step, from the goal and the screen as it is now. Nothing here looks at a plan position:
     * the loop is told what is true on screen and answers with one action, a question, or a reason.
     */
    fun decide(ctx: LoopContext): LoopDecision {
        val goal = ctx.goal
        val now = ctx.now

        if (goal.satisfied(now, ctx.before, ctx.tapsVerified)) {
            return LoopDecision.Done(
                "${goal.evidence()} - seen on screen in ${now.packageName} at revision ${now.revision}."
            )
        }
        // Safety first, and unchanged by the new loop: never read or tap around a password field.
        if (now.passwordFocused) {
            return LoopDecision.GiveUp(
                "A password field is focused, so I won't tap or read around it. Finish this part yourself."
            )
        }
        ctx.budget.stopReason(ctx)?.let { return LoopDecision.GiveUp(it) }

        when (goal.kind) {
            GoalKind.POINT -> {
                val pointer = ctx.resolvedPointer
                if (pointer == null) {
                    if (ctx.pointerQuestion != null && ctx.pointerOptions.size >= 2) {
                        return LoopDecision.Ask(
                            question = ctx.pointerQuestion,
                            body = ctx.pointerNote.ifBlank { "I can see more than one match for \u201C${goal.pointing}\u201D." },
                            options = ctx.pointerOptions
                        )
                    }
                    if (ctx.pointerNote.isNotBlank()) return LoopDecision.GiveUp(ctx.pointerNote)
                }
                if (pointer != null) {
                    if (ctx.tapsUsed > 0) {
                        return LoopDecision.GiveUp(
                            "I tapped ${describeTarget(pointer)} and the screen didn't give me what you asked for."
                        )
                    }
                    val risk = SafetyPolicy.assessTap(pointer.label)
                    if (risk.level == RiskLevel.BLOCKED) return LoopDecision.GiveUp(risk.reason)
                    return LoopDecision.Act(
                        AgentAction.TapTarget(pointer.label, pointer.ordinal),
                        "tap ${describeTarget(pointer)} (${pointer.evidence})"
                    )
                }
            }

            GoalKind.FIND, GoalKind.TAP -> {
                val visible = now.shows(goal.target)
                if (!visible) {
                    return LoopDecision.Act(
                        AgentAction.Scroll(forward = true, times = 1),
                        "scroll down to look for \u201C${abbreviate(goal.target)}\u201D"
                    )
                }
                if (ctx.tapsUsed > 0) {
                    return LoopDecision.GiveUp(
                        "\u201C${abbreviate(goal.target)}\u201D is on screen and I tapped it, but the screen " +
                            "never became what you asked for."
                    )
                }
                if (goal.kind == GoalKind.FIND) {
                    // The finish line for FIND is visibility, so reaching here means it moved away.
                    return LoopDecision.GiveUp("I lost sight of \u201C${abbreviate(goal.target)}\u201D again.")
                }
                val risk = SafetyPolicy.assessTap(goal.target)
                if (risk.level == RiskLevel.BLOCKED) return LoopDecision.GiveUp(risk.reason)
                return LoopDecision.Act(
                    AgentAction.TapTarget(goal.target, ordinalOf(goal.target)),
                    "tap \u201C${abbreviate(goal.target)}\u201D"
                )
            }

            GoalKind.DISMISS -> {
                if (ctx.dismissUsed > 0) {
                    return LoopDecision.GiveUp(
                        "Nothing on this screen looks closeable, so I stopped instead of tapping around it."
                    )
                }
                return LoopDecision.Act(AgentAction.Dismiss, "close whatever is on top")
            }

            GoalKind.BACK -> {
                if (ctx.backUsed > 0) {
                    return LoopDecision.GiveUp("I went back, and the screen didn't change the way you asked.")
                }
                return LoopDecision.Act(AgentAction.Back, "go back one screen")
            }

            GoalKind.TYPE -> {
                if (ctx.typedUsed > 0) {
                    return LoopDecision.GiveUp(
                        "I typed it, but the field never showed \u201C${abbreviate(goal.text)}\u201D."
                    )
                }
                return LoopDecision.Act(
                    AgentAction.TypeText(goal.text, pressEnter = false),
                    "type \u201C${abbreviate(goal.text)}\u201D into the field"
                )
            }

            GoalKind.OPEN_APP -> {
                if (ctx.openedApp) {
                    return LoopDecision.GiveUp(
                        "Android never showed ${goal.appName} in the foreground, so I stopped."
                    )
                }
                return LoopDecision.Act(AgentAction.OpenApp(goal.appName), "open ${goal.appName}")
            }

            GoalKind.GENERIC -> Unit
        }
        return LoopDecision.GiveUp(
            "I've done the steps I could plan for \u201C${abbreviate(goal.raw)}\u201D, and I can't see a " +
                "way to check the rest on this screen."
        )
    }

    private fun describeTarget(pointer: ResolvedPointer): String =
        if (pointer.label.isNotBlank()) "\u201C${abbreviate(pointer.label)}\u201D" else "the control you pointed at"
}
