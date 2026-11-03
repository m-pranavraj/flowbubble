package com.flowbubble.app.service

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.flowbubble.app.MainActivity
import com.flowbubble.app.R
import com.flowbubble.app.access.FlowAccessibilityService
import com.flowbubble.app.agent.AutomationDecision
import com.flowbubble.app.agent.AutomationEngine
import com.flowbubble.app.agent.AutomationRule
import com.flowbubble.app.agent.AutomationSignal
import com.flowbubble.app.agent.NotificationSignal
import com.flowbubble.app.agent.TriggerKind
import com.flowbubble.app.commands.CommandExecutor
import com.flowbubble.app.core.AutomationStore
import com.flowbubble.app.notifications.NotificationCenter
import com.flowbubble.app.overlay.PanelAction
import com.flowbubble.app.overlay.PanelContent
import com.flowbubble.app.overlay.OverlayUi
import com.flowbubble.app.voice.CommandParser

private const val TAG = "FlowBubble|Automation"
private const val REQUEST = 42
private const val CHANNEL_ID = "flowbubble_automations"
private const val PROMPT_ID = 104
private const val PANEL_ID = 105
private const val ALARM_WINDOW_MS = 5 * 60_000L

/** How often a polled wake looks for an app opening or a notification arriving. */
private const val HEARTBEAT_MS = 5 * 60_000L

/** The gap between one automation step and the next, so each phrase lands like a separate request. */
private const val STEP_GAP_MS = 4_000L

/**
 * Wakes the automation engine: at boot, on the next time trigger, and when the app reports an app
 * coming to the front or a phrase being said. Every rule it runs is gated by the engine, which refuses
 * anything while the bubble is off — so the bubble's switch is the kill switch for automations too.
 */
class AutomationReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_RUN = "com.flowbubble.app.action.AUTOMATIONS_RUN"
        const val ACTION_APP_OPENED = "com.flowbubble.app.action.APP_OPENED"
        const val ACTION_PHRASE = "com.flowbubble.app.action.PHRASE_SPOKEN"
        const val ACTION_APPROVE = "com.flowbubble.app.action.AUTOMATION_APPROVE"
        const val ACTION_DISMISS = "com.flowbubble.app.action.AUTOMATION_DISMISS"
        const val EXTRA_PACKAGE = "com.flowbubble.app.extra.PACKAGE"
        const val EXTRA_PHRASE = "com.flowbubble.app.extra.PHRASE"
        const val EXTRA_RULE = "com.flowbubble.app.extra.RULE"
        const val EXTRA_KEY = "com.flowbubble.app.extra.KEY"
        const val EXTRA_TOKEN = "com.flowbubble.app.extra.TOKEN"

        fun notifyAppOpened(context: Context, packageName: String) =
            send(context, Intent(context, AutomationReceiver::class.java)
                .setAction(ACTION_APP_OPENED)
                .putExtra(EXTRA_PACKAGE, packageName))

        fun notifyPhrase(context: Context, phrase: String) =
            send(context, Intent(context, AutomationReceiver::class.java)
                .setAction(ACTION_PHRASE)
                .putExtra(EXTRA_PHRASE, phrase))

        fun notifyNotificationArrived(context: Context) =
            send(context, Intent(context, AutomationReceiver::class.java).setAction(ACTION_RUN))

        /** Arms the next time trigger and re-reads the rules. Call it whenever a rule is saved or toggled. */
        fun armNext(context: Context) {
            val now = System.currentTimeMillis()
            val rules = AutomationStore.list(context)
            val due = rules.mapNotNull { AutomationEngine.nextRunAtMillis(it, now) }.minOrNull()
            // Android tells no one when another app comes to the front, so those rules are polled instead.
            // Polling only happens while the bubble is on: off is the kill switch, so no job runs at all.
            val polled = rules
                .any {
                    OverlayService.isRunning &&
                        it.enabled &&
                        it.trigger.kind != TriggerKind.TIME &&
                        it.steps.isNotEmpty()
                }
                .let { if (it) now + HEARTBEAT_MS else null }
            val at = listOfNotNull(due, polled).minOrNull()
            if (at == null) {
                cancelAlarms(context)
                return
            }
            // Inexact on purpose: an exact alarm would need SCHEDULE_EXACT_ALARM, which this app does not
            // declare, and a few minutes of drift on a "7am" automation is not worth asking for it.
            schedule(context, at, wake = due != null && at == due)
        }

        /** The kill switch: while the bubble is off, no automation is scheduled at all. */
        fun cancelAlarms(context: Context) {
            context.getSystemService(AlarmManager::class.java)
                ?.cancel(alarmIntent(context.applicationContext))
        }

        /** Arm when the bubble is on, stand down when it is not. Call it whenever the switch changes. */
        fun reschedule(context: Context) {
            if (OverlayService.isRunning) armNext(context) else {
                cancelAlarms(context)
                AutomationHost.stopAll()
            }
        }

        private fun schedule(context: Context, atMillis: Long, wake: Boolean) {
            val manager = context.getSystemService(AlarmManager::class.java) ?: return
            val type = if (wake) AlarmManager.RTC_WAKEUP else AlarmManager.RTC
            runCatching { manager.setWindow(type, atMillis, ALARM_WINDOW_MS, alarmIntent(context)) }
        }

        private fun send(context: Context, intent: Intent) {
            runCatching { context.sendBroadcast(intent.setPackage(context.packageName)) }
        }

        private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST,
            Intent(context, AutomationReceiver::class.java).setAction(ACTION_RUN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        ensureChannel(app)
        when (intent.action) {
            ACTION_APPROVE -> answer(app, intent)
            ACTION_DISMISS -> clearPrompts(app)
            else -> sweep(app, intent)
        }
    }

    private fun sweep(context: Context, intent: Intent) {
        val signal = signal(context, intent)
        AutomationStore.list(context).forEach { rule -> decide(context, rule, signal) }
        // The bubble's switch is the kill switch: off, an in-flight rule stops and nothing keeps polling.
        // A wake that simply finds the bubble off re-arms the plan instead of cancelling the user's schedule.
        if (!signal.bubbleRunning) AutomationHost.stopAll()
        armNext(context)
    }

    private fun decide(context: Context, rule: AutomationRule, signal: AutomationSignal) {
        when (val decision = AutomationEngine.evaluate(rule, signal)) {
            is AutomationDecision.Run -> {
                AutomationStore.recordRun(
                    context,
                    rule.id,
                    decision.triggerKey,
                    signal.nowMillis,
                    "Started ${decision.matched}"
                )
                AutomationHost.run(context, rule)
                log(rule, "ran ${decision.matched}")
            }
            is AutomationDecision.Ask -> {
                AutomationStore.recordAsked(context, rule.id, decision.triggerKey, "Needs your OK")
                ask(context, decision)
                log(rule, "asked first ${decision.matched}")
            }
            is AutomationDecision.Blocked -> Unit
        }
    }

    /** Only the rule's own name and kind reach the log: never a phrase, a screen, or a notification body. */
    private fun log(rule: AutomationRule, what: String) {
        Log.i(TAG, "${rule.name} [${rule.trigger.kind}] $what")
    }

    private fun signal(context: Context, intent: Intent): AutomationSignal {
        val now = System.currentTimeMillis()
        val reported = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val seen = reported.ifBlank { FlowAccessibilityService.instance?.foregroundPackage().orEmpty() }
        val spoken = intent.getStringExtra(EXTRA_PHRASE).orEmpty()
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val newest = NotificationCenter.recent(1).firstOrNull()
        return AutomationSignal(
            nowMillis = now,
            bubbleRunning = OverlayService.isRunning,
            foregroundPackage = seen,
            // An app the voice layer reported counts as opening now; a polled one only since we last looked.
            foregroundSinceMillis = if (reported.isNotBlank()) now else AutomationWatch.observe(seen, now),
            notification = newest?.let {
                NotificationSignal(
                    key = it.key,
                    packageName = it.packageName,
                    appLabel = it.appLabel,
                    title = it.title,
                    text = it.text,
                    postedAtMillis = it.postedAt
                )
            },
            // A phrase only ever reaches the engine as something the voice layer reported this moment.
            spokenPhrase = spoken,
            spokenAtMillis = if (spoken.isBlank()) 0L else now,
            screenLocked = keyguard?.isKeyguardLocked == true,
            // Android gives a receiver no signal for a sign-in prompt, so the lock check is the real one.
            awaitingAuthentication = false
        )
    }

    /**
     * The fresh confirmation a consequential rule must have before anything runs. It names the rule and
     * how many steps it wants, never what those steps would say, and an unanswered prompt runs nothing.
     */
    private fun ask(context: Context, decision: AutomationDecision.Ask) {
        val rule = decision.rule
        val key = decision.triggerKey
        val token = AutomationPrompts.hold(rule.id, key) {
            AutomationStore.recordRun(
                context,
                rule.id,
                key,
                System.currentTimeMillis(),
                "Ran after your OK"
            )
            AutomationHost.run(context, rule)
        }
        val summary = AutomationEngine.summary(rule)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(AutomationEngine.question(rule))
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setAutoCancel(true)
            .setContentIntent(openAutomations(context))
            .addAction(R.drawable.ic_mic, "Run it", approveIntent(context, token, rule.id, key))
            .addAction(R.drawable.ic_mic, "Cancel", dismissIntent(context))
            .build()
        post(context, PROMPT_ID, notification)
    }

    private fun approveIntent(context: Context, token: Int, ruleId: String, triggerKey: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            token,
            Intent(context, AutomationReceiver::class.java)
                .setAction(ACTION_APPROVE)
                .putExtra(EXTRA_RULE, ruleId)
                .putExtra(EXTRA_KEY, triggerKey)
                .putExtra(EXTRA_TOKEN, token),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Closing the prompt is not an answer: nothing is run and nothing is recorded as run. */
    private fun dismissIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST + 1,
        Intent(context, AutomationReceiver::class.java).setAction(ACTION_DISMISS),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun openAutomations(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST + 2,
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_AUTOMATIONS),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun answer(context: Context, intent: Intent) {
        val token = intent.getIntExtra(EXTRA_TOKEN, 0)
        val ruleId = intent.getStringExtra(EXTRA_RULE).orEmpty()
        val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        // One-shot, and it has to match the exact rule and trigger instance that was shown, so a replayed
        // or stale tap approves nothing at all.
        val matched = AutomationPrompts.take(token, ruleId, key)
        Log.i(TAG, if (matched) "approval honoured for $ruleId" else "approval ignored for $ruleId")
        clearPrompts(context)
    }

    private fun clearPrompts(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(PROMPT_ID)
        manager.cancel(PANEL_ID)
    }

    private fun post(context: Context, id: Int, notification: Notification) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        runCatching { manager.notify(id, notification) }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Automations",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Where an automation asks before it sends, spends, or deletes anything."
            lockscreenVisibility = Notification.VISIBILITY_SECRET
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}

