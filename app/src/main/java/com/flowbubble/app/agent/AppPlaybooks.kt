package com.flowbubble.app.agent

import com.flowbubble.app.vision.TextMatcher
import java.util.Locale

/**
 * The shapes of task people actually ask an app to do. A playbook describes one shape inside one
 * app, stated as what is looked for on screen, so the generic engine stops rediscovering the same
 * search box on every run. Nothing here performs an action: [Playbook.resolve] hands the planner the
 * ordinary [AgentStep] chain the engine already runs, plus the evidence each step has to leave.
 */
enum class PlaybookFamily {
    SEARCH_IN_APP,
    FIND_ON_PAGE,
    SCROLL_TO_RESULT,
    COMMIT_FLOW,
    NAVIGATE_TO,

    /** Nothing is known about this app: the chain the planner already emits, unchanged. */
    GENERIC
}

/**
 * A control named by what it is for. Roles are the app-agnostic half of a recipe - when a screen
 * shows no word the recipe recognises, the engine resolves the role against the tree it already
 * reads (`ScreenFusion.searchField` and `searchAffordance`), so a role match costs no new code.
 */
enum class ScreenRole {
    SEARCH_BOX,
    SEARCH_BUTTON,
    SEARCH_TAB,
    RESULT_ROW,
    FORM_FIELD,
    GO_BUTTON,
    COMMIT_BUTTON;

    /** Words a person can say for this role, so a spoken "search tab" resolves without a recipe. */
    val words: String
        get() = when (this) {
            SEARCH_BOX -> "search box"
            SEARCH_BUTTON -> "search button"
            SEARCH_TAB -> "search tab"
            RESULT_ROW -> "result"
            FORM_FIELD -> "field"
            GO_BUTTON -> "go button"
            COMMIT_BUTTON -> "confirm button"
        }

    companion object {
        fun named(raw: String): ScreenRole? {
            val needle = TextMatcher.normalize(raw)
            if (needle.isEmpty()) return null
            return entries.firstOrNull { role -> TextMatcher.normalize(role.words) == needle }
        }
    }
}

/**
 * Where a step looks: the app's own words first, then the role the engine understands. An ordinal is
 * a position in a list of rows, never a screen coordinate.
 */
data class ScreenLocator(
    val patterns: List<String> = emptyList(),
    val role: ScreenRole? = null,
    val ordinal: Int? = null
) {
    /**
     * True when the screen itself shows one of these words. A locator with no words at all is
     * always live, because the engine resolves a bare role against the tree. Screen text is only
     * ever matched, never obeyed: a label saying "tap to pay" is data about the screen, not an
     * instruction to the agent.
     */
    fun liveOn(obs: ScreenObservation): Boolean =
        patterns.any { obs.shows(it) } || (patterns.isEmpty() && role != null)

    /** The label handed to the engine. A field and a commit get no invented label. */
    fun tapLabel(): String = patterns.firstOrNull { it.isNotBlank() } ?: when (role) {
        ScreenRole.SEARCH_BUTTON, ScreenRole.SEARCH_TAB, ScreenRole.SEARCH_BOX -> "Search"
        ScreenRole.RESULT_ROW -> "result"
        ScreenRole.GO_BUTTON -> "Search"
        ScreenRole.FORM_FIELD, ScreenRole.COMMIT_BUTTON, null -> ""
    }

    fun describe(): String = when {
        patterns.isNotEmpty() && role != null -> "\u201C${patterns.first()}\u201D or the ${role.words}"
        patterns.isNotEmpty() -> "\u201C${patterns.first()}\u201D"
        role != null -> "the ${role.words}"
        else -> "whatever the screen offers"
    }
}

/** The one move a step makes, named in the engine's own vocabulary. */
enum class PlaybookOp {
    OPEN_APP,
    OPEN_MENU,
    REVEAL_SEARCH,
    TYPE_QUERY,
    SUBMIT_SEARCH,
    SCROLL_UNTIL,
    OPEN_MATCH,
    FILL_FIELD,
    TAP_BUTTON,
    COMMIT
}

/** How a step proves it landed, written before the user's words are known. */
enum class ProofKind {
    NONE,

    /** The field visibly holds what was typed. */
    FIELD_TEXT,

    /** The screen moved away from the one the task started on. */
    SCREEN_MOVED,

    /** The words that were searched came back as a row, which is what proves a search ran. */
    QUERY_IN_LIST,

    /** The place named is on screen with its own controls. */
    PLACE_ON_CARD,

    /** The user's own tap, which the loop only ever knows from a change. */
    USER_TAPPED
}

/** Whether the agent may press a control at all. */
enum class UserGate { NONE, REQUIRED }

/**
 * One move of a recipe: what to look for, what it must produce, and who is allowed to press it.
 * `locate` and `elseLocate` are the two shapes of the same affordance - the magnifier icon, or the
 * tab that replaces it in a newer build.
 */
data class PlaybookStep(
    val label: String,
    val op: PlaybookOp,
    val locate: ScreenLocator = ScreenLocator(),
    val elseLocate: ScreenLocator? = null,
    val proof: ProofKind = ProofKind.NONE,
    /** Screen words meaning this step went the wrong way. Checked before the proof, never acted on. */
    val wrongWay: List<String> = emptyList(),
    val gate: UserGate = UserGate.NONE,
    val field: String = "",
    val maxScrolls: Int = 6,
    val settleMs: Long = 0
) {
    /** A commit belongs to the user whatever the recipe says: a step can add a gate, never lift one. */
    val needsUser: Boolean get() = gate == UserGate.REQUIRED || op == PlaybookOp.COMMIT

    /** The affordance this screen actually offers. Null means the recipe has nothing to press here. */
    fun locatorFor(obs: ScreenObservation): ScreenLocator? = when {
        locate.liveOn(obs) -> locate
        else -> elseLocate?.takeIf { it.liveOn(obs) }
    }

    fun wrongWaySeenOn(obs: ScreenObservation): String? = wrongWay.firstOrNull { obs.shows(it) }
}

/**
 * A recipe. `keys` are package ids plus the names people say for the app; empty keys are the
 * app-agnostic twin, which carries the same shape and matches by role alone.
 */
