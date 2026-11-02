package com.flowbubble.app.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.flowbubble.app.access.FieldActionOutcome
import com.flowbubble.app.access.FlowAccessibilityService
import com.flowbubble.app.access.ScreenPulse
import com.flowbubble.app.access.TextInsertResult
import com.flowbubble.app.capture.ScreenCapture
import com.flowbubble.app.commands.AppMatch
import com.flowbubble.app.commands.AppResolver
import com.flowbubble.app.core.HistoryStore
import com.flowbubble.app.core.LastActionMemory
import com.flowbubble.app.core.Prefs
import com.flowbubble.app.overlay.OverlayUi
import com.flowbubble.app.overlay.PanelAction
import com.flowbubble.app.overlay.PanelContent
import com.flowbubble.app.capture.ScreenSession
import com.flowbubble.app.vision.PointResolution
import com.flowbubble.app.vision.ScreenFusion
import com.flowbubble.app.vision.ScreenModel
import com.flowbubble.app.vision.ScreenModelFactory
import com.flowbubble.app.vision.ScreenTarget
import com.flowbubble.app.vision.TargetLocator
import com.flowbubble.app.vision.TargetSource
import com.flowbubble.app.vision.TextMatcher
import com.flowbubble.app.voice.AiRole
import com.flowbubble.app.voice.LlmClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/** How long an app gets to show a real search field after its search icon is tapped. */
private const val SEARCH_FIELD_TIMEOUT_MS = 3_000L
private const val SEARCH_FIELD_POLL_MS = 200L

/**
 * A cold start of a heavy app on a busy device was measured at 9-10 s, and this is only a cap:
 * the poll returns the moment the app is actually in front, so a normal launch costs about a
 * second. A tighter cap turned a real launch into a failure one poll before the window appeared.
 */
private const val APP_LAUNCH_TIMEOUT_MS = 20_000L

/** How long the screen must be still before a wait treats it as settled. */
private const val SCREEN_QUIET_MS = 250L

/** Waiters check the pulse this often. It is an atomic read, not a screen read. */
private const val PULSE_POLL_MS = 50L

/** Fallback re-read interval for screens that change without emitting a usable event. */
private const val BLIND_REREAD_MS = 400L

/**
 * How long the reactive loop waits for the live session to publish the screen a step produced. The
 * session publishes within a few hundred milliseconds when something moved, so this is a patience
 * cap and not a sleep: a step that changed the screen returns as soon as the new revision lands.
 */
private const val REVISION_WAIT_MS = 1100L
private const val FIRST_REVISION_WAIT_MS = 600L

/**
 * A configured model may pick the next step, but only a few times per run: every consult is a network
 * round trip with screen text in it, and the deterministic rules are what keep a loop cheap and fast.
 */
private const val MAX_AI_CONSULTS = 4