/** Outstanding prompts, keyed by rule and trigger instance so an old tap cannot approve a new run. */
private object AutomationPrompts {
    private val owners = mutableMapOf<Int, Pair<String, String>>()
    private val callbacks = mutableMapOf<Int, () -> Unit>()
    private var nextToken = 1

    @Synchronized
    fun hold(ruleId: String, triggerKey: String, action: () -> Unit): Int {
        // One live prompt per rule: asking again supersedes the earlier one, so an approval always
        // belongs to the trigger instance currently on screen.
        owners.entries.filter { it.value.first == ruleId }.map { it.key }.forEach { stale ->
            owners.remove(stale)
            callbacks.remove(stale)
        }
        val token = nextToken++
        owners[token] = ruleId to triggerKey
        callbacks[token] = action
        return token
    }

    /** True when the tap matched a live prompt, in which case and only then its callback runs. */
    @Synchronized
    fun take(token: Int, ruleId: String, triggerKey: String): Boolean {
        val owner = owners[token] ?: return false
        if (owner != ruleId to triggerKey) return false
        owners.remove(token)
        val action = callbacks.remove(token) ?: return false
        action()
        return true
    }
}

/**
 * A poll cannot see an app arrive, only that the front app changed since the last look, so the previous
 * look is the honest "no later than" for the open. Lives in the app process, which is the same process
 * the bubble keeps alive while automations are allowed to run.
 */
