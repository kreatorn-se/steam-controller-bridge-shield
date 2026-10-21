package com.steamcontroller.android.ui.pill

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The small status pill at the top centre of the TV. Callable from any thread.
 *
 * Drawn in a system overlay window ([PillOverlay]) so it shows above GeForce NOW and every other
 * app. The bridge grants itself the overlay permission through the shell-uid service at start
 * (`appops set <app> SYSTEM_ALERT_WINDOW allow`); until that has happened, or if it is ever
 * refused, the text falls back to a plain Toast so no status is lost.
 *
 * Captures: [holdForCapture] takes any pill (or toast) off the screen before `screencap` runs and
 * keeps new ones back until [releaseAfterCapture], so the pill can never end up in a screenshot.
 */
object Pill {
    enum class Kind { CAPTURED, SAVED, FAILED, CONNECTED, DISCONNECTED, RECONNECTING, PROFILE, BATTERY_LOW, INFO }

    private const val TAG = "Pill"

    private val main by lazy { Handler(Looper.getMainLooper()) }

    // Main thread only.
    private var overlay: PillOverlay? = null
    private var toast: Toast? = null
    /** Captures in progress; while above 0 nothing is drawn, the latest message waits. */
    private var holds = 0
    /** Safety net: a hold that is never released lapses here (uptime ms). */
    private var holdUntil = 0L
    private var deferred: Pair<Kind, String>? = null

    /** Time for the compositor to drop the removed window before screencap reads the screen. */
    private const val SETTLE_MS = 120L
    /** Longer than any screencap may take (ShellAllowlist: 120 s). */
    private const val HOLD_MAX_MS = 130_000L
    /** How long the capture's own pill keeps the screen before a message held back meanwhile. */
    private const val DEFERRED_AFTER_MS = 1_700L

    /**
     * Hides whatever pill or toast is showing, at once and without animation, and keeps new ones
     * back until [releaseAfterCapture]. Blocks the calling (capture) thread until the pill window
     * is gone plus a short settle, so the very next screencap cannot contain it. Not for the main
     * thread (it would only hide, without the settle).
     */
    fun holdForCapture() {
        val onMain = Looper.myLooper() == Looper.getMainLooper()
        val latch = CountDownLatch(1)
        val hide = Runnable {
            holds++
            holdUntil = SystemClock.uptimeMillis() + HOLD_MAX_MS
            try { overlay?.hideNow() } catch (t: Throwable) { Log.w(TAG, "hide failed: ${t.message}") }
            try { toast?.cancel() } catch (_: Throwable) { }
            toast = null
            latch.countDown()
        }
        if (onMain) { hide.run(); return }
        main.post(hide)
        try {
            latch.await(1_000, TimeUnit.MILLISECONDS)
            Thread.sleep(SETTLE_MS)
        } catch (_: InterruptedException) {
        }
    }

    /**
     * Ends a [holdForCapture]: shows [kind]/[text] if given (the capture's own outcome), then any
     * message that arrived during the capture, after a moment.
     */
    fun releaseAfterCapture(context: Context, kind: Kind? = null, text: String? = null) {
        val app = context.applicationContext
        main.post {
            holds = (holds - 1).coerceAtLeast(0)
            if (holds > 0) {
                // Another capture is still running: its pill waits too.
                if (kind != null && text != null) deferred = kind to text.uppercase(Locale.ROOT)
                return@post
            }
            val waiting = deferred
            deferred = null
            if (kind != null && text != null) {
                showOnMain(app, kind, text.uppercase(Locale.ROOT))
                if (waiting != null) main.postDelayed({ showOnMain(app, waiting.first, waiting.second) }, DEFERRED_AFTER_MS)
            } else if (waiting != null) {
                showOnMain(app, waiting.first, waiting.second)
            }
        }
    }

    private fun held(): Boolean {
        if (holds > 0 && SystemClock.uptimeMillis() > holdUntil) {
            Log.w(TAG, "capture hold lapsed")
            holds = 0
        }
        return holds > 0
    }

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
        if (held()) {
            deferred = kind to label
            return
        }
        try {
            if (Settings.canDrawOverlays(app)) {
                val o = overlay ?: PillOverlay(app).also { overlay = it }
                if (o.show(kind, label)) return
            }
        } catch (t: Throwable) {
            Log.w(TAG, "overlay pill failed, falling back to a toast: ${t.message}")
        }
        try {
            toast = Toast.makeText(app, label, Toast.LENGTH_SHORT).also { it.show() }
        } catch (_: Throwable) {
        }
    }
}
