package com.steamcontroller.android.hub

import android.content.Context
import android.util.Log
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.R
import com.steamcontroller.android.input.NamedProfile
import com.steamcontroller.android.service.ControllerService
import com.steamcontroller.android.ui.pill.Pill

/**
 * Profiles that follow Steam (replaces "bind to app", which cannot work inside GeForce NOW):
 * while the controller service runs and the Shield is linked, Gamehub's `GET /api/device/v1/now`
 * is polled. When the game changes to one a profile is bound to (NamedProfile.steamAppIds), that
 * profile is applied through the normal named-profile path; when the game ends, the profile from
 * before the switch comes back - unless Kent picked another one by hand during that game.
 *
 * State is kept in preferences so a service restart in the middle of a game loses nothing.
 */
object SteamSwitch {
    private const val TAG = "SteamSwitch"
    private const val PREFS = "steam_switch"
    private const val K_APP = "appid"        // last appid seen (0 = none)
    private const val K_AUTO = "auto"        // profile id we applied for it
    private const val K_BEFORE = "before"    // profile id active before that ("" = none)

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** One poll (background thread). Returns how long to wait before the next one, in ms. */
    fun tick(c: Context): Long {
        if (!ControllerService.isRunning || !GamehubStore.isLinked(c) || GamehubTv.Missing.has(c, "now")) return 30_000L
        val r = GamehubTv.now(c)
        if (r is GamehubTv.Res.Err) {
            if (r.fail == GamehubTv.Fail.NOT_DEPLOYED) GamehubTv.Missing.mark(c, "now")
            return 30_000L
        }
        val now = (r as GamehubTv.Res.Ok).value
        apply(c, now?.appid ?: 0L)
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
                if (auto == null || manual) prefs.edit().putString(K_BEFORE, active ?: "").apply()
                if (active != bound.id) switchTo(c, bound)
                prefs.edit().putString(K_AUTO, bound.id).apply()
                Diag.note("steam switch: app $appid -> profile")
            }
            auto != null -> {
                // The bound game ended (or another, unbound one started): back to before.
                val before = prefs.getString(K_BEFORE, "") ?: ""
                if (!manual && before.isNotEmpty()) profiles.firstOrNull { it.id == before }?.let { switchTo(c, it) }
                prefs.edit().remove(K_AUTO).remove(K_BEFORE).apply()
                Diag.note("steam switch: game ended, restored=${!manual && before.isNotEmpty()}")
            }
        }
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
