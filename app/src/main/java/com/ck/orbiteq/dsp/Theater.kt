package com.ck.orbiteq.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/* =====================================================================
 *  THEATER MODE — virtual 7.1.4 cinema for headphones
 *
 *  stereo ─┬─ 80 Hz low-pass ─────────────────────────────► sub (both ears)
 *          └─ 80 Hz high-pass ─► STFT upmix ─► C, FL, FR, ambience L/R
 *                                        │
 *              ambience → sides, rears, 4 height speakers (decorrelated)
 *              cinema room: early reflections + 8-line reverb → surrounds/heights
 *                                        │
 *              11 virtual speakers, each rendered with a head model
 *              (ITD + head shadow + pinna/direction cues) → left/right ear
 *                                        │
 *                            XM4 correction → out
 * ===================================================================== */

/** Settings for Theater mode (plain values so it can be unit-tested off-device). */
class TheaterParams(
    val room: Int = 1,             // 0 Studio, 1 Cinema, 2 Arena
    val immersion: Float = 0.5f,   // 0 real cinema .. 1 wow
    val vocal: Float = 0.6f,       // how firmly vocals are locked to the centre speaker
    val sub: Float = 0.4f,         // subwoofer level
    val headphoneFix: Boolean = true,
)

/** In-place complex radix-2 FFT. */
class ComplexFft(private val n: Int) {
    private val cosT = DoubleArray(n / 2) { cos(2.0 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { sin(2.0 * PI * it / n) }

    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
            var m = n shr 1
            while (m >= 1 && (j and m) != 0) {
                j = j xor m
                m = m shr 1
            }
            j = j or m
        }
        val sign = if (inverse) -1.0 else 1.0
        var size = 2
        while (size <= n) {
            val half = size shr 1
            val step = n / size
            var start = 0
            while (start < n) {
                for (k in 0 until half) {
                    val c = cosT[k * step]
                    val s = sinT[k * step] * sign
                    val a = start + k
                    val b = a + half
                    // multiply by e^(-i*theta) (forward) or e^(+i*theta) (inverse)
                    val tr = re[b] * c + im[b] * s
                    val ti = im[b] * c - re[b] * s
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                start += size
            }
            size = size shl 1
        }
    }
}

/**
 * Frequency-domain stereo upmixer. Splits each frequency into
 * centre (vocals), direct left/right (instruments) and ambience (room / reverb).
 */
class Upmixer(private val n: Int = 2048) {
    private val hop = n / 4
    /** Samples of delay this block adds (checked by unit test). */
    val latency = n

    var ambienceGain = 1.2
    var centerPower = 4.0

    private val win = DoubleArray(n) { sqrt(0.5 - 0.5 * cos(2.0 * PI * it / n)) }
    private val inL = DoubleArray(n)
    private val inR = DoubleArray(n)
    private var fill = 0
    private val ola = Array(5) { DoubleArray(n) }
    private val outQ = Array(5) { FloatArray(hop) }
    private var outPos = 0

    private val fft = ComplexFft(n)
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)
    private val bins = n / 2 + 1
    private val lRe = DoubleArray(bins); private val lIm = DoubleArray(bins)
    private val rRe = DoubleArray(bins); private val rIm = DoubleArray(bins)
    private val specRe = Array(5) { DoubleArray(bins) }
    private val specIm = Array(5) { DoubleArray(bins) }
    private val pll = DoubleArray(bins); private val prr = DoubleArray(bins)
    private val plrRe = DoubleArray(bins); private val plrIm = DoubleArray(bins)
    private val smooth = 0.85

    fun reset() {
        inL.fill(0.0); inR.fill(0.0); fill = 0; outPos = 0
        ola.forEach { it.fill(0.0) }; outQ.forEach { it.fill(0f) }
        pll.fill(0.0); prr.fill(0.0); plrRe.fill(0.0); plrIm.fill(0.0)
    }

