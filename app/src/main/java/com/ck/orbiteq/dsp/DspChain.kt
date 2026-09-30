package com.ck.orbiteq.dsp

import com.ck.orbiteq.model.AudioState
import com.ck.orbiteq.model.EqSettings
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

/** Peak limiter so boosts never clip. */
class Limiter(sampleRate: Int) {
    private var gain = 1f
    private val release = exp(-1.0 / (0.12 * sampleRate)).toFloat()
    private val ceiling = 0.97f

    fun reset() {
        gain = 1f
    }

    fun process(buf: FloatArray, frames: Int) {
        for (n in 0 until frames) {
            val l = buf[2 * n]
            val r = buf[2 * n + 1]
            val peak = max(abs(l), abs(r))
            val target = if (peak > ceiling) ceiling / peak else 1f
            gain = if (target < gain) target else target + (gain - target) * release
            buf[2 * n] = (l * gain).coerceIn(-1f, 1f)
            buf[2 * n + 1] = (r * gain).coerceIn(-1f, 1f)
        }
    }
}

/** Full effect chain used by the in-app player. Works on interleaved stereo floats. */
class DspChain(sampleRate: Int) {
    private val fs = sampleRate.toDouble()
    private val eqL = Array(EqSettings.BANDS) { Biquad() }
    private val eqR = Array(EqSettings.BANDS) { Biquad() }
    private val bassL = Biquad()
    private val bassR = Biquad()
    private var eqOn = false
    private var bassOn = false
    private var pre = 1f
    private var loud = 1f

    private val eightD = EightDProcessor(sampleRate)
    private val spatial = Spatializer(sampleRate)
    private val reverb = Reverb(sampleRate)
    private val limiter = Limiter(sampleRate)

    private fun dbToLin(db: Float) = 10.0.pow(db / 20.0).toFloat()

    fun configure(s: EqSettings, e: AudioState.EightD) {
        var maxBoost = 0f
        for (i in 0 until EqSettings.BANDS) {
            val g = s.gains[i].toDouble()
            eqL[i].setPeaking(fs, EqSettings.FREQS[i].toDouble(), 1.1, g)
            eqR[i].setPeaking(fs, EqSettings.FREQS[i].toDouble(), 1.1, g)
            if (s.gains[i] > maxBoost) maxBoost = s.gains[i]
        }
        eqOn = s.gains.any { abs(it) > 0.05f }

        val bassDb = s.bass * 10.0
        bassL.setLowShelf(fs, 100.0, bassDb)
        bassR.setLowShelf(fs, 100.0, bassDb)
        bassOn = s.bass > 0.01f
        maxBoost = max(maxBoost, s.bass * 10f)

        // Leave some automatic headroom when boosting so the limiter works less.
        pre = dbToLin(s.preamp - maxBoost * 0.5f)
        loud = dbToLin(s.loudness)

        spatial.amount = s.spatial
        reverb.setPreset(s.reverb)

        eightD.enabled = e.enabled
        eightD.speedHz = e.speedHz
        eightD.depth = e.depth
    }

    fun reset() {
        eqL.forEach { it.reset() }
        eqR.forEach { it.reset() }
        bassL.reset()
        bassR.reset()
        eightD.reset()
        spatial.reset()
        reverb.reset()
        limiter.reset()
    }

    fun process(buf: FloatArray, frames: Int) {
        val g = pre
        if (eqOn || bassOn || g != 1f) {
            for (n in 0 until frames) {
                var l = buf[2 * n] * g
                var r = buf[2 * n + 1] * g
                if (eqOn) {
                    for (b in 0 until EqSettings.BANDS) {
                        l = eqL[b].process(l)
                        r = eqR[b].process(r)
                    }
                }
                if (bassOn) {
                    l = bassL.process(l)
                    r = bassR.process(r)
                }
                buf[2 * n] = l
                buf[2 * n + 1] = r
            }
        }
        eightD.process(buf, frames)
        spatial.process(buf, frames)
        reverb.process(buf, frames)
        if (loud != 1f) {
            for (i in 0 until frames * 2) buf[i] *= loud
        }
        limiter.process(buf, frames)
    }
}
