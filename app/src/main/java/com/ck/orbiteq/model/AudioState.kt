package com.ck.orbiteq.model

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Single source of truth for the current sound. Both the system-wide
 * service and the 8D player read from here. Call setters on the main thread.
 */
object AudioState {

    class EightD(
        val enabled: Boolean = false,
        val speedHz: Float = 0.12f,  // circles per second
        val depth: Float = 0.85f,    // 0..1
    )

    @Volatile var eq: EqSettings = EqSettings()
        private set

    @Volatile var eightD: EightD = EightD()
        private set

    /** Bumped on every change so the audio thread knows to reconfigure. */
    @Volatile var version: Int = 0
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("orbit_eq", Context.MODE_PRIVATE)
        prefs = p
        try {
            val raw = p.getString("eq", null)
            if (raw != null) eq = EqSettings.fromJson(JSONObject(raw))
        } catch (_: Exception) {
        }
        eightD = EightD(
            p.getBoolean("8d_on", false),
            p.getFloat("8d_speed", 0.12f).coerceIn(0.03f, 0.4f),
            p.getFloat("8d_depth", 0.85f).coerceIn(0f, 1f),
        )
        version++
    }

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    fun setEq(s: EqSettings, persist: Boolean = true) {
        eq = s
        version++
        if (persist) prefs?.edit()?.putString("eq", s.toJson().toString())?.apply()
        notifyListeners()
    }

    fun setEightD(e: EightD) {
        eightD = e
        version++
        prefs?.edit()
            ?.putBoolean("8d_on", e.enabled)
            ?.putFloat("8d_speed", e.speedHz)
            ?.putFloat("8d_depth", e.depth)
            ?.apply()
        notifyListeners()
    }

    private fun notifyListeners() {
        for (l in listeners) l()
    }

    // ---------- saved profiles ----------

    private fun profilesJson(): JSONObject = try {
        JSONObject(prefs?.getString("profiles", null) ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    fun customProfiles(): List<Profile> {
        val o = profilesJson()
        val out = ArrayList<Profile>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val obj = o.optJSONObject(k) ?: continue
            out.add(Profile(k, EqSettings.fromJson(obj), ProfileKind.CUSTOM))
        }
        out.sortBy { it.name.lowercase() }
        return out
    }

    fun saveProfile(name: String, s: EqSettings) {
        val o = profilesJson()
        o.put(name, s.toJson())
        prefs?.edit()?.putString("profiles", o.toString())?.apply()
    }

    fun deleteProfile(name: String) {
        val o = profilesJson()
        o.remove(name)
        val links = linksJson()
        val keys = links.keys().asSequence().toList()
        for (k in keys) if (links.optString(k) == name) links.remove(k)
        prefs?.edit()
            ?.putString("profiles", o.toString())
            ?.putString("links", links.toString())
            ?.apply()
    }

    fun findProfile(name: String): Profile? =
        customProfiles().firstOrNull { it.name == name }
            ?: Presets.headphones.firstOrNull { it.name == name }
            ?: Presets.sound.firstOrNull { it.name == name }

    // ---------- headphone -> profile links ----------

    private fun linksJson(): JSONObject = try {
        JSONObject(prefs?.getString("links", null) ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    fun deviceLinks(): Map<String, String> {
        val o = linksJson()
        val map = LinkedHashMap<String, String>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = o.optString(k)
        }
        return map
    }

    fun linkDevice(deviceKey: String, profileName: String) {
        val o = linksJson()
        o.put(deviceKey, profileName)
        prefs?.edit()?.putString("links", o.toString())?.apply()
    }

    fun profileForDevice(deviceKey: String): Profile? =
        deviceLinks()[deviceKey]?.let { findProfile(it) }
}