    /** out = [centre, frontL, frontR, ambL, ambR] */
    fun process(l: Float, r: Float, out: FloatArray) {
        for (k in 0 until 5) out[k] = outQ[k][outPos]
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

    private fun frame() {
        for (i in 0 until n) {
            re[i] = inL[i] * win[i]
            im[i] = inR[i] * win[i]
        }
        fft.transform(re, im, false)

        val lam = smooth
        val one = 1.0 - lam
        for (k in 0 until bins) {
            val nk = (n - k) % n
            val lr = 0.5 * (re[k] + re[nk]); val li = 0.5 * (im[k] - im[nk])
            val rr = 0.5 * (im[k] + im[nk]); val ri = -0.5 * (re[k] - re[nk])
            lRe[k] = lr; lIm[k] = li; rRe[k] = rr; rIm[k] = ri

            pll[k] = lam * pll[k] + one * (lr * lr + li * li)
            prr[k] = lam * prr[k] + one * (rr * rr + ri * ri)
            plrRe[k] = lam * plrRe[k] + one * (lr * rr + li * ri)
            plrIm[k] = lam * plrIm[k] + one * (li * rr - lr * ri)

            val energy = pll[k] + prr[k] + 1e-18
            val coh = (sqrt(plrRe[k] * plrRe[k] + plrIm[k] * plrIm[k]) / (sqrt(pll[k] * prr[k]) + 1e-18))
                .coerceIn(0.0, 1.0)
            // Ambience = low coherence, but only when both sides carry energy
            // (a hard-panned instrument is direct sound, not room).
            val balance = 2.0 * sqrt(pll[k] * prr[k]) / energy
            val a = ((1.0 - coh) * ambienceGain * balance).coerceIn(0.0, 1.0)
            val d = sqrt(max(0.0, 1.0 - a * a))
            val sim = (2.0 * plrRe[k] / energy).coerceIn(0.0, 1.0)
            val c = sim.pow(centerPower)

            val dlr = d * lr; val dli = d * li
            val drr = d * rr; val dri = d * ri
            val cr = c * 0.5 * (dlr + drr); val ci = c * 0.5 * (dli + dri)
            specRe[0][k] = cr; specIm[0][k] = ci                 // centre
            specRe[1][k] = dlr - cr; specIm[1][k] = dli - ci     // front L
            specRe[2][k] = drr - cr; specIm[2][k] = dri - ci     // front R
            specRe[3][k] = a * lr; specIm[3][k] = a * li         // ambience L
            specRe[4][k] = a * rr; specIm[4][k] = a * ri         // ambience R
        }

        synth(1, 2)
        synth(0, 3)
        synth(4, -1)

        for (k in 0 until 5) {
            val o = ola[k]
            val q = outQ[k]
            for (i in 0 until hop) q[i] = o[i].toFloat()
            System.arraycopy(o, hop, o, 0, n - hop)
            for (i in n - hop until n) o[i] = 0.0
        }
        System.arraycopy(inL, hop, inL, 0, n - hop)
        System.arraycopy(inR, hop, inR, 0, n - hop)
    }

    /** Inverse-transforms two real outputs at once (a in real part, b in imaginary part). */
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
        fft.transform(re, im, true)
        val scale = 0.5 / n   // 1/n for the inverse FFT, 0.5 for sqrt-Hann at 75 % overlap
        val oa = ola[a]
        for (i in 0 until n) oa[i] += re[i] * win[i] * scale
        if (b >= 0) {
            val ob = ola[b]
            for (i in 0 until n) ob[i] += im[i] * win[i] * scale
        }
    }
}

/** First-order IIR (used for the head-shadow filter). */
class OnePole {
    private var b0 = 1.0
    private var b1 = 0.0
    private var a1 = 0.0
    private var x1 = 0.0
    private var y1 = 0.0

    fun process(x: Float): Float {
        val xd = x.toDouble()
        val y = b0 * xd + b1 * x1 - a1 * y1
        x1 = xd
        y1 = y
        return y.toFloat()
    }

    fun reset() {
        x1 = 0.0; y1 = 0.0
    }

