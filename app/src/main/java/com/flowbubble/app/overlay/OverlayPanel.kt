package com.flowbubble.app.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class OverlayPanel(
    private val context: Context,
    private val windowManager: WindowManager
) {
    private var root: View? = null
    private var current: PanelContent? = null
    private var hidden: PanelContent? = null
    private val handler = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { close() }

    private val density = context.resources.displayMetrics.density
    private fun dp(value: Float) = (value * density).toInt()

    val isShowing: Boolean get() = root != null

    fun show(content: PanelContent) {
        remove()
        current = content
        hidden = null
        val card = build(content)
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), 0, dp(16f), 0)
            addView(card)
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
            y = dp(96f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val added = runCatching { windowManager.addView(outer, params) }.isSuccess
        if (!added) return
        root = outer
        content.autoDismissMs?.let { handler.postDelayed(dismissRunnable, it) }
    }

    fun close() {
        current = null
        hidden = null
        remove()
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
        if (current == null && root == null) show(content)
    }

    private fun remove() {
        handler.removeCallbacks(dismissRunnable)
        root?.let { view -> runCatching { windowManager.removeView(view) } }
        root = null
    }

    private fun build(content: PanelContent): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(22f).toFloat()
                setColor(Color.parseColor("#F2121922"))
                setStroke(dp(1f), Color.parseColor("#335EEAD4"))
            }
            setPadding(dp(18f), dp(14f), dp(18f), dp(14f))
            elevation = dp(12f).toFloat()
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(context).apply {
                text = content.title
                setTextColor(Color.parseColor("#F2F7FA"))
                textSize = 16.5f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        if (content.dismissible) {
            header.addView(
                TextView(context).apply {
                    text = "\u2715"
                    setTextColor(Color.parseColor("#8FA1B3"))
                    textSize = 16f
                    gravity = Gravity.CENTER
                    contentDescription = "Close"
                    isClickable = true
                    setPadding(dp(8f), dp(2f), dp(2f), dp(2f))
                    setOnClickListener { close() }
                }
            )
        }
        card.addView(header)

        if (content.body.isNotBlank()) {
            card.addView(
                TextView(context).apply {
                    text = content.body
                    setTextColor(Color.parseColor("#C7D3DE"))
                    textSize = 14f
                    setLineSpacing(dp(3f).toFloat(), 1f)
                    setPadding(0, dp(8f), 0, dp(2f))
                    if (content.scrollable) {
                        maxLines = 12
                        movementMethod = ScrollingMovementMethod()
                        isVerticalScrollBarEnabled = true
                        scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
                    }
                }
            )
        }

        content.actions.forEach { action ->
            card.addView(actionView(action))
        }
        if (content.copyText != null && content.actions.none { it.label.equals("Copy", true) }) {
            val copyText = content.copyText
            card.addView(
                actionView(
                    PanelAction("Copy", onClick = {
                        copy(copyText)
                    })
                )
            )
        }
        return card
    }

    private fun actionView(action: PanelAction): TextView {
        val primary = action.primary
        return TextView(context).apply {
            text = action.label
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(
                if (primary) Color.parseColor("#071311") else Color.parseColor("#DCE6EF")
            )
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(if (primary) Color.parseColor("#5EEAD4") else Color.parseColor("#1C2731"))
                if (!primary) setStroke(dp(1f), Color.parseColor("#2B3946"))
            }
            setPadding(dp(14f), dp(11f), dp(14f), dp(11f))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8f) }
            setOnClickListener { action.onClick() }
        }
    }

    private fun copy(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("FlowBubble", text))
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}
