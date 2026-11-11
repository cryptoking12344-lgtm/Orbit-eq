package com.ck.orbiteq.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.ck.orbiteq.R

/**
 * The soft colour behind the Liquid Glass theme: three big glows on a near-black ground, so
 * the see-through panels have something to tint. The main glow takes its colour from the
 * playing song's cover ([setArt]) and falls back to the accent colour.
 */
class GlowBackdropView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val base = context.attrColor(R.attr.oBg)
    private val accent = context.attrColor(R.attr.oAccent)
    private var main = accent
    private var shown = accent
    private var anim: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Tint the main glow from a cover (null = back to the accent colour). */
    fun setArt(b: Bitmap?) {
        val target = if (b == null) accent else vivid(average(b))
        if (target == main) return
        main = target
        val from = shown
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            addUpdateListener {
                shown = ColorUtils.blendARGB(from, target, it.animatedValue as Float)
                invalidate()
            }
            start()
        }
    }

    private fun average(b: Bitmap): Int {
        val s = Bitmap.createScaledBitmap(b, 8, 8, true)
        var r = 0L; var g = 0L; var bl = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            val c = s.getPixel(x, y)
            r += Color.red(c); g += Color.green(c); bl += Color.blue(c)
        }
        if (s !== b) s.recycle()
        return Color.rgb((r / 64).toInt(), (g / 64).toInt(), (bl / 64).toInt())
    }

    /** Covers are often grey or dark on average: push to a colour that glows. */
    private fun vivid(c: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(c, hsl)
        if (hsl[1] < 0.12f) return accent        // no real hue: keep the accent
        hsl[1] = hsl[1].coerceIn(0.55f, 0.85f)
        hsl[2] = 0.5f
        return ColorUtils.HSLToColor(hsl)
    }

    private fun shift(c: Int, deg: Float): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(c, hsl)
        hsl[0] = (hsl[0] + deg + 360f) % 360f
        return ColorUtils.HSLToColor(hsl)
    }

    private fun glow(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int, alpha: Int) {
        paint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(ColorUtils.setAlphaComponent(color, alpha), ColorUtils.setAlphaComponent(color, alpha / 3), ColorUtils.setAlphaComponent(color, 0)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r, paint)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(base)
        val w = width.toFloat(); val h = height.toFloat()
        glow(canvas, w * 0.12f, h * 0.30f, w * 0.85f, shown, 120)
        glow(canvas, w * 1.02f, h * 0.52f, w * 0.80f, shift(shown, -70f), 85)
        glow(canvas, w * 0.45f, h * 1.00f, w * 0.85f, shift(shown, 55f), 70)
    }
}