    /** Brown & Duda spherical-head shadow: gain 1 at low frequency, alpha at high frequency. */
    fun setHeadShadow(fs: Double, alpha: Double) {
        val w0 = TheaterProcessor.SOUND_SPEED / TheaterProcessor.HEAD_RADIUS
        val k = 2.0 * fs
        val a0 = k + 2 * w0
        b0 = (alpha * k + 2 * w0) / a0
        b1 = (2 * w0 - alpha * k) / a0
        a1 = (2 * w0 - k) / a0
    }
}

/** One virtual loudspeaker rendered to both ears with a head model. */
class VirtualSpeaker(sampleRate: Int) {
    private val fs = sampleRate.toDouble()
    private val cues = Array(5) { Biquad() }
    private val cueOn = BooleanArray(5)
    private val size = 256
    private val ring = FloatArray(size)
    private var w = 0
    private var delayL = 0.0
    private var delayR = 0.0
    private val shadowL = OnePole()
    private val shadowR = OnePole()
    var outL = 0f
        private set
    var outR = 0f
        private set

    fun place(azDeg: Double, elDeg: Double) {
        val az = Math.toRadians(azDeg)
        val el = Math.toRadians(elDeg)
        val sx = cos(el) * cos(az)       // forward
        val sy = cos(el) * sin(az)       // right
        val sz = sin(el)                 // up

        // Interaural time difference (Woodworth), far ear is delayed.
        val lat = asin(sy.coerceIn(-1.0, 1.0))
        val itd = TheaterProcessor.HEAD_RADIUS / TheaterProcessor.SOUND_SPEED * (abs(lat) + sin(abs(lat))) * fs
        delayL = if (sy > 0) itd else 0.0
        delayR = if (sy < 0) itd else 0.0

        // Head shadow for each ear.
        shadowR.setHeadShadow(fs, shadowAlpha(acos(sy.coerceIn(-1.0, 1.0))))
        shadowL.setHeadShadow(fs, shadowAlpha(acos((-sy).coerceIn(-1.0, 1.0))))

        // Direction cues from the outer ear (front / behind / above).
        val front = max(0.0, sx)
        val rear = max(0.0, -sx)
        val up = max(0.0, sz)
        val g0 = 2.5 * front * (1 - up) - 3.5 * rear
        val g1 = 2.5 * rear
        val g2 = 5.0 * up
        val elD = elDeg.coerceIn(-40.0, 60.0)
        val g3 = -7.0 * (1.0 - (elDeg / 70.0).coerceIn(0.0, 1.0))
        val g4 = -5.0 * rear
        cues[0].setPeaking(fs, 4000.0, 1.0, g0)
        cues[1].setPeaking(fs, 1100.0, 1.4, g1)
        cues[2].setPeaking(fs, 8000.0, 2.0, g2)
        cues[3].setPeaking(fs, 7000.0 + elD * 80.0, 5.0, g3)
        cues[4].setHighShelf(fs, 9000.0, g4)
        val gains = doubleArrayOf(g0, g1, g2, g3, g4)
        for (i in 0 until 5) cueOn[i] = abs(gains[i]) > 0.05
    }

    private fun shadowAlpha(incidence: Double): Double {
        val aMin = 0.1
        val thetaMin = Math.toRadians(150.0)
        return (1 + aMin / 2) + (1 - aMin / 2) * cos(incidence / thetaMin * PI)
    }

    fun reset() {
        ring.fill(0f)
        cues.forEach { it.reset() }
        shadowL.reset(); shadowR.reset()
    }

    private fun read(delay: Double): Float {
        val pos = w - delay
        val i0 = kotlin.math.floor(pos).toInt()
        val frac = (pos - i0).toFloat()
        val a = ring[(i0 + size * 4) % size]
        val b = ring[(i0 + 1 + size * 4) % size]
        return a + (b - a) * frac
    }

    fun process(x: Float) {
        var y = x
        for (i in 0 until 5) if (cueOn[i]) y = cues[i].process(y)
        ring[w] = y
        outL = shadowL.process(read(delayL))
        outR = shadowR.process(read(delayR))
        w++
        if (w >= size) w = 0
    }
}

