package com.ck.orbiteq.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/** Tiny picture of a theme for the Appearance sheet. */
class ThemePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var theme = Appearance.THEME_ORBIT
        set(v) { field = v; invalidate() }
    var accent = Appearance.ACCENT_COLORS[0]
        set(v) { field = v; invalidate() }

    private val heights = floatArrayOf(0.4f, 0.62f, 0.78f, 0.55f, 0.88f, 0.7f, 0.46f, 0.64f, 0.82f, 0.58f, 0.36f, 0.5f, 0.3f, 0.22f)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val d = context.dp(1f)
        p.shader = null
        when (theme) {
            Appearance.THEME_RETRO -> {
                p.color = 0xFF8E8EA2.toInt(); canvas.drawRect(0f, 0f, w, h, p)
                p.color = 0xFF1E1E28.toInt(); canvas.drawRect(3 * d, 3 * d, w, h, p)
                p.color = 0xFF000000.toInt(); canvas.drawRect(3 * d, 3 * d, w - 3 * d, h - 3 * d, p)
            }
            Appearance.THEME_MIDNIGHT -> {
                p.color = 0xFF26262E.toInt(); r.set(0f, 0f, w, h); canvas.drawRoundRect(r, 12 * d, 12 * d, p)
                p.color = 0xFF000000.toInt(); r.set(d, d, w - d, h - d); canvas.drawRoundRect(r, 11 * d, 11 * d, p)
            }
            else -> {
                p.color = 0xFF15151D.toInt(); r.set(0f, 0f, w, h); canvas.drawRoundRect(r, 12 * d, 12 * d, p)
            }
        }
        val n = heights.size
        val pad = 10 * d
        val gap = 3 * d
        val bw = (w - pad * 2 - gap * (n - 1)) / n
        val base = h - pad
        val maxH = h - pad * 2
        for (i in 0 until n) {
            val x = pad + i * (bw + gap)
            val bh = heights[i] * maxH
            when (theme) {
                Appearance.THEME_RETRO -> {
                    var y = base
                    var k = 0
                    while (y > base - bh) {
                        val frac = (base - y) / maxH
                        p.color = when {
                            frac > 0.8f -> 0xFFFF4B3E.toInt()
                            frac > 0.55f -> 0xFFF5D33A.toInt()
                            else -> 0xFF3BE36A.toInt()
                        }
                        canvas.drawRect(x, y - 3 * d, x + bw, y - d, p)
                        y -= 4 * d
                        k++
                    }
                }
                Appearance.THEME_MIDNIGHT -> {
                    p.color = if (i < 5) accent else 0xFFF4F4F8.toInt()
                    r.set(x, base - bh, x + bw, base); canvas.drawRoundRect(r, bw / 2, bw / 2, p)
                }
                else -> {
                    p.shader = LinearGradient(0f, base, 0f, base - maxH, accent, 0xFF22D3EE.toInt(), Shader.TileMode.CLAMP)
                    r.set(x, base - bh, x + bw, base); canvas.drawRoundRect(r, bw / 2, bw / 2, p)
                    p.shader = null
                }
            }
        }
    }
}
