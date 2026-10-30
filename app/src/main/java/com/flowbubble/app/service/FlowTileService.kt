package com.flowbubble.app.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.flowbubble.app.MainActivity
import com.flowbubble.app.R
import com.flowbubble.app.core.Permissions

class FlowTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (OverlayService.isRunning) {
            OverlayService.stop(this)
        } else {
            val permissions = Permissions.snapshot(this)
            if (!permissions.coreReady) {
                openSetup()
                return
            }
            OverlayService.start(this)
        }
        TileService.requestListeningState(
            this,
            ComponentName(this, FlowTileService::class.java)
        )
    }

    private fun updateTile() {
        val quickTile = qsTile ?: return
        quickTile.icon = Icon.createWithResource(this, R.drawable.ic_mic)
        quickTile.label = getString(R.string.app_name)
        quickTile.state = if (OverlayService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        quickTile.updateTile()
    }

    private fun openSetup() {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_SETUP)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
