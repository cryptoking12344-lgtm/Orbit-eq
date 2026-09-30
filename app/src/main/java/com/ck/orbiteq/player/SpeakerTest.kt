package com.ck.orbiteq.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.ck.orbiteq.dsp.TheaterProcessor
import com.ck.orbiteq.model.AudioState
import java.util.Random
import kotlin.math.max

/**
 * Plays pulsed pink noise from one virtual speaker at a time, like the
 * speaker check on a cinema amplifier, through the current Theater settings.
 */
object SpeakerTest {

    interface Listener {
        /** Speaker now playing, or -1 when the test finishes. */
        fun onTestSpeaker(index: Int)
    }

    private const val FS = 48000
    private const val SECONDS_PER_SPEAKER = 1.6
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var stopFlag = false
    private var worker: Thread? = null
    var listener: Listener? = null

    val isRunning: Boolean get() = worker?.isAlive == true

    fun playOne(speaker: Int) = start(intArrayOf(speaker))

    fun playAll() = start(TheaterProcessor.TEST_ORDER)

    fun stop() {
        stopFlag = true
        try {
            worker?.join(500)
        } catch (_: InterruptedException) {
        }
        worker = null
    }

    private fun start(order: IntArray) {
        stop()
        if (PlayerEngine.state.playing) PlayerEngine.pause()
        stopFlag = false
        val t = Thread({ run(order) }, "orbit-speaker-test")
        worker = t
        t.start()
    }

    private fun run(order: IntArray) {
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

            val proc = TheaterProcessor(FS)
            proc.configure(AudioState.theater.toParams())
            val block = 512
            val input = FloatArray(block)
            val out = FloatArray(block * 2)
            val noise = PinkNoise()
            val perSpeaker = (SECONDS_PER_SPEAKER * FS).toInt()
            val burst = (0.18 * FS).toInt()
            val gap = (0.07 * FS).toInt()

            for (spk in order) {
                if (stopFlag) break
                main.post { listener?.onTestSpeaker(spk) }
                var done = 0
                while (done < perSpeaker && !stopFlag) {
                    val n = minOf(block, perSpeaker - done)
                    for (i in 0 until n) {
                        val pos = (done + i) % (burst + gap)
                        // short fades avoid clicks
                        val env = when {
                            pos >= burst -> 0f
                            pos < 240 -> pos / 240f
                            pos > burst - 240 -> (burst - pos) / 240f
                            else -> 1f
                        }
                        input[i] = noise.next() * env * 0.8f
                    }
                    proc.processTest(input, out, n, spk)
                    tr.write(out, 0, n * 2, AudioTrack.WRITE_BLOCKING)
                    done += n
                }
            }
            // let the room tail ring out briefly
            if (!stopFlag) {
                input.fill(0f)
                repeat((0.5 * FS / block).toInt()) {
                    proc.processTest(input, out, block, 0)
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

    /** Paul Kellet's pink noise filter. */
    private class PinkNoise {
        private val r = Random()
        private var b0 = 0f; private var b1 = 0f; private var b2 = 0f; private var b3 = 0f
        private var b4 = 0f; private var b5 = 0f; private var b6 = 0f

        fun next(): Float {
            val w = (r.nextFloat() * 2f - 1f) * 0.3f
            b0 = 0.99886f * b0 + w * 0.0555179f
            b1 = 0.99332f * b1 + w * 0.0750759f
            b2 = 0.96900f * b2 + w * 0.1538520f
            b3 = 0.86650f * b3 + w * 0.3104856f
            b4 = 0.55000f * b4 + w * 0.5329522f
            b5 = -0.7616f * b5 - w * 0.0168980f
            val v = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362f
            b6 = w * 0.115926f
            return v * 0.25f
        }
    }
}