class AgentRunner(
    private val context: Context,
    private val ui: OverlayUi
) {
    private data class StepOutcome(
        val ok: Boolean = false,
        val cancelled: Boolean = false,
        val note: String = "",
        /** What was actually observed. Null means the step ran but nothing proved it worked. */
        val evidence: String? = null
    )

    private var job: Job? = null
    private var generation = 0
    @Volatile
    private var stopRequested = false
    private val pendingConfirm = AtomicReference<CompletableDeferred<Boolean>?>(null)
    private val pendingChoice = AtomicReference<CompletableDeferred<Int?>?>(null)
    private val notes = ArrayList<String>()

    fun isRunning(): Boolean = job?.isActive == true

    fun start(scope: CoroutineScope, goal: String) {
        stop()
        stopRequested = false
        notes.clear()
        val myGeneration = ++generation
        job = scope.launch {
            val outcome = try {
                execute(goal)
            } catch (cancelled: CancellationException) {
                AgentOutcome.Cancelled
            } catch (error: Throwable) {
                AgentOutcome.Failed(error.message ?: "The task stopped unexpectedly.")
            }
            if (generation == myGeneration) finish(goal, outcome)
        }
    }

    fun startWithPlan(scope: CoroutineScope, plan: AgentPlan) {
        stop()
        stopRequested = false
        notes.clear()
        val myGeneration = ++generation
        job = scope.launch {
            val outcome = try {
                runPlan(plan)
            } catch (cancelled: CancellationException) {
                AgentOutcome.Cancelled
            } catch (error: Throwable) {
                AgentOutcome.Failed(error.message ?: "The task stopped unexpectedly.")
            }
            if (generation == myGeneration) finish(plan.goal, outcome)
        }
    }

    fun stop(showStopped: Boolean = false) {
        val wasRunning = job?.isActive == true
        generation++
        stopRequested = true
        pendingConfirm.getAndSet(null)?.complete(false)
        pendingChoice.getAndSet(null)?.complete(null)
        job?.cancel()
        job = null
        if (showStopped && wasRunning) {
            HistoryStore.add(context, "Agent", "Stopped by you", "done")
            runCatching { ui.closePanel() }
            ui.showPanel(PanelContent(title = "Stopped", autoDismissMs = 1200))
        }
    }

    private suspend fun execute(goal: String): AgentOutcome {
        HistoryStore.add(context, "Agent", goal.take(90), "requested")
        ui.showPanel(progress("Planning", "Working out the steps."))

        var plan = LocalPlanner.plan(goal)
        if (plan == null) {
            if (!Prefs.agentLlmEnabled(context) || !LlmClient.isConfigured(context, AiRole.Plan)) {
                return AgentOutcome.Failed(
                    "I can run multi-step search, open, type, tap, and scroll requests on my own. " +
                        "For free-form screen tasks, turn on AI planning in Your Bubble settings and configure a model."
                )
            }
            val service = FlowAccessibilityService.instance
                ?: return AgentOutcome.Failed(
                    "Accessibility is off, so I can't see or act on the screen. " +
                        "Enable Your Bubble voice access in Android settings."
                )
            val endpoint = LlmClient.endpointLabel(context, AiRole.Plan)
            val approved = confirm(
                title = "Plan this with AI?",
                body = "This sends the visible screen text to your AI model at $endpoint to choose the steps. " +
                    "Nothing is saved or uploaded anywhere else."
            )
            if (!approved) return AgentOutcome.Cancelled

            ui.showPanel(progress("Reading the screen", "Gathering visible text for the planner."))
            val model = readModel(service, includePixels = true)
            val screenText = model?.readableText(3000).orEmpty()
            if (screenText.isBlank()) {
                return AgentOutcome.Failed(
                    "I couldn't read enough text on this screen to plan the task." +
                        if (!ScreenCapture.isActive) {
                            " Screen access is off, so apps that hide their controls stay invisible."
                        } else {
                            ""
                        }
                )
            }
            plan = LlmPlanner.plan(goal, screenText, LlmClient.config(context, AiRole.Plan))
                .getOrElse { error ->
                    return AgentOutcome.Failed("AI planning failed: ${error.message ?: "unknown error"}. Nothing was changed.")
                }
        }
        return runReactive(plan)
    }

    private suspend fun runPlan(plan: AgentPlan): AgentOutcome {
        val total = plan.steps.size
        val steps = ArrayList<TraceStep>()
        val route = if (plan.source == PlanSource.AI) "AI-planned chain" else "planned chain"
        fun trace(outcome: OutcomeKind, headline: String) = ExecutionTrace(
            asked = plan.goal,
            route = route,
            steps = steps.toList(),
            outcome = outcome,
            headline = headline
        )
        for ((index, step) in plan.steps.withIndex()) {
            if (stopRequested) return AgentOutcome.Cancelled
            showProgress(index, total, step.description)
            val outcome = try {
                executeStep(step)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                StepOutcome(false, note = error.message ?: "That step failed.")
            }
            if (outcome.cancelled) return AgentOutcome.Cancelled
            steps.add(
                TraceStep(
                    label = step.description,
                    detail = if (outcome.note == step.description) "" else outcome.note,
                    evidence = outcome.evidence,
                    informational = step.action is AgentAction.Wait
                )
            )
            if (!outcome.ok) {
                return AgentOutcome.Failed(
                    "Step ${index + 1} of $total: ${outcome.note}".trim(),
                    trace(OutcomeKind.FAILED, "Stopped at step ${index + 1} of $total.")
                )
            }
            if (outcome.note.isNotBlank()) notes.add(outcome.note)
        }
        val summary = if (notes.isEmpty()) {
            "Finished ${plan.steps.size} step${if (plan.steps.size == 1) "" else "s"}."
        } else {
            notes.joinToString("\n\n")
        }
        return AgentOutcome.Completed(summary, trace(OutcomeKind.COMPLETED, ""))
    }

    /**
     * Watch the screen, decide one step, take it, then read the screen again - until the goal is
     * visibly true, the user stops it, or the budget runs out.
     *
     * The planned steps are the opening moves, not the whole run: once they are spent the next action
     * comes from [LocalPlanner.decide] against the current [ScreenObservation], so a scroll that
     * landed somewhere unexpected changes what happens next instead of being followed blindly. An AI
     * role that is configured may pick the step; when it is not, deterministic rules do, and the run
     * still finishes. Every safety gate lives in [executeStep] and is reused unchanged, so a
     * reactive tap goes through exactly the same confirmation and credential rules as a planned one.
     */
    private suspend fun runReactive(plan: AgentPlan): AgentOutcome {
        val budget = ReactiveBudget()
        val steps = ArrayList<TraceStep>()
        val route = if (plan.source == PlanSource.AI) "AI-planned chain, then watched the screen" else "watched the screen"
        val startedAt = System.currentTimeMillis()

        fun trace(outcome: OutcomeKind, headline: String) = ExecutionTrace(
            asked = plan.goal,
            route = route,
            steps = steps.toList(),
            outcome = outcome,
            headline = headline
        )

        var goal = LocalPlanner.reactiveGoal(plan.goal)
        var nextStep = 0
        var stepsUsed = 0
        var tapsUsed = 0
        var scrollsUsed = 0
        var dismissUsed = 0
        var backUsed = 0
        var typedUsed = 0
        var openedApp = false
        var tapsVerified = false
        var aiConsults = 0
        var lastAction: AgentAction? = null
        var sameScreenStreak = 0
        var first: ScreenObservation? = null
        var previous: ScreenObservation? = null
        var baselineRevision = 0
        var resolvedPointer: ResolvedPointer? = null
        var pointerQuestion: String? = null
        var pointerOptions: List<String> = emptyList()
        var pointerNote = ""
        var startedSessionHere = false

        if (goal != null && !ScreenSession.isWatching()) {
            // Eyes for the loop, without asking Android for anything new: a session started with a
            // null context never requests a projection, it just watches whatever is already flowing.
            ScreenSession.start(null, fromUser = false)
            startedSessionHere = true
        }
        try {
            while (true) {
                if (stopRequested) return AgentOutcome.Cancelled
                val service = FlowAccessibilityService.instance
                    ?: return AgentOutcome.Failed(
                        "Accessibility is off, so I can't see or act on the screen. " +
                            "Enable Your Bubble voice access in Android settings.",
                        trace(OutcomeKind.FAILED, "I couldn't see the screen.")
                    )
                val now = observe(service, if (first == null) FIRST_REVISION_WAIT_MS else REVISION_WAIT_MS)
                    ?: return AgentOutcome.Failed(
                        "I couldn't read the screen, so I stopped instead of acting blind.",
                        trace(OutcomeKind.FAILED, "I couldn't read the screen.")
                    )
                if (first == null) {
                    first = now
                    baselineRevision = now.revision
                    goal = goal?.withBaseline(now)
                }
                sameScreenStreak = if (previous != null && now.fingerprint == previous.fingerprint) {
                    sameScreenStreak + 1
                } else {
                    0
                }
                val currentGoal = goal
                val appeared = appearedSince(previous, baselineRevision)
                if (currentGoal != null && currentGoal.kind == GoalKind.POINT && resolvedPointer == null) {
                    val facts = resolvePointer(currentGoal.pointing, appeared)
                    resolvedPointer = facts.resolved
                    pointerQuestion = facts.question
                    pointerOptions = facts.options
                    pointerNote = facts.note
                }
                val ctx = LoopContext(
                    goal = currentGoal ?: ReactiveGoal(plan.goal, GoalKind.GENERIC),
                    now = now,
                    before = previous,
                    first = first,
                    appeared = appeared,
                    resolvedPointer = resolvedPointer,
                    pointerQuestion = pointerQuestion,
                    pointerOptions = pointerOptions,
                    pointerNote = pointerNote,
                    stepsUsed = stepsUsed,
                    tapsUsed = tapsUsed,
                    scrollsUsed = scrollsUsed,
                    dismissUsed = dismissUsed,
                    backUsed = backUsed,
                    typedUsed = typedUsed,
                    openedApp = openedApp,
                    sameScreenStreak = sameScreenStreak,
                    tapsVerified = tapsVerified,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    budget = budget,
                    lastAction = lastAction
                )
                val decision: LoopDecision = if (nextStep < plan.steps.size) {
                    val step = plan.steps[nextStep++]
                    LoopDecision.Act(step.action, step.description)
                } else if (plan.source == PlanSource.AI && aiConsults < MAX_AI_CONSULTS &&
                    LlmClient.isConfigured(context, AiRole.Plan)
                ) {
                    // The user approved sending this screen to their model when this plan was made, so
                    // the model may choose the next step. If it has nothing usable, the deterministic
                    // rules take it from here; configuring a model can never be what breaks a run.
                    aiConsults++
                    runCatching {
                        LlmPlanner.nextStep(
                            goal = plan.goal,
                            observation = now,
                            tried = steps.map { it.label },
                            config = LlmClient.config(context, AiRole.Plan)
                        )
                    }.getOrNull() ?: LocalPlanner.decide(ctx)
                } else {
                    LocalPlanner.decide(ctx)
                }

                when (decision) {
                    is LoopDecision.Done -> {
                        steps.add(
                            TraceStep(
                                label = "Checked the screen against what you asked for",
                                detail = decision.evidence,
                                evidence = decision.evidence
                            )
                        )
                        val summary = (notes + decision.evidence).joinToString("\n\n")
                        return AgentOutcome.Completed(summary, trace(OutcomeKind.COMPLETED, ""))
                    }
                    is LoopDecision.GiveUp -> {
                        steps.add(
                            TraceStep(
                                label = "Stopped watching",
                                detail = decision.reason,
                                evidence = null
                            )
                        )
                        val completed = steps.count { it.verified && !it.informational }
                        return AgentOutcome.Failed(
                            decision.reason + if (completed > 0) " ($completed step${if (completed == 1) "" else "s"} confirmed first.)" else "",
                            trace(OutcomeKind.FAILED, "I stopped after $stepsUsed steps.")
                        )
                    }
                    is LoopDecision.Ask -> {
                        resolvedPointer = null
                        pointerQuestion = null
                        val choice = askChoice(
                            title = decision.question,
                            body = decision.body + " I won't tap anything until you pick.",
                            options = decision.options
                        )
                        if (choice == null) return AgentOutcome.Cancelled
                        val label = decision.options.getOrNull(choice).orEmpty()
                        if (label.isBlank()) {
                            pointerNote = "That pick had no readable label, so I stopped rather than tap blind."
                            continue
                        }
                        resolvedPointer = ResolvedPointer(label = label, ordinal = null, evidence = "you picked it")
                        continue
                    }
                    is LoopDecision.Act -> {
                        val step = AgentStep(decision.action, decision.reason)
                        lastAction = decision.action
                        stepsUsed++
                        if (stopRequested) return AgentOutcome.Cancelled
                        showProgress(stepsUsed, budget.maxSteps, decision.reason)
                        val outcome = try {
                            executeStep(step)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            StepOutcome(false, note = error.message ?: "That step failed.")
                        }
                        if (outcome.cancelled) return AgentOutcome.Cancelled
                        steps.add(
                            TraceStep(
                                label = decision.reason,
                                detail = if (outcome.note == decision.reason) "" else outcome.note,
                                evidence = outcome.evidence,
                                informational = decision.action is AgentAction.Wait
                            )
                        )
                        if (outcome.note.isNotBlank() && outcome.note != decision.reason) notes.add(outcome.note)
                        when (decision.action) {
                            is AgentAction.TapTarget -> {
                                tapsUsed++
                                if (outcome.ok && outcome.evidence != null) {
                                    tapsVerified = true
                                    resolvedPointer = null
                                    pointerNote = ""
                                }
                            }
                            is AgentAction.LongPress -> tapsUsed++
                            is AgentAction.Scroll -> scrollsUsed++
                            is AgentAction.ScrollUntil -> scrollsUsed += decision.action.maxScrolls.coerceAtMost(8)
                            is AgentAction.Swipe -> scrollsUsed++
                            AgentAction.Dismiss -> dismissUsed++
                            AgentAction.Back -> backUsed++
                            is AgentAction.TypeText -> typedUsed++
                            is AgentAction.OpenApp -> openedApp = true
                            else -> Unit
                        }
                        previous = now
                        if (!outcome.ok) {
                            // A step that failed is information, not a dead end: the next decision is
                            // made from the screen as it now is. The budgets bound how long that can go on.
                            continue
                        }
                    }
                }
            }
        } finally {
            if (startedSessionHere) ScreenSession.releaseIfUnpinned(null)
        }
    }

    /**
     * What the screen looks like right now, preferring the live session: it is already re-reading on
     * its own, and waiting for the next revision is exactly "see what the last action produced".
     */
    private suspend fun observe(service: FlowAccessibilityService, waitMs: Long): ScreenObservation? {
        val fieldText = runCatching { service.currentFieldText().orEmpty() }.getOrNull().orEmpty()
        if (ScreenSession.isWatching()) {
            val minimum = ScreenSession.revision()
            val moved = ScreenSession.awaitRevision(minimum, waitMs)
            val snapshot = moved ?: ScreenSession.snapshot()
            if (snapshot != null) return ScreenObservation.of(snapshot.model, snapshot.revision, fieldText)
        }
        val model = readModel(service, includePixels = false) ?: return null
        return ScreenObservation.of(model, ScreenSession.revision(), fieldText)
    }

    /** Labels that were not there when the task started - what "that" can point at. */
    private fun appearedSince(previous: ScreenObservation?, baselineRevision: Int): List<String> {
        if (ScreenSession.isWatching() && baselineRevision > 0) {
            val diff = ScreenSession.since(baselineRevision)
            if (diff.appeared.isNotEmpty()) return diff.appeared
        }
        val before = previous?.labels?.map { TextMatcher.normalize(it) }?.toSet() ?: return emptyList()
        return ScreenSession.snapshot()?.model?.targets
            ?.map { TextMatcher.normalize(it.label) }
            ?.filter { it.isNotBlank() && it !in before }
            ?: previous?.labels.orEmpty().map { TextMatcher.normalize(it) }.filter { it !in before }
    }

    /**
     * Resolves "the third one" / "that chat at the top" / "the blue button" against the live target
     * list. An ambiguous reference becomes a question through the existing choice panel; it is never
     * guessed, and a control with no readable label is refused rather than tapped blind.
     */
    private data class PointerFacts(
        val resolved: ResolvedPointer? = null,
        val question: String? = null,
        val options: List<String> = emptyList(),
        val note: String = ""
    )

    /**
     * Resolves "the third one" / "that chat at the top" / "the blue button" against the live target
     * list. An ambiguous reference becomes a question through the existing choice panel; it is never
     * guessed, and a control with no readable label is refused rather than tapped blind.
     */
    private fun resolvePointer(phrase: String, appeared: List<String>): PointerFacts {
        val appearedSet = appeared.toSet()
        val resolution = ScreenSession.resolvePointing(phrase, appearedSet) ?: run {
            val model = ScreenSession.currentModel() ?: return PointerFacts(
                note = "I'm not watching the screen yet, so I can't tell which one you mean."
            )
            TargetLocator.resolve(
                phrase = phrase,
                targets = model.targets,
                appeared = appearedSet,
                hueOf = { box -> ScreenSession.hueAt(box) },
                screenWidth = model.screenWidth,
                screenHeight = model.screenHeight
            )
        }
        return when (resolution) {
            is PointResolution.Found -> when {
                resolution.target.label.isBlank() -> PointerFacts(
                    note = "I found the control you meant, but it has no readable name, so I stopped " +
                        "instead of tapping blind coordinates."
                )
                SafetyPolicy.isCredentialLabel(resolution.target.label) -> PointerFacts(
                    note = "That looks like a credential field, so I stopped."
                )
                else -> PointerFacts(
                    resolved = ResolvedPointer(
                        label = resolution.target.label,
                        evidence = resolution.evidence,
                        target = resolution.target
                    )
                )
            }
            is PointResolution.Ambiguous -> PointerFacts(
                question = resolution.question,
                note = resolution.reason,
                options = resolution.candidates.map { candidate ->
                    candidate.label.ifBlank { "the control ${candidate.box.centerY}px down the screen" }
                }
            )
            is PointResolution.NotFound -> PointerFacts(note = resolution.reason)
        }
    }

    private suspend fun executeStep(step: AgentStep): StepOutcome = when (val action = step.action) {
        is AgentAction.OpenApp -> openApp(action)
        is AgentAction.OpenUrl -> openUrl(action)
        is AgentAction.TapTarget -> tapTarget(action)
        is AgentAction.LongPress -> longPress(action)
        is AgentAction.TypeText -> typeText(action)
        is AgentAction.Scroll -> scroll(action)
        is AgentAction.Swipe -> swipe(action)
        is AgentAction.ScrollUntil -> scrollUntil(action)
        AgentAction.OpenMenu -> openMenu()
        AgentAction.ClearField -> clearField()
        AgentAction.SelectAll -> selectAll()
        AgentAction.CopySelection -> copySelection()
        AgentAction.Paste -> paste()
        AgentAction.Dismiss -> dismiss()
        AgentAction.Back -> goBack()
        is AgentAction.Wait -> {
            val started = System.currentTimeMillis()
            val beatsAtStart = ScreenPulse.count()
            val settled = awaitSettled(action.ms)
            val elapsed = System.currentTimeMillis() - started
            StepOutcome(
                true,
                note = if (settled) "" else "The screen was still updating after $elapsed ms.",
                evidence = if (settled) "the screen stopped changing after $elapsed ms" else null
            )
        }
        is AgentAction.AskAi -> askAi(action)
    }

    private suspend fun openApp(action: AgentAction.OpenApp): StepOutcome {
        val matches = withContext(Dispatchers.IO) { AppResolver.find(context, action.query) }
        if (matches.isEmpty()) {
            return StepOutcome(false, note = "I couldn't find an app matching \u201C${action.query}\u201D.")
        }
        val target = if (matches.size == 1) {
            matches.first()
        } else {
            matches.firstOrNull { it.label.equals(action.query, true) }
                ?: chooseApp(matches)
                ?: return StepOutcome(cancelled = true)
        }
        val service = FlowAccessibilityService.instance
        if (service?.foregroundPackage() == target.packageName) {
            return StepOutcome(
                true,
                note = "${target.label} is already open",
                evidence = "${target.label} was already in the foreground"
            )
        }
        ui.closePanel()
        if (!AppResolver.launch(context, target.packageName)) {
            return StepOutcome(false, note = "Android didn't open ${target.label}.")
        }
        if (service == null) {
            return StepOutcome(true, note = "Asked Android to open ${target.label}")
        }
        return if (awaitForeground(service, target.packageName, APP_LAUNCH_TIMEOUT_MS)) {
            StepOutcome(
                true,
                note = "Opened ${target.label}",
                evidence = "the foreground changed to ${target.label}"
            )
        } else {
            StepOutcome(
                false,
                note = "Android accepted the request to open ${target.label}, but the screen never " +
                    "switched to it. The app may have failed to start."
            )
        }
    }

    private fun openUrl(action: AgentAction.OpenUrl): StepOutcome {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(action.url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) {
            return StepOutcome(false, note = "No browser or app can open ${action.url}.")
        }
        val opened = runCatching { context.startActivity(intent) }.isSuccess
        return if (opened) {
            StepOutcome(true, note = "Opened ${action.url}", evidence = "Android accepted the link")
        } else {
            StepOutcome(false, note = "Android refused to open that link.")
        }
    }

    private suspend fun tapTarget(action: AgentAction.TapTarget): StepOutcome {
        val service = FlowAccessibilityService.instance ?: return accessibilityMissing()
        val model = readModel(service, includePixels = true)
            ?: return StepOutcome(false, note = "I couldn't read the current screen.")
        if (model.passwordFocused) {
            return StepOutcome(false, note = "A password field is focused, so I won't tap or read around it.")
        }

        val target = model.findTarget(action.label, action.ordinal)
            ?: return StepOutcome(
                false,
                note = "I couldn't find \u201C${action.label}\u201D on this screen.${captureHint(model)}"
            )
        if (target.editable && SafetyPolicy.isCredentialLabel(target.label)) {
            return StepOutcome(false, note = "That looks like a credential field, so I stopped.")
        }
        val risk = SafetyPolicy.assessTap(target.label)
        when (risk.level) {
            RiskLevel.BLOCKED -> return StepOutcome(false, note = risk.reason)
            RiskLevel.CONFIRM -> {
                val approved = confirm(
                    title = "Tap \u201C${target.label}\u201D?",
                    body = risk.reason + " Your Bubble will only tap it if you approve."
                )
                if (!approved) return StepOutcome(cancelled = true)
            }
            RiskLevel.SAFE -> Unit
        }

        ui.closePanel()
        val beforeHash = ScreenFusion.textHash(model)
        val beforePackage = model.packageName
        if (!tapOn(service, target)) {
            return StepOutcome(false, note = "Android didn't accept the tap on \u201C${target.label}\u201D.")
        }
        return if (awaitScreenChange(service, beforeHash, beforePackage, 2800)) {
            StepOutcome(true, note = "Tapped \u201C${target.label}\u201D", evidence = "the screen changed after the tap")
        } else {
            StepOutcome(
                false,
                note = "I tapped \u201C${target.label}\u201D, but the screen didn't change, so I stopped instead of guessing."
            )
        }
    }

    private suspend fun longPress(action: AgentAction.LongPress): StepOutcome {
        val service = FlowAccessibilityService.instance ?: return accessibilityMissing()
        val model = readModel(service, includePixels = true)
            ?: return StepOutcome(false, note = "I couldn't read the current screen.")
        if (model.passwordFocused) {
            return StepOutcome(false, note = "A password field is focused, so I won't touch the screen.")
        }
        val target = model.findTarget(action.label, action.ordinal)
            ?: return StepOutcome(
                false,
                note = "I couldn't find \u201C${action.label}\u201D on this screen.${captureHint(model)}"
            )
        val risk = SafetyPolicy.assessTap(target.label)
        when (risk.level) {
            RiskLevel.BLOCKED -> return StepOutcome(false, note = risk.reason)
            RiskLevel.CONFIRM -> {
                val approved = confirm(
                    title = "Hold \u201C${target.label}\u201D down?",
                    body = risk.reason + " Your Bubble will only hold it if you approve."
                )
                if (!approved) return StepOutcome(cancelled = true)
            }
            RiskLevel.SAFE -> Unit
        }

        ui.closePanel()
        val beforeHash = ScreenFusion.textHash(model)
        val beforePackage = model.packageName
        if (!longPressOn(service, target, action.holdMs)) {
            return StepOutcome(false, note = "Android didn't accept the long-press on \u201C${target.label}\u201D.")
        }
        return if (awaitScreenChange(service, beforeHash, beforePackage, 2800)) {
            StepOutcome(
                true,
                note = "Held \u201C${target.label}\u201D and something opened",
                evidence = "something appeared after the hold"
            )
        } else {
            StepOutcome(
                false,
                note = "I held \u201C${target.label}\u201D, but nothing opened, so I stopped instead of guessing."
            )
        }
    }

    private suspend fun typeText(action: AgentAction.TypeText): StepOutcome {
        val service = FlowAccessibilityService.instance ?: return accessibilityMissing()
        if (service.isPasswordFocused()) {
            return StepOutcome(false, note = "A password field is focused. I won't type here.")
        }

        var fieldLabel = ""
        if (action.searchFieldOnly) {
            val model = readModel(service, includePixels = false)
                ?: return StepOutcome(false, note = "I couldn't read the current screen.")
            var field = ScreenFusion.searchField(model.targets)
            var openedSearch = false
            var clearedNotice = false
            if (field == null) {
                clearedNotice = closeBlockingNotice(service)
                if (clearedNotice) field = waitForSearchField(service)
            }
            if (field == null && tapSearchIcon(service, model)) {
                openedSearch = true
                field = waitForSearchField(service)
            }
            if (field == null) {
                return StepOutcome(
                    false,
                    note = when {
                        openedSearch ->
                            "I opened the app's search, but it never showed a field to type in."
                        clearedNotice ->
                            "I closed the notice that was covering the screen, but there is still " +
                                "no search field or search button to use."
                        else ->
                            "I don't see a search field or a search button on this screen."
                    }
                )
            }
            fieldLabel = field.label
            if (!tapOn(service, field)) {
                return StepOutcome(false, note = "I found the search field but couldn't open it.")
            }
            if (!focusField(service, field)) {
                return StepOutcome(false, note = "I found the search field but it didn't take keyboard focus.")
            }
        } else if (service.focusedEditable() == null) {
            val model = readModel(service, includePixels = false)
                ?: return StepOutcome(false, note = "I couldn't read the current screen.")
            val editable = model.targets.firstOrNull {
                it.editable && !SafetyPolicy.isCredentialLabel(it.label)
            } ?: return StepOutcome(false, note = "There's no text field to type into on this screen.")
            fieldLabel = editable.label
            if (!tapOn(service, editable)) {
                return StepOutcome(false, note = "I found a text field but couldn't focus it.")
            }
            if (!focusField(service, editable)) {
                return StepOutcome(false, note = "I found a text field but it didn't take keyboard focus.")
            }
        } else {
            fieldLabel = service.focusedFieldLabel().orEmpty()
        }

        var result = if (action.searchFieldOnly) {
            service.setFocusedFieldText(action.text)
        } else {
            service.insertText(action.text)
        }
        if (result is TextInsertResult.Failed && result.message.startsWith("No text field is focused")) {
            // Closing a panel or an activity change can take focus back between the check above
            // and the write; re-acquiring it once is enough to tell a race from a real failure.
            if (refocusField(service, action.searchFieldOnly)) {
                result = if (action.searchFieldOnly) {
                    service.setFocusedFieldText(action.text)
                } else {
                    service.insertText(action.text)
                }
            }
        }
        val inserted = when (result) {
            is TextInsertResult.Inserted -> result
            is TextInsertResult.Copied -> return StepOutcome(
                false,
                note = "The field didn't accept the text. It's copied to your clipboard instead."
            )
            is TextInsertResult.Failed -> return StepOutcome(false, note = result.message)
        }

        delay(160)
        val snippet = action.text.take(60)
        var current = service.currentFieldText().orEmpty()
        var confirmed = TextEvidence.confirmed(snippet, current, service::hasVisibleText)
        if (!confirmed) {
            delay(400)
            current = service.currentFieldText().orEmpty()
            confirmed = TextEvidence.confirmed(snippet, current, service::hasVisibleText)
        }
        if (!confirmed) {
            return StepOutcome(false, note = "The text didn't appear in the field, so I stopped.")
        }
        LastActionMemory.rememberEdit(inserted.previousText, inserted.newText, fieldLabel)
        if (action.pressEnter) {
            // Ask only now that the text is visibly in the field: approving a submit before anything
            // is typed asks the user to agree to an action they cannot check.
            // Judge the risk from what the field *is*, not what it currently says - a browser's
            // address bar holds a URL, and a message composer can hold the word "search".
            val submissionLabel = service.focusedFieldLabel()?.takeIf { it.isNotBlank() } ?: fieldLabel
            val risk = SafetyPolicy.assessSubmission(submissionLabel)
            when (risk.level) {
                RiskLevel.BLOCKED -> return StepOutcome(false, note = risk.reason)
                RiskLevel.CONFIRM -> {
                    val approved = confirm(title = "Press Enter?", body = risk.reason)
                    if (!approved) return StepOutcome(cancelled = true)
                }
                RiskLevel.SAFE -> Unit
            }
            val before = readModel(service, includePixels = false)
            val beforeHash = before?.let { ScreenFusion.textHash(it) }
            val beforePackage = before?.packageName
            if (!service.pressImeEnter()) {
                return StepOutcome(
                    true,
                    note = "Typed \u201C${abbreviate(action.text)}\u201D, but I couldn't find a submit control."
                )
            }
            val changed = if (beforeHash != null && beforePackage != null) {
                awaitScreenChange(service, beforeHash, beforePackage, 2600)
            } else {
                delay(1100)
                false
            }
            if (!changed) {
                return StepOutcome(
                    true,
                    note = "Typed \u201C${abbreviate(action.text)}\u201D and pressed Enter, " +
                        "but the screen didn't change, so I can't confirm it ran."
                )
            }
        }
        return StepOutcome(
            true,
            note = if (action.pressEnter) {
                "Searched for \u201C${abbreviate(action.text)}\u201D"
            } else {
                "Typed \u201C${abbreviate(action.text)}\u201D"
            },
            evidence = "the field now contains the text"
        )
    }

    private fun scroll(action: AgentAction.Scroll): StepOutcome {
        val service = FlowAccessibilityService.instance ?: return accessibilityMissing()
        var done = 0
        repeat(action.times) {
            if (service.scroll(action.forward)) done++
        }
        val direction = if (action.forward) "down" else "up"
        return if (done > 0) {
            StepOutcome(
                true,
                note = "Scrolled $direction",
                evidence = "$done of ${action.times} scrolls accepted by the view"
            )
        } else {
            StepOutcome(false, note = "This screen didn't accept a scroll.")
        }
    }

    private suspend fun scrollUntil(action: AgentAction.ScrollUntil): StepOutcome {
        val service = FlowAccessibilityService.instance ?: return accessibilityMissing()
        val maxScrolls = action.maxScrolls.coerceIn(1, 12)
        if (service.hasVisibleText(action.label)) {
            return if (action.tapAfter) {
                tapFound(service, action.label, 0)
            } else {
                StepOutcome(
                    true,
                    note = "\u201C${action.label}\u201D is already on screen",
                    evidence = "the label was already visible"
                )
            }
        }

        var swipes = 0
        var lastText = service.screenText(1500)
        while (swipes < maxScrolls) {
            if (!service.scroll(true)) {
                return StepOutcome(
                    false,
                    note = if (swipes == 0) {
                        "This screen didn't scroll, and I couldn't see \u201C${action.label}\u201D."
                    } else {
                        "The screen stopped scrolling after $swipes swipe${if (swipes == 1) "" else "s"}, " +
                            "and I never saw \u201C${action.label}\u201D."
                    }
                )
            }
            swipes++
            delay(420)
            if (service.hasVisibleText(action.label)) {
                if (!action.tapAfter) {
                    return StepOutcome(
                        true,
                        note = "Found \u201C${action.label}\u201D after scrolling $swipes time${if (swipes == 1) "" else "s"}",
                        evidence = "the label appeared after $swipes scroll${if (swipes == 1) "" else "s"}"
                    )
                }
                return tapFound(service, action.label, swipes)
            }
            val currentText = service.screenText(1500)
            if (currentText.isNotBlank() && currentText == lastText) {
                return StepOutcome(
                    false,
                    note = "The screen stopped changing after $swipes swipe${if (swipes == 1) "" else "s"}, " +
                        "and I never saw \u201C${action.label}\u201D."
                )
            }
            lastText = currentText
        }
        return StepOutcome(
            false,
            note = "I scrolled $maxScrolls times but never saw \u201C${action.label}\u201D."
        )
    }

    private suspend fun tapFound(
        service: FlowAccessibilityService,
        label: String,
        swipes: Int
    ): StepOutcome {
        val risk = SafetyPolicy.assessTap(label)
        when (risk.level) {
            RiskLevel.BLOCKED -> return StepOutcome(false, note = risk.reason)
            RiskLevel.CONFIRM -> {
                val approved = confirm(
                    title = "Tap \u201C$label\u201D?",
                    body = risk.reason + " Your Bubble will only tap it if you approve."
                )
                if (!approved) return StepOutcome(cancelled = true)
            }
            RiskLevel.SAFE -> Unit
        }
        val before = readModel(service, includePixels = false)
        val beforeHash = before?.let { ScreenFusion.textHash(it) }
        val beforePackage = before?.packageName
        ui.closePanel()
        if (!service.tapByText(label).success) {
            return StepOutcome(false, note = "I found \u201C$label\u201D but Android didn't accept the tap.")
        }
        val changed = if (beforeHash != null && beforePackage != null) {
            awaitScreenChange(service, beforeHash, beforePackage, 2600)
        } else {
            delay(900)
            false
        }
        val foundNote = if (swipes > 0) " after scrolling $swipes time${if (swipes == 1) "" else "s"}" else ""
        return if (changed) {
            StepOutcome(
                true,
                note = if (swipes > 0) "Scrolled to \u201C$label\u201D and tapped it$foundNote" else "Tapped \u201C$label\u201D",
                evidence = "the screen changed after the tap"
            )
        } else {
            StepOutcome(
                true,
                note = "Tapped \u201C$label\u201D$foundNote, but the screen didn't change, " +
                    "so I can't confirm it opened."
            )
        }
    }

    private fun goBack(): StepOutcome {
        val service = FlowAccessibilityService.instance ?: return accessibilityMissing()
        return if (service.pressBack()) {
            StepOutcome(true, note = "Went back", evidence = "Android accepted the back action")
        } else {
            StepOutcome(false, note = "Android didn't accept the back action.")
        }
    }

    private suspend fun swipe(action: AgentAction.Swipe): StepOutcome {
        val service = FlowAccessibilityService.instance ?: return accessibilityMissing()
        val before = readModel(service, includePixels = false)
        val beforeHash = before?.let { ScreenFusion.textHash(it) }
        val beforePackage = before?.packageName
        val direction = if (action.toRight) "right" else "left"

        ui.closePanel()
        var sent = 0
        repeat(action.times.coerceIn(1, 4)) {
            if (stopRequested) return StepOutcome(cancelled = true)
            if (service.swipeHorizontal(action.toRight)) sent++
            delay(460)
        }
        if (sent == 0) {
            return StepOutcome(false, note = "Android didn't accept the swipe gesture.")
        }
        return if (beforeHash != null && beforePackage != null &&
            awaitScreenChange(service, beforeHash, beforePackage, 2200)
        ) {
            StepOutcome(true, note = "Swiped $direction", evidence = "the screen changed after the swipe")
        } else {
            StepOutcome(
                false,
                note = "I swiped $direction, but the screen didn't change. This view may not move sideways."
            )
        }
    }

    private suspend fun openMenu(): StepOutcome {
        val service = FlowAccessibilityService.instance ?: return accessibilityMissing()
        val before = readModel(service, includePixels = true)
        val beforeHash = before?.let { ScreenFusion.textHash(it) }
        val beforePackage = before?.packageName

        ui.closePanel()
        val result = service.openOverflow()
        if (!result.success) {
            return StepOutcome(false, note = result.note ?: "I couldn't open a menu on this screen.")
        }
        return if (beforeHash != null && beforePackage != null &&
            awaitScreenChange(service, beforeHash, beforePackage, 2400)
        ) {
            StepOutcome(true, note = "Opened \u201C${result.label}\u201D", evidence = "the screen changed after the tap")
        } else {
            StepOutcome(
                false,
                note = "I tapped \u201C${result.label}\u201D, but no menu appeared, so I stopped."
            )
        }
    }

    private fun clearField(): StepOutcome {
        val service = FlowAccessibilityService.instance ?: return accessibilityMissing()
        return when (val outcome = service.clearFocusedField()) {
            is FieldActionOutcome.Done -> StepOutcome(true, note = outcome.note, evidence = outcome.note)
            is FieldActionOutcome.Blocked -> StepOutcome(false, note = outcome.message)
        }
    }

    private fun selectAll(): StepOutcome {
        val service = FlowAccessibilityService.instance ?: return accessibilityMissing()
        return when (val outcome = service.selectAllFocused()) {
            is FieldActionOutcome.Done -> StepOutcome(true, note = outcome.note, evidence = outcome.note)
            is FieldActionOutcome.Blocked -> StepOutcome(false, note = outcome.message)
        }
    }

    private fun copySelection(): StepOutcome {
        val service = FlowAccessibilityService.instance ?: return accessibilityMissing()
        return when (val outcome = service.copyFocused()) {
            is FieldActionOutcome.Done -> StepOutcome(true, note = outcome.note, evidence = outcome.note)
            is FieldActionOutcome.Blocked -> StepOutcome(false, note = outcome.message)
        }
    }

    private suspend fun paste(): StepOutcome {
        val service = FlowAccessibilityService.instance ?: return accessibilityMissing()
        if (service.isPasswordFocused()) {
            return StepOutcome(false, note = "A password field is focused. I won't paste here.")
        }
        val before = service.currentFieldText().orEmpty()
        return when (val outcome = service.pasteFocused()) {
            is FieldActionOutcome.Blocked -> StepOutcome(false, note = outcome.message)
            is FieldActionOutcome.Done -> {
                delay(180)
                val after = service.currentFieldText().orEmpty()
                if (after == before) {
                    StepOutcome(
                        false,
                        note = "The app accepted the paste but the field didn't change, so nothing was added."
                    )
                } else {
                    LastActionMemory.rememberEdit(before, after, service.focusedFieldLabel())
                    StepOutcome(true, note = "Pasted into the field", evidence = "the field text changed")
                }
            }
        }
    }

    private suspend fun dismiss(): StepOutcome {
        val service = FlowAccessibilityService.instance ?: return accessibilityMissing()
        val before = readModel(service, includePixels = false)
        val beforeHash = before?.let { ScreenFusion.textHash(it) }
        val beforePackage = before?.packageName

        val control = service.findCloseControl()
        if (!control.success) {
            return dismissWithBack(service, beforeHash, beforePackage, control.note)
        }
        val label = control.label.orEmpty()
        val risk = SafetyPolicy.assessTap(label)
        when (risk.level) {
            RiskLevel.BLOCKED -> return StepOutcome(false, note = risk.reason)
            RiskLevel.CONFIRM -> {
                val approved = confirm(
                    title = "Tap \u201C$label\u201D?",
                    body = risk.reason + " Your Bubble will only tap it if you approve."
                )
                if (!approved) return StepOutcome(cancelled = true)
            }
            RiskLevel.SAFE -> Unit
        }

        ui.closePanel()
        if (!service.tapByText(label).success) {
            return StepOutcome(false, note = "\u201C$label\u201D is on screen but didn't accept a tap.")
        }
        return if (beforeHash != null && beforePackage != null &&
            awaitScreenChange(service, beforeHash, beforePackage, 2400)
        ) {
            StepOutcome(
                true,
                note = "Closed it with \u201C$label\u201D",
                evidence = "the screen changed after closing"
            )
        } else {
            StepOutcome(
                true,
                note = "Tapped \u201C$label\u201D, but the screen didn't change, so I can't confirm it closed."
            )
        }
    }

    private suspend fun dismissWithBack(
        service: FlowAccessibilityService,
        beforeHash: Int?,
        beforePackage: String?,
        reason: String?
    ): StepOutcome {
        val approved = confirm(
            title = "Use the back gesture?",
            body = (reason ?: "I couldn't read a close button here.") +
                " Your Bubble can press back instead, which closes keyboards, popups, and this screen."
        )
        if (!approved) return StepOutcome(cancelled = true)

        ui.closePanel()
        if (!service.pressBack()) {
            return StepOutcome(false, note = "Android didn't accept the back action.")
        }
        return if (beforeHash != null && beforePackage != null &&
            awaitScreenChange(service, beforeHash, beforePackage, 2200)
        ) {
            StepOutcome(
                true,
                note = "Pressed back and the screen changed",
                evidence = "the screen changed after back"
            )
        } else {
            StepOutcome(
                true,
                note = "Pressed back, but I couldn't read a change on the screen, so please check it."
            )
        }
    }

    private suspend fun askAi(action: AgentAction.AskAi): StepOutcome {
        val service = FlowAccessibilityService.instance ?: return accessibilityMissing()
        if (!LlmClient.isConfigured(context, AiRole.ScreenAnswer)) {
            return StepOutcome(false, note = "No AI model is configured. Add one in Your Bubble settings.")
        }
        val model = readModel(service, includePixels = true)
        val text = model?.readableText(5000).orEmpty()
        if (text.isBlank()) return StepOutcome(false, note = "There's no readable text on this screen.")

        val endpoint = LlmClient.endpointLabel(context, AiRole.ScreenAnswer)
        val approved = confirm(
            title = "Send screen text to AI?",
            body = "This sends ${text.length} characters of visible text to $endpoint. " +
                "Nothing is stored, and no other data leaves your device."
        )
        if (!approved) return StepOutcome(cancelled = true)

        ui.showPanel(progress("Asking AI", "The answer will appear here."))
        val answer = LlmClient.askAboutScreen(action.instruction, text, LlmClient.config(context, AiRole.ScreenAnswer))
            .getOrElse { error ->
                return StepOutcome(false, note = "The AI request failed: ${error.message ?: "unknown error"}")
            }
        LastActionMemory.rememberOutput(answer)
        return StepOutcome(true, note = answer.take(1800), evidence = "the model answered")
    }

    /**
     * Most apps hide search behind an icon rather than showing a field, so the icon has to be opened
     * before anything can be typed. Only a control the safety policy considers unambiguous is tapped,
     * and the caller still has to find a real field afterwards.
     */
    /**
     * Search is usually a new activity, sometimes in another package, and its tree is empty for a
     * moment while it draws. Poll for the field instead of guessing a settle time.
     */
    private suspend fun waitForSearchField(service: FlowAccessibilityService): ScreenTarget? {
        val deadline = System.currentTimeMillis() + SEARCH_FIELD_TIMEOUT_MS
        var field: ScreenTarget? = null
        while (field == null && System.currentTimeMillis() < deadline) {
            delay(SEARCH_FIELD_POLL_MS)
            val next = readModel(service, includePixels = false)
            if (next != null) field = ScreenFusion.searchField(next.targets)
        }
        return field
    }

    /**
     * A first-run notice often sits on top of the screen an app really opens to, which leaves a
     * chain with nothing to search. Only a control that merely closes something is tapped, and the
     * back gesture is never used here: leaving the app would be worse than stopping with a reason.
     */
    private suspend fun closeBlockingNotice(service: FlowAccessibilityService): Boolean {
        val control = service.findCloseControl()
        if (!control.success) return false
        val label = control.label.orEmpty()
        if (SafetyPolicy.assessTap(label).level != RiskLevel.SAFE) return false
        ui.closePanel()
        return service.tapByText(label).success
    }

    private suspend fun tapSearchIcon(service: FlowAccessibilityService, model: ScreenModel): Boolean {
        val affordance = ScreenFusion.searchAffordance(model.targets) ?: return false
        if (SafetyPolicy.assessTap(affordance.label).level != RiskLevel.SAFE) return false
        return tapOn(service, affordance)
    }

    private suspend fun tapOn(service: FlowAccessibilityService, target: ScreenTarget): Boolean {
        if (target.source == TargetSource.ACCESSIBILITY) {
            if (service.tapByText(target.label).success) return true
        }
        return tapAt(service, target)
    }

    private suspend fun tapAt(service: FlowAccessibilityService, target: ScreenTarget): Boolean {
        ui.hideForScreenRead()
        return try {
            delay(80)
            service.tapAt(target.box.centerX, target.box.centerY)
        } finally {
            delay(120)
            ui.restoreAfterScreenRead()
        }
    }

    private suspend fun longPressOn(
        service: FlowAccessibilityService,
        target: ScreenTarget,
        holdMs: Int
    ): Boolean {
        if (target.source == TargetSource.ACCESSIBILITY &&
            service.longPressByText(target.label, holdMs).success
        ) {
            return true
        }
        ui.hideForScreenRead()
        return try {
            delay(80)
            service.longPressAt(target.box.centerX, target.box.centerY, holdMs)
        } finally {
            delay(holdMs + 240L)
            ui.restoreAfterScreenRead()
        }
    }

    private suspend fun focusField(service: FlowAccessibilityService, target: ScreenTarget): Boolean {
        if (awaitEditable(service, 1400)) return true
        tapAt(service, target)
        return awaitEditable(service, 1600)
    }

    /** Re-acquires focus from the current screen after something took it away mid-step. */
    private suspend fun refocusField(service: FlowAccessibilityService, searchFieldOnly: Boolean): Boolean {
        if (awaitEditable(service, 400)) return true
        val model = readModel(service, includePixels = false) ?: return false
        val target = if (searchFieldOnly) {
            ScreenFusion.searchField(model.targets)
        } else {
            model.targets.firstOrNull { it.editable && !SafetyPolicy.isCredentialLabel(it.label) }
        } ?: return false
        if (!tapOn(service, target)) return false
        return focusField(service, target)
    }

    /**
     * Returns true once the screen has been still for [SCREEN_QUIET_MS] *as observed from here*.
     * Inherited stillness is not enough: right after a launch the window can be up before it has
     * started drawing, and a wait that trusts an old quiet period waves the next step through
     * against a half-built screen.
     */
    private suspend fun awaitSettled(maxMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + maxMs
        var lastBeat = ScreenPulse.count()
        var stillSince = System.currentTimeMillis()
        while (true) {
            val now = System.currentTimeMillis()
            val beat = ScreenPulse.count()
            if (beat != lastBeat) {
                lastBeat = beat
                stillSince = now
            }
            if (now - stillSince >= SCREEN_QUIET_MS) return true
            if (now >= deadline) return false
            delay(PULSE_POLL_MS)
        }
    }

    private suspend fun awaitEditable(service: FlowAccessibilityService, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (service.focusedEditable() != null) return true
            delay(PULSE_POLL_MS)
        }
        return service.focusedEditable() != null
    }

    /**
     * Waits for the named app to become the foreground app. Comparing against a package read
     * before the launch is fragile: that one read can land on Your Bubble's own panel or on the
     * null gap between activities, which previously turned a real launch into a blind 900 ms sleep.
     */
    private suspend fun awaitForeground(
        service: FlowAccessibilityService,
        packageName: String,
        timeoutMs: Long
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (service.foregroundPackage() == packageName) return true
            delay(PULSE_POLL_MS)
        }
        return service.foregroundPackage() == packageName
    }

    private suspend fun awaitScreenChange(
        service: FlowAccessibilityService,
        hash: Int,
        packageName: String,
        timeoutMs: Long
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastBeat = ScreenPulse.count()
        var nextForcedRead = System.currentTimeMillis() + BLIND_REREAD_MS
        while (System.currentTimeMillis() < deadline) {
            delay(PULSE_POLL_MS)
            val beat = ScreenPulse.count()
            val now = System.currentTimeMillis()
            // Re-reading the tree is the expensive part, so only do it when something actually
            // changed, with an occasional blind read for screens that emit nothing useful.
            if (beat == lastBeat && now < nextForcedRead) continue
            lastBeat = beat
            nextForcedRead = now + BLIND_REREAD_MS
            val model = readModel(service, includePixels = false) ?: continue
            if (model.packageName != packageName || ScreenFusion.textHash(model) != hash) return true
        }
        return false
    }

    private suspend fun readModel(
        service: FlowAccessibilityService,
        includePixels: Boolean
    ): ScreenModel? {
        val pixels = includePixels && ScreenCapture.isActive
        if (pixels) ui.hideForScreenRead()
        return try {
            if (pixels) delay(130)
            ScreenModelFactory.build(service, pixels)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            null
        } finally {
            if (pixels) ui.restoreAfterScreenRead()
        }
    }

    private suspend fun confirm(title: String, body: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingConfirm.set(deferred)
        val approve: () -> Unit = {
            ui.closePanel()
            deferred.complete(true)
        }
        val deny: () -> Unit = {
            ui.closePanel()
            deferred.complete(false)
        }
        ui.showPanel(
            PanelContent(
                title = title,
                body = body,
                dismissible = false,
                actions = listOf(
                    PanelAction("Approve", primary = true, onClick = approve),
                    PanelAction("Cancel", onClick = deny)
                ),
                voicePrompt = "$title. ${body.take(180)}".replace('\n', ' '),
                onVoiceApprove = approve,
                onVoiceDeny = deny
            )
        )
        return try {
            deferred.await()
        } finally {
            pendingConfirm.compareAndSet(deferred, null)
        }
    }

    private suspend fun askChoice(title: String, body: String, options: List<String>): Int? {
        val deferred = CompletableDeferred<Int?>()
        pendingChoice.set(deferred)
        ui.showPanel(
            PanelContent(
                title = title,
                body = body,
                dismissible = false,
                actions = options.mapIndexed { index, label ->
                    PanelAction(label) {
                        ui.closePanel()
                        deferred.complete(index)
                    }
                } + PanelAction("Cancel") {
                    ui.closePanel()
                    deferred.complete(null)
                }
            )
        )
        return try {
            deferred.await()
        } finally {
            pendingChoice.compareAndSet(deferred, null)
        }
    }

    private suspend fun chooseApp(matches: List<AppMatch>): AppMatch? {
        val options = matches.take(4)
        val index = askChoice("Which app?", "I found more than one match.", options.map { it.label })
            ?: return null
        return options.getOrNull(index)
    }

    private fun showProgress(index: Int, total: Int, label: String) {
        ui.showPanel(
            PanelContent(
                title = "Step ${index + 1} of $total",
                body = label,
                dismissible = false,
                actions = listOf(PanelAction("Stop") { stop(showStopped = true) })
            )
        )
    }

    private fun progress(title: String, body: String): PanelContent = PanelContent(
        title = title,
        body = body,
        dismissible = false,
        actions = listOf(PanelAction("Stop") { stop(showStopped = true) })
    )

    private fun finish(goal: String, outcome: AgentOutcome) {
        when (outcome) {
            is AgentOutcome.Completed -> {
                val trace = outcome.trace
                val kind = trace?.outcomeFor(stepsDone = true) ?: OutcomeKind.PARTIAL
                HistoryStore.add(
                    context,
                    "Agent",
                    "Completed: ${goal.take(70)}",
                    kind.historyStatus()
                )
                val body = trace?.renderBody()?.takeIf { it.isNotBlank() } ?: outcome.summary
                ui.announce(
                    if (kind == OutcomeKind.COMPLETED) "Done. ${outcome.summary.take(140)}"
                    else "Finished with notes. ${outcome.summary.take(140)}"
                )
                ui.showPanel(
                    PanelContent(
                        title = if (kind == OutcomeKind.COMPLETED) "Task complete" else "Finished with notes",
                        body = body.take(4000),
                        scrollable = true,
                        copyText = outcome.summary,
                        actions = listOf(PanelAction("Done", primary = true) { ui.closePanel() })
                    )
                )
            }
            is AgentOutcome.Failed -> {
                HistoryStore.add(context, "Agent", "Stopped: ${goal.take(70)}", OutcomeKind.FAILED.historyStatus())
                ui.announce("I stopped. ${outcome.summary.take(140)}")
                val completed = outcome.trace?.steps?.count { it.verified && !it.informational } ?: 0
                val body = buildString {
                    append(outcome.summary)
                    if (completed > 0) {
                        append("\n\n")
                        append("$completed step${if (completed == 1) "" else "s"} confirmed before stopping.")
                    }
                    outcome.trace?.let { trace ->
                        if (trace.steps.isNotEmpty()) {
                            append("\n\n")
                            append(trace.steps.take(6).joinToString("\n") { it.render() })
                        }
                    }
                }
                ui.showPanel(
                    PanelContent(
                        title = "I stopped",
                        body = body.take(4000),
                        scrollable = true,
                        actions = listOf(PanelAction("Close", primary = true) { ui.closePanel() })
                    )
                )
            }
            AgentOutcome.Cancelled -> {
                HistoryStore.add(context, "Agent", "Cancelled: ${goal.take(70)}", OutcomeKind.AWAITING_USER.historyStatus())
                ui.announce("Cancelled.")
                ui.showPanel(PanelContent(title = "Cancelled", autoDismissMs = 1200))
            }
        }
    }

    private fun accessibilityMissing(): StepOutcome = StepOutcome(
        false,
        note = "Accessibility is off, so I can't see or act on the screen. " +
            "Enable Your Bubble voice access in Android settings."
    )

    private fun captureHint(model: ScreenModel): String = when {
        model.secureLikely -> " This app may block screen capture, so I only had accessibility text."
        !ScreenCapture.isActive ->
            " Turn on screen access so I can read apps that hide their controls."
        else -> ""
    }

    private fun abbreviate(text: String, max: Int = 48): String =
        if (text.length <= max) text else text.take(max - 1) + "\u2026"
}
