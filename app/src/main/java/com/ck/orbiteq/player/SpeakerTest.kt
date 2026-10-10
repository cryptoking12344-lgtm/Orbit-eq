package com.ck.orbiteq.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.ck.orbiteq.dsp.Space360Processor
import com.ck.orbiteq.dsp.TheaterProcessor
import com.ck.orbiteq.model.AudioState
import java.util.Random
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Speaker check: a soft plucked-string arpeggio from one virtual speaker at a
 * time. Going around all speakers plays a rising pentatonic melody.
 */
object SpeakerTest {

    interface Listener {
        /** Speaker now playing, or -1 when the test finishes. */
        fun onTestSpeaker(index: Int)
    }

    private const val FS = 48000
    private const val SECONDS_PER_SPEAKER = 1.35
    /** Pentatonic steps (semitones above C4) — one per speaker in test order. */
    private val SCALE = intArrayOf(0, 2, 4, 7, 9, 12, 14, 16, 19, 21, 24, 26)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var stopFlag = false
    private var worker: Thread? = null
    var listener: Listener? = null

    val isRunning: Boolean get() = worker?.isAlive == true

    private fun is360() = AudioState.mode == AudioState.MODE_360

    fun playOne(speaker: Int) {
        val order = if (is360()) Space360Processor.TEST_ORDER else TheaterProcessor.TEST_ORDER
        val step = order.indexOf(speaker).coerceAtLeast(0)
        start(intArrayOf(speaker), step)
    }

    fun playAll() = start(if (is360()) Space360Processor.TEST_ORDER else TheaterProcessor.TEST_ORDER, 0)

    fun stop() {
        stopFlag = true
        try {
            worker?.join(500)
        } catch (_: InterruptedException) {
        }
        worker = null
    }

    private fun start(order: IntArray, firstStep: Int) {
        stop()
        if (PlayerEngine.state.playing) PlayerEngine.pause()
        stopFlag = false
        val space = is360()
        val t = Thread({ run(order, firstStep, space) }, "orbit-speaker-test")
        worker = t
        t.start()
    }

    private fun run(order: IntArray, firstStep: Int, space: Boolean) {
        var track: AudioTrack? = null
        try {
            val minBuf = AudioTrack.getMinBufferSize(FS, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            val tr = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(FS)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBuf, 8192) * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = tr
            tr.play()

            val theater = if (space) null else TheaterProcessor(FS).also { it.configure(AudioState.theater.toParams()) }
            val sphere = if (space) Space360Processor(FS).also { it.configure(AudioState.space.toParams(AudioState.theater)) } else null
            fun render(input: FloatArray, out: FloatArray, n: Int, spk: Int) {
                if (sphere != null) sphere.processTest(input, out, n, spk) else theater!!.processTest(input, out, n, spk)
            }

            val block = 512
            val input = FloatArray(block)
            val out = FloatArray(block * 2)
            val synth = PluckSynth(FS)
            val perSpeaker = (SECONDS_PER_SPEAKER * FS).toInt()
            val noteGap = (0.24 * FS).toInt()

            for ((k, spk) in order.withIndex()) {
                if (stopFlag) break
                main.post { listener?.onTestSpeaker(spk) }
                val root = SCALE[(firstStep + k) % SCALE.size]
                // root, fifth, octave
                val notes = intArrayOf(root, root + 7, root + 12)
                var done = 0
                while (done < perSpeaker && !stopFlag) {
                    val n = minOf(block, perSpeaker - done)
                    for (i in 0 until n) {
                        val t = done + i
                        if (t % noteGap == 0) {
                            val idx = t / noteGap
                            if (idx < notes.size) synth.pluck(midiToHz(60 + notes[idx]), if (idx == 0) 0.55f else 0.42f)
                        }
                        input[i] = synth.next()
                    }
                    render(input, out, n, spk)
                    tr.write(out, 0, n * 2, AudioTrack.WRITE_BLOCKING)
                    done += n
                }
            }
            // let the notes and room ring out
            if (!stopFlag) {
                val last = order.lastOrNull() ?: 0
                repeat((0.7 * FS / block).toInt()) {
                    for (i in 0 until block) input[i] = synth.next()
                    render(input, out, block, last)
                    tr.write(out, 0, block * 2, AudioTrack.WRITE_BLOCKING)
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                track?.stop()
                track?.release()
            } catch (_: Exception) {
            }
            main.post { listener?.onTestSpeaker(-1) }
        }
    }

    private fun midiToHz(m: Int) = 440f * 2f.pow((m - 69) / 12f)

    /** Karplus–Strong plucked string, a few overlapping voices. */
    private class PluckSynth(private val fs: Int) {
        private class Voice(size: Int) {
            val buf = FloatArray(size)
            var len = 1
            var pos = 0
            var active = false
            var decay = 0.996f
        }

        private val voices = Array(4) { Voice(fs / 40) }
        private var nextVoice = 0
        private val rnd = Random(3)

        fun pluck(freq: Float, amp: Float) {
            val v = voices[nextVoice]
            nextVoice = (nextVoice + 1) % voices.size
            v.len = (fs / freq).roundToInt().coerceIn(2, v.buf.size)
            v.pos = 0
            v.active = true
            // longer sustain for low notes, a little shorter for high ones
            v.decay = (0.9985f - freq / 400000f).coerceIn(0.990f, 0.9985f)
            // soft, slightly smoothed noise burst = warm pick attack
            var prev = 0f
            for (i in 0 until v.len) {
                val x = (rnd.nextFloat() * 2f - 1f) * amp
                prev = prev * 0.45f + x * 0.55f
                v.buf[i] = prev
            }
        }

        fun next(): Float {
            var s = 0f
            for (v in voices) {
                if (!v.active) continue
                val i = v.pos
                val j = if (i + 1 >= v.len) 0 else i + 1
                val out = v.buf[i]
                v.buf[i] = (out + v.buf[j]) * 0.5f * v.decay
                v.pos = j
                s += out
            }
            return s
        }
    }
}
