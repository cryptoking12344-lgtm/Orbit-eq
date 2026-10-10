package com.ck.orbiteq.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/* =====================================================================
 *  ORBIT 360 — any stereo song placed on a sphere around your head
 *
 *  stereo ─┬─ 80 Hz low-pass ─────────────────────────────► sub (both ears)
 *          └─ 80 Hz high-pass ─► per-frequency analysis:
 *               where is it panned?  → angle around you (vocals stay in front)
 *               is it ambience?      → sides / behind / overhead
 *          ─► 8 ear-level + 4 overhead virtual speakers ─► head model ─► ears
 *          ─► light studio room ─► XM4 correction ─► out
 * ===================================================================== */

class SpaceParams(
    val wrap: Float = 0.7f,       // 0 = 90° in front .. 1 = full circle
    val height: Float = 0.5f,     // how much goes overhead
    val rotation: Float = 0f,     // degrees, turns the whole scene (+ = to the right)
    val headSize: Int = 1,        // 0 small, 1 medium, 2 large
    val sub: Float = 0.4f,
    val headphoneFix: Boolean = true,
)

/** Splits stereo into 12 speaker feeds by where each frequency is panned. */
class SphereUpmixer(private val n: Int = 2048) {
    private val hop = n / 4
    val latency = n

    // Read by the analysis frame; written by configure (whole-frame consistency isn't critical).
    @Volatile var wrapDeg = 250.0
    @Volatile var rotationDeg = 0.0
    @Volatile var height = 0.5
    var ambienceGain = 1.6

    private val outs = Space360Processor.SPEAKERS
    private val win = DoubleArray(n) { sqrt(0.5 - 0.5 * cos(2.0 * PI * it / n)) }
    private val inL = DoubleArray(n)
    private val inR = DoubleArray(n)
    private var fill = 0
    private val ola = Array(outs) { DoubleArray(n) }
    private val outQ = Array(outs) { FloatArray(hop) }
    private var outPos = 0

