package com.steamcontroller.android.input

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.steamcontroller.android.hub.CaptureLevels
import com.steamcontroller.android.hub.CaptureStore
import com.steamcontroller.android.hub.GamehubStore
import com.steamcontroller.android.hub.GamehubSync
import com.steamcontroller.android.hub.RawFrame
import com.steamcontroller.android.service.Diagnostics
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

    /**
     * Captures the screen with `screencap` straight into the Gamehub upload queue
     * (Android/data/<app>/files/captures) and asks the sync loop to upload it.
     *
     * If the shell user cannot write there, falls back to Pictures/Screenshots and copies the
     * file into the queue from there (needs the storage read permission the installer grants).
     *
     * Either way the finished frame waits in the queue as `<id>.raw` while [CaptureLevels]
     * processes it (levels, colour boost) on its own background thread; only then does it become
     * `<id>.png` for the uploader.
     *
     * The pill is never in a capture: any pill on screen is taken down before screencap starts and
     * none is drawn until screencap has exited ([Pill.holdForCapture]). CAPTURED then appears
     * once the frame is complete on disk, straight after screencap returns: about a second with
     * the raw framebuffer (`screencap <file>.raw`, no PNG encode; [RawFrame]), the PNG encode
     * happening afterwards in [CaptureLevels].
     */
    fun takeScreenshot(context: Context, runShell: (Array<String>) -> Int) {
        val app = context.applicationContext
        Thread({
            var holding = false
            var released = false
            fun release(kind: Pill.Kind?, text: String?) {
                if (released) return
                released = true
                if (holding) Pill.releaseAfterCapture(app, kind, text)
                else if (kind != null && text != null) Pill.show(app, kind, text)
            }
            try {
                val capturedAt = System.currentTimeMillis()
                val id = CaptureStore.newId(capturedAt)
                val queueDir = CaptureStore.dir(app)
                val fg = try { UsageStatsHelper.getCurrentForegroundApp(app) } catch (_: Throwable) { null }

                Pill.holdForCapture()
                holding = true
                val shotStart = SystemClock.elapsedRealtime()
                var raw: File? = null
                var pictures: File? = null
                var failText = "CAPTURE FAILED"
                if (queueDir != null) {
                    val stash = File(queueDir, "$id${CaptureStore.RAW_SUFFIX}")
                    // PNG into a temporary name, then renamed: a 4K PNG takes the Shield 10-30 s to
                    // encode during a stream, and nothing may pick up a half-written file.
                    fun screencapPng(): Int {
                        val tmp = File(queueDir, "${CaptureStore.TMP_PREFIX}$id.png")
                        val exit = runShell(arrayOf("screencap", "-p", tmp.path))
                        if (exit == 0 && CaptureStore.isCompletePng(tmp) && tmp.renameTo(stash)) raw = stash
                        else {
                            Log.w(TAG, "screencap -p into queue failed (exit $exit, ${tmp.length()} bytes)")
                            tmp.delete()
                        }
                        return exit
                    }
                    var exit: Int
                    if (CaptureStore.rawBacklog(queueDir) < CaptureStore.MAX_RAW_BACKLOG) {
                        // The raw framebuffer: no encode, about a second for a 4K frame. Straight
                        // to <id>.raw (the orphan pass leaves files younger than a minute alone).
                        exit = runShell(arrayOf("screencap", stash.path))
                        val check = if (exit == 0) RawFrame.check(stash) else null
                        if (check is RawFrame.Check.Ok) {
                            raw = stash
                        } else {
                            val why = (check as? RawFrame.Check.Bad)?.reason ?: "exit $exit"
                            Log.w(TAG, "raw screencap unusable ($why)")
                            Diagnostics.record(TAG, "capture $id raw screencap unusable ($why)" + if (exit == 0) " -> PNG" else "")
                            stash.delete()
                            // A frame we cannot read (another pixel format): the PNG way instead.
                            if (exit == 0) exit = screencapPng()
                        }
                    } else {
                        Diagnostics.record(TAG, "capture $id: ${CaptureStore.MAX_RAW_BACKLOG}+ raw frames waiting -> PNG")
                        exit = screencapPng()
                    }
                    // Only fall back to Pictures when the queue directory itself was unwritable;
                    // re-encoding after a slow capture would just double the wait.
                    if (raw == null && exit != -1) {
                        val p = captureViaPictures(runShell, id, queueDir)
                        pictures = p.file
                        if (pictures != null) raw = stash
                        p.failText?.let { failText = it }
                    }
                }
                val screencapMs = SystemClock.elapsedRealtime() - shotStart
                val rawFile = raw
                if (rawFile == null || queueDir == null) {
                    release(Pill.Kind.FAILED, failText)
                    return@Thread
                }
                CaptureStore.writeMeta(app, id, capturedAt, fg)
                val linked = GamehubStore.isLinked(app)
                // screencap has exited and its frame is complete on disk: now, and only now, the pill.
                // Unlinked, the capture is stored for good already (processing only changes pixels);
                // linked, GamehubSync shows SAVED TO GAMEHUB once the upload has gone through.
                if (linked) release(Pill.Kind.CAPTURED, "CAPTURED") else release(Pill.Kind.SAVED, "CAPTURED · SAVED")
                CaptureLevels.submit(queueDir, id, rawFile, pictures, GamehubStore.captureSaturation(app), screencapMs) { published ->
                    if (!published) {
                        Pill.show(app, Pill.Kind.FAILED, "CAPTURE FAILED")
                    } else if (linked) {
                        GamehubSync.kick(app)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Screenshot threw: ${t.message}")
                release(Pill.Kind.FAILED, "CAPTURE FAILED")
            } finally {
                release(null, null)
            }
        }, "screencap").apply { isDaemon = true; start() }
    }

    private class PicturesResult(val file: File?, val failText: String? = null)

    /**
     * screencap into Pictures/Screenshots, then a copy into the queue as `<id>.raw` (via a temp
     * name, so the orphan pass never sees a half-copied file).
     */
    private fun captureViaPictures(runShell: (Array<String>) -> Int, id: String, queueDir: File): PicturesResult {
        val dir = ShellAllowlist.SCREENSHOT_DIR
        val path = "$dir/$id.png"
        runShell(arrayOf("mkdir", "-p", dir))
        if (runShell(arrayOf("screencap", "-p", path)) != 0) return PicturesResult(null)
        val tmp = File(queueDir, "${CaptureStore.TMP_PREFIX}$id.png")
        return try {
            File(path).copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(File(queueDir, "$id${CaptureStore.RAW_SUFFIX}"))) throw java.io.IOException("rename failed")
            PicturesResult(File(path))
        } catch (t: Throwable) {
            Log.w(TAG, "copy from Pictures failed: ${t.message}")
            tmp.delete()
            PicturesResult(null, "SAVED TO PICTURES · NO UPLOAD ACCESS")
        }
    }
}
