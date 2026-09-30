package com.ck.orbiteq.model

import org.json.JSONArray
import org.json.JSONObject

/** One complete "sound": 10-band EQ + effects. Immutable; use copy() to change. */
class EqSettings(
    val gains: FloatArray = FloatArray(BANDS),
    val preamp: Float = 0f,     // dB, -12..+6
    val bass: Float = 0f,       // 0..1
    val spatial: Float = 0f,    // 0..1  (3D surround / Atmos-style)
    val reverb: Int = 0,        // index into REVERB_NAMES
    val loudness: Float = 0f,   // dB, 0..8
) {
    fun copy(
        gains: FloatArray = this.gains.copyOf(),
        preamp: Float = this.preamp,
        bass: Float = this.bass,
        spatial: Float = this.spatial,
        reverb: Int = this.reverb,
        loudness: Float = this.loudness,
    ): EqSettings = EqSettings(gains, preamp, bass, spatial, reverb, loudness)

    fun toJson(): JSONObject {
        val arr = JSONArray()
        for (g in gains) arr.put(g.toDouble())
        return JSONObject()
            .put("g", arr)
            .put("p", preamp.toDouble())
            .put("b", bass.toDouble())
            .put("s", spatial.toDouble())
            .put("r", reverb)
            .put("l", loudness.toDouble())
    }

    companion object {
        const val BANDS = 10
        const val GAIN_MAX = 12f
        val FREQS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        val FREQ_LABELS = arrayOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
        val REVERB_NAMES = arrayOf("Off", "Room", "Studio", "Club", "Hall", "Arena")

        fun fromJson(o: JSONObject): EqSettings {
            val arr = o.optJSONArray("g")
            val g = FloatArray(BANDS) { i ->
                val v = arr?.optDouble(i, 0.0) ?: 0.0
                v.toFloat().coerceIn(-GAIN_MAX, GAIN_MAX)
            }
            return EqSettings(
                gains = g,
                preamp = o.optDouble("p", 0.0).toFloat().coerceIn(-12f, 6f),
                bass = o.optDouble("b", 0.0).toFloat().coerceIn(0f, 1f),
                spatial = o.optDouble("s", 0.0).toFloat().coerceIn(0f, 1f),
                reverb = o.optInt("r", 0).coerceIn(0, REVERB_NAMES.size - 1),
                loudness = o.optDouble("l", 0.0).toFloat().coerceIn(0f, 8f),
            )
        }
    }
}
