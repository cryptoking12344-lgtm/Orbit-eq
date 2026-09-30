package com.ck.orbiteq.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.ck.orbiteq.R
import com.ck.orbiteq.dsp.Spectrum
import kotlin.math.max

/** Rounded spectrum bars with a violet-to-cyan gradient. */
class VisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val levels = FloatArray(Spectrum.BANDS)
    private val dp = resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val accent2 = ContextCompat.getColor(context, R.color.accent2)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.surface2)
    }
    private val rect = RectF()

    fun setLevels(bands: FloatArray) {
        for (i in levels.indices) {
            val v = bands.getOrElse(i) { 0f }
            levels[i] = if (v > levels[i]) v else levels[i] * 0.8f + v * 0.2f
        }
        invalidate()
    }

    fun clear() {
        levels.fill(0f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        barPaint.shader = LinearGradient(0f, h.toFloat(), 0f, 0f, accent, accent2, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = levels.size
        val gap = 3f * dp
        val bw = (width - gap * (n - 1)) / n
        if (bw <= 0f) return
        val h = height.toFloat()
        val radius = bw / 2f
        for (i in 0 until n) {
            val x = i * (bw + gap)
            rect.set(x, 0f, x + bw, h)
            canvas.drawRoundRect(rect, radius, radius, trackPaint)
            val barH = max(levels[i], 0.04f) * h
            rect.set(x, h - barH, x + bw, h)
            canvas.drawRoundRect(rect, radius, radius, barPaint)
        }
    }
}