data class Playbook(
    val id: String,
    val family: PlaybookFamily,
    val keys: Set<String> = emptySet(),
    val steps: List<PlaybookStep>,
    val headline: String = "",
    /**
     * False until a run of this recipe has been watched end to end on a real phone. It is false
     * everywhere today: the apps these recipes name are not installed on the rig, so what is proven
     * here is the selection logic and the predicates, not the booking.
     */
    val provenOnDevice: Boolean = false
) {
    val isTwin: Boolean get() = keys.isEmpty()
    val gatedStep: PlaybookStep? get() = steps.firstOrNull { it.needsUser }

    /** Which of this app's keys the caller used, so a trace can say how the recipe was picked. */
    fun matchedBy(raw: String): String? {
        val needle = normalizeKey(raw)
        if (needle.isEmpty()) return null
        return keys.firstOrNull { normalizeKey(it) == needle }
    }

    /** The chain the engine may run, with every gated step left out of it. */
    fun resolve(slots: PlaybookSlots): ResolvedRecipe {
        val resolved = ArrayList<ResolvedStep>()
        val runnable = ArrayList<AgentStep>()
        steps.forEachIndexed { index, step ->
            val previous = steps.getOrNull(index - 1)
            val next = steps.getOrNull(index + 1)
            // Enter is not a step of its own to the engine: it rides on the text just typed. A
            // submit that names a control of its own stays a step, because something has to be tapped.
            if (step.op == PlaybookOp.SUBMIT_SEARCH && previous?.op == PlaybookOp.TYPE_QUERY && step.runsOnEnter) {
                foldProof(resolved, step, slots)
                return@forEachIndexed
            }
            // Nor is a tap after a scroll: ScrollUntil already carries tapAfter.
            if (step.op == PlaybookOp.OPEN_MATCH && previous?.op == PlaybookOp.SCROLL_UNTIL) {
                foldProof(resolved, step, slots)
                return@forEachIndexed
            }
            val description = fill(step.label, slots)
            val action = actionFor(step, next, slots)
            resolved.add(
                ResolvedStep(
                    step = step,
                    description = description,
                    action = action,
                    doneWhen = proofFor(step, slots),
                    // A step the engine has no action for is a step only the user can take.
                    needsUser = step.needsUser || action == null,
                    askUser = handoffFor(step, slots, action)
                )
            )
            if (action != null) {
                runnable.add(AgentStep(action, description))
                if (step.settleMs > 0) {
                    runnable.add(AgentStep(AgentAction.Wait(step.settleMs), "Wait for the screen"))
                }
            }
        }
        val gated = resolved.firstOrNull { it.needsUser }
        return ResolvedRecipe(
            playbook = this,
            steps = resolved,
            runnable = runnable,
            slots = slots,
            needle = slots.needle(),
            handoff = gated?.askUser.orEmpty(),
            blocked = gated?.description.orEmpty()
        )
    }

    private fun actionFor(step: PlaybookStep, next: PlaybookStep?, slots: PlaybookSlots): AgentAction? = when {
        step.needsUser -> null
        step.op == PlaybookOp.FILL_FIELD && slots.valueFor(step.field).isBlank() -> null
        // A field the safety policy reads as a credential is never typed into, recipe or not.
        step.op == PlaybookOp.FILL_FIELD && SafetyPolicy.isCredentialLabel(step.field) -> null
        else -> when (step.op) {
            PlaybookOp.OPEN_APP -> AgentAction.OpenApp(clean(slots.app))
            PlaybookOp.OPEN_MENU -> AgentAction.OpenMenu
            PlaybookOp.REVEAL_SEARCH -> AgentAction.TapTarget(clean(step.locate.tapLabel()), step.locate.ordinal)
            PlaybookOp.TYPE_QUERY -> AgentAction.TypeText(
                text = clean(slots.typedText(step)),
                pressEnter = next != null && next.op == PlaybookOp.SUBMIT_SEARCH && next.runsOnEnter,
                searchFieldOnly = step.usesSearchBox
            )
            PlaybookOp.SUBMIT_SEARCH -> AgentAction.TapTarget(clean(step.locate.tapLabel()), step.locate.ordinal)
            PlaybookOp.SCROLL_UNTIL -> AgentAction.ScrollUntil(
                label = clean(slots.matchText(step)),
                tapAfter = next?.op == PlaybookOp.OPEN_MATCH,
                maxScrolls = step.maxScrolls
            )
            PlaybookOp.OPEN_MATCH -> AgentAction.TapTarget(clean(slots.matchText(step)), step.locate.ordinal)
            PlaybookOp.FILL_FIELD -> AgentAction.TypeText(text = clean(slots.valueFor(step.field)))
            PlaybookOp.TAP_BUTTON -> AgentAction.TapTarget(clean(step.locate.tapLabel()), step.locate.ordinal)
            PlaybookOp.COMMIT -> null
        }
    }

    /** A submit step with no control of its own means the keyboard's Enter key. */
    private val PlaybookStep.runsOnEnter: Boolean
        get() = locate.patterns.all { it.isBlank() } && locate.role == null

    private val PlaybookStep.usesSearchBox: Boolean
        get() = locate.role == ScreenRole.SEARCH_BOX || elseLocate?.role == ScreenRole.SEARCH_BOX

    private fun proofFor(step: PlaybookStep, slots: PlaybookSlots): List<GoalCondition> {
        val needle = clean(slots.matchText(step))
        val typed = clean(slots.typedText(step))
        return when (step.proof) {
            ProofKind.NONE -> emptyList()
            ProofKind.FIELD_TEXT ->
                if (typed.isEmpty()) emptyList() else listOf(GoalCondition.FieldContains(typed))
            ProofKind.SCREEN_MOVED -> listOf(GoalCondition.ScreenChanged())
            ProofKind.QUERY_IN_LIST, ProofKind.PLACE_ON_CARD ->
                if (needle.isEmpty()) emptyList() else listOf(GoalCondition.LabelAppeared(needle))
            ProofKind.USER_TAPPED -> listOf(GoalCondition.TapVerified)
        }
    }

    private fun handoffFor(step: PlaybookStep, slots: PlaybookSlots, action: AgentAction?): String = when {
        action != null -> ""
        step.op == PlaybookOp.COMMIT ->
            "\u201C${clean(fill(step.label, slots))}\u201D is the last tap, so it is yours. " +
                "Your Bubble stopped with the screen ready."
        else -> "Your Bubble has nothing to press for \u201C${clean(step.field.ifBlank { step.label })}\u201D. " +
            "Tell it what to type, or do that part yourself."
    }

    /** The user's words as holes in the recipe's own sentences. */
    private fun fill(raw: String, slots: PlaybookSlots): String = raw
        .replace("{{app}}", clean(slots.app))
        .replace("{{query}}", clean(slots.query))
        .replace("{{result}}", clean(slots.result.ifBlank { slots.query }))
        .replace("{{place}}", clean(slots.place.ifBlank { slots.query }))
}

