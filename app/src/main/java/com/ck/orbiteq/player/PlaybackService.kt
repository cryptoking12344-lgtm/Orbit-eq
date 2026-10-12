package com.ck.orbiteq.player

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ck.orbiteq.Notifs
import com.ck.orbiteq.R
import com.ck.orbiteq.library.ArtLoader
import java.util.concurrent.Executors

/**
 * Keeps music playing in the background, shows the media notification and
 * lock-screen controls, and receives headphone buttons / XM4 touch gestures.
 */
class PlaybackService : Service() {

    companion object {
        const val ACTION_TOGGLE = "com.ck.orbiteq.player.TOGGLE"
        const val ACTION_PLAY = "com.ck.orbiteq.player.PLAY"
        const val ACTION_NEXT = "com.ck.orbiteq.player.NEXT"
        const val ACTION_PREV = "com.ck.orbiteq.player.PREV"
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

        /** Intent for buttons outside the app (widgets). */
        fun pending(ctx: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getForegroundService(
                ctx, code,
                Intent(ctx, PlaybackService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }

    private lateinit var session: MediaSessionCompat
    private val main = Handler(Looper.getMainLooper())
    private val artExec = Executors.newSingleThreadExecutor()
    private var art: Bitmap? = null
    private var artFor: String? = null
    private var foreground = false
    private var lastKey = ""

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (PlayerEngine.state.playing) PlayerEngine.pause()
        }
    }

    private val listener = object : PlayerEngine.Listener {
        override fun onPlaybackState(state: PlayerEngine.State) = refresh()
        override fun onSpectrum(bands: FloatArray) {}
    }
    private val queueListener: () -> Unit = { refresh() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifs.ensure(this)
        running = true
        session = MediaSessionCompat(this, "OrbitEQ").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = PlayQueue.play(this@PlaybackService)
                override fun onPause() = PlayerEngine.pause()
                override fun onSkipToNext() { PlayQueue.next() }
                override fun onSkipToPrevious() = PlayQueue.previous()
                override fun onSeekTo(pos: Long) = PlayerEngine.seekTo(pos)
                override fun onStop() = PlayerEngine.stop()
            })
            setSessionActivity(Notifs.openApp(this@PlaybackService))
            isActive = true
        }
        // must be in the foreground straight away
        goForeground(build())
        ContextCompat.registerReceiver(
            this, noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        PlayerEngine.addListener(listener)
        PlayQueue.addListener(queueListener)
        refresh()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foreground) goForeground(build())
        PlayQueue.init(this)
        when (intent?.action) {
            ACTION_TOGGLE -> PlayQueue.togglePlay(this)
            ACTION_PLAY -> PlayQueue.play(this)
            ACTION_NEXT -> PlayQueue.next()
            ACTION_PREV -> PlayQueue.previous()
            ACTION_STOP -> PlayerEngine.stop()
        }
        // nothing to play (empty queue): don't hang around
        if (PlayQueue.isEmpty && !PlayerEngine.state.loaded) {
            main.post { stopSelf() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        PlayerEngine.removeListener(listener)
        PlayQueue.removeListener(queueListener)
        try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
        session.isActive = false
        session.release()
        getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
        super.onDestroy()
    }

    private fun refresh() {
        val st = PlayerEngine.state
        val t = PlayQueue.current

        // album art in the background, then refresh again
        val wantArt = t?.uri
        if (wantArt != artFor) {
            artFor = wantArt
            art = null
            if (t != null) {
                val ctx = applicationContext
                artExec.execute {
                    val b = try { ArtLoader.loadSync(ctx, t.albumId, 512, t.uri) } catch (_: Throwable) { null }
                    main.post {
                        if (artFor == wantArt) {
                            art = b
                            lastKey = ""
                            refresh()
                        }
                    }
                }
            }
        }

        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_STOP
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    if (st.playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    st.positionMs, if (st.playing) 1f else 0f
                )
                .build()
        )

        // only rebuild metadata + notification when something visible changed
        val key = "${t?.uri}|${st.title}|${st.playing}|${st.durationMs}|${art != null}"
        if (key == lastKey) return
        lastKey = key

        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, t?.title ?: st.title ?: getString(R.string.app_name))
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, t?.artist ?: "")
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, t?.album ?: "")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, if (st.durationMs > 0) st.durationMs else (t?.durationMs ?: 0))
                .apply { art?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) } }
                .build()
        )

        val n = build()
        if (st.playing) {
            goForeground(n)
        } else {
            // paused: notification stays, but can be swiped away
            if (foreground) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                foreground = false
            }
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, n)
        }
    }

    private fun goForeground(n: Notification) {
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            foreground = true
        } catch (_: Exception) {
        }
    }

    private fun action(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun build(): Notification {
        val st = PlayerEngine.state
        val t = PlayQueue.current
        return NotificationCompat.Builder(this, Notifs.CH_PLAYER)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle(t?.title ?: st.title ?: getString(R.string.app_name))
            .setContentText(t?.artist ?: getString(if (st.playing) R.string.now_playing else R.string.paused))
            .setLargeIcon(art)
            .setContentIntent(Notifs.openApp(this))
            .setDeleteIntent(action(ACTION_STOP, 5))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(st.playing)
            .addAction(R.drawable.ic_prev, getString(R.string.previous), action(ACTION_PREV, 1))
            .addAction(
                if (st.playing) R.drawable.ic_pause else R.drawable.ic_play,
                getString(if (st.playing) R.string.pause else R.string.play),
                action(ACTION_TOGGLE, 2)
            )
            .addAction(R.drawable.ic_next, getString(R.string.next), action(ACTION_NEXT, 3))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }
}
