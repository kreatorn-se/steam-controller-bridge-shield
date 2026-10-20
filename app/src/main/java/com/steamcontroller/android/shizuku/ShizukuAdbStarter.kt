package com.steamcontroller.android.shizuku

import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * Starts Shizuku after a reboot without a computer, by talking ADB to the Shield itself.
 *
 * The Shield keeps "Network debugging" (adbd on port 5555) switched on across reboots, so the
 * app connects to 127.0.0.1:5555 with its own RSA key — the same thing the PX13 script does from
 * the LAN — and runs Shizuku's starter. The first connection shows the "Allow debugging?"
 * prompt on the TV once; tick "Always allow" and every later boot is silent.
 */
object ShizukuAdbStarter {
    private const val TAG = "ShizukuAdbStarter"
    private const val HOST = "127.0.0.1"
    private const val PORT = 5555
    private const val PKG = ShizukuStarterService.SHIZUKU_PACKAGE

    // Prefer Shizuku's own start.sh (written once the Shizuku app has been opened); otherwise run
    // the starter binary from its native library directory, which is what start.sh does.
    private val START_COMMAND = """
        S=/storage/emulated/0/Android/data/$PKG/start.sh
        if [ -f "${'$'}S" ]; then sh "${'$'}S"; exit ${'$'}?; fi
        P=${'$'}(pm path $PKG | head -n1 | sed 's/^package://; s/base\.apk${'$'}//')
        for L in "${'$'}{P}lib/arm64/libshizuku.so" "${'$'}{P}lib/arm/libshizuku.so"; do
          if [ -f "${'$'}L" ]; then "${'$'}L"; exit ${'$'}?; fi
        done
        echo "Shizuku starter not found - open the Shizuku app once"; exit 3
    """.trimIndent()

    data class Result(val ok: Boolean, val message: String)

    private fun keyPair(context: Context): AdbKeyPair {
        val dir = File(context.filesDir, "adb").apply { mkdirs() }
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    /** Checks the ADB connection only (and triggers the one-time authorisation prompt). */
    fun testConnection(context: Context, waitForPromptMs: Int = 60_000): Result = run(context, "echo ok", waitForPromptMs)

    /** Runs Shizuku's starter. */
    fun startShizuku(context: Context, timeoutMs: Int = 20_000): Result = run(context, START_COMMAND, timeoutMs)

    private fun run(context: Context, command: String, socketTimeoutMs: Int): Result = try {
        Dadb.create(HOST, PORT, keyPair(context), 5_000, socketTimeoutMs).use { adb ->
            val r = adb.shell(command)
            val out = r.allOutput.trim().takeLast(300)
            Log.i(TAG, "exit ${r.exitCode}: $out")
            Result(r.exitCode == 0, out.ifBlank { "exit ${r.exitCode}" })
        }
    } catch (t: Throwable) {
        Log.w(TAG, "adb failed: ${t.javaClass.simpleName}: ${t.message}")
        Result(false, describe(t))
    }

    private fun describe(t: Throwable): String {
        val m = (t.message ?: "").lowercase()
        return when {
            t is java.net.ConnectException || "refused" in m ->
                "Network debugging is off. Turn it on in Developer options."
            t is java.net.SocketTimeoutException || "timed out" in m ->
                "No answer. Accept the debugging prompt on the TV and tick Always allow."
            else -> t.message ?: t.javaClass.simpleName
        }
    }
}
