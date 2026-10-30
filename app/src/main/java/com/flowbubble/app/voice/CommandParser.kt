package com.flowbubble.app.voice

import com.flowbubble.app.agent.ActionParsing
import com.flowbubble.app.agent.LocalPlanner
import com.flowbubble.app.commands.ChatApp

enum class SearchScope { AUTO, WEB, CURRENT }

enum class FollowUpKind {
    SHORTER, LONGER, PROFESSIONAL, CASUAL, TIDY, SUMMARIZE, TRANSLATE, UNDO, AGAIN, COPY;

    val editsText: Boolean
        get() = when (this) {
            SHORTER, LONGER, PROFESSIONAL, CASUAL, TIDY, SUMMARIZE, TRANSLATE -> true
            UNDO, AGAIN, COPY -> false
        }
}

enum class VolumeAction { UP, DOWN, MUTE, UNMUTE }

sealed interface Command {
    data class OpenApp(val query: String) : Command
    data class SetTimer(val seconds: Int) : Command
    data class SetAlarm(val hour: Int, val minute: Int) : Command
    data class Call(val query: String) : Command
    data class SendMessage(val contact: String, val message: String) : Command

    /**
     * Open an existing conversation with [contact], optionally in a named chat [app] ("whatsapp",
     * "whatsapp business", "messages"). A null app means the first chat app actually installed.
     */
    data class OpenChat(val contact: String, val app: String? = null) : Command
    data class Navigate(val place: String) : Command
    data class Tap(val target: String, val ordinal: Int? = null) : Command
    data class Scroll(val forward: Boolean, val times: Int = 1) : Command
    data object Back : Command
    data object Home : Command
    data object Recents : Command
    data object ReadScreen : Command
    data object OpenSettings : Command
    data class Dnd(val enable: Boolean, val minutes: Int? = null) : Command
    data class TypeText(val text: String) : Command
    data object Screenshot : Command
    data object ReadNotifications : Command
    data object ClearNotifications : Command
    data class OpenUrl(val url: String) : Command
    data class WebSearch(val query: String) : Command
    data class Search(
        val query: String,
        val scope: SearchScope = SearchScope.AUTO,
        val app: String? = null
    ) : Command
    data class AgentTask(val goal: String) : Command
    data class AskScreen(val question: String) : Command
    data class TranslateScreen(val language: String) : Command
    data object MediaToggle : Command
    data class FollowUp(val kind: FollowUpKind, val language: String? = null) : Command
    data class SaveRoutine(val name: String, val phrase: String? = null) : Command
    data class RunRoutine(val name: String) : Command
    data class DeleteRoutine(val name: String) : Command
    data object ListRoutines : Command
    data class Flashlight(val on: Boolean) : Command
    data class Volume(val action: VolumeAction, val steps: Int = 1) : Command
    data object BatteryStatus : Command
    data class ComposeEmail(val to: String, val subject: String) : Command
    data class NewEvent(val title: String) : Command
    data object ScreenActions : Command
}

