package com.ck.orbiteq.library

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Reads the songs on the phone (Android's media library) and groups them. */
object MusicLibrary {

    val permission: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    @Volatile var songs: List<Track> = emptyList()
        private set
    @Volatile var albums: List<Group> = emptyList()
        private set
    @Volatile var artists: List<Group> = emptyList()
        private set
    @Volatile var genres: List<Group> = emptyList()
        private set
    @Volatile var folders: List<Group> = emptyList()
        private set
    @Volatile var loaded = false
        private set
    @Volatile var loading = false
        private set

    private val byId = HashMap<Long, Track>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun track(id: Long): Track? = synchronized(byId) { byId[id] }

    fun load(ctx: Context, force: Boolean = false) {
        if (!hasPermission(ctx)) return
        if (loading || (loaded && !force)) return
        loading = true
        val app = ctx.applicationContext
        exec.execute {
            val list = try { query(app) } catch (_: Exception) { emptyList() }
            val genreMap = if (Build.VERSION.SDK_INT < 30) {
                try { queryGenresOld(app) } catch (_: Exception) { emptyMap() }
            } else emptyMap()
            val full = if (genreMap.isEmpty()) list else list.map { t -> genreMap[t.id]?.let { t.copy(genre = it) } ?: t }
            val grouped = group(full)
            main.post {
                songs = full
                synchronized(byId) {
                    byId.clear()
                    for (t in full) byId[t.id] = t
                }
                albums = grouped[0]; artists = grouped[1]; genres = grouped[2]; folders = grouped[3]
                loaded = true
                loading = false
                for (l in listeners) l()
            }
        }
    }

    private fun query(ctx: Context): List<Track> {
        val cols = ArrayList(
            listOf(
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DATA,
            )
        )
        if (Build.VERSION.SDK_INT >= 29) cols.add(MediaStore.Audio.Media.RELATIVE_PATH)
        if (Build.VERSION.SDK_INT >= 30) cols.add(MediaStore.Audio.Media.GENRE)
        val out = ArrayList<Track>()
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        ctx.contentResolver.query(
            base, cols.toTypedArray(), "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            @Suppress("DEPRECATION")
            val iData = c.getColumnIndex(MediaStore.Audio.Media.DATA)
            val iRel = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH) else -1
            val iGenre = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex(MediaStore.Audio.Media.GENRE) else -1
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val artist = c.getString(iArtist)?.takeUnless { it.isBlank() || it == "<unknown>" } ?: "Unknown artist"
                val album = c.getString(iAlbum)?.takeUnless { it.isBlank() } ?: "Unknown album"
                val data = if (iData >= 0) c.getString(iData) else null
                val rel = if (iRel >= 0) c.getString(iRel) else null
                val folder = when {
                    !rel.isNullOrBlank() -> rel.trimEnd('/').substringAfterLast('/')
                    !data.isNullOrBlank() -> File(data).parentFile?.name ?: ""
                    else -> ""
                }.ifBlank { "Music" }
                val trackRaw = c.getInt(iTrack)
                out.add(
                    Track(
                        id = id,
                        uri = ContentUris.withAppendedId(base, id).toString(),
                        title = c.getString(iTitle)?.takeUnless { it.isBlank() } ?: "Unknown",
                        artist = artist,
                        album = album,
                        albumId = c.getLong(iAlbumId),
                        durationMs = c.getLong(iDur),
                        trackNo = if (trackRaw >= 1000) trackRaw % 1000 else trackRaw,
                        folder = folder,
                        genre = if (iGenre >= 0) c.getString(iGenre)?.trim() ?: "" else "",
                        dateAdded = c.getLong(iAdded),
                    )
                )
            }
        }
        return out
    }

    /** Android 9/10: genres live in their own table. */
    private fun queryGenresOld(ctx: Context): Map<Long, String> {
        val map = HashMap<Long, String>()
        @Suppress("DEPRECATION")
        val gUri = MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI
        ctx.contentResolver.query(gUri, arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME), null, null, null)
            ?.use { g ->
                while (g.moveToNext()) {
                    val gid = g.getLong(0)
                    val name = g.getString(1)?.trim().orEmpty()
                    if (name.isEmpty()) continue
                    @Suppress("DEPRECATION")
                    val members = MediaStore.Audio.Genres.Members.getContentUri("external", gid)
                    ctx.contentResolver.query(members, arrayOf(MediaStore.Audio.Genres.Members.AUDIO_ID), null, null, null)
                        ?.use { m -> while (m.moveToNext()) map[m.getLong(0)] = name }
                }
            }
        return map
    }

    private fun group(all: List<Track>): List<List<Group>> {
        val byAlbum = all.groupBy { it.albumId }.map { (aid, ts) ->
            val sorted = ts.sortedWith(compareBy({ it.trackNo }, { it.title.lowercase() }))
            val artist = sorted.map { it.artist }.distinct().let { if (it.size == 1) it[0] else "Various artists" }
            Group("album:$aid", sorted[0].album, artist, sorted, aid)
        }.sortedBy { it.name.lowercase() }

        val byArtist = all.groupBy { it.artist }.map { (a, ts) ->
            Group("artist:$a", a, songsLabel(ts.size), ts.sortedBy { it.title.lowercase() }, ts[0].albumId)
        }.sortedBy { it.name.lowercase() }

        val byGenre = all.filter { it.genre.isNotBlank() }.groupBy { it.genre }.map { (g, ts) ->
            Group("genre:$g", g, songsLabel(ts.size), ts.sortedBy { it.title.lowercase() }, ts[0].albumId)
        }.sortedBy { it.name.lowercase() }

        val byFolder = all.groupBy { it.folder }.map { (f, ts) ->
            Group("folder:$f", f, songsLabel(ts.size), ts.sortedBy { it.title.lowercase() }, ts[0].albumId)
        }.sortedBy { it.name.lowercase() }

        return listOf(byAlbum, byArtist, byGenre, byFolder)
    }

    fun songsLabel(n: Int) = if (n == 1) "1 song" else "$n songs"
}
