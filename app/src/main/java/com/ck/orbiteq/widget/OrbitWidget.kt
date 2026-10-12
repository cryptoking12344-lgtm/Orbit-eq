package com.ck.orbiteq.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.SizeF
import android.widget.RemoteViews
import com.ck.orbiteq.MainActivity
import com.ck.orbiteq.R
import com.ck.orbiteq.library.ArtLoader
import com.ck.orbiteq.player.PlayQueue
import com.ck.orbiteq.player.PlaybackService
import com.ck.orbiteq.player.PlayerEngine
import java.util.concurrent.Executors

/** Resizable home-screen player: small / medium / large layouts, four styles. */
class OrbitWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        WidgetUpdater.update(context, ids)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        WidgetUpdater.update(context, intArrayOf(id))
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        for (id in ids) WidgetStyles.forget(context, id)
    }
}

object WidgetStyles {
    const val ORIGINAL = 0
    const val DARK = 1
    const val GLASS = 2
    const val CLASSIC = 3
    val NAMES = arrayOf("Original", "Dark", "Liquid glass", "Classic")

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("orbit_widgets", Context.MODE_PRIVATE)
    fun get(ctx: Context, id: Int) = prefs(ctx).getInt("w$id", ORIGINAL).coerceIn(0, 3)
    fun set(ctx: Context, id: Int, style: Int) = prefs(ctx).edit().putInt("w$id", style).apply()
    fun forget(ctx: Context, id: Int) = prefs(ctx).edit().remove("w$id").apply()

    class Look(
        val bg: Int, val lcd: Int, val playBg: Int,
        val title: Int, val sub: Int, val icon: Int, val playIcon: Int,
    )

    fun look(style: Int) = when (style) {
        DARK -> Look(R.drawable.wbg_dark, R.drawable.wlcd_none, R.drawable.wbtn_dark,
            0xFFF4F4F8.toInt(), 0xFF8A8A93.toInt(), 0xFFF4F4F8.toInt(), 0xFF000000.toInt())
        GLASS -> Look(R.drawable.wbg_glass, R.drawable.wlcd_none, R.drawable.wbtn_glass,
            0xFFFFFFFF.toInt(), 0xE6FFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt())
        CLASSIC -> Look(R.drawable.wbg_classic, R.drawable.wlcd_classic, R.drawable.wbtn_classic,
            0xFF3BE36A.toInt(), 0xFF2E9E4C.toInt(), 0xFFE8E8F0.toInt(), 0xFF3BE36A.toInt())
        else -> Look(R.drawable.wbg_original, R.drawable.wlcd_none, R.drawable.wbtn_original,
            0xFFF4F4F8.toInt(), 0xFF9A9AAE.toInt(), 0xFFF4F4F8.toInt(), 0xFF0A0A0F.toInt())
    }
}

object WidgetUpdater {
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private var art: Bitmap? = null
    private var artFor: String? = null
    private var lastKey = ""

