package com.ck.orbiteq.player

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ck.orbiteq.Notifs
import com.ck.orbiteq.R

/** Keeps the 8D player alive in the background and shows playback controls. */
class PlaybackService : Service() {

    companion object {
        const val ACTION_TOGGLE = "com.ck.orbiteq.player.TOGGLE"
        const val ACTION_STOP = "com.ck.orbiteq.player.STOP"
        private const val NOTIF_ID = 12

        @Volatile
        var running = false
            private set

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, PlaybackService::class.java))
            } catch (_: Exception) {
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PlaybackService::class.java))
        }
    }

    private var lastPlaying: Boolean? = null
    private var lastTitle: String? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (PlayerEngine.state.playing) PlayerEngine.pause()
        }
    }

    private val listener = object : PlayerEngine.Listener {
        override fun onPlaybackState(state: PlayerEngine.State) {
            if (state.playing != lastPlaying || state.title != lastTitle) show(state)
        }

        override fun onSpectrum(bands: FloatArray) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifs.ensure(this)
        running = true
        show(PlayerEngine.state)
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        PlayerEngine.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        show(PlayerEngine.state)
        when (intent?.action) {
            ACTION_TOGGLE -> PlayerEngine.togglePlay()
            ACTION_STOP -> PlayerEngine.stop()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        PlayerEngine.removeListener(listener)
        try {
            unregisterReceiver(noisyReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun show(state: PlayerEngine.State) {
        lastPlaying = state.playing
        lastTitle = state.title
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, build(state), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun action(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun build(state: PlayerEngine.State): Notification =
        NotificationCompat.Builder(this, Notifs.CH_PLAYER)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle(state.title ?: getString(R.string.app_name))
            .setContentText(getString(if (state.playing) R.string.now_playing else R.string.paused))
            .setContentIntent(Notifs.openApp(this))
            .setOngoing(state.playing)
            .setSilent(true)
            .addAction(
                if (state.playing) R.drawable.ic_pause else R.drawable.ic_play,
                getString(if (state.playing) R.string.pause else R.string.play),
                action(ACTION_TOGGLE, 1)
            )
            .addAction(0, getString(R.string.stop), action(ACTION_STOP, 2))
            .build()
}
