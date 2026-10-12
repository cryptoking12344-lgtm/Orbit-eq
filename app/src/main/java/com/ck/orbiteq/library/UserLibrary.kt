package com.ck.orbiteq.library

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** Orbit's own lists: liked songs, recently played and playlists (stored in the app). */
object UserLibrary {
    private const val MAX_RECENT = 50
    private var prefs: SharedPreferences? = null
    private val liked = LinkedHashSet<Long>()
    private val recent = ArrayList<Long>()
    private val playlists = LinkedHashMap<String, MutableList<Long>>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("orbit_library", Context.MODE_PRIVATE)
        prefs = p
        try {
            JSONArray(p.getString("liked", "[]")).let { a -> for (i in 0 until a.length()) liked.add(a.getLong(i)) }
            JSONArray(p.getString("recent", "[]")).let { a -> for (i in 0 until a.length()) recent.add(a.getLong(i)) }
            val o = JSONObject(p.getString("playlists", "{}") ?: "{}")
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val a = o.optJSONArray(k) ?: continue
                playlists[k] = MutableList(a.length()) { a.getLong(it) }
            }
        } catch (_: Exception) {
        }
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() { for (l in listeners) l() }

    // ---- liked
    fun isLiked(id: Long) = id > 0 && liked.contains(id)

    fun toggleLike(id: Long): Boolean {
        if (id <= 0) return false
        val now = if (liked.contains(id)) { liked.remove(id); false } else { liked.add(id); true }
        save()
        changed()
        return now
    }

    fun likedTracks(): List<Track> = liked.toList().asReversed().mapNotNull { MusicLibrary.track(it) }

    // ---- recent
    fun addRecent(id: Long) {
        if (id <= 0) return
        recent.remove(id)
        recent.add(0, id)
        while (recent.size > MAX_RECENT) recent.removeAt(recent.size - 1)
        save()
        changed()
    }

    fun recentTracks(): List<Track> = recent.mapNotNull { MusicLibrary.track(it) }

    // ---- playlists
    fun playlistNames(): List<String> = playlists.keys.toList()

    fun playlistTracks(name: String): List<Track> = playlists[name]?.mapNotNull { MusicLibrary.track(it) } ?: emptyList()

    fun createPlaylist(name: String): Boolean {
        if (name.isBlank() || playlists.containsKey(name)) return false
        playlists[name] = ArrayList()
        save(); changed()
        return true
    }

    fun addToPlaylist(name: String, ids: List<Long>) {
        val list = playlists.getOrPut(name) { ArrayList() }
        for (id in ids) if (id > 0 && !list.contains(id)) list.add(id)
        save(); changed()
    }

    fun removeFromPlaylist(name: String, id: Long) {
        playlists[name]?.remove(id)
        save(); changed()
    }

    fun renamePlaylist(old: String, new: String): Boolean {
        if (new.isBlank() || playlists.containsKey(new)) return false
        val list = playlists.remove(old) ?: return false
        playlists[new] = list
        save(); changed()
        return true
    }

    fun deletePlaylist(name: String) {
        playlists.remove(name)
        save(); changed()
    }

    private fun save() {
        val o = JSONObject()
        for ((k, v) in playlists) o.put(k, JSONArray(v))
        prefs?.edit()
            ?.putString("liked", JSONArray(liked.toList()).toString())
            ?.putString("recent", JSONArray(recent).toString())
            ?.putString("playlists", o.toString())
            ?.apply()
    }
}
