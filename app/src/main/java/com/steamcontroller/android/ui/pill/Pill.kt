package com.steamcontroller.android.ui.pill

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * The small status pill at the top centre of the TV. Callable from any thread.
 * Contract only: the runtime workstream replaces the Toast fallback with the animated overlay.
 */
object Pill {
    enum class Kind { CAPTURED, SAVED, FAILED, CONNECTED, DISCONNECTED, RECONNECTING, PROFILE, BATTERY_LOW, INFO }

    /** [text] is shown in capitals; keep it short ("SAVED TO GAMEHUB", "PROFILE · ELDEN RING"). */
    fun show(context: Context, kind: Kind, text: String) {
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).post { Toast.makeText(app, text, Toast.LENGTH_SHORT).show() }
    }
}
