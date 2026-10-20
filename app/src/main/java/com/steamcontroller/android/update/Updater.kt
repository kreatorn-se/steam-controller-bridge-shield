package com.steamcontroller.android.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.steamcontroller.android.BuildConfig
import com.steamcontroller.android.hub.Diag
import com.steamcontroller.android.service.ControllerService
import com.steamcontroller.android.uinput.UInputGamepad
import com.steamcontroller.android.ui.pill.Pill
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Self-update without a PC: the latest GitHub release of this repository, checked at start and
 * then at most every six hours. One press downloads `SteamControllerBridge-v<ver>.apk` into the
 * app's private files dir and installs it only if all of these hold, checked right before the
 * install on the very descriptor that is installed:
 *
 * - the release lists a SHA-256 digest for the asset, and the file matches it (no digest, no
 *   update);
 * - the APK's package name is this app's, it is signed with the same certificate as the
 *   installed app, and its version code is higher.
 *
 * The bytes then go to the Shizuku shell service as a file descriptor, which runs exactly
 * `pm install -r -S <size>` with them on stdin (ShellAllowlist). Nothing is world-readable and
 * test-only packages are not accepted. Before installing, a flag is written; UpdateReceiver
 * (MY_PACKAGE_REPLACED) brings the controller service and the app back when the new version
 * starts.
 */
object Updater {
    private const val API = "https://api.github.com/repos/kreatorn-se/steam-controller-bridge-shield/releases/latest"
    private const val PREFS = "updater"
    private const val CHECK_EVERY_MS = 6 * 3_600_000L
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val ASSET = Regex("""SteamControllerBridge-v([0-9.]+)(-[a-z0-9]+)?\.apk""")

    data class Release(val version: String, val name: String, val url: String, val size: Long, val sha256: String?)

    sealed class State {
        object Idle : State()
        data class Available(val rel: Release) : State()
        data class Downloading(val rel: Release, val progress: Float) : State()
        data class Installing(val rel: Release) : State()
        /** [seq] tells one failure from the next, so each is announced once. */
        data class Failed(val rel: Release?, val message: String, val seq: Long = 0L) : State()
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
            fail(rel, "needs_service")
            return
        }
        busy = true
        publish(State.Downloading(rel, 0f))
        io.execute {
            try {
                if (rel.sha256 == null || !SHA256.matches(rel.sha256)) {
                    fail(rel, "no_digest")
                    return@execute
                }
                val dir = File(app.filesDir, "update").apply { mkdirs() }
                dir.listFiles()?.forEach { if (it.name != rel.name) it.delete() }
                // Leftovers from 1.4.1, which kept the APK in the shared external dir.
                app.getExternalFilesDir(null)?.let { File(it, "update").deleteRecursively() }
                if (!ASSET.matches(rel.name)) throw IllegalStateException("bad name")
                val apk = File(dir, rel.name)
                download(rel, apk)
                ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    val size = pfd.statSize
                    if (rel.size > 0 && size != rel.size) { apk.delete(); fail(rel, "size"); return@execute }
                    verifyPackage(app, apk)?.let { why -> apk.delete(); fail(rel, why); return@execute }
                    // Hash the descriptor that is handed over, then rewind it for the shell side.
                    if (sha256(pfd) != rel.sha256) { apk.delete(); fail(rel, "checksum"); return@execute }
                    Os.lseek(pfd.fileDescriptor, 0L, OsConstants.SEEK_SET)
                    publish(State.Installing(rel))
                    // Come back after the replace: the receiver reads these (UpdateReceiver).
                    p(app).edit().putBoolean("pending", true).putBoolean("service_was_running", ControllerService.isRunning)
                        .putLong("pending_at", System.currentTimeMillis()).commit()
                    Pill.show(app, Pill.Kind.INFO, "UPDATING · back in a moment")
                    Diag.note("update ${BuildConfig.VERSION_NAME} -> ${rel.version}: installing")
                    val exit = gamepad.installApk(pfd, size)
                    // Success kills this process before we get here; anything else is a failure.
                    p(app).edit().putBoolean("pending", false).apply()
                    fail(rel, if (exit == -2) "needs_service" else "install_$exit")
                }
            } catch (t: Throwable) {
                fail(rel, "download")
                Diag.error("update download failed: ${t.javaClass.simpleName}")
            } finally {
                busy = false
            }
        }
    }

    /** Null when [apk] is a newer build of this app with the same signer, else why not. */
    private fun verifyPackage(c: Context, apk: File): String? {
        val pm = c.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val theirs = pm.getPackageArchiveInfo(apk.path, flags) ?: return "unreadable"
        if (theirs.packageName != BuildConfig.APPLICATION_ID || c.packageName != BuildConfig.APPLICATION_ID) return "package"
        val mine = pm.getPackageInfo(c.packageName, flags)
        if (versionCode(theirs) <= versionCode(mine)) return "version"
        val a = signers(theirs)
        if (a.isEmpty() || a != signers(mine)) return "signer"
        return null
    }

    @Suppress("DEPRECATION")
    private fun versionCode(p: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signers(p: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners else p.signatures
        return sigs.orEmpty().map { sig -> hex(MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())) }.toSet()
    }

    private var failures = 0L
    private var announced = 0L

    private fun fail(rel: Release, why: String) {
        Diag.error("update failed: $why")
        publish(State.Failed(rel, why, synchronized(this) { ++failures }))
    }

    /** True the first time it is asked about [f]: its pill shows once, not on every re-observe. */
    @Synchronized fun announce(f: State.Failed): Boolean {
        if (f.seq <= announced) return false
        announced = f.seq
        return true
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

    /** SHA-256 of everything readable from [pfd]'s current position (left at the end). */
    private fun sha256(pfd: ParcelFileDescriptor): String {
        val md = MessageDigest.getInstance("SHA-256")
        Os.lseek(pfd.fileDescriptor, 0L, OsConstants.SEEK_SET)
        // Not closed: closing a stream made from a FileDescriptor must not close the descriptor.
        val input = FileInputStream(pfd.fileDescriptor)
        val buf = ByteArray(64 * 1024)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        return hex(md.digest())
    }

    private fun hex(b: ByteArray) = b.joinToString("") { String.format("%02x", it.toInt() and 0xFF) }

    /** For UpdateReceiver: whether this start follows our own install, and if the service ran. */
    fun consumePending(c: Context): Pair<Boolean, Boolean> {
        val prefs = p(c)
        val pending = prefs.getBoolean("pending", false) && System.currentTimeMillis() - prefs.getLong("pending_at", 0L) < 30 * 60_000L
        val wasRunning = prefs.getBoolean("service_was_running", false)
        prefs.edit().putBoolean("pending", false).remove("rel").apply()
        return pending to wasRunning
    }
}