object CommandParser {
    private val dndRegex = Regex(
        "^(?:turn\\s+)?(on|off)\\s+(?:do not disturb|dnd)" +
            "(?:\\s+for\\s+([a-z0-9]+(?:[- ][a-z0-9]+)?)\\s*(minutes?|mins?|hours?|hrs?))?$",
        RegexOption.IGNORE_CASE
    )
    private val readRegex = Regex(
        "^(?:(?:read|summarize|summarise|explain)\\s+(?:this\\s+)?(?:page|screen)|what(?:'s| is) on my screen)$",
        RegexOption.IGNORE_CASE
    )
    private val scrollRegex = Regex(
        "^scroll\\s+(down|up)(?:\\s+(twice|two times|2 times|three times|3 times|thrice))?$",
        RegexOption.IGNORE_CASE
    )
    private val screenshotRegex = Regex(
        "^(?:take|capture|grab)\\s+(?:a\\s+)?screenshot$|^screenshot$",
        RegexOption.IGNORE_CASE
    )
    private val readNotificationsRegex = Regex(
        "^(?:read|check|show|open)\\s+(?:my\\s+)?notifications$" +
            "|^what did i miss(?:\\s+while i was away)?$",
        RegexOption.IGNORE_CASE
    )
    private val clearNotificationsRegex = Regex(
        "^(?:clear|dismiss|delete)\\s+(?:all\\s+)?(?:my\\s+)?notifications$",
        RegexOption.IGNORE_CASE
    )
    private val mediaRegex = Regex(
        "^(?:play|pause|resume|toggle)\\s+(?:the\\s+)?(?:music|playback|media)$",
        RegexOption.IGNORE_CASE
    )
    private val timerRegex = Regex(
        "^(?:set\\s+(?:a\\s+)?timer\\s+(?:for\\s+)?|timer\\s+(?:for\\s+)?)" +
            "([a-z0-9]+(?:[- ][a-z0-9]+)?)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)$",
        RegexOption.IGNORE_CASE
    )
    private val alarmRegex = Regex(
        "^set\\s+(?:an?\\s+)?alarm\\s+(?:for\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?$",
        RegexOption.IGNORE_CASE
    )
    private val messageRegex = Regex(
        "^(?:send\\s+(?:a\\s+)?message\\s+to\\s+|text\\s+|message\\s+|whatsapp\\s+)(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val messageStartRegex = Regex("^(?:send|text|message|whatsapp)\\b", RegexOption.IGNORE_CASE)

    /**
     * "Go to chat of Pranav" is addressed to an existing conversation, not to an app and not to a
     * draft. These forms are matched before the app/search rules so the phrase never turns into an
     * app lookup for something called "chat of Pranav".
     */
    private val chatOfRegex = Regex(
        "^(?:go\\s+to\\s+|open\\s+|show\\s+me\\s+)?(?:the\\s+)?chat\\s+(?:of|with)\\s+(.+?)" +
            "(?:\\s+(?:on|in|using)\\s+(${ChatApp.SPOKEN_WORDS}))?$",
        RegexOption.IGNORE_CASE
    )
    private val chatAppFirstRegex = Regex(
        "^(?:go\\s+to\\s+|open\\s+)?(?:the\\s+)?(${ChatApp.SPOKEN_WORDS})\\s+(?:chat\\s+)?" +
            "(?:with|of)\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val chatWithOnRegex = Regex(
        "^(?:chat|talk)\\s+with\\s+(.+?)\\s+(?:on|in|using)\\s+(${ChatApp.SPOKEN_WORDS})$",
        RegexOption.IGNORE_CASE
    )
    private val openThenRegex = Regex(
        "^(?:open|launch|start)\\s+(?:the\\s+)?(.+?)\\s+(?:and\\s+)?then\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val openAndRegex = Regex(
        "^(?:open|launch|start)\\s+(?:the\\s+)?(.+?)\\s+and\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val messageSplitRegex =
        Regex("\\s+(?:saying|that says|to say)\\s+", RegexOption.IGNORE_CASE)
    private val callRegex = Regex("^(?:call|dial)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val navigateRegex =
        Regex("^(?:navigate|directions|take me)\\s+(?:to\\s+)?(.+)$", RegexOption.IGNORE_CASE)
    private val translateScreenRegex = Regex(
        "^translate\\s+(?:this|the)\\s+(?:page|screen)\\s+(?:to|into)\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val askScreenAboutRegex = Regex(
        "^ask\\s+(?:ai\\s+)?(?:about\\s+)?(?:this|the)\\s+(?:page|screen)\\s*[:,]?\\s*(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val askAiRegex = Regex("^ask\\s+ai\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val whatDoesScreenSayRegex = Regex(
        "^what does (?:this|the) (?:page|screen) say(?:\\s+about\\s+(.+))?$",
        RegexOption.IGNORE_CASE
    )
    private val typeRegex = Regex("^type\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val webSearchRegex = Regex(
        "^(?:search\\s+(?:the\\s+)?(?:web|internet|online)(?:\\s+for)?|google\\s+for|look\\s+up)\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val currentAppSearchRegex = Regex(
        "^(?:search|find|look)\\s+(?:in\\s+)?(?:this|the current)\\s+(?:app|screen)\\s+for\\s+(.+)$" +
            "|^(?:search|find|look)\\s+(?:for\\s+)?(.+?)\\s+(?:in this app|in the current app|on this page|on this screen|here)$",
        RegexOption.IGNORE_CASE
    )
    /** "in X" usually names an app, but these clearly don’t, so the plain search path keeps them. */
    private val NON_APP_TARGETS = setOf("here", "this", "web", "google", "the web", "internet", "browser")

    private val inAppSearchRegex = Regex(
        "^(?:search|find|look)\\s+(?:for\\s+)?(.+?)\\s+in\\s+(?:the\\s+)?([a-z0-9 .+_-]{2,40}?)\\s*(?:app|application)?$",
        RegexOption.IGNORE_CASE
    )

    private val bareSearchRegex = Regex(
        "^(?:search|find)\\s+(?:for\\s+)?(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val goToRegex = Regex("^(?:go to|visit)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val tapRegex =
        Regex("^(?:tap|click|press|select|choose)\\s+(?:the\\s+)?(.+)$", RegexOption.IGNORE_CASE)
    private val openResultRegex = Regex(
        "^open\\s+(?:the\\s+)?((?:first|1st|second|2nd|third|3rd|fourth|4th|fifth|5th|\\d{1,2}(?:st|nd|rd|th)?))" +
            "\\s+(?:result|option|item|link|one)$",
        RegexOption.IGNORE_CASE
    )
    private val ordinalRegex = Regex(
        "^(first|1st|second|2nd|third|3rd|fourth|4th|fifth|5th)\\s+(?:result|option|item|link|one)$",
        RegexOption.IGNORE_CASE
    )
    private val openRegex =
        Regex("^(?:open|launch|start)\\s+(?:the\\s+)?(.+?)(?:\\s+app)?$", RegexOption.IGNORE_CASE)
    private val scrollFindRegex = Regex(
        "^(?:scroll\\s+(?:down\\s+)?(?:until\\s+(?:you\\s+)?(?:see|find|reach)|to)\\s+.+" +
            "|(?:find|look\\s+for)\\s+.+\\s+by\\s+scrolling.*)$",
        RegexOption.IGNORE_CASE
    )
    private val shorterRegex = Regex(
        "^(?:make\\s+(?:it|that|this)\\s+(?:a\\s+bit\\s+|slightly\\s+)?(?:more\\s+)?shorter|shorten\\s+(?:it|that|this))$",
        RegexOption.IGNORE_CASE
    )
    private val longerRegex = Regex(
        "^(?:make\\s+(?:it|that|this)\\s+(?:a\\s+bit\\s+|slightly\\s+)?(?:more\\s+)?longer|expand\\s+(?:it|that|this)|lengthen\\s+(?:it|that|this))$",
        RegexOption.IGNORE_CASE
    )
    private val professionalRegex = Regex(
        "^(?:make\\s+(?:it|that|this)\\s+(?:sound\\s+|more\\s+)?professional|make\\s+(?:it|that|this)\\s+formal|formalize\\s+(?:it|that|this))$",
        RegexOption.IGNORE_CASE
    )
    private val casualRegex = Regex(
        "^(?:make\\s+(?:it|that|this)\\s+(?:sound\\s+|more\\s+)?casual|make\\s+(?:it|that|this)\\s+(?:friendlier|more\\s+friendly))$",
        RegexOption.IGNORE_CASE
    )
    private val tidyRegex = Regex(
        "^(?:fix\\s+(?:the\\s+)?(?:grammar|spelling|punctuation)(?:\\s+(?:of|in)\\s+(?:it|that|this))?" +
            "|fix\\s+(?:it|that|this)|clean\\s+(?:it|that|this)\\s+up|tidy\\s+(?:it|that|this)\\s+up)$",
        RegexOption.IGNORE_CASE
    )
    private val undoRegex = Regex("^(?:undo(?:\\s+(?:that|it))?|revert(?:\\s+(?:that|it))?)$", RegexOption.IGNORE_CASE)
    private val againRegex = Regex(
        "^(?:try\\s+(?:that\\s+)?again|do\\s+(?:that|it)\\s+again|run\\s+(?:that|it)\\s+again|repeat(?:\\s+that)?|again)$",
        RegexOption.IGNORE_CASE
    )
    private val copyThatRegex = Regex(
        "^(?:copy\\s+(?:that|it|this)|copy\\s+(?:the\\s+)?last\\s+(?:result|answer))$",
        RegexOption.IGNORE_CASE
    )
    private val summarizeThatRegex = Regex("^summari[sz]e\\s+(?:that|it|this)$", RegexOption.IGNORE_CASE)
    private val followTranslateRegex =
        Regex("^translate\\s+(?:that|it|this)\\s+(?:to|into)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val flashlightRegex = Regex(
        "^(?:(?:turn|switch)\\s+)?(?:(on|off)\\s+(?:the\\s+)?(?:flashlight|torch)|(?:flashlight|torch)\\s+(on|off))$",
        RegexOption.IGNORE_CASE
    )
    private val volumeRegex = Regex(
        "^(?:turn\\s+)?(?:the\\s+)?(?:media\\s+)?volume\\s+(up|down)(?:\\s+(?:by\\s+)?(.+))?$",
        RegexOption.IGNORE_CASE
    )
    private val muteRegex = Regex(
        "^(?:(mute|unmute)(?:\\s+(?:the\\s+)?(?:media\\s+)?(?:volume|sound|audio))?)$",
        RegexOption.IGNORE_CASE
    )
    private val batteryRegex = Regex(
        "^(?:battery(?:\\s+(?:status|level))?|how(?:'s|\\s+is)\\s+(?:my|the)\\s+battery" +
            "|how\\s+much\\s+battery\\s+(?:do\\s+i\\s+have|is\\s+left)|what(?:'s|\\s+is)\\s+my\\s+battery(?:\\s+at)?)$",
        RegexOption.IGNORE_CASE
    )
    private val emailRegex = Regex(
        "^(?:(?:compose|write|draft|send)\\s+(?:an?\\s+)?email\\s+to|email)\\s+(.+?)\\s+" +
            "(?:about|regarding|with\\s+the\\s+subject|saying)\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val eventRegex = Regex(
        "^(?:create|add|new|schedule|make)\\s+(?:an?\\s+)?(?:calendar\\s+)?(?:event|meeting|appointment)" +
            "(?:\\s+(?:for|called|named|titled|about))?\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val saveRoutineRegex = Regex(
        "^(?:save|create|add)\\s+(?:a\\s+)?routine\\s+(?:called\\s+|named\\s+)?(.+?)\\s+(?:for|to|that)\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val saveLastRoutineRegex = Regex(
        "^(?:save|create|add)\\s+(?:this|that)\\s+as\\s+(?:a\\s+)?routine(?:\\s+(?:called|named))?\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val runRoutineRegex = Regex(
        "^(?:run|start|do|use|play)\\s+(?:the\\s+)?routine\\s+(?:called\\s+|named\\s+)?(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val deleteRoutineRegex = Regex(
        "^(?:delete|remove|forget)\\s+(?:the\\s+)?routine\\s+(?:called\\s+|named\\s+)?(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val listRoutinesRegex = Regex(
        "^(?:(?:list|show)(?:\\s+me)?(?:\\s+my)?\\s+routines|what\\s+routines\\s+(?:do\\s+i\\s+have|are\\s+saved))$",
        RegexOption.IGNORE_CASE
    )
    private val screenActionsRegex = Regex(
        "^(?:what\\s+can\\s+i\\s+do\\s+(?:here|on\\s+this\\s+(?:screen|page))|what\\s+can\\s+i\\s+tap(?:\\s+here)?" +
            "|show\\s+(?:me\\s+)?(?:smart|quick)\\s+actions(?:\\s+here)?" +
            "|smart\\s+actions|what\\s+are\\s+my\\s+options(?:\\s+here)?)$",
        RegexOption.IGNORE_CASE
    )
    private val chainRegex = Regex("\\s+(?:and\\s+)?then\\s+", RegexOption.IGNORE_CASE)
    private val actionStartRegex = Regex(
        "^(?:open|launch|start|go to|visit|search|find|look|tap|click|press|type|scroll|swipe|" +
            "read|summarize|summarise|translate|ask|take|capture)\\b",
        RegexOption.IGNORE_CASE
    )

    private const val MAX_VERB_STRIPPED_WORDS = 4

    /**
     * Words that mean "open this app", matching the same verbs `openRegex` accepts. Romanised Hindi
     * is included because a code-mixed request is still an app request.
     */
    private val openVerbs = listOf("open", "launch", "start", "kholo", "khol")

    /**
     * Removes a leading request verb, spelled exactly or one edit away, so the rest of the phrase
     * can be scored against the installed apps on its own instead of being diluted by the verb.
     * Longer phrases are returned untouched: they read as dictation, not a command with a mistyped
     * verb, and stripping them would turn ordinary sentences into app guesses.
     */
    /**
     * True when the phrase opens with a request verb, spelled exactly or one edit away. A misheard
     * verb is still a request, which is what lets "opem youube" be read as a name instead of text.
     */
    fun startsWithActionVerb(raw: String): Boolean {
        val parts = collapse(raw).split(" ").filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > MAX_VERB_STRIPPED_WORDS) return false
        val first = parts.first().lowercase()
        if (first.length < 3) return false
        return openVerbs.any { verb ->
            first == verb || (verb.length >= 4 && PhraseResolver.editDistance(first, verb) <= 1)
        }
    }

    /**
     * Removes the leading request verb so the rest can be scored against the installed apps on its
     * own instead of being diluted by the verb. Longer phrases are returned untouched: they read as
     * dictation, and stripping them would turn ordinary sentences into app guesses.
     */
    fun stripActionVerb(raw: String): String {
        val text = collapse(raw)
        if (!startsWithActionVerb(raw)) return text
        val rest = text.substringAfter(' ').trim()
        return if (rest.isEmpty()) text else rest.removePrefix("the ").trim()
    }

    private fun collapse(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

    fun parse(raw: String): Command? {
        val rewritten = HindiCommands.rewrite(raw)
        return parseOnce(rewritten ?: raw)
    }

    /** The English grammar. A Hindi phrase is rewritten into it before it arrives. */
    private fun parseOnce(raw: String): Command? {
        val text = raw.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return null

        dndRegex.matchEntire(text)?.let { match ->
            val enable = match.groupValues[1].equals("on", true)
            val value = parseCount(match.groupValues[2])
            val unit = match.groupValues[3].lowercase()
            val minutes = if (value != null && value > 0) {
                if (unit.startsWith("h")) value * 60 else value
            } else {
                null
            }
            return Command.Dnd(enable, if (enable) minutes else null)
        }

        val lower = text.lowercase()
        if (lower in setOf("settings", "open settings", "system settings")) return Command.OpenSettings
        if (readRegex.matches(text)) return Command.ReadScreen
        scrollRegex.matchEntire(text)?.let {
            val times = when (it.groupValues[2].lowercase()) {
                "twice", "two times", "2 times" -> 2
                "three times", "3 times", "thrice" -> 3
                else -> 1
            }
            return Command.Scroll(it.groupValues[1].equals("down", true), times)
        }
        if (scrollFindRegex.matches(text)) return Command.AgentTask(text)

        followTranslateRegex.matchEntire(text)?.let { match ->
            val language = match.groupValues[1].trim().trimEnd('.', '!', '?')
            if (language.isNotBlank()) return Command.FollowUp(FollowUpKind.TRANSLATE, language)
        }
        when {
            shorterRegex.matches(text) -> return Command.FollowUp(FollowUpKind.SHORTER)
            longerRegex.matches(text) -> return Command.FollowUp(FollowUpKind.LONGER)
            professionalRegex.matches(text) -> return Command.FollowUp(FollowUpKind.PROFESSIONAL)
            casualRegex.matches(text) -> return Command.FollowUp(FollowUpKind.CASUAL)
            tidyRegex.matches(text) -> return Command.FollowUp(FollowUpKind.TIDY)
            summarizeThatRegex.matches(text) -> return Command.FollowUp(FollowUpKind.SUMMARIZE)
            undoRegex.matches(text) -> return Command.FollowUp(FollowUpKind.UNDO)
            againRegex.matches(text) -> return Command.FollowUp(FollowUpKind.AGAIN)
            copyThatRegex.matches(text) -> return Command.FollowUp(FollowUpKind.COPY)
        }

        saveRoutineRegex.matchEntire(text)?.let { match ->
            val name = cleanRoutineName(match.groupValues[1])
            val phrase = match.groupValues[2].trim().trimEnd('.', '!')
            if (name.isNotBlank() && phrase.isNotBlank()) return Command.SaveRoutine(name, phrase)
        }
        saveLastRoutineRegex.matchEntire(text)?.let { match ->
            val name = cleanRoutineName(match.groupValues[1])
            if (name.isNotBlank()) return Command.SaveRoutine(name, null)
        }
        runRoutineRegex.matchEntire(text)?.let { match ->
            val name = cleanRoutineName(match.groupValues[1])
            if (name.isNotBlank()) return Command.RunRoutine(name)
        }
        deleteRoutineRegex.matchEntire(text)?.let { match ->
            val name = cleanRoutineName(match.groupValues[1])
            if (name.isNotBlank()) return Command.DeleteRoutine(name)
        }
        if (listRoutinesRegex.matches(text)) return Command.ListRoutines

        if (screenActionsRegex.matches(text)) return Command.ScreenActions

        flashlightRegex.matchEntire(text)?.let { match ->
            val state = match.groupValues[1].ifBlank { match.groupValues[2] }
            if (state.isNotBlank()) return Command.Flashlight(state.equals("on", true))
        }
        volumeRegex.matchEntire(text)?.let { match ->
            val raw = match.groupValues[2].trim()
            val steps = if (raw.isBlank()) 1 else parseCount(raw)
            if (steps != null && steps in 1..10) {
                val action = if (match.groupValues[1].equals("up", true)) VolumeAction.UP else VolumeAction.DOWN
                return Command.Volume(action, steps)
            }
        }
        muteRegex.matchEntire(text)?.let { match ->
            return Command.Volume(
                if (match.groupValues[1].equals("mute", true)) VolumeAction.MUTE else VolumeAction.UNMUTE
            )
        }
        if (batteryRegex.matches(text)) return Command.BatteryStatus

        emailRegex.matchEntire(text)?.let { match ->
            val to = match.groupValues[1].trim()
            val subject = match.groupValues[2].trim().trimEnd('.', '!')
            if (to.isNotBlank() && subject.isNotBlank()) return Command.ComposeEmail(to, subject)
        }
        eventRegex.matchEntire(text)?.let { match ->
            val title = match.groupValues[1].trim().trimEnd('.', '!')
            if (title.isNotBlank()) return Command.NewEvent(title)
        }

        when (lower) {
            "back", "go back" -> return Command.Back
            "home", "go home" -> return Command.Home
            "recents", "recent apps", "open recents" -> return Command.Recents
        }

        if (screenshotRegex.matches(text)) return Command.Screenshot
        if (readNotificationsRegex.matches(text)) return Command.ReadNotifications
        if (clearNotificationsRegex.matches(text)) return Command.ClearNotifications
        if (mediaRegex.matches(text)) return Command.MediaToggle

        timerRegex.matchEntire(text)?.let { match ->
            val value = parseCount(match.groupValues[1]) ?: return@let
            val unit = match.groupValues[2].lowercase()
            val multiplier = when {
                unit.startsWith("sec") -> 1
                unit.startsWith("min") -> 60
                else -> 3600
            }
            if (value > 0) return Command.SetTimer(value * multiplier)
        }

        alarmRegex.matchEntire(text)?.let { match ->
            val rawHour = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = match.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: return@let
            var hour = rawHour
            when (match.groupValues[3].lowercase()) {
                "pm" -> if (hour < 12) hour += 12
                "am" -> if (hour == 12) hour = 0
            }
            if (hour in 0..23 && minute in 0..59) return Command.SetAlarm(hour, minute)
        }

        chatOfRegex.matchEntire(text)?.let { match ->
            parseOpenChat(match.groupValues[1], match.groupValues[2])?.let { return it }
        }
        chatAppFirstRegex.matchEntire(text)?.let { match ->
            parseOpenChat(match.groupValues[2], match.groupValues[1])?.let { return it }
        }
        chatWithOnRegex.matchEntire(text)?.let { match ->
            parseOpenChat(match.groupValues[1], match.groupValues[2])?.let { return it }
        }

        messageRegex.matchEntire(text)?.let { match ->
            parseMessageRest(match.groupValues[1])?.let { return it }
            // "WhatsApp Pranav" has no body to send, so it can only mean the existing conversation.
            if (lower.startsWith("whatsapp ")) {
                val only = cleanContactName(match.groupValues[1])
                if (only.isNotBlank() && !only.contains(' ') && only.length <= MAX_CONTACT_CHARS) {
                    return Command.OpenChat(only, "whatsapp")
                }
            }
        }

        callRegex.matchEntire(text)?.let { match ->
            val target = match.groupValues[1].trim()
            if (target.isNotBlank()) return Command.Call(target)
        }

        navigateRegex.matchEntire(text)?.let { match ->
            val place = match.groupValues[1].trim()
            if (place.isNotBlank()) return Command.Navigate(place)
        }

        translateScreenRegex.matchEntire(text)?.let { match ->
            val language = match.groupValues[1].trim()
            if (language.isNotBlank()) return Command.TranslateScreen(language)
        }

        askScreenAboutRegex.matchEntire(text)?.let { match ->
            val question = match.groupValues[1].trim()
            if (question.isNotBlank()) return Command.AskScreen(question)
        }

        askAiRegex.matchEntire(text)?.let { match ->
            val question = match.groupValues[1].trim()
            if (question.isNotBlank()) return Command.AskScreen(question)
        }

        whatDoesScreenSayRegex.matchEntire(text)?.let { match ->
            val topic = match.groupValues[1].trim()
            return Command.AskScreen(
                if (topic.isBlank()) "What does this screen show?" else "What does this screen say about $topic?"
            )
        }

        typeRegex.matchEntire(text)?.let { match ->
            val content = match.groupValues[1].trim()
            if (content.isNotBlank()) return Command.TypeText(content)
        }

        webSearchRegex.matchEntire(text)?.let { match ->
            val query = match.groupValues[1].trim()
            if (query.isNotBlank()) return Command.WebSearch(query)
        }

        currentAppSearchRegex.matchEntire(text)?.let { match ->
            val query = match.groupValues[1].ifBlank { match.groupValues[2] }.trim()
            if (query.isNotBlank()) return Command.Search(query, SearchScope.CURRENT)
        }

        inAppSearchRegex.matchEntire(text)?.let { match ->
            val query = match.groupValues[1].trim()
            val app = match.groupValues[2].trim().removeSuffix("the").trim()
            if (query.isNotBlank() && app.isNotBlank() && app.lowercase() !in NON_APP_TARGETS) {
                return Command.Search(query, SearchScope.AUTO, app)
            }
        }

        bareSearchRegex.matchEntire(text)?.let { match ->
            val query = match.groupValues[1].trim()
            if (query.isNotBlank() && query.lowercase() !in setOf("settings", "notifications")) {
                return Command.Search(query, SearchScope.AUTO)
            }
        }

        goToRegex.matchEntire(text)?.let { match ->
            val target = match.groupValues[1].trim()
            if (target.isNotBlank()) {
                ActionParsing.canonicalUrl(target)?.let { return Command.OpenUrl(it) }
                if (target.lowercase() in setOf("settings", "the settings", "system settings")) {
                    return Command.OpenSettings
                }
                return Command.OpenApp(target.removeSuffix(" app").trim())
            }
        }

        openResultRegex.matchEntire(text)?.let { match ->
            val word = match.groupValues[1].trim()
            val ordinal = ordinalIndex(word) ?: return@let
            return Command.Tap(word, ordinal)
        }

        (openThenRegex.matchEntire(text) ?: openAndRegex.matchEntire(text))?.let { match ->
            val app = match.groupValues[1].trim()
            val rest = match.groupValues[2].trim()
            if (app.isNotBlank() && rest.isNotBlank()) {
                if (messageStartRegex.containsMatchIn(rest)) {
                    parseMessageRest(rest)?.let { return it }
                }
                return Command.AgentTask(text)
            }
        }

        if (chainRegex.containsMatchIn(text) && actionStartRegex.containsMatchIn(text)) {
            return Command.AgentTask(text)
        }

        if (LocalPlanner.isControlPhrase(text)) return Command.AgentTask(text)

        tapRegex.matchEntire(text)?.let { match ->
            val target = match.groupValues[1].trim()
            val ordinal = ordinalRegex.matchEntire(target)?.let { ordinalIndex(it.groupValues[1]) }
            if (ordinal != null) return Command.Tap(target, ordinal)
            if (target.isNotBlank()) return Command.Tap(target)
        }

        openRegex.matchEntire(text)?.let { match ->
            val query = match.groupValues[1].trim()
            if (query.isNotBlank()) {
                ActionParsing.canonicalUrl(query)?.let { return Command.OpenUrl(it) }
                return Command.OpenApp(query)
            }
        }

        return null
    }

    private const val MAX_CONTACT_CHARS = 40

    /** "chat with you later" is dictation, not a request to find a contact named "you later". */
    private val CHAT_PRONOUNS = setOf("me", "you", "him", "her", "us", "them", "it")

    private fun parseOpenChat(rawContact: String, rawApp: String): Command.OpenChat? {
        val contact = cleanContactName(rawContact)
        if (contact.isBlank() || contact.length > MAX_CONTACT_CHARS) return null
        if (contact.substringBefore(' ').lowercase() in CHAT_PRONOUNS) return null
        val app = cleanContactName(rawApp).lowercase().takeIf { it.isNotBlank() }
        return Command.OpenChat(contact, app)
    }

    private fun cleanContactName(raw: String): String =
        raw.trim().trimEnd('.', '!', '?', ',').removeSuffix("'s").trim()

    private fun cleanRoutineName(raw: String): String =
        raw.trim().trimEnd('.', '!', '?', ',').take(40).trim()

    private fun parseMessageRest(rawRest: String): Command.SendMessage? {
        var rest = rawRest.trim()
        messageRegex.matchEntire(rest)?.let { rest = it.groupValues[1].trim() }
        if (rest.isBlank()) return null
        val split = messageSplitRegex.split(rest, limit = 2)
        val contact: String
        val message: String
        if (split.size == 2) {
            contact = split[0].trim()
            message = split[1].trim()
        } else {
            val space = rest.indexOf(' ')
            if (space <= 0) return null
            contact = rest.substring(0, space).trim()
            message = rest.substring(space + 1).trim()
        }
        return if (contact.isNotBlank() && message.isNotBlank()) {
            Command.SendMessage(contact, message)
        } else {
            null
        }
    }

    private fun parseCount(raw: String): Int? {
        val cleaned = raw.trim().lowercase().replace("-", " ")
        if (cleaned.isEmpty()) return null
        cleaned.toIntOrNull()?.let { return it }
        val parts = cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size == 1) return COUNT_WORDS[parts[0]]
        if (parts.size == 2) {
            val tens = COUNT_WORDS[parts[0]] ?: return null
            val ones = COUNT_WORDS[parts[1]] ?: return null
            if (tens % 10 == 0 && tens >= 20 && ones in 1..9) return tens + ones
        }
        return null
    }

    private val COUNT_WORDS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30,
        "forty" to 40, "fifty" to 50, "sixty" to 60, "ninety" to 90
    )

    private fun ordinalIndex(word: String): Int? = when (word.lowercase()) {
        "first", "1st" -> 1
        "second", "2nd" -> 2
        "third", "3rd" -> 3
        "fourth", "4th" -> 4
        "fifth", "5th" -> 5
        else -> word.toIntOrNull()
    }
}
