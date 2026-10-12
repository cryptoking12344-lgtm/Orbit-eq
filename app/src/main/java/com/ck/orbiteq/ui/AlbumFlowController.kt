package com.ck.orbiteq.ui

import android.content.Context
import android.graphics.RectF
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ck.orbiteq.R
import com.ck.orbiteq.library.Group
import com.ck.orbiteq.library.MusicLibrary
import com.ck.orbiteq.player.PlayQueue
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlin.math.max
import kotlin.math.min

/** Album carousel + the flip-over song list. Used in the Library tab and sideways full-screen. */
class AlbumFlowController(private val ctx: Context, container: FrameLayout) {

    val root: View = LayoutInflater.from(ctx).inflate(R.layout.view_album_flow, container, false)
    private val flow: AlbumFlowView = root.findViewById(R.id.flowView)
    private val title: TextView = root.findViewById(R.id.flowTitle)
    private val sub: TextView = root.findViewById(R.id.flowSub)
    private val effects: ChipGroup = root.findViewById(R.id.flowEffects)
    private val panel: MaterialCardView = root.findViewById(R.id.flowPanel)
    private val panelTitle: TextView = root.findViewById(R.id.panelTitle)
    private val panelSub: TextView = root.findViewById(R.id.panelSub)
    private val panelList: RecyclerView = root.findViewById(R.id.panelList)
    private val adapter = TrackListAdapter { i -> playFrom(i, false) }
    private var albums: List<Group> = emptyList()
    private var openAlbum: Group? = null
    private val rect = RectF()

    init {
        container.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        panelList.layoutManager = LinearLayoutManager(ctx)
        panelList.adapter = adapter
        flow.effect = Appearance.flowEffect(ctx)
        Appearance.FLOW_NAMES.forEachIndexed { i, name ->
            val chip = LayoutInflater.from(ctx).inflate(R.layout.item_chip, effects, false) as Chip
            chip.text = name
            chip.isCheckable = true
            chip.id = View.generateViewId()
            chip.isChecked = i == flow.effect
            chip.setOnClickListener {
                flow.effect = i
                Appearance.setFlowEffect(ctx, i)
            }
            effects.addView(chip)
        }
        flow.onSelected = { i -> showTitle(i) }
        flow.onCenterTap = { i -> openAlbum(i) }
        root.findViewById<MaterialButton>(R.id.panelClose).setOnClickListener { closeAlbum() }
        root.findViewById<MaterialButton>(R.id.panelPlay).setOnClickListener { playFrom(0, false) }
        root.findViewById<MaterialButton>(R.id.panelShuffle).setOnClickListener { playFrom(0, true) }
    }

    fun refresh() {
        val keep = flow.selected
        albums = MusicLibrary.albums
        flow.setAlbums(albums, keep)
        if (albums.isEmpty()) {
            title.text = ctx.getString(R.string.lib_no_albums)
            sub.text = ""
        }
    }

    /** Point at a given album (e.g. the one that's playing). */
    fun focusAlbum(albumId: Long) {
        val i = albums.indexOfFirst { it.albumId == albumId }
        if (i >= 0) flow.scrollTo(i, animate = false)
    }

    fun onBack(): Boolean {
        if (panel.visibility == View.VISIBLE) { closeAlbum(); return true }
        return false
    }

    fun highlight(uri: String?) {
        openAlbum?.let { adapter.set(it.tracks, uri) }
    }

    private fun showTitle(i: Int) {
        val g = albums.getOrNull(i) ?: return
        title.text = g.name
        sub.text = "${g.sub} · ${MusicLibrary.songsLabel(g.tracks.size)}"
    }

    private fun openAlbum(i: Int) {
        val g = albums.getOrNull(i) ?: return
        openAlbum = g
        panelTitle.text = g.name
        val total = g.tracks.sumOf { it.durationMs }
        panelSub.text = "${g.sub} · ${MusicLibrary.songsLabel(g.tracks.size)} · ${fmtTime(total)}"
        adapter.set(g.tracks, PlayQueue.current?.uri)
        flow.setOpen(true)

        // size + place the song list over the flipped album
        val wide = root.width > root.height * 1.2f
        val lp = panel.layoutParams as FrameLayout.LayoutParams
        lp.width = ctx.dp(if (wide) 360f else 310f).toInt()
        lp.height = if (wide) (root.height * 0.86f).toInt() else ctx.dp(380f).toInt()
        panel.layoutParams = lp
        flow.centerCardRect(rect)
        val top = rect.centerY() - lp.height / 2f
        panel.translationY = max(ctx.dp(8f), min(top, root.height - lp.height - ctx.dp(8f)))

        panel.visibility = View.VISIBLE
        panel.cameraDistance = 9000 * ctx.resources.displayMetrics.density
        panel.alpha = 0f
        panel.rotationY = -90f
        panel.animate().setStartDelay(260).setDuration(320).alpha(1f).rotationY(0f).start()
    }

    private fun closeAlbum() {
        panel.animate().setStartDelay(0).setDuration(220).alpha(0f).rotationY(90f).withEndAction {
            panel.visibility = View.GONE
            panel.rotationY = 0f
        }.start()
        flow.postDelayed({ flow.setOpen(false) }, 120)
        openAlbum = null
    }

    private fun playFrom(i: Int, shuffle: Boolean) {
        val g = openAlbum ?: return
        PlayQueue.playList(ctx, g.tracks, i, shuffle)
        adapter.set(g.tracks, PlayQueue.current?.uri)
    }
}
