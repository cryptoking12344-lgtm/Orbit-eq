package com.ck.orbiteq.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import androidx.core.graphics.ColorUtils
import com.ck.orbiteq.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * A ridged thumb wheel, seen from the side, that scrolls the album wall (drag left / right).
 * It reports how many albums to move ([onTurn]) and the release speed ([onRelease], albums/s).
 * What it shows always follows the wall ([setPosition]), so it keeps spinning during a fling.
 */
class JogWheelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onStart: (() -> Unit)? = null
    var onTurn: ((Float) -> Unit)? = null
    var onRelease: ((Float) -> Unit)? = null

    private var pos = 0f
    /** The wall's scroll position (albums). */
    fun setPosition(p: Float) {
        if (p != pos) { pos = p; invalidate() }
    }

    private val accent = context.attrColor(R.attr.oAccent)
    private val surface = context.attrColor(R.attr.oSurface)
    private val surface2 = context.attrColor(R.attr.oSurface2)
    private val text = context.attrColor(R.attr.oText)
    private val grid = context.attrColor(R.attr.oGrid)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rc = RectF()

    /** Strip: finger travel per album. */
    private val stripStep get() = context.dp(34f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, resolveSize(context.dp(44f).toInt(), heightMeasureSpec))
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        drawStrip(canvas)
    }

    private fun drawStrip(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = h / 2f
        rc.set(0f, 0f, w, h)
        // the body: a cylinder, darker toward the ends
        paint.shader = LinearGradient(0f, 0f, w, 0f,
            intArrayOf(ColorUtils.blendARGB(surface, 0xFF000000.toInt(), 0.45f), surface2,
                ColorUtils.blendARGB(surface2, 0xFFFFFFFF.toInt(), 0.06f), surface2,
                ColorUtils.blendARGB(surface, 0xFF000000.toInt(), 0.45f)),
            floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(rc, r, r, paint)
        paint.shader = null

        // ridges on the surface; 3 per album, rolling with the wall
        val radius = w / 2f
        val spacing = stripStep / 3f
        val shift = pos * stripStep           // surface travel in px
        val cx = w / 2f
        val inset = context.dp(9f)
        val first = floor((-PI / 2 * radius + shift) / spacing).toInt()
        val last = floor((PI / 2 * radius + shift) / spacing).toInt() + 1
        for (i in first..last) {
            val s = i * spacing - shift       // arc length from the front
            val th = s / radius
            if (th < -PI / 2 || th > PI / 2) continue
            val face = cos(th).toFloat()      // 1 facing you, 0 at the edges
            val x = cx + (radius * sin(th)).toFloat()
            stroke.strokeWidth = context.dp(1.2f) + context.dp(1.6f) * face
            val major = Math.floorMod(i, 3) == 0
            val a = (if (major) 210 else 120) * face * face
            stroke.color = ColorUtils.setAlphaComponent(if (major) text else grid, a.toInt().coerceIn(0, 255))
            c.drawLine(x, inset, x, h - inset, stroke)
        }
        // glossy top highlight
        paint.shader = LinearGradient(0f, 0f, 0f, h / 2,
            ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), 34), 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRoundRect(rc.left + r * 0.5f, rc.top + 1, rc.right - r * 0.5f, h / 2, r, r, paint)
        paint.shader = null
        // centre marker
        paint.color = accent
        val mw = context.dp(3f)
        c.drawRoundRect(cx - mw / 2, 0f, cx + mw / 2, context.dp(7f), mw, mw, paint)
        c.drawRoundRect(cx - mw / 2, h - context.dp(7f), cx + mw / 2, h, mw, mw, paint)
        // rim
        stroke.strokeWidth = context.dp(1f)
        stroke.color = ColorUtils.setAlphaComponent(text, if (pressed) 70 else 36)
        c.drawRoundRect(rc.left + 0.5f, rc.top + 0.5f, rc.right - 0.5f, rc.bottom - 0.5f, r, r, stroke)
    }

    // ------------------------------------------------------------------ touch

    private var tracker: VelocityTracker? = null
    private var lastX = 0f
    private var pressed = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                pressed = true
                lastX = e.x
                tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                onStart?.invoke()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                val dx = e.x - lastX
                lastX = e.x
                onTurn?.invoke(-dx / stripStep)     // ridges move with the finger, like the covers
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                pressed = false
                tracker?.addMovement(e)
                tracker?.computeCurrentVelocity(1000)
                val v = -(tracker?.xVelocity ?: 0f) / stripStep
                tracker?.recycle(); tracker = null
                if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
                onRelease?.invoke(v.coerceIn(-60f, 60f))
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()
}
