package com.steamcontroller.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.steamcontroller.android.Prefs

/**
 * Woken by the shell-uid UInputService when this app's process was killed while the virtual
 * pad is still live (the Shield's low-memory killer does this when a GFN stream starts).
 * Restarts the controller service within a second or two instead of Android's 10-30 s retry;
 * the service re-attaches to the devices that were kept alive, so games never lose the pad.
 *
 * Exported because the shell uid can only reach exported components. Anyone sending it can at
 * most start the controller service, which this app runs anyway; a deliberate stop is honoured.
 */
class ReviveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        if (ControllerService.isRunning) return
        if (Prefs.getUserStoppedService(context)) {
            Log.i(TAG, "revive ignored: the service was stopped on purpose")
            return
        }
        try {
            context.startForegroundService(
                Intent(context, ControllerService::class.java)
                    // The pad never went away; don't announce the link as if it were new.
                    .putExtra(ControllerService.EXTRA_REVIVED, true),
            )
            Log.i(TAG, "revived the controller service")
        } catch (t: Throwable) {
            Log.w(TAG, "revive failed: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "ReviveReceiver"
        const val ACTION = "com.steamcontroller.android.action.REVIVE"
    }
}
