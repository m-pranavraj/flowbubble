package com.flowbubble.app.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.flowbubble.app.vision.ScreenBox
import com.flowbubble.app.vision.ScreenNode

sealed class TextInsertResult {
    data class Inserted(val previousText: String, val newText: String) : TextInsertResult()
    data class Copied(val message: String) : TextInsertResult()
    data class Failed(val message: String) : TextInsertResult()
}

sealed class FieldActionOutcome {
    data class Done(val note: String) : FieldActionOutcome()
    data class Blocked(val message: String) : FieldActionOutcome()
}

data class SelectionInfo(val fullText: String, val start: Int, val end: Int) {
    val selectedText: String
        get() = if (start in 0..end && end <= fullText.length) fullText.substring(start, end) else ""

    val hasSelection: Boolean get() = selectedText.isNotBlank()
}

data class TapResult(val success: Boolean, val label: String? = null, val note: String? = null)

class FlowAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: FlowAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null

        private val overflowWords = listOf("more options", "overflow", "options menu", "menu", "options")
        private val closeWords = listOf(
            "close", "cancel", "dismiss", "not now", "no thanks", "later", "skip", "back", "got it"
        )
        private val guardedWords = setOf(
            "ok", "okay", "allow", "accept", "agree", "continue", "confirm", "send",
            "delete", "remove", "buy", "pay", "install", "update", "submit", "subscribe",
            "purchase", "sign", "log", "tip"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // Our own overlay animates constantly; letting it count would stop every wait from settling.
        if (event.packageName == packageName) return
        ScreenPulse.beat()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun focusedNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
    }

    fun isPasswordFocused(): Boolean {
        var node = focusedNode()
        var depth = 0
        while (node != null && depth < 6) {
            if (node.isPassword) return true
            node = node.parent
            depth++
        }
        return false
    }

    fun focusedEditable(): AccessibilityNodeInfo? {
        val focus = focusedNode() ?: return null
        var node: AccessibilityNodeInfo? = focus
        var depth = 0
        while (node != null && depth < 8) {
            if (node.isEditable && !node.isPassword) return node
            node = node.parent
            depth++
        }
        return null
    }

    fun captureSelection(): SelectionInfo? {
        val node = focusedEditable() ?: return null
        val text = node.text?.toString() ?: return null
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        if (start < 0 || end < 0 || start > text.length || end > text.length) return null
        return SelectionInfo(text, start, end)
    }

    fun currentFieldText(): String? = focusedEditable()?.text?.toString()

    fun focusedFieldLabel(): String? {
        val node = focusedEditable() ?: return null
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.trim()
        } else {
            null
        }
        if (!hint.isNullOrBlank()) return hint
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrBlank()) return desc
        return nearestLabel(node)
    }

    /**
     * Replaces the focused field's whole value. Used when the user asks to search or type a fresh query.
     */
    fun setFocusedFieldText(text: String): TextInsertResult {
        if (isPasswordFocused()) {
            return TextInsertResult.Failed("This looks like a password field. FlowBubble won't type here.")
        }
        val node = focusedEditable()
            ?: return TextInsertResult.Failed("No text field is focused.")
        val old = node.text?.toString().orEmpty()
        return applyText(node, text, old)
    }

    /**
     * Inserts at the caret, replacing the active selection if there is one.
     */
    fun insertText(text: String): TextInsertResult {
        if (isPasswordFocused()) {
            return TextInsertResult.Failed("This looks like a password field. FlowBubble won't type here.")
        }
        val node = focusedEditable()
            ?: return TextInsertResult.Failed("No text field is focused. Tap a text field first, then hold the bubble.")

        val old = node.text?.toString().orEmpty()
        val rawStart = node.textSelectionStart
        val rawEnd = node.textSelectionEnd
        val start = if (rawStart in 0..old.length) rawStart else old.length
        val end = if (rawEnd in start..old.length) rawEnd else start
        val newText = old.substring(0, start) + text + old.substring(end)

        return applyText(node, newText, old)
    }

    /**
     * Replaces a captured range, but only if the field still holds the expected text.
     */
    fun replaceRange(expectedFull: String, start: Int, end: Int, replacement: String): TextInsertResult {
        if (isPasswordFocused()) {
            return TextInsertResult.Failed("This looks like a password field. FlowBubble won't edit it.")
        }
        val node = focusedEditable()
            ?: return TextInsertResult.Failed("The text field is no longer focused.")
        val current = node.text?.toString().orEmpty()
        if (current != expectedFull) {
            copyToClipboard(replacement)
            return TextInsertResult.Copied(
                "The field changed since you selected the text, so nothing was overwritten. The result is copied to your clipboard."
            )
        }
        if (start < 0 || end < start || end > current.length) {
            copyToClipboard(replacement)
            return TextInsertResult.Copied("Couldn't locate the selection, so the result is copied to your clipboard.")
        }
        val newText = current.substring(0, start) + replacement + current.substring(end)
        return applyText(node, newText, current)
    }

    /**
     * Rewrites the focused field only while it still holds the text the user
     * asked to rewrite. Otherwise the result goes to the clipboard.
     */
    fun replaceFocusedFieldIf(expected: String, replacement: String): TextInsertResult {
        if (isPasswordFocused()) {
            return TextInsertResult.Failed("This looks like a password field. FlowBubble won't edit it.")
        }
        val node = focusedEditable()
            ?: return TextInsertResult.Failed("The text field is no longer focused.")
        val current = node.text?.toString().orEmpty()
        if (current != expected) {
            copyToClipboard(replacement)
            return TextInsertResult.Copied(
                "The field changed since that text, so nothing was overwritten. The result is copied to your clipboard."
            )
        }
        return applyText(node, replacement, current)
    }

    fun hasVisibleText(target: String): Boolean {
        val needle = target.trim()
        if (needle.isEmpty()) return false
        val root = rootInActiveWindow ?: return false
        val nodes = runCatching { root.findAccessibilityNodeInfosByText(needle) }.getOrNull()
        if (nodes?.any { it.isVisibleToUser } == true) return true
        return screenText(4000).contains(needle, ignoreCase = true)
    }

    fun undoInsert(previousText: String, expectedNewText: String): Boolean {
        val node = focusedEditable() ?: return false
        if (node.text?.toString().orEmpty() != expectedNewText) return false
        return node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    previousText,
                )
            }
        )
    }

    private fun applyText(node: AccessibilityNodeInfo, newText: String, oldText: String): TextInsertResult {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        val set = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (set) return TextInsertResult.Inserted(oldText, newText)

        copyToClipboard(newText)
        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        return if (pasted) {
            TextInsertResult.Inserted(oldText, newText)
        } else {
            TextInsertResult.Copied(
                "This field didn't accept direct text. The text is copied to your clipboard so you can paste it."
            )
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("FlowBubble", text))
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    fun tapByText(target: String): TapResult {
        val root = rootInActiveWindow ?: return TapResult(false, note = "I can't see the current screen yet.")
        val needle = target.trim().lowercase()
        if (needle.isEmpty()) return TapResult(false, note = "Nothing to tap.")

        val matches = labeledMatches(root, target)
        if (matches.isEmpty()) return TapResult(false, note = "I couldn't find \"$target\" on this screen.")
        val chosen = pickMatch(matches, needle) ?: return TapResult(false, note = "I couldn't find \"$target\" on this screen.")

        val clicked = clickNodeOrAncestor(chosen.value)
        val label = labelOf(chosen.value) ?: chosen.key
        return if (clicked) {
            TapResult(true, label, if (matches.size > 1) "Matched ${matches.size} items." else null)
        } else {
            TapResult(false, label, "\"$label\" is visible but isn't tappable.")
        }
    }

    /**
     * Holds a control down long enough for the app to open its context menu.
     * Uses the node action when the app exposes one, otherwise a real gesture.
     */
    fun longPressByText(target: String, holdMs: Int = 650): TapResult {
        val root = rootInActiveWindow ?: return TapResult(false, note = "I can't see the current screen yet.")
        val needle = target.trim().lowercase()
        if (needle.isEmpty()) return TapResult(false, note = "Nothing to long-press.")

        val matches = labeledMatches(root, target)
        val chosen = pickMatch(matches, needle)
            ?: return TapResult(false, note = "I couldn't find \"$target\" on this screen.")
        val node = chosen.value
        if (node.isPassword) return TapResult(false, chosen.key, "That's a password field, so I left it alone.")
        val label = labelOf(node) ?: chosen.key

        val held = longClickNodeOrAncestor(node) || run {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            bounds.width() > 0 && bounds.height() > 0 &&
                longPressAt(bounds.centerX(), bounds.centerY(), holdMs)
        }
        return if (held) {
            TapResult(true, label, if (matches.size > 1) "Matched ${matches.size} items." else null)
        } else {
            TapResult(false, label, "\"$label\" didn't accept a long-press.")
        }
    }

    private fun labeledMatches(
        root: AccessibilityNodeInfo,
        target: String
    ): LinkedHashMap<String, AccessibilityNodeInfo> {
        val needle = target.trim().lowercase()
        val matches = LinkedHashMap<String, AccessibilityNodeInfo>()
        if (needle.isEmpty()) return matches
        root.findAccessibilityNodeInfosByText(target)?.forEach { node ->
            if (node.isVisibleToUser) {
                val label = labelOf(node) ?: nearestLabel(node)
                if (label != null) matches.putIfAbsent(label.lowercase(), node)
            }
        }
        if (matches.isEmpty()) collectMatches(root, needle, matches, 0)
        return matches
    }

    private fun pickMatch(
        matches: LinkedHashMap<String, AccessibilityNodeInfo>,
        needle: String
    ): Map.Entry<String, AccessibilityNodeInfo>? =
        matches.entries.firstOrNull { it.key == needle } ?: matches.entries.minByOrNull { it.key.length }

    fun tapOrdinal(ordinal: Int): TapResult {
        val root = rootInActiveWindow ?: return TapResult(false, note = "I can't see the current screen yet.")
        val clickable = ArrayList<Pair<AccessibilityNodeInfo, String>>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 800) {
            val node = queue.removeFirst()
            visited++
            if (node.isVisibleToUser && node.isClickable) {
                val label = labelOf(node)
                if (!label.isNullOrBlank()) clickable.add(node to label)
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        val ordered = clickable
            .map { (node, label) ->
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                Triple(node, label, bounds)
            }
            .filter { it.third.width() > 0 && it.third.height() > 0 }
            .sortedWith(compareBy({ it.third.top }, { it.third.left }))

        val pick = ordered.getOrNull(ordinal - 1)
            ?: return TapResult(false, note = "There aren't $ordinal tappable options on this screen.")
        val clicked = pick.first.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return TapResult(clicked, pick.second, if (clicked) null else "That option didn't accept a tap.")
    }

    private fun collectMatches(
        node: AccessibilityNodeInfo,
        needle: String,
        out: MutableMap<String, AccessibilityNodeInfo>,
        depth: Int,
    ) {
        if (depth > 40 || out.size >= 30) return
        val label = labelOf(node)
        if (!label.isNullOrBlank() && node.isVisibleToUser && label.lowercase().contains(needle)) {
            out.putIfAbsent(label.lowercase(), node)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectMatches(it, needle, out, depth + 1) }
        }
    }

    private fun labelOf(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) return text
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrEmpty()) return desc
        return null
    }

    private fun nearestLabel(node: AccessibilityNodeInfo): String? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 4) {
            val label = labelOf(current)
            if (!label.isNullOrBlank()) return label
            current = current.parent
            depth++
        }
        return null
    }

    private fun clickNodeOrAncestor(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 8) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            parent = parent.parent
            depth++
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun longClickNodeOrAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 8) {
            if (current.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true
            current = current.parent
            depth++
        }
        return false
    }

    fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow
        if (root != null) {
            val scrollable = findScrollable(root, 0)
            if (scrollable != null) {
                val action = if (forward) {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }
                if (scrollable.performAction(action)) return true
            }
        }
        return swipe(forward)
    }

    private fun findScrollable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 30) return null
        if (node.isVisibleToUser && node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { findScrollable(it, depth + 1) }
            if (found != null) return found
        }
        return null
    }

    private fun swipe(forward: Boolean): Boolean {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val startY = if (forward) metrics.heightPixels * 0.72f else metrics.heightPixels * 0.32f
        val endY = if (forward) metrics.heightPixels * 0.32f else metrics.heightPixels * 0.72f
        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 320))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun pressImeEnter(): Boolean {
        val node = focusedNode() ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                return true
            }
        }
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable &&
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                return true
            }
            current = current.parent
            depth++
        }
        return false
    }

    fun mediaPlayPause(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_KEYCODE_HEADSETHOOK)
        } else {
            false
        }

    fun takeScreenshot(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        } else {
            false
        }

    fun tapAt(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun longPressAt(x: Int, y: Int, holdMs: Int = 650): Boolean {
        val duration = holdMs.coerceIn(400, 1500)
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration.toLong()))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Side swipe for carousels, tabs, and list-row actions. */
    fun swipeHorizontal(toRight: Boolean): Boolean {
        val metrics = resources.displayMetrics
        val y = metrics.heightPixels * 0.55f
        val near = metrics.widthPixels * 0.22f
        val far = metrics.widthPixels * 0.78f
        val path = Path().apply {
            moveTo(if (toRight) near else far, y)
            lineTo(if (toRight) far else near, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 260))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Opens the app's overflow menu. Matches a labelled "more options" control first,
     * then falls back to the unlabelled icon slot in the top-right of the app bar.
     */
    fun openOverflow(): TapResult {
        val root = rootInActiveWindow ?: return TapResult(false, note = "I can't see the current screen yet.")
        val controls = ArrayList<AccessibilityNodeInfo>()
        collectClickable(root, controls, 0)
        if (controls.isEmpty()) {
            return TapResult(false, note = "This screen has no tappable controls I can read.")
        }

        val named = overflowWords.firstNotNullOfOrNull { word ->
            controls.firstOrNull { node ->
                val label = labelOf(node) ?: return@firstOrNull false
                label.length <= 28 && labelMatchesWord(label, word)
            }
        }
        if (named != null) {
            val label = labelOf(named).orEmpty()
            return if (clickNodeOrAncestor(named)) {
                TapResult(true, label)
            } else {
                TapResult(false, label, "\"$label\" is visible but isn't tappable.")
            }
        }

        val (width, height) = screenSize()
        val corner = controls.firstOrNull { node ->
            if (labelOf(node) != null) return@firstOrNull false
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            bounds.height() in 1..height / 8 &&
                bounds.centerY() < height * 0.18 &&
                bounds.centerX() > width * 0.6 &&
                bounds.width() in 1..width / 3
        }
        if (corner != null) {
            val bounds = Rect()
            corner.getBoundsInScreen(bounds)
            val sent = corner.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                tapAt(bounds.centerX(), bounds.centerY())
            return if (sent) {
                TapResult(true, "menu", "No label here, so I tapped the top-right icon.")
            } else {
                TapResult(false, "menu", "The top-right icon didn't accept a tap.")
            }
        }
        return TapResult(false, note = "I couldn't find a menu button on this screen.")
    }

    /**
     * Finds the control that closes what's on screen without pressing it, so the
     * caller can risk-check the label first. Refuses to name a button that would
     * accept, allow, buy, or delete something.
     */
    fun findCloseControl(): TapResult {
        val root = rootInActiveWindow ?: return TapResult(false, note = "I can't see the current screen yet.")
        val controls = ArrayList<AccessibilityNodeInfo>()
        collectClickable(root, controls, 0)
        val labelled = controls.mapNotNull { node -> labelOf(node)?.let { node to it.trim() } }
        if (labelled.isEmpty()) {
            return TapResult(false, note = "I can't read any buttons on this screen.")
        }

        for (word in closeWords) {
            val hit = labelled.firstOrNull { (_, label) ->
                label.length <= 28 && labelMatchesWord(label, word)
            }
            if (hit != null) {
                if (hit.first.isPassword) {
                    return TapResult(false, hit.second, "That's a password field, so I left it alone.")
                }
                return TapResult(true, hit.second)
            }
        }

        val guarded = labelled
            .filter { (_, label) -> isGuardedLabel(label) }
            .map { (_, label) -> "\u201C$label\u201D" }
            .distinct()
            .take(3)
            .joinToString(", ")
        return TapResult(
            false,
            note = if (guarded.isEmpty()) {
                "I don't see a close or cancel button here, so nothing was tapped."
            } else {
                "The only buttons here are $guarded. FlowBubble won't choose one of those for you."
            }
        )
    }

    private fun collectClickable(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > 40 || out.size >= 200) return
        if (node.isVisibleToUser && node.isClickable) out.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectClickable(it, out, depth + 1) }
        }
    }

    fun clearFocusedField(): FieldActionOutcome {
        if (isPasswordFocused()) {
            return FieldActionOutcome.Blocked("This looks like a password field. FlowBubble won't clear it.")
        }
        val node = focusedEditable()
            ?: return FieldActionOutcome.Blocked("No text field is focused, so there's nothing for me to clear.")
        val old = node.text?.toString().orEmpty()
        if (old.isEmpty()) return FieldActionOutcome.Done("The field was already empty")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        val after = focusedEditable()?.text?.toString().orEmpty()
        return if (after.isEmpty()) {
            FieldActionOutcome.Done("Cleared the field")
        } else {
            FieldActionOutcome.Blocked("This field didn't clear, so I left your text alone.")
        }
    }

    /**
     * Selects the whole value of a field. Android exposes no select-all node action,
     * so this sets the selection range explicitly.
     */
    private fun selectAllText(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val length = node.text?.toString()?.length ?: 0
        if (length == 0) return false
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, length)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    fun selectAllFocused(): FieldActionOutcome {
        if (isPasswordFocused()) {
            return FieldActionOutcome.Blocked("This looks like a password field. FlowBubble won't select it.")
        }
        val node = focusedEditable()
            ?: return FieldActionOutcome.Blocked("No text field is focused, so there's nothing to select.")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return FieldActionOutcome.Blocked("Selecting all the text needs Android 9 or newer.")
        }
        if ((node.text?.toString()?.length ?: 0) == 0) {
            return FieldActionOutcome.Blocked("This field is empty, so there's nothing to select.")
        }
        if (!selectAllText(node)) {
            return FieldActionOutcome.Blocked("This field doesn't let me select its text.")
        }
        val after = captureSelection()
        return if (after != null && after.hasSelection) {
            FieldActionOutcome.Done("Selected ${after.selectedText.length} characters")
        } else {
            FieldActionOutcome.Done("Asked the field to select all, but it doesn't report a selection")
        }
    }

    /**
     * Copies a selection. FlowBubble never reads the clipboard back, so the caller
     * can only confirm that the field reported a selection, not what's on the clipboard.
     */
    fun copyFocused(): FieldActionOutcome {
        if (isPasswordFocused()) {
            return FieldActionOutcome.Blocked("This looks like a password field. FlowBubble won't copy from it.")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return FieldActionOutcome.Blocked("Copying from another app needs Android 9 or newer.")
        }
        val node = focusedEditable()
        if (node == null && focusedNode() == null) {
            return FieldActionOutcome.Blocked("I can't find a focused field or selection to copy from.")
        }
        val selection = captureSelection()
        if (node != null && (selection == null || !selection.hasSelection)) {
            if (!selectAllText(node)) {
                return FieldActionOutcome.Blocked("Nothing is selected and this field won't let me select its text.")
            }
        }
        if (!copyCandidates().any { it.performAction(AccessibilityNodeInfo.ACTION_COPY) }) {
            return FieldActionOutcome.Blocked("This app doesn't let FlowBubble copy.")
        }
        val length = captureSelection()?.selectedText?.length ?: 0
        return FieldActionOutcome.Done(
            if (length > 0) "Copied $length characters" else "Asked the app to copy the selection"
        )
    }

    fun pasteFocused(): FieldActionOutcome {
        if (isPasswordFocused()) {
            return FieldActionOutcome.Blocked("This looks like a password field. FlowBubble won't paste into it.")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return FieldActionOutcome.Blocked("Pasting into another app needs Android 9 or newer.")
        }
        val node = focusedEditable()
            ?: return FieldActionOutcome.Blocked("No text field is focused. Tap a field first, then ask again.")
        if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            return FieldActionOutcome.Blocked("This field didn't accept a paste.")
        }
        return FieldActionOutcome.Done("Pasted into the field")
    }

    private fun copyCandidates(): List<AccessibilityNodeInfo> {
        focusedEditable()?.let { return listOf(it) }
        val chain = ArrayList<AccessibilityNodeInfo>()
        var node: AccessibilityNodeInfo? = focusedNode()
        var depth = 0
        while (node != null && depth < 6) {
            chain.add(node)
            node = node.parent
            depth++
        }
        return chain
    }

    private fun labelMatchesWord(rawLabel: String, word: String): Boolean {
        val label = rawLabel.lowercase().trim()
        if (label.isEmpty()) return false
        if (word.contains(' ')) {
            return label == word || label.startsWith("$word ") || label.endsWith(" $word") ||
                label.contains(" $word ")
        }
        return label.split(Regex("[^a-z0-9]+")).any { it == word }
    }

    private fun isGuardedLabel(rawLabel: String): Boolean {
        val words = rawLabel.lowercase().trim().split(Regex("[^a-z0-9]+"))
        return words.any { it in guardedWords }
    }

    fun currentPackage(): String? = rootInActiveWindow?.packageName?.toString()

    /**
     * A connected service that can see neither a window nor an active root has been left in a
     * dead state (Android does this after a process is replaced mid-session) and will not
     * recover on its own. It still reports as enabled, so this is the only honest test.
     */
    fun isResponsive(): Boolean = windows.isNotEmpty() || rootInActiveWindow != null

    /**
     * The app a person is actually looking at. The active window can briefly be FlowBubble's own
     * panel, and `rootInActiveWindow` is null mid-transition, so the topmost application window is
     * the reliable answer. Returns null when only our own overlay is on screen.
     */
    fun foregroundPackage(): String? {
        val topWindow = windows
            .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
            .maxByOrNull { it.layer }
        val fromWindow = topWindow?.root?.packageName?.toString()
        if (fromWindow != null && fromWindow != packageName) return fromWindow
        val active = rootInActiveWindow?.packageName?.toString()
        return if (active == packageName) null else active ?: fromWindow
    }

    fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = getSystemService(WindowManager::class.java)
            val bounds = manager?.maximumWindowMetrics?.bounds
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                return bounds.width() to bounds.height()
            }
        }
        val metrics = resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    fun visibleNodes(maxNodes: Int = 400): List<ScreenNode> {
        val root = rootInActiveWindow ?: return emptyList()
        val result = ArrayList<ScreenNode>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && result.size < maxNodes && visited < 1200) {
            val node = queue.removeFirst()
            visited++
            val nodePackage = node.packageName?.toString()
            if (node.isVisibleToUser && nodePackage != packageName) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val text = node.text?.toString()?.trim().orEmpty()
                val description = node.contentDescription?.toString()?.trim().orEmpty()
                val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    node.hintText?.toString()?.trim().orEmpty()
                } else {
                    ""
                }
                val label = when {
                    text.isNotEmpty() -> text
                    description.isNotEmpty() -> description
                    hint.isNotEmpty() -> hint
                    node.isEditable -> "text field"
                    else -> ""
                }
                val box = ScreenBox(bounds.left, bounds.top, bounds.right, bounds.bottom)
                if (box.isValid && (label.isNotEmpty() || node.isEditable || node.isScrollable || node.isClickable)) {
                    result.add(
                        ScreenNode(
                            label = label,
                            box = box,
                            clickable = node.isClickable,
                            editable = node.isEditable,
                            password = node.isPassword,
                            scrollable = node.isScrollable,
                            packageName = nodePackage
                        )
                    )
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::add)
            }
        }
        return result
    }

    fun findEditableTarget(hints: List<String>): ScreenBox? {
        val normalizedHints = hints.map { it.lowercase() }
        val editable = visibleNodes().filter { it.editable && it.box.isValid }
        val hinted = editable.firstOrNull { node ->
            val label = node.label.lowercase()
            normalizedHints.any { label.contains(it) }
        } ?: editable.firstOrNull { node ->
            val label = node.label.lowercase()
            label.contains("search") || label.contains("find") || label.contains("type")
        } ?: editable.firstOrNull()
        return hinted?.box
    }

    fun labelAt(x: Int, y: Int): String? {
        val nodes = visibleNodes()
        return nodes
            .filter { it.box.containsPoint(x, y) }
            .minByOrNull { it.box.area }
            ?.label
            ?.takeIf { it.isNotBlank() }
    }

    fun screenText(maxChars: Int = 4000): String {
        val root = rootInActiveWindow ?: return ""
        val lines = LinkedHashSet<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        var total = 0
        while (queue.isNotEmpty() && visited < 600 && total < maxChars) {
            val node = queue.removeFirst()
            visited++
            if (node.isVisibleToUser && node.packageName?.toString() != packageName && !node.isPassword) {
                val text = node.text?.toString()?.trim()
                val desc = node.contentDescription?.toString()?.trim()
                val line = when {
                    !text.isNullOrEmpty() -> text
                    !desc.isNullOrEmpty() -> desc
                    else -> null
                }
                if (line != null && line.length > 1 && lines.add(line)) {
                    total += line.length + 1
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return lines.joinToString("\n").take(maxChars)
    }
}
