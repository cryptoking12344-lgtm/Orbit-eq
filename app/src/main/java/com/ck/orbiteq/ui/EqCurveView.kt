package com.ck.orbiteq.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.ck.orbiteq.R
import com.ck.orbiteq.model.EqSettings
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Draggable 10-band EQ curve. Drag any point up or down. */
class EqCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** (gains, finished) — finished is true when the finger lifts. */
    var onGainsChanged: ((FloatArray, Boolean) -> Unit)? = null

    private val gains = FloatArray(EqSettings.BANDS)
    private var active = -1
    private val maxDb = EqSettings.GAIN_MAX
    private val dp = resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val accent2 = ContextCompat.getColor(context, R.color.accent2)

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.grid)
        strokeWidth = 1f * dp
    }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_dim)
        alpha = 90
        strokeWidth = 1.2f * dp
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_dim)
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text)
        textSize = sp(12f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.surface)
    }
    private val knobRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * dp
        color = accent
    }
    private val curve = Path()
    private val fill = Path()

    private fun plotL() = 34f * dp
    private fun plotR() = width - 10f * dp
    private fun plotT() = 22f * dp
    private fun plotB() = height - 24f * dp
    private fun xFor(i: Int) = plotL() + (i + 0.5f) * (plotR() - plotL()) / EqSettings.BANDS
    private fun yFor(db: Float) = plotT() + (maxDb - db) / (2 * maxDb) * (plotB() - plotT())

    fun setGains(g: FloatArray) {
        if (active >= 0) return  // don't fight the user's finger
        for (i in gains.indices) gains[i] = g.getOrElse(i) { 0f }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        curvePaint.shader = LinearGradient(plotL(), 0f, plotR(), 0f, accent, accent2, Shader.TileMode.CLAMP)
        fillPaint.shader = LinearGradient(
            0f, plotT(), 0f, plotB(),
            ColorUtils.setAlphaComponent(accent, 90),
            ColorUtils.setAlphaComponent(accent2, 8),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val l = plotL()
        val r = plotR()
        val b = plotB()

        for (db in intArrayOf(12, 6, 0, -6, -12)) {
            val y = yFor(db.toFloat())
            canvas.drawLine(l, y, r, y, if (db == 0) zeroPaint else gridPaint)
            canvas.drawText(if (db > 0) "+$db" else "$db", l - 17f * dp, y + 3.5f * dp, labelPaint)
        }

        val n = EqSettings.BANDS
        val xs = FloatArray(n) { xFor(it) }
        val ys = FloatArray(n) { yFor(gains[it]) }

        curve.reset()
        curve.moveTo(l, ys[0])
        curve.lineTo(xs[0], ys[0])
        for (i in 0 until n - 1) {
            val i0 = max(i - 1, 0)
            val i3 = min(i + 2, n - 1)
            curve.cubicTo(
                xs[i] + (xs[i + 1] - xs[i0]) / 6f, ys[i] + (ys[i + 1] - ys[i0]) / 6f,
                xs[i + 1] - (xs[i3] - xs[i]) / 6f, ys[i + 1] - (ys[i3] - ys[i]) / 6f,
                xs[i + 1], ys[i + 1]
            )
        }
        curve.lineTo(r, ys[n - 1])

        val zeroY = yFor(0f)
        fill.set(curve)
        fill.lineTo(r, zeroY)
        fill.lineTo(l, zeroY)
        fill.close()

        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(curve, curvePaint)

        for (i in 0 until n) {
            val rad = if (i == active) 9f * dp else 6f * dp
            canvas.drawCircle(xs[i], ys[i], rad, knobPaint)
            canvas.drawCircle(xs[i], ys[i], rad, knobRing)
            canvas.drawText(EqSettings.FREQ_LABELS[i], xs[i], b + 17f * dp, labelPaint)
        }

        if (active >= 0) {
            val text = String.format(Locale.US, "%+.1f dB", gains[active])
            canvas.drawText(text, xs[active], ys[active] - 15f * dp, valuePaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val span = (plotR() - plotL()) / EqSettings.BANDS
                active = ((e.x - plotL()) / span).toInt().coerceIn(0, EqSettings.BANDS - 1)
                parent?.requestDisallowInterceptTouchEvent(true)
                setFromY(e.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (active >= 0) setFromY(e.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (active >= 0) {
                    active = -1
                    onGainsChanged?.invoke(gains.copyOf(), true)
                    invalidate()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun setFromY(y: Float) {
        val db = (maxDb - (y - plotT()) / (plotB() - plotT()) * 2 * maxDb).coerceIn(-maxDb, maxDb)
        val snapped = (db * 2f).roundToInt() / 2f
        if (snapped != gains[active]) {
            gains[active] = snapped
            onGainsChanged?.invoke(gains.copyOf(), false)
            invalidate()
        }
    }
}