/** 8-line feedback delay network — the long, smooth tail of a big room. */
class Fdn(sampleRate: Int) {
    private val fs = sampleRate
    private val maxLen = (0.2 * sampleRate).toInt()
    private val bufs = Array(8) { FloatArray(maxLen) }
    private val idx = IntArray(8)
    private val len = IntArray(8) { 1000 }
    private val g = FloatArray(8)
    private val lp = FloatArray(8)
    private val x = FloatArray(8)
    private var damp = 0.4f
    private val baseMs = doubleArrayOf(29.7, 37.1, 41.1, 43.7, 53.3, 59.9, 67.1, 73.3)

    fun configure(size: Double, rt60: Double, damping: Float) {
        for (i in 0 until 8) {
            len[i] = (baseMs[i] * size * fs / 1000.0).toInt().coerceIn(16, maxLen - 1)
            if (idx[i] >= len[i]) idx[i] = 0
            g[i] = 10.0.pow(-3.0 * len[i] / (rt60 * fs)).toFloat()
        }
        damp = damping
    }

    fun reset() {
        bufs.forEach { it.fill(0f) }
        lp.fill(0f)
    }

    fun process(input: Float, out: FloatArray) {
        val d = damp
        for (i in 0 until 8) {
            val o = bufs[i][idx[i]]
            out[i] = o
            lp[i] = o * (1f - d) + lp[i] * d
            x[i] = lp[i]
        }
        // fast Hadamard mix (orthogonal, so the loop stays stable)
        var h = 1
        while (h < 8) {
            var i = 0
            while (i < 8) {
                for (j in i until i + h) {
                    val a = x[j]; val b = x[j + h]
                    x[j] = a + b; x[j + h] = a - b
                }
                i += h * 2
            }
            h = h shl 1
        }
        val norm = 0.35355339f
        for (i in 0 until 8) {
            val inj = if (i and 1 == 0) input else -input
            bufs[i][idx[i]] = inj * 0.35f + x[i] * norm * g[i]
            idx[i]++
            if (idx[i] >= len[i]) idx[i] = 0
        }
    }
}

/** Schroeder all-pass — smears ambience so each surround speaker sounds different. */
class Diffuser(sizeSamples: Int, private val gain: Float = 0.5f) {
    private val buf = FloatArray(sizeSamples.coerceAtLeast(1))
    private var i = 0
    fun process(x: Float): Float {
        val b = buf[i]
        val v = x + b * gain
        buf[i] = v
        i++
        if (i >= buf.size) i = 0
        return b - v * gain
    }
    fun reset() = buf.fill(0f)
}

class TheaterProcessor(sampleRate: Int) {

