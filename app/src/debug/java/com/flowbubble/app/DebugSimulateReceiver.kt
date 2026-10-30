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
                        "root=${window.root?.packageName} title=${window.title}"
                )
            }
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
