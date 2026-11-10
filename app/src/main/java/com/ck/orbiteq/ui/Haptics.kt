package com.ck.orbiteq.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Short "detent" ticks for the album wall. Uses the vibration motor directly, because the
 * standard view tick (CLOCK_TICK) is silent on many Samsung phones.
 * [strength] 0..1: slow movement gives a firmer click, fast spinning a lighter one.
 */
object Haptics {
    private var vibrator: Vibrator? = null
    private var primitives: Boolean? = null

    private fun vib(ctx: Context): Vibrator? {
        vibrator?.let { return it }
        val v = if (Build.VERSION.SDK_INT >= 31)
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
        vibrator = v
        return v
    }

    private var clickOk: Boolean? = null

    fun tick(ctx: Context, strength: Float) {
        val v = vib(ctx) ?: return
        if (!v.hasVibrator()) return
        val s = strength.coerceIn(0.15f, 1f)
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                // medium detent: a "click" pulse (firmer than a tick), 45-85 % depending on speed
                val click = clickOk ?: v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK).also { clickOk = it }
                if (click) {
                    v.vibrate(VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.35f + 0.5f * s).compose())
                    return
                }
                val ok = primitives ?: v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK).also { primitives = it }
                if (ok) {
                    v.vibrate(VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, (0.6f + 0.4f * s).coerceAtMost(1f)).compose())
                    return
                }
            }
            if (v.hasAmplitudeControl()) {
                v.vibrate(VibrationEffect.createOneShot(14, (100 + 130 * s).toInt().coerceAtMost(255)))
            } else {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            }
        } catch (_: Exception) {
        }
    }
}
