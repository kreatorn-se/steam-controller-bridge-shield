package com.steamcontroller.android.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.steamcontroller.android.BuildConfig
import com.steamcontroller.android.hub.Diag
import com.steamcontroller.android.service.ControllerService
import com.steamcontroller.android.uinput.UInputGamepad
import com.steamcontroller.android.ui.pill.Pill
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Self-update without a PC: the latest GitHub release of this repository, checked at start and
 * then at most every six hours. One press downloads `SteamControllerBridge-v<ver>.apk` into the
 * app's external files dir (the shell user can read it there), checks its SHA-256 against the
 * release's digest when GitHub gives one, and installs it with `pm install -r -t` through the
 * Shizuku shell service (ShellAllowlist allows exactly that, for exactly that folder and name).
 *
 * Android refuses an APK signed with another key, so only builds of this same app can install.
 * Before installing, a flag is written; UpdateReceiver (MY_PACKAGE_REPLACED) brings the
 * controller service and the app back when the new version starts.
 */
object Updater {
    private const val API = "https://api.github.com/repos/kreatorn-se/steam-controller-bridge-shield/releases/latest"
    private const val PREFS = "updater"
    private const val CHECK_EVERY_MS = 6 * 3_600_000L
    private val ASSET = Regex("""SteamControllerBridge-v([0-9.]+)(-[a-z0-9]+)?\.apk""")

    data class Release(val version: String, val name: String, val url: String, val size: Long, val sha256: String?)