/** Carries a folded submit's proof onto the step that actually pressed Enter. */
private fun foldProof(resolved: ArrayList<ResolvedStep>, step: PlaybookStep, slots: PlaybookSlots) {
    if (resolved.isEmpty()) return
    val last = resolved.removeAt(resolved.lastIndex)
    val needle = clean(slots.matchText(last.step))
    val extra = when (step.proof) {
        ProofKind.SCREEN_MOVED -> listOf(GoalCondition.ScreenChanged())
        ProofKind.QUERY_IN_LIST, ProofKind.PLACE_ON_CARD ->
            if (needle.isEmpty()) emptyList() else listOf(GoalCondition.LabelAppeared(needle))
        else -> emptyList()
    }
    resolved.add(last.copy(doneWhen = last.doneWhen + extra))
}

/** The words one recipe run supplies. All of it is untrusted text, capped short, stored nowhere. */
data class PlaybookSlots(
    val app: String = "",
    val query: String = "",
    val result: String = "",
    val place: String = "",
    val fieldValues: Map<String, String> = emptyMap()
) {
    /** The row a scroll or an open step is looking for. */
    fun matchText(step: PlaybookStep): String = when (step.op) {
        PlaybookOp.SCROLL_UNTIL, PlaybookOp.OPEN_MATCH -> result.ifBlank { query }
        else -> when (step.locate.role) {
            ScreenRole.RESULT_ROW -> result.ifBlank { query }
            else -> place.ifBlank { query }
        }
    }

    /**
     * What a typing step put on screen, so its proof checks the right words. A navigate recipe
     * carries the place rather than a query, and both are the same act to the engine.
     */
    fun typedText(step: PlaybookStep): String =
        if (step.op == PlaybookOp.FILL_FIELD) valueFor(step.field) else query.ifBlank { place.ifBlank { result } }

    /** Only a value the user named is ever typed; a recipe holds no values of its own. */
    fun valueFor(field: String): String {
        val key = normalizeKey(field)
        if (key.isEmpty()) return ""
        val named = fieldValues.entries.firstOrNull { normalizeKey(it.key) == key }?.value.orEmpty()
        return named.ifBlank { if (key in TYPED_FIELDS) query else "" }
    }

    /** The one string the loop watches for at the end of the run. */
    fun needle(): String = result.ifBlank { place.ifBlank { query } }

    private companion object {
        val TYPED_FIELDS = setOf("text", "query", "search", "message", "body", "note")
    }
}

/** One resolved step: what to press, what must become true, and who may press it. */
data class ResolvedStep(
    val step: PlaybookStep,
    val description: String,
    val action: AgentAction?,
    val doneWhen: List<GoalCondition>,
    val needsUser: Boolean,
    val askUser: String
) {
    val wrongWay: List<String> get() = step.wrongWay
    val locator: ScreenLocator get() = step.locate

    /** The control this screen offers for this step, or null when it offers neither shape. */
    fun locatorOn(obs: ScreenObservation): ScreenLocator? = step.locatorFor(obs)

    /** True when the screen now shows the words that mean this step went the wrong way. */
    fun wentWrongWay(obs: ScreenObservation): String? = step.wrongWaySeenOn(obs)
}

/** A recipe with the user's words in it: the chain the agent may run, and what it must leave alone. */
data class ResolvedRecipe(
    val playbook: Playbook,
    val steps: List<ResolvedStep>,
    val runnable: List<AgentStep>,
    val slots: PlaybookSlots,
    val needle: String,
    val handoff: String,
    val blocked: String
) {
    /** Feed this to the runner. Null when the recipe has nothing the agent may press. */
    fun agentPlan(goal: String): AgentPlan? =
        runnable.takeIf { it.isNotEmpty() }?.let { AgentPlan(goal, it, PlanSource.LOCAL) }

    /** The commit is outside the runnable chain, so no plan position can reach it. */
    val commitLeftToUser: Boolean
        get() {
            val commits = steps.filter { it.step.op == PlaybookOp.COMMIT }
            return commits.isNotEmpty() && commits.all { it.action == null }
        }

    /** The finish line for the reactive loop, from the last step that proves anything. */
    fun goal(raw: String): ReactiveGoal? {
        val last = steps.lastOrNull { !it.needsUser && it.doneWhen.isNotEmpty() } ?: return null
        val kind = when (playbook.family) {
            PlaybookFamily.SCROLL_TO_RESULT -> GoalKind.FIND
            PlaybookFamily.NAVIGATE_TO -> GoalKind.TAP
            PlaybookFamily.SEARCH_IN_APP, PlaybookFamily.FIND_ON_PAGE,
            PlaybookFamily.COMMIT_FLOW, PlaybookFamily.GENERIC -> GoalKind.GENERIC
        }
        // A row that has to be scrolled to is the loop's own find-first shape, budget included.
        val scroll = steps.firstOrNull { it.step.op == PlaybookOp.SCROLL_UNTIL }
        return ReactiveGoal(
            raw = raw,
            kind = kind,
            target = clean(needle),
            appName = clean(slots.app),
            findFirst = scroll != null,
            maxScrolls = scroll?.step?.maxScrolls ?: 8,
            doneWhen = last.doneWhen
        )
    }

    /** What a step was supposed to make true, for the trace and the panel. */
    fun evidence(): String = steps.filter { it.doneWhen.isNotEmpty() }
        .joinToString("\n") { step ->
            "${step.description} - " + step.doneWhen.joinToString(", ") { it.describe() }
        }

    /** The steps this screen can actually be acted on right now. */
    fun actionableOn(obs: ScreenObservation): List<ResolvedStep> =
        steps.filter { it.action != null && it.locatorOn(obs) != null }

    /** The reason to stop before acting, from the screen as it is now. Null means go ahead. */
    fun stopReason(obs: ScreenObservation): String? {
        AppPlaybooks.blockedBy(obs)?.let { return it }
        val wrong = steps.firstNotNullOfOrNull { it.wentWrongWay(obs) }
        return wrong?.let { "\u201C${clean(it)}\u201D is on the screen, so this stopped instead of guessing." }
    }
}

/** Where a request lands: a written recipe, the same shape by role, or an honest no. */
sealed interface PlaybookRoute {
    /** A recipe written for this app, with its own words. */
    data class Curated(val playbook: Playbook, val matchedBy: String) : PlaybookRoute

