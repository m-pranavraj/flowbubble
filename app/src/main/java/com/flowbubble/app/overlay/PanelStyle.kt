package com.flowbubble.app.overlay

/**
 * The overlay card's design decisions that need no Android framework: which tone a panel wears,
 * how its body is broken up, which role each button gets, and the geometry the view layer
 * measures against. Keeping it here means the whole visual grammar is unit-testable on the JVM
 * and the view code stays a renderer instead of a pile of inline conditionals.
 *
 * Everything is derived from the fields [PanelContent] already carries; the contract never widens.
 */

/** The mood the card has to broadcast before a single word is read. */
enum class PanelTone {
    /** A confirmation or choice that is waiting for the user and blocks the task until then. */
    Await,

    /** Multi-step work in flight. */
    Working,

    /** Something finished and the system verified it. */
    Verified,

    /** Finished, but with an unverified step or a caveat the user should read. */
    Caution,

    /** Refused, failed or stopped. */
    Alert,

    /** A transient pill or a menu with no outcome to report. */
    Info
}

/** How tall and how dressed-up the card is. */
enum class PanelShape {
    /** Full card: header, body surface, actions. */
    Sheet,

    /** One-line acknowledgement that erases itself. */
    Pill
}

/** Reading style for one paragraph of the body. */
enum class PanelBodyKind {
    /** A short status line, set directly on the card. */
    Status,

    /** A numbered execution trace with verification lines. */
    Trace,

    /** Screen text in quotes — someone else's words, not the assistant's. */
    Quote,

    /** A long run of text the assistant is showing rather than saying. */
    Reading
}

/** Visual weight of a button. */
enum class ActionRole {
    /** Filled lavender gradient: the affirmative path. */
    Primary,

    /** Crimson to burgundy: this presses a button on the outside world. */
    Outbound,

    /** Tinted outline: a deliberate alternative. */
    Secondary,

    /** Text only: closing, dismissing, moving on. */
    Quiet
}

/** How the action list is arranged. */
enum class ActionLayout { Single, Row, Stack }

data class PanelBodyBlock(
    val kind: PanelBodyKind,
    val text: String,
    val isLast: Boolean
)

object PanelStyle {

    // ---------------------------------------------------------------------------------------------
    // Geometry. All dp, no Android types; the view multiplies by density.
    // ---------------------------------------------------------------------------------------------

    const val CARD_RADIUS_DP = 28f
    const val PILL_RADIUS_DP = 24f
    const val SURFACE_RADIUS_DP = 18f
    const val BUTTON_RADIUS_DP = 16f
    const val BUTTON_HEIGHT_DP = 46f
    const val MIN_TOUCH_DP = 44f
    const val CARD_SIDE_PADDING_DP = 16f
    const val WINDOW_BOTTOM_OFFSET_DP = 96f

    /** Characters of body text kept before the card would grow past the screen. */
    fun bodyCharBudget(scrollable: Boolean): Int = if (scrollable) 3000 else 720

    /**
     * Height the body surface may reach before it scrolls internally.
     * Zero means "let the text take what it needs", which is what short status lines want.
     */
    fun bodyMaxHeightDp(kind: PanelBodyKind, scrollable: Boolean): Float = when (kind) {
        PanelBodyKind.Trace -> if (scrollable) 172f else 0f
        PanelBodyKind.Reading -> if (scrollable) 156f else 0f
        PanelBodyKind.Quote -> if (scrollable) 136f else 0f
        PanelBodyKind.Status -> 0f
    }

    fun titleMaxLines(shape: PanelShape): Int = if (shape == PanelShape.Pill) 2 else 3

    /** Truncation that keeps the head of a trace readable instead of cutting it mid-word. */
    fun clampBody(text: String, maxChars: Int): String {
        if (maxChars <= 0 || text.length <= maxChars) return text
        val head = text.take(maxChars - 1)
        val cutAt = head.lastIndexOf('\n').takeIf { it > maxChars / 2 } ?: (maxChars - 1)
        return head.take(cutAt).trimEnd() + "\u2026"
    }

