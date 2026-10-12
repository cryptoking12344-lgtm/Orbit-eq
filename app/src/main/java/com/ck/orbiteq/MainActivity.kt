package com.ck.orbiteq

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.media.audiofx.AudioEffect
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ck.orbiteq.dsp.Space360Processor
import com.ck.orbiteq.dsp.Spectrum
import com.ck.orbiteq.dsp.TheaterProcessor
import com.ck.orbiteq.global.DeviceUtil
import com.ck.orbiteq.global.GlobalEffectsService
import com.ck.orbiteq.library.ArtLoader
import com.ck.orbiteq.library.MusicLibrary
import com.ck.orbiteq.library.Track
import com.ck.orbiteq.library.UserLibrary
import com.ck.orbiteq.model.AudioState
import com.ck.orbiteq.model.EqSettings
import com.ck.orbiteq.model.Presets
import com.ck.orbiteq.model.Profile
import com.ck.orbiteq.player.PlayQueue
import com.ck.orbiteq.player.PlayerEngine
import com.ck.orbiteq.player.SpeakerTest
import com.ck.orbiteq.ui.AlbumFlowController
import com.ck.orbiteq.ui.Appearance
import com.ck.orbiteq.ui.EqCurveView
import com.ck.orbiteq.ui.LibraryController
import com.ck.orbiteq.ui.Sheets
import com.ck.orbiteq.ui.SpeakerMapView
import com.ck.orbiteq.ui.VisualizerView
import com.ck.orbiteq.ui.attrColor
import com.ck.orbiteq.ui.dp
import com.ck.orbiteq.ui.fmtTime
import com.ck.orbiteq.youtube.YouTubeLoader
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), PlayerEngine.Listener, SpeakerTest.Listener,
    YouTubeLoader.Listener, Sheets.VizStyleHost {

    companion object {
        private const val PAGE_LIBRARY = 0
        private const val PAGE_PLAYER = 1
        private const val PAGE_EQ = 2
        private const val PAGE_PROFILES = 3
    }

    // header
    private lateinit var switchGlobal: MaterialSwitch
    private lateinit var txtHeaderSub: TextView
    private lateinit var pages: List<View>
    private lateinit var bottomNav: BottomNavigationView

    // equalizer page
    private lateinit var vizGlobal: VisualizerView
    private lateinit var txtVizHint: TextView
    private lateinit var eqCurve: EqCurveView
    private lateinit var chipsPresets: ChipGroup
    private lateinit var chipsReverb: ChipGroup
    private lateinit var sliderBass: Slider
    private lateinit var sliderSpatial: Slider
    private lateinit var sliderLoud: Slider
    private lateinit var sliderPreamp: Slider
    private lateinit var lblBass: TextView
    private lateinit var lblSpatial: TextView
    private lateinit var lblLoud: TextView
    private lateinit var lblPreamp: TextView
    private lateinit var txtStatus: TextView
    private lateinit var sliderWarmth: Slider
    private lateinit var sliderCrossfeed: Slider
    private lateinit var lblWarmth: TextView
    private lateinit var lblCrossfeed: TextView
    private lateinit var toggleGain: MaterialButtonToggleGroup
    private val customPresetChips = ArrayList<Chip>()

    // player page (now playing)
    private lateinit var vizPlayer: VisualizerView
    private lateinit var npArt: ImageView
    private lateinit var npTitle: TextView
    private lateinit var npArtist: TextView
    private lateinit var npLike: MaterialButton
    private lateinit var npShuffle: MaterialButton
    private lateinit var npRepeat: MaterialButton
    private lateinit var txtPos: TextView
    private lateinit var txtDur: TextView
    private lateinit var sliderSeek: Slider
    private lateinit var btnPlay: MaterialButton

    // youtube
    private lateinit var editYt: TextInputEditText
    private lateinit var btnYtPlay: MaterialButton
    private lateinit var progressYt: LinearProgressIndicator
    private lateinit var txtYtStatus: TextView

    // sound mode
    private lateinit var toggleMode: MaterialButtonToggleGroup
    private lateinit var txtModeSub: TextView
    private lateinit var panelSpatial: View
    private lateinit var panelTheater: View
    private lateinit var panel360: View
    private lateinit var sliderWrap: Slider
    private lateinit var sliderHeight: Slider
    private lateinit var sliderRotate: Slider
    private lateinit var lblWrap: TextView
    private lateinit var lblHeight: TextView
    private lateinit var lblRotate: TextView
    private lateinit var chipsHead: ChipGroup
    private val headChips = ArrayList<Chip>()
    private lateinit var speakerMap: SpeakerMapView
    private lateinit var txtTestNow: TextView
    private lateinit var btnSpeakerTest: MaterialButton
    private lateinit var chipsRoom: ChipGroup
    private lateinit var sliderImmersion: Slider
    private lateinit var sliderVocal: Slider
    private lateinit var sliderSub: Slider
    private lateinit var lblImmersion: TextView
    private lateinit var lblVocal: TextView
    private lateinit var lblSub: TextView
    private lateinit var switchHpFix: MaterialSwitch
    private val roomChips = ArrayList<Chip>()

    // headphones page
    private lateinit var txtDevice: TextView
    private lateinit var txtLinks: TextView
    private lateinit var txtNoProfiles: TextView
    private lateinit var chipsHeadphones: ChipGroup
    private lateinit var chipsCustom: ChipGroup

    // library, mini player, sideways album flow
    private lateinit var library: LibraryController
    private lateinit var miniPlayer: MaterialCardView
    private lateinit var miniArt: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniPlay: MaterialButton
    private lateinit var landOverlay: FrameLayout
    private var landFlow: AlbumFlowController? = null

    private val handler = Handler(Looper.getMainLooper())
    private var globalViz: Visualizer? = null
    private var seekTouching = false
    private var suppress = false
    private var currentPage = PAGE_LIBRARY
    private var shownArtFor: String? = null
    private val reverbChips = ArrayList<Chip>()
    private val stateListener: () -> Unit = { refreshSound() }
    private val queueListener: () -> Unit = { refreshNowPlaying() }

    private val statusPoll = object : Runnable {
        override fun run() {
            refreshGlobal()
            handler.postDelayed(this, 1500)
        }
    }

    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) loadSong(uri)
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> startGlobal() }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startGlobalViz()
            } else {
                txtVizHint.text = getString(R.string.viz_hint)
            }
        }

    private val musicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            library.onPermissionResult()
            landFlow?.refresh()
        }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.apply(this)
        super.onCreate(savedInstanceState)
        AudioState.init(this)
        UserLibrary.init(this)
        PlayQueue.init(this)
        setContentView(R.layout.activity_main)
        bindViews()
        setupNav()
        setupEq()
        setupAmp()
        setupPlayer()
        setupTheater()
        setupProfiles()
        setupYouTube()
        setupMiniPlayer()
        library = LibraryController(this, findViewById(R.id.pageLibrary)) { askMusicPermission() }
        findViewById<MaterialButton>(R.id.btnAppearance).setOnClickListener { Sheets.appearance(this) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })

        val start = savedInstanceState?.getInt("page", PAGE_LIBRARY) ?: PAGE_LIBRARY
        bottomNav.selectedItemId = navIdFor(start)
        showPage(start)
        handleEffectIntent(intent)
        handleShareIntent(intent)
        applyOrientation(resources.configuration)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", currentPage)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleEffectIntent(intent)
        handleShareIntent(intent)
    }

    override fun onDestroy() {
        if (YouTubeLoader.listener === this) YouTubeLoader.listener = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        AudioState.addListener(stateListener)
        PlayerEngine.addListener(this)
        PlayQueue.addListener(queueListener)
        UserLibrary.addListener(queueListener)
        SpeakerTest.listener = this
        refreshSound()
        onPlaybackState(PlayerEngine.state)
        refreshNowPlaying()
        refreshProfiles()
        if (currentPage == PAGE_LIBRARY) library.onShow()
        handler.post(statusPoll)
        if (currentPage == PAGE_EQ) maybeStartGlobalViz()
    }

    override fun onPause() {
        super.onPause()
        AudioState.removeListener(stateListener)
        PlayerEngine.removeListener(this)
        PlayQueue.removeListener(queueListener)
        UserLibrary.removeListener(queueListener)
        library.onHide()
        SpeakerTest.listener = null
        SpeakerTest.stop()
        onTestSpeaker(-1)
        handler.removeCallbacks(statusPoll)
        stopGlobalViz()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig)
    }

    private fun goBack() {
        if (landOverlay.visibility == View.VISIBLE && landFlow?.onBack() == true) return
        if (currentPage == PAGE_LIBRARY && library.onBack()) return
        if (currentPage != PAGE_LIBRARY) {
            bottomNav.selectedItemId = R.id.nav_library
            return
        }
        finish()
    }

    private fun bindViews() {
        switchGlobal = findViewById(R.id.switchGlobal)
        txtHeaderSub = findViewById(R.id.txtHeaderSub)
        bottomNav = findViewById(R.id.bottomNav)
        pages = listOf<View>(
            findViewById(R.id.pageLibrary),
            findViewById(R.id.pagePlayer),
            findViewById(R.id.pageEq),
            findViewById(R.id.pageProfiles),
        )

        vizGlobal = findViewById(R.id.vizGlobal)
        txtVizHint = findViewById(R.id.txtVizHint)
        eqCurve = findViewById(R.id.eqCurve)
        chipsPresets = findViewById(R.id.chipsPresets)
        chipsReverb = findViewById(R.id.chipsReverb)
        sliderBass = findViewById(R.id.sliderBass)
        sliderSpatial = findViewById(R.id.sliderSpatial)
        sliderLoud = findViewById(R.id.sliderLoud)
        sliderPreamp = findViewById(R.id.sliderPreamp)
        lblBass = findViewById(R.id.lblBass)
        lblSpatial = findViewById(R.id.lblSpatial)
        lblLoud = findViewById(R.id.lblLoud)
        lblPreamp = findViewById(R.id.lblPreamp)
        txtStatus = findViewById(R.id.txtStatus)
        sliderWarmth = findViewById(R.id.sliderWarmth)
        sliderCrossfeed = findViewById(R.id.sliderCrossfeed)
        lblWarmth = findViewById(R.id.lblWarmth)
        lblCrossfeed = findViewById(R.id.lblCrossfeed)
        toggleGain = findViewById(R.id.toggleGain)

        vizPlayer = findViewById(R.id.vizPlayer)
        npArt = findViewById(R.id.npArt)
        npTitle = findViewById(R.id.npTitle)
        npArtist = findViewById(R.id.npArtist)
        npLike = findViewById(R.id.npLike)
        npShuffle = findViewById(R.id.npShuffle)
        npRepeat = findViewById(R.id.npRepeat)
        txtPos = findViewById(R.id.txtPos)
        txtDur = findViewById(R.id.txtDur)
        sliderSeek = findViewById(R.id.sliderSeek)
        btnPlay = findViewById(R.id.btnPlay)

        editYt = findViewById(R.id.editYt)
        btnYtPlay = findViewById(R.id.btnYtPlay)
        progressYt = findViewById(R.id.progressYt)
        txtYtStatus = findViewById(R.id.txtYtStatus)

        toggleMode = findViewById(R.id.toggleMode)
        txtModeSub = findViewById(R.id.txtModeSub)
        panelSpatial = findViewById(R.id.panelSpatial)
        panelTheater = findViewById(R.id.panelTheater)
        panel360 = findViewById(R.id.panel360)
        sliderWrap = findViewById(R.id.sliderWrap)
        sliderHeight = findViewById(R.id.sliderHeight)
        sliderRotate = findViewById(R.id.sliderRotate)
        lblWrap = findViewById(R.id.lblWrap)
        lblHeight = findViewById(R.id.lblHeight)
        lblRotate = findViewById(R.id.lblRotate)
        chipsHead = findViewById(R.id.chipsHead)
        speakerMap = findViewById(R.id.speakerMap)
        txtTestNow = findViewById(R.id.txtTestNow)
        btnSpeakerTest = findViewById(R.id.btnSpeakerTest)
        chipsRoom = findViewById(R.id.chipsRoom)
        sliderImmersion = findViewById(R.id.sliderImmersion)
        sliderVocal = findViewById(R.id.sliderVocal)
        sliderSub = findViewById(R.id.sliderSub)
        lblImmersion = findViewById(R.id.lblImmersion)
        lblVocal = findViewById(R.id.lblVocal)
        lblSub = findViewById(R.id.lblSub)
        switchHpFix = findViewById(R.id.switchHpFix)

        txtDevice = findViewById(R.id.txtDevice)
        txtLinks = findViewById(R.id.txtLinks)
        txtNoProfiles = findViewById(R.id.txtNoProfiles)
        chipsHeadphones = findViewById(R.id.chipsHeadphones)
        chipsCustom = findViewById(R.id.chipsCustom)

        miniPlayer = findViewById(R.id.miniPlayer)
        miniArt = findViewById(R.id.miniArt)
        miniTitle = findViewById(R.id.miniTitle)
        miniSub = findViewById(R.id.miniSub)
        miniPlay = findViewById(R.id.miniPlay)
        landOverlay = findViewById(R.id.landOverlay)
    }

    // ------------------------------------------------------------------ navigation

    private fun navIdFor(page: Int) = when (page) {
        PAGE_PLAYER -> R.id.nav_player
        PAGE_EQ -> R.id.nav_eq
        PAGE_PROFILES -> R.id.nav_profiles
        else -> R.id.nav_library
    }

    private fun setupNav() {
        bottomNav.setOnItemSelectedListener { item ->
            showPage(
                when (item.itemId) {
                    R.id.nav_player -> PAGE_PLAYER
                    R.id.nav_eq -> PAGE_EQ
                    R.id.nav_profiles -> PAGE_PROFILES
                    else -> PAGE_LIBRARY
                }
            )
            true
        }
    }

    private fun showPage(index: Int) {
        val wasLibrary = currentPage == PAGE_LIBRARY
        currentPage = index
        pages.forEachIndexed { i, v -> v.visibility = if (i == index) View.VISIBLE else View.GONE }
        if (index == PAGE_EQ) maybeStartGlobalViz() else stopGlobalViz()
        if (index == PAGE_PROFILES) refreshProfiles()
        if (index == PAGE_LIBRARY) library.onShow() else if (wasLibrary) library.onHide()
        refreshMini()
    }

    // ------------------------------------------------------------------ sideways album flow

    private fun applyOrientation(cfg: Configuration) {
        val land = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        if (land) {
            val f = landFlow ?: AlbumFlowController(this, landOverlay).also { landFlow = it }
            if (!MusicLibrary.hasPermission(this)) askMusicPermission() else MusicLibrary.load(this)
            f.refresh()
            PlayQueue.current?.let { f.focusAlbum(it.albumId) }
            landOverlay.visibility = View.VISIBLE
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            landOverlay.visibility = View.GONE
            ctl.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun askMusicPermission() {
        musicPermission.launch(MusicLibrary.permission)
    }

    // ------------------------------------------------------------------ equalizer page

    private fun setupEq() {
        eqCurve.onGainsChanged = { g, done ->
            AudioState.setEq(AudioState.eq.copy(gains = g), persist = done)
        }

        for (p in Presets.sound) {
            val chip = newChip(chipsPresets, p.name, checkable = false)
            chip.setOnClickListener {
                AudioState.setEq(AudioState.eq.copy(gains = p.settings.gains.copyOf()))
            }
            chipsPresets.addView(chip)
        }
        rebuildCustomPresetChips()

        EqSettings.REVERB_NAMES.forEachIndexed { i, name ->
            val chip = newChip(chipsReverb, name, checkable = true)
            chip.setOnClickListener { AudioState.setEq(AudioState.eq.copy(reverb = i)) }
            reverbChips.add(chip)
            chipsReverb.addView(chip)
        }

        findViewById<MaterialButton>(R.id.btnReset).setOnClickListener { AudioState.setEq(EqSettings()) }
        findViewById<MaterialButton>(R.id.btnSavePreset).setOnClickListener { Sheets.savePreset(this) }

        bindSlider(sliderBass) { v, done -> AudioState.setEq(AudioState.eq.copy(bass = v), done) }
        bindSlider(sliderSpatial) { v, done -> AudioState.setEq(AudioState.eq.copy(spatial = v), done) }
        bindSlider(sliderLoud) { v, done -> AudioState.setEq(AudioState.eq.copy(loudness = v), done) }
        bindSlider(sliderPreamp) { v, done -> AudioState.setEq(AudioState.eq.copy(preamp = v), done) }

        vizGlobal.setOnClickListener { requestGlobalViz() }
        txtVizHint.setOnClickListener { requestGlobalViz() }

        switchGlobal.setOnCheckedChangeListener { _, checked ->
            if (suppress) return@setOnCheckedChangeListener
            if (checked) {
                enableGlobal()
            } else {
                GlobalEffectsService.stop(this)
                handler.postDelayed({ refreshGlobal() }, 300)
            }
        }
    }

    /** Your saved presets appear after the built-in ones (long-press to rename or delete). */
    private fun rebuildCustomPresetChips() {
        for (c in customPresetChips) chipsPresets.removeView(c)
        customPresetChips.clear()
        for (p in AudioState.customProfiles()) {
            val chip = newChip(chipsPresets, p.name, checkable = false)
            chip.chipStrokeWidth = dp(1.5f)
            chip.chipStrokeColor = ColorStateList.valueOf(attrColor(R.attr.oAccent))
            chip.setOnClickListener { applyProfile(p) }
            chip.setOnLongClickListener { presetMenu(p); true }
            customPresetChips.add(chip)
            chipsPresets.addView(chip)
        }
    }

    private fun presetMenu(p: Profile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(p.name)
            .setItems(arrayOf(getString(R.string.rename), getString(R.string.delete))) { _, which ->
                if (which == 0) {
                    val input = EditText(this).apply { setText(p.name); setSelection(p.name.length); setSingleLine() }
                    val pad = dp(20f).toInt()
                    val box = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.rename)
                        .setView(box)
                        .setPositiveButton(R.string.save) { _, _ ->
                            val n = input.text.toString().trim()
                            if (n.isNotEmpty() && n != p.name && !AudioState.renameProfile(p.name, n)) {
                                toast(getString(R.string.name_taken))
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                } else {
                    confirmDelete(p)
                }
            }
            .show()
    }

    private fun setupAmp() {
        bindSlider(sliderWarmth) { v, done -> AudioState.setAmp(AudioState.amp.copy(warmth = v), done) }
        bindSlider(sliderCrossfeed) { v, done -> AudioState.setAmp(AudioState.amp.copy(crossfeed = v), done) }
        toggleGain.addOnButtonCheckedListener { _, id, isChecked ->
            if (suppress || !isChecked) return@addOnButtonCheckedListener
            AudioState.setAmp(AudioState.amp.copy(gainHigh = id == R.id.gainHigh))
        }
    }

    private fun refreshSound() {
        val s = AudioState.eq
        eqCurve.setGains(s.gains)
        setSlider(sliderBass, s.bass)
        setSlider(sliderSpatial, s.spatial)
        setSlider(sliderLoud, s.loudness)
        setSlider(sliderPreamp, s.preamp)
        lblBass.text = getString(R.string.lbl_bass, (s.bass * 100).roundToInt())
        lblSpatial.text = getString(R.string.lbl_spatial, (s.spatial * 100).roundToInt())
        lblLoud.text = getString(R.string.lbl_loud, s.loudness)
        lblPreamp.text = getString(R.string.lbl_preamp, s.preamp)
        reverbChips.forEachIndexed { i, c -> c.isChecked = i == s.reverb }
        val customNames = AudioState.customProfiles().map { it.name }
        if (customPresetChips.map { it.text.toString() } != customNames) rebuildCustomPresetChips()

        val a = AudioState.amp
        setSlider(sliderWarmth, a.warmth)
        setSlider(sliderCrossfeed, a.crossfeed)
        lblWarmth.text = getString(R.string.lbl_warmth, (a.warmth * 100).roundToInt())
        lblCrossfeed.text = getString(R.string.lbl_crossfeed, (a.crossfeed * 100).roundToInt())

        val t = AudioState.theater
        val sp = AudioState.space
        val mode = AudioState.mode
        suppress = true
        toggleGain.check(if (a.gainHigh) R.id.gainHigh else R.id.gainLow)
        toggleMode.check(
            when (mode) {
                AudioState.MODE_THEATER -> R.id.modeTheater
                AudioState.MODE_360 -> R.id.mode360
                else -> R.id.modeOff
            }
        )
        switchHpFix.isChecked = t.headphoneFix
        suppress = false
        setSlider(sliderImmersion, t.immersion)
        setSlider(sliderVocal, t.vocal)
        setSlider(sliderSub, t.sub)
        roomChips.forEachIndexed { i, c -> c.isChecked = i == t.room }
        val immName = when {
            t.immersion < 0.34f -> R.string.imm_real
            t.immersion < 0.67f -> R.string.imm_mid
            else -> R.string.imm_wow
        }
        lblImmersion.text = getString(R.string.lbl_immersion, getString(immName))
        lblVocal.text = getString(R.string.lbl_vocal, (t.vocal * 100).roundToInt())
        lblSub.text = getString(R.string.lbl_sub, (t.sub * 100).roundToInt())
        speakerMap.setImmersion(t.immersion)

        setSlider(sliderWrap, sp.wrap)
        setSlider(sliderHeight, sp.height)
        setSlider(sliderRotate, sp.rotation)
        lblWrap.text = getString(R.string.lbl_wrap, Space360Processor.wrapDegrees(sp.wrap).roundToInt())
        lblHeight.text = getString(R.string.lbl_height, (sp.height * 100).roundToInt())
        val rot = sp.rotation.roundToInt()
        lblRotate.text = getString(
            R.string.lbl_rotate,
            when {
                rot == 0 -> getString(R.string.rot_front)
                rot > 0 -> getString(R.string.rot_right, rot)
                else -> getString(R.string.rot_left, -rot)
            }
        )
        headChips.forEachIndexed { i, c -> c.isChecked = i == sp.headSize }
        speakerMap.setSpace(sp.wrap, sp.rotation)

        txtModeSub.text = getString(
            when (mode) {
                AudioState.MODE_THEATER -> R.string.mode_sub_theater
                AudioState.MODE_360 -> R.string.mode_sub_360
                else -> R.string.mode_sub_off
            }
        )
        panelSpatial.visibility = if (mode == AudioState.MODE_OFF) View.GONE else View.VISIBLE
        panelTheater.visibility = if (mode == AudioState.MODE_THEATER) View.VISIBLE else View.GONE
        panel360.visibility = if (mode == AudioState.MODE_360) View.VISIBLE else View.GONE
        speakerMap.setMode(if (mode == AudioState.MODE_360) SpeakerMapView.SPACE else SpeakerMapView.THEATER)
        if (!SpeakerTest.isRunning) {
            txtTestNow.text = getString(if (mode == AudioState.MODE_360) R.string.map_hint_360 else R.string.test_hint)
        }
        refreshMini()
    }

    private fun enableGlobal() {
        val needsNotif = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsNotif) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else startGlobal()
    }

    private fun startGlobal() {
        try {
            GlobalEffectsService.start(this)
        } catch (e: Exception) {
            toast(e.message ?: "Couldn't start")
        }
        handler.postDelayed({ refreshGlobal() }, 700)
    }

    private fun refreshGlobal() {
        val on = GlobalEffectsService.running
        if (switchGlobal.isChecked != on) {
            suppress = true
            switchGlobal.isChecked = on
            suppress = false
        }
        txtHeaderSub.text = getString(if (on) R.string.global_on else R.string.global_off)
        txtStatus.text = if (on) GlobalEffectsService.status else getString(R.string.status_off)
    }

    private fun handleEffectIntent(i: Intent?) {
        if (i?.action != AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL) return
        val session = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        bottomNav.selectedItemId = R.id.nav_eq
        if (session > 0 && GlobalEffectsService.running) {
            try {
                GlobalEffectsService.start(this, session)
            } catch (_: Exception) {
            }
        }
    }

    // --- system-wide visualizer (needs the audio-output permission Android files under "microphone")

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    private fun requestGlobalViz() {
        if (hasMic()) startGlobalViz() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun maybeStartGlobalViz() {
        if (hasMic()) startGlobalViz()
    }

    private fun startGlobalViz() {
        if (globalViz != null || currentPage != PAGE_EQ) return
        try {
            val v = Visualizer(0)
            v.setEnabled(false)
            v.setCaptureSize(Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024))
            val bands = FloatArray(Spectrum.BANDS)
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}

                    override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (fft == null) return
                        Spectrum.fromVisualizerFft(fft, samplingRate, bands)
                        vizGlobal.setLevels(bands)
                    }
                },
                (Visualizer.getMaxCaptureRate() / 2).coerceAtMost(20000),
                false,
                true
            )
            v.setEnabled(true)
            globalViz = v
            txtVizHint.text = getString(R.string.viz_live)
        } catch (t: Throwable) {
            txtVizHint.text = getString(R.string.viz_unsupported)
        }
    }

    private fun stopGlobalViz() {
        try {
            globalViz?.setEnabled(false)
            globalViz?.release()
        } catch (_: Throwable) {
        }
        globalViz = null
        vizGlobal.clear()
    }

    override fun onVizStyleChanged(style: Int) {
        vizGlobal.style = style
        vizPlayer.style = style
    }

    // ------------------------------------------------------------------ now playing

    private fun setupPlayer() {
        findViewById<MaterialButton>(R.id.btnOpen).setOnClickListener { pickAudio.launch(arrayOf("audio/*")) }
        btnPlay.setOnClickListener {
            if (PlayQueue.isEmpty && !PlayerEngine.state.loaded) {
                bottomNav.selectedItemId = R.id.nav_library
            } else {
                PlayQueue.togglePlay(this)
            }
        }
        findViewById<MaterialButton>(R.id.npPrev).setOnClickListener { PlayQueue.previous() }
        findViewById<MaterialButton>(R.id.npNext).setOnClickListener { PlayQueue.next() }
        npShuffle.setOnClickListener { PlayQueue.toggleShuffle() }
        npRepeat.setOnClickListener { PlayQueue.cycleRepeat() }
        npLike.setOnClickListener { PlayQueue.current?.let { UserLibrary.toggleLike(it.id) } }
        findViewById<MaterialButton>(R.id.npQueue).setOnClickListener { Sheets.queue(this) }

        sliderSeek.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                seekTouching = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                seekTouching = false
                val d = PlayerEngine.state.durationMs
                if (d > 0) PlayerEngine.seekTo((slider.value * d).toLong())
            }
        })
        sliderSeek.addOnChangeListener { _, value, fromUser ->
            if (fromUser) txtPos.text = fmtTime((value * PlayerEngine.state.durationMs).toLong())
        }
    }

    private fun refreshNowPlaying() {
        val t = PlayQueue.current
        val st = PlayerEngine.state
        npTitle.text = t?.title ?: st.title ?: getString(R.string.no_song)
        npTitle.isSelected = true
        npArtist.text = when {
            st.error != null -> st.error
            t != null -> listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · ")
            else -> getString(R.string.open_hint)
        }
        val liked = t != null && UserLibrary.isLiked(t.id)
        npLike.visibility = if (t != null && t.id > 0) View.VISIBLE else View.INVISIBLE
        npLike.setIconResource(if (liked) R.drawable.ic_heart else R.drawable.ic_heart_outline)
        npLike.iconTint = ColorStateList.valueOf(attrColor(if (liked) R.attr.oAccent else R.attr.oText))
        npShuffle.iconTint = ColorStateList.valueOf(attrColor(if (PlayQueue.shuffle) R.attr.oAccent else R.attr.oTextDim))
        npRepeat.setIconResource(if (PlayQueue.repeat == PlayQueue.REPEAT_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        npRepeat.iconTint = ColorStateList.valueOf(
            attrColor(if (PlayQueue.repeat == PlayQueue.REPEAT_OFF) R.attr.oTextDim else R.attr.oAccent)
        )
        if (t?.uri != shownArtFor) {
            shownArtFor = t?.uri
            showArt(npArt, t, 600)
            showArt(miniArt, t, 160)
        }
        library.setCurrent(t?.uri)
        refreshMini()
    }

    private fun showArt(iv: ImageView, t: Track?, size: Int) {
        iv.setImageResource(R.drawable.ic_note)
        iv.scaleType = ImageView.ScaleType.CENTER
        iv.setBackgroundColor(attrColor(R.attr.oSurface2))
        iv.imageTintList = ColorStateList.valueOf(attrColor(R.attr.oTextDim))
        if (t == null) return
        val want = t.uri
        ArtLoader.load(this, t.albumId, size, t.uri) { b ->
            if (b != null && PlayQueue.current?.uri == want) {
                iv.imageTintList = null
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                iv.setImageBitmap(b)
            }
        }
    }

    override fun onPlaybackState(state: PlayerEngine.State) {
        btnPlay.setIconResource(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlay.contentDescription = getString(if (state.playing) R.string.pause else R.string.play)
        miniPlay.setIconResource(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play)
        txtDur.text = fmtTime(state.durationMs)
        if (!seekTouching) {
            txtPos.text = fmtTime(state.positionMs)
            val frac = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
            setSlider(sliderSeek, frac)
        }
        if (state.error != null) npArtist.text = state.error
    }

    override fun onSpectrum(bands: FloatArray) {
        vizPlayer.setLevels(bands)
    }

    // ------------------------------------------------------------------ mini player

    private fun setupMiniPlayer() {
        miniPlayer.setOnClickListener { bottomNav.selectedItemId = R.id.nav_player }
        miniPlay.setOnClickListener { PlayQueue.togglePlay(this) }
        findViewById<MaterialButton>(R.id.miniNext).setOnClickListener { PlayQueue.next() }
    }

    private fun refreshMini() {
        if (!::miniPlayer.isInitialized) return
        val t = PlayQueue.current
        val show = currentPage != PAGE_PLAYER && t != null
        miniPlayer.visibility = if (show) View.VISIBLE else View.GONE
        if (t != null) {
            miniTitle.text = t.title
            val mode = when (AudioState.mode) {
                AudioState.MODE_THEATER -> getString(R.string.theater)
                AudioState.MODE_360 -> getString(R.string.mode_360)
                else -> null
            }
            miniSub.text = listOfNotNull(t.artist.takeIf { it.isNotBlank() }, mode).joinToString(" · ")
        }
    }

    // ------------------------------------------------------------------ sound mode

    private fun setupTheater() {
        toggleMode.addOnButtonCheckedListener { _, id, isChecked ->
            if (suppress || !isChecked) return@addOnButtonCheckedListener
            val m = when (id) {
                R.id.modeTheater -> AudioState.MODE_THEATER
                R.id.mode360 -> AudioState.MODE_360
                else -> AudioState.MODE_OFF
            }
            if (m != AudioState.mode) {
                SpeakerTest.stop()
                AudioState.setMode(m)
            }
        }
        bindSlider(sliderWrap) { v, done -> AudioState.setSpace(AudioState.space.copy(wrap = v), done) }
        bindSlider(sliderHeight) { v, done -> AudioState.setSpace(AudioState.space.copy(height = v), done) }
        bindSlider(sliderRotate) { v, done -> AudioState.setSpace(AudioState.space.copy(rotation = v), done) }
        findViewById<MaterialButton>(R.id.btnResetRotate).setOnClickListener {
            AudioState.setSpace(AudioState.space.copy(rotation = 0f))
        }
        Space360Processor.HEAD_NAMES.forEachIndexed { i, name ->
            val chip = newChip(chipsHead, name, checkable = true)
            chip.setOnClickListener { AudioState.setSpace(AudioState.space.copy(headSize = i)) }
            headChips.add(chip)
            chipsHead.addView(chip)
        }
        speakerMap.onRotate = { deg, done -> AudioState.setSpace(AudioState.space.copy(rotation = deg), done) }
        switchHpFix.setOnCheckedChangeListener { _, checked ->
            if (suppress) return@setOnCheckedChangeListener
            AudioState.setTheater(AudioState.theater.copy(headphoneFix = checked))
        }
        TheaterProcessor.ROOM_NAMES.forEachIndexed { i, name ->
            val chip = newChip(chipsRoom, name, checkable = true)
            chip.setOnClickListener { AudioState.setTheater(AudioState.theater.copy(room = i)) }
            roomChips.add(chip)
            chipsRoom.addView(chip)
        }
        bindSlider(sliderImmersion) { v, done -> AudioState.setTheater(AudioState.theater.copy(immersion = v), done) }
        bindSlider(sliderVocal) { v, done -> AudioState.setTheater(AudioState.theater.copy(vocal = v), done) }
        bindSlider(sliderSub) { v, done -> AudioState.setTheater(AudioState.theater.copy(sub = v), done) }

        speakerMap.onSpeakerTapped = { i -> SpeakerTest.playOne(i) }
        btnSpeakerTest.setOnClickListener {
            if (SpeakerTest.isRunning) SpeakerTest.stop() else SpeakerTest.playAll()
        }
    }

    override fun onTestSpeaker(index: Int) {
        speakerMap.setActive(index)
        if (index >= 0) {
            val names = if (AudioState.mode == AudioState.MODE_360) Space360Processor.NAMES else TheaterProcessor.NAMES
            txtTestNow.text = getString(R.string.test_now, names.getOrElse(index) { "" })
            btnSpeakerTest.text = getString(R.string.test_stop)
        } else {
            txtTestNow.text = getString(if (AudioState.mode == AudioState.MODE_360) R.string.map_hint_360 else R.string.test_hint)
            btnSpeakerTest.text = getString(R.string.test_all)
        }
    }

    // ------------------------------------------------------------------ youtube

    private fun setupYouTube() {
        YouTubeLoader.listener = this
        btnYtPlay.setOnClickListener { playYouTube(editYt.text?.toString()) }
        editYt.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                playYouTube(editYt.text?.toString())
                true
            } else {
                false
            }
        }
        findViewById<MaterialButton>(R.id.btnYtPaste).setOnClickListener {
            val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
            val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString() else null
            val link = YouTubeLoader.findUrl(text)
            if (link == null) {
                toast(getString(R.string.yt_clipboard_empty))
            } else {
                editYt.setText(link)
                playYouTube(link)
            }
        }
    }

    private fun handleShareIntent(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val link = YouTubeLoader.findUrl(i.getStringExtra(Intent.EXTRA_TEXT))
        if (link == null) {
            toast(getString(R.string.yt_no_link))
            return
        }
        // handle each share once (not again after rotation)
        i.action = null
        bottomNav.selectedItemId = R.id.nav_player
        editYt.setText(link)
        playYouTube(link)
    }

    private fun playYouTube(text: String?) {
        val link = YouTubeLoader.findUrl(text)
        if (link == null) {
            toast(getString(R.string.yt_no_link))
            return
        }
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(editYt.windowToken, 0)
        editYt.clearFocus()
        SpeakerTest.stop()
        YouTubeLoader.load(this, link)
    }

    override fun onYtProgress(message: String, progress: Int) {
        txtYtStatus.text = message
        progressYt.visibility = View.VISIBLE
        if (progress < 0) {
            if (!progressYt.isIndeterminate) {
                progressYt.visibility = View.INVISIBLE
                progressYt.isIndeterminate = true
                progressYt.visibility = View.VISIBLE
            }
        } else {
            if (progressYt.isIndeterminate) {
                progressYt.visibility = View.INVISIBLE
                progressYt.isIndeterminate = false
                progressYt.visibility = View.VISIBLE
            }
            progressYt.setProgressCompat(progress, true)
        }
        btnYtPlay.isEnabled = false
    }

    override fun onYtReady(file: File, title: String) {
        progressYt.visibility = View.GONE
        btnYtPlay.isEnabled = true
        txtYtStatus.text = getString(R.string.yt_playing)
        PlayQueue.playSingle(
            this,
            Track(
                id = -2, uri = Uri.fromFile(file).toString(), title = title, artist = getString(R.string.youtube),
                album = "", albumId = -1, durationMs = 0,
            )
        )
    }

    override fun onYtError(message: String) {
        progressYt.visibility = View.GONE
        btnYtPlay.isEnabled = true
        txtYtStatus.text = message
        toast(message)
    }

    private fun loadSong(uri: Uri) {
        if (YouTubeLoader.busy) {
            YouTubeLoader.cancel()
            progressYt.visibility = View.GONE
            btnYtPlay.isEnabled = true
            txtYtStatus.text = getString(R.string.yt_sub)
        }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        val name = queryName(uri)?.substringBeforeLast('.') ?: "Song"
        PlayQueue.playSingle(
            this,
            Track(id = -1, uri = uri.toString(), title = name, artist = "", album = "", albumId = -1, durationMs = 0)
        )
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------------ headphones page

    private fun setupProfiles() {
        for (p in Presets.headphones) {
            val chip = newChip(chipsHeadphones, p.name, checkable = false)
            chip.setOnClickListener { applyProfile(p) }
            chipsHeadphones.addView(chip)
        }
        findViewById<MaterialButton>(R.id.btnLinkDevice).setOnClickListener { linkDevice() }
        findViewById<MaterialButton>(R.id.btnSaveProfile).setOnClickListener { Sheets.savePreset(this) }
    }

    private fun applyProfile(p: Profile) {
        AudioState.applyProfile(p)
        toast(getString(R.string.applied, p.name))
    }

    private fun refreshProfiles() {
        val dev = DeviceUtil.currentHeadphone(this)
        txtDevice.text = if (dev != null) getString(R.string.device_now, DeviceUtil.label(dev))
        else getString(R.string.device_none)

        chipsCustom.removeAllViews()
        val custom = AudioState.customProfiles()
        for (p in custom) {
            val chip = newChip(chipsCustom, p.name, checkable = false)
            chip.setOnClickListener { applyProfile(p) }
            chip.setOnLongClickListener {
                presetMenu(p)
                true
            }
            chipsCustom.addView(chip)
        }
        txtNoProfiles.visibility = if (custom.isEmpty()) View.VISIBLE else View.GONE

        val links = AudioState.deviceLinks()
        txtLinks.text = if (links.isEmpty()) getString(R.string.no_links)
        else links.entries.joinToString("\n") { "• ${it.key.substringAfter(':')}  →  ${it.value}" }
    }

    private fun linkDevice() {
        val dev = DeviceUtil.currentHeadphone(this)
        if (dev == null) {
            toast(getString(R.string.connect_first))
            return
        }
        val label = DeviceUtil.label(dev)
        AudioState.saveProfile(label, AudioState.eq)
        AudioState.linkDevice(DeviceUtil.key(dev), label)
        toast(getString(R.string.linked, label))
        refreshProfiles()
    }

    private fun confirmDelete(p: Profile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_q, p.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                AudioState.deleteProfile(p.name)
                refreshProfiles()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ helpers

    private fun newChip(parent: ViewGroup, text: String, checkable: Boolean): Chip {
        val chip = layoutInflater.inflate(R.layout.item_chip, parent, false) as Chip
        chip.id = View.generateViewId()
        chip.text = text
        chip.isCheckable = checkable
        return chip
    }

    private fun bindSlider(s: Slider, onChange: (Float, Boolean) -> Unit) {
        s.addOnChangeListener { _, value, fromUser -> if (fromUser) onChange(value, false) }
        s.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                onChange(slider.value, true)
            }
        })
    }

    private fun setSlider(s: Slider, v: Float) {
        val clamped = v.coerceIn(s.valueFrom, s.valueTo)
        if (s.value != clamped) s.value = clamped
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
