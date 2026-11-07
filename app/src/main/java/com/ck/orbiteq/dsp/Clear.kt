package com.ck.orbiteq.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

/**
 * CLEAR BASS: deep bass up or down without muddying the voice or distorting.
 *
 * Only the bass below ~110 Hz is taken out (a steep low-pass), scaled and added back, so
 * mids and vocals are untouched. A boost is watched by its own fast limiter that only turns
 * the *extra* bass down on big hits; the rest of the music is never squeezed, so there is no
 * pumping or clipping. level: -1..+1 (= -10..+10 on the slider, about 1 dB per step).
 */
class ClearBass(sampleRate: Int) {
    private val fs = sampleRate.toDouble()
    // the boost itself: two stacked low shelves = a steep, smooth step with no dip above it
    private val s1 = Array(2) { Biquad() }
    private val s2 = Array(2) { Biquad() }
    // bass detector for the protection limiter
    private val det = Array(2) { Biquad().apply { setLowPass(fs, 110.0, 0.707) } }
    private var lin = 1f               // full boost as a gain on the bass (1 = none)
    private var env = 0f
    private var gr = 1f
    private val envRel = exp(-1.0 / (0.15 * sampleRate)).toFloat()
    private val grAtk = (1.0 - exp(-1.0 / (0.002 * sampleRate))).toFloat()
    private val grRel = (1.0 - exp(-1.0 / (0.25 * sampleRate))).toFloat()
    /** Highest the boosted bass alone may reach (full scale = 1). */
    private val ceiling = 0.85f
    private var db = 0.0

    var active = false
        private set

    fun configure(level: Float) {
        val d = level.coerceIn(-1f, 1f) * 10.0
        if (d == db && active == (abs(d) > 0.05)) return
        db = d
        active = abs(d) > 0.05
        for (ch in 0..1) {
            s1[ch].setLowShelf(fs, 95.0, d / 2, 0.7)
            s2[ch].setLowShelf(fs, 95.0, d / 2, 0.7)
        }
        lin = 10.0.pow(d / 20.0).toFloat()
    }

    fun reset() { s1.forEach { it.reset() }; s2.forEach { it.reset() }; det.forEach { it.reset() }; env = 0f; gr = 1f }

    fun process(buf: FloatArray, frames: Int) {
        if (!active) return
        val boost = lin > 1f
        for (n in 0 until frames) {
            val l = buf[2 * n]; val r = buf[2 * n + 1]
            val yl = s2[0].process(s1[0].process(l))
            val yr = s2[1].process(s1[1].process(r))
            var g = 1f
            if (boost) {
                val p = max(abs(det[0].process(l)), abs(det[1].process(r)))
                env = if (p > env) p else p + (env - p) * envRel
                // how much of the boost fits under the ceiling right now
                val target = if (env * lin <= ceiling) 1f
                else ((ceiling / max(env, 1e-6f) - 1f) / (lin - 1f)).coerceIn(0f, 1f)
                gr += (target - gr) * (if (target < gr) grAtk else grRel)
                g = gr
            }
            // g = 1: full Clear Bass; g < 1: only part of the extra bass on a big hit
            buf[2 * n] = l + g * (yl - l)
            buf[2 * n + 1] = r + g * (yr - r)
        }
    }
}

/**
 * CLEAR AUDIO+: one switch for clearer, more detailed sound (like Sony's ClearAudio+ / DSEE).
 *  1. Detail restore: rebuilds the "air" above ~10 kHz that MP3 / YouTube compression cuts
 *     off, by deriving gentle harmonics from the 6-10 kHz content that is still there.
 *  2. Note attacks: drum hits, plucks and consonants get a short lift (presence band only,
 *     only at the start of each note), so things sound sharper without being louder.
 *  3. Tidy EQ: a little less 'boxy' low-mid (280 Hz), a touch more presence (3.2 kHz).
 * Output is pulled down 1 dB so the extra detail never pushes the music into clipping.
 */
class ClearAudio(sampleRate: Int) {
    private val fs = sampleRate.toDouble()
    var enabled = false

    private val exA = Array(2) { Biquad().apply { setHighPass(fs, 6500.0, 0.707) } }
    private val exB = Array(2) { Biquad().apply { setHighPass(fs, 9500.0, 0.707) } }
    private val exC = Array(2) { Biquad().apply { setHighPass(fs, 9500.0, 0.707) } }
    private val pres = Array(2) { Biquad().apply { setHighPass(fs, 1800.0, 0.707) } }
    private val mud = Array(2) { Biquad().apply { setPeaking(fs, 280.0, 1.0, -1.5) } }
    private val lift = Array(2) { Biquad().apply { setPeaking(fs, 3200.0, 0.9, 1.0) } }

    private var fast = 0f
    private var slow = 0f
    private val fAtk = (1.0 - exp(-1.0 / (0.001 * sampleRate))).toFloat()
    private val fRel = (1.0 - exp(-1.0 / (0.025 * sampleRate))).toFloat()
    private val sAtk = (1.0 - exp(-1.0 / (0.040 * sampleRate))).toFloat()
    private val sRel = (1.0 - exp(-1.0 / (0.250 * sampleRate))).toFloat()
    private var t = 0f
    private val tSmooth = (1.0 - exp(-1.0 / (0.003 * sampleRate))).toFloat()

    private val airMix = 0.8f
    private val attackMix = 0.9f
    private val out = 0.891f   // -1 dB

    fun reset() {
        for (a in arrayOf(exA, exB, exC, pres, mud, lift)) a.forEach { it.reset() }
        fast = 0f; slow = 0f; t = 0f
    }

    fun process(buf: FloatArray, frames: Int) {
        if (!enabled) return
        for (n in 0 until frames) {
            var l = buf[2 * n]; var r = buf[2 * n + 1]
            // 3. tidy EQ
            l = lift[0].process(mud[0].process(l)); r = lift[1].process(mud[1].process(r))
            // 2. note attacks: compare a fast and a slow loudness follower of the presence band
            val pl = pres[0].process(l); val pr = pres[1].process(r)
            val lev = 0.5f * (abs(pl) + abs(pr))
            fast += (lev - fast) * (if (lev > fast) fAtk else fRel)
            slow += (lev - slow) * (if (lev > slow) sAtk else sRel)
            val target = (fast / (slow + 1e-5f) - 1f).coerceIn(0f, 1f)
            t += (target - t) * tSmooth
            // 1. detail restore: rectifying the top band makes its 2nd harmonics (13-20 kHz)
            val al = exC[0].process(exB[0].process(abs(exA[0].process(l))))
            val ar = exC[1].process(exB[1].process(abs(exA[1].process(r))))
            l += attackMix * t * pl + airMix * al
            r += attackMix * t * pr + airMix * ar
            buf[2 * n] = l * out
            buf[2 * n + 1] = r * out
        }
    }
}
