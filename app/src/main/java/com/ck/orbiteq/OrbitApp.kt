package com.ck.orbiteq

import android.app.Application
import com.ck.orbiteq.library.UserLibrary
import com.ck.orbiteq.model.AudioState
import com.ck.orbiteq.player.PlayQueue
import com.ck.orbiteq.player.PlayerEngine
import com.ck.orbiteq.widget.WidgetUpdater

class OrbitApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AudioState.init(this)
        UserLibrary.init(this)
        PlayQueue.init(this)

        // keep home-screen widgets in step with the player
        PlayerEngine.addListener(object : PlayerEngine.Listener {
            override fun onPlaybackState(state: PlayerEngine.State) = WidgetUpdater.onPlayerChanged(this@OrbitApp)
            override fun onSpectrum(bands: FloatArray) {}
        })
        PlayQueue.addListener { WidgetUpdater.onPlayerChanged(this) }
    }
}
