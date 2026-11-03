package com.flowbubble.app.overlay

import android.animation.ObjectAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.compose.ui.graphics.toArgb
import com.flowbubble.app.ui.theme.Ink

/**
 * The card the assistant shows on top of whatever app is in front.
 *
 * Everything this class knows about a request arrives as a [PanelContent]; it only renders it. The
 * decisions that shape the render (tone, body style, button role, geometry) live in [PanelStyle]
 * beside it so they are testable without a device, and every colour is read from [Ink] so the
 * overlay, the bubble and the app cannot drift apart.
 *
 * Safety contract the service leans on, deliberately unchanged:
 *  - `dismissible = false` gets no close affordance of any kind;
 *  - `autoDismissMs` only ever *closes* the card. Closing is a cancellation: the timer never
 *    reaches an approve handler, so a confirmation cannot time out into a yes;
 *  - [hideForCapture] lifts the whole card out of the window tree so it can never be caught in a
 *    screen read, and [restoreAfterCapture] puts it back with only the time it had left;
 *  - the window keeps `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL` and short-edge cutout mode.
 */
class OverlayPanel(
    private val context: Context,
    private val windowManager: WindowManager
) {
    var onClosed: (() -> Unit)? = null

    private var root: View? = null
    private var current: PanelContent? = null
    private var hidden: PanelContent? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Single reusable callback: posting a fresh Runnable per panel would leak the previous one. */
    private val dismissRunnable = Runnable { close() }
    private var dismissDeadlineMs = 0L
    private var pulse: ObjectAnimator? = null

    private val density = context.resources.displayMetrics.density
    private fun dp(value: Float): Int = (value * density).toInt()
    private fun dpf(value: Float): Float = value * density
    private fun withAlpha(color: Int, percent: Int): Int = PanelStyle.withAlpha(color, percent)

    // Palette resolved once for the life of the panel, not per card.
    private val cVoid = Ink.Void.toArgb()
    private val cObsidian = Ink.Obsidian.toArgb()
    private val cOnyx = Ink.Onyx.toArgb()
    private val cSmoke = Ink.Smoke.toArgb()
    private val cLavender = Ink.Lavender.toArgb()
    private val cLavenderDeep = Ink.LavenderDeep.toArgb()
    private val cBurgundy = Ink.Burgundy.toArgb()
    private val cBurgundyDeep = Ink.BurgundyDeep.toArgb()
    private val cCrimson = Ink.Crimson.toArgb()
    private val cCrimsonSoft = Ink.CrimsonSoft.toArgb()
    private val cJade = Ink.Jade.toArgb()
    private val cAmber = Ink.Amber.toArgb()
    private val cMist = Ink.Mist.toArgb()
    private val cFaint = Ink.Faint.toArgb()
    private val cWhisper = Ink.Whisper.toArgb()

    private val faceRegular = Typeface.SANS_SERIF
    private val faceMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val faceBold = Typeface.create("sans-serif", Typeface.BOLD)

    private var outlineRadius = dpf(PanelStyle.CARD_RADIUS_DP)

    /** Explicit outline, so the elevation shadow keeps the card radius on every API level. */
    private val cardOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, outlineRadius)
        }
    }

    val isShowing: Boolean get() = root != null

    // ---------------------------------------------------------------------------------------------
    // Surface the service uses
    // ---------------------------------------------------------------------------------------------

    fun show(content: PanelContent) {
        attach(content, content.autoDismissMs ?: 0L)
    }

    fun close() {
        val wasShowing = root != null || current != null || hidden != null
        current = null
        hidden = null
        dismissDeadlineMs = 0L
        remove()
        if (wasShowing) onClosed?.invoke()
    }

    fun hideForCapture() {
        val content = current
        if (content != null && root != null) {
            hidden = content
            current = null
            remove()
        }
    }

    fun restoreAfterCapture() {
        val content = hidden ?: return
        hidden = null
        if (current != null || root != null) return
        val wasArmed = content.autoDismissMs != null && dismissDeadlineMs > 0L
        val remaining = if (wasArmed) {
            PanelStyle.remainingDismissMs(dismissDeadlineMs, SystemClock.uptimeMillis())
        } else 0L
        if (wasArmed && remaining <= 0L) {
            // The timer ran out while the card was hidden for the screen read. Let it stay closed
            // rather than handing the panel a second full lifetime; still only ever a dismissal.
            dismissDeadlineMs = 0L
            return
        }
        attach(content, remaining)
    }

    private fun attach(content: PanelContent, delayMs: Long) {
        remove()
        current = content
        hidden = null
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            val side = dp(PanelStyle.CARD_SIDE_PADDING_DP)
            setPadding(side, dp(14f), side, dp(ROOT_BOTTOM_PADDING_DP))
            addView(buildCard(content))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // Offset the bottom padding, so the card itself still sits where it always has.
            y = dp(PanelStyle.WINDOW_BOTTOM_OFFSET_DP) - dp(ROOT_BOTTOM_PADDING_DP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val added = runCatching { windowManager.addView(outer, params) }.isSuccess
        if (!added) {
            // Nothing was attached, so undo the work the build started (a running card pulses).
            current = null
            dismissDeadlineMs = 0L
            remove()
            return
        }
        root = outer
        if (PanelStyle.armsAutoDismiss(delayMs)) {
            dismissDeadlineMs = SystemClock.uptimeMillis() + delayMs
            handler.postDelayed(dismissRunnable, delayMs)
        } else {
            dismissDeadlineMs = 0L
        }
    }

    private fun remove() {
        handler.removeCallbacks(dismissRunnable)
        pulse?.cancel()
        pulse = null
        root?.let { view -> runCatching { windowManager.removeView(view) } }
        root = null
    }

    // ---------------------------------------------------------------------------------------------
    // Card
    // ---------------------------------------------------------------------------------------------

    private fun buildCard(content: PanelContent): View {
        val tone = PanelStyle.toneOf(content)
        return if (PanelStyle.shapeOf(content) == PanelShape.Pill) buildPill(content, tone)
        else buildSheet(content, tone)
    }

    private fun buildSheet(content: PanelContent, tone: PanelTone): FrameLayout {
        val accent = accentOf(tone)
        val radius = dpf(PanelStyle.CARD_RADIUS_DP)
        outlineRadius = radius
        val card = FrameLayout(context).apply {
            background = cardShell(radius, accent)
            elevation = dpf(18f)
            outlineProvider = cardOutline
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                outlineSpotShadowColor = withAlpha(accent, 40)
                outlineAmbientShadowColor = withAlpha(cLavenderDeep, 26)
            }
        }

        // Depth in layers: a sheen beneath the content, a luminous hairline along the top edge.
        card.addView(
            View(context).apply {
                background = verticalGradient(
                    intArrayOf(withAlpha(cLavender, 10), withAlpha(cLavender, 4), 0x00000000),
                    floatArrayOf(0f, 0.45f, 1f)
                )
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(88f))
        )
        card.addView(topEdge(accent, tone))

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22f), dp(19f), dp(22f), dp(20f))
        }
        column.addView(sheetHeader(content, tone, accent))
        val blocks = PanelStyle.blocks(content.body, content.scrollable)
        if (blocks.isNotEmpty()) column.addView(bodyColumn(blocks, content, accent))
        actionGroup(content, tone)?.let { column.addView(it) }
        if (PanelStyle.showHandsFreeHint(content.voicePrompt != null, content.onVoiceApprove != null)) {
            column.addView(hintLine(accent))
        }
        card.addView(
            column,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        return card
    }

    /** Transient acknowledgement: one line, a tone dot, no chrome, erases itself. */
    private fun buildPill(content: PanelContent, tone: PanelTone): LinearLayout {
        val accent = accentOf(tone)
        val radius = dpf(PanelStyle.PILL_RADIUS_DP)
        outlineRadius = radius
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardShell(radius, accent)
            elevation = dpf(12f)
            outlineProvider = cardOutline
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                outlineSpotShadowColor = withAlpha(accent, 34)
                outlineAmbientShadowColor = withAlpha(cLavenderDeep, 20)
            }
            setPadding(dp(18f), dp(12f), dp(20f), dp(12f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(dot(withAlpha(accent, 92), dp(7f)), rowParams(dp(7f), dp(7f), marginEnd = dp(12f)))
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(
            label(
                text = content.title,
                sizeSp = 15.5f,
                color = cMist,
                face = faceMedium,
                tracking = 0.05f,
                maxLines = PanelStyle.titleMaxLines(PanelShape.Pill)
            ).apply {
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(300f)
            }
        )
        if (content.body.isNotBlank()) {
            column.addView(
                label(
                    text = PanelStyle.clampBody(content.body, PanelStyle.bodyCharBudget(false)),
                    sizeSp = 13f,
                    color = cFaint,
                    face = faceRegular,
                    tracking = 0.02f,
                    maxLines = 2
                ).apply {
                    ellipsize = TextUtils.TruncateAt.END
                    maxWidth = dp(300f)
                    setLineSpacing(0f, 1.25f)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(3f) }
                }
            )
        }
        row.addView(
            column,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        return row
    }

    /** Eyebrow states the purpose, the title says what, a tone rail runs down the left. */
    private fun sheetHeader(content: PanelContent, tone: PanelTone, accent: Int): View {
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titles = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = withRail(null, withAlpha(accent, 96))
            setPadding(dp(18f), 0, dp(2f), 0)
        }
        val eyebrow = PanelStyle.eyebrow(tone)
        if (eyebrow.isNotEmpty()) {
            titles.addView(
                label(
                    text = eyebrow.uppercase(),
                    sizeSp = 11f,
                    color = withAlpha(accent, 92),
                    face = faceBold,
                    tracking = 0.08f
                ).apply { includeFontPadding = false }
            )
        }
        titles.addView(
            label(
                text = content.title,
                sizeSp = 19f,
                color = cMist,
                face = faceBold,
                tracking = 0.01f,
                maxLines = PanelStyle.titleMaxLines(PanelShape.Sheet)
            ).apply {
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                if (eyebrow.isNotEmpty()) layoutParams = below(dp(3f))
            }
        )
        head.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (content.dismissible) {
            head.addView(quietClose(), rowParams(dp(44f), dp(44f), marginStart = dp(8f)))
        }
        return head
    }

    private fun quietClose(): TextView = TextView(context).apply {
        text = "\u2715"
        textSize = 17f
        typeface = faceMedium
        gravity = Gravity.CENTER
        includeFontPadding = false
        contentDescription = "Close"
        isClickable = true
        isFocusable = false
        setTextColor(stateColor(cFaint, cMist))
        background = buttonBackground(ActionRole.Quiet, dpf(PanelStyle.BUTTON_RADIUS_DP))
        setOnClickListener { close() }
    }

    // ---------------------------------------------------------------------------------------------
    // Body
    // ---------------------------------------------------------------------------------------------

    private fun bodyColumn(
        blocks: List<PanelBodyBlock>,
        content: PanelContent,
        accent: Int
    ): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = below(dp(14f))
        }
        val listBody = blocks.count { it.kind == PanelBodyKind.Status } > 1
        blocks.forEach { block ->
            val view = when {
                PanelStyle.isCaption(block, blocks.size) -> caption(block.text)
                block.kind == PanelBodyKind.Status && !listBody -> status(block.text)
                block.kind == PanelBodyKind.Status -> railParagraph(block.text, withAlpha(accent, 46), false)
                block.kind == PanelBodyKind.Quote -> railParagraph(block.text, withAlpha(cLavender, 72), true)
                else -> surfaceView(block, content.scrollable, accent)
            }
            column.addView(view)
        }
        return column
    }

    private fun status(text: String): TextView =
        label(
            text = PanelStyle.clampBody(text, PanelStyle.bodyCharBudget(false)),
            sizeSp = 15f,
            color = withAlpha(cMist, 92),
            face = faceRegular,
            tracking = 0.03f
        ).apply {
            setLineSpacing(0f, 1.3f)
            layoutParams = fullBelow(dp(6f))
        }

    private fun caption(text: String): TextView =
        label(
            text = PanelStyle.clampBody(text, PanelStyle.bodyCharBudget(false)),
            sizeSp = 13.5f,
            color = cFaint,
            face = faceRegular,
            tracking = 0.06f
        ).apply {
            setLineSpacing(0f, 1.28f)
            layoutParams = fullBelow(dp(12f))
        }

    /**
     * A paragraph with a tone rail down its left: quoted screen text, or one item of a short list.
     * The rail is a background layer instead of a child view, so it spans whatever height the text
     * reaches without a second measure pass.
     */
    private fun railParagraph(text: String, railColor: Int, quote: Boolean): TextView {
        val radius = dpf(PanelStyle.SURFACE_RADIUS_DP)
        return label(
            text = PanelStyle.clampBody(text, PanelStyle.bodyCharBudget(quote)),
            sizeSp = if (quote) 14f else 14.5f,
            color = withAlpha(cMist, if (quote) 88 else 92),
            face = faceRegular,
            tracking = if (quote) 0.08f else 0.03f
        ).apply {
            setLineSpacing(0f, if (quote) 1.36f else 1.28f)
            background = withRail(
                if (quote) surfaceBackground(radius, withAlpha(cLavender, 20)) else null,
                railColor
            )
            setPadding(dp(15f), dp(11f), dp(10f), dp(11f))
            layoutParams = fullBelow(if (quote) dp(10f) else dp(9f))
        }
    }

    /** Traces and screen text get their own inset surface, and scroll inside it when asked to. */
    private fun surfaceView(block: PanelBodyBlock, scrollable: Boolean, accent: Int): View {
        val radius = dpf(PanelStyle.SURFACE_RADIUS_DP)
        val surface = FrameLayout(context).apply {
            background = withRail(
                surfaceBackground(radius, withAlpha(cLavender, 22)),
                withAlpha(accent, 70)
            )
            setPadding(dp(19f), dp(13f), dp(12f), dp(13f))
        }
        val trace = block.kind == PanelBodyKind.Trace
        val body = label(
            text = if (trace) traceText(block.text, scrollable)
            else PanelStyle.clampBody(block.text, PanelStyle.bodyCharBudget(scrollable)),
            sizeSp = if (trace) 13.5f else 14f,
            color = withAlpha(cMist, 90),
            face = faceRegular,
            tracking = 0.04f
        ).apply {
            setLineSpacing(0f, if (trace) 1.36f else 1.38f)
        }
        val maxHeight = PanelStyle.bodyMaxHeightDp(block.kind, scrollable)
        if (scrollable && maxHeight > 0f) {
            val fade = View(context).apply {
                background = verticalGradient(
                    intArrayOf(0x00000000, withAlpha(cOnyx, 150), withAlpha(cVoid, 235))
                )
                visibility = View.GONE
            }
            val scroll = MaxHeightScrollView(context, dp(maxHeight)) { overflowing ->
                fade.visibility = if (overflowing) View.VISIBLE else View.GONE
            }
            scroll.addView(body)
            val stack = FrameLayout(context).apply {
                addView(
                    scroll,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                addView(
                    fade,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        dp(24f),
                        Gravity.BOTTOM
                    )
                )
            }
            surface.addView(
                stack,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        } else {
            surface.addView(body)
        }

        val shell = FrameLayout(context)
        shell.addView(
            surface,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(14f) }
        )
        shell.layoutParams = fullBelow(dp(12f))
        return shell
    }

    /**
     * A trace is a sequence, not a paragraph: step numbers lavender and bold, results mist, and the
     * verification line under each one jade when the system confirmed it, amber when it did not.
     */
    private fun traceText(body: String, scrollable: Boolean): CharSequence {
        val source = PanelStyle.clampBody(body, PanelStyle.bodyCharBudget(scrollable))
        val out = android.text.SpannableStringBuilder()
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        source.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) {
                out.append("\n")
                return@forEach
            }
            val start = out.length
            out.append(trimmed)
            val end = out.length
            val confirmed = trimmed.startsWith("confirmed")
            val unverified = trimmed.startsWith("not confirmed")
            val color = when {
                confirmed -> withAlpha(cJade, 92)
                unverified -> withAlpha(cAmber, 92)
                else -> withAlpha(cMist, 92)
            }
            out.setSpan(ForegroundColorSpan(color), start, end, flags)
            if (confirmed || unverified) {
                out.setSpan(RelativeSizeSpan(0.93f), start, end, flags)
            } else {
                val lead = STEP_LEADING.find(trimmed)?.value?.length ?: 0
                if (lead > 0) {
                    out.setSpan(ForegroundColorSpan(withAlpha(cLavender, 96)), start, start + lead, flags)
                    out.setSpan(StyleSpan(Typeface.BOLD), start, start + lead, flags)
                }
            }
            out.append("\n")
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------------------------

    private fun actionGroup(content: PanelContent, tone: PanelTone): View? {
        val copyText = content.copyText
        val labels = content.actions.map { it.label }
        val appendCopy = PanelStyle.shouldAppendCopy(copyText, labels)
        if (content.actions.isEmpty() && !appendCopy) return null

        val actions = if (appendCopy && copyText != null) {
            content.actions + PanelAction("Copy") { copy(copyText) }
        } else {
            content.actions
        }
        val roles = actions.mapIndexed { index, action ->
            // The fallback Copy is a convenience, never a competitor with the real choices.
            if (appendCopy && index == actions.lastIndex) ActionRole.Quiet
            else PanelStyle.roleOf(
                label = action.label,
                primary = action.primary,
                tone = tone,
                title = content.title,
                body = content.body,
                actionCount = actions.size
            )
        }
        val layout = PanelStyle.layoutOf(actions.map { it.label })
        val row = layout == ActionLayout.Row
        val container = LinearLayout(context).apply {
            orientation = if (row) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        container.layoutParams = fullBelow(dp(if (row) 18f else 16f))

        if (row) {
            val weights = PanelStyle.weightsFor(roles)
            val order = PanelStyle.orderedFor(roles, tone)
            order.forEachIndexed { slot, index ->
                val params = LinearLayout.LayoutParams(0, dp(PanelStyle.BUTTON_HEIGHT_DP), weights[index])
                if (slot != order.lastIndex) params.marginEnd = dp(10f)
                container.addView(actionButton(actions[index], roles[index], layout), params)
            }
        } else {
            actions.forEachIndexed { index, action ->
                val role = roles[index]
                // A stacked button keeps its own height. An exact layout height measures the label
                // with EXACTLY and clips whatever it needs to wrap to — an "Open Your Bubble setup"
                // label simply disappears. minimumHeight on the button still guarantees the touch target.
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                if (index > 0) {
                    params.topMargin = dp(if (role == ActionRole.Quiet) 4f else 9f)
                }
                container.addView(actionButton(action, role, layout), params)
            }
        }
        return container
    }

    private fun actionButton(action: PanelAction, role: ActionRole, layout: ActionLayout): TextView =
        TextView(context).apply {
            text = PanelStyle.buttonLabel(action.label, layout)
            textSize = if (layout == ActionLayout.Row) 13.5f else 14.5f
            typeface = if (role == ActionRole.Quiet) faceMedium else faceBold
            letterSpacing = if (layout == ActionLayout.Row) 0.09f else 0.05f
            gravity = Gravity.CENTER
            includeFontPadding = false
            minimumHeight = dp(PanelStyle.MIN_TOUCH_DP)
            isClickable = true
            isFocusable = false
            contentDescription = action.label
            background = buttonBackground(role, dpf(PanelStyle.BUTTON_RADIUS_DP))
            applyTextColor(this, role)
            val horizontal = dp(if (role == ActionRole.Quiet) 12f else 18f)
            setPadding(horizontal, dp(11f), horizontal, dp(11f))
            setOnClickListener { action.onClick() }
        }

    /** Quiet buttons lift on press; the filled roles keep a single steady ink. */
    private fun applyTextColor(button: TextView, role: ActionRole) {
        when (role) {
            ActionRole.Quiet -> button.setTextColor(stateColor(cFaint, cMist))
            ActionRole.Primary -> button.setTextColor(withAlpha(cVoid, 97))
            ActionRole.Outbound -> button.setTextColor(withAlpha(cMist, 97))
            ActionRole.Secondary -> button.setTextColor(withAlpha(cLavender, 96))
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Paint
    // ---------------------------------------------------------------------------------------------

    private fun cardShell(radius: Float, accent: Int): LayerDrawable {
        val base = rounded(radius).apply {
            setOrientation(GradientDrawable.Orientation.TOP_BOTTOM)
            setColors(intArrayOf(withAlpha(cSmoke, 94), withAlpha(cObsidian, 95), withAlpha(cVoid, 97)))
        }
        val innerGlow = rounded(radius).apply {
            setColor(0x00000000)
            setStroke(dp(1f), withAlpha(accent, 14))
        }
        val hairline = rounded(radius).apply {
            setColor(0x00000000)
            setStroke(dp(1f), withAlpha(cLavender, 36))
        }
        return LayerDrawable(arrayOf(base, innerGlow, hairline))
    }

    private fun surfaceBackground(radius: Float, hairline: Int): LayerDrawable {
        val fill = rounded(radius).apply {
            setOrientation(GradientDrawable.Orientation.TOP_BOTTOM)
            setColors(intArrayOf(withAlpha(cOnyx, 80), withAlpha(cVoid, 64)))
        }
        val stroke = rounded(radius).apply {
            setColor(0x00000000)
            setStroke(dp(1f), hairline)
        }
        return LayerDrawable(arrayOf(fill, stroke))
    }

    private fun railDrawable(color: Int): Drawable = rounded(dpf(2f)).apply {
        setOrientation(GradientDrawable.Orientation.TOP_BOTTOM)
        setColors(intArrayOf(color, withAlpha(color, 26), 0x00000000))
    }

    /**
     * Lays a tone rail on the left edge of an existing background. Doing it as a layer rather than
     * a child view means the rail spans whatever height the paragraph reaches, and it costs one
     * drawable instead of a measure pass.
     */
    private fun withRail(under: Drawable?, railColor: Int): LayerDrawable {
        val shell = LayerDrawable(arrayOf(under ?: ColorDrawable(0x00000000), railDrawable(railColor)))
        shell.setLayerWidth(1, dp(3f))
        shell.setLayerInset(1, dp(1f), dp(4f), 0, dp(4f))
        return shell
    }

    /** The thin luminous edge: steady on a finished card, breathing while a task is still running. */
    private fun topEdge(accent: Int, tone: PanelTone): View {
        val params = FrameLayout.LayoutParams(dp(112f), dp(2f), Gravity.TOP or Gravity.START).apply {
            marginStart = dp(26f)
            topMargin = dp(1f)
        }
        val edge = View(context).apply {
            background = rounded(dpf(2f)).apply {
                setOrientation(GradientDrawable.Orientation.LEFT_RIGHT)
                setColors(
                    intArrayOf(0x00000000, withAlpha(accent, 215), withAlpha(cLavenderDeep, 130), 0x00000000)
                )
            }
            layoutParams = params
        }
        if (tone == PanelTone.Working) startPulse(edge)
        return edge
    }

    private fun startPulse(edge: View) {
        val animator = ObjectAnimator.ofFloat(edge, View.ALPHA, 0.42f, 1f).apply {
            duration = 1150L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }
        pulse = animator
        animator.start()
    }

    private fun dot(color: Int, size: Int): View = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        minimumWidth = size
        minimumHeight = size
    }

    private fun buttonBackground(role: ActionRole, radius: Float): Drawable {
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), buttonFill(role, radius, true))
            addState(intArrayOf(android.R.attr.state_focused), buttonFill(role, radius, true))
            addState(intArrayOf(), buttonFill(role, radius, false))
        }
        val mask = rounded(radius).apply { setColor(0xFF000000.toInt()) }
        val ripple = when (role) {
            ActionRole.Primary -> withAlpha(cLavenderDeep, 60)
            ActionRole.Outbound -> withAlpha(cCrimson, 55)
            else -> withAlpha(cLavender, 26)
        }
        // RippleDrawable(color, CONTENT, mask). Passing the plate as the content and the state list
        // as the mask paints an opaque black square behind every label and throws the fill away, so
        // the primary button — black ink on purpose — becomes black on black.
        return RippleDrawable(ColorStateList.valueOf(ripple), states, mask)
    }

    private fun buttonFill(role: ActionRole, radius: Float, pressed: Boolean): LayerDrawable =
        LayerDrawable(
            when (role) {
                ActionRole.Primary -> arrayOf(
                    gradientFill(
                        radius,
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(if (pressed) cMist else cLavender, if (pressed) cLavender else cLavenderDeep)
                    ),
                    stroked(radius, withAlpha(cLavender, 78))
                )
                ActionRole.Outbound -> arrayOf(
                    gradientFill(
                        radius,
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(
                            if (pressed) cCrimsonSoft else cCrimson,
                            if (pressed) cBurgundy else cBurgundyDeep
                        )
                    ),
                    stroked(radius, withAlpha(cCrimsonSoft, 66))
                )
                ActionRole.Secondary -> arrayOf(
                    gradientFill(
                        radius,
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(
                            withAlpha(cSmoke, if (pressed) 96 else 72),
                            withAlpha(cOnyx, if (pressed) 92 else 68)
                        )
                    ),
                    stroked(radius, withAlpha(cLavender, if (pressed) 86 else 46))
                )
                ActionRole.Quiet -> arrayOf(
                    flat(radius, if (pressed) withAlpha(cLavender, 24) else 0x00000000),
                    stroked(radius, if (pressed) withAlpha(cLavender, 28) else 0x00000000)
                )
            }
        )

    private fun gradientFill(
        radius: Float,
        orientation: GradientDrawable.Orientation,
        colors: IntArray
    ): Drawable = rounded(radius).apply {
        setOrientation(orientation)
        setColors(colors)
    }

    private fun stroked(radius: Float, color: Int): Drawable = rounded(radius).apply {
        setColor(0x00000000)
        setStroke(dp(1f), color)
    }

    private fun flat(radius: Float, color: Int): Drawable = rounded(radius).apply { setColor(color) }

    private fun rounded(radius: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
    }

    private fun verticalGradient(colors: IntArray): Drawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors)

    private fun verticalGradient(colors: IntArray, stops: FloatArray): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setOrientation(GradientDrawable.Orientation.TOP_BOTTOM)
        setColors(colors, stops)
    }

    private fun stateColor(normal: Int, active: Int): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_pressed),
            intArrayOf(android.R.attr.state_focused),
            intArrayOf()
        ),
        intArrayOf(active, active, normal)
    )

    private fun hintLine(accent: Int): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = fullBelow(dp(15f))
        }
        row.addView(dot(withAlpha(accent, 80), dp(6f)), rowParams(dp(6f), dp(6f), marginEnd = dp(10f)))
        row.addView(
            label(
                text = PanelStyle.voiceHintText(),
                sizeSp = 12f,
                color = withAlpha(cWhisper, 96),
                face = faceMedium,
                tracking = 0.06f
            ),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        return row
    }

    private fun accentOf(tone: PanelTone): Int = when (tone) {
        PanelTone.Await -> cLavender
        PanelTone.Working -> cLavenderDeep
        PanelTone.Verified -> cJade
        PanelTone.Caution -> cAmber
        PanelTone.Alert -> cCrimson
        PanelTone.Info -> cLavender
    }

    private fun label(
        text: CharSequence,
        sizeSp: Float,
        color: Int,
        face: Typeface,
        tracking: Float,
        maxLines: Int = Int.MAX_VALUE
    ): TextView = TextView(context).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        typeface = face
        letterSpacing = tracking
        this.maxLines = maxLines
    }

    private fun rowParams(
        width: Int,
        height: Int,
        marginStart: Int = 0,
        marginEnd: Int = 0,
        childGravity: Int = Gravity.CENTER_VERTICAL
    ): LinearLayout.LayoutParams = LinearLayout.LayoutParams(width, height).apply {
        this.marginStart = marginStart
        this.marginEnd = marginEnd
        gravity = childGravity
    }

    private fun below(marginPx: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = marginPx }

    private fun fullBelow(marginPx: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = marginPx }

    // ---------------------------------------------------------------------------------------------

    private fun copy(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Your Bubble", text))
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    /**
     * A scroll view that caps its own height, so a long trace scrolls inside the card instead of
     * pushing the card off screen. Reports overflow once per measure pass, which is what turns the
     * "there is more below" fade on.
     */
    private class MaxHeightScrollView(
        context: Context,
        private val maxHeightPx: Int,
        private val onOverflow: (Boolean) -> Unit
    ) : ScrollView(context) {
        init {
            isVerticalScrollBarEnabled = true
            overScrollMode = OVER_SCROLL_NEVER
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val capped = mode != MeasureSpec.EXACTLY || size > maxHeightPx
            super.onMeasure(
                widthMeasureSpec,
                if (capped) MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST) else heightMeasureSpec
            )
            val child = getChildAt(0)
            onOverflow(child != null && child.measuredHeight > measuredHeight)
        }
    }

    companion object {
        private const val ROOT_BOTTOM_PADDING_DP = 18f
        private val STEP_LEADING = Regex("^([0-9]+[.)\\-]?|Step [0-9]+|[-\\u2022\\u25AA])\\s+")
    }
}
