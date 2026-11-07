package com.ck.orbiteq.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import androidx.core.graphics.ColorUtils
import com.ck.orbiteq.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * A wheel that scrolls the album wall.
 *  STRIP = a ridged thumb wheel seen from the side (drag left / right).
 *  DIAL  = a round jog dial you spin in circles.
 * It reports how many albums to move ([onTurn]) and the release speed ([onRelease], albums/s).
 * What it shows always follows the wall ([setPosition]), so it keeps spinning during a fling.
 */
class JogWheelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        const val STRIP = 0
        const val DIAL = 1
        /** Dial: degrees of turn per album (12 albums per full circle). */
        const val DIAL_DEG = 30f
    }

    var style = STRIP
        set(v) { field = v; requestLayout(); invalidate() }

    var onStart: (() -> Unit)? = null
    var onTurn: ((Float) -> Unit)? = null
    var onRelease: ((Float) -> Unit)? = null
    var onCenterTap: (() -> Unit)? = null

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
        if (style == DIAL) {
            val s = context.dp(116f).toInt()
            setMeasuredDimension(resolveSize(s, widthMeasureSpec), resolveSize(s, heightMeasureSpec))
        } else {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(w, resolveSize(context.dp(44f).toInt(), heightMeasureSpec))
        }
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        if (style == DIAL) drawDial(canvas) else drawStrip(canvas)
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

    private fun drawDial(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f - context.dp(3f)
        val ang = Math.toRadians((pos * DIAL_DEG).toDouble())

        // shadow + body with a soft light from the top-left
        paint.color = ColorUtils.setAlphaComponent(0xFF000000.toInt(), 70)
        c.drawCircle(cx, cy + context.dp(3f), r, paint)
        paint.shader = RadialGradient(cx - r * 0.35f, cy - r * 0.4f, r * 1.6f,
            ColorUtils.blendARGB(surface2, 0xFFFFFFFF.toInt(), 0.10f),
            ColorUtils.blendARGB(surface, 0xFF000000.toInt(), 0.35f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, paint)
        paint.shader = null

        // knurled ring: 48 grooves that turn with the wall
        val n = 48
        for (i in 0 until n) {
            val a = ang + i * 2 * PI / n
            val sa = sin(a).toFloat(); val ca = cos(a).toFloat()
            val light = (0.55f + 0.45f * (-sa * 0.6f - ca * 0.8f).coerceIn(-1f, 1f))
            stroke.strokeWidth = context.dp(if (i % 4 == 0) 2f else 1.2f)
            stroke.color = ColorUtils.setAlphaComponent(if (i % 4 == 0) text else grid, (70 + 120 * light).toInt().coerceIn(0, 255))
            c.drawLine(cx + sa * r * 0.80f, cy - ca * r * 0.80f, cx + sa * r * 0.96f, cy - ca * r * 0.96f, stroke)
        }
        // inner face
        val inner = r * 0.72f
        paint.shader = RadialGradient(cx, cy - inner * 0.3f, inner * 1.3f,
            surface2, ColorUtils.blendARGB(surface, 0xFF000000.toInt(), 0.2f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, inner, paint)
        paint.shader = null
        stroke.strokeWidth = context.dp(1f)
        stroke.color = ColorUtils.setAlphaComponent(text, 40)
        c.drawCircle(cx, cy, inner, stroke)

        // finger dimple that orbits as it turns
        val dr = inner * 0.66f
        val dx = cx + sin(ang).toFloat() * dr; val dy = cy - cos(ang).toFloat() * dr
        paint.shader = RadialGradient(dx, dy, context.dp(11f),
            ColorUtils.setAlphaComponent(0xFF000000.toInt(), 90), ColorUtils.setAlphaComponent(accent, 160), Shader.TileMode.CLAMP)
        c.drawCircle(dx, dy, context.dp(10f), paint)
        paint.shader = null

        // centre button (tap = open the album)
        paint.color = ColorUtils.setAlphaComponent(accent, if (centerDown) 255 else 200)
        c.drawCircle(cx, cy, inner * 0.28f, paint)
        // fixed pointer at the top
        paint.color = accent
        c.drawCircle(cx, cy - r - context.dp(0f) + context.dp(2.5f), context.dp(2.5f), paint)
    }

    // ------------------------------------------------------------------ touch

    private var tracker: VelocityTracker? = null
    private var lastX = 0f
    private var lastAng = 0.0
    private var angVel = 0f
    private var lastNs = 0L
    private var pressed = false
    private var centerDown = false
    private var moved = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cx = width / 2f; val cy = height / 2f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                pressed = true; moved = 0f
                if (style == DIAL) {
                    val d = hypot(e.x - cx, e.y - cy)
                    val r = min(width, height) / 2f
                    if (d < r * 0.22f) { centerDown = true; invalidate(); return true }
                    if (d > r * 1.05f) { pressed = false; return false }
                    lastAng = atan2((e.y - cy).toDouble(), (e.x - cx).toDouble())
                    angVel = 0f; lastNs = System.nanoTime()
                } else {
                    lastX = e.x
                    tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                }
                onStart?.invoke()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (centerDown) return true
                if (style == DIAL) {
                    val a = atan2((e.y - cy).toDouble(), (e.x - cx).toDouble())
                    var da = a - lastAng
                    if (da > PI) da -= 2 * PI
                    if (da < -PI) da += 2 * PI
                    lastAng = a
                    // ignore the wobbly middle where the angle jumps around
                    if (hypot(e.x - cx, e.y - cy) < min(width, height) * 0.12f) return true
                    val albums = (Math.toDegrees(da) / DIAL_DEG).toFloat()   // clockwise = next
                    val now = System.nanoTime()
                    val dt = ((now - lastNs) / 1e9f).coerceAtLeast(0.001f)
                    lastNs = now
                    angVel += (albums / dt - angVel) * 0.4f
                    moved += abs(albums)
                    onTurn?.invoke(albums)
                } else {
                    tracker?.addMovement(e)
                    val dx = e.x - lastX
                    lastX = e.x
                    moved += abs(dx) / stripStep
                    onTurn?.invoke(-dx / stripStep)     // ridges move with the finger, like the covers
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                pressed = false
                if (centerDown) {
                    centerDown = false; invalidate()
                    if (e.actionMasked == MotionEvent.ACTION_UP && hypot(e.x - cx, e.y - cy) < min(width, height) * 0.3f) {
                        performClick(); onCenterTap?.invoke()
                    }
                    return true
                }
                val v = if (style == DIAL) {
                    // finger stopped before lifting: no fling
                    if (System.nanoTime() - lastNs > 60_000_000L) 0f else angVel
                } else {
                    tracker?.addMovement(e)
                    tracker?.computeCurrentVelocity(1000)
                    -(tracker?.xVelocity ?: 0f) / stripStep
                }
                tracker?.recycle(); tracker = null
                onRelease?.invoke(v.coerceIn(-60f, 60f))
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()
}
