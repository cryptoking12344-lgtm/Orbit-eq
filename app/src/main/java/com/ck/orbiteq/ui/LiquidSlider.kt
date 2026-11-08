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
import androidx.core.graphics.ColorUtils
import com.ck.orbiteq.R
import com.google.android.material.slider.Slider
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * The album scrub bar's look for every slider: a slim track at rest that swells into a
 * see-through liquid-glass capsule while you hold it, stretching and trailing with your speed.
 * Ticks the vibration motor as the value passes each step (firmer slow, lighter fast).
 *
 * It is still a Material [Slider] underneath (touch, steps, accessibility, listeners all work
 * as before); only the drawing is replaced. The Retro theme keeps its classic Winamp look.
 */
class LiquidSlider @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = com.google.android.material.R.attr.sliderStyle,
) : Slider(context, attrs, defStyleAttr) {

    private val retro = context.isRetro()
    private val accent = context.attrColor(R.attr.oAccent)
    private val text = context.attrColor(R.attr.oText)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rc = RectF()

    private var held = false
    private var k = 0f                 // 0 resting .. 1 held
    private var speed = 0f             // fraction of the range per second, smoothed (signed)
    private var lastFrameNs = 0L
    private var lastFrac = -1f
    private var lastFracNs = 0L

    // haptics
    private var lastNotch = Int.MIN_VALUE
    private var lastTickNs = 0L

    init {
        addOnChangeListener { _, v, fromUser ->
            val f = frac(v)
            val now = System.nanoTime()
            if (lastFrac >= 0f) {
                val dt = ((now - lastFracNs) / 1e9f).coerceAtLeast(0.004f)
                speed += ((f - lastFrac) / dt - speed) * 0.35f
            }
            lastFrac = f; lastFracNs = now
            if (fromUser) tickIfCrossed(v, now)
            invalidate()
        }
    }

    /** One tick per step; continuous sliders tick every 1/20 of their range. */
    private fun notchSize(): Float {
        val range = valueTo - valueFrom
        return if (stepSize > 0f) max(stepSize, range / 40f) else range / 20f
    }

    private fun tickIfCrossed(v: Float, now: Long) {
        val notch = floor((v - valueFrom) / notchSize() + 0.5f).toInt()
        if (lastNotch == Int.MIN_VALUE) { lastNotch = notch; return }
        if (notch != lastNotch && now - lastTickNs > 30_000_000L) {
            Haptics.tick(context, 0.8f - 0.55f * min(abs(speed) / 2.5f, 1f))
            lastTickNs = now
        }
        lastNotch = notch
    }

    private fun frac(v: Float) = ((v - valueFrom) / max(1e-6f, valueTo - valueFrom)).coerceIn(0f, 1f)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                held = true
                lastNotch = floor((value - valueFrom) / notchSize() + 0.5f).toInt()
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { held = false; invalidate() }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        if (retro) { super.onDraw(canvas); return }

        // animate the swell and the speed stretch
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0.016f else ((now - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.05f)
        lastFrameNs = now
        k += ((if (held && isEnabled) 1f else 0f) - k) * min(1f, dt * 14f)
        if (!held) speed *= max(0f, 1f - dt * 8f)
        val animating = abs((if (held) 1f else 0f) - k) > 0.005f || abs(speed) > 0.01f
        if (!animating && !held) { if (k < 0.01f) k = 0f; lastFrameNs = 0L }

        val pad = trackSidePadding.toFloat()
        val w = width - 2 * pad
        val cy = height / 2f
        val f = frac(value)
        val x = pad + w * f
        val alphaMul = if (isEnabled) 1f else 0.4f

        // track
        val th = context.dp(5f + 4f * k)
        rc.set(pad, cy - th / 2, pad + w, cy + th / 2)
        paint.shader = null
        paint.color = ColorUtils.setAlphaComponent(text, ((30 + 18 * k) * alphaMul).toInt())
        canvas.drawRoundRect(rc, th / 2, th / 2, paint)
        // fill: from the start, or from zero when the range goes below and above zero
        val zero = if (valueFrom < 0f && valueTo > 0f) pad + w * frac(0f) else pad
        rc.set(min(zero, x), cy - th / 2, max(zero, x), cy + th / 2)
        paint.color = ColorUtils.setAlphaComponent(accent, (255 * alphaMul).toInt())
        if (rc.width() > 0.5f) canvas.drawRoundRect(rc, th / 2, th / 2, paint)

        // thumb: a small glass bead at rest, a stretched liquid capsule while held
        val stretch = min(abs(speed) / 3f, 1f) * k
        val baseW = context.dp(18f + 26f * k)
        val tw = baseW * (1f + 0.5f * stretch)
        val tH = context.dp(18f + 8f * k) * (1f - 0.18f * stretch)
        val lag = -sign(speed) * stretch * tw * 0.12f
        val cx = (x + lag).coerceIn(pad - tw / 2 + context.dp(4f), pad + w + tw / 2 - context.dp(4f))
        rc.set(cx - tw / 2, cy - tH / 2, cx + tw / 2, cy + tH / 2)
        drawGlass(canvas, rc, tH / 2, k, alphaMul)

        if (animating || held) postInvalidateOnAnimation()
    }

    /** Same liquid glass as the album bar: tinted body, top shine, bright rim, soft shadow. */
    private fun drawGlass(c: Canvas, r: RectF, rad: Float, k: Float, alphaMul: Float) {
        // shadow
        paint.shader = null
        paint.color = ColorUtils.setAlphaComponent(0xFF000000.toInt(), ((50 + 30 * k) * alphaMul).toInt())
        c.drawRoundRect(r.left, r.top + context.dp(1.5f), r.right, r.bottom + context.dp(2.5f), rad, rad, paint)
        // body: solid accent bead at rest, see-through tinted glass when held
        val solid = ColorUtils.blendARGB(accent, 0xFFFFFFFF.toInt(), 0.15f)
        val glass = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(accent, 0xFFFFFFFF.toInt(), 0.3f), 110)
        val body = ColorUtils.blendARGB(solid, glass, k)
        paint.color = ColorUtils.setAlphaComponent(body, (colorAlpha(body) * alphaMul).toInt())
        c.drawRoundRect(r, rad, rad, paint)
        // top shine
        paint.shader = LinearGradient(0f, r.top, 0f, r.centerY(),
            ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), ((70 + 70 * k) * alphaMul).toInt()), 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRoundRect(r.left + rad * 0.25f, r.top + context.dp(1.5f), r.right - rad * 0.25f, r.centerY(), rad, rad, paint)
        paint.shader = null
        // rim
        stroke.strokeWidth = context.dp(1f)
        stroke.color = ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), ((60 + 70 * k) * alphaMul).toInt())
        c.drawRoundRect(r, rad, rad, stroke)
    }

    private fun colorAlpha(c: Int) = (c ushr 24) and 0xFF
}