    /** The app-agnostic twin: the right shape, controls found by role, no claim about the app. */
    data class ByRole(val playbook: Playbook) : PlaybookRoute

    /** The chain the planner already emits, unchanged, for an app nothing is known about. */
    data class EngineFallback(val playbook: Playbook) : PlaybookRoute

    /** This build has not verified that app and says so instead of guessing. */
    data class Refused(val label: String, val reason: String, val outcome: OutcomeKind) : PlaybookRoute

    val playbookOrNull: Playbook?
        get() = when (this) {
            is Curated -> playbook
            is ByRole -> playbook
            is EngineFallback -> playbook
            is Refused -> null
        }
}

/** An app the recipes name out loud as unverified, and the families that stay refused. */
data class UnverifiedApp(
    val label: String,
    val keys: Set<String>,
    val families: Set<PlaybookFamily>,
    val reason: String
) {
    fun covers(family: PlaybookFamily): Boolean = family in families

    fun refusal(family: PlaybookFamily): String =
        "I haven't verified ${PlaybookCatalog.wordsFor(family)} in $label. $reason " +
            "A control you name can still be tapped or scrolled, which is the ordinary screen engine."
}

/**
 * The lookup the planner consults before it starts guessing at a chain: curated recipe, then the
 * family's app-agnostic twin, then the ordinary engine, and an honest refusal for named apps.
 */
object AppPlaybooks {

    fun route(appKey: String, family: PlaybookFamily): PlaybookRoute {
        refusalFor(appKey, family)?.let { return it }
        val curated = catalog.firstOrNull { it.family == family && it.matchedBy(appKey) != null }
        if (curated != null) {
            return PlaybookRoute.Curated(curated, curated.matchedBy(appKey).orEmpty())
        }
        val twin = twins[family] ?: return PlaybookRoute.EngineFallback(generic)
        return when (family) {
            PlaybookFamily.GENERIC -> PlaybookRoute.EngineFallback(twin)
            else -> PlaybookRoute.ByRole(twin)
        }
    }

    /** The recipe written for a known app, or null. Only curated recipes count as known. */
    fun playbookFor(appKey: String, family: PlaybookFamily): Playbook? =
        catalog.firstOrNull { it.family == family && it.matchedBy(appKey) != null }

    /** The app-agnostic twin of a family: the shape with no claim about the app. */
    fun twinFor(family: PlaybookFamily): Playbook? = twins[family]

    /** What the engine already does, for an app nothing is known about. */
    fun genericRecipe(): Playbook = generic

    /** A refusal for this app and family, or null when a recipe or a twin may be used. */
    fun refusalFor(appKey: String, family: PlaybookFamily): PlaybookRoute.Refused? {
        val key = normalizeKey(appKey)
        if (key.isEmpty()) return null
        val app = unverified.firstOrNull { named -> named.keys.any { normalizeKey(it) == key } }
            ?: return null
        if (!app.covers(family)) return null
        return PlaybookRoute.Refused(app.label, app.refusal(family), OutcomeKind.UNSUPPORTED)
    }

    /**
     * Words that mean the run has reached a wall it must not try to get around.
     *
     * Matched as whole phrases inside whole words: [ScreenObservation.shows] also answers "the label is
     * inside the needle", which reads "Pinned" as a PIN prompt and stops a run that has hit nothing.
     * A wall is worth stopping for; a word that merely contains one is not.
     */
    fun blockedBy(obs: ScreenObservation): String? {
        if (obs.passwordFocused) {
            return "A password field is focused, so this stops here and the rest is yours."
        }
        val hit = AUTH_WALL.firstOrNull { wall -> obs.labels.any { namesPhrase(it, wall) } } ?: return null
        return "\u201C${clean(hit)}\u201D is on the screen. Your Bubble won't get around a lock, a " +
            "sign-in, or a human check - finish that part yourself."
    }

    private fun namesPhrase(label: String, phrase: String): Boolean {
        val haystack = " ${wallWords(label)} "
        return haystack.contains(" ${wallWords(phrase)} ")
    }

    private fun wallWords(raw: String): String =
        raw.lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.joinToString(" ")

    /** Package id or spoken name to the app's display name, for panels and refusals. */
    fun labelFor(appKey: String): String {
        val key = normalizeKey(appKey)
        val curated = catalog.firstOrNull { book -> book.matchedBy(key) != null }
        if (curated != null) return titleize(curated.keys.firstOrNull { !it.contains('.') } ?: key)
        val app = unverified.firstOrNull { named -> named.keys.any { normalizeKey(it) == key } }
        return app?.label ?: titleize(key.ifBlank { clean(appKey) })
    }

    /** Every recipe, curated first. Order only decides which curated match wins. */
    fun recipes(): List<Playbook> = catalog

    private fun titleize(raw: String): String = raw
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word -> word.replaceFirstChar(Char::titlecase) }

    private val twins: Map<PlaybookFamily, Playbook> = mapOf(
        PlaybookFamily.SEARCH_IN_APP to searchTwin,
        PlaybookFamily.SCROLL_TO_RESULT to scrollTwin,
        PlaybookFamily.COMMIT_FLOW to commitTwin,
        PlaybookFamily.NAVIGATE_TO to navigateTwin,
        PlaybookFamily.GENERIC to generic
    )

    /** Named out loud as unverified, so the product refuses rather than guesses. */
    val unverified: List<UnverifiedApp> = listOf(
        UnverifiedApp(
            label = "Telegram",
            keys = setOf("org.telegram.messenger", "telegram", "tg"),
            families = PlaybookFamily.entries.toSet(),
            reason = "Its screens change with each account and build, and nothing here was watched on one."
        ),
        UnverifiedApp(
            label = "Signal",
            keys = setOf("signal"),
            families = PlaybookFamily.entries.toSet(),
            reason = "Conversations stay out of the screen tree by design, so a recipe there is a guess."
        ),
        UnverifiedApp(
            label = "Instagram",
            keys = setOf("com.instagram.android", "instagram", "insta", "ig"),
            families = PlaybookFamily.entries.toSet(),
            reason = "Search and reels reload forever, so a chain there wanders into its budget ceiling."
        ),
        UnverifiedApp(
            label = "a payment app",
            keys = setOf("net.one97.paytm", "paytm", "phonepe", "phone pe", "gpay", "google pay"),
            families = setOf(PlaybookFamily.COMMIT_FLOW),
            reason = "Money leaves the phone on the last tap, and that tap is never taken for you."
        )
    )

    private val catalog: List<Playbook> = listOf(
        whatsappSearch, whatsappScroll, whatsappSend,
        chromeSearch, chromeFindInPage,
        mailSearch, mailScroll, mailSend,
        mapsSearch, mapsScroll, mapsNavigate,
        youtubeSearch, youtubeScroll,
        clockScroll, clockForm,
        searchTwin, scrollTwin, commitTwin, navigateTwin, generic
    )

    private val AUTH_WALL = listOf(
        "password", "passcode", "pin", "captcha", "verify you are human",
        "sign in to continue", "unlock your phone", "face unlock", "fingerprint"
    )
}

