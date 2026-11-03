package com.flowbubble.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.flowbubble.app.access.FlowAccessibilityService
import com.flowbubble.app.service.OverlayService

/** Debug-only entry point: injects a transcript so the pipeline can be exercised without a microphone. */
class DebugSimulateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getStringExtra("probe") == "windows") {
            val service = FlowAccessibilityService.instance
            Log.i("FlowBubbleDebug", "probe service=${service != null} " +
                "active=${service?.currentPackage()} foreground=${service?.foregroundPackage()}")
            service?.windows?.forEach { window ->
                Log.i(
                    "FlowBubbleDebug",
                    "probe win type=${window.type} layer=${window.layer} " +
                        "root=${window.root?.packageName} title=${window.title} " +
                        "focus=${window.root?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)}"
                )
            }
            /*
             * A focused Compose field says `focused=true` in a uiautomator dump, which is the per-node
             * flag, not the answer to findFocus() — so a dump that shows a focused field proves nothing
             * about whether findFocus() can reach it. This walks the tree and prints the nodes that
             * actually carry the flag, which tells the two possibilities apart.
             */
            service?.windows
                ?.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
                ?.forEach { window ->
                    val root = window.root ?: return@forEach
                    val stack = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
                    stack.addLast(root)
                    val focused = ArrayList<String>()
                    var visited = 0
                    while (stack.isNotEmpty() && visited < 600) {
                        val node = stack.removeLast()
                        visited += 1
                        if (node.isFocused || node.isAccessibilityFocused) {
                            focused.add(
                                "${node.className} text=${node.text} editable=${node.isEditable} " +
                                    "a11yFocus=${node.isAccessibilityFocused}"
                            )
                        }
                        for (child in 0 until node.childCount) {
                            node.getChild(child)?.let { stack.addLast(it) }
                        }
                    }
                    Log.i(
                        "FlowBubbleDebug",
                        "probe scan root=${root.packageName} visited=$visited focused=${focused.take(3)}"
                    )
                }
            Log.i("FlowBubbleDebug", "probe focusedNode=${service?.focusedNode()} " +
                "editable=${service?.focusedEditable()}")
            return
        }
        val text = intent.getStringExtra("text")
        Log.i("FlowBubbleDebug", "receiver got text=$text")
        if (text.isNullOrBlank()) return
        context.startService(
            Intent(context, OverlayService::class.java)
                .setAction(OverlayService.ACTION_SIMULATE)
                .putExtra(OverlayService.EXTRA_SIMULATED_TEXT, text)
        )
        Log.i("FlowBubbleDebug", "startService sent")
    }
}
