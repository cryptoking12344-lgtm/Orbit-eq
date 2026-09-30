package com.ck.orbiteq.global

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import com.ck.orbiteq.model.EqSettings

/**
 * Android's built-in audio effects attached to one audio session.
 * Session 0 = the global output mix (works on most phones).
 */
class EffectChain(val session: Int) {

    companion object {
        private const val PRIORITY = 1000

        /** Upper edge of each EQ band (geometric midpoints between centres). */
        private val CUTOFFS = floatArrayOf(44f, 88f, 177f, 354f, 707f, 1414f, 2828f, 5657f, 11314f, 20000f)

        private val REVERB_MAP = shortArrayOf(
            PresetReverb.PRESET_NONE,
            PresetReverb.PRESET_SMALLROOM,
            PresetReverb.PRESET_MEDIUMROOM,
            PresetReverb.PRESET_LARGEROOM,
            PresetReverb.PRESET_MEDIUMHALL,
            PresetReverb.PRESET_LARGEHALL,
        )
    }

    private var dp: DynamicsProcessing? = null
    private var virt: Virtualizer? = null
    private var rev: PresetReverb? = null
    val problems = ArrayList<String>()

    init {
        try {
            val eq = DynamicsProcessing.Eq(true, true, EqSettings.BANDS)
            for (i in 0 until EqSettings.BANDS) {
                eq.setBand(i, DynamicsProcessing.EqBand(true, CUTOFFS[i], 0f))
            }
            val limiter = DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -2f, 0f)
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,
                true, EqSettings.BANDS,
                false, 0,
                false, 0,
                true
            )
                .setPreEqAllChannelsTo(eq)
                .setLimiterAllChannelsTo(limiter)
                .build()
            val d = DynamicsProcessing(PRIORITY, session, cfg)
            d.setEnabled(true)
            dp = d
        } catch (t: Throwable) {
            problems.add("equalizer")
        }
        try {
            virt = Virtualizer(PRIORITY, session)
        } catch (t: Throwable) {
            problems.add("3D surround")
        }
        try {
            rev = PresetReverb(PRIORITY, session)
        } catch (t: Throwable) {
            problems.add("reverb")
        }
    }

    val worksAtAll: Boolean get() = dp != null || virt != null || rev != null

    fun apply(s: EqSettings) {
        dp?.let { d ->
            try {
                for (i in 0 until EqSettings.BANDS) {
                    var g = s.gains[i]
                    when (i) {
                        0 -> g += s.bass * 9f
                        1 -> g += s.bass * 7f
                        2 -> g += s.bass * 3.5f
                    }
                    d.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, CUTOFFS[i], g))
                }
                d.setInputGainAllChannelsTo(s.preamp + s.loudness)
            } catch (_: Throwable) {
            }
        }
        virt?.let { v ->
            try {
                if (s.spatial > 0.01f) {
                    if (v.strengthSupported) v.setStrength((s.spatial * 1000).toInt().coerceIn(0, 1000).toShort())
                    v.setEnabled(true)
                } else {
                    v.setEnabled(false)
                }
            } catch (_: Throwable) {
            }
        }
        rev?.let { r ->
            try {
                if (s.reverb > 0) {
                    r.setPreset(REVERB_MAP[s.reverb.coerceIn(0, REVERB_MAP.size - 1)])
                    r.setEnabled(true)
                } else {
                    r.setEnabled(false)
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun release() {
        try { dp?.release() } catch (_: Throwable) {}
        try { virt?.release() } catch (_: Throwable) {}
        try { rev?.release() } catch (_: Throwable) {}
        dp = null
        virt = null
        rev = null
    }
}
