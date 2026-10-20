package com.steamcontroller.android.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.steamcontroller.android.MainActivity
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.service.ControllerService

/**
 * After a self-update (Updater wrote the flag before `pm install`), Android sends the new
 * version MY_PACKAGE_REPLACED: start the controller service again if it was running, and bring
 * the app back to the screen. The shell-uid service's watchdog also revives the service
 * (ReviveReceiver); whichever comes first wins, the other sees it running. A replace from the
 * PC or the Play-style installer (no flag) is left alone.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val (ours, serviceWasRunning) = Updater.consumePending(context)
        if (!ours) return
        Log.i(TAG, "updated in place; service was running: $serviceWasRunning")
        if (serviceWasRunning && !ControllerService.isRunning && !Prefs.getUserStoppedService(context)) {
            try {
                context.startForegroundService(
                    Intent(context, ControllerService::class.java).putExtra(ControllerService.EXTRA_REVIVED, true),
                )
            } catch (t: Throwable) {
                Log.w(TAG, "service restart failed: ${t.message}")
            }
        }
        // The overlay permission the app holds lets it start an activity from the background.
        try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            Log.w(TAG, "relaunch failed: ${t.message}")
        }
    }

    private companion object {
        const val TAG = "UpdateReceiver"
    }
}