private object PlaybookCatalog {
    /** Reads as "I haven't verified search in Telegram." - the family in the user's own words. */
    fun wordsFor(family: PlaybookFamily): String = when (family) {
        PlaybookFamily.SEARCH_IN_APP -> "search"
        PlaybookFamily.FIND_ON_PAGE -> "find-in-page"
        PlaybookFamily.SCROLL_TO_RESULT -> "scrolling to a result"
        PlaybookFamily.COMMIT_FLOW -> "a form or booking commit"
        PlaybookFamily.NAVIGATE_TO -> "navigation"
        PlaybookFamily.GENERIC -> "that app"
    }
}

/** Screen words meaning a search came back empty, so the run stops instead of scrolling on. */
private val NO_RESULTS = listOf("no results", "nothing found", "no matches", "couldn't find")

/** Screen words meaning the app is asking the user to prove they are a person, or to sign in. */
private val HUMAN_WALL = listOf("sign in", "log in", "captcha", "verify you are human", "are you a robot")

/** Screen words meaning a credential is wanted, which is always the user's part to fill. */
private val CREDENTIAL_STOP = listOf("password", "passcode", "pin", "cvv")

private const val MAX_TEXT = 160

private fun normalizeKey(raw: String): String {
    val trimmed = raw.trim().lowercase(Locale.ROOT)
    if (trimmed.contains('.') && trimmed.all { it.isLetterOrDigit() || it in ".-_" }) return trimmed
    return trimmed
        .split(Regex("\\s+"))
        .map { word -> word.filter { it in 'a'..'z' || it in '0'..'9' } }
        .filter { it.isNotEmpty() && it != "app" && it != "the" }
        .joinToString(" ")
}

private fun clean(raw: String): String = raw.replace(Regex("\\s+"), " ").trim().take(MAX_TEXT)

// ---- App-agnostic twins -----------------------------------------------------------------------
// Each twin is the same shape written without any app's words, so an unrecognised app still gets an
// evidence line for every step. The search twin deliberately emits the chain LocalPlanner emits
// today, which is what makes "unknown app" and "the engine, unchanged" the same answer.

private val searchTwin = Playbook(
    id = "twin.search",
    family = PlaybookFamily.SEARCH_IN_APP,
    headline = "Search the app in front",
    steps = listOf(
        PlaybookStep("Open {{app}}", PlaybookOp.OPEN_APP, settleMs = 900),
        PlaybookStep(
            label = "Search {{app}} for \u201C{{query}}\u201D",
            op = PlaybookOp.TYPE_QUERY,
            locate = ScreenLocator(role = ScreenRole.SEARCH_BOX),
            elseLocate = ScreenLocator(patterns = listOf("search"), role = ScreenRole.SEARCH_BUTTON),
            proof = ProofKind.QUERY_IN_LIST,
            wrongWay = NO_RESULTS
        ),
        PlaybookStep("Run the search", PlaybookOp.SUBMIT_SEARCH, proof = ProofKind.SCREEN_MOVED)
    )
)

private val scrollTwin = Playbook(
    id = "twin.scroll",
    family = PlaybookFamily.SCROLL_TO_RESULT,
    headline = "Scroll to a row and open it",
    steps = listOf(
        PlaybookStep("Open {{app}}", PlaybookOp.OPEN_APP, settleMs = 900),
        PlaybookStep(
            label = "Scroll until \u201C{{result}}\u201D shows",
            op = PlaybookOp.SCROLL_UNTIL,
            locate = ScreenLocator(role = ScreenRole.RESULT_ROW),
            maxScrolls = 8
        ),
        PlaybookStep(
            label = "Open \u201C{{result}}\u201D",
            op = PlaybookOp.OPEN_MATCH,
            locate = ScreenLocator(role = ScreenRole.RESULT_ROW, ordinal = 1),
            proof = ProofKind.SCREEN_MOVED,
            wrongWay = NO_RESULTS
        )
    )
)

private val commitTwin = Playbook(
    id = "twin.commit",
    family = PlaybookFamily.COMMIT_FLOW,
    headline = "Type what you said, stop at the last button",
    steps = listOf(
        PlaybookStep("Open {{app}}", PlaybookOp.OPEN_APP, settleMs = 900),
        PlaybookStep(
            label = "Type what you asked for",
            op = PlaybookOp.FILL_FIELD,
            locate = ScreenLocator(role = ScreenRole.FORM_FIELD),
            field = "text",
            proof = ProofKind.FIELD_TEXT,
            wrongWay = CREDENTIAL_STOP + HUMAN_WALL
        ),
        PlaybookStep(
            label = "The final button",
            op = PlaybookOp.COMMIT,
            locate = ScreenLocator(role = ScreenRole.COMMIT_BUTTON),
            gate = UserGate.REQUIRED,
            proof = ProofKind.USER_TAPPED
        )
    )
)

private val navigateTwin = Playbook(
    id = "twin.navigate",
    family = PlaybookFamily.NAVIGATE_TO,
    headline = "Find a place and put it on the map",
    steps = listOf(
        PlaybookStep("Open {{app}}", PlaybookOp.OPEN_APP, settleMs = 900),
        PlaybookStep(
            label = "Search for {{place}}",
            op = PlaybookOp.TYPE_QUERY,
            locate = ScreenLocator(role = ScreenRole.SEARCH_BOX),
            proof = ProofKind.PLACE_ON_CARD,
            wrongWay = NO_RESULTS
        ),
        PlaybookStep("Run the search", PlaybookOp.SUBMIT_SEARCH, proof = ProofKind.SCREEN_MOVED),
        PlaybookStep(
            label = "Show directions",
            op = PlaybookOp.TAP_BUTTON,
            locate = ScreenLocator(patterns = listOf("directions", "start", "go"), role = ScreenRole.GO_BUTTON),
            proof = ProofKind.SCREEN_MOVED,
            wrongWay = HUMAN_WALL
        )
    )
)

