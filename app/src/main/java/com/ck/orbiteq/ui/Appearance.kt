package com.ck.orbiteq.ui

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import com.ck.orbiteq.R

/** Theme, accent colour, visualizer style and album-flow effect (per device). */
object Appearance {
    const val THEME_ORBIT = 0
    const val THEME_MIDNIGHT = 1
    const val THEME_RETRO = 2

    const val VIZ_BARS = 0
    const val VIZ_PEAKS = 1
    const val VIZ_WAVE = 2

    val THEME_NAMES = arrayOf("Orbit", "Midnight", "Retro")
    val THEME_DESCS = arrayOf(
        "Deep navy with violet and cyan. The original look.",
        "Pure black for OLED screens. Quiet, saves battery.",
        "Grey bevels, green LCD and segmented bars, like 2000s desktop players.",
    )
    val ACCENT_NAMES = arrayOf("Violet", "Cyan", "Mint", "Amber", "Rose")
    val ACCENT_COLORS = intArrayOf(0xFFA78BFA.toInt(), 0xFF22D3EE.toInt(), 0xFF5EEAD4.toInt(), 0xFFFBBF24.toInt(), 0xFFFB7185.toInt())
    private val ACCENT_STYLES = intArrayOf(R.style.Accent_Violet, R.style.Accent_Cyan, R.style.Accent_Mint, R.style.Accent_Amber, R.style.Accent_Rose)
    val VIZ_NAMES = arrayOf("Bars", "Peaks", "Wave")
    val FLOW_NAMES = arrayOf("Cover Flow", "Wheel", "Stack", "Glide")

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("orbit_look", Context.MODE_PRIVATE)

    fun theme(ctx: Context) = prefs(ctx).getInt("theme", THEME_ORBIT).coerceIn(0, 2)
    fun accent(ctx: Context) = prefs(ctx).getInt("accent", -1).let { if (it in 0..4) it else defaultAccent(theme(ctx)) }
    fun vizStyle(ctx: Context) = prefs(ctx).getInt("viz", -1).let { if (it in 0..2) it else if (theme(ctx) == THEME_RETRO) VIZ_PEAKS else VIZ_BARS }
    fun flowEffect(ctx: Context) = prefs(ctx).getInt("flow", 0).coerceIn(0, 3)

    private fun defaultAccent(theme: Int) = if (theme == THEME_MIDNIGHT) 2 else 0

    fun setTheme(ctx: Context, t: Int) = prefs(ctx).edit().putInt("theme", t).remove("accent").remove("viz").apply()
    fun setAccent(ctx: Context, a: Int) = prefs(ctx).edit().putInt("accent", a).apply()
    fun setVizStyle(ctx: Context, v: Int) = prefs(ctx).edit().putInt("viz", v).apply()
    fun setFlowEffect(ctx: Context, f: Int) = prefs(ctx).edit().putInt("flow", f).apply()

    /** Call before super.onCreate / setContentView. */
    fun apply(activity: Activity) {
        val t = theme(activity)
        activity.setTheme(
            when (t) {
                THEME_MIDNIGHT -> R.style.Theme_OrbitEQ_Midnight
                THEME_RETRO -> R.style.Theme_OrbitEQ_Retro
                else -> R.style.Theme_OrbitEQ
            }
        )
        if (t != THEME_RETRO) activity.theme.applyStyle(ACCENT_STYLES[accent(activity)], true)
    }
}

fun Context.attrColor(@AttrRes attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.data
}

fun Context.isRetro(): Boolean {
    val tv = TypedValue()
    return theme.resolveAttribute(R.attr.oIsRetro, tv, true) && tv.data != 0
}

fun Context.dp(v: Float) = v * resources.displayMetrics.density
