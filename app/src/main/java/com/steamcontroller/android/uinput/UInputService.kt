package com.steamcontroller.android.uinput

import android.content.Context
import android.hardware.usb.UsbDevice
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.steamcontroller.android.BuildConfig
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

// Bound by Shizuku.bindUserService() - this code runs in a separate process with the
// shell UID (2000), which is what gives access to the virtual-output device nodes.
//
// IMPORTANT: must have a no-arg constructor. Shizuku v13+ also tries the Context
// constructor first; either is acceptable.
class UInputService : IUInputService.Stub {
    companion object {
        private const val TAG = "UInputService"

        /** How often the liveness watchdog checks for client silence. */
        private const val WATCHDOG_TICK_MS = 500L

        /**
         * The app polls at 50 Hz while its service runs, so this much silence means Android has
         * killed it (on the Shield: the low-memory killer when a GFN stream starts). We keep the
         * virtual devices alive - games never see the controller unplug - and revive the app.
         */
        private val REVIVE_AT_MS = longArrayOf(1_500L, 6_000L, 15_000L, 35_000L, 60_000L)

        /** Give up after this much silence: the app is gone for good (force-stopped, uninstalled). */
        private const val CLIENT_IDLE_TIMEOUT_MS = 90_000L

        private const val REVIVE_ACTION = "com.steamcontroller.android.action.REVIVE"

        /** Valve: the only vendor whose devices [grantUsbPermission] will grant. */
        private const val VALVE_VID = 0x28DE
        private const val REVIVE_RECEIVER = "com.steamcontroller.android.service.ReviveReceiver"
    }

    @Suppress("unused")
    constructor() : super() {
        Log.i(TAG, "UInputService instantiated (no-arg)")
    }

    @Suppress("unused")
    constructor(context: Context?) : super() {
        Log.i(TAG, "UInputService instantiated (Context=$context)")
        // Shizuku hands us a context of the app's own package: its uid is the one client we serve.
        appUid = try { context?.applicationInfo?.uid ?: -1 } catch (_: Throwable) { -1 }
    }

    /** The app's uid when Shizuku gave us its context, else -1 (then checked by package name). */
    private var appUid = -1

    // ── Client liveness watchdog ───────────────────────────────────────────────
    //
    // This process runs as the shell uid and holds the uhid devices. If the app is killed
    // abruptly (an adb install does exactly this) nothing calls destroy(), so the process would
    // survive as an orphan with its devices still registered - which is how duplicate gamepads
    // accumulate and how RetroArch ends up bound to a pad whose owner no longer exists.
    //
    // The app polls us continuously while it is alive (the rumble poll runs at 50 Hz), so silence
    // for a while means the client is gone and we should exit. The app recovers: it notices the
    // binder death and re-binds on the next start.
    private val lastCallAt = AtomicLong(SystemClock.uptimeMillis())

    // Starts after lastCallAt is initialised (property and init order is declaration order), and
    // covers both constructors: Shizuku may use either.
    init {
        startLivenessWatchdog()
    }

