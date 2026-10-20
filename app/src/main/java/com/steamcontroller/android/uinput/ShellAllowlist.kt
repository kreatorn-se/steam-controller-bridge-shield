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
 *
 * Adding a system action means adding its exact shape here.
 */
internal object ShellAllowlist {
    const val TIMEOUT_MS = 15_000L
    // A 4K PNG takes the Shield 15-30 s to encode while a GFN stream is running.
    private const val SCREENCAP_TIMEOUT_MS = 120_000L

    fun timeoutFor(cmd: Array<String>): Long = if (cmd.firstOrNull() == "screencap") SCREENCAP_TIMEOUT_MS else TIMEOUT_MS
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

    fun isAllowed(cmd: Array<String>): Boolean {
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