    fun ids(ctx: Context): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, OrbitWidget::class.java))

    /** Refresh every widget when the song or play state changed. */
    fun onPlayerChanged(ctx: Context) {
        val t = PlayQueue.current
        val key = "${t?.uri}|${PlayerEngine.state.playing}|${art != null}"
        if (key == lastKey) return
        lastKey = key
        val all = ids(ctx)
        if (all.isNotEmpty()) update(ctx, all)
    }

    fun update(ctx: Context, ids: IntArray) {
        val app = ctx.applicationContext
        val t = PlayQueue.current
        if (t?.uri != artFor) {
            artFor = t?.uri
            art = null
            if (t != null) {
                exec.execute {
                    val b = try { ArtLoader.loadSync(app, t.albumId, 300, t.uri) } catch (_: Throwable) { null }
                    main.post {
                        if (artFor == t.uri && b != null) {
                            art = b
                            update(app, ids(app))
                        }
                    }
                }
            }
        }
        val mgr = AppWidgetManager.getInstance(app)
        for (id in ids) {
            try {
                mgr.updateAppWidget(id, build(app, mgr, id))
            } catch (_: Exception) {
            }
        }
    }

    private fun build(ctx: Context, mgr: AppWidgetManager, id: Int): RemoteViews {
        val style = WidgetStyles.get(ctx, id)
        val small = fill(ctx, RemoteViews(ctx.packageName, R.layout.widget_small), style)
        val medium = fill(ctx, RemoteViews(ctx.packageName, R.layout.widget_medium), style)
        val large = fill(ctx, RemoteViews(ctx.packageName, R.layout.widget_large), style)
        if (Build.VERSION.SDK_INT >= 31) {
            return RemoteViews(
                mapOf(
                    SizeF(40f, 40f) to small,
                    SizeF(180f, 40f) to medium,
                    SizeF(220f, 130f) to large,
                )
            )
        }
        val o = mgr.getAppWidgetOptions(id)
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 60)
        return when {
            w >= 220 && h >= 130 -> large
            w >= 180 -> medium
            else -> small
        }
    }

    private fun fill(ctx: Context, rv: RemoteViews, style: Int): RemoteViews {
        val look = WidgetStyles.look(style)
        val t = PlayQueue.current
        val playing = PlayerEngine.state.playing
        val classic = style == WidgetStyles.CLASSIC

        rv.setInt(R.id.w_root, "setBackgroundResource", look.bg)
        rv.setInt(R.id.w_play, "setBackgroundResource", look.playBg)
        rv.setImageViewResource(R.id.w_play, if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        rv.setInt(R.id.w_play, "setColorFilter", look.playIcon)
        rv.setContentDescription(R.id.w_play, ctx.getString(if (playing) R.string.pause else R.string.play))
        rv.setOnClickPendingIntent(R.id.w_play, PlaybackService.pending(ctx, PlaybackService.ACTION_TOGGLE, 21))

        val b = art
        if (b != null) rv.setImageViewBitmap(R.id.w_art, b) else rv.setImageViewResource(R.id.w_art, R.drawable.wart_placeholder)
        val open = PendingIntent.getActivity(
            ctx, 20,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        rv.setOnClickPendingIntent(R.id.w_art, open)

        // views that only exist in the medium + large layouts
        if (rv.layoutId != R.layout.widget_small) {
            rv.setInt(R.id.w_lcd, "setBackgroundResource", look.lcd)
            val title = t?.title ?: ctx.getString(R.string.app_name)
            val sub = t?.artist ?: ctx.getString(R.string.widget_idle)
            rv.setTextViewText(R.id.w_title, if (classic) title.uppercase() else title)
            rv.setTextViewText(R.id.w_artist, if (classic) sub.uppercase() else sub)
            rv.setTextColor(R.id.w_title, look.title)
            rv.setTextColor(R.id.w_artist, look.sub)
            rv.setOnClickPendingIntent(R.id.w_lcd, open)
            rv.setInt(R.id.w_prev, "setColorFilter", look.icon)
            rv.setInt(R.id.w_next, "setColorFilter", look.icon)
            if (classic) {
                rv.setInt(R.id.w_prev, "setBackgroundResource", R.drawable.wbtn_classic)
                rv.setInt(R.id.w_next, "setBackgroundResource", R.drawable.wbtn_classic)
            } else {
                rv.setInt(R.id.w_prev, "setBackgroundResource", R.drawable.wbtn_none)
                rv.setInt(R.id.w_next, "setBackgroundResource", R.drawable.wbtn_none)
            }
            rv.setOnClickPendingIntent(R.id.w_prev, PlaybackService.pending(ctx, PlaybackService.ACTION_PREV, 22))
            rv.setOnClickPendingIntent(R.id.w_next, PlaybackService.pending(ctx, PlaybackService.ACTION_NEXT, 23))
        }
        return rv
    }
}
