package com.ck.orbiteq.youtube

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs on the build server against real YouTube. It checks that the
 * library still works and that our downloader gets real audio bytes.
 * (YouTube sometimes blocks data-centre servers, so CI doesn't fail on it.)
 */
class YtSmokeTest {

    @Test
    fun linkFinding() {
        assertTrue(YtCore.findUrl("Check this https://youtu.be/jNQXAC9IVRw?si=abc out") == "https://youtu.be/jNQXAC9IVRw?si=abc")
        assertTrue(YtCore.findUrl("https://music.youtube.com/watch?v=jNQXAC9IVRw") != null)
        assertTrue(YtCore.findUrl("no link here") == null)
    }

    @Test
    fun resolveAndDownload() {
        val pick = YtCore.resolve("https://www.youtube.com/watch?v=jNQXAC9IVRw")
        println("YT_SMOKE resolved: '${pick.title}' by ${pick.uploader}, itag=${pick.itag}, ext=${pick.ext}, bitrate=${pick.bitrate}, duration=${pick.durationSec}s")
        val f = File.createTempFile("yt", "." + pick.ext)
        YtCore.download(pick, f) { done, total ->
            println("YT_SMOKE progress $done / $total")
            true
        }
        println("YT_SMOKE downloaded ${f.length()} bytes")
        val head = f.readBytes().copyOfRange(0, 12)
        println("YT_SMOKE header: " + head.joinToString(" ") { "%02x".format(it) })
        assertTrue(f.length() > 50_000)
        f.delete()
    }
}
