package com.ck.orbiteq.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.ck.orbiteq.R
import com.ck.orbiteq.dsp.Space360Processor
import com.ck.orbiteq.dsp.TheaterProcessor
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Top-down map: your head in the middle (facing up), ear-level speakers on the
 * outer ring, overhead speakers on the dashed inner ring. Tap a speaker to test it.
 * In 360 mode the shaded arc shows where the music wraps; drag to turn the scene.
 */
class SpeakerMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        const val THEATER = 0
        const val SPACE = 1
    }

    var onSpeakerTapped: ((Int) -> Unit)? = null
    /** (degrees, finished) */
    var onRotate: ((Float, Boolean) -> Unit)? = null

    private var mode = THEATER
    private var frontWidth = TheaterProcessor.frontWidth(0.5f)
    private var wrapDeg = 237.0
    private var rotation = 0f
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
        style = Paint.Style.STROKE; strokeWidth = 1.2f * dp; color = grid
    }
    private val topRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * dp; color = grid
        pathEffect = DashPathEffect(floatArrayOf(5f * dp, 5f * dp), 0f)
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = surface2 }
    private val headStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; color = dim
    }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * dp }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val nose = Path()
    private val arcRect = RectF()
    private val xs = FloatArray(12)
    private val ys = FloatArray(12)

    private fun count() = if (mode == SPACE) Space360Processor.SPEAKERS else TheaterProcessor.SPEAKERS

    fun setMode(m: Int) {
        if (m != mode) {
            mode = m
            active = -1
            invalidate()
        }
    }

    fun setImmersion(immersion: Float) {
        frontWidth = TheaterProcessor.frontWidth(immersion); invalidate()
    }

    fun setSpace(wrap: Float, rotationDeg: Float) {
        wrapDeg = Space360Processor.wrapDegrees(wrap)
        if (!dragging) rotation = rotationDeg
        invalidate()
    }

    fun setActive(index: Int) {
        active = index; invalidate()
    }

    fun setOn(on: Boolean) {
        enabledLook = on; invalidate()
    }

    private fun radius() = min(width, height) / 2f - 22f * dp

    private fun position(i: Int): DoubleArray =
        if (mode == SPACE) Space360Processor.position(i) else TheaterProcessor.position(i, frontWidth)

    private fun isTop(i: Int) = if (mode == SPACE) i >= Space360Processor.TOP0 else i >= TheaterProcessor.TFL

    private fun label(i: Int) = if (mode == SPACE) Space360Processor.SHORT[i] else TheaterProcessor.SHORT[i]

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = radius()
        val rTop = r * 0.55f

        canvas.drawCircle(cx, cy, r, ringPaint)
        canvas.drawCircle(cx, cy, rTop, topRingPaint)

        if (mode == SPACE) {
            // where the music sits around you
            val ra = r * 0.8f
            arcRect.set(cx - ra, cy - ra, cx + ra, cy + ra)
            arcPaint.strokeWidth = 14f * dp
            arcPaint.color = ColorUtils.setAlphaComponent(accent, if (enabledLook) 60 else 25)
            val start = (rotation - wrapDeg / 2.0 - 90.0).toFloat()
            canvas.drawArc(arcRect, start, wrapDeg.toFloat(), false, arcPaint)
            // stage centre marker
            val a = Math.toRadians(rotation.toDouble())
            stagePaint.color = if (enabledLook) accent else dim
            canvas.drawCircle(cx + (ra * sin(a)).toFloat(), cy - (ra * cos(a)).toFloat(), 5f * dp, stagePaint)
        }

        val hr = r * 0.15f
        nose.reset()
        nose.moveTo(cx - hr * 0.35f, cy - hr * 0.9f)
        nose.lineTo(cx, cy - hr * 1.35f)
        nose.lineTo(cx + hr * 0.35f, cy - hr * 0.9f)
        nose.close()
        canvas.drawPath(nose, headPaint)
        canvas.drawCircle(cx, cy, hr, headPaint)
        canvas.drawCircle(cx, cy, hr, headStroke)

        val n = count()
        for (i in 0 until n) {
            val pos = position(i)
            val az = Math.toRadians(pos[0])
            val rr = if (isTop(i)) rTop else r
            xs[i] = cx + (rr * sin(az)).toFloat()
            ys[i] = cy - (rr * cos(az)).toFloat()
        }
        for (i in 0 until n) {
            val top = isTop(i)
            val color = if (top) accent2 else accent
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
            val below = ys[i] >= cy || top
            val ly = if (below) ys[i] + rad + 12f * dp else ys[i] - rad - 5f * dp
            canvas.drawText(label(i), xs[i], ly, labelPaint)
        }
    }

    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; dragging = false
                if (mode == SPACE) parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == SPACE && (dragging || hypot(e.x - downX, e.y - downY) > 12f * dp)) {
                    dragging = true
                    rotation = angleAt(e.x, e.y)
                    onRotate?.invoke(rotation, false)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (dragging) {
                    dragging = false
                    onRotate?.invoke(rotation, true)
                    return true
                }
                var best = -1
                var bestD = 30f * dp
                for (i in 0 until count()) {
                    val d = hypot(e.x - xs[i], e.y - ys[i])
                    if (d < bestD) { bestD = d; best = i }
                }
                if (best >= 0) onSpeakerTapped?.invoke(best)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (dragging) {
                    dragging = false
                    onRotate?.invoke(rotation, true)
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    /** Angle of the touch around the centre, 0 = up (front), + = right, snapped to 5°. */
    private fun angleAt(x: Float, y: Float): Float {
        val a = Math.toDegrees(atan2((x - width / 2f).toDouble(), -(y - height / 2f).toDouble()))
        val snapped = (a / 5.0).roundToInt() * 5
        return (if (abs(snapped) > 180) snapped - 360 * Integer.signum(snapped) else snapped).toFloat()
    }
}
