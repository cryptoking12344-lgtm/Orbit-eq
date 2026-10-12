package com.ck.orbiteq.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ck.orbiteq.R
import com.ck.orbiteq.ui.Appearance
import com.ck.orbiteq.ui.dp
import com.google.android.material.card.MaterialCardView

/** Shown when a widget is added (or reconfigured): pick its style. */
class WidgetConfigActivity : AppCompatActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.apply(this)
        super.onCreate(savedInstanceState)
        widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // if the user backs out, the widget is not added
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setContentView(R.layout.activity_widget_config)
        val list: LinearLayout = findViewById(R.id.cfgList)
        val current = WidgetStyles.get(this, widgetId)
        val inf = LayoutInflater.from(this)

        for (style in 0..3) {
            val card = MaterialCardView(this)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(14f).toInt()
            card.layoutParams = lp
            card.radius = dp(20f)
            card.strokeWidth = if (style == current) dp(2f).toInt() else 0
            card.setCardBackgroundColor(0xFF22222E.toInt())
            card.isClickable = true
            card.isFocusable = true
            card.contentDescription = getString(R.string.widget_style_cd, WidgetStyles.NAMES[style])

            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(dp(14f).toInt(), dp(12f).toInt(), dp(14f).toInt(), dp(14f).toInt())
            val name = TextView(this)
            name.text = WidgetStyles.NAMES[style]
            name.textSize = 16f
            name.setTextColor(0xFFF4F4F8.toInt())
            box.addView(name)

            // live preview using the real widget layout on a wallpaper-like backdrop
            val stage = FrameLayout(this)
            stage.setBackgroundResource(R.drawable.widget_preview_backdrop)
            stage.setPadding(dp(10f).toInt(), dp(10f).toInt(), dp(10f).toInt(), dp(10f).toInt())
            val preview = inf.inflate(R.layout.widget_medium, stage, false)
            preview.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(64f).toInt())
            stylePreview(preview, style)
            stage.addView(preview)
            val slp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            slp.topMargin = dp(8f).toInt()
            box.addView(stage, slp)
            card.addView(box)

            card.setOnClickListener {
                WidgetStyles.set(this, widgetId, style)
                WidgetUpdater.update(this, intArrayOf(widgetId))
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                finish()
            }
            list.addView(card)
        }
    }

    private fun stylePreview(v: View, style: Int) {
        val look = WidgetStyles.look(style)
        v.findViewById<View>(R.id.w_root).setBackgroundResource(look.bg)
        v.findViewById<View>(R.id.w_lcd).setBackgroundResource(look.lcd)
        val play = v.findViewById<android.widget.ImageButton>(R.id.w_play)
        play.setBackgroundResource(look.playBg)
        play.setColorFilter(look.playIcon)
        v.findViewById<android.widget.ImageButton>(R.id.w_prev).setColorFilter(look.icon)
        v.findViewById<android.widget.ImageButton>(R.id.w_next).setColorFilter(look.icon)
        if (style == WidgetStyles.CLASSIC) {
            v.findViewById<View>(R.id.w_prev).setBackgroundResource(R.drawable.wbtn_classic)
            v.findViewById<View>(R.id.w_next).setBackgroundResource(R.drawable.wbtn_classic)
        }
        val title = v.findViewById<TextView>(R.id.w_title)
        val sub = v.findViewById<TextView>(R.id.w_artist)
        title.text = if (style == WidgetStyles.CLASSIC) "SONG TITLE" else "Song title"
        sub.text = if (style == WidgetStyles.CLASSIC) "ARTIST" else "Artist"
        title.setTextColor(look.title)
        sub.setTextColor(look.sub)
    }
}