/** Exactly the chain LocalPlanner already emits for "open X and search for Y", with nothing added. */
private val generic = Playbook(
    id = "engine.generic",
    family = PlaybookFamily.GENERIC,
    headline = "The ordinary screen engine",
    steps = searchTwin.steps
)

// ---- WhatsApp ---------------------------------------------------------------------------------

private val whatsappSearch = Playbook(
    id = "whatsapp.search",
    family = PlaybookFamily.SEARCH_IN_APP,
    keys = setOf("com.whatsapp", "com.whatsapp.w4b", "whatsapp", "wa", "what app"),
    headline = "Search WhatsApp's chats",
    steps = listOf(
        PlaybookStep("Open {{app}}", PlaybookOp.OPEN_APP, settleMs = 900),
        PlaybookStep(
            // Newer builds move search to a bottom tab, which the generic magnifier finder can't see.
            label = "Open WhatsApp's search",
            op = PlaybookOp.REVEAL_SEARCH,
            locate = ScreenLocator(patterns = listOf("search"), role = ScreenRole.SEARCH_TAB),
            elseLocate = ScreenLocator(patterns = listOf("search", "search chats"), role = ScreenRole.SEARCH_BUTTON)
        ),
        PlaybookStep(
            label = "Search WhatsApp for \u201C{{query}}\u201D",
            op = PlaybookOp.TYPE_QUERY,
            locate = ScreenLocator(
                patterns = listOf("search", "search chats", "start typing"),
                role = ScreenRole.SEARCH_BOX
            ),
            proof = ProofKind.QUERY_IN_LIST,
            wrongWay = NO_RESULTS
        ),
        PlaybookStep("Run the search", PlaybookOp.SUBMIT_SEARCH, proof = ProofKind.SCREEN_MOVED)
    )
)

private val whatsappScroll = Playbook(
    id = "whatsapp.scroll",
    family = PlaybookFamily.SCROLL_TO_RESULT,
    keys = setOf("com.whatsapp", "com.whatsapp.w4b", "whatsapp", "wa"),
    headline = "Scroll the chat list and open one",
    steps = listOf(
        PlaybookStep("Open {{app}}", PlaybookOp.OPEN_APP, settleMs = 900),
        PlaybookStep(
            label = "Scroll until \u201C{{result}}\u201D shows",
            op = PlaybookOp.SCROLL_UNTIL,
            locate = ScreenLocator(patterns = listOf("chats", "archive", "unread chats"), role = ScreenRole.RESULT_ROW),
            maxScrolls = 8
        ),
        PlaybookStep(
            label = "Open \u201C{{result}}\u201D",
            op = PlaybookOp.OPEN_MATCH,
            locate = ScreenLocator(role = ScreenRole.RESULT_ROW, ordinal = 1),
            proof = ProofKind.SCREEN_MOVED,
            wrongWay = NO_RESULTS + HUMAN_WALL
        )
    )
)

private val whatsappSend = Playbook(
    id = "whatsapp.send",
    family = PlaybookFamily.COMMIT_FLOW,
    keys = setOf("com.whatsapp", "com.whatsapp.w4b", "whatsapp", "wa"),
    headline = "Draft a WhatsApp message, never send it",
    steps = listOf(
        PlaybookStep("Open {{app}}", PlaybookOp.OPEN_APP, settleMs = 900),
        PlaybookStep(
            label = "Open \u201C{{result}}\u201D",
            op = PlaybookOp.OPEN_MATCH,
            locate = ScreenLocator(role = ScreenRole.RESULT_ROW, ordinal = 1),
            proof = ProofKind.SCREEN_MOVED
        ),
        PlaybookStep(
            label = "Write the message",
            op = PlaybookOp.FILL_FIELD,
            locate = ScreenLocator(
                patterns = listOf("message", "type a message", "ask a question"),
                role = ScreenRole.FORM_FIELD
            ),
            field = "message",
            proof = ProofKind.FIELD_TEXT,
            wrongWay = CREDENTIAL_STOP + HUMAN_WALL
        ),
        PlaybookStep(
            label = "Send",
            op = PlaybookOp.COMMIT,
            locate = ScreenLocator(patterns = listOf("send"), role = ScreenRole.COMMIT_BUTTON),
            gate = UserGate.REQUIRED,
            proof = ProofKind.USER_TAPPED
        )
    )
)

// ---- Chrome -----------------------------------------------------------------------------------

private val chromeSearch = Playbook(
    id = "chrome.search",
    family = PlaybookFamily.SEARCH_IN_APP,
    keys = setOf("com.android.chrome", "chrome", "browser"),
    headline = "Use Chrome's address bar",
    steps = listOf(
        PlaybookStep("Open {{app}}", PlaybookOp.OPEN_APP, settleMs = 900),
        PlaybookStep(
            // The omnibox is always on screen, so this app needs no reveal step at all.
            label = "Search Chrome for \u201C{{query}}\u201D",
            op = PlaybookOp.TYPE_QUERY,
            locate = ScreenLocator(
                patterns = listOf("search or type site", "address and search bar", "type a url", "search"),
                role = ScreenRole.SEARCH_BOX
            ),
            proof = ProofKind.FIELD_TEXT,
            wrongWay = listOf("can't find this site", "no internet", "page not available")
        ),
        PlaybookStep("Load it", PlaybookOp.SUBMIT_SEARCH, proof = ProofKind.SCREEN_MOVED)
    )
)

private val chromeFindInPage = Playbook(
    id = "chrome.find",
    family = PlaybookFamily.FIND_ON_PAGE,
    keys = setOf("com.android.chrome", "chrome", "browser"),
    headline = "Find words inside the open page",
    steps = listOf(
        PlaybookStep("Open {{app}}", PlaybookOp.OPEN_APP, settleMs = 900),
        PlaybookStep("Open Chrome's menu", PlaybookOp.OPEN_MENU, settleMs = 600),
        PlaybookStep(
            label = "Tap find in page",
            op = PlaybookOp.TAP_BUTTON,
            locate = ScreenLocator(patterns = listOf("find in page", "select and read"), role = ScreenRole.GO_BUTTON),
            proof = ProofKind.SCREEN_MOVED
        ),
        PlaybookStep(
            label = "Find \u201C{{query}}\u201D on the page",
            op = PlaybookOp.TYPE_QUERY,
            locate = ScreenLocator(patterns = listOf("find in page", "search the page"), role = ScreenRole.SEARCH_BOX),
            proof = ProofKind.FIELD_TEXT
        ),
        PlaybookStep(
            // Find-in-page runs on Enter, so this submit folds into the typing step above it.
            label = "Run the find",
            op = PlaybookOp.SUBMIT_SEARCH,
            proof = ProofKind.QUERY_IN_LIST,
            wrongWay = NO_RESULTS
        )
    )
)

