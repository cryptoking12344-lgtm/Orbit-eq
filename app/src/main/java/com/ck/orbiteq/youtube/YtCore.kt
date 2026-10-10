package com.ck.orbiteq.youtube

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Message that is safe to show the user as-is. */
class YtException(message: String, cause: Throwable? = null) : Exception(message, cause)

class YtCancelled : Exception("cancelled")

/**
 * Plain-JVM YouTube helper (no Android classes) so it can also run as a
 * unit test on the build server.
 */
object YtCore {

    const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    private const val CHUNK = 2L * 1024 * 1024
    private val POST_BODY = byteArrayOf(0x78, 0)

    class Pick(
        val id: String,
        val title: String,
        val uploader: String,
        val url: String,
        val ext: String,
        val itag: Int,
        val bitrate: Int,
        val durationSec: Long,
    )

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Volatile private var ready = false

    @Synchronized
    fun init() {
        if (ready) return
        NewPipe.init(PageDownloader)
        ready = true
    }

    private val LINK = Regex("""https?://(?:www\.|m\.|music\.)?(?:youtube\.com|youtu\.be)/\S+""", RegexOption.IGNORE_CASE)

    /** Finds the first YouTube link inside any shared text. */
    fun findUrl(text: String?): String? = text?.let { LINK.find(it)?.value?.trimEnd('.', ',', ')', '"') }

    /** Looks up the video and picks the best audio stream for our decoder. */
    fun resolve(link: String): Pick {
        init()
        val info = try {
            StreamInfo.getInfo(ServiceList.YouTube, link)
        } catch (e: AgeRestrictedContentException) {
            throw YtException("Age-restricted videos can't be played without signing in", e)
        } catch (e: GeographicRestrictionException) {
            throw YtException("This video isn't available in your country", e)
        } catch (e: ContentNotAvailableException) {
            throw YtException("This video isn't available" + (e.message?.let { ": $it" } ?: ""), e)
        } catch (e: SignInConfirmNotBotException) {
            throw YtException("YouTube is asking to confirm you're not a bot. Try again later, or switch between Wi‑Fi and mobile data.", e)
        } catch (e: ReCaptchaException) {
            throw YtException("YouTube asked for a captcha. Try again in a little while.", e)
        } catch (e: ExtractionException) {
            throw YtException("Couldn't read this video. YouTube may have changed something, so the app may need an update.", e)
        } catch (e: IOException) {
            throw YtException("Network problem. Check your internet connection.", e)
        }

        if (info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM) {
            throw YtException("Live streams aren't supported yet")
        }

        var streams = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        if (streams.isEmpty()) throw YtException("No playable audio found for this video")

        // Prefer the original language track when a video has dubbed versions.
        val original = streams.filter { it.audioTrackType == AudioTrackType.ORIGINAL }
        if (original.isNotEmpty()) streams = original

        fun rate(s: AudioStream) = if (s.averageBitrate > 0) s.averageBitrate else s.bitrate
        // AAC (m4a) decodes on every phone; Opus/WebM is the fallback.
        val best = streams.filter { it.format == MediaFormat.M4A }.maxByOrNull { rate(it) }
            ?: streams.maxByOrNull { rate(it) }!!

        return Pick(
            id = info.id,
            title = info.name ?: "YouTube",
            uploader = info.uploaderName ?: "",
            url = best.content,
            ext = best.format?.getSuffix() ?: "audio",
            itag = best.itag,
            bitrate = rate(best),
            durationSec = info.duration,
        )
    }

    private fun userAgentFor(url: String): String = when {
        YoutubeParsingHelper.isIosStreamingUrl(url) -> YoutubeParsingHelper.getIosUserAgent(null)
        YoutubeParsingHelper.isAndroidStreamingUrl(url) -> YoutubeParsingHelper.getAndroidUserAgent(null)
        YoutubeParsingHelper.isVisionOsStreamingUrl(url) -> YoutubeParsingHelper.getVisionOsUserAgent(null)
        else -> DESKTOP_UA
    }

    /**
     * Downloads the audio in 2 MB pieces (the way YouTube's own players do).
     * [progress] gets (bytesDone, totalBytes or -1); return false to cancel.
     */
    fun download(pick: Pick, dest: File, progress: (Long, Long) -> Boolean) {
        val total = Regex("""[?&]clen=(\d+)""").find(pick.url)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
        var lastError: Exception? = null
        // Mode 0: POST + range in the URL (current YouTube players). Mode 1: plain GET + Range header.
        for (mode in 0..1) {
            try {
                fetchAll(pick.url, total, dest, mode, progress)
                return
            } catch (e: YtCancelled) {
                dest.delete()
                throw e
            } catch (e: Exception) {
                lastError = e
                dest.delete()
            }
        }
        throw YtException("Couldn't download the audio (${lastError?.message ?: "unknown error"}). Try again.", lastError)
    }

    private fun fetchAll(url: String, total: Long, dest: File, mode: Int, progress: (Long, Long) -> Boolean) {
        val ua = userAgentFor(url)
        val web = YoutubeParsingHelper.isWebStreamingUrl(url)
        var pos = 0L
        var rn = 1
        dest.outputStream().buffered().use { out ->
            while (total < 0 || pos < total) {
                val end = if (total > 0) minOf(pos + CHUNK, total) - 1 else pos + CHUNK - 1
                val b = okhttp3.Request.Builder().header("User-Agent", ua)
                if (mode == 0) {
                    b.url("$url&range=$pos-$end&rn=${rn++}")
                    b.post(POST_BODY.toRequestBody(null))
                } else {
                    b.url(url)
                    b.header("Range", "bytes=$pos-$end")
                }
                if (web) {
                    b.header("Origin", "https://www.youtube.com")
                    b.header("Referer", "https://www.youtube.com/")
                }
                var got = 0L
                http.newCall(b.build()).execute().use { resp ->
                    if (resp.code != 200 && resp.code != 206) throw IOException("HTTP ${resp.code}")
                    val input = resp.body?.byteStream() ?: throw IOException("empty response")
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                    }
                }
                if (got == 0L) break
                pos += got
                if (!progress(pos, total)) throw YtCancelled()
                if (total < 0 && got < CHUNK) break
            }
        }
        if (pos < 1024) throw IOException("file too small")
    }

    /** HTTP for the extractor's page and API requests (same behaviour as the NewPipe app). */
    private object PageDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val data = request.dataToSend()
            val body = when {
                data != null -> data.toRequestBody(null)
                request.httpMethod() == "POST" -> ByteArray(0).toRequestBody(null)
                else -> null
            }
            val b = okhttp3.Request.Builder()
                .method(request.httpMethod(), body)
                .url(request.url())
                .header("User-Agent", DESKTOP_UA)
            request.headers()?.forEach { (name, values) ->
                b.removeHeader(name)
                for (v in values) b.addHeader(name, v)
            }
            http.newCall(b.build()).execute().use { resp ->
                if (resp.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
                val text = resp.body?.string()
                return Response(resp.code, resp.message, resp.headers.toMultimap(), text, resp.request.url.toString())
            }
        }
    }
}
