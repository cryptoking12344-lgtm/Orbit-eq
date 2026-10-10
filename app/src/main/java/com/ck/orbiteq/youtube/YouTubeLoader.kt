package com.ck.orbiteq.youtube

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors

/** Finds a YouTube video's audio, saves it to the cache and hands back the file. */
object YouTubeLoader {

    interface Listener {
        /** progress: 0..100, or -1 while it's still looking the video up. */
        fun onYtProgress(message: String, progress: Int)
        fun onYtReady(file: File, title: String)
        fun onYtError(message: String)
    }

    private const val CACHE_LIMIT = 500L * 1024 * 1024
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var job = 0
    @Volatile var busy = false
        private set
    var listener: Listener? = null

    fun findUrl(text: String?) = YtCore.findUrl(text)

    fun cancel() {
        job++
        busy = false
    }

    fun load(ctx: Context, link: String) {
        val id = ++job
        busy = true
        val app = ctx.applicationContext
        post(id) { it.onYtProgress("Finding the video…", -1) }
        exec.execute {
            try {
                val pick = YtCore.resolve(link)
                if (id != job) return@execute
                val dir = File(app.cacheDir, "youtube").apply { mkdirs() }
                val file = File(dir, "${pick.id}_${pick.itag}.${pick.ext}")
                if (!file.exists() || file.length() < 1024) {
                    val part = File(dir, file.name + ".part")
                    post(id) { it.onYtProgress("Loading “${pick.title}”", 0) }
                    var lastPct = -1
                    YtCore.download(pick, part) { done, total ->
                        if (id != job) return@download false
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != lastPct) {
                            lastPct = pct
                            post(id) { it.onYtProgress("Loading “${pick.title}”", pct) }
                        }
                        true
                    }
                    if (!part.renameTo(file)) throw YtException("Couldn't save the audio")
                }
                file.setLastModified(System.currentTimeMillis())
                trimCache(dir)
                post(id) {
                    busy = false
                    it.onYtReady(file, pick.title)
                }
            } catch (e: YtCancelled) {
                // user started something else
            } catch (e: YtException) {
                post(id) {
                    busy = false
                    it.onYtError(e.message ?: "Something went wrong")
                }
            } catch (e: Throwable) {
                post(id) {
                    busy = false
                    it.onYtError("Something went wrong: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
                }
            }
        }
    }

    private fun post(id: Int, block: (Listener) -> Unit) {
        main.post {
            if (id != job) return@post
            listener?.let(block)
        }
    }

    /** Keeps the YouTube cache under 500 MB by removing the oldest songs. */
    private fun trimCache(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") } ?: return
        var size = files.sumOf { it.length() }
        for (f in files.sortedBy { it.lastModified() }) {
            if (size <= CACHE_LIMIT) break
            size -= f.length()
            f.delete()
        }
    }
}