    private val fft = ComplexFft(n)
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)
    private val bins = n / 2 + 1
    private val specRe = Array(outs) { DoubleArray(bins) }
    private val specIm = Array(outs) { DoubleArray(bins) }
    private val pll = DoubleArray(bins); private val prr = DoubleArray(bins)
    private val plrRe = DoubleArray(bins); private val plrIm = DoubleArray(bins)
    private val smooth = 0.85

    fun reset() {
        inL.fill(0.0); inR.fill(0.0); fill = 0; outPos = 0
        ola.forEach { it.fill(0.0) }; outQ.forEach { it.fill(0f) }
        pll.fill(0.0); prr.fill(0.0); plrRe.fill(0.0); plrIm.fill(0.0)
    }

    fun process(l: Float, r: Float, out: FloatArray) {
        for (k in 0 until outs) out[k] = outQ[k][outPos]
        inL[n - hop + fill] = l.toDouble()
        inR[n - hop + fill] = r.toDouble()
        fill++
        outPos++
        if (fill == hop) {
            frame()
            fill = 0
            outPos = 0
        }
    }

    /** Adds a source at azimuth [az] (degrees) to the 8-speaker ear-level ring. */
    private fun panRing(k: Int, az: Double, xr: Double, xi: Double) {
        var a = az % 360.0
        if (a < 0) a += 360.0
        val f = a / 45.0
        val i0 = floor(f).toInt() % 8
        val i1 = (i0 + 1) % 8
        val frac = f - floor(f)
        val g0 = cos(frac * PI / 2)
        val g1 = sin(frac * PI / 2)
        specRe[i0][k] += xr * g0; specIm[i0][k] += xi * g0
        specRe[i1][k] += xr * g1; specIm[i1][k] += xi * g1
    }

    /** Adds a source to the 4 overhead speakers (at 45°, 135°, 225°, 315°). */
    private fun panTop(k: Int, az: Double, xr: Double, xi: Double) {
        var a = (az - 45.0) % 360.0
        if (a < 0) a += 360.0
        val f = a / 90.0
        val i0 = floor(f).toInt() % 4
        val i1 = (i0 + 1) % 4
        val frac = f - floor(f)
        val g0 = cos(frac * PI / 2)
        val g1 = sin(frac * PI / 2)
        val t0 = Space360Processor.TOP0 + i0
        val t1 = Space360Processor.TOP0 + i1
        specRe[t0][k] += xr * g0; specIm[t0][k] += xi * g0
        specRe[t1][k] += xr * g1; specIm[t1][k] += xi * g1
    }

    private fun frame() {
        for (i in 0 until n) {
            re[i] = inL[i] * win[i]
            im[i] = inR[i] * win[i]
        }
        fft.transform(re, im, false)
        for (c in 0 until outs) {
            specRe[c].fill(0.0); specIm[c].fill(0.0)
        }

        val halfWrap = wrapDeg / 2.0
        val rot = rotationDeg
        val h = height
        val directUp = 0.35 * h                     // share of direct sound lifted overhead
        val directRing = sqrt(1.0 - directUp * directUp)
        val ambUp = 0.3 + 0.6 * h
        val ambRing = sqrt(1.0 - ambUp * ambUp)
        val ambSpread = 100.0 + 0.25 * wrapDeg / 2.0   // ambience sits at the sides/behind
        val lam = smooth
        val one = 1.0 - lam

        for (k in 0 until bins) {
            val nk = (n - k) % n
            val lr = 0.5 * (re[k] + re[nk]); val li = 0.5 * (im[k] - im[nk])
            val rr = 0.5 * (im[k] + im[nk]); val ri = -0.5 * (re[k] - re[nk])

            pll[k] = lam * pll[k] + one * (lr * lr + li * li)
            prr[k] = lam * prr[k] + one * (rr * rr + ri * ri)
            plrRe[k] = lam * plrRe[k] + one * (lr * rr + li * ri)
            plrIm[k] = lam * plrIm[k] + one * (li * rr - lr * ri)

            val energy = pll[k] + prr[k] + 1e-18
            val coh = (sqrt(plrRe[k] * plrRe[k] + plrIm[k] * plrIm[k]) / (sqrt(pll[k] * prr[k]) + 1e-18))
                .coerceIn(0.0, 1.0)
            val balance = 2.0 * sqrt(pll[k] * prr[k]) / energy
            val a = ((1.0 - coh) * ambienceGain * balance).coerceIn(0.0, 1.0)
            val d = sqrt(1.0 - a * a)

            // Direct sound: one source at the angle given by its left/right pan.
            val sl = sqrt(pll[k]); val sr = sqrt(prr[k])
            val p = ((sr - sl) / (sl + sr + 1e-18)).coerceIn(-1.0, 1.0)
            val az = rot + Math.signum(p) * abs(p).pow(0.8) * halfWrap
            var mr = 0.5 * d * (lr + rr)
            var mi = 0.5 * d * (li + ri)
            val target = 0.5 * d * d * (lr * lr + li * li + rr * rr + ri * ri)
            val mono = mr * mr + mi * mi
            val g = if (mono > 1e-24) sqrt(target / mono).coerceAtMost(2.0) else 0.0
            mr *= g; mi *= g
            panRing(k, az, mr * directRing, mi * directRing)
            if (directUp > 0.0) panTop(k, az, mr * directUp, mi * directUp)

            // Ambience: left/right room sound around the sides, behind and above.
            val alr = a * lr; val ali = a * li
            val arr = a * rr; val ari = a * ri
            panRing(k, rot - ambSpread, alr * ambRing, ali * ambRing)
            panRing(k, rot + ambSpread, arr * ambRing, ari * ambRing)
            panTop(k, rot - 90.0, alr * ambUp, ali * ambUp)
            panTop(k, rot + 90.0, arr * ambUp, ari * ambUp)
        }

        var c = 0
        while (c < outs) {
            synth(c, if (c + 1 < outs) c + 1 else -1)
            c += 2
        }

        for (k in 0 until outs) {
            val o = ola[k]
            val q = outQ[k]
            for (i in 0 until hop) q[i] = o[i].toFloat()
            System.arraycopy(o, hop, o, 0, n - hop)
            for (i in n - hop until n) o[i] = 0.0
        }
        System.arraycopy(inL, hop, inL, 0, n - hop)
        System.arraycopy(inR, hop, inR, 0, n - hop)
    }

    private fun synth(a: Int, b: Int) {
        val aRe = specRe[a]; val aIm = specIm[a]
        for (k in 0 until bins) {
            val ar = aRe[k]; val ai = aIm[k]
            val br = if (b >= 0) specRe[b][k] else 0.0
            val bi = if (b >= 0) specIm[b][k] else 0.0
            re[k] = ar - bi
            im[k] = ai + br
            if (k in 1 until n / 2) {
                re[n - k] = ar + bi
                im[n - k] = -ai + br
            }
        }
        // Nyquist and DC bins must be real for a real output signal.
        fft.transform(re, im, true)
        val scale = 0.5 / n
        val oa = ola[a]
        for (i in 0 until n) oa[i] += re[i] * win[i] * scale
        if (b >= 0) {
            val ob = ola[b]
            for (i in 0 until n) ob[i] += im[i] * win[i] * scale
        }
    }
}