private object AutomationWatch {
    private var seenPackage = ""
    private var seenAt = 0L

    @Synchronized
    fun observe(packageName: String, nowMillis: Long): Long {
        if (packageName.isBlank()) return 0L
        val changed = packageName != seenPackage
        val previous = seenAt
        if (changed) {
            seenPackage = packageName
            seenAt = nowMillis
        }
        // A change is only dated by the last look, and the very first look dates nothing at all.
        return if (changed) previous else seenAt
    }
}

/**
 * Runs a firing rule's steps through the command layer the bubble already uses: each step is parsed by
 * CommandParser and executed with its own gates, so an automation cannot get ahead of a confirmation.
 * Turning the bubble off stops the remaining steps.
 */
private object AutomationHost {
    private val handler = Handler(Looper.getMainLooper())
    private var executor: CommandExecutor? = null

    fun run(context: Context, rule: AutomationRule) {
        if (!OverlayService.isRunning) {
            // The kill switch also covers an approval that arrives after the bubble went off.
            stopAll()
            return
        }
        stopAll()
        val app = context.applicationContext
        val engine = executor(app)
        val steps = rule.steps.mapNotNull { phrase -> CommandParser.parse(phrase)?.let { it to phrase } }
        steps.forEachIndexed { index, (command, phrase) ->
            handler.postDelayed({
                if (!OverlayService.isRunning) {
                    stopAll()
                    return@postDelayed
                }
                engine.execute(command, phrase)
            }, index * STEP_GAP_MS)
        }
        Log.i(TAG, "${rule.name} dispatched ${steps.size} steps")
    }

    fun stopAll() {
        handler.removeCallbacksAndMessages(null)
        executor?.stop()
        executor = null
    }

    private fun executor(context: Context): CommandExecutor =
        executor ?: CommandExecutor(context, PanelHost(context)).also { executor = it }
}

/**
 * Where an automation's result and confirmation cards go when no bubble panel raised them. Buttons come
 * back through the receiver, and a prompt that is dismissed or ignored approves nothing.
 */
private class PanelHost(private val context: Context) : OverlayUi {
    override fun showPanel(content: PanelContent) {
        val body = content.body.ifBlank { content.title }
        // One owner per panel, so its own buttons share a prompt slot instead of cancelling each other.
        val owner = "panel:" + content.title
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(content.title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setAutoCancel(true)
            .apply {
                content.actions.take(3).forEachIndexed { index, action ->
                    addAction(R.drawable.ic_mic, action.label, buttonIntent(owner, index, action))
                }
            }
            .build()
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        runCatching { manager.notify(PANEL_ID, notification) }
    }

    override fun closePanel() {
        runCatching { NotificationManagerCompat.from(context).cancel(PANEL_ID) }
    }

    private fun buttonIntent(owner: String, index: Int, action: PanelAction): PendingIntent {
        val token = AutomationPrompts.hold(owner, index.toString(), action.onClick)
        return PendingIntent.getBroadcast(
            context,
            token,
            Intent(context, AutomationReceiver::class.java)
                .setAction(AutomationReceiver.ACTION_APPROVE)
                .putExtra(AutomationReceiver.EXTRA_RULE, owner)
                .putExtra(AutomationReceiver.EXTRA_KEY, index.toString())
                .putExtra(AutomationReceiver.EXTRA_TOKEN, token),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
