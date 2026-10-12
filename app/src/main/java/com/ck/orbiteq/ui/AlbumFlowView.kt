package com.ck.orbiteq.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import com.ck.orbiteq.R
import com.ck.orbiteq.library.ArtLoader
import com.ck.orbiteq.library.Group
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 3D album carousel (Cover Flow / Wheel / Stack / Glide) with momentum scrolling,
 * mirror reflections and a flip animation for the centre album.
 */
class AlbumFlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onSelected: ((Int) -> Unit)? = null
    var onCenterTap: ((Int) -> Unit)? = null

    var effect = FlowMath.FLOW
        set(v) { field = v; invalidate() }

    private var albums: List<Group> = emptyList()
    private var position = 0f          // scroll position in album units
    private var lastSelected = -1
    private var open = 0f               // 0 closed .. 1 flipped open
    private var flip = 0f               // 0..180 degrees for the centre album

    private val artSize = 400
    private val reflections = HashMap<Long, Bitmap>()
    private val placeholders = HashMap<Long, Bitmap>()
    private val accent = context.attrColor(R.attr.oAccent)
    private val surface = context.attrColor(R.attr.oSurface)
    private val grid = context.attrColor(R.attr.oGrid)
    private val bg = context.attrColor(R.attr.oBg)
    private val textColor = context.attrColor(R.attr.oText)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val quadMatrix = Matrix()
    private val src = FloatArray(8)
    private val dst = FloatArray(8)
    private val place = FlowMath.Place()
    private val path = Path()
    private val centerRect = RectF()

    private var animator: ValueAnimator? = null
    private var flipAnim: ValueAnimator? = null
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var downPos = 0f
    private var dragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    val selected: Int get() = position.roundToInt().coerceIn(0, max(0, albums.size - 1))
    val isOpen: Boolean get() = open > 0.5f

    fun setAlbums(list: List<Group>, keepIndex: Int = selected) {
        albums = list
        position = keepIndex.coerceIn(0, max(0, list.size - 1)).toFloat()
        lastSelected = -1
        notifySelection()
        invalidate()
    }

    fun scrollTo(index: Int, animate: Boolean = true) {
        if (albums.isEmpty()) return
        val target = index.coerceIn(0, albums.size - 1).toFloat()
        if (!animate) {
            animator?.cancel(); position = target; notifySelection(); invalidate(); return
        }
        animateTo(target, 420L)
    }

    /** Flip the centre album over (true) or back (false). */
    fun setOpen(o: Boolean) {
        flipAnim?.cancel()
        val from = open
        val to = if (o) 1f else 0f
        flipAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = (560 * abs(to - from)).toLong().coerceAtLeast(1)
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                open = it.animatedValue as Float
                flip = open * 180f
                invalidate()
            }
            start()
        }
    }

    /** Screen rectangle of the centre album (to place the song list over it). */
    fun centerCardRect(out: RectF): RectF {
        val card = cardPx()
        val cx = width / 2f
        val cy = cardCenterY()
        out.set(cx - card * 0.68f, cy - card * 0.68f, cx + card * 0.68f, cy + card * 0.68f)
        return out
    }

    private fun wide() = width > height * 1.2f
    private fun cardPx(): Float = if (wide()) height * 0.6f else min(width * 0.52f, height * 0.6f)
    private fun cardCenterY(): Float = if (wide()) height * 0.42f else height * 0.44f
    private fun spacingPx(): Float = cardPx() * (if (effect == FlowMath.FLOW) (if (wide()) 0.45f else 0.38f) else 0.55f)

    private fun notifySelection() {
        val s = selected
        if (s != lastSelected && albums.isNotEmpty()) {
            lastSelected = s
            onSelected?.invoke(s)
        }
    }

    private fun animateTo(target: Float, ms: Long) {
        animator?.cancel()
        val from = position
        animator = ValueAnimator.ofFloat(from, target).apply {
            duration = ms
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                position = it.animatedValue as Float
                notifySelection()
                invalidate()
            }
            start()
        }
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (albums.isEmpty()) return
        val card = cardPx()
        val cx = width / 2f
        val cy = cardCenterY()
        val f = card * 3.6f
        val wide = wide()

        val first = max(0, position.toInt() - 7)
        val last = min(albums.size - 1, position.toInt() + 7)
        val order = (first..last).sortedByDescending { abs(it - position) }

        for (i in order) {
            val d = i - position
            FlowMath.place(d, effect, wide, open, place)
            if (place.alpha <= 0.01f) continue
            val g = albums[i]
            val bmp = art(g)
            val isCenter = abs(d) < 0.5f
            val rot = if (isCenter) flip else 0f
            val showBack = isCenter && flip > 90f

            // reflection first (under the cover)
            if (place.reflect && open < 0.5f) {
                val refl = reflection(g, bmp)
                FlowMath.project(place, rot, card, cx, cy, f, 0.52f, 0.52f + 0.4f, dst)
                drawQuad(canvas, refl, (place.alpha * 255).toInt())
            }

            FlowMath.project(place, rot, card, cx, cy, f, -0.5f, 0.5f, dst)
            if (showBack) {
                drawBack(canvas, place.alpha)
            } else {
                drawQuad(canvas, bmp, (place.alpha * 255).toInt())
                if (isCenter) {
                    // thin rim so the centre album pops
                    edgePaint.color = ColorUtils.setAlphaComponent(textColor, 40)
                    edgePaint.strokeWidth = context.dp(1f)
                    quadPath(); canvas.drawPath(path, edgePaint)
                }
            }
            if (isCenter) {
                centerRect.set(
                    min(min(dst[0], dst[2]), min(dst[4], dst[6])), min(min(dst[1], dst[3]), min(dst[5], dst[7])),
                    max(max(dst[0], dst[2]), max(dst[4], dst[6])), max(max(dst[1], dst[3]), max(dst[5], dst[7])),
                )
            }
        }
    }

    private fun quadPath() {
        path.reset()
        path.moveTo(dst[0], dst[1]); path.lineTo(dst[2], dst[3]); path.lineTo(dst[4], dst[5]); path.lineTo(dst[6], dst[7]); path.close()
    }

    private fun drawQuad(canvas: Canvas, bmp: Bitmap, alpha: Int) {
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        src[0] = 0f; src[1] = 0f; src[2] = w; src[3] = 0f; src[4] = w; src[5] = h; src[6] = 0f; src[7] = h
        if (!quadMatrix.setPolyToPoly(src, 0, dst, 0, 4)) return
        paint.alpha = alpha.coerceIn(0, 255)
        canvas.drawBitmap(bmp, quadMatrix, paint)
    }

    private fun drawBack(canvas: Canvas, alpha: Float) {
        quadPath()
        backPaint.color = surface
        backPaint.alpha = (alpha * 255).toInt()
        canvas.drawPath(path, backPaint)
        edgePaint.color = grid
        edgePaint.strokeWidth = context.dp(1f)
        canvas.drawPath(path, edgePaint)
    }

    private fun art(g: Group): Bitmap {
        ArtLoader.cached(g.albumId, artSize)?.let { return it }
        ArtLoader.load(context, g.albumId, artSize, g.tracks.firstOrNull()?.uri) { b ->
            if (b != null) { reflections.remove(g.albumId); invalidate() }
        }
        return placeholders.getOrPut(g.albumId) { makePlaceholder(g) }
    }

    /** A clean cover for albums without art: accent-tinted square with the album's initial. */
    private fun makePlaceholder(g: Group): Bitmap {
        val s = 256
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val hue = (abs(g.name.hashCode()) % 360).toFloat()
        val base = ColorUtils.HSLToColor(floatArrayOf(hue, 0.45f, 0.32f))
        c.drawColor(base)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = ColorUtils.HSLToColor(floatArrayOf(hue, 0.6f, 0.82f))
        p.style = Paint.Style.STROKE
        p.strokeWidth = 18f
        c.drawCircle(s / 2f, s / 2f, s * 0.26f, p)
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.textSize = s * 0.22f
        p.typeface = Typeface.DEFAULT_BOLD
        val letter = g.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "♪"
        c.drawText(letter, s / 2f, s / 2f - (p.descent() + p.ascent()) / 2f, p)
        return b
    }

    /** Mirror image of the bottom of the cover, fading out (cached per album). */
    private fun reflection(g: Group, bmp: Bitmap): Bitmap {
        reflections[g.albumId]?.let { if (it.width == bmp.width) return it }
        val w = bmp.width
        val h = (bmp.height * 0.4f).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val m = Matrix().apply { preScale(1f, -1f); postTranslate(0f, bmp.height.toFloat()) }
        // flipped copy: the cover's bottom edge lands on the reflection's top row
        c.drawBitmap(bmp, m, null)
        val fade = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fade)
        reflections[g.albumId] = out
        return out
    }

    // ------------------------------------------------------------------ touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (albums.isEmpty()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                downX = e.x; downY = e.y; downPos = position; dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                val dx = e.x - downX
                if (!dragging && abs(dx) > touchSlop && !isOpen) dragging = true
                if (dragging) {
                    position = (downPos - dx / spacingPx()).coerceIn(-0.4f, albums.size - 0.6f)
                    notifySelection()
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val vt = velocity
                vt?.addMovement(e)
                vt?.computeCurrentVelocity(1000)
                val vx = vt?.xVelocity ?: 0f
                velocity?.recycle(); velocity = null
                if (dragging) {
                    dragging = false
                    // momentum: fling a few albums, then settle on one
                    val travel = -vx / spacingPx() * 0.32f
                    val target = (position + travel).roundToInt().coerceIn(0, albums.size - 1).toFloat()
                    val dist = abs(target - position)
                    animateTo(target, (260 + dist * 90).toLong().coerceAtMost(1400))
                } else {
                    tap(e.x, e.y)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                velocity?.recycle(); velocity = null
                if (dragging) { dragging = false; animateTo(position.roundToInt().toFloat().coerceIn(0f, albums.size - 1f), 260) }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun tap(x: Float, y: Float) {
        if (isOpen) return
        if (centerRect.contains(x, y)) {
            performClick()
            onCenterTap?.invoke(selected)
            return
        }
        // tapped a side album: bring it to the middle
        val dir = if (x < width / 2f) -1 else 1
        val dist = abs(x - width / 2f) / max(1f, cardPx() * (if (effect == FlowMath.FLOW) 0.4f else 0.8f))
        val steps = max(1, min(4, dist.toInt()))
        scrollTo(selected + dir * steps)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