class Space360Processor(sampleRate: Int) {

    companion object {
        const val SPEAKERS = 12
        const val TOP0 = 8

        val NAMES = arrayOf(
            "Front", "Front right", "Right", "Rear right", "Behind", "Rear left", "Left", "Front left",
            "Above front right", "Above rear right", "Above rear left", "Above front left",
        )
        val SHORT = arrayOf("F", "FR", "R", "RR", "B", "RL", "L", "FL", "↑FR", "↑RR", "↑RL", "↑FL")
        val TEST_ORDER = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 11, 8, 9, 10)
        val HEAD_NAMES = arrayOf("Small", "Medium", "Large")
        private val HEAD_RADIUS = doubleArrayOf(0.080, 0.0875, 0.095)

        /** Azimuth (+ = right) and elevation in degrees. */
        fun position(speaker: Int): DoubleArray =
            if (speaker < TOP0) {
                var az = speaker * 45.0
                if (az > 180) az -= 360
                doubleArrayOf(az, 0.0)
            } else {
                var az = 45.0 + (speaker - TOP0) * 90.0
                if (az > 180) az -= 360
                doubleArrayOf(az, 45.0)
            }

        fun wrapDegrees(wrap: Float) = 90.0 + 210.0 * wrap.coerceIn(0f, 1f)
    }

    private val fs = sampleRate
    private val fsD = sampleRate.toDouble()
    private val speakers = Array(SPEAKERS) { VirtualSpeaker(sampleRate) }
    private val sIn = FloatArray(SPEAKERS)
    private val upmix = SphereUpmixer(2048)
    private val up = FloatArray(SPEAKERS)
    private var headSize = -1

    private val hpL1 = Biquad(); private val hpL2 = Biquad()
    private val hpR1 = Biquad(); private val hpR2 = Biquad()
    private val lp1 = Biquad(); private val lp2 = Biquad()
    private val subSize = upmix.latency + 1
    private val subRing = FloatArray(subSize)
    private var subW = 0

    // Light studio room so the sphere feels like a real space, not inside your head.
    private val fdn = Fdn(sampleRate)
    private val fdnOut = FloatArray(8)
    private val roomSize = upmix.latency + 1
    private val roomRing = FloatArray(roomSize)
    private var roomW = 0
    private var roomGain = 0.1f

    private val fixL = Array(TheaterProcessor.FIX_F.size) { Biquad() }
    private val fixR = Array(TheaterProcessor.FIX_F.size) { Biquad() }
    private var fixOn = true
    private val fixGain = 10.0.pow(-3.0 / 20.0).toFloat()
    private var subGain = 1f
    private var master = 0.9f

    var earL = 0f
        private set
    var earR = 0f
        private set

    init {
        hpL1.setHighPass(fsD, 80.0); hpL2.setHighPass(fsD, 80.0)
        hpR1.setHighPass(fsD, 80.0); hpR2.setHighPass(fsD, 80.0)
        lp1.setLowPass(fsD, 80.0); lp2.setLowPass(fsD, 80.0)
        val t = TheaterProcessor
        for (i in t.FIX_F.indices) {
            for (b in arrayOf(fixL[i], fixR[i])) {
                when (t.FIX_TYPE[i]) {
                    1 -> b.setLowShelf(fsD, t.FIX_F[i], t.FIX_G[i], t.FIX_Q[i])
                    2 -> b.setHighShelf(fsD, t.FIX_F[i], t.FIX_G[i], t.FIX_Q[i])
                    else -> b.setPeaking(fsD, t.FIX_F[i], t.FIX_Q[i], t.FIX_G[i])
                }
            }
        }
        fdn.configure(0.35, 0.4, 0.45f)
        configure(SpaceParams())
    }

    fun configure(p: SpaceParams) {
        val hs = p.headSize.coerceIn(0, 2)
        if (hs != headSize) {
            headSize = hs
            for (i in 0 until SPEAKERS) {
                val pos = position(i)
                speakers[i].place(pos[0], pos[1], HEAD_RADIUS[hs])
            }
        }
        val wrapDeg = wrapDegrees(p.wrap)
        upmix.wrapDeg = wrapDeg
        upmix.rotationDeg = p.rotation.toDouble()
        upmix.height = p.height.coerceIn(0f, 1f).toDouble()
        roomGain = (0.06 + 0.06 * p.wrap).toFloat()
        subGain = 10.0.pow((-4.0 + 12.0 * p.sub) / 20.0).toFloat()
        fixOn = p.headphoneFix
    }

    fun reset() {
        upmix.reset()
        speakers.forEach { it.reset() }
        subRing.fill(0f); roomRing.fill(0f)
        fdn.reset()
        for (b in arrayOf(hpL1, hpL2, hpR1, hpR2, lp1, lp2)) b.reset()
        fixL.forEach { it.reset() }; fixR.forEach { it.reset() }
    }

    fun process(buf: FloatArray, frames: Int) {
        for (n in 0 until frames) {
            val l = buf[2 * n]
            val r = buf[2 * n + 1]
            val low = lp2.process(lp1.process((l + r) * 0.5f))
            val hl = hpL2.process(hpL1.process(l))
            val hr = hpR2.process(hpR1.process(r))
            subRing[subW] = low
            subW++
            if (subW >= subSize) subW = 0
            val sub = subRing[subW]

            // the room is fed with the same delay as the upmix so it stays in sync
            roomRing[roomW] = (hl + hr) * 0.5f
            roomW++
            if (roomW >= roomSize) roomW = 0
            val roomIn = roomRing[roomW]

            upmix.process(hl, hr, up)
            for (i in 0 until SPEAKERS) sIn[i] = up[i]
            render(roomIn)
            output(buf, n, earL * master + sub * subGain, earR * master + sub * subGain)
        }
    }

    fun processTest(input: FloatArray, out: FloatArray, frames: Int, speaker: Int) {
        val s = speaker.coerceIn(0, SPEAKERS - 1)
        for (n in 0 until frames) {
            sIn.fill(0f)
            sIn[s] = input[n]
            render(input[n] * 0.5f)
            output(out, n, earL * master, earR * master)
        }
    }

    private fun output(buf: FloatArray, n: Int, l: Float, r: Float) {
        var outL = l
        var outR = r
        if (fixOn) {
            outL *= fixGain; outR *= fixGain
            for (b in fixL) outL = b.process(outL)
            for (b in fixR) outR = b.process(outR)
        }
        buf[2 * n] = outL
        buf[2 * n + 1] = outR
    }

    private fun render(roomIn: Float) {
        fdn.process(roomIn, fdnOut)
        val g = roomGain
        for (i in 0 until 8) sIn[i] += fdnOut[i] * g
        sIn[8] += fdnOut[1] * g * 0.7f
        sIn[9] += fdnOut[3] * g * 0.7f
        sIn[10] += fdnOut[5] * g * 0.7f
        sIn[11] += fdnOut[7] * g * 0.7f
        var el = 0f
        var er = 0f
        for (i in 0 until SPEAKERS) {
            val sp = speakers[i]
            sp.process(sIn[i])
            el += sp.outL
            er += sp.outR
        }
        earL = el
        earR = er
    }
}
