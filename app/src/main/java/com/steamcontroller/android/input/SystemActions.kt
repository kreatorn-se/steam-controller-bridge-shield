package com.steamcontroller.android.input

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.steamcontroller.android.hub.CaptureLevels
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
     *
     * Either way the finished frame waits in the queue as `<id>.raw` while [CaptureLevels]
     * expands GeForce NOW's limited-range video to full range on its own background thread;
     * only then does it become `<id>.png` for the uploader. The CAPTURED pill is independent of
     * all this and still fires as soon as screencap has the frame.
     */
    fun takeScreenshot(context: Context, runShell: (Array<String>) -> Int) {
        val app = context.applicationContext
        Thread({
            try {
                val capturedAt = System.currentTimeMillis()
                val id = CaptureStore.newId(capturedAt)
                val queueDir = CaptureStore.dir(app)
                val fg = try { UsageStatsHelper.getCurrentForegroundApp(app) } catch (_: Throwable) { null }

                var raw: File? = null
                var pictures: File? = null
                if (queueDir != null) {
                    // Write under a temporary name: a 4K PNG takes the Shield 15-30 s to encode
                    // during a stream, and the uploader must never pick up a half-written file.
                    val tmp = File(queueDir, "${CaptureStore.TMP_PREFIX}$id.png")
                    val stash = File(queueDir, "$id${CaptureStore.RAW_SUFFIX}")
                    announceCapturedWhenGrabbed(app, tmp)
                    val exit = runShell(arrayOf("screencap", "-p", tmp.path))
                    if (exit == 0 && CaptureStore.isCompletePng(tmp) && tmp.renameTo(stash)) {
                        raw = stash
                    } else {
                        Log.w(TAG, "screencap into queue failed (exit $exit, ${tmp.length()} bytes)")
                        tmp.delete()
                        // Only fall back to Pictures when the queue directory itself was unwritable;
                        // re-encoding after a slow capture would just double the wait.
                        if (exit != -1) {
                            pictures = captureViaPictures(app, runShell, id, queueDir)
                            if (pictures != null) raw = stash
                        }
                    }
                }
                if (raw == null || queueDir == null) {
                    Pill.show(app, Pill.Kind.FAILED, "CAPTURE FAILED")
                    return@Thread
                }
                CaptureStore.writeMeta(app, id, capturedAt, fg)
                val linked = GamehubStore.isLinked(app)
                // Unlinked, the capture is safely stored now; the level fix only changes its pixels.
                if (!linked) Pill.show(app, Pill.Kind.SAVED, "SAVED")
                CaptureLevels.submit(queueDir, id, raw, pictures) { published ->
                    if (!published) {
                        Pill.show(app, Pill.Kind.FAILED, "CAPTURE FAILED")
                    } else if (linked) {
                        // GamehubSync shows SAVED TO GAMEHUB once the upload has gone through.
                        GamehubSync.kick(app)
                    }
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

    /**
     * screencap into Pictures/Screenshots, then a copy into the queue as `<id>.raw` (via a temp
     * name, so the orphan pass never sees a half-copied file). Returns the Pictures file, or null.
     */
    private fun captureViaPictures(context: Context, runShell: (Array<String>) -> Int, id: String, queueDir: File): File? {
        val dir = ShellAllowlist.SCREENSHOT_DIR
        val path = "$dir/$id.png"
        runShell(arrayOf("mkdir", "-p", dir))
        if (runShell(arrayOf("screencap", "-p", path)) != 0) return null
        val tmp = File(queueDir, "${CaptureStore.TMP_PREFIX}$id.png")
        return try {
            File(path).copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(File(queueDir, "$id${CaptureStore.RAW_SUFFIX}"))) throw java.io.IOException("rename failed")
            File(path)
        } catch (t: Throwable) {
            Log.w(TAG, "copy from Pictures failed: ${t.message}")
            tmp.delete()
            Pill.show(context, Pill.Kind.FAILED, "SAVED TO PICTURES · NO UPLOAD ACCESS")
            null
        }
    }
}
