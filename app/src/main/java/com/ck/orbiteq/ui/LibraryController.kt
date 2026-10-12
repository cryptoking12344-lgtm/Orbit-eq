package com.ck.orbiteq.ui

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ck.orbiteq.R
import com.ck.orbiteq.library.Group
import com.ck.orbiteq.library.MusicLibrary
import com.ck.orbiteq.library.Track
import com.ck.orbiteq.library.UserLibrary
import com.ck.orbiteq.player.PlayQueue
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout

/** The Library tab. */
class LibraryController(
    private val act: Activity,
    root: View,
    private val askPermission: () -> Unit,
) {
    companion object {
        const val SONGS = 0
        const val ALBUMS = 1
        const val ARTISTS = 2
        const val GENRES = 3
        const val FOLDERS = 4
        const val PLAYLISTS = 5
        const val LIKED = 6
        const val RECENT = 7
        private val TAB_NAMES = intArrayOf(
            R.string.lib_songs, R.string.lib_albums, R.string.lib_artists, R.string.lib_genres,
            R.string.lib_folders, R.string.lib_playlists, R.string.lib_liked, R.string.lib_recent,
        )
    }

    private val tabs: ChipGroup = root.findViewById(R.id.chipsLibTabs)
    private val searchBox: TextInputLayout = root.findViewById(R.id.libSearchBox)
    private val searchEdit: EditText = root.findViewById(R.id.editLibSearch)
    private val detailHeader: View = root.findViewById(R.id.libDetailHeader)
    private val detailTitle: TextView = root.findViewById(R.id.txtLibDetailTitle)
    private val detailSub: TextView = root.findViewById(R.id.txtLibDetailSub)
    private val list: RecyclerView = root.findViewById(R.id.libList)
    private val albumsBox: FrameLayout = root.findViewById(R.id.libAlbums)
    private val message: View = root.findViewById(R.id.libMessage)
    private val messageText: TextView = root.findViewById(R.id.txtLibMessage)
    private val allowBtn: MaterialButton = root.findViewById(R.id.btnLibAllow)

    private var tab = SONGS
    private var detail: Group? = null
    private var detailKind = -1
    private var query = ""
    private val tabChips = ArrayList<Chip>()
    private var flow: AlbumFlowController? = null

    private val adapter = LibraryAdapter(
        onSong = { songs, i -> if (i >= 0) PlayQueue.playList(act, songs, i, false) },
        onSongMore = { t, v -> songMenu(t, v) },
        onGroup = { g -> openGroup(g) },
        onGroupLong = { g -> if (tab == PLAYLISTS && g.key.startsWith("pl:")) playlistMenu(g.name) },
    )

    private val libListener: () -> Unit = { refresh() }

    init {
        list.layoutManager = LinearLayoutManager(act)
        list.adapter = adapter

        val inf = LayoutInflater.from(act)
        val search = inf.inflate(R.layout.item_chip, tabs, false) as Chip
        search.id = View.generateViewId()
        search.text = act.getString(R.string.search)
        search.isCheckable = false
        search.chipIcon = act.getDrawable(R.drawable.ic_search)
        search.isChipIconVisible = true
        search.setOnClickListener { toggleSearch() }
        tabs.addView(search)
        TAB_NAMES.forEachIndexed { i, res ->
            val chip = inf.inflate(R.layout.item_chip, tabs, false) as Chip
            chip.id = View.generateViewId()
            chip.text = act.getString(res)
            chip.isCheckable = true
            chip.isChecked = i == tab
            chip.setOnClickListener { selectTab(i) }
            tabChips.add(chip)
            tabs.addView(chip)
        }

        root.findViewById<MaterialButton>(R.id.btnLibBack).setOnClickListener { closeDetail() }
        root.findViewById<MaterialButton>(R.id.btnLibPlayAll).setOnClickListener {
            detail?.tracks?.let { if (it.isNotEmpty()) PlayQueue.playList(act, it, 0, false) }
        }
        root.findViewById<MaterialButton>(R.id.btnLibShuffle).setOnClickListener {
            detail?.tracks?.let { if (it.isNotEmpty()) PlayQueue.playList(act, it, 0, true) }
        }
        allowBtn.setOnClickListener { askPermission() }
        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                refresh()
            }
        })
    }

    fun onShow() {
        MusicLibrary.addListener(libListener)
        UserLibrary.addListener(libListener)
        if (MusicLibrary.hasPermission(act)) MusicLibrary.load(act)
        refresh()
    }

    fun onHide() {
        MusicLibrary.removeListener(libListener)
        UserLibrary.removeListener(libListener)
    }

    fun onPermissionResult() {
        if (MusicLibrary.hasPermission(act)) MusicLibrary.load(act, force = true)
        refresh()
    }

    fun setCurrent(uri: String?) {
        adapter.setCurrent(uri)
        flow?.highlight(uri)
    }

    /** Back button: closes the song list, the search or the open group first. */
    fun onBack(): Boolean {
        if (flow?.onBack() == true) return true
        if (detail != null) { closeDetail(); return true }
        if (searchBox.visibility == View.VISIBLE) { toggleSearch(); return true }
        return false
    }

    private fun selectTab(i: Int) {
        tab = i
        detail = null
        tabChips.forEachIndexed { k, c -> c.isChecked = k == i }
        refresh()
    }

    private fun toggleSearch() {
        if (searchBox.visibility == View.VISIBLE) {
            searchEdit.setText("")
            searchBox.visibility = View.GONE
        } else {
            searchBox.visibility = View.VISIBLE
            if (tab == ALBUMS) selectTab(SONGS)
            searchEdit.requestFocus()
        }
    }

    private fun openGroup(g: Group) {
        if (g.key == "new") { newPlaylist(null); return }
        detail = g
        detailKind = tab
        refresh()
        list.scrollToPosition(0)
    }

    private fun closeDetail() {
        detail = null
        refresh()
    }

    private fun matches(t: Track) = query.isEmpty() ||
        t.title.contains(query, true) || t.artist.contains(query, true) || t.album.contains(query, true)

    fun refresh() {
        if (!MusicLibrary.hasPermission(act)) {
            showMessage(act.getString(R.string.lib_need_permission), true)
            return
        }
        if (!MusicLibrary.loaded) {
            showMessage(act.getString(R.string.lib_loading), false)
            MusicLibrary.load(act)
            return
        }
        message.visibility = View.GONE

        val d = detail
        if (d != null) {
            // refresh playlist contents (they can change)
            val tracks = if (d.key.startsWith("pl:")) UserLibrary.playlistTracks(d.name) else d.tracks
            detail = d.copy(tracks = tracks)
            detailHeader.visibility = View.VISIBLE
            detailTitle.text = d.name
            detailSub.text = MusicLibrary.songsLabel(tracks.size)
            showList()
            adapter.showSongs(tracks.filter { matches(it) })
            emptyHint(tracks.isEmpty(), R.string.lib_empty_list)
            return
        }
        detailHeader.visibility = View.GONE

        when (tab) {
            ALBUMS -> {
                list.visibility = View.GONE
                albumsBox.visibility = View.VISIBLE
                val f = flow ?: AlbumFlowController(act, albumsBox).also { flow = it }
                f.refresh()
                PlayQueue.current?.let { f.focusAlbum(it.albumId) }
                if (MusicLibrary.albums.isEmpty()) showMessage(act.getString(R.string.lib_no_music), false)
                return
            }
            SONGS -> {
                showList()
                val l = MusicLibrary.songs.filter { matches(it) }
                adapter.showSongs(l)
                emptyHint(l.isEmpty(), if (query.isEmpty()) R.string.lib_no_music else R.string.lib_no_results)
            }
            ARTISTS -> groups(MusicLibrary.artists, R.drawable.ic_person)
            GENRES -> groups(MusicLibrary.genres, R.drawable.ic_tag)
            FOLDERS -> groups(MusicLibrary.folders, R.drawable.ic_folder)
            PLAYLISTS -> {
                val pls = UserLibrary.playlistNames().map { n ->
                    val ts = UserLibrary.playlistTracks(n)
                    Group("pl:$n", n, MusicLibrary.songsLabel(ts.size), ts, -1)
                }
                val all = listOf(Group("new", act.getString(R.string.new_playlist), act.getString(R.string.new_playlist_sub), emptyList(), -1)) + pls
                showList()
                adapter.showGroups(all, R.drawable.ic_playlist)
            }
            LIKED -> {
                showList()
                val l = UserLibrary.likedTracks().filter { matches(it) }
                adapter.showSongs(l)
                emptyHint(l.isEmpty(), R.string.lib_no_liked)
            }
            RECENT -> {
                showList()
                val l = UserLibrary.recentTracks().filter { matches(it) }
                adapter.showSongs(l)
                emptyHint(l.isEmpty(), R.string.lib_no_recent)
            }
        }
    }

    private fun groups(gs: List<Group>, icon: Int) {
        showList()
        val l = if (query.isEmpty()) gs else gs.filter { it.name.contains(query, true) }
        adapter.showGroups(l, icon)
        emptyHint(l.isEmpty(), if (query.isEmpty()) R.string.lib_no_music else R.string.lib_no_results)
    }

    private fun showList() {
        list.visibility = View.VISIBLE
        albumsBox.visibility = View.GONE
    }

    private fun emptyHint(empty: Boolean, res: Int) {
        if (empty) showMessage(act.getString(res), false) else message.visibility = View.GONE
    }

    private fun showMessage(text: String, permission: Boolean) {
        message.visibility = View.VISIBLE
        messageText.text = text
        allowBtn.visibility = if (permission) View.VISIBLE else View.GONE
        if (permission) {
            list.visibility = View.GONE
            albumsBox.visibility = View.GONE
            detailHeader.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------------ menus

    private fun songMenu(t: Track, anchor: View) {
        val m = PopupMenu(act, anchor)
        m.menu.add(0, 1, 0, R.string.play_next)
        m.menu.add(0, 2, 1, R.string.add_to_queue)
        m.menu.add(0, 3, 2, if (UserLibrary.isLiked(t.id)) R.string.unlike else R.string.like)
        m.menu.add(0, 4, 3, R.string.add_to_playlist)
        val d = detail
        if (d != null && d.key.startsWith("pl:")) m.menu.add(0, 5, 4, R.string.remove_from_playlist)
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { PlayQueue.playNext(t); toast(R.string.added_next) }
                2 -> { PlayQueue.addToQueue(t); toast(R.string.added_queue) }
                3 -> UserLibrary.toggleLike(t.id)
                4 -> pickPlaylist(t)
                5 -> d?.let { UserLibrary.removeFromPlaylist(it.name, t.id) }
            }
            true
        }
        m.show()
    }

    private fun pickPlaylist(t: Track) {
        val names = UserLibrary.playlistNames()
        val items = (names + act.getString(R.string.new_playlist_dots)).toTypedArray()
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.add_to_playlist)
            .setItems(items) { _, which ->
                if (which < names.size) {
                    UserLibrary.addToPlaylist(names[which], listOf(t.id))
                    toast(act.getString(R.string.added_to, names[which]))
                } else {
                    newPlaylist(t)
                }
            }
            .show()
    }

    private fun nameDialog(title: Int, initial: String, done: (String) -> Unit) {
        val input = EditText(act).apply {
            setText(initial)
            setSelection(initial.length)
            setSingleLine()
        }
        val pad = act.dp(20f).toInt()
        val box = FrameLayout(act).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(act)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) done(n)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun newPlaylist(first: Track?) {
        nameDialog(R.string.new_playlist, "") { n ->
            if (!UserLibrary.createPlaylist(n) && !UserLibrary.playlistNames().contains(n)) return@nameDialog
            first?.let { UserLibrary.addToPlaylist(n, listOf(it.id)) }
            toast(act.getString(R.string.playlist_created, n))
        }
    }

    private fun playlistMenu(name: String) {
        MaterialAlertDialogBuilder(act)
            .setTitle(name)
            .setItems(arrayOf(act.getString(R.string.rename), act.getString(R.string.delete))) { _, which ->
                if (which == 0) {
                    nameDialog(R.string.rename, name) { n -> UserLibrary.renamePlaylist(name, n) }
                } else {
                    MaterialAlertDialogBuilder(act)
                        .setTitle(act.getString(R.string.delete_q, name))
                        .setPositiveButton(R.string.delete) { _, _ -> UserLibrary.deletePlaylist(name) }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            .show()
    }

    private fun toast(res: Int) = Toast.makeText(act, res, Toast.LENGTH_SHORT).show()
    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_SHORT).show()
}
