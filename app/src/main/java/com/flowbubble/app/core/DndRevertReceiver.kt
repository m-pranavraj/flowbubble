package com.flowbubble.app.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DndRevertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            DndRevert.restore(context)
        } else {
            DndRevert.revertNow(context)
        }
    }
}
