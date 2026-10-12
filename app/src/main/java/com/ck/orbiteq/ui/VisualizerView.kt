package com.ck.orbiteq.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.ck.orbiteq.R
import com.ck.orbiteq.dsp.Spectrum
import kotlin.math.max
import kotlin.math.min

/** Spectrum display: rounded Bars, segmented Peaks (with falling peak caps) or a smooth Wave. */
class VisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var style = Appearance.vizStyle(context)
        set(v) { field = v; rebuildShaders(); invalidate() }

    private val levels = FloatArray(Spectrum.BANDS)
    private val peaks = FloatArray(Spectrum.BANDS)
    private val peakHold = IntArray(Spectrum.BANDS)
    private val retro = context.isRetro()
    private val accent = context.attrColor(R.attr.oAccent)
    private val accent2 = context.attrColor(R.attr.oAccent2)
    private val track = context.attrColor(R.attr.oSurface2)
    private val text = context.attrColor(R.attr.oText)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val segPaint = Paint()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = track }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2.5f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val waveFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lcdPaint = Paint().apply { color = 0xFF000000.toInt() }
    private val rect = RectF()
    private val path = Path()
    private val fill = Path()

    fun setLevels(bands: FloatArray) {
        for (i in levels.indices) {
            val v = bands.getOrElse(i) { 0f }
            levels[i] = if (v > levels[i]) v else levels[i] * 0.8f + v * 0.2f
            if (levels[i] >= peaks[i]) {
                peaks[i] = levels[i]; peakHold[i] = 10
            } else if (peakHold[i] > 0) {
                peakHold[i]--
            } else {
                peaks[i] = max(levels[i], peaks[i] - 0.025f)
            }
        }
        invalidate()
    }

    fun clear() {
        levels.fill(0f); peaks.fill(0f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShaders()
    }

    private fun rebuildShaders() {
        val h = height.toFloat().coerceAtLeast(1f)
        barPaint.shader = LinearGradient(0f, h, 0f, 0f, accent, accent2, Shader.TileMode.CLAMP)
        wavePaint.shader = LinearGradient(0f, 0f, width.toFloat().coerceAtLeast(1f), 0f, accent, accent2, Shader.TileMode.CLAMP)
        waveFill.shader = LinearGradient(
            0f, 0f, 0f, h,
            ColorUtils.setAlphaComponent(accent, 110), ColorUtils.setAlphaComponent(accent2, 10), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (style) {
            Appearance.VIZ_PEAKS -> drawPeaks(canvas)
            Appearance.VIZ_WAVE -> drawWave(canvas)
            else -> drawBars(canvas)
        }
    }

    private fun drawBars(canvas: Canvas) {
        val n = levels.size
        val gap = context.dp(3f)
        val bw = (width - gap * (n - 1)) / n
        if (bw <= 0f) return
        val h = height.toFloat()
        val r = if (retro) 0f else bw / 2f
        for (i in 0 until n) {
            val x = i * (bw + gap)
            rect.set(x, 0f, x + bw, h)
            canvas.drawRoundRect(rect, r, r, trackPaint)
            val barH = max(levels[i], 0.04f) * h
            rect.set(x, h - barH, x + bw, h)
            canvas.drawRoundRect(rect, r, r, barPaint)
        }
    }

    private fun segColor(frac: Float): Int = if (retro) {
        when {
            frac > 0.85f -> 0xFFFF4B3E.toInt()
            frac > 0.6f -> 0xFFF5D33A.toInt()
            else -> 0xFF3BE36A.toInt()
        }
    } else {
        ColorUtils.blendARGB(accent, accent2, frac)
    }

    private fun drawPeaks(canvas: Canvas) {
        val n = levels.size
        val w = width.toFloat()
        val h = height.toFloat()
        if (retro) canvas.drawRect(0f, 0f, w, h, lcdPaint)
        val pad = if (retro) context.dp(3f) else 0f
        val gap = context.dp(2f)
        val bw = (w - pad * 2 - gap * (n - 1)) / n
        if (bw <= 0f) return
        val segs = max(6, min(16, (h / context.dp(4.5f)).toInt()))
        val sh = (h - pad * 2) / segs
        val dim = if (retro) 0xFF0E2414.toInt() else track
        for (i in 0 until n) {
            val x = pad + i * (bw + gap)
            val lit = (levels[i] * segs).toInt()
            val pk = (peaks[i] * segs).toInt().coerceAtMost(segs - 1)
            for (s in 0 until segs) {
                val top = h - pad - (s + 1) * sh
                segPaint.color = if (s < lit) segColor(s.toFloat() / segs) else dim
                canvas.drawRect(x, top + 1f, x + bw, top + sh - 1f, segPaint)
            }
            if (peaks[i] > 0.05f) {
                val top = h - pad - (pk + 1) * sh
                segPaint.color = if (retro) 0xFFE8E8F0.toInt() else text
                canvas.drawRect(x, top + 1f, x + bw, top + sh - 1f, segPaint)
            }
        }
    }

    private fun drawWave(canvas: Canvas) {
        val n = levels.size
        val w = width.toFloat()
        val h = height.toFloat()
        if (n < 2) return
        val step = w / (n - 1)
        path.reset()
        for (i in 0 until n) {
            val x = i * step
            val y = h - max(levels[i], 0.03f) * (h - context.dp(3f))
            if (i == 0) {
                path.moveTo(x, y)
            } else {
                val px = (i - 1) * step
                val py = h - max(levels[i - 1], 0.03f) * (h - context.dp(3f))
                val mx = (px + x) / 2f
                path.cubicTo(mx, py, mx, y, x, y)
            }
        }
        fill.set(path)
        fill.lineTo(w, h)
        fill.lineTo(0f, h)
        fill.close()
        canvas.drawPath(fill, waveFill)
        canvas.drawPath(path, wavePaint)
    }
}