    companion object {
        const val HEAD_RADIUS = 0.0875
        const val SOUND_SPEED = 343.0

        const val C = 0; const val FL = 1; const val FR = 2; const val SL = 3; const val SR = 4
        const val RL = 5; const val RR = 6; const val TFL = 7; const val TFR = 8; const val TRL = 9; const val TRR = 10
        const val SPEAKERS = 11

        val NAMES = arrayOf(
            "Center", "Front left", "Front right", "Side left", "Side right", "Rear left", "Rear right",
            "Top front left", "Top front right", "Top rear left", "Top rear right",
        )
        val SHORT = arrayOf("C", "FL", "FR", "SL", "SR", "RL", "RR", "TFL", "TFR", "TRL", "TRR")
        /** Order for "play all", clockwise like a cinema speaker check. */
        val TEST_ORDER = intArrayOf(FL, C, FR, SR, RR, RL, SL, TFL, TFR, TRR, TRL)
        val ROOM_NAMES = arrayOf("Studio", "Cinema", "Arena")

        /** Azimuth (deg, + = right) and elevation for each speaker at a given front width. */
        fun position(speaker: Int, frontWidth: Double): DoubleArray = when (speaker) {
            C -> doubleArrayOf(0.0, 0.0)
            FL -> doubleArrayOf(-frontWidth, 0.0)
            FR -> doubleArrayOf(frontWidth, 0.0)
            SL -> doubleArrayOf(-100.0, 0.0)
            SR -> doubleArrayOf(100.0, 0.0)
            RL -> doubleArrayOf(-145.0, 0.0)
            RR -> doubleArrayOf(145.0, 0.0)
            TFL -> doubleArrayOf(-45.0, 45.0)
            TFR -> doubleArrayOf(45.0, 45.0)
            TRL -> doubleArrayOf(-135.0, 45.0)
            else -> doubleArrayOf(135.0, 45.0)
        }

        fun frontWidth(immersion: Float) = 30.0 + 14.0 * immersion

        // size, rt60 (s), predelay (s), damping, wet
        private val ROOMS = arrayOf(
            doubleArrayOf(0.45, 0.45, 0.004, 0.35, 0.20),
            doubleArrayOf(1.00, 1.15, 0.018, 0.42, 0.30),
            doubleArrayOf(1.80, 2.30, 0.034, 0.50, 0.36),
        )

        // Early reflections: delay (ms at size 1), source (0 L, 1 R, 2 mono), speaker, gain
        private val ER_MS = doubleArrayOf(13.0, 13.6, 17.0, 19.0, 27.0, 29.5, 38.0, 41.0, 51.0, 55.0, 62.0, 67.0)
        private val ER_SRC = intArrayOf(2, 2, 0, 1, 0, 1, 0, 1, 2, 2, 1, 0)
        private val ER_SPK = intArrayOf(TFL, TFR, SL, SR, SR, SL, RL, RR, TRL, TRR, SL, SR)
        private val ER_GAIN = floatArrayOf(0.30f, 0.30f, 0.40f, 0.40f, 0.22f, 0.22f, 0.26f, 0.26f, 0.17f, 0.17f, 0.13f, 0.13f)

        // Sony WH-1000XM4 correction (AutoEQ, oratory1990 measurement): type 0 PK, 1 LSC, 2 HSC
        private val FIX_TYPE = intArrayOf(1, 0, 0, 0, 0, 2, 0, 0, 0, 0)
        private val FIX_F = doubleArrayOf(105.0, 143.0, 2289.0, 56.0, 5144.0, 10000.0, 407.0, 6715.0, 1007.0, 576.0)
        private val FIX_G = doubleArrayOf(-4.2, -5.2, 6.1, 1.2, -3.2, -1.0, 1.7, 3.0, 1.0, -1.2)
        private val FIX_Q = doubleArrayOf(0.70, 1.10, 1.57, 1.19, 6.00, 0.70, 3.14, 5.99, 3.41, 3.55)
    }

    private val fs = sampleRate
    private val fsD = sampleRate.toDouble()
    private val speakers = Array(SPEAKERS) { VirtualSpeaker(sampleRate) }
    private val sIn = FloatArray(SPEAKERS)

    private val upmix = Upmixer(2048)
    private val up = FloatArray(5)

    // 80 Hz crossover (Linkwitz-Riley 4th order)
    private val hpL1 = Biquad(); private val hpL2 = Biquad()
    private val hpR1 = Biquad(); private val hpR2 = Biquad()
    private val lp1 = Biquad(); private val lp2 = Biquad()
    private val subSize = upmix.latency + 1
    private val subRing = FloatArray(subSize)
    private var subW = 0

    // ambience distribution
    private val ambSize = (0.03 * sampleRate).toInt() + 2
    private val ambL = FloatArray(ambSize)
    private val ambR = FloatArray(ambSize)
    private var ambW = 0
    private val dTopF = (0.005 * sampleRate).toInt()
    private val dRear = (0.011 * sampleRate).toInt()
    private val dTopR = (0.017 * sampleRate).toInt()
    private val difRearL = Diffuser((0.0031 * sampleRate).toInt())
    private val difRearR = Diffuser((0.0037 * sampleRate).toInt())
    private val difTopFL = Diffuser((0.0023 * sampleRate).toInt())
    private val difTopFR = Diffuser((0.0029 * sampleRate).toInt())
    private val difTopRL = Diffuser((0.0041 * sampleRate).toInt())
    private val difTopRR = Diffuser((0.0047 * sampleRate).toInt())

