package com.ck.orbiteq.global

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ck.orbiteq.Notifs
import com.ck.orbiteq.R
import com.ck.orbiteq.model.AudioState
import com.ck.orbiteq.tile.EqTileService

/**
 * Applies the current sound to every app. Attaches to the global mix
 * (session 0) and to any session a music app announces.
 */
class GlobalEffectsService : Service() {

    companion object {
        const val ACTION_STOP = "com.ck.orbiteq.global.STOP"
        const val EXTRA_SESSION = "session"
        private const val NOTIF_ID = 11

        @Volatile
        var running = false
            private set

        @Volatile
        var status: String = ""
            private set

        fun start(ctx: Context, session: Int = -1) {
            val i = Intent(ctx, GlobalEffectsService::class.java)
            if (session > 0) i.putExtra(EXTRA_SESSION, session)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GlobalEffectsService::class.java))
        }
    }

    private val chains = HashMap<Int, EffectChain>()
    private val main = Handler(Looper.getMainLooper())
    private var audioManager: AudioManager? = null
    private val stateListener: () -> Unit = { applyAll() }

    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
            if (session <= 0) return
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> attach(session)
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> detach(session)
            }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            val list = addedDevices ?: return
            for (d in list) {
                if (!DeviceUtil.isHeadphone(d)) continue
                val profile = AudioState.profileForDevice(DeviceUtil.key(d)) ?: continue
                AudioState.setEq(profile.settings.copy())
                Toast.makeText(
                    this@GlobalEffectsService,
                    getString(R.string.applied, profile.name),
                    Toast.LENGTH_SHORT
                ).show()
                break
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AudioState.init(this)
        Notifs.ensure(this)
        if (!goForeground()) {
            stopSelf()
            return
        }
        running = true
        audioManager = getSystemService(AudioManager::class.java)
        attach(0)

        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        ContextCompat.registerReceiver(this, sessionReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        audioManager?.registerAudioDeviceCallback(deviceCallback, main)
        AudioState.addListener(stateListener)
        refreshTile()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) return START_NOT_STICKY
        goForeground()
        val session = intent?.getIntExtra(EXTRA_SESSION, -1) ?: -1
        if (session > 0) attach(session)
        return START_STICKY
    }

    override fun onDestroy() {
        val wasRunning = running
        running = false
        status = ""
        if (wasRunning) {
            AudioState.removeListener(stateListener)
            try {
                unregisterReceiver(sessionReceiver)
            } catch (_: Exception) {
            }
            audioManager?.unregisterAudioDeviceCallback(deviceCallback)
        }
        for (c in chains.values) c.release()
        chains.clear()
        refreshTile()
        super.onDestroy()
    }

    private fun attach(session: Int) {
        if (chains.containsKey(session)) return
        val chain = EffectChain(session)
        if (!chain.worksAtAll && session != 0) return
        chain.apply(AudioState.eq)
        chains[session] = chain
        updateStatus()
    }

    private fun detach(session: Int) {
        chains.remove(session)?.release()
        updateStatus()
    }

    private fun applyAll() {
        val s = AudioState.eq
        for (c in chains.values) c.apply(s)
    }

    private fun updateStatus() {
        val global = chains[0]
        val appSessions = chains.keys.count { it != 0 }
        status = when {
            global == null || (!global.worksAtAll && appSessions == 0) -> getString(R.string.status_failed)
            global.problems.isNotEmpty() -> getString(R.string.status_partial, global.problems.joinToString(", "))
            else -> getString(R.string.status_ok, appSessions)
        }
    }

    private fun goForeground(): Boolean {
        return try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 3,
            Intent(this, GlobalEffectsService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, Notifs.CH_EFFECTS)
            .setSmallIcon(R.drawable.ic_eq)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.global_on))
            .setContentIntent(Notifs.openApp(this))
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.turn_off), stopIntent)
            .build()
    }

    private fun refreshTile() {
        try {
            TileService.requestListeningState(this, ComponentName(this, EqTileService::class.java))
        } catch (_: Exception) {
        }
    }
}