// ---- Gmail, and Outlook by name only -----------------------------------------------------------

private val MAIL_PATTERNS = listOf("search mail", "search in all mail", "search your emails", "search")

private val mailSearch = Playbook(
    id = "mail.search",
    family = PlaybookFamily.SEARCH_IN_APP,
    // Outlook has no package id verified on this device, so it keys on the name and screen words.
    keys = setOf("com.google.android.gm", "gmail", "mail", "email", "google mail", "outlook"),
    headline = "Search a mailbox",
    steps = listOf(
        PlaybookStep("Open {{app}}", PlaybookOp.OPEN_APP, settleMs = 900),
        PlaybookStep(
            label = "Search {{app}} for \u201C{{query}}\u201D",
            op = PlaybookOp.TYPE_QUERY,
            locate = ScreenLocator(patterns = MAIL_PATTERNS, role = ScreenRole.SEARCH_BOX),
            proof = ProofKind.QUERY_IN_LIST,
            wrongWay = NO_RESULTS
        ),
        PlaybookStep("Run the search", PlaybookOp.SUBMIT_SEARCH, proof = ProofKind.SCREEN_MOVED)
    )
)

private val mailScroll = Playbook(
    id = "mail.scroll",
    family = PlaybookFamily.SCROLL_TO_RESULT,
    keys = setOf("com.google.android.gm", "gmail", "mail", "email", "outlook"),
    headline = "Scroll a mailbox and open a mail",
    steps = listOf(
        PlaybookStep("Open {{app}}", PlaybookOp.OPEN_APP, settleMs = 900),
        PlaybookStep(
            label = "Scroll until \u201C{{result}}\u201D shows",
            op = PlaybookOp.SCROLL_UNTIL,
            locate = ScreenLocator(patterns = listOf("inbox", "primary", "all mail"), role = ScreenRole.RESULT_ROW),
            maxScrolls = 8
        ),
        PlaybookStep(
            label = "Open \u201C{{result}}\u201D",
            op = PlaybookOp.OPEN_MATCH,
            locate = ScreenLocator(role = ScreenRole.RESULT_ROW, ordinal = 1),
            proof = ProofKind.SCREEN_MOVED,
            wrongWay = NO_RESULTS
        )
    )
)

private val mailSend = Playbook(
    id = "mail.send",
    family = PlaybookFamily.COMMIT_FLOW,
    keys = setOf("com.google.android.gm", "gmail", "mail", "email", "outlook"),
    headline = "Draft an email, leave send alone",
    steps = listOf(
        PlaybookStep("Open {{app}}", PlaybookOp.OPEN_APP, settleMs = 900),
        PlaybookStep(
            label = "Start a new mail",
            op = PlaybookOp.TAP_BUTTON,
            locate = ScreenLocator(patterns = listOf("compose", "write", "new mail", "new message"), role = ScreenRole.GO_BUTTON),
            proof = ProofKind.SCREEN_MOVED
        ),
        PlaybookStep(
            label = "Fill the recipient",
            op = PlaybookOp.FILL_FIELD,
            locate = ScreenLocator(patterns = listOf("to", "recipients"), role = ScreenRole.FORM_FIELD),
            field = "to",
            proof = ProofKind.FIELD_TEXT,
            wrongWay = CREDENTIAL_STOP
        ),
        PlaybookStep(
            label = "Write the message",
            op = PlaybookOp.FILL_FIELD,
            locate = ScreenLocator(patterns = listOf("subject", "message", "compose email"), role = ScreenRole.FORM_FIELD),
            field = "body",
            proof = ProofKind.FIELD_TEXT,
            wrongWay = CREDENTIAL_STOP + HUMAN_WALL
        ),
        PlaybookStep(
            label = "Send",
            op = PlaybookOp.COMMIT,
            locate = ScreenLocator(patterns = listOf("send"), role = ScreenRole.COMMIT_BUTTON),
            gate = UserGate.REQUIRED,
            proof = ProofKind.USER_TAPPED
        )
    )
)

// ---- Maps -------------------------------------------------------------------------------------

private val mapsSearch = Playbook(
    id = "maps.search",
    family = PlaybookFamily.SEARCH_IN_APP,
    keys = setOf("com.google.android.apps.maps", "maps", "google maps", "navigation", "nav"),
    headline = "Search Maps",
    steps = listOf(
        PlaybookStep("Open {{app}}", PlaybookOp.OPEN_APP, settleMs = 900),
        PlaybookStep(
            // Maps shows a real field, so there is no icon to press first.
            label = "Search maps for \u201C{{query}}\u201D",
            op = PlaybookOp.TYPE_QUERY,
            locate = ScreenLocator(patterns = listOf("search maps", "search here", "where to"), role = ScreenRole.SEARCH_BOX),
            proof = ProofKind.QUERY_IN_LIST,
            wrongWay = NO_RESULTS
        ),
        PlaybookStep("Run the search", PlaybookOp.SUBMIT_SEARCH, proof = ProofKind.SCREEN_MOVED)
    )
)

private val mapsScroll = Playbook(
    id = "maps.scroll",
    family = PlaybookFamily.SCROLL_TO_RESULT,
    keys = setOf("com.google.android.apps.maps", "maps", "google maps"),
    headline = "Scroll the places list and open one",
    steps = listOf(
        PlaybookStep("Open {{app}}", PlaybookOp.OPEN_APP, settleMs = 900),
        PlaybookStep(
            label = "Scroll until \u201C{{result}}\u201D shows",
            op = PlaybookOp.SCROLL_UNTIL,
            locate = ScreenLocator(patterns = listOf("results", "places", "nearby"), role = ScreenRole.RESULT_ROW),
            maxScrolls = 8
        ),
        PlaybookStep(
            label = "Open \u201C{{result}}\u201D",
            op = PlaybookOp.OPEN_MATCH,
            locate = ScreenLocator(role = ScreenRole.RESULT_ROW, ordinal = 1),
            proof = ProofKind.PLACE_ON_CARD,
            wrongWay = NO_RESULTS
        )
    )
)

