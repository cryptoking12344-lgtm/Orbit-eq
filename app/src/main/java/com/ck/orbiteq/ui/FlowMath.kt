package com.ck.orbiteq.ui

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/**
 * 3D placement for album covers (plain maths, no Android classes).
 * Screen axes: x right, y down, z AWAY from the viewer. Units: card size = 1.
 */
object FlowMath {
    const val FLOW = 0
    const val WHEEL = 1
    const val STACK = 2
    const val GLIDE = 3

    class Place {
        var x = 0f
        var y = 0f
        var z = 0f
        var rotY = 0f     // degrees; + turns the right edge away
        var scale = 1f
        var alpha = 1f
        var reflect = true
    }

    /** d = album index minus scroll position; open = 0..1 (center album flipped open). */
    fun place(d: Float, effect: Int, wide: Boolean, open: Float, out: Place): Place {
        val a = abs(d)
        val sg = sign(d)
        val t = min(a, 1f)
        out.scale = 1f; out.y = 0f; out.reflect = true
        when (effect) {
            WHEEL -> {
                val step = 36f
                val phi = Math.toRadians((d * step).toDouble())
                val r = if (wide) 1.7f else 1.45f
                out.x = (r * sin(phi)).toFloat()
                out.z = (r * (1 - cos(phi))).toFloat()
                out.rotY = d * step
                val lim = if (wide) 3.4f else 2.6f
                out.alpha = if (a > lim) 0f else (1f - a * 0.18f).coerceIn(0f, 1f)
            }
            STACK -> {
                out.reflect = false
                if (d < 0) {
                    out.x = d * 1.9f
                    out.y = -d * 0.12f
                    out.z = 0f
                    out.rotY = 0f
                    out.alpha = (1f + d * 1.2f).coerceIn(0f, 1f)
                } else {
                    out.x = if (wide) d * 0.2f else 0f
                    out.y = if (wide) 0f else -d * 0.09f
                    out.z = d * 0.38f
                    out.rotY = if (wide) -8f * min(d, 1f) else 0f
                    out.alpha = (1f - d * 0.22f).coerceIn(0f, 1f)
                }
            }
            GLIDE -> {
                out.x = d * (if (wide) 1.12f else 1.1f)
                out.z = 0f
                out.rotY = 0f
                out.scale = 1f - 0.28f * t
                val lim = if (wide) 2.6f else 1.6f
                out.alpha = if (a > lim) 0f else 1f - 0.45f * t
            }
            else -> { // FLOW — tilted covers, the classic phone carousel
                val first = if (wide) 0.86f else 0.62f
                val step = if (wide) 0.32f else 0.12f
                out.x = sg * (t * first + max(0f, a - 1f) * step)
                out.z = -0.32f * (1f - t) + 0.28f * t
                out.rotY = -sg * t * (if (wide) 62f else 66f)
                val lim = if (wide) 5.5f else 4.5f
                out.alpha = if (a > lim) 0f else 1f
            }
        }
        // the open (flipped) album comes forward; the rest fade back
        if (open > 0f) {
            if (a < 0.5f) {
                out.z -= 0.55f * open
            } else {
                out.alpha *= 1f - 0.85f * open
            }
        }
        return out
    }

    /**
     * Projects the 4 corners of a card (half-size = 0.5 * scale) placed at [p], plus an extra
     * rotation [extraRot] (used for the flip), into screen points.
     * Corner order: top-left, top-right, bottom-right, bottom-left. f = perspective distance.
     */
    fun project(
        p: Place, extraRot: Float, cardPx: Float, cx: Float, cy: Float, f: Float,
        yTop: Float, yBottom: Float, out: FloatArray,
    ) {
        val th = Math.toRadians((p.rotY + extraRot).toDouble())
        val c = cos(th).toFloat()
        val s = sin(th).toFloat()
        val h = 0.5f * p.scale
        val lx = floatArrayOf(-h, h, h, -h)
        val ly = floatArrayOf(yTop * p.scale, yTop * p.scale, yBottom * p.scale, yBottom * p.scale)
        for (i in 0 until 4) {
            val x = p.x + lx[i] * c
            val z = p.z + lx[i] * s
            val y = p.y + ly[i]
            val zPx = z * cardPx
            val k = f / (f + zPx).coerceAtLeast(f * 0.05f)
            out[2 * i] = cx + x * cardPx * k
            out[2 * i + 1] = cy + y * cardPx * k
        }
    }
}
