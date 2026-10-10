package com.ck.orbiteq.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * "Atmos-style" 3D surround for headphones: mid/side widening plus
 * cross-channel early reflections that push the sound outside your head.
 */
class Spatializer(sampleRate: Int) {
    var amount = 0f

    private val size = (0.03 * sampleRate).toInt() + 2
    private val bufL = FloatArray(size)
    private val bufR = FloatArray(size)
    private var w = 0
    private val d1L = (0.0110 * sampleRate).toInt()
    private val d1R = (0.0137 * sampleRate).toInt()
    private val d2L = (0.0190 * sampleRate).toInt()
    private val d2R = (0.0230 * sampleRate).toInt()
    private val lpL = Biquad().also { it.setLowPass(sampleRate.toDouble(), 3500.0) }
    private val lpR = Biquad().also { it.setLowPass(sampleRate.toDouble(), 3500.0) }

    fun reset() {
        bufL.fill(0f)
        bufR.fill(0f)
        lpL.reset()
        lpR.reset()
    }

    private fun idx(i: Int) = ((i % size) + size) % size

    fun process(buf: FloatArray, frames: Int) {
        if (amount < 0.01f) return
        val width = 1f + amount * 0.9f
        val refl = amount * 0.22f
        for (n in 0 until frames) {
            val l = buf[2 * n]
            val r = buf[2 * n + 1]
            bufL[w] = l
            bufR[w] = r
            val m = (l + r) * 0.5f
            val s = (l - r) * 0.5f * width
            val eL = lpL.process(bufR[idx(w - d1L)] * 0.6f + bufL[idx(w - d2L)] * 0.4f)
            val eR = lpR.process(bufL[idx(w - d1R)] * 0.6f + bufR[idx(w - d2R)] * 0.4f)
            buf[2 * n] = m + s + eL * refl
            buf[2 * n + 1] = m - s + eR * refl
            w++
            if (w >= size) w = 0
        }
    }
}

/** Freeverb-style stereo reverb with room presets. */
class Reverb(sampleRate: Int) {

    private class Comb(size: Int) {
        val buf = FloatArray(size.coerceAtLeast(1))
        var i = 0
        var store = 0f
        fun process(x: Float, feedback: Float, damp: Float): Float {
            val out = buf[i]
            store = out * (1f - damp) + store * damp
            buf[i] = x + store * feedback
            i++
            if (i >= buf.size) i = 0
            return out
        }
        fun clear() {
            buf.fill(0f)
            store = 0f
        }
    }

    private class AllPass(size: Int) {
        val buf = FloatArray(size.coerceAtLeast(1))
        var i = 0
        fun process(x: Float): Float {
            val b = buf[i]
            val out = b - x
            buf[i] = x + b * 0.5f
            i++
            if (i >= buf.size) i = 0
            return out
        }
        fun clear() = buf.fill(0f)
    }

    private val scale = sampleRate / 44100.0
    private val combTunings = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val apTunings = intArrayOf(556, 441, 341, 225)
    private val combsL = combTunings.map { Comb((it * scale).toInt()) }
    private val combsR = combTunings.map { Comb(((it + 23) * scale).toInt()) }
    private val apL = apTunings.map { AllPass((it * scale).toInt()) }
    private val apR = apTunings.map { AllPass(((it + 23) * scale).toInt()) }

    private var feedback = 0.84f
    private var damp = 0.2f
    private var wet = 0f

    fun setPreset(index: Int) {
        when (index) {
            1 -> set(0.45f, 0.50f, 0.35f)  // Room
            2 -> set(0.60f, 0.40f, 0.42f)  // Studio
            3 -> set(0.75f, 0.30f, 0.52f)  // Club
            4 -> set(0.86f, 0.25f, 0.62f)  // Hall
            5 -> set(0.95f, 0.20f, 0.72f)  // Arena
            else -> wet = 0f
        }
    }

    private fun set(room: Float, dampV: Float, wetV: Float) {
        feedback = room * 0.28f + 0.7f
        damp = dampV * 0.4f
        wet = wetV
    }

    fun reset() {
        combsL.forEach { it.clear() }
        combsR.forEach { it.clear() }
        apL.forEach { it.clear() }
        apR.forEach { it.clear() }
    }

    fun process(buf: FloatArray, frames: Int) {
        if (wet <= 0f) return
        val dry = 1f - wet * 0.3f
        for (n in 0 until frames) {
            val l = buf[2 * n]
            val r = buf[2 * n + 1]
            val input = (l + r) * 0.015f
            var outL = 0f
            var outR = 0f
            for (c in combsL) outL += c.process(input, feedback, damp)
            for (c in combsR) outR += c.process(input, feedback, damp)
            for (a in apL) outL = a.process(outL)
            for (a in apR) outR = a.process(outR)
            buf[2 * n] = l * dry + outL * wet
            buf[2 * n + 1] = r * dry + outR * wet
        }
    }
}