private val mapsNavigate = Playbook(
    id = "maps.navigate",
    family = PlaybookFamily.NAVIGATE_TO,
    keys = setOf("com.google.android.apps.maps", "maps", "google maps", "navigation", "nav"),
    headline = "Route to a place",
    steps = listOf(
        PlaybookStep("Open {{app}}", PlaybookOp.OPEN_APP, settleMs = 900),
        PlaybookStep(
            label = "Search maps for {{place}}",
            op = PlaybookOp.TYPE_QUERY,
            locate = ScreenLocator(patterns = listOf("search maps", "where to", "choose destination"), role = ScreenRole.SEARCH_BOX),
            proof = ProofKind.PLACE_ON_CARD,
            wrongWay = NO_RESULTS
        ),
        PlaybookStep("Run the search", PlaybookOp.SUBMIT_SEARCH, proof = ProofKind.SCREEN_MOVED),
        PlaybookStep(
            label = "Show directions to {{place}}",
            op = PlaybookOp.TAP_BUTTON,
            locate = ScreenLocator(patterns = listOf("directions", "route", "start", "go"), role = ScreenRole.GO_BUTTON),
            proof = ProofKind.SCREEN_MOVED,
            wrongWay = HUMAN_WALL
        ),
        PlaybookStep(
            // A route on screen is not a commitment; a booked ride, table, or ticket is.
            label = "Book or start",
            op = PlaybookOp.COMMIT,
            locate = ScreenLocator(patterns = listOf("book", "order", "confirm", "start"), role = ScreenRole.COMMIT_BUTTON),
            gate = UserGate.REQUIRED,
            proof = ProofKind.USER_TAPPED
        )
    )
)

// ---- YouTube ----------------------------------------------------------------------------------

private val youtubeSearch = Playbook(
    id = "youtube.search",
    family = PlaybookFamily.SEARCH_IN_APP,
    keys = setOf("com.google.android.youtube", "youtube", "yt"),
    headline = "Search YouTube",
    steps = listOf(
        PlaybookStep("Open {{app}}", PlaybookOp.OPEN_APP, settleMs = 900),
        PlaybookStep(
            label = "Open YouTube's search",
            op = PlaybookOp.REVEAL_SEARCH,
            locate = ScreenLocator(patterns = listOf("search youtube", "search"), role = ScreenRole.SEARCH_BUTTON),
            elseLocate = ScreenLocator(patterns = listOf("search"), role = ScreenRole.SEARCH_TAB)
        ),
        PlaybookStep(
            label = "Search YouTube for \u201C{{query}}\u201D",
            op = PlaybookOp.TYPE_QUERY,
            locate = ScreenLocator(
                patterns = listOf("search youtube", "what are you looking for", "search"),
                role = ScreenRole.SEARCH_BOX
            ),
            proof = ProofKind.QUERY_IN_LIST,
            wrongWay = NO_RESULTS
        ),
        PlaybookStep("Run the search", PlaybookOp.SUBMIT_SEARCH, proof = ProofKind.SCREEN_MOVED)
    )
)

private val youtubeScroll = Playbook(
    id = "youtube.scroll",
    family = PlaybookFamily.SCROLL_TO_RESULT,
    keys = setOf("com.google.android.youtube", "youtube", "yt"),
    headline = "Scroll the results and open a video",
    steps = listOf(
        PlaybookStep("Open {{app}}", PlaybookOp.OPEN_APP, settleMs = 900),
        PlaybookStep(
            label = "Scroll until \u201C{{result}}\u201D shows",
            op = PlaybookOp.SCROLL_UNTIL,
            locate = ScreenLocator(patterns = listOf("videos", "shorts", "results"), role = ScreenRole.RESULT_ROW),
            maxScrolls = 10
        ),
        PlaybookStep(
            label = "Open \u201C{{result}}\u201D",
            op = PlaybookOp.OPEN_MATCH,
            locate = ScreenLocator(role = ScreenRole.RESULT_ROW, ordinal = 1),
            proof = ProofKind.SCREEN_MOVED,
            wrongWay = NO_RESULTS
        )
    )
)

// ---- Clock ------------------------------------------------------------------------------------

private val clockScroll = Playbook(
    id = "clock.scroll",
    family = PlaybookFamily.SCROLL_TO_RESULT,
    // No package id is known for the clock on this device, so it keys on the names people say.
    keys = setOf("clock", "alarm", "timer"),
    headline = "Find an alarm in the list",
    steps = listOf(
        PlaybookStep("Open {{app}}", PlaybookOp.OPEN_APP, settleMs = 900),
        PlaybookStep(
            label = "Scroll until \u201C{{result}}\u201D shows",
            op = PlaybookOp.SCROLL_UNTIL,
            locate = ScreenLocator(patterns = listOf("alarms", "clock", "add alarm"), role = ScreenRole.RESULT_ROW),
            maxScrolls = 6
        ),
        PlaybookStep(
            label = "Open \u201C{{result}}\u201D",
            op = PlaybookOp.OPEN_MATCH,
            locate = ScreenLocator(role = ScreenRole.RESULT_ROW, ordinal = 1),
            proof = ProofKind.SCREEN_MOVED
        )
    )
)

private val clockForm = Playbook(
    id = "clock.form",
    family = PlaybookFamily.COMMIT_FLOW,
    keys = setOf("clock", "alarm", "timer"),
    headline = "Set up an alarm, save it yourself",
    steps = listOf(
        PlaybookStep("Open {{app}}", PlaybookOp.OPEN_APP, settleMs = 900),
        PlaybookStep(
            label = "Add an alarm",
            op = PlaybookOp.TAP_BUTTON,
            locate = ScreenLocator(patterns = listOf("add alarm", "add an alarm"), role = ScreenRole.GO_BUTTON),
            proof = ProofKind.SCREEN_MOVED
        ),
        PlaybookStep(
            label = "Label the alarm",
            op = PlaybookOp.FILL_FIELD,
            locate = ScreenLocator(patterns = listOf("label", "add label"), role = ScreenRole.FORM_FIELD),
            field = "label",
            proof = ProofKind.FIELD_TEXT
        ),
        PlaybookStep(
            label = "Save",
            op = PlaybookOp.COMMIT,
            locate = ScreenLocator(patterns = listOf("ok", "save", "set clock"), role = ScreenRole.COMMIT_BUTTON),
            gate = UserGate.REQUIRED,
            proof = ProofKind.USER_TAPPED
        )
    )
)