    // room
    private val preSize = (0.3 * sampleRate).toInt()
    private val preL = FloatArray(preSize)
    private val preR = FloatArray(preSize)
    private var preW = 0
    private val erDelay = IntArray(ER_MS.size)
    private var fdnDelay = 0
    private val fdn = Fdn(sampleRate)
    private val fdnOut = FloatArray(8)

    // headphone correction
    private val fixL = Array(FIX_F.size) { Biquad() }
    private val fixR = Array(FIX_F.size) { Biquad() }

    // derived gains
    private var gCenter = 1f
    private var gSide = 0.7f
    private var gRear = 0.6f
    private var gTopF = 0.5f
    private var gTopR = 0.4f
    private var erGain = 0.3f
    private var lateGain = 0.2f
    private var subGain = 1f
    private var master = 0.6f
    private var fixOn = true
    private var fixGain = 1f

    var earL = 0f
        private set
    var earR = 0f
        private set

    init {
        hpL1.setHighPass(fsD, 80.0); hpL2.setHighPass(fsD, 80.0)
        hpR1.setHighPass(fsD, 80.0); hpR2.setHighPass(fsD, 80.0)
        lp1.setLowPass(fsD, 80.0); lp2.setLowPass(fsD, 80.0)
        for (i in FIX_F.indices) {
            for (b in arrayOf(fixL[i], fixR[i])) {
                when (FIX_TYPE[i]) {
                    1 -> b.setLowShelf(fsD, FIX_F[i], FIX_G[i], FIX_Q[i])
                    2 -> b.setHighShelf(fsD, FIX_F[i], FIX_G[i], FIX_Q[i])
                    else -> b.setPeaking(fsD, FIX_F[i], FIX_Q[i], FIX_G[i])
                }
            }
        }
        configure(TheaterParams())
    }

    private fun db(v: Double) = 10.0.pow(v / 20.0).toFloat()

    fun configure(p: TheaterParams) {
        val imm = p.immersion.coerceIn(0f, 1f)
        val room = ROOMS[p.room.coerceIn(0, ROOMS.size - 1)]

        val width = frontWidth(imm)
        for (i in 0 until SPEAKERS) {
            val pos = position(i, width)
            speakers[i].place(pos[0], pos[1])
        }

        upmix.ambienceGain = 1.5 + 0.6 * imm
        upmix.centerPower = 8.0 - 6.0 * p.vocal.coerceIn(0f, 1f)
        gCenter = 0.85f + 0.25f * p.vocal
        gSide = 0.6f + 0.3f * imm
        gRear = 0.4f + 0.4f * imm
        gTopF = 0.25f + 0.5f * imm
        gTopR = 0.2f + 0.45f * imm

        val size = room[0] * (1.0 + 0.25 * imm)
        val rt60 = room[1] * (0.85 + 0.4 * imm)
        val pre = room[2] + 0.012 * imm
        val wet = (room[4] * (0.55 + 0.9 * imm)).toFloat()
        erGain = wet * 1.1f
        lateGain = wet * 0.55f
        for (i in ER_MS.indices) {
            erDelay[i] = (ER_MS[i] * size * fs / 1000.0).toInt().coerceIn(1, preSize - 1)
        }
        fdnDelay = (pre * fs).toInt().coerceIn(1, preSize - 1)
        fdn.configure(size, rt60, room[3].toFloat())

        subGain = db(-4.0 + 12.0 * p.sub)
        fixOn = p.headphoneFix
        fixGain = db(-3.0)
        master = 0.9f
    }

    fun reset() {
        upmix.reset()
        speakers.forEach { it.reset() }
        subRing.fill(0f); ambL.fill(0f); ambR.fill(0f); preL.fill(0f); preR.fill(0f)
        fdn.reset()
        for (d in arrayOf(difRearL, difRearR, difTopFL, difTopFR, difTopRL, difTopRR)) d.reset()
        for (b in arrayOf(hpL1, hpL2, hpR1, hpR2, lp1, lp2)) b.reset()
        fixL.forEach { it.reset() }; fixR.forEach { it.reset() }
    }

    /** Main path: interleaved stereo in, binaural stereo out (in place). */
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
            val sub = subRing[subW]   // oldest sample = delayed by the upmix latency

