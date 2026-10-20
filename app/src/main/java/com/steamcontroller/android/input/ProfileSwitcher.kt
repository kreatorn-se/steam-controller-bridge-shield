package com.steamcontroller.android.input

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.service.ControllerService
import com.steamcontroller.android.ui.pill.Pill
import java.util.concurrent.Executors

/**
 * Steps through the saved Game Profiles (named profiles) in their stored order.
 *
 * Used by the controller chord (action-layer button held + D-pad left/right, see UInputGamepad)
 * and by the UI. The prefs work runs on its own thread so neither the input path nor the main
 * thread ever waits for it; the controller service is then told to live-swap the virtual device
 * if the new profile emulates something else (gamepad vs desktop).
 */
object ProfileSwitcher {
    private const val TAG = "ProfileSwitcher"

    private val worker =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "profile-switch").apply { isDaemon = true }
        }

    /** Apply the profile [delta] steps from the active one (wrapping). Returns immediately. */
    fun cycle(
        context: Context,
        delta: Int,
    ) {
        val app = context.applicationContext
        worker.execute {
            try {
                cycleNow(app, delta)
            } catch (t: Throwable) {
                Log.w(TAG, "profile cycle failed: ${t.message}")
            }
        }
    }

    /** Synchronous version for callers already on a background thread; null if none saved. */
    @WorkerThread
    fun cycleNow(
        context: Context,
        delta: Int,
    ): NamedProfile? {
        val profiles = Prefs.listNamedProfiles(context)
        if (profiles.isEmpty()) {
            Pill.show(context, Pill.Kind.INFO, "NO SAVED PROFILES")
            return null
        }
        val activeId = Prefs.getActiveNamedProfileId(context)
        val current = profiles.indexOfFirst { it.id == activeId }
        val index =
            if (current < 0) {
                if (delta >= 0) 0 else profiles.size - 1
            } else {
                Math.floorMod(current + delta, profiles.size)
            }
        val next = profiles[index]
        Prefs.applyNamedProfile(context, next)
        Log.i(TAG, "profile → ${next.name} (delta $delta)")
        Pill.show(context, Pill.Kind.PROFILE, "PROFILE · ${next.name}")
        ControllerService.notifyNamedProfileApplied(context)
        return next
    }
}
