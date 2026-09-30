package com.ck.orbiteq.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** RBJ-cookbook biquad filter (transposed direct form II). */
class Biquad {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(x: Float): Float {
        val xd = x.toDouble()
        val y = b0 * xd + z1
        z1 = b1 * xd - a1 * y + z2
        z2 = b2 * xd - a2 * y
        return y.toFloat()
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    fun setPeaking(fs: Double, f0: Double, q: Double, gainDb: Double) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * safeFreq(fs, f0) / fs
        val alpha = sin(w0) / (2.0 * q)
        val c = cos(w0)
        set(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
    }

    fun setLowShelf(fs: Double, f0: Double, gainDb: Double, q: Double = 0.707) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * safeFreq(fs, f0) / fs
        val c = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val sa = 2.0 * sqrt(a) * alpha
        set(
            a * ((a + 1) - (a - 1) * c + sa),
            2 * a * ((a - 1) - (a + 1) * c),
            a * ((a + 1) - (a - 1) * c - sa),
            (a + 1) + (a - 1) * c + sa,
            -2 * ((a - 1) + (a + 1) * c),
            (a + 1) + (a - 1) * c - sa,
        )
    }

    fun setLowPass(fs: Double, f0: Double, q: Double = 0.707) {
        val w0 = 2.0 * PI * safeFreq(fs, f0) / fs
        val c = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        set((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
    }

    private fun safeFreq(fs: Double, f: Double) = f.coerceIn(10.0, fs * 0.45)

    private fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
        b0 = nb0 / na0
        b1 = nb1 / na0
        b2 = nb2 / na0
        a1 = na1 / na0
        a2 = na2 / na0
    }
}