    private fun startLivenessWatchdog() {
        val thread =
            Thread(
                {
                    var revivesSent = 0
                    while (true) {
                        try {
                            Thread.sleep(WATCHDOG_TICK_MS)
                            val idle = SystemClock.uptimeMillis() - lastCallAt.get()
                            if (idle < REVIVE_AT_MS[0]) {
                                revivesSent = 0
                                continue
                            }
                            if (revivesSent < REVIVE_AT_MS.size && idle >= REVIVE_AT_MS[revivesSent]) {
                                revivesSent++
                                reviveApp(idle)
                            }
                            if (idle > CLIENT_IDLE_TIMEOUT_MS) {
                                Log.i(
                                    TAG,
                                    "no client calls for ${idle}ms - exiting so the devices are " +
                                        "released instead of being orphaned"
                                )
                                try {
                                    UInputNative.destroy()
                                } catch (_: Throwable) {
                                }
                                System.exit(0)
                            }
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                    }
                },
                "uinput-liveness"
            )
        thread.isDaemon = true
        thread.start()
    }

    /**
     * Asks the app to start its controller service again. A broadcast to the app's exported
     * ReviveReceiver is the one route the shell uid has into a non-exported service; Android does
     * not deliver it to a force-stopped app, so a deliberate force stop still stays stopped.
     */
    private fun reviveApp(idleMs: Long) {
        try {
            val p = ProcessBuilder(
                "am", "broadcast", "-a", REVIVE_ACTION,
                "-n", "${BuildConfig.APPLICATION_ID}/$REVIVE_RECEIVER",
            ).redirectErrorStream(true).start()
            p.waitFor(5, TimeUnit.SECONDS)
            p.destroyForcibly()
            Log.i(TAG, "client silent for ${idleMs}ms - keeping devices, asked the app to come back")
        } catch (t: Throwable) {
            Log.w(TAG, "revive broadcast failed: ${t.message}")
        }
    }

    private fun touch() {
        lastCallAt.set(SystemClock.uptimeMillis())
    }

    override fun selectBackend(): Int {
        touch()
        return try {
            UInputNative.selectBackend()
        } catch (t: Throwable) {
            Log.e(TAG, "selectBackend failed: ${t.message}")
            UInputNative.Backend.NONE
        }
    }

    override fun getBackendDetail(): String {
        touch()
        return try {
            UInputNative.backendDetail()
        } catch (t: Throwable) {
            Log.e(TAG, "getBackendDetail failed: ${t.message}")
            "unavailable: ${t.message}"
        }
    }

    override fun supportsRumble(): Boolean {
        touch()
        return try {
            UInputNative.supportsRumble()
        } catch (t: Throwable) {
            Log.e(TAG, "supportsRumble failed: ${t.message}")
            false
        }
    }

    override fun createGamepad(profileId: Int): Boolean {
        touch()
        return try {
            UInputNative.createDevice(profileId)
        } catch (t: Throwable) {
            Log.e(TAG, "createDevice failed: ${t.message}")
            false
        }
    }

    override fun sendFrame(
        buttons: Int,
        leftStickX: Int,
        leftStickY: Int,
        rightStickX: Int,
        rightStickY: Int,
        leftTrigger: Int,
        rightTrigger: Int,
        dpadX: Int,
        dpadY: Int
    ) {
        touch()
        try {
            UInputNative.sendFrame(
                buttons,
                leftStickX,
                leftStickY,
                rightStickX,
                rightStickY,
                leftTrigger,
                rightTrigger,
                dpadX,
                dpadY
            )
        } catch (t: Throwable) {
            Log.e(TAG, "sendFrame failed: ${t.message}")
        }
    }

    override fun sendMouseFrame(relX: Int, relY: Int, scrollY: Int, keys: Int) {
        touch()
        try {
            UInputNative.sendMouseFrame(relX, relY, scrollY, keys)
        } catch (t: Throwable) {
            Log.e(TAG, "sendMouseFrame failed: ${t.message}")
        }
    }

    override fun pollForceFeedback(): IntArray? {
        // Called at 50 Hz by the app while it is alive - the main proof of life.
        touch()
        return try {
            UInputNative.pollFFEvent()
        } catch (t: Throwable) {
            Log.e(TAG, "pollFFEvent failed: ${t.message}")
            null
        }
    }

    override fun runShellCommand(cmd: Array<String>?): Int {
        touch()
        return runAllowed(cmd)?.exit ?: -1
    }

    override fun runShellCommandForOutput(cmd: Array<String>?): String? {
        touch()
        return runAllowed(cmd)?.takeIf { it.exit == 0 }?.output?.trim()
    }

    override fun installApk(apk: ParcelFileDescriptor?, size: Long): Int {
        touch()
        if (apk == null) return -1
        return try {
            val cmd = arrayOf("pm", "install", "-r", "-S", size.toString())
            runAllowed(cmd, stdin = apk, stdinBytes = size)?.exit ?: -1
        } finally {
            try {
                apk.close()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Grants the calling app USB access to one attached Valve device, as the shell uid (which
     * holds MANAGE_USB on Android 11: frameworks/base/packages/Shell/AndroidManifest.xml), through
     * the hidden IUsbManager - the same grant the system dialog makes, minus the dialog. The device
     * is looked up by name in the system's own device list, so its vendor id is the real one, and
     * the grant goes to the caller's uid only, after checking that uid is this app's.
     */
    override fun grantUsbPermission(deviceName: String?): Int {
        touch()
        if (deviceName.isNullOrEmpty()) return -1
        val caller = Binder.getCallingUid()
        if (!isOwnApp(caller)) {
            Log.w(TAG, "USB grant refused: uid $caller is not ${BuildConfig.APPLICATION_ID}")
            return -1
        }
        return try {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "usb") as? IBinder ?: return -1
            val usb = Class.forName("android.hardware.usb.IUsbManager\$Stub").getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder) ?: return -1
            val list = Bundle()
            usb.javaClass.getMethod("getDeviceList", Bundle::class.java).invoke(usb, list)
            list.classLoader = UsbDevice::class.java.classLoader
            var dev: UsbDevice? = null
            for (key in list.keySet()) {
                @Suppress("DEPRECATION")
                val d = list.getParcelable<UsbDevice>(key)
                if (d != null && d.deviceName == deviceName) dev = d
            }
            if (dev == null) return 0
            if (dev.vendorId != VALVE_VID) {
                Log.w(TAG, "USB grant refused: ${dev.deviceName} is vendor 0x${dev.vendorId.toString(16)}, not Valve")
                return 0
            }
            usb.javaClass.getMethod("grantDevicePermission", UsbDevice::class.java, Int::class.javaPrimitiveType)
                .invoke(usb, dev, caller)
            Log.i(TAG, "USB access granted to uid $caller for ${dev.deviceName} (pid 0x${dev.productId.toString(16)})")
            1
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            Log.w(TAG, "USB grant failed: ${cause.javaClass.simpleName}: ${cause.message}")
            -1
        }
    }

    /** [uid] is this app: the uid Shizuku's context names, or a uid whose packages include ours. */
    private fun isOwnApp(uid: Int): Boolean {
        if (appUid >= 0) return uid == appUid
        return try {
            val pm = Class.forName("android.app.ActivityThread").getMethod("getPackageManager").invoke(null) ?: return false
            @Suppress("UNCHECKED_CAST")
            val pkgs = pm.javaClass.getMethod("getPackagesForUid", Int::class.javaPrimitiveType).invoke(pm, uid) as? Array<String>
            pkgs?.contains(BuildConfig.APPLICATION_ID) == true
        } catch (t: Throwable) {
            Log.w(TAG, "could not check uid $uid: ${t.message}")
            false
        }
    }

    private class ShellResult(val exit: Int, val output: String)

    /**
     * Runs [cmd] as the shell uid only if [ShellAllowlist] permits it. Output is drained while the
     * child runs (so a chatty command cannot block on a full pipe), capped at
     * [ShellAllowlist.MAX_OUTPUT_BYTES], and the child is killed after
     * [ShellAllowlist.TIMEOUT_MS] - well inside the liveness watchdog's window.
     */
    private fun runAllowed(
        cmd: Array<String>?,
        stdin: ParcelFileDescriptor? = null,
        stdinBytes: Long = 0L,
    ): ShellResult? {
        if (cmd == null || !ShellAllowlist.isAllowed(cmd, withStdin = stdin != null)) {
            Log.w(TAG, "refused shell command: ${cmd?.joinToString(" ")}")
            return null
        }
        var proc: Process? = null
        return try {
            proc = ProcessBuilder(cmd.toList()).redirectErrorStream(true).start()
            val sink = proc.outputStream
            if (stdin == null) {
                sink.close()
            } else {
                // Exactly [stdinBytes] from the passed descriptor, then EOF.
                val writer =
                    Thread({
                        try {
                            val input = FileInputStream(stdin.fileDescriptor)
                            val chunk = ByteArray(64 * 1024)
                            var left = stdinBytes
                            while (left > 0) {
                                val n = input.read(chunk, 0, minOf(chunk.size.toLong(), left).toInt())
                                if (n < 0) break
                                sink.write(chunk, 0, n)
                                left -= n
                            }
                        } catch (_: IOException) {
                        } finally {
                            try {
                                sink.close()
                            } catch (_: IOException) {
                            }
                        }
                    }, "shell-input")
                writer.isDaemon = true
                writer.start()
            }
            val buffer = ByteArrayOutputStream()
            val stream = proc.inputStream
            val reader =
                Thread({
                    val chunk = ByteArray(4096)
                    try {
                        while (true) {
                            val n = stream.read(chunk)
                            if (n < 0) break
                            val room = ShellAllowlist.MAX_OUTPUT_BYTES - buffer.size()
                            if (room > 0) buffer.write(chunk, 0, minOf(n, room))
                        }
                    } catch (_: IOException) {
                    }
                }, "shell-output")
            reader.isDaemon = true
            reader.start()
            if (!proc.waitFor(ShellAllowlist.timeoutFor(cmd), TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "shell command timed out: ${cmd.joinToString(" ")}")
                return null
            }
            reader.join(1_000)
            val exit = proc.exitValue()
            Log.i(TAG, "shell ${cmd.joinToString(" ")} → exit=$exit")
            ShellResult(exit, buffer.toString(Charsets.UTF_8.name()))
        } catch (t: Throwable) {
            Log.e(TAG, "shell command failed: ${t.message}")
            null
        } finally {
            proc?.let {
                try {
                    it.inputStream.close()
                } catch (_: Throwable) {
                }
                it.destroyForcibly()
            }
        }
    }

    override fun createKeyboard(): Boolean {
        touch()
        if (!isOwnApp(Binder.getCallingUid())) return false
        return try {
            UInputNative.createKeyboard()
        } catch (t: Throwable) {
            Log.e(TAG, "createKeyboard failed: ${t.message}")
            false
        }
    }

    override fun sendKeyboard(modifiers: Int, keys: IntArray?): Boolean {
        touch()
        val k = keys ?: IntArray(0)
        if (!com.steamcontroller.android.radial.RadialKeys.reportAllowed(modifiers, k)) {
            Log.w(TAG, "refused keyboard report mods=$modifiers keys=${k.joinToString()}")
            return false
        }
        return try {
            UInputNative.sendKeyboard(modifiers, k)
        } catch (t: Throwable) {
            Log.e(TAG, "sendKeyboard failed: ${t.message}")
            false
        }
    }

    override fun destroyKeyboard() {
        touch()
        try {
            UInputNative.destroyKeyboard()
        } catch (t: Throwable) {
            Log.e(TAG, "destroyKeyboard failed: ${t.message}")
        }
    }

    override fun destroy() {
        touch()
        try {
            UInputNative.destroy()
        } catch (t: Throwable) {
            Log.e(TAG, "destroy native failed: ${t.message}")
        }
        // Shizuku contract: destroy() must terminate the process
        System.exit(0)
    }
}
