package com.steamcontroller.android.input

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.steamcontroller.android.hub.CaptureStore
import com.steamcontroller.android.hub.GamehubStore
import com.steamcontroller.android.hub.GamehubSync
import com.steamcontroller.android.service.UsageStatsHelper
import com.steamcontroller.android.ui.pill.Pill
import com.steamcontroller.android.uinput.ShellAllowlist
import java.io.File

/**
 * Non-gamepad actions triggered by mapped Steam Controller buttons.
 * The actual shell command runs in the Shizuku UserService (UID shell, ~2000),
 * passed in as a `runShell` callback so this object stays decoupled from the IPC layer.
 */
object SystemActions {

    private const val TAG = "SystemActions"
    private const val CAPTURED_MAX_WAIT_MS = 450L

    /**
     * Captures the screen with `screencap` straight into the Gamehub upload queue
     * (Android/data/<app>/files/captures) and asks the sync loop to upload it.
     *
     * If the shell user cannot write there, falls back to Pictures/Screenshots and copies the
     * file into the queue from there (needs the storage read permission the installer grants).
     */
    fun takeScreenshot(context: Context, runShell: (Array<String>) -> Int) {
        val app = context.applicationContext
        Thread({
            try {
                val capturedAt = System.currentTimeMillis()
                val id = CaptureStore.newId(capturedAt)
                val queueDir = CaptureStore.dir(app)
                val fg = try { UsageStatsHelper.getCurrentForegroundApp(app) } catch (_: Throwable) { null }

                var ok = false
                if (queueDir != null) {
                    // Write under a temporary name: a 4K PNG takes the Shield 15-30 s to encode
                    // during a stream, and the uploader must never pick up a half-written file.
                    val tmp = File(queueDir, "${CaptureStore.TMP_PREFIX}$id.png")
                    announceCapturedWhenGrabbed(app, tmp)
                    val exit = runShell(arrayOf("screencap", "-p", tmp.path))
                    ok = exit == 0 && CaptureStore.isCompletePng(tmp) && tmp.renameTo(File(queueDir, "$id.png"))
                    if (!ok) {
                        Log.w(TAG, "screencap into queue failed (exit $exit, ${tmp.length()} bytes)")
                        tmp.delete()
                    }
                    // Only fall back to Pictures when the queue directory itself was unwritable;
                    // re-encoding after a slow capture would just double the wait.
                    if (!ok && exit != -1) ok = captureViaPictures(app, runShell, id, queueDir)
                }
                if (!ok) {
                    Pill.show(app, Pill.Kind.FAILED, "CAPTURE FAILED")
                    return@Thread
                }
                CaptureStore.writeMeta(app, id, capturedAt, fg)
                if (GamehubStore.isLinked(app)) {
                    // GamehubSync shows SAVED TO GAMEHUB once the upload has gone through.
                    GamehubSync.kick(app)
                } else {
                    Pill.show(app, Pill.Kind.SAVED, "SAVED")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Screenshot threw: ${t.message}")
                Pill.show(app, Pill.Kind.FAILED, "CAPTURE FAILED")
            }
        }, "screencap").apply { isDaemon = true; start() }
    }

    /**
     * CAPTURED as soon as screencap has the frame. screencap grabs the display within ~150 ms of
     * starting and then spends 15-30 s encoding the PNG; showing the pill at the press itself
     * would put the pill in the screenshot. Bytes in the file prove the grab is done (on builds
     * that stream the encode); otherwise the pill follows after a short, still-instant-feeling
     * delay that clears the grab.
     */
    private fun announceCapturedWhenGrabbed(app: Context, file: File) {
        Thread({
            val deadline = SystemClock.uptimeMillis() + CAPTURED_MAX_WAIT_MS
            try {
                while (SystemClock.uptimeMillis() < deadline && file.length() <= 0L) Thread.sleep(25)
            } catch (_: InterruptedException) {
            }
            Pill.show(app, Pill.Kind.CAPTURED, "CAPTURED")
        }, "screencap-pill").apply { isDaemon = true; start() }
    }

    private fun captureViaPictures(context: Context, runShell: (Array<String>) -> Int, id: String, queueDir: File): Boolean {
        val dir = ShellAllowlist.SCREENSHOT_DIR
        val path = "$dir/$id.png"
        runShell(arrayOf("mkdir", "-p", dir))
        if (runShell(arrayOf("screencap", "-p", path)) != 0) return false
        return try {
            File(path).copyTo(File(queueDir, "$id.png"), overwrite = true)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "copy from Pictures failed: ${t.message}")
            Pill.show(context, Pill.Kind.FAILED, "SAVED TO PICTURES · NO UPLOAD ACCESS")
            false
        }
    }
}
