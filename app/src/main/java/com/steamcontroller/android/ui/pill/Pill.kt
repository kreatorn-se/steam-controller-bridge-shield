package com.steamcontroller.android.ui.pill

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import java.util.Locale

/**
 * The small status pill at the top centre of the TV. Callable from any thread.
 *
 * Drawn in a system overlay window ([PillOverlay]) so it shows above GeForce NOW and every other
 * app. The bridge grants itself the overlay permission through the shell-uid service at start
 * (`appops set <app> SYSTEM_ALERT_WINDOW allow`); until that has happened, or if it is ever
 * refused, the text falls back to a plain Toast so no status is lost.
 */
object Pill {
    enum class Kind { CAPTURED, SAVED, FAILED, CONNECTED, DISCONNECTED, RECONNECTING, PROFILE, BATTERY_LOW, INFO }

    private const val TAG = "Pill"

    private val main by lazy { Handler(Looper.getMainLooper()) }

    // Main thread only.
    private var overlay: PillOverlay? = null

    /** [text] is shown in capitals; keep it short ("SAVED TO GAMEHUB", "PROFILE · ELDEN RING"). */
    fun show(context: Context, kind: Kind, text: String) {
        val app = context.applicationContext
        val label = text.uppercase(Locale.ROOT)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showOnMain(app, kind, label)
        } else {
            main.post { showOnMain(app, kind, label) }
        }
    }

    private fun showOnMain(app: Context, kind: Kind, label: String) {
        try {
            if (Settings.canDrawOverlays(app)) {
                val o = overlay ?: PillOverlay(app).also { overlay = it }
                if (o.show(kind, label)) return
            }
        } catch (t: Throwable) {
            Log.w(TAG, "overlay pill failed, falling back to a toast: ${t.message}")
        }
        try {
            Toast.makeText(app, label, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }
}
