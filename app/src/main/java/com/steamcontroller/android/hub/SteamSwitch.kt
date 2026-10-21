package com.steamcontroller.android.hub

import android.content.Context
import android.util.Log
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.R
import com.steamcontroller.android.input.NamedProfile
import com.steamcontroller.android.service.ControllerService
import com.steamcontroller.android.ui.pill.Pill
import com.steamcontroller.android.ui.tv.ImportStatus

/**
 * Profiles that follow Steam (replaces "bind to app", which cannot work inside GeForce NOW):
 * while the controller service runs and the Shield is linked, Gamehub's `GET /api/device/v1/now`
 * is polled. When the game changes to one a profile is bound to (NamedProfile.steamAppIds), that
 * profile is applied through the normal named-profile path. Just before the first switch, every
 * live setting is snapshotted (a hidden profile in these prefs), so when the game ends exactly
 * what was live comes back - unsaved tweaks, or no named profile at all, included - unless Kent
 * picked another profile by hand during that game.
 *
 * State is kept in preferences so a service restart in the middle of a game loses nothing.
 */
object SteamSwitch {
    private const val TAG = "SteamSwitch"
    private const val PREFS = "steam_switch"
    private const val K_APP = "appid"        // last appid seen (0 = none)
    private const val K_AUTO = "auto"        // profile id we applied for it
    private const val K_BEFORE = "before"    // profile id active before that ("" = none)
    private const val K_SNAP = "snapshot"    // live settings before the switch (NamedProfile JSON)

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * One poll (background thread). Returns how long to wait before the next one, in ms. The only
     * `/now` poll in the app: it also feeds the status strip's import chip ([ImportStatus]), so it
     * runs while the bridge runs (profiles) or while the app is in front (the chip).
     */
    fun tick(c: Context): Long {
        val profiles = ControllerService.isRunning
        if (!profiles && !ImportStatus.active) return 30_000L
        if (!GamehubStore.isLinked(c)) { ImportStatus.post(null); return 30_000L }
        if (GamehubTv.Missing.has(c, "now")) return 30_000L
        val r = GamehubTv.nowStatus(c)
        if (r is GamehubTv.Res.Err) {
            if (r.fail == GamehubTv.Fail.NOT_DEPLOYED) GamehubTv.Missing.mark(c, "now")
            if (r.fail == GamehubTv.Fail.NOT_LINKED || r.fail == GamehubTv.Fail.UNAUTHORIZED) ImportStatus.post(null)
            return 30_000L
        }
        val st = (r as GamehubTv.Res.Ok).value
        ImportStatus.post(st.importing)
        if (!profiles) return 30_000L
        apply(c, st.now?.appid ?: 0L)
        return if (gfnInFront(c)) 10_000L else 30_000L
    }

    @Synchronized
    private fun apply(c: Context, appid: Long) {
        val prefs = p(c)
        val last = prefs.getLong(K_APP, 0L)
        if (appid == last) return
        prefs.edit().putLong(K_APP, appid).apply()
        val profiles = Prefs.listNamedProfiles(c)
        val active = Prefs.getActiveNamedProfileId(c)
        val auto = prefs.getString(K_AUTO, null)
        // Chosen by hand during the game: leave everything as Kent set it.
        val manual = auto != null && active != auto
        val bound = if (appid > 0) profiles.firstOrNull { appid in it.steamAppIds } else null
        when {
            bound != null -> {
                if (auto == null || manual) {
                    val snap = Prefs.captureCurrentAsProfile(c, "steam-switch-snapshot")
                    prefs.edit().putString(K_BEFORE, active ?: "").putString(K_SNAP, snap.toJson().toString()).apply()
                }
                if (active != bound.id) switchTo(c, bound)
                prefs.edit().putString(K_AUTO, bound.id).apply()
                Diag.note("steam switch: app $appid -> profile")
            }
            auto != null -> {
                // The bound game ended (or another, unbound one started): back to before.
                val before = prefs.getString(K_BEFORE, "") ?: ""
                val snap = prefs.getString(K_SNAP, null)?.let { runCatching { NamedProfile.fromJson(org.json.JSONObject(it)) }.getOrNull() }
                val restored = when {
                    manual -> false
                    snap != null -> { restore(c, snap, profiles.firstOrNull { it.id == before }); true }
                    before.isNotEmpty() -> profiles.firstOrNull { it.id == before }?.let { switchTo(c, it); true } ?: false
                    else -> false
                }
                prefs.edit().remove(K_AUTO).remove(K_BEFORE).remove(K_SNAP).apply()
                Diag.note("steam switch: game ended, restored=$restored")
            }
        }
    }

    /** Puts back the live settings from before the switch, and which profile was active then. */
    private fun restore(c: Context, snap: NamedProfile, beforeProfile: NamedProfile?) {
        Log.i(TAG, "restoring the settings from before the Steam game")
        Prefs.applyNamedProfile(c, snap)
        Prefs.setActiveNamedProfileId(c, beforeProfile?.id)
        ControllerService.notifyNamedProfileApplied(c)
        Pill.show(c, Pill.Kind.PROFILE, beforeProfile?.let { c.getString(R.string.prof_pill, it.name) } ?: c.getString(R.string.steam_restored))
    }

    private fun switchTo(c: Context, profile: NamedProfile) {
        Log.i(TAG, "applying '${profile.name}' for the Steam game")
        Prefs.applyNamedProfile(c, profile)
        ControllerService.notifyNamedProfileApplied(c)
        Pill.show(c, Pill.Kind.PROFILE, c.getString(R.string.prof_pill, profile.name))
    }

    /** GeForce NOW in front, read cheaply when usage access is granted; otherwise assume not. */
    private fun gfnInFront(c: Context): Boolean = try {
        com.steamcontroller.android.service.UsageStatsHelper.hasPermission(c) &&
            com.steamcontroller.android.service.UsageStatsHelper.getCurrentForegroundApp(c) == "com.nvidia.geforcenow"
    } catch (_: Throwable) {
        false
    }
}