    /** Alpha blending for ARGB ints, kept here so the view layer has no arithmetic in it. */
    fun withAlpha(argb: Int, alphaPercent: Int): Int {
        val a = alphaPercent.coerceIn(0, 100)
        val byte = (a * 255 + 50) / 100
        return (byte shl 24) or (argb and 0x00FFFFFF)
    }

    // ---------------------------------------------------------------------------------------------
    // Tone
    // ---------------------------------------------------------------------------------------------

    private val runningTitles = setOf(
        "summarizing", "rewriting", "asking", "working", "opening", "running", "reading",
        "preparing", "searching", "loading", "planning", "starting", "typing", "checking",
        "dialing", "generating", "applying", "sending", "listening", "figuring", "choosing"
    )

    private val alertWords = setOf(
        "failed", "couldn't", "couldnt", "cannot", "unable", "unavailable", "missing",
        "blocked", "denied", "error", "nowhere", "unsupported", "expired", "offline",
        "refused", "crashed", "timed", "stopped"
    )

    private val cautionWords = setOf(
        "notes", "careful", "review", "verify", "instead", "uncertain", "partially",
        "manually", "warning", "unsure", "assumption", "mostly"
    )

    private val cautionPhrases = setOf(
        "not confirmed", "doesn't report", "does not report", "couldn't confirm",
        "i couldn't verify", "check the", "no system evidence", "if you meant",
        "nothing is verified", "not verified"
    )

    private val verifiedWords = setOf(
        "complete", "done", "inserted", "copied", "tapped", "sent", "opened", "created",
        "updated", "deleted", "removed", "undone", "reverted", "muted", "unmuted", "saved",
        "found", "toggled", "summarized", "answered", "cleared", "paused", "resumed",
        "dialer", "replaced", "launched", "set", "ready", "accepted"
    )

    fun toneOf(content: PanelContent): PanelTone {
        val labels = content.actions.map { it.label }
        val awaiting = !content.dismissible &&
            (content.actions.size >= 2 || content.voicePrompt != null)
        if (awaiting) return PanelTone.Await

        val titleWords = tokenize(content.title)
        val isProgress = titleWords.any { it in runningTitles } ||
            STEP_TITLE.matches(content.title.trim())
        if (isProgress) return PanelTone.Working

        val text = tokenize(content.title + " " + content.body)
        val joined = (content.title + "\n" + content.body).lowercase()
        if (text.any { it in alertWords }) return PanelTone.Alert
        if (text.any { it in cautionWords }) return PanelTone.Caution
        if (cautionPhrases.any { joined.contains(it) }) return PanelTone.Caution
        if (text.any { it in verifiedWords }) return PanelTone.Verified
        return PanelTone.Info
    }

    /** The small uppercase line above the title. Empty means show no eyebrow at all. */
    fun eyebrow(tone: PanelTone): String = when (tone) {
        PanelTone.Await -> "Needs your OK"
        PanelTone.Working -> "Working"
        PanelTone.Verified -> "Verified"
        PanelTone.Caution -> "Check this"
        PanelTone.Alert -> "Not done"
        PanelTone.Info -> ""
    }

    fun shapeOf(content: PanelContent): PanelShape {
        val timeout = content.autoDismissMs
        if (content.actions.isNotEmpty()) return PanelShape.Sheet
        if (timeout == null || content.scrollable) return PanelShape.Sheet
        if (!content.dismissible) return PanelShape.Sheet
        if (content.title.length + content.body.length > 190) return PanelShape.Sheet
        return PanelShape.Pill
    }

    // ---------------------------------------------------------------------------------------------
    // Body
    // ---------------------------------------------------------------------------------------------

