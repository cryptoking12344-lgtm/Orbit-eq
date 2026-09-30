package com.ck.orbiteq

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ck.orbiteq.dsp.Spectrum
import com.ck.orbiteq.global.DeviceUtil
import com.ck.orbiteq.global.GlobalEffectsService
import com.ck.orbiteq.model.AudioState
import com.ck.orbiteq.model.EqSettings
import com.ck.orbiteq.model.Presets
import com.ck.orbiteq.model.Profile
import com.ck.orbiteq.dsp.TheaterProcessor
import com.ck.orbiteq.player.PlayerEngine
import com.ck.orbiteq.player.SpeakerTest
import com.ck.orbiteq.ui.EqCurveView
import com.ck.orbiteq.ui.SpeakerMapView
import com.ck.orbiteq.ui.VisualizerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), PlayerEngine.Listener, SpeakerTest.Listener {

    // header
    private lateinit var switchGlobal: MaterialSwitch
    private lateinit var txtHeaderSub: TextView
    private lateinit var pages: List<View>

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

    // player page
    private lateinit var vizPlayer: VisualizerView
    private lateinit var txtTrack: TextView
    private lateinit var txtTrackSub: TextView
    private lateinit var txtPos: TextView
    private lateinit var txtDur: TextView
    private lateinit var sliderSeek: Slider
    private lateinit var btnPlay: MaterialButton
    private lateinit var switch8d: MaterialSwitch
    private lateinit var slider8dSpeed: Slider
    private lateinit var slider8dDepth: Slider
    private lateinit var lbl8dSpeed: TextView
    private lateinit var lbl8dDepth: TextView

    // theater
    private lateinit var switchTheater: MaterialSwitch
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
    private lateinit var card8d: View
    private lateinit var txt8dSub: TextView
    private val roomChips = ArrayList<Chip>()

    // headphones page
    private lateinit var txtDevice: TextView
    private lateinit var txtLinks: TextView
    private lateinit var txtNoProfiles: TextView
    private lateinit var chipsHeadphones: ChipGroup
    private lateinit var chipsCustom: ChipGroup

    private val handler = Handler(Looper.getMainLooper())
    private var globalViz: Visualizer? = null
    private var seekTouching = false
    private var suppress = false
    private var currentPage = 0
    private val reverbChips = ArrayList<Chip>()
    private val stateListener: () -> Unit = { refreshSound() }

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

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AudioState.init(this)
        setContentView(R.layout.activity_main)
        bindViews()
        setupNav()
        setupEq()
        setupPlayer()
        setupTheater()
        setupProfiles()
        handleEffectIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleEffectIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AudioState.addListener(stateListener)
        PlayerEngine.addListener(this)
        SpeakerTest.listener = this
        refreshSound()
        onPlaybackState(PlayerEngine.state)
        refreshProfiles()
        handler.post(statusPoll)
        if (currentPage == 0) maybeStartGlobalViz()
    }

    override fun onPause() {
        super.onPause()
        AudioState.removeListener(stateListener)
        PlayerEngine.removeListener(this)
        SpeakerTest.listener = null
        SpeakerTest.stop()
        onTestSpeaker(-1)
        handler.removeCallbacks(statusPoll)
        stopGlobalViz()
    }

    private fun bindViews() {
        switchGlobal = findViewById(R.id.switchGlobal)
        txtHeaderSub = findViewById(R.id.txtHeaderSub)
        pages = listOf<View>(
            findViewById(R.id.pageEq),
            findViewById(R.id.pagePlayer),
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

        vizPlayer = findViewById(R.id.vizPlayer)
        txtTrack = findViewById(R.id.txtTrack)
        txtTrackSub = findViewById(R.id.txtTrackSub)
        txtPos = findViewById(R.id.txtPos)
        txtDur = findViewById(R.id.txtDur)
        sliderSeek = findViewById(R.id.sliderSeek)
        btnPlay = findViewById(R.id.btnPlay)
        switch8d = findViewById(R.id.switch8d)
        slider8dSpeed = findViewById(R.id.slider8dSpeed)
        slider8dDepth = findViewById(R.id.slider8dDepth)
        lbl8dSpeed = findViewById(R.id.lbl8dSpeed)
        lbl8dDepth = findViewById(R.id.lbl8dDepth)

        switchTheater = findViewById(R.id.switchTheater)
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
        card8d = findViewById(R.id.card8d)
        txt8dSub = findViewById(R.id.txt8dSub)

        txtDevice = findViewById(R.id.txtDevice)
        txtLinks = findViewById(R.id.txtLinks)
        txtNoProfiles = findViewById(R.id.txtNoProfiles)
        chipsHeadphones = findViewById(R.id.chipsHeadphones)
        chipsCustom = findViewById(R.id.chipsCustom)
    }

    // ------------------------------------------------------------------ navigation

    private fun setupNav() {
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener { item ->
            showPage(
                when (item.itemId) {
                    R.id.nav_player -> 1
                    R.id.nav_profiles -> 2
                    else -> 0
                }
            )
            true
        }
    }

    private fun showPage(index: Int) {
        currentPage = index
        pages.forEachIndexed { i, v -> v.visibility = if (i == index) View.VISIBLE else View.GONE }
        if (index == 0) maybeStartGlobalViz() else stopGlobalViz()
        if (index == 2) refreshProfiles()
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

        EqSettings.REVERB_NAMES.forEachIndexed { i, name ->
            val chip = newChip(chipsReverb, name, checkable = true)
            chip.setOnClickListener { AudioState.setEq(AudioState.eq.copy(reverb = i)) }
            reverbChips.add(chip)
            chipsReverb.addView(chip)
        }

        findViewById<MaterialButton>(R.id.btnReset).setOnClickListener {
            AudioState.setEq(EqSettings())
        }

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

        val e = AudioState.eightD
        suppress = true
        switch8d.isChecked = e.enabled
        suppress = false
        setSlider(slider8dSpeed, e.speedHz)
        setSlider(slider8dDepth, e.depth)
        lbl8dSpeed.text = getString(R.string.lbl_8d_speed, 1f / e.speedHz)
        lbl8dDepth.text = getString(R.string.lbl_8d_depth, (e.depth * 100).roundToInt())

        val t = AudioState.theater
        suppress = true
        switchTheater.isChecked = t.enabled
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
        speakerMap.setOn(t.enabled)

        // Theater replaces the 8D effect while it's on.
        card8d.alpha = if (t.enabled) 0.45f else 1f
        switch8d.isEnabled = !t.enabled
        slider8dSpeed.isEnabled = !t.enabled
        slider8dDepth.isEnabled = !t.enabled
        txt8dSub.text = getString(if (t.enabled) R.string.eight_d_off_theater else R.string.eight_d_sub)
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
        if (session > 0 && GlobalEffectsService.running) {
            try {
                GlobalEffectsService.start(this, session)
            } catch (_: Exception) {
            }
        }
    }

    // --- system-wide visualizer (needs microphone permission to read the output mix)

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    private fun requestGlobalViz() {
        if (hasMic()) startGlobalViz() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun maybeStartGlobalViz() {
        if (hasMic()) startGlobalViz()
    }

    private fun startGlobalViz() {
        if (globalViz != null || currentPage != 0) return
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

    // ------------------------------------------------------------------ player page

    private fun setupPlayer() {
        findViewById<MaterialButton>(R.id.btnOpen).setOnClickListener { pickAudio.launch(arrayOf("audio/*")) }
        btnPlay.setOnClickListener {
            if (!PlayerEngine.state.loaded) pickAudio.launch(arrayOf("audio/*")) else PlayerEngine.togglePlay()
        }
        findViewById<MaterialButton>(R.id.btnStop).setOnClickListener {
            PlayerEngine.stop()
            vizPlayer.clear()
        }

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
            if (fromUser) txtPos.text = fmt((value * PlayerEngine.state.durationMs).toLong())
        }

        switch8d.setOnCheckedChangeListener { _, checked ->
            if (suppress) return@setOnCheckedChangeListener
            val e = AudioState.eightD
            AudioState.setEightD(AudioState.EightD(checked, e.speedHz, e.depth))
        }
        bindSlider(slider8dSpeed) { v, _ ->
            val e = AudioState.eightD
            AudioState.setEightD(AudioState.EightD(e.enabled, v, e.depth))
        }
        bindSlider(slider8dDepth) { v, _ ->
            val e = AudioState.eightD
            AudioState.setEightD(AudioState.EightD(e.enabled, e.speedHz, v))
        }
    }

    // ------------------------------------------------------------------ theater

    private fun setupTheater() {
        switchTheater.setOnCheckedChangeListener { _, checked ->
            if (suppress) return@setOnCheckedChangeListener
            AudioState.setTheater(AudioState.theater.copy(enabled = checked))
        }
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
            txtTestNow.text = getString(R.string.test_now, TheaterProcessor.NAMES[index])
            btnSpeakerTest.text = getString(R.string.test_stop)
        } else {
            txtTestNow.text = getString(R.string.test_hint)
            btnSpeakerTest.text = getString(R.string.test_all)
        }
    }

    private fun loadSong(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        val name = queryName(uri)?.substringBeforeLast('.') ?: "Song"
        PlayerEngine.load(this, uri, name)
        PlayerEngine.play()
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    override fun onPlaybackState(state: PlayerEngine.State) {
        txtTrack.text = state.title ?: getString(R.string.no_song)
        txtTrackSub.text = when {
            state.error != null -> state.error
            state.playing -> getString(R.string.now_playing)
            state.loaded -> getString(R.string.paused)
            else -> getString(R.string.open_hint)
        }
        btnPlay.text = getString(if (state.playing) R.string.pause else R.string.play)
        btnPlay.setIconResource(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play)
        txtDur.text = fmt(state.durationMs)
        if (!seekTouching) {
            txtPos.text = fmt(state.positionMs)
            val frac = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
            setSlider(sliderSeek, frac)
        }
    }

    override fun onSpectrum(bands: FloatArray) {
        vizPlayer.setLevels(bands)
    }

    // ------------------------------------------------------------------ headphones page

    private fun setupProfiles() {
        for (p in Presets.headphones) {
            val chip = newChip(chipsHeadphones, p.name, checkable = false)
            chip.setOnClickListener { applyProfile(p) }
            chipsHeadphones.addView(chip)
        }
        findViewById<MaterialButton>(R.id.btnLinkDevice).setOnClickListener { linkDevice() }
        findViewById<MaterialButton>(R.id.btnSaveProfile).setOnClickListener { promptSave() }
    }

    private fun applyProfile(p: Profile) {
        AudioState.setEq(p.settings.copy())
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
                confirmDelete(p)
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

    private fun promptSave() {
        val input = EditText(this).apply {
            hint = getString(R.string.profile_name)
            setSingleLine()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.save_title)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    AudioState.saveProfile(name, AudioState.eq)
                    refreshProfiles()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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

    private fun fmt(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
