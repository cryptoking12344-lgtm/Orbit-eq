package com.ck.orbiteq.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/** Radix-2 FFT returning magnitudes of a Hann-windowed frame. */
class Fft(private val n: Int) {
    private val cosT = FloatArray(n / 2) { cos(2.0 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n / 2) { sin(2.0 * PI * it / n).toFloat() }
    private val window = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / (n - 1))).toFloat() }
    private val re = FloatArray(n)
    private val im = FloatArray(n)

    fun magnitudes(input: FloatArray, out: FloatArray) {
        for (i in 0 until n) {
            re[i] = input[i] * window[i]
            im[i] = 0f
        }
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
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                for (k in 0 until half) {
                    val t = k * step
                    val a = start + k
                    val b = a + half
                    val tr = re[b] * cosT[t] + im[b] * sinT[t]
                    val ti = im[b] * cosT[t] - re[b] * sinT[t]
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                start += size
            }
            size = size shl 1
        }
        for (k in 0 until n / 2) out[k] = hypot(re[k], im[k])
    }
}

/** Groups FFT bins into log-spaced bars (0..1) for the visualizer. */
object Spectrum {
    const val BANDS = 28
    private const val F_MIN = 40.0
    private const val F_MAX = 16000.0

    private fun edge(i: Int) = F_MIN * (F_MAX / F_MIN).pow(i.toDouble() / BANDS)

    fun fromMagnitudes(
        mags: FloatArray,
        sampleRate: Int,
        fftSize: Int,
        fullScale: Float,
        floorDb: Float,
        out: FloatArray,
    ) {
        if (sampleRate <= 0 || mags.size < 2) return
        val binHz = sampleRate.toDouble() / fftSize
        for (b in 0 until BANDS) {
            val lo = (edge(b) / binHz).toInt().coerceIn(1, mags.size - 1)
            val hi = (edge(b + 1) / binHz).toInt().coerceIn(lo, mags.size - 1)
            var m = 0f
            for (k in lo..hi) if (mags[k] > m) m = mags[k]
            val db = 20f * log10(m / fullScale + 1e-9f)
            out[b] = ((db - floorDb) / -floorDb).coerceIn(0f, 1f)
        }
    }

    /** For android.media.audiofx.Visualizer FFT bytes. */
    fun fromVisualizerFft(fft: ByteArray, samplingRateMilliHz: Int, out: FloatArray) {
        val n = fft.size
        if (n < 4) return
        val half = n / 2
        val mags = FloatArray(half)
        mags[0] = abs(fft[0].toFloat())
        for (k in 1 until half) {
            mags[k] = hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat())
        }
        fromMagnitudes(mags, samplingRateMilliHz / 1000, n, 128f, -55f, out)
    }
}

/** Feeds processed audio into the FFT ~30 times per second. */
class Analyzer(private val sampleRate: Int) {
    private val n = 1024
    private val fft = Fft(n)
    private val ring = FloatArray(n)
    private var w = 0
    private val frame = FloatArray(n)
    private val mags = FloatArray(n / 2)
    private var sinceLast = 0
    private val interval = sampleRate / 30

    fun feed(stereo: FloatArray, frames: Int): FloatArray? {
        for (i in 0 until frames) {
            ring[w] = (stereo[2 * i] + stereo[2 * i + 1]) * 0.5f
            w = (w + 1) and (n - 1)
        }
        sinceLast += frames
        if (sinceLast < interval) return null
        sinceLast = 0
        for (i in 0 until n) frame[i] = ring[(w + i) and (n - 1)]
        fft.magnitudes(frame, mags)
        val out = FloatArray(Spectrum.BANDS)
        Spectrum.fromMagnitudes(mags, sampleRate, n, n / 4f, -70f, out)
        return out
    }
}
