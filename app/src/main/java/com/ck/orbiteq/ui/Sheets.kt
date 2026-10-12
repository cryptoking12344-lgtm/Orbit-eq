package com.ck.orbiteq.ui

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ck.orbiteq.R
import com.ck.orbiteq.global.DeviceUtil
import com.ck.orbiteq.model.AudioState
import com.ck.orbiteq.player.PlayQueue
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText

object Sheets {

    private fun sheet(act: Activity, layout: Int): Pair<BottomSheetDialog, View> {
        val d = BottomSheetDialog(act)
        val v = LayoutInflater.from(act).inflate(layout, null, false)
        d.setContentView(v)
        d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        d.behavior.skipCollapsed = true
        return d to v
    }

    // ------------------------------------------------------------------ save EQ preset

    fun savePreset(act: Activity) {
        val (d, v) = sheet(act, R.layout.sheet_preset)
        val curve: EqCurveView = v.findViewById(R.id.presetCurve)
        curve.interactive = false
        curve.setGains(AudioState.eq.gains)
        val name: TextInputEditText = v.findViewById(R.id.presetName)
        val fx: MaterialCheckBox = v.findViewById(R.id.presetIncludeFx)
        val link: MaterialCheckBox = v.findViewById(R.id.presetLink)
        val dev = DeviceUtil.currentHeadphone(act)
        if (dev != null) {
            link.text = act.getString(R.string.preset_link, DeviceUtil.label(dev))
        } else {
            link.text = act.getString(R.string.preset_link_none)
            link.isEnabled = false
        }
        val mine: ChipGroup = v.findViewById(R.id.presetMine)
        val customs = AudioState.customProfiles()
        v.findViewById<View>(R.id.presetMineLabel).visibility = if (customs.isEmpty()) View.GONE else View.VISIBLE
        for (p in customs) {
            val chip = LayoutInflater.from(act).inflate(R.layout.item_chip, mine, false) as Chip
            chip.text = p.name
            chip.isCheckable = false
            // tapping an existing one fills the name (saving then overwrites it)
            chip.setOnClickListener { name.setText(p.name); name.setSelection(p.name.length) }
            mine.addView(chip)
        }
        v.findViewById<MaterialButton>(R.id.presetCancel).setOnClickListener { d.dismiss() }
        v.findViewById<MaterialButton>(R.id.presetSave).setOnClickListener {
            val n = name.text?.toString()?.trim().orEmpty()
            if (n.isEmpty()) {
                name.error = act.getString(R.string.preset_need_name)
                return@setOnClickListener
            }
            AudioState.saveProfile(n, AudioState.eq, fx.isChecked)
            if (link.isChecked && dev != null) AudioState.linkDevice(DeviceUtil.key(dev), n)
            Toast.makeText(act, act.getString(R.string.preset_saved, n), Toast.LENGTH_SHORT).show()
            d.dismiss()
        }
        d.show()
    }

    // ------------------------------------------------------------------ appearance

    fun appearance(act: Activity) {
        val (d, v) = sheet(act, R.layout.sheet_appearance)
        val cards: LinearLayout = v.findViewById(R.id.themeCards)
        val current = Appearance.theme(act)
        val accent = Appearance.accent(act)
        val inf = LayoutInflater.from(act)
        for (i in 0..2) {
            val card = inf.inflate(R.layout.item_theme_card, cards, false) as MaterialCardView
            card.findViewById<ThemePreviewView>(R.id.themePreview).apply {
                theme = i
                this.accent = Appearance.ACCENT_COLORS[if (i == current) accent else if (i == Appearance.THEME_MIDNIGHT) 2 else 0]
            }
            card.findViewById<TextView>(R.id.themeName).text = Appearance.THEME_NAMES[i]
            card.findViewById<TextView>(R.id.themeDesc).text = Appearance.THEME_DESCS[i]
            card.isChecked = i == current
            card.strokeColor = act.attrColor(if (i == current) R.attr.oAccent else R.attr.oSurface2)
            card.contentDescription = act.getString(R.string.theme_cd, Appearance.THEME_NAMES[i])
            card.setOnClickListener {
                if (i != current) {
                    Appearance.setTheme(act, i)
                    d.dismiss()
                    act.recreate()
                }
            }
            cards.addView(card)
        }

        val row: LinearLayout = v.findViewById(R.id.accentRow)
        val retro = current == Appearance.THEME_RETRO
        v.findViewById<View>(R.id.accentLabel).visibility = if (retro) View.GONE else View.VISIBLE
        row.visibility = if (retro) View.GONE else View.VISIBLE
        val size = act.dp(44f).toInt()
        for (i in Appearance.ACCENT_COLORS.indices) {
            val b = android.widget.Button(act)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = act.dp(12f).toInt()
            b.layoutParams = lp
            b.minWidth = 0; b.minHeight = 0; b.minimumWidth = 0; b.minimumHeight = 0
            b.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Appearance.ACCENT_COLORS[i])
                if (i == accent) setStroke(act.dp(3f).toInt(), act.attrColor(R.attr.oText))
            }
            b.contentDescription = Appearance.ACCENT_NAMES[i]
            b.setOnClickListener {
                if (i != accent) {
                    Appearance.setAccent(act, i)
                    d.dismiss()
                    act.recreate()
                }
            }
            row.addView(b)
        }

        val viz: ChipGroup = v.findViewById(R.id.vizStyles)
        val style = Appearance.vizStyle(act)
        Appearance.VIZ_NAMES.forEachIndexed { i, n ->
            val chip = inf.inflate(R.layout.item_chip, viz, false) as Chip
            chip.id = View.generateViewId()
            chip.text = n
            chip.isCheckable = true
            chip.isChecked = i == style
            chip.setOnClickListener {
                Appearance.setVizStyle(act, i)
                (act as? VizStyleHost)?.onVizStyleChanged(i)
            }
            viz.addView(chip)
        }
        d.show()
    }

    interface VizStyleHost {
        fun onVizStyleChanged(style: Int)
    }

    // ------------------------------------------------------------------ queue

    fun queue(act: Activity) {
        val (d, v) = sheet(act, R.layout.sheet_list)
        v.findViewById<TextView>(R.id.sheetTitle).setText(R.string.up_next)
        val list: RecyclerView = v.findViewById(R.id.sheetList)
        list.layoutManager = LinearLayoutManager(act)
        val up = PlayQueue.upcoming()
        val adapter = TrackListAdapter { i ->
            PlayQueue.jumpTo(i)
            d.dismiss()
        }
        adapter.set(up, PlayQueue.current?.uri, numbers = false)
        list.adapter = adapter
        if (up.isEmpty()) {
            v.findViewById<TextView>(R.id.sheetTitle).setText(R.string.queue_empty)
        }
        list.layoutParams = list.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        d.show()
    }
}