            upmix.process(hl, hr, up)
            val c = up[0]; val fl = up[1]; val fr = up[2]; val al = up[3]; val ar = up[4]

            ambL[ambW] = al
            ambR[ambW] = ar
            sIn[C] = c * gCenter
            sIn[FL] = fl
            sIn[FR] = fr
            sIn[SL] = al * gSide
            sIn[SR] = ar * gSide
            sIn[RL] = difRearL.process(amb(ambL, dRear)) * gRear
            sIn[RR] = difRearR.process(amb(ambR, dRear)) * gRear
            sIn[TFL] = difTopFL.process(amb(ambL, dTopF)) * gTopF
            sIn[TFR] = difTopFR.process(amb(ambR, dTopF)) * gTopF
            sIn[TRL] = difTopRL.process(amb(ambL, dTopR)) * gTopR
            sIn[TRR] = difTopRR.process(amb(ambR, dTopR)) * gTopR
            ambW++
            if (ambW >= ambSize) ambW = 0

            render(fl + 0.7f * c + 0.5f * al, fr + 0.7f * c + 0.5f * ar)

            var outL = earL * master + sub * subGain
            var outR = earR * master + sub * subGain
            if (fixOn) {
                outL *= fixGain; outR *= fixGain
                for (b in fixL) outL = b.process(outL)
                for (b in fixR) outR = b.process(outR)
            }
            buf[2 * n] = outL
            buf[2 * n + 1] = outR
        }
    }

    /** Speaker test: mono signal played from one virtual speaker (with the room). */
    fun processTest(input: FloatArray, out: FloatArray, frames: Int, speaker: Int) {
        for (n in 0 until frames) {
            val x = input[n]
            sIn.fill(0f)
            sIn[speaker.coerceIn(0, SPEAKERS - 1)] = x
            render(x * 0.5f, x * 0.5f)
            var outL = earL * master
            var outR = earR * master
            if (fixOn) {
                outL *= fixGain; outR *= fixGain
                for (b in fixL) outL = b.process(outL)
                for (b in fixR) outR = b.process(outR)
            }
            out[2 * n] = outL
            out[2 * n + 1] = outR
        }
    }

    private fun amb(ring: FloatArray, delay: Int): Float {
        var i = ambW - delay
        if (i < 0) i += ambSize
        return ring[i]
    }

    /** Adds the cinema room into the speaker feeds, then renders all speakers to the ears. */
    private fun render(srcL: Float, srcR: Float) {
        preL[preW] = srcL
        preR[preW] = srcR

        val eg = erGain
        for (t in erDelay.indices) {
            var i = preW - erDelay[t]
            if (i < 0) i += preSize
            val v = when (ER_SRC[t]) {
                0 -> preL[i]
                1 -> preR[i]
                else -> (preL[i] + preR[i]) * 0.5f
            }
            sIn[ER_SPK[t]] += v * ER_GAIN[t] * eg
        }

        var j = preW - fdnDelay
        if (j < 0) j += preSize
        fdn.process((preL[j] + preR[j]) * 0.5f, fdnOut)
        val lg = lateGain
        sIn[SL] += fdnOut[0] * lg
        sIn[SR] += fdnOut[1] * lg
        sIn[RL] += fdnOut[2] * lg
        sIn[RR] += fdnOut[3] * lg
        sIn[TFL] += fdnOut[4] * lg
        sIn[TFR] += fdnOut[5] * lg
        sIn[TRL] += fdnOut[6] * lg
        sIn[TRR] += fdnOut[7] * lg
        sIn[FL] += (fdnOut[0] + fdnOut[5]) * 0.3f * lg
        sIn[FR] += (fdnOut[1] + fdnOut[4]) * 0.3f * lg

        preW++
        if (preW >= preSize) preW = 0

        var el = 0f
        var er = 0f
        for (i in 0 until SPEAKERS) {
            val s = speakers[i]
            s.process(sIn[i])
            el += s.outL
            er += s.outR
        }
        earL = el
        earR = er
    }
}
