package com.ck.orbiteq.dsp

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.tanh

/**
 * Headphone-amp stage, like a DAC/amp on your desk:
 *  - Tube warmth: gentle asymmetric saturation (adds soft 2nd harmonics) + a warm tilt
 *  - Crossfeed: feeds a little low-passed, slightly delayed sound to the other ear,
 *    like listening to speakers (less "inside the head", less fatigue)
 *  - Output gain: Low (0 dB) or High (+6 dB); the limiter after it keeps peaks clean
 */
class AmpParams(
    val warmth: Float = 0f,     // 0..1
    val crossfeed: Float = 0f,  // 0..1
    val gainHigh: Boolean = false,
)

class AmpProcessor(sampleRate: Int) {
    private val fs = sampleRate.toDouble()

    private var warmth = 0f
    private var drive = 1.0
    private var bias = 0.0
    private var norm = 1.0
    private var biasOut = 0.0
    private val tiltLowL = Biquad(); private val tiltLowR = Biquad()
    private val tiltHighL = Biquad(); private val tiltHighR = Biquad()

    private var cross = 0f
    private var crossGain = 0f
    private var directGain = 1f
    private val xLpL = Biquad(); private val xLpR = Biquad()
    private val dSize = 64
    private val dL = FloatArray(dSize)
    private val dR = FloatArray(dSize)
    private var dW = 0
    private val delay = (0.00030 * sampleRate).toInt().coerceIn(1, dSize - 1)

    // DC blocker after the asymmetric curve
    private val dcR = exp(-2.0 * PI * 8.0 / sampleRate).toFloat()
    private var dcXl = 0f; private var dcYl = 0f
    private var dcXr = 0f; private var dcYr = 0f

    private var outGain = 1f

    init {
        xLpL.setLowPass(fs, 700.0, 0.6)
        xLpR.setLowPass(fs, 700.0, 0.6)
        configure(AmpParams())
    }

    val active: Boolean get() = warmth > 0.01f || cross > 0.01f || outGain != 1f

    fun configure(p: AmpParams) {
        warmth = p.warmth.coerceIn(0f, 1f)
        drive = 1.0 + 1.3 * warmth
        bias = 0.45 * warmth
        biasOut = tanh(bias)
        // unity gain for quiet signals: only loud peaks get rounded off
        norm = 1.0 / (drive * (1.0 - biasOut * biasOut))
        val w = warmth.toDouble()
        tiltLowL.setLowShelf(fs, 140.0, 1.2 * w); tiltLowR.setLowShelf(fs, 140.0, 1.2 * w)
        tiltHighL.setHighShelf(fs, 9000.0, -2.0 * w); tiltHighR.setHighShelf(fs, 9000.0, -2.0 * w)

        cross = p.crossfeed.coerceIn(0f, 1f)
        // up to about -6 dB of crossfeed; direct path trimmed so total loudness stays similar
        crossGain = 0.5f * cross
        directGain = 1f - 0.22f * cross

        outGain = if (p.gainHigh) 2f else 1f
    }

    fun reset() {
        for (b in arrayOf(tiltLowL, tiltLowR, tiltHighL, tiltHighR, xLpL, xLpR)) b.reset()
        dL.fill(0f); dR.fill(0f)
        dcXl = 0f; dcYl = 0f; dcXr = 0f; dcYr = 0f
    }

    private fun saturate(x: Float): Float {
        val y = (tanh(drive * x + bias) - biasOut) * norm
        return y.toFloat()
    }

    fun process(buf: FloatArray, frames: Int) {
        if (!active) return
        val w = warmth
        val c = cross
        for (n in 0 until frames) {
            var l = buf[2 * n]
            var r = buf[2 * n + 1]

            if (w > 0.01f) {
                // parallel blend keeps transients; the saturated part adds the glow
                var sl = saturate(l)
                var sr = saturate(r)
                // DC blocker (the asymmetric curve adds a little DC)
                val yl = sl - dcXl + dcR * dcYl; dcXl = sl; dcYl = yl; sl = yl
                val yr = sr - dcXr + dcR * dcYr; dcXr = sr; dcYr = yr; sr = yr
                l = l * (1f - w) + sl * w
                r = r * (1f - w) + sr * w
                l = tiltHighL.process(tiltLowL.process(l))
                r = tiltHighR.process(tiltLowR.process(r))
            }

            if (c > 0.01f) {
                val lowL = xLpL.process(l)
                val lowR = xLpR.process(r)
                dL[dW] = lowL
                dR[dW] = lowR
                var ri = dW - delay
                if (ri < 0) ri += dSize
                val fromL = dL[ri]
                val fromR = dR[ri]
                dW++
                if (dW >= dSize) dW = 0
                val nl = l * directGain + fromR * crossGain
                val nr = r * directGain + fromL * crossGain
                l = nl
                r = nr
            }

            buf[2 * n] = l * outGain
            buf[2 * n + 1] = r * outGain
        }
    }

    companion object {
        fun dbToLin(db: Double) = 10.0.pow(db / 20.0).toFloat()
    }
}
