package com.ck.orbiteq.library

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Album art, loaded in the background and cached. */
object ArtLoader {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val pending = ConcurrentHashMap<String, MutableList<(Bitmap?) -> Unit>>()
    private val exec = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    private fun key(albumId: Long, size: Int) = "$albumId@$size"

    fun cached(albumId: Long, size: Int): Bitmap? = cache.get(key(albumId, size))

    /** Calls back on the main thread (with null when the album has no art). */
    fun load(ctx: Context, albumId: Long, size: Int, trackUri: String?, cb: (Bitmap?) -> Unit) {
        if (albumId <= 0 && trackUri == null) { cb(null); return }
        val k = key(albumId, size)
        cache.get(k)?.let { cb(it); return }
        if (missing.contains(k)) { cb(null); return }
        val waiting = pending[k]
        if (waiting != null) { synchronized(waiting) { waiting.add(cb) }; return }
        val list = mutableListOf(cb)
        pending[k] = list
        val app = ctx.applicationContext
        exec.execute {
            val bmp = try { loadSync(app, albumId, size, trackUri) } catch (_: Throwable) { null }
            if (bmp != null) cache.put(k, bmp) else missing.add(k)
            main.post {
                val cbs = pending.remove(k) ?: return@post
                synchronized(cbs) { for (c in cbs) c(bmp) }
            }
        }
    }

    /** Blocking load — only call from a background thread. */
    fun loadSync(ctx: Context, albumId: Long, size: Int, trackUri: String?): Bitmap? {
        cache.get(key(albumId, size))?.let { return it }
        val cr = ctx.contentResolver
        if (albumId > 0) {
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    val u = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId)
                    return cr.loadThumbnail(u, Size(size, size), null).also { cache.put(key(albumId, size), it) }
                } catch (_: Exception) {
                }
            } else {
                try {
                    val u = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
                    cr.openInputStream(u)?.use { input ->
                        val bytes = input.readBytes()
                        decode(bytes, size)?.let { cache.put(key(albumId, size), it); return it }
                    }
                } catch (_: Exception) {
                }
            }
        }
        if (trackUri != null) {
            val r = MediaMetadataRetriever()
            try {
                val u = Uri.parse(trackUri)
                if (u.scheme == "file") r.setDataSource(u.path) else r.setDataSource(ctx, u)
                r.embeddedPicture?.let { pic -> decode(pic, size)?.let { cache.put(key(albumId, size), it); return it } }
            } catch (_: Exception) {
            } finally {
                try { r.release() } catch (_: Exception) {}
            }
        }
        return null
    }

    private fun decode(bytes: ByteArray, size: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= size && o.outHeight / (sample * 2) >= size) sample *= 2
        val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        if (full.width == size && full.height == size) return full
        val side = minOf(full.width, full.height)
        val sq = Bitmap.createBitmap(full, (full.width - side) / 2, (full.height - side) / 2, side, side)
        return Bitmap.createScaledBitmap(sq, size, size, true)
    }
}
