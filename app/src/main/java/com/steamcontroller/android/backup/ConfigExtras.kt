package com.steamcontroller.android.backup

import android.content.Context
import org.json.JSONObject

/**
 * Keys of the synced config this version does not know, kept verbatim so a newer hub's settings
 * survive the Shield's round trip (import → export → sync) instead of being dropped. Covers the
 * top level and `liveSettings` (contract radial-v1.1 amendment 3: e.g. `liveSettings.radial`, the
 * default radial menus); named profiles keep theirs in NamedProfile.extrasJson.
 */
object ConfigExtras {
    val ROOT_KNOWN = setOf("schemaVersion", "exportedAt", "liveSettings", "namedProfiles", "activeNamedProfileId")
    val LIVE_KNOWN = setOf(
        "gamepadProfileId", "leftCal", "rightCal", "rumbleIntensity", "mouseSensitivity", "trackpadAsMouse",
        "lOuter", "rOuter", "lCurve", "rCurve", "padAccel", "gyro", "mapping", "desktopMapping", "actionLayer",
    )

    private const val PREFS = "config_extras"
    private const val K_ROOT = "root"
    private const val K_LIVE = "live"

    /** The keys of [obj] not in [known], as a JSON object string; null when there are none. */
    fun unknown(obj: JSONObject, known: Set<String>): String? {
        val x = JSONObject()
        obj.keys().forEach { k -> if (k !in known) x.put(k, obj.get(k)) }
        return if (x.length() == 0) null else x.toString()
    }

    /** Adds the saved [extras] to [target] without overwriting anything [target] already has. */
    fun merge(target: JSONObject, extras: String?) {
        if (extras.isNullOrBlank()) return
        try {
            val x = JSONObject(extras)
            x.keys().forEach { k -> if (!target.has(k)) target.put(k, x.get(k)) }
        } catch (_: Exception) {
        }
    }

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun root(c: Context): String? = p(c).getString(K_ROOT, null)
    fun live(c: Context): String? = p(c).getString(K_LIVE, null)

    /** An imported config replaces what was kept (each level is a full snapshot). */
    fun save(c: Context, root: String?, live: String?, hasLive: Boolean) {
        val e = p(c).edit()
        if (root == null) e.remove(K_ROOT) else e.putString(K_ROOT, root)
        if (hasLive) { if (live == null) e.remove(K_LIVE) else e.putString(K_LIVE, live) }
        e.commit()
    }

    /** `liveSettings.radial`: the default radial menus (null when none). */
    fun defaultRadial(c: Context): String? = try {
        live(c)?.let { JSONObject(it).optJSONObject("radial")?.toString() }
    } catch (_: Exception) {
        null
    }
}
