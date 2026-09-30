package com.ck.orbiteq.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.ck.orbiteq.R
import com.ck.orbiteq.dsp.TheaterProcessor
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Top-down cinema map: your head in the middle (facing up), 7 speakers
 * around you and 4 height speakers on the inner dashed ring. Tap a speaker to test it.
 */
class SpeakerMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onSpeakerTapped: ((Int) -> Unit)? = null

    private var frontWidth = TheaterProcessor.frontWidth(0.5f)
    private var active = -1
    private var enabledLook = true
    private val dp = resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val accent2 = ContextCompat.getColor(context, R.color.accent2)
    private val textCol = ContextCompat.getColor(context, R.color.text)
    private val dim = ContextCompat.getColor(context, R.color.text_dim)
    private val grid = ContextCompat.getColor(context, R.color.grid)
    private val surface2 = ContextCompat.getColor(context, R.color.surface2)

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * dp
        color = grid
    }
    private val topRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * dp
        color = grid
        pathEffect = DashPathEffect(floatArrayOf(5f * dp, 5f * dp), 0f)
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = surface2 }
    private val headStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * dp
        color = dim
    }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val nose = Path()
    private val xs = FloatArray(TheaterProcessor.SPEAKERS)
    private val ys = FloatArray(TheaterProcessor.SPEAKERS)

    fun setImmersion(immersion: Float) {
        frontWidth = TheaterProcessor.frontWidth(immersion)
        invalidate()
    }

    fun setActive(index: Int) {
        active = index
        invalidate()
    }

    fun setOn(on: Boolean) {
        enabledLook = on
        invalidate()
    }

    private fun radius() = min(width, height) / 2f - 22f * dp

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = radius()
        val rTop = r * 0.55f

        canvas.drawCircle(cx, cy, r, ringPaint)
        canvas.drawCircle(cx, cy, rTop, topRingPaint)

        // head, facing up
        val hr = r * 0.15f
        nose.reset()
        nose.moveTo(cx - hr * 0.35f, cy - hr * 0.9f)
        nose.lineTo(cx, cy - hr * 1.35f)
        nose.lineTo(cx + hr * 0.35f, cy - hr * 0.9f)
        nose.close()
        canvas.drawPath(nose, headPaint)
        canvas.drawCircle(cx, cy, hr, headPaint)
        canvas.drawCircle(cx, cy, hr, headStroke)

        for (i in 0 until TheaterProcessor.SPEAKERS) {
            val pos = TheaterProcessor.position(i, frontWidth)
            val az = Math.toRadians(pos[0])
            val isTop = pos[1] > 0
            val rr = if (isTop) rTop else r
            xs[i] = cx + (rr * sin(az)).toFloat()
            ys[i] = cy - (rr * cos(az)).toFloat()
        }

        for (i in 0 until TheaterProcessor.SPEAKERS) {
            val isTop = i >= TheaterProcessor.TFL
            val color = if (isTop) accent2 else accent
            val on = i == active
            val rad = (if (on) 11f else 8f) * dp
            if (on) {
                glow.color = ColorUtils.setAlphaComponent(color, 70)
                canvas.drawCircle(xs[i], ys[i], rad * 2.1f, glow)
            }
            dotFill.color = if (on) color else surface2
            dotStroke.color = if (enabledLook) color else dim
            canvas.drawCircle(xs[i], ys[i], rad, dotFill)
            canvas.drawCircle(xs[i], ys[i], rad, dotStroke)
            labelPaint.color = if (on) textCol else dim
            val below = ys[i] >= cy || isTop
            val ly = if (below) ys[i] + rad + 12f * dp else ys[i] - rad - 5f * dp
            canvas.drawText(TheaterProcessor.SHORT[i], xs[i], ly, labelPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) return true
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            var best = -1
            var bestD = 30f * dp
            for (i in 0 until TheaterProcessor.SPEAKERS) {
                val d = hypot(e.x - xs[i], e.y - ys[i])
                if (d < bestD) {
                    bestD = d
                    best = i
                }
            }
            if (best >= 0) onSpeakerTapped?.invoke(best)
            return true
        }
        return super.onTouchEvent(e)
    }
}
