package com.steamcontroller.android.input

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.steamcontroller.android.hub.CaptureStore
import com.steamcontroller.android.hub.GamehubStore
import com.steamcontroller.android.hub.GamehubSync
import com.steamcontroller.android.service.UsageStatsHelper
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
     */
    fun takeScreenshot(context: Context, runShell: (Array<String>) -> Int) {
        Thread({
            try {
                val capturedAt = System.currentTimeMillis()
                val id = CaptureStore.newId(capturedAt)
                val queueDir = CaptureStore.dir(context)
                val fg = try { UsageStatsHelper.getCurrentForegroundApp(context) } catch (_: Throwable) { null }

                var ok = false
                if (queueDir != null) {
                    val target = File(queueDir, "$id.png")
                    val exit = runShell(arrayOf("screencap", "-p", target.path))
                    ok = exit == 0 && target.length() > 0
                    if (!ok) {
                        Log.w(TAG, "screencap into queue failed (exit $exit); trying Pictures fallback")
                        target.delete()
                    }
                }
                if (!ok) {
                    val dir = ShellAllowlist.SCREENSHOT_DIR
                    val path = "$dir/$id.png"
                    runShell(arrayOf("mkdir", "-p", dir))
                    val exit = runShell(arrayOf("screencap", "-p", path))
                    if (exit == 0 && queueDir != null) {
                        ok = try {
                            File(path).copyTo(File(queueDir, "$id.png"), overwrite = true)
                            true
                        } catch (t: Throwable) {
                            Log.w(TAG, "copy from Pictures failed: ${t.message}")
                            false
                        }
                    } else if (exit == 0) {
                        // Saved locally even though there is no queue directory.
                        say(context, "Screenshot saved to Pictures")
                        return@Thread
                    }
                }

                if (!ok) {
                    say(context, "Screenshot failed")
                    return@Thread
                }
                CaptureStore.writeMeta(context, id, capturedAt, fg)
                if (GamehubStore.isLinked(context)) {
                    say(context, "Captured · uploading to Gamehub")
                    GamehubSync.kick(context)
                } else {
                    say(context, "Captured · link Gamehub to upload")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Screenshot threw: ${t.message}")
            }
        }, "screencap").apply { isDaemon = true; start() }
    }

    private fun say(context: Context, msg: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }
}
