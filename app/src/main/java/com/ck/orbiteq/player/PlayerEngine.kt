package com.ck.orbiteq.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.ck.orbiteq.dsp.Analyzer
import com.ck.orbiteq.dsp.DspChain
import com.ck.orbiteq.model.AudioState
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max

/**
 * Decodes a song with MediaCodec, runs it through our own DSP chain
 * (EQ, bass, 8D, surround, reverb, limiter) and plays it with AudioTrack.
 */
object PlayerEngine {

    interface Listener {
        fun onPlaybackState(state: State)
        fun onSpectrum(bands: FloatArray)
    }

    data class State(
        val title: String? = null,
        val loaded: Boolean = false,
        val playing: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val error: String? = null,
    )

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val lock = ReentrantLock()
    private val resumed = lock.newCondition()

    /** Only changed on the main thread. */
    var state = State()
        private set

    private var worker: Thread? = null
    @Volatile private var stopFlag = false
    @Volatile private var paused = false
    @Volatile private var seekUs = -1L
    private var appContext: Context? = null
    private var uri: Uri? = null
    private var focusRequest: AudioFocusRequest? = null

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private fun publish() {
        val s = state
        for (l in listeners) l.onPlaybackState(s)
    }

    private fun update(block: (State) -> State) {
        main.post {
            state = block(state)
            publish()
        }
    }

    fun load(ctx: Context, uri: Uri, title: String) {
        stopWorker()
        appContext = ctx.applicationContext
        this.uri = uri
        seekUs = -1L
        state = State(title = title, loaded = true)
        publish()
    }

    fun play() {
        val ctx = appContext ?: return
        val u = uri ?: return
        if (!requestFocus(ctx)) return
        if (worker?.isAlive == true) {
            lock.withLock {
                paused = false
                resumed.signalAll()
            }
        } else {
            stopFlag = false
            paused = false
            val t = Thread({ decodeLoop(ctx, u) }, "orbit-decoder")
            t.priority = Thread.MAX_PRIORITY
            worker = t
            t.start()
        }
        if (!PlaybackService.running) PlaybackService.start(ctx)
        state = state.copy(playing = true, error = null)
        publish()
    }

    fun pause() {
        paused = true
        state = state.copy(playing = false)
        publish()
    }

    fun togglePlay() {
        if (state.playing) pause() else play()
    }

    fun stop() {
        stopWorker()
        abandonFocus()
        state = state.copy(playing = false, positionMs = 0)
        publish()
        appContext?.let { PlaybackService.stop(it) }
    }

    fun seekTo(ms: Long) {
        seekUs = max(0L, ms) * 1000L
        state = state.copy(positionMs = ms)
        publish()
    }

    private fun stopWorker() {
        stopFlag = true
        lock.withLock { resumed.signalAll() }
        try {
            worker?.join(800)
        } catch (_: InterruptedException) {
        }
        worker = null
    }

