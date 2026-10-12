package com.ck.orbiteq.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ck.orbiteq.R
import com.ck.orbiteq.library.ArtLoader
import com.ck.orbiteq.library.Group
import com.ck.orbiteq.library.Track
import com.google.android.material.button.MaterialButton
import java.util.Locale

fun fmtTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return if (total >= 3600) String.format(Locale.US, "%d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60)
    else String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}

/** Loads album art into an ImageView, skipping it if the row was reused meanwhile. */
fun bindArt(iv: ImageView, albumId: Long, uri: String?, sizePx: Int = 160) {
    val key = "$albumId|$uri"
    iv.tag = key
    val cached = ArtLoader.cached(albumId, sizePx)
    if (cached != null) {
        iv.imageTintList = null
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.setImageBitmap(cached); iv.setBackgroundColor(0); return
    }
    iv.setImageResource(R.drawable.ic_note)
    iv.scaleType = ImageView.ScaleType.CENTER
    iv.setBackgroundColor(iv.context.attrColor(R.attr.oSurface2))
    iv.imageTintList = android.content.res.ColorStateList.valueOf(iv.context.attrColor(R.attr.oTextDim))
    ArtLoader.load(iv.context, albumId, sizePx, uri) { b ->
        if (iv.tag == key && b != null) {
            iv.imageTintList = null
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setImageBitmap(b)
            iv.setBackgroundColor(0)
        }
    }
}

/** One list for songs and groups (artists, genres, folders, playlists). */
class LibraryAdapter(
    private val onSong: (List<Track>, Int) -> Unit,
    private val onSongMore: (Track, View) -> Unit,
    private val onGroup: (Group) -> Unit,
    private val onGroupLong: ((Group) -> Unit)? = null,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var songs: List<Track> = emptyList()
    private var groups: List<Group> = emptyList()
    private var groupIcon = R.drawable.ic_person
    private var currentUri: String? = null

    fun showSongs(list: List<Track>) {
        songs = list; groups = emptyList()
        notifyDataSetChanged()
    }

    fun showGroups(list: List<Group>, icon: Int) {
        groups = list; songs = emptyList(); groupIcon = icon
        notifyDataSetChanged()
    }

    fun setCurrent(uri: String?) {
        if (uri != currentUri) { currentUri = uri; if (songs.isNotEmpty()) notifyDataSetChanged() }
    }

    override fun getItemCount() = if (groups.isNotEmpty()) groups.size else songs.size
    override fun getItemViewType(position: Int) = if (groups.isNotEmpty()) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 1) GroupVH(inf.inflate(R.layout.item_group, parent, false))
        else SongVH(inf.inflate(R.layout.item_song, parent, false))
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        if (h is SongVH) {
            val t = songs[position]
            h.title.text = t.title
            h.sub.text = "${t.artist} · ${t.album}"
            val ctx = h.itemView.context
            h.title.setTextColor(ctx.attrColor(if (t.uri == currentUri) R.attr.oAccent else R.attr.oText))
            bindArt(h.art, t.albumId, t.uri)
            h.itemView.setOnClickListener { onSong(songs, h.bindingAdapterPosition) }
            h.more.setOnClickListener { onSongMore(t, it) }
        } else if (h is GroupVH) {
            val g = groups[position]
            h.name.text = g.name
            h.sub.text = g.sub
            if (g.albumId > 0) {
                h.icon.visibility = View.GONE
                bindArt(h.art, g.albumId, g.tracks.firstOrNull()?.uri)
            } else {
                h.art.setImageDrawable(null)
                h.art.setBackgroundColor(h.itemView.context.attrColor(R.attr.oAccentDim))
                h.icon.visibility = View.VISIBLE
                h.icon.setImageResource(groupIcon)
            }
            h.itemView.setOnClickListener { onGroup(g) }
            h.itemView.setOnLongClickListener {
                val cb = onGroupLong ?: return@setOnLongClickListener false
                cb(g); true
            }
        }
    }

    class SongVH(v: View) : RecyclerView.ViewHolder(v) {
        val art: ImageView = v.findViewById(R.id.songArt)
        val title: TextView = v.findViewById(R.id.songTitle)
        val sub: TextView = v.findViewById(R.id.songSub)
        val more: MaterialButton = v.findViewById(R.id.songMore)
    }

    class GroupVH(v: View) : RecyclerView.ViewHolder(v) {
        val art: ImageView = v.findViewById(R.id.groupArt)
        val icon: ImageView = v.findViewById(R.id.groupIcon)
        val name: TextView = v.findViewById(R.id.groupName)
        val sub: TextView = v.findViewById(R.id.groupSub)
    }
}

/** Compact numbered track list (album back side, queue). */
class TrackListAdapter(
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<TrackListAdapter.VH>() {
    private var tracks: List<Track> = emptyList()
    private var highlightUri: String? = null
    private var numbered = true

    fun set(list: List<Track>, current: String?, numbers: Boolean = true) {
        tracks = list; highlightUri = current; numbered = numbers
        notifyDataSetChanged()
    }

    override fun getItemCount() = tracks.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_track_small, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val t = tracks[position]
        val ctx = h.itemView.context
        h.no.text = if (numbered) (if (t.trackNo > 0) t.trackNo else position + 1).toString() else ""
        h.title.text = if (numbered) t.title else "${t.title} · ${t.artist}"
        h.len.text = fmtTime(t.durationMs)
        val on = t.uri == highlightUri
        h.title.setTextColor(ctx.attrColor(if (on) R.attr.oAccent else R.attr.oText))
        h.itemView.setOnClickListener { onClick(h.bindingAdapterPosition) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val no: TextView = v.findViewById(R.id.tsNo)
        val title: TextView = v.findViewById(R.id.tsTitle)
        val len: TextView = v.findViewById(R.id.tsLen)
    }
}
