package com.ck.orbiteq.ui

import android.animation.ValueAnimator
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import com.google.android.material.slider.Slider
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Swipe left / right anywhere to move between the dock tabs, with an animated transition.
 * The same transition plays when a dock tab is tapped.
 *
 * Things that already move sideways (album wall, thumb wheel, sliders, EQ curve, chip rows,
 * anything horizontally scrollable) keep the gesture; the swipe only starts elsewhere.
 *
 * Effects:
 *  LIQUID - like the iPhone fold animation: the old tab melts into a heavy blur while the new
 *           one forms out of the same blur and sharpens (no sliding).
 *  SLIDE  - pages side by side.   DEPTH - old page sinks back, new slides over it.
 *  CUBE   - pages turn like the faces of a cube.   OFF - dock only.
 */
class PageSwiper(
    private val pages: List<View>,
    private val current: () -> Int,
    private val enabled: () -> Boolean,
    private val effect: () -> Int,
    /** Send a cancel to the views under the finger once the swipe takes over. */
    private val cancelChildren: (MotionEvent) -> Unit,
    /** Make [page] the shown tab (selects the dock item). */
    private val commit: (Int) -> Unit,
) {
    companion object {
        const val LIQUID = 0
        const val SLIDE = 1
        const val DEPTH = 2
        const val CUBE = 3
        const val OFF = 4
        val NAMES = arrayOf("Liquid fade", "Slide", "Depth", "Cube", "Off")
    }

    private enum class State { NONE, MAYBE, DRAG }

    private var state = State.NONE
    private var downX = 0f
    private var downY = 0f
    private var from = -1
    private var to = -1
    private var progress = 0f
    private var tracker: VelocityTracker? = null
    private var anim: ValueAnimator? = null
    private val ease = PathInterpolator(0.2f, 0f, 0f, 1f)

    val busy get() = anim != null || state == State.DRAG

    // ------------------------------------------------------------------ touch

    /** Call from Activity.dispatchTouchEvent; true = the swipe consumed the event. */
    fun onTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                state = State.NONE
                if (!enabled() || effect() == OFF || anim != null) return false
                val page = pages.getOrNull(current()) ?: return false
                if (blockedAt(page, ev.rawX, ev.rawY)) return false
                downX = ev.rawX; downY = ev.rawY
                state = State.MAYBE
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (state == State.MAYBE) {
                    val slop = ViewConfiguration.get(pages[0].context).scaledTouchSlop
                    if (abs(dx) > slop * 1.5f && abs(dx) > abs(dy) * 1.6f) {
                        state = State.DRAG
                        from = current()
                        val c = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                        cancelChildren(c)
                        c.recycle()
                    } else if (abs(dy) > slop) {
                        state = State.NONE        // a vertical scroll: leave it alone
                    }
                    return state == State.DRAG
                }
                if (state == State.DRAG) {
                    drag(ev.rawX - downX)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (state == State.DRAG) {
                    tracker?.addMovement(ev)
                    tracker?.computeCurrentVelocity(1000)
                    val vx = tracker?.xVelocity ?: 0f
                    release(vx, ev.actionMasked == MotionEvent.ACTION_UP)
                    tracker?.recycle(); tracker = null
                    state = State.NONE
                    return true
                }
                tracker?.recycle(); tracker = null
                state = State.NONE
            }
        }
        return false
    }

    private fun width() = max(1, pages[0].width).toFloat()

    private fun drag(dx: Float) {
        // finger moving left = next tab
        val dir = if (dx < 0) 1 else -1
        val t = from + dir
        if (t !in pages.indices) {
            // no tab that way: a little resistance on the current page
            if (to >= 0) { reset(pages[to]); pages[to].visibility = View.GONE; to = -1 }
            pages[from].translationX = dx * 0.12f
            progress = 0f
            return
        }
        if (t != to) {
            if (to >= 0) { reset(pages[to]); pages[to].visibility = View.GONE }
            to = t
            pages[from].translationX = 0f
            pages[to].visibility = View.VISIBLE
        }
        progress = (abs(dx) / width()).coerceIn(0f, 1f)
        render(progress)
    }

    private fun release(vx: Float, up: Boolean) {
        if (to < 0) {
            // pulled past the end: spring back
            val v = pages[from]
            v.animate().translationX(0f).setDuration(220).setInterpolator(ease).start()
            return
        }
        val dir = if (to > from) 1 else -1
        val fling = -vx * dir > 600f          // quick flick toward the new tab
        val back = -vx * dir < -600f          // quick flick back
        val go = up && !back && (progress > 0.5f || fling)
        settle(if (go) 1f else 0f) { if (go) finish(to) else cancel() }
    }

    // ------------------------------------------------------------------ tapping the dock

    /** Animate from the shown tab to [target] (dock tap). [done] makes [target] the shown tab. */
    fun animateTo(target: Int, done: () -> Unit) {
        val f = current()
        if (effect() == OFF || target == f || target !in pages.indices || anim != null || state == State.DRAG) { done(); return }
        from = f; to = target; progress = 0f
        pages[to].visibility = View.VISIBLE
        render(0f)
        settle(1f) {
            done()
            cleanup()
            onlyShown(current())
        }
    }

    // ------------------------------------------------------------------ animation

    private fun settle(end: Float, after: () -> Unit) {
        val start = progress
        val liquid = effect() == LIQUID
        val dist = abs(end - start)
        anim = ValueAnimator.ofFloat(start, end).apply {
            duration = ((if (liquid) 460L else 320L) * max(0.35f, dist)).toLong()
            interpolator = ease
            addUpdateListener { progress = it.animatedValue as Float; render(progress) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    anim = null
                    after()
                }
            })
            start()
        }
    }

    private fun finish(page: Int) {
        commit(page)
        cleanup()
        onlyShown(page)
    }

    private fun cancel() {
        cleanup()
        onlyShown(current())
    }

    /** Whatever happened, end with exactly one tab on screen. */
    private fun onlyShown(page: Int) {
        pages.forEachIndexed { i, v -> v.visibility = if (i == page) View.VISIBLE else View.GONE }
    }

    private fun cleanup() {
        pages.forEach { reset(it) }
        to = -1
    }

    private fun reset(v: View) {
        v.alpha = 1f; v.scaleX = 1f; v.scaleY = 1f
        v.translationX = 0f; v.translationZ = 0f; v.rotationY = 0f
        if (Build.VERSION.SDK_INT >= 31) v.setRenderEffect(null)
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun blur(v: View, px: Float) {
        if (Build.VERSION.SDK_INT < 31) return
        v.setRenderEffect(if (px < 0.6f) null else RenderEffect.createBlurEffect(px, px, Shader.TileMode.CLAMP))
    }

    /** Draw the transition at [p] (0 = old tab, 1 = new tab). */
    private fun render(p: Float) {
        val a = pages.getOrNull(from) ?: return
        val b = pages.getOrNull(to) ?: return
        val w = width()
        val dir = if (to > from) 1f else -1f
        val d = a.resources.displayMetrics.density
        when (effect()) {
            LIQUID -> {
                // melt: both layouts are heavily blurred in the middle and overlap
                val maxBlur = 30f * d
                a.alpha = 1f - smooth(0.12f, 0.78f, p)
                b.alpha = smooth(0.22f, 0.88f, p)
                val sa = 1f - 0.03f * p
                val sb = 0.97f + 0.03f * p
                a.scaleX = sa; a.scaleY = sa; b.scaleX = sb; b.scaleY = sb
                blur(a, maxBlur * min(1f, p / 0.5f))
                blur(b, maxBlur * min(1f, (1f - p) / 0.5f))
                b.translationZ = 1f
            }
            SLIDE -> {
                a.translationX = -dir * w * p
                b.translationX = dir * w * (1f - p)
            }
            DEPTH -> {
                val s = 1f - 0.12f * p
                a.scaleX = s; a.scaleY = s
                a.alpha = 1f - 0.7f * p
                b.translationX = dir * w * (1f - p)
                b.translationZ = 2f
            }
            CUBE -> {
                val cam = 12000f * d
                a.cameraDistance = cam; b.cameraDistance = cam
                a.pivotX = if (dir > 0) w else 0f; a.pivotY = a.height / 2f
                b.pivotX = if (dir > 0) 0f else w; b.pivotY = b.height / 2f
                a.translationX = -dir * w * p
                b.translationX = dir * w * (1f - p)
                a.rotationY = -dir * 90f * p
                b.rotationY = dir * 90f * (1f - p)
            }
        }
    }

    // ------------------------------------------------------------------ what keeps its own swipe

    private val tmp = Rect()
    private val loc = IntArray(2)

    private fun blockedAt(root: View, x: Float, y: Float): Boolean {
        var v: View? = deepest(root, x, y) ?: return false
        while (v != null) {
            if (ownsSideways(v)) return true
            if (v === root) break
            v = v.parent as? View
        }
        return false
    }

    private fun ownsSideways(v: View): Boolean =
        v is AlbumFlowView || v is JogWheelView || v is Slider || v is EqCurveView ||
            v is SpeakerMapView || v.canScrollHorizontally(1) || v.canScrollHorizontally(-1)

    private fun deepest(v: View, x: Float, y: Float): View? {
        if (v.visibility != View.VISIBLE) return null
        v.getLocationOnScreen(loc)
        tmp.set(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        if (!tmp.contains(x.toInt(), y.toInt())) return null
        if (v is ViewGroup) {
            for (i in v.childCount - 1 downTo 0) {
                deepest(v.getChildAt(i), x, y)?.let { return it }
            }
        }
        return v
    }
}
