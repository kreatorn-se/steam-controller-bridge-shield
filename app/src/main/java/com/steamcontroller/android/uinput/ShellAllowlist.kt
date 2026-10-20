package com.steamcontroller.android.uinput

import com.steamcontroller.android.BuildConfig

/**
 * The only commands [UInputService] will run as the shell uid. The service holds shell
 * privileges, so it exposes exactly what the app uses instead of a general escape hatch:
 *
 * - `mkdir -p` / `screencap -p` inside the screenshots directory (SystemActions)
 * - `screencap -p` into the app's own Gamehub upload queue (Android/data/<app>/files/captures)
 * - `settings get|put|delete secure show_ime_with_hard_keyboard` (UInputGamepad)
 * - `cmd deviceidle whitelist +<this app>` so Android may restart the service after a kill
 * - `appops set <this app> SYSTEM_ALERT_WINDOW allow` so the status pill can draw above games
 * - `pm install -r -S <bytes>` for the app's own self-update, and only through
 *   [UInputService.installApk], which feeds the APK on stdin from a file descriptor the app
 *   passes. The APK stays in the app's private storage, and the app checks its SHA-256, package
 *   name, signing certificate and version code just before. No other command gets stdin.
 *
 * Adding a system action means adding its exact shape here.
 */
internal object ShellAllowlist {
    const val TIMEOUT_MS = 15_000L
    // A 4K PNG takes the Shield 15-30 s to encode while a GFN stream is running.
    private const val SCREENCAP_TIMEOUT_MS = 120_000L

    // Installing a ~20 MB APK: dexopt on the Shield can take a minute or two.
    private const val INSTALL_TIMEOUT_MS = 240_000L

    fun timeoutFor(cmd: Array<String>): Long = when (cmd.firstOrNull()) {
        "screencap" -> SCREENCAP_TIMEOUT_MS
        "pm" -> INSTALL_TIMEOUT_MS
        else -> TIMEOUT_MS
    }
    const val MAX_OUTPUT_BYTES = 64 * 1024

    const val SCREENSHOT_DIR = "/sdcard/Pictures/Screenshots"
    private const val IME_SETTING = "show_ime_with_hard_keyboard"

    private val SCREENSHOT_FILE = Regex("""[A-Za-z0-9_\-]+\.png""")

    // The app's external files dir as the shell user sees it; both spellings resolve to it.
    private val CAPTURE_DIRS = listOf(
        "/storage/emulated/0/Android/data/${BuildConfig.APPLICATION_ID}/files/captures",
        "/sdcard/Android/data/${BuildConfig.APPLICATION_ID}/files/captures",
    )
    private val INT_VALUE = Regex("""-?\d{1,10}""")

    /** Upper bound for a self-update APK (the release is ~20 MB). */
    const val MAX_APK_BYTES = 256L * 1024 * 1024
    private val APK_SIZE = Regex("""[1-9][0-9]{0,9}""")

    /** The self-update install, the only command that is given stdin ([withStdin]). */
    fun isInstall(cmd: Array<String>): Boolean =
        cmd.size == 5 && cmd[0] == "pm" && cmd[1] == "install" && cmd[2] == "-r" && cmd[3] == "-S" &&
            APK_SIZE.matches(cmd[4]) && cmd[4].toLong() <= MAX_APK_BYTES

    fun isAllowed(cmd: Array<String>, withStdin: Boolean = false): Boolean {
        if (withStdin || cmd.firstOrNull() == "pm") return withStdin && isInstall(cmd)
        val args = cmd.toList()
        return when {
            args == listOf("mkdir", "-p", SCREENSHOT_DIR) -> {
                true
            }

            args.size == 3 && args[0] == "screencap" && args[1] == "-p" -> {
                isScreenshotPath(args[2])
            }

            args == listOf("cmd", "deviceidle", "whitelist", "+${BuildConfig.APPLICATION_ID}") -> {
                true
            }

            args == listOf("appops", "set", BuildConfig.APPLICATION_ID, "SYSTEM_ALERT_WINDOW", "allow") -> {
                true
            }

            args.size >= 4 && args[0] == "settings" && args[2] == "secure" && args[3] == IME_SETTING -> {
                when (args[1]) {
                    "get", "delete" -> args.size == 4
                    "put" -> args.size == 5 && INT_VALUE.matches(args[4])
                    else -> false
                }
            }

            else -> {
                false
            }
        }
    }

    private fun isScreenshotPath(path: String): Boolean =
        (CAPTURE_DIRS + SCREENSHOT_DIR).any { dir ->
            val prefix = "$dir/"
            path.startsWith(prefix) && SCREENSHOT_FILE.matches(path.removePrefix(prefix))
        }
}