    // ---------------- audio focus ----------------

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> main.post { if (state.playing) pause() }
        }
    }

    private fun requestFocus(ctx: Context): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return true
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes())
            .setOnAudioFocusChangeListener(focusListener, main)
            .build()
            .also { focusRequest = it }
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val ctx = appContext ?: return
        val req = focusRequest ?: return
        ctx.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(req)
    }

    private fun attributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private fun createTrack(sampleRate: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT
        )
        return AudioTrack.Builder()
            .setAudioAttributes(attributes())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuf, 8192) * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    // ---------------- decoder thread ----------------

    private fun decodeLoop(ctx: Context, uri: Uri) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        var endedNaturally = false
        try {
            val ex = MediaExtractor()
            extractor = ex
            ex.setDataSource(ctx, uri, null)
            var trackIndex = -1
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    break
                }
            }
            if (trackIndex < 0) throw IllegalStateException("No audio found in this file")
            ex.selectTrack(trackIndex)
            val inFormat = ex.getTrackFormat(trackIndex)
            val durationMs =
                if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
            update { it.copy(durationMs = durationMs) }

            val mime = inFormat.getString(MediaFormat.KEY_MIME) ?: throw IllegalStateException("Unknown format")
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(inFormat, null, null, 0)
            c.start()

            var sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var dsp = DspChain(sampleRate)
            var analyzer = Analyzer(sampleRate)
            var dspVersion = -1
            var stereo = FloatArray(8192)
            var lastPosUs = -1_000_000L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone && !stopFlag) {
                if (paused) {
                    track?.pause()
                    lock.withLock {
                        while (paused && !stopFlag) resumed.await(250, TimeUnit.MILLISECONDS)
                    }
                    if (stopFlag) break
                    track?.play()
                }

                val target = seekUs
                if (target >= 0) {
                    seekUs = -1L
                    ex.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    c.flush()
                    inputDone = false
                    track?.let {
                        it.pause()
                        it.flush()
                        it.play()
                    }
                    dsp.reset()
                }

                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(5000)
                    if (inIdx >= 0) {
                        val inBuf = c.getInputBuffer(inIdx)
                        val size = if (inBuf != null) ex.readSampleData(inBuf, 0) else -1
                        if (size < 0) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }

                val outIdx = c.dequeueOutputBuffer(info, 5000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat
                    val newRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING))
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    if (newRate != sampleRate || track == null) {
                        sampleRate = newRate
                        track?.release()
                        track = null
                        dsp = DspChain(sampleRate)
                        analyzer = Analyzer(sampleRate)
                        dspVersion = -1
                    }
                } else if (outIdx >= 0) {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    val outBuf = c.getOutputBuffer(outIdx)
                    if (info.size > 0 && outBuf != null) {
                        val t = track ?: createTrack(sampleRate).also {
                            it.play()
                            track = it
                        }
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        outBuf.order(ByteOrder.nativeOrder())

                        val ch = max(1, channels)
                        val frames: Int
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val fb = outBuf.asFloatBuffer()
                            frames = fb.remaining() / ch
                            if (stereo.size < frames * 2) stereo = FloatArray(frames * 2)
                            for (i in 0 until frames) {
                                val base = i * ch
                                toStereo(stereo, i, ch) { k -> fb.get(base + k) }
                            }
                        } else {
                            val sb = outBuf.asShortBuffer()
                            frames = sb.remaining() / ch
                            if (stereo.size < frames * 2) stereo = FloatArray(frames * 2)
                            for (i in 0 until frames) {
                                val base = i * ch
                                toStereo(stereo, i, ch) { k -> sb.get(base + k) / 32768f }
                            }
                        }

                        val v = AudioState.version
                        if (v != dspVersion) {
                            dspVersion = v
                            dsp.configure(AudioState.eq, AudioState.eightD, AudioState.theater)
                        }
                        dsp.process(stereo, frames)

                        val bands = analyzer.feed(stereo, frames)
                        if (bands != null) main.post { for (l in listeners) l.onSpectrum(bands) }

                        var written = 0
                        val total = frames * 2
                        while (written < total && !stopFlag && seekUs < 0) {
                            val n = t.write(stereo, written, total - written, AudioTrack.WRITE_BLOCKING)
                            if (n <= 0) break
                            written += n
                        }

                        val pos = info.presentationTimeUs
                        if (pos - lastPosUs > 250_000L || pos < lastPosUs) {
                            lastPosUs = pos
                            update { it.copy(positionMs = pos / 1000) }
                        }
                    }
                    c.releaseOutputBuffer(outIdx, false)
                }
            }
            endedNaturally = outputDone && !stopFlag
            if (endedNaturally) {
                try {
                    Thread.sleep(400)
                } catch (_: InterruptedException) {
                }
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Couldn't play this file"
            update { it.copy(playing = false, error = msg) }
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
            try {
                track?.stop()
                track?.release()
            } catch (_: Exception) {}
        }
        if (endedNaturally) {
            main.post {
                state = state.copy(playing = false, positionMs = 0)
                publish()
                PlaybackService.stop(ctx)
            }
        }
    }

    /** Converts one frame of any channel layout to stereo. */
    private inline fun toStereo(out: FloatArray, frame: Int, ch: Int, get: (Int) -> Float) {
        val l: Float
        val r: Float
        when {
            ch == 1 -> {
                l = get(0); r = l
            }
            ch >= 6 -> {
                // 5.1: FL FR FC LFE BL BR
                val c = get(2) * 0.707f
                l = (get(0) + c + get(4) * 0.707f) * 0.6f
                r = (get(1) + c + get(5) * 0.707f) * 0.6f
            }
            else -> {
                l = get(0); r = get(1)
            }
        }
        out[2 * frame] = l
        out[2 * frame + 1] = r
    }
}
