package com.ck.orbiteq.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.ck.orbiteq.MainActivity
import com.ck.orbiteq.global.GlobalEffectsService

/** Quick-settings tile that turns the system-wide EQ on and off. */
class EqTileService : TileService() {

    private val main = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        render(GlobalEffectsService.running)
    }

    override fun onClick() {
        super.onClick()
        if (GlobalEffectsService.running) {
            GlobalEffectsService.stop(this)
            render(false)
        } else {
            try {
                GlobalEffectsService.start(this)
                render(true)
            } catch (_: Exception) {
                // Android blocked a background start — open the app instead.
                openApp()
            }
        }
        main.postDelayed({ render(GlobalEffectsService.running) }, 800)
    }

    private fun render(on: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (on) "On" else "Off"
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