    sealed class State {
        object Idle : State()
        data class Available(val rel: Release) : State()
        data class Downloading(val rel: Release, val progress: Float) : State()
        data class Installing(val rel: Release) : State()
        data class Failed(val rel: Release?, val message: String) : State()
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "updater").apply { isDaemon = true } }
    private val listeners = LinkedHashSet<(State) -> Unit>()
    @Volatile var state: State = State.Idle
        private set
    @Volatile private var busy = false

    fun observe(l: (State) -> Unit) { listeners.add(l); l(state) }
    fun remove(l: (State) -> Unit) { listeners.remove(l) }

    private fun publish(s: State) {
        state = s
        main.post { for (l in listeners.toList()) l(s) }
    }

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── checking ──────────────────────────────────────────────────────────────────────

    /** At start, and from the background loop; checks GitHub at most every six hours. */
    fun checkIfDue(c: Context, force: Boolean = false) {
        val app = c.applicationContext
        // What the last check found shows at once, without the network.
        if (state is State.Idle) remembered(app)?.let { if (isNewer(it.version)) publish(State.Available(it)) }
        val last = p(app).getLong("checked_at", 0L)
        if (!force && System.currentTimeMillis() - last < CHECK_EVERY_MS) return
        io.execute {
            val rel = try { fetchLatest() } catch (t: Throwable) { Diag.note("update check failed: ${t.javaClass.simpleName}"); return@execute }
            p(app).edit().putLong("checked_at", System.currentTimeMillis()).apply()
            if (rel != null && isNewer(rel.version)) {
                remember(app, rel)
                if (!busy) publish(State.Available(rel))
            } else if (!busy) {
                p(app).edit().remove("rel").apply()
                publish(State.Idle)
            }
        }
    }

    private fun fetchLatest(): Release? {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("accept", "application/vnd.github+json")
        conn.setRequestProperty("user-agent", "Gamehub-TV/${BuildConfig.VERSION_NAME}")
        try {
            if (conn.responseCode != 200) return null
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
            val tagVersion = o.optString("tag_name").removePrefix("v").trim()
            val assets = o.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name")
                val m = ASSET.matchEntire(name) ?: continue
                val version = m.groupValues[1].ifEmpty { tagVersion }
                val digest = a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
                return Release(version, name, a.optString("browser_download_url"), a.optLong("size", 0L), digest)
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun remember(c: Context, r: Release) {
        val o = JSONObject().put("version", r.version).put("name", r.name).put("url", r.url).put("size", r.size).put("sha256", r.sha256 ?: "")
        p(c).edit().putString("rel", o.toString()).apply()
    }

    private fun remembered(c: Context): Release? = try {
        p(c).getString("rel", null)?.let { JSONObject(it) }?.let {
            Release(it.getString("version"), it.getString("name"), it.getString("url"), it.optLong("size"), it.optString("sha256").ifEmpty { null })
        }
    } catch (_: Throwable) { null }

    /** "1.4.10" > "1.4.9"; anything unparsable is not newer. */
    fun isNewer(version: String): Boolean {
        val a = version.split('.').map { it.toIntOrNull() ?: return false }
        val b = BuildConfig.VERSION_NAME.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    // ── installing ────────────────────────────────────────────────────────────────────

    /** Download, verify and install [rel]. Progress and the outcome arrive through [observe]. */
    fun install(c: Context, rel: Release) {
        if (busy) return
        val app = c.applicationContext
        val gamepad = UInputGamepad.active
        if (!ControllerService.isRunning || gamepad == null) {
            publish(State.Failed(rel, "needs_service"))
            return
        }
        busy = true
        publish(State.Downloading(rel, 0f))
        io.execute {
            try {
                val dir = File(app.getExternalFilesDir(null), "update").apply { mkdirs() }
                dir.listFiles()?.forEach { if (it.name != rel.name) it.delete() }
                val apk = File(dir, rel.name)
                if (!ASSET.matches(apk.name)) throw IllegalStateException("bad name")
                download(rel, apk)
                if (rel.sha256 != null && sha256(apk) != rel.sha256) {
                    apk.delete()
                    fail(rel, "checksum")
                    return@execute
                }
                apk.setReadable(true, false)
                publish(State.Installing(rel))
                // Come back after the replace: the receiver reads these (UpdateReceiver).
                p(app).edit().putBoolean("pending", true).putBoolean("service_was_running", ControllerService.isRunning)
                    .putLong("pending_at", System.currentTimeMillis()).commit()
                Pill.show(app, Pill.Kind.INFO, "UPDATING · back in a moment")
                Diag.note("update ${BuildConfig.VERSION_NAME} -> ${rel.version}: installing")
                val exit = gamepad.runInstall(apk.absolutePath.replace(Regex("^/storage/emulated/\\d+/"), "/storage/emulated/0/"))
                // Success kills this process before we get here; anything else is a failure.
                p(app).edit().putBoolean("pending", false).apply()
                fail(rel, if (exit == -2) "needs_service" else "install_$exit")
            } catch (t: Throwable) {
                fail(rel, "download")
                Diag.error("update download failed: ${t.javaClass.simpleName}")
            } finally {
                busy = false
            }
        }
    }

    private fun fail(rel: Release, why: String) {
        Diag.error("update failed: $why")
        publish(State.Failed(rel, why))
    }

    private fun download(rel: Release, dest: File) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        var conn = URL(rel.url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("user-agent", "Gamehub-TV/${BuildConfig.VERSION_NAME}")
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else rel.size
            var done = 0L
            var lastPublished = 0f
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pr = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                        if (pr - lastPublished >= 0.01f) { lastPublished = pr; publish(State.Downloading(rel, pr)) }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (!tmp.renameTo(dest)) throw IllegalStateException("rename")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
    }

    /** For UpdateReceiver: whether this start follows our own install, and if the service ran. */
    fun consumePending(c: Context): Pair<Boolean, Boolean> {
        val prefs = p(c)
        val pending = prefs.getBoolean("pending", false) && System.currentTimeMillis() - prefs.getLong("pending_at", 0L) < 30 * 60_000L
        val wasRunning = prefs.getBoolean("service_was_running", false)
        prefs.edit().putBoolean("pending", false).remove("rel").apply()
        return pending to wasRunning
    }
}
