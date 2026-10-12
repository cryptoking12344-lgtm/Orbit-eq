package com.ck.orbiteq.player

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.ck.orbiteq.library.Track
import com.ck.orbiteq.library.UserLibrary
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** What's playing and what's next. Call from the main thread. */
object PlayQueue {
    const val REPEAT_OFF = 0
    const val REPEAT_ALL = 1
    const val REPEAT_ONE = 2
    private const val MAX_SAVED = 1000

    private var app: Context? = null
    private var prefs: SharedPreferences? = null
    private val tracks = ArrayList<Track>()
    private val order = ArrayList<Int>()     // play order (indexes into tracks)
    var pos = -1
        private set
    var shuffle = false
        private set
    var repeat = REPEAT_OFF
        private set
    private var restoredPositionMs = 0L
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    val current: Track? get() = if (pos in order.indices) tracks[order[pos]] else null
    val isEmpty: Boolean get() = current == null

    /** Songs from the current one onwards, in play order. */
    fun upcoming(): List<Track> = if (pos < 0) emptyList() else (pos until order.size).map { tracks[order[it]] }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() { for (l in listeners) l() }

    fun init(ctx: Context) {
        if (prefs != null) return
        app = ctx.applicationContext
        val p = ctx.applicationContext.getSharedPreferences("orbit_queue", Context.MODE_PRIVATE)
        prefs = p
        try {
            val o = JSONObject(p.getString("q", "{}") ?: "{}")
            val arr = o.optJSONArray("t") ?: JSONArray()
            for (i in 0 until arr.length()) tracks.add(Track.fromJson(arr.getJSONObject(i)))
            val ord = o.optJSONArray("o") ?: JSONArray()
            for (i in 0 until ord.length()) ord.getInt(i).takeIf { it in tracks.indices }?.let { order.add(it) }
            if (order.size != tracks.size) { order.clear(); order.addAll(tracks.indices) }
            pos = o.optInt("p", -1).takeIf { it in order.indices } ?: if (order.isEmpty()) -1 else 0
            shuffle = o.optBoolean("s", false)
            repeat = o.optInt("r", REPEAT_OFF)
            restoredPositionMs = o.optLong("ms", 0)
        } catch (_: Exception) {
            tracks.clear(); order.clear(); pos = -1
        }
        PlayerEngine.onComplete = { onTrackEnded() }
    }

    // ------------------------------------------------------------ building the queue

    fun playList(ctx: Context, list: List<Track>, start: Int, shuffled: Boolean = shuffle) {
        if (list.isEmpty()) return
        app = ctx.applicationContext
        tracks.clear(); tracks.addAll(list)
        shuffle = shuffled
        buildOrder(start.coerceIn(0, list.size - 1))
        startCurrent(0)
    }

    fun playSingle(ctx: Context, t: Track) = playList(ctx, listOf(t), 0, false)

    fun playNext(t: Track) {
        if (isEmpty) { app?.let { playSingle(it, t) }; return }
        tracks.add(t)
        order.add(pos + 1, tracks.size - 1)
        save(); changed()
    }

    fun addToQueue(t: Track) {
        if (isEmpty) { app?.let { playSingle(it, t) }; return }
        tracks.add(t)
        order.add(tracks.size - 1)
        save(); changed()
    }

    /** Jump to the n-th song of upcoming(). */
    fun jumpTo(offset: Int) {
        val p = pos + offset
        if (p in order.indices) { pos = p; startCurrent(0) }
    }

    private fun buildOrder(startIndex: Int) {
        order.clear()
        if (shuffle) {
            val rest = tracks.indices.filter { it != startIndex }.shuffled()
            order.add(startIndex); order.addAll(rest)
        } else {
            order.addAll(tracks.indices)
        }
        pos = if (shuffle) 0 else startIndex
    }

    fun toggleShuffle() {
        val cur = if (pos in order.indices) order[pos] else 0
        shuffle = !shuffle
        if (tracks.isNotEmpty()) {
            if (shuffle) {
                val rest = tracks.indices.filter { it != cur }.shuffled()
                order.clear(); order.add(cur); order.addAll(rest); pos = 0
            } else {
                order.clear(); order.addAll(tracks.indices); pos = cur
            }
        }
        save(); changed()
    }

    fun cycleRepeat() {
        repeat = (repeat + 1) % 3
        save(); changed()
    }

    // ------------------------------------------------------------ transport

    private fun startCurrent(seekMs: Long) {
        val t = current ?: return
        val ctx = app ?: return
        PlayerEngine.load(ctx, Uri.parse(t.uri), t.title)
        if (seekMs > 0) PlayerEngine.seekTo(seekMs)
        PlayerEngine.play()
        UserLibrary.addRecent(t.id)
        restoredPositionMs = 0
        save(); changed()
    }

    /** Play/pause from anywhere (buttons, widget, headphones). */
    fun togglePlay(ctx: Context) {
        app = ctx.applicationContext
        if (PlayerEngine.state.loaded) PlayerEngine.togglePlay() else resume(ctx)
    }

    fun play(ctx: Context) {
        app = ctx.applicationContext
        if (PlayerEngine.state.loaded) PlayerEngine.play() else resume(ctx)
    }

    /** Starts the saved song where it was left off. */
    fun resume(ctx: Context): Boolean {
        app = ctx.applicationContext
        if (isEmpty) return false
        startCurrent(restoredPositionMs)
        return true
    }

    fun next(): Boolean {
        if (order.isEmpty()) return false
        val n = pos + 1
        pos = when {
            n < order.size -> n
            repeat == REPEAT_ALL -> 0
            else -> return false
        }
        startCurrent(0)
        return true
    }

    fun previous() {
        if (order.isEmpty()) return
        if (PlayerEngine.state.positionMs > 3000 || pos == 0 && repeat != REPEAT_ALL) {
            if (PlayerEngine.state.loaded) PlayerEngine.seekTo(0) else startCurrent(0)
            return
        }
        pos = if (pos - 1 >= 0) pos - 1 else order.size - 1
        startCurrent(0)
    }

    private fun onTrackEnded(): Boolean {
        if (repeat == REPEAT_ONE) { startCurrent(0); return true }
        return next()
    }

    /** Remember the playback position (called on pause / stop). */
    fun savePosition(ms: Long) {
        restoredPositionMs = ms
        save()
    }

    private fun save() {
        val p = prefs ?: return
        val o = JSONObject()
        val arr = JSONArray()
        for (i in 0 until minOf(tracks.size, MAX_SAVED)) arr.put(tracks[i].toJson())
        val ord = JSONArray()
        for (i in order) if (i < MAX_SAVED) ord.put(i)
        o.put("t", arr).put("o", ord).put("p", pos.coerceAtMost(ord.length() - 1))
            .put("s", shuffle).put("r", repeat).put("ms", restoredPositionMs)
        p.edit().putString("q", o.toString()).apply()
    }
}