    /**
     * Splits the body on blank lines and gives each paragraph a reading style. The last paragraph
     * of a multi-paragraph body is usually a caption ("I won't do anything until you choose"),
     * so it is flagged rather than dressed identically to the content above it.
     */
    fun blocks(body: String, scrollable: Boolean): List<PanelBodyBlock> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()
        val parts = BLANK_LINE.split(trimmed).filter { it.isNotBlank() }
        if (parts.isEmpty()) return emptyList()
        val kinds = parts.map { blockKind(it, scrollable) }
        return parts.mapIndexed { index, part ->
            PanelBodyBlock(kinds[index], part.trim(), index == parts.lastIndex)
        }
    }

    fun blockKind(block: String, scrollable: Boolean): PanelBodyKind {
        val text = block.trim()
        if (isQuoted(text)) return PanelBodyKind.Quote
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val numbered = lines.count { STEP_PREFIX.containsMatchIn(it) }
        val evidence = text.contains("confirmed") || text.contains("\u2014")
        if (lines.size >= 2 && (numbered >= 2 || (numbered >= 1 && evidence && scrollable))) {
            return PanelBodyKind.Trace
        }
        if (text.count { it == '\n' } >= 2 || text.length > 220) return PanelBodyKind.Reading
        return PanelBodyKind.Status
    }

    fun isQuoted(text: String): Boolean {
        val t = text.trim()
        if (t.length < 3) return false
        return t.first() in OPEN_QUOTES && t.last() in CLOSE_QUOTES
    }

    /**
     * A trailing short paragraph is a caption, not content: smaller and quieter.
     */
    fun isCaption(block: PanelBodyBlock, blockCount: Int): Boolean =
        block.isLast && blockCount > 1 && block.kind == PanelBodyKind.Status &&
            block.text.length <= 160

    // ---------------------------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------------------------

    private val rejectWords = setOf(
        "cancel", "no", "stop", "deny", "never", "decline", "abort", "skip"
    )

    private val dismissiveWords = setOf(
        "close", "dismiss", "ok", "okay", "got", "not", "now", "fine", "done", "understood"
    )

    /**
     * Verbs that put something out beyond the phone. Only ever matched as the *first* word of a
     * label, so an app name like "WhatsApp" in a choice list is not mistaken for an outbound verb.
     */
    private val outboundVerbs = setOf(
        "send", "share", "post", "publish", "email", "mail", "call", "dial", "message",
        "upload", "submit", "delete", "remove", "erase", "pay", "buy", "forward", "reply"
    )

    private val outboundPhrases = setOf(
        "leaves your device", "leave your device", "sends the visible", "sends your",
        "send your message", "will send", "is sent to", "sent to ", "calls ", "dial",
        "uploads", "shares this", "posts this", "publishes", "opens the dialer"
    )

    /**
     * Button hierarchy. An outbound action is painted as one no matter how the caller flagged it,
     * because "this presses a button on the outside world" outranks "the caller thinks this is
     * the primary button". Rejection wording never takes the outbound paint.
     */
    fun roleOf(
        label: String,
        primary: Boolean,
        tone: PanelTone,
        title: String,
        body: String,
        actionCount: Int
    ): ActionRole {
        val words = tokenize(label)
        val first = words.firstOrNull()
        val rejects = first in rejectWords || (first == "not" && words.getOrNull(1) == "now")
        val dismissive = words.any { it in dismissiveWords }
        if (rejects) {
            // Stopping or refusing is a real decision, so it keeps a tappable shape: outlined when
            // it is alone or facing an approval, plain text when it merely tidies up a result card.
            return when {
                actionCount <= 1 -> ActionRole.Secondary
                tone == PanelTone.Await -> ActionRole.Secondary
                else -> ActionRole.Quiet
            }
        }
        if (dismissive && !primary) return ActionRole.Quiet
        val outboundByLabel = words.firstOrNull() in outboundVerbs
        val outboundByContext = outboundContext(title, body)
        if (outboundByLabel || (outboundByContext && tone == PanelTone.Await)) {
            return ActionRole.Outbound
        }
        if (primary) return ActionRole.Primary
        return ActionRole.Secondary
    }

    /**
     * Context only paints a button crimson while a confirmation is outstanding: an approval that
     * commits an outbound action ("Send screen text to AI?" -> Approve) is the outbound button even
     * though its label is neutral. Once the work is done, only the label's own verb counts, so a
     * result card's "Copy" stays lavender however the quoted text reads.
     */
    fun outboundContext(title: String, body: String): Boolean {
        val joined = (title + " " + body).lowercase()
        if (outboundPhrases.any { joined.contains(it) }) return true
        return tokenize(title).any { it in outboundVerbs }
    }

    fun isDestructive(label: String, title: String, body: String): Boolean =
        roleOf(label, true, PanelTone.Await, title, body, 2) == ActionRole.Outbound

    /** Row buttons get a compact, tracked label; stacked buttons keep the caller's wording. */
    fun buttonLabel(label: String, layout: ActionLayout): String =
        if (layout == ActionLayout.Row) label.trim().uppercase() else label.trim()

    fun layoutOf(labels: List<String>): ActionLayout = when {
        labels.isEmpty() -> ActionLayout.Single
        labels.size == 1 -> ActionLayout.Single
        labels.size == 2 && labels.all { it.length <= 16 } &&
            labels.sumOf { it.length } <= 30 -> ActionLayout.Row
        labels.size == 3 && labels.all { it.length <= 10 } -> ActionLayout.Row
        else -> ActionLayout.Stack
    }

    /** Row weights: the affirmative button keeps the wider half of the row. */
    fun weightsFor(roles: List<ActionRole>): List<Float> = roles.map { role ->
        when (role) {
            ActionRole.Primary, ActionRole.Outbound -> 1.7f
            ActionRole.Secondary -> 1.1f
            ActionRole.Quiet -> 0.85f
        }
    }

    /**
     * Display order for a two-button row, as indices into the caller's list. A confirmation puts
     * the button that commits on the right, where Android dialogs put the affirmative; every other
     * card keeps the order the caller wrote, because that order already carries meaning.
     */
    fun orderedFor(roles: List<ActionRole>, tone: PanelTone): List<Int> {
        if (tone != PanelTone.Await || roles.size != 2) return roles.indices.toList()
        val firstIsAffirmative = roles[0] == ActionRole.Primary || roles[0] == ActionRole.Outbound
        return if (firstIsAffirmative) listOf(1, 0) else listOf(0, 1)
    }

    /** The auto-Copy button is only added when the caller did not already wire one up. */
    fun shouldAppendCopy(copyText: String?, labels: List<String>): Boolean =
        !copyText.isNullOrBlank() && labels.none { it.equals("Copy", ignoreCase = true) }

    /**
     * Hands-free wording stays on the card only when the panel can actually answer it, which is
     * what the service checks before it starts listening for yes or no.
     */
    fun showHandsFreeHint(hasVoicePrompt: Boolean, hasApproveHandler: Boolean): Boolean =
        hasVoicePrompt && hasApproveHandler

    fun voiceHintText(): String = "Say \u201CYes\u201D or \u201CNo\u201D, or tap below"

    /**
     * Auto-dismiss only ever *closes* the card, and closing is a cancel: the timer never reaches an
     * approve handler, so a confirmation that times out is refused rather than waved through.
     * A missing or non-positive timer simply never arms.
     */
    fun armsAutoDismiss(autoDismissMs: Long?): Boolean = autoDismissMs != null && autoDismissMs > 0L

    /** Remaining time after the card was hidden for a screen read; never negative. */
    fun remainingDismissMs(deadlineUptimeMs: Long, nowUptimeMs: Long): Long =
        (deadlineUptimeMs - nowUptimeMs).coerceAtLeast(0L)

    // ---------------------------------------------------------------------------------------------

    private val BLANK_LINE = Regex("\n[ \t\u00A0]*\n+")
    private val STEP_PREFIX = Regex("^(\\d+[.)\\-]|step\\s*\\d+|[-\u2022\u25AA\u2013])\\s", RegexOption.IGNORE_CASE)
    private val STEP_TITLE = Regex("^step \\d+ of \\d+$", RegexOption.IGNORE_CASE)
    private val OPEN_QUOTES = charArrayOf('\u201C', '\u201E', '\u00AB', '\u2039', '"', '\'')
    private val CLOSE_QUOTES = charArrayOf('\u201D', '\u201F', '\u00BB', '\u203A', '"', '\'')
    private val WORD_SPLIT = Regex("[^\\p{L}\\p{N}']+")

    private fun tokenize(text: String): List<String> =
        text.lowercase().split(WORD_SPLIT).filter { it.isNotEmpty() }
}
