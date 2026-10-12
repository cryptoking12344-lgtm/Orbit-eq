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
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

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

    var effect = FlowMath.N8
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

    // ---- motion physics (albums, albums/second)
    private enum class Mode { IDLE, DRAG, FLING, SPRING, SCRUB }
    private var mode = Mode.IDLE
    private var vel = 0f                // scroll speed, albums per second
    private var lean = 0f               // smoothed speed used for the covers' lean
    private var target = 0f             // spring target
    private var stiffness = 110f
    private var lastFrameNs = 0L
    private var lastDragPos = 0f
    private var lastDragNs = 0L
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val frame = object : Runnable {
        override fun run() { step() }
    }
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
        val t = index.coerceIn(0, albums.size - 1).toFloat()
        if (!animate) {
            mode = Mode.IDLE; vel = 0f; lean = 0f; position = t; notifySelection(); invalidate(); return
        }
        springTo(t, 70f)
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
    /** How far the finger travels to move one album. */
    private fun spacingPx(): Float = cardPx() * when (effect) {
        FlowMath.N8 -> if (wide()) 0.46f else 0.40f
        FlowMath.FLOW -> if (wide()) 0.45f else 0.38f
        else -> 0.55f
    }

    private fun notifySelection() {
        val s = selected
        if (s != lastSelected && albums.isNotEmpty()) {
            if (lastSelected >= 0 && mode != Mode.IDLE && abs(vel) < 12f) {
                // a soft click per album, like a wheel detent (skipped while racing)
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            }
            lastSelected = s
            onSelected?.invoke(s)
        }
    }

    // ------------------------------------------------------------------ physics

    /** Friction for flings: a hard flick races through many albums, a gentle one moves a few. */
    private val friction = 2.4f

    private fun startLoop() {
        if (lastFrameNs == 0L) {
            lastFrameNs = System.nanoTime()
            postOnAnimation(frame)
        }
    }

    private fun springTo(t: Float, k: Float = 110f) {
        target = t
        stiffness = k
        mode = Mode.SPRING
        startLoop()
    }

    private fun maxPos() = max(0, albums.size - 1).toFloat()

    private fun step() {
        val now = System.nanoTime()
        val dt = ((now - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.05f)
        lastFrameNs = now
        when (mode) {
            Mode.FLING -> {
                position += vel * dt
                vel *= exp(-friction * dt)
                val outOfRange = position < 0f || position > maxPos()
                if (outOfRange) {
                    // ran past the first/last album: bounce back
                    springTo(position.coerceIn(0f, maxPos()), 160f)
                } else if (abs(vel) < 2.2f) {
                    // where it would naturally stop -> settle exactly on an album
                    val stop = (position + vel / friction).roundToInt().toFloat().coerceIn(0f, maxPos())
                    springTo(stop)
                }
            }
            Mode.SPRING -> {
                val c = 2f * sqrt(stiffness)
                val acc = -stiffness * (position - target) - c * vel
                vel += acc * dt
                position += vel * dt
                if (abs(position - target) < 0.0008f && abs(vel) < 0.02f) {
                    position = target; vel = 0f; mode = Mode.IDLE
                }
            }
            Mode.DRAG -> {
                // finger is still: let the measured speed fade out
                if (now - lastDragNs > 40_000_000L) vel *= exp(-12f * dt)
            }
            Mode.SCRUB -> {
                // the wall chases the finger on the bar; its speed drives the lean like a swipe
                val prev = position
                // moving: follow the finger exactly; finger resting: settle onto the nearest album
                val settle = 1f - min(abs(vel) / 3f, 1f)
                val aim = scrubTarget + (scrubTarget.roundToInt() - scrubTarget) * settle
                position += (aim - position) * min(1f, dt * 16f)
                val v = (position - prev) / dt
                vel += (v - vel) * min(1f, dt * 20f)
            }
            Mode.IDLE -> vel = 0f
        }
        // the bar swells into a liquid capsule while held, and relaxes after
        scrubT += ((if (mode == Mode.SCRUB) 1f else 0f) - scrubT) * min(1f, dt * 12f)
        if (mode != Mode.SCRUB && scrubT < 0.01f) scrubT = 0f
        wobble += (vel - wobble) * min(1f, dt * 7f)
        // the lean follows the speed smoothly (covers settle flat when they stop)
        lean += (vel - lean) * min(1f, dt * 9f)
        notifySelection()
        invalidate()
        if (mode == Mode.IDLE && abs(lean) < 0.05f && scrubT == 0f) {
            lean = 0f
            lastFrameNs = 0L
        } else {
            postOnAnimation(frame)
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
            FlowMath.place(d, effect, wide, open, place, lean)
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
        drawPositionBar(canvas)
    }

    // ------------------------------------------------------------------ liquid scrub bar

    private var scrubT = 0f            // 0 resting slim bar .. 1 held liquid capsule
    private var scrubTarget = 0f
    private var wobble = 0f            // lagging speed: makes the capsule stretch and settle like liquid
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val pill = RectF()

    private fun barPad() = context.dp(28f)
    private fun barY() = height - context.dp(14f)
    private fun barThumbW(): Float = max(context.dp(24f), (width - 2 * barPad()) / albums.size.toFloat()).coerceAtMost(context.dp(80f))
    /** Finger x on the bar -> scroll position (fractional album). */
    private fun barPos(x: Float): Float {
        val pad = barPad(); val w = width - 2 * pad; val tw = barThumbW()
        val frac = ((x - pad - tw / 2f) / max(1f, w - tw)).coerceIn(0f, 1f)
        return frac * maxPos()
    }
    private fun onBar(y: Float) = albums.size >= 2 && !isOpen && y > height - context.dp(46f)

    private fun drawPositionBar(canvas: Canvas) {
        if (albums.size < 2 || open > 0.5f) return
        val k = scrubT
        val pad = barPad()
        val y = barY()
        val w = width - 2 * pad
        val frac = (position / maxPos()).coerceIn(0f, 1f)
        val tw = barThumbW()
        val cx = pad + tw / 2f + (w - tw) * frac

        // track: slim line at rest, a frosted groove while held
        val trackH = context.dp(3f + 5f * k)
        pill.set(pad, y - trackH / 2, pad + w, y + trackH / 2)
        glassPaint.shader = null
        glassPaint.color = ColorUtils.setAlphaComponent(textColor, (28 + 22 * k).toInt())
        canvas.drawRoundRect(pill, trackH / 2, trackH / 2, glassPaint)

        // thumb: accent pill at rest -> liquid glass capsule that stretches with speed
        val stretch = min(abs(wobble) / 25f, 1f) * k
        val baseW = tw + (context.dp(44f) - tw).coerceAtLeast(0f) * k
        val thW = baseW * (1f + 0.55f * stretch)
        val thH = context.dp(3f + 23f * k) * (1f - 0.22f * stretch)
        // the capsule trails behind the motion a little, like a drop being dragged
        val lag = -sign(wobble) * stretch * thW * 0.12f
        pill.set(cx - thW / 2 + lag, y - thH / 2, cx + thW / 2 + lag, y + thH / 2)
        val r = thH / 2
        val active = min(1f, 0.35f + abs(lean) / 6f + k)
        if (k < 0.02f) {
            glassPaint.color = ColorUtils.setAlphaComponent(accent, (255 * active).toInt())
            canvas.drawRoundRect(pill, r, r, glassPaint)
        } else {
            drawGlass(canvas, pill, r, k)
        }

        // letter bubble above the thumb (only while using the bar)
        if (k > 0.02f) {
            val g = albums.getOrNull(selected) ?: return
            val ch = g.name.trim().firstOrNull()?.uppercaseChar()
            val letter = when { ch == null -> "#"; ch.isLetter() -> ch.toString(); else -> "#" }
            val size = context.dp(64f) * (0.6f + 0.4f * k)
            val by = y - context.dp(26f) - size / 2 - context.dp(10f) * k
            val bx = cx.coerceIn(size / 2 + context.dp(6f), width - size / 2 - context.dp(6f))
            pill.set(bx - size / 2, by - size / 2, bx + size / 2, by + size / 2)
            drawGlass(canvas, pill, size / 2, k)
            letterPaint.color = ColorUtils.setAlphaComponent(textColor, (255 * k).toInt())
            letterPaint.textSize = size * 0.5f
            canvas.drawText(letter, bx, by - (letterPaint.descent() + letterPaint.ascent()) / 2f, letterPaint)
        }
    }

    /** Apple-style liquid glass: frosted tint, bright rim, a soft highlight on top. */
    private fun drawGlass(canvas: Canvas, rc: RectF, r: Float, k: Float) {
        val a = k.coerceIn(0f, 1f)
        // soft shadow under the glass
        glassPaint.shader = null
        glassPaint.color = ColorUtils.setAlphaComponent(0xFF000000.toInt(), (60 * a).toInt())
        canvas.drawRoundRect(rc.left, rc.top + context.dp(2f), rc.right, rc.bottom + context.dp(3f), r, r, glassPaint)
        // body: accent-tinted, mostly see-through
        glassPaint.color = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(accent, 0xFFFFFFFF.toInt(), 0.25f), (95 * a).toInt())
        canvas.drawRoundRect(rc, r, r, glassPaint)
        // top highlight
        glassPaint.shader = LinearGradient(0f, rc.top, 0f, rc.centerY(),
            ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), (120 * a).toInt()), 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rc.left + r * 0.18f, rc.top + context.dp(1.5f), rc.right - r * 0.18f, rc.centerY(), r, r, glassPaint)
        glassPaint.shader = null
        // rim
        glassStroke.strokeWidth = context.dp(1f)
        glassStroke.color = ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), (110 * a).toInt())
        canvas.drawRoundRect(rc, r, r, glassStroke)
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
            MotionEvent.ACTION_DOWN -> if (onBar(e.y)) {
                // grab the bar: the wall follows the finger, letters pop up
                mode = Mode.SCRUB
                scrubbing = true
                scrubTarget = barPos(e.x)
                parent?.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                startLoop()
                return true
            } else {
                // catch the moving wall under the finger
                val wasMoving = mode == Mode.FLING || (mode == Mode.SPRING && abs(vel) > 1.5f)
                mode = Mode.DRAG
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                downX = e.x; downY = e.y; downPos = position
                dragging = wasMoving          // a catch is never a tap
                caught = wasMoving
                vel = 0f
                lastDragPos = position; lastDragNs = System.nanoTime()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (scrubbing) {
                scrubTarget = barPos(e.x)
                startLoop()
                return true
            } else {
                velocity?.addMovement(e)
                val dx = e.x - downX
                if (!dragging && abs(dx) > touchSlop && !isOpen) {
                    dragging = true
                    downX = e.x; downPos = position
                }
                if (dragging && !isOpen) {
                    var p = downPos - (e.x - downX) / spacingPx()
                    // rubber band past the first / last album
                    val mx = maxPos()
                    if (p < 0f) p = -rubber(-p) else if (p > mx) p = mx + rubber(p - mx)
                    val now = System.nanoTime()
                    val dt = (now - lastDragNs) / 1e9f
                    if (dt > 0.004f) {
                        val v = (p - lastDragPos) / dt
                        vel += (v - vel) * 0.5f
                        lastDragPos = p; lastDragNs = now
                    }
                    position = p
                    notifySelection()
                    startLoop()
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (scrubbing) {
                scrubbing = false
                parent?.requestDisallowInterceptTouchEvent(false)
                // keep the momentum a moment, then land on the album under the thumb
                springTo(scrubTarget.roundToInt().toFloat().coerceIn(0f, maxPos()), 90f)
                return true
            } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                parent?.requestDisallowInterceptTouchEvent(false)
                val vt = velocity
                vt?.addMovement(e)
                vt?.computeCurrentVelocity(1000, maxFling)
                val vx = vt?.xVelocity ?: 0f
                velocity?.recycle(); velocity = null
                if (dragging && !isOpen) {
                    dragging = false
                    release(-vx / spacingPx())
                } else if (caught) {
                    springTo(position.roundToInt().toFloat().coerceIn(0f, maxPos()))
                } else {
                    mode = Mode.IDLE
                    tap(e.x, e.y)
                }
                caught = false
                return true
            } else {
                parent?.requestDisallowInterceptTouchEvent(false)
                velocity?.recycle(); velocity = null
                dragging = false; caught = false
                springTo(position.roundToInt().toFloat().coerceIn(0f, maxPos()))
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private var caught = false
    private var scrubbing = false
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()

    /** Past the ends the wall resists more the further you pull (max ~0.6 album). */
    private fun rubber(over: Float) = 0.6f * (1f - 1f / (over * 1.6f + 1f))

    /**
     * Finger lifted at [v] albums/second.
     * Slow: settle on the nearest album. Medium: glide a few. Hard: race through many,
     * then ease down and land exactly on one.
     */
    private fun release(v: Float) {
        val mx = maxPos()
        if (position < 0f || position > mx) { vel = v * 0.3f; springTo(position.coerceIn(0f, mx), 160f); return }
        if (abs(v) < 1.2f) {
            vel = v
            springTo((position + v * 0.12f).roundToInt().toFloat().coerceIn(0f, mx))
            return
        }
        // a hard flick gets a little extra so it really flies
        val boost = 1f + 0.35f * ((abs(v) - 8f) / 30f).coerceIn(0f, 1f)
        vel = (v * boost).coerceIn(-90f, 90f)
        mode = Mode.FLING
        startLoop()
    }

    private fun tap(x: Float, y: Float) {
        if (isOpen) return
        if (centerRect.contains(x, y)) {
            performClick()
            onCenterTap?.invoke(selected)
            return
        }
        // tapped a side album: bring that one to the middle
        val dir = if (x < width / 2f) -1 else 1
        val card = cardPx()
        val off = abs(x - width / 2f) / card    // in card units
        val first = when (effect) { FlowMath.N8 -> if (wide()) 0.84f else 0.80f; FlowMath.FLOW -> if (wide()) 0.86f else 0.62f; else -> 1f }
        val step = when (effect) { FlowMath.N8 -> if (wide()) 0.40f else 0.30f; FlowMath.FLOW -> if (wide()) 0.32f else 0.12f; else -> 1f }
        val steps = if (off <= first + 0.25f) 1 else 1 + ((off - first - 0.25f) / step).toInt() + 1
        scrollTo(selected + dir * steps.coerceIn(1, 6))
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
