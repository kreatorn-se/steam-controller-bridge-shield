package com.steamcontroller.android.hub

import android.content.Context
import android.os.Build
import com.steamcontroller.android.BuildConfig
import com.steamcontroller.android.service.Diagnostics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Diagnostics to Gamehub (POST /api/device/v1/diag): the existing Diagnostics history plus the
 * TV's own notes (home load timings), in batches every two minutes while the app or the
 * controller service runs, at once after an error, and a crash report on the next start.
 *
 * Redacted: never the token (and nothing that looks like one), no file contents, no user text.
 * Off switch on DEVICE. A hub without the endpoint (404) is left alone: the data is dropped.
 */
object Diag {
    private const val PREFS = "diag"
    private const val K_ON = "upload"
    private const val MAX_BATCH = 200
    private const val MAX_CRASH = 16 * 1024

    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "diag").apply { isDaemon = true } }
    private val notes = ArrayList<String>()
    private var mark = 0L
    private var soon: ScheduledFuture<*>? = null
    @Volatile private var app: Context? = null

    fun enabled(c: Context): Boolean = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_ON, true)
    fun setEnabled(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_ON, on).apply()
        if (!on) synchronized(notes) { notes.clear(); mark = Diagnostics.since(Long.MAX_VALUE).first }
    }

    /** Crash handler and the context to send with; safe to call more than once. */
    fun install(c: Context) {
        if (app != null) return
        app = c.applicationContext
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { writeCrash(t, e) } catch (_: Throwable) {}
            prior?.uncaughtException(t, e)
        }
        // A crash from last time goes out now.
        flushSoon(10_000L)
    }

    /** A line of the TV's own (timings, states). */
    fun note(line: String) {
        synchronized(notes) {
            if (notes.size >= MAX_BATCH) notes.removeAt(0)
            notes.add(redact(line))
        }
    }

    /** Something went wrong: note it and send soon. */
    fun error(line: String) {
        note("ERROR $line")
        flushSoon(5_000L)
    }

    fun flushSoon(delayMs: Long) {
        synchronized(this) {
            soon?.cancel(false)
            soon = exec.schedule({ flush() }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    /** One batch now (background thread). */
    fun flush() {
        val c = app ?: return
        if (!enabled(c) || !GamehubStore.isLinked(c) || GamehubTv.Missing.has(c, "diag")) {
            synchronized(notes) { notes.clear() }
            mark = Diagnostics.since(mark).first
            crashFiles(c).forEach { it.delete() }
            return
        }
        for (f in crashFiles(c)) {
            val text = try { f.readText().take(MAX_CRASH) } catch (_: Throwable) { "" }
            val r = send(c, JSONArray(), text)
            if (r != Sent.RETRY) f.delete()
            if (r == Sent.MISSING) return
        }
        val (newMark, recent) = Diagnostics.since(mark)
        val batch = JSONArray()
        for (l in recent) batch.put(redact(l))
        val ours = synchronized(notes) { val copy = notes.toList(); notes.clear(); copy }
        for (l in ours) batch.put(l)
        if (batch.length() == 0) { mark = newMark; return }
        when (send(c, batch, null)) {
            Sent.OK, Sent.MISSING -> mark = newMark
            Sent.RETRY -> synchronized(notes) { notes.addAll(0, ours.takeLast(MAX_BATCH / 2)) }
        }
    }

    private enum class Sent { OK, RETRY, MISSING }

    private fun send(c: Context, lines: JSONArray, crash: String?): Sent {
        val body = JSONObject()
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("device", JSONObject().put("model", Build.MODEL ?: "").put("sdk", Build.VERSION.SDK_INT))
            .put("lines", lines)
        if (crash != null) body.put("crash", crash)
        return when (val r = GamehubTv.postDiag(c, body.toString())) {
            is GamehubTv.Res.Ok -> Sent.OK
            is GamehubTv.Res.Err -> when (r.fail) {
                GamehubTv.Fail.NOT_DEPLOYED -> { GamehubTv.Missing.mark(c, "diag"); Sent.MISSING }
                GamehubTv.Fail.OFFLINE -> Sent.RETRY
                else -> Sent.MISSING // refused or broken: drop, never loop
            }
        }
    }

    private fun crashDir(c: Context) = File(c.filesDir, "diag").apply { mkdirs() }
    private fun crashFiles(c: Context): List<File> = crashDir(c).listFiles { f -> f.name.startsWith("crash-") }?.sortedBy { it.name }?.take(3) ?: emptyList()

    private fun writeCrash(t: Thread, e: Throwable) {
        val c = app ?: return
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val text = "thread=${t.name} version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" + redact(sw.toString()).take(MAX_CRASH)
        File(crashDir(c), "crash-${System.currentTimeMillis()}.txt").writeText(text)
    }

    private val HEX = Regex("""\b[0-9a-fA-F]{32,}\b""")
    private val BEARER = Regex("""(?i)bearer\s+\S+""")
    private val TOKENISH = Regex("""(?i)(token|authorization)[=:]\s*\S+""")

    /** Strips anything that could be a credential. */
    fun redact(s: String): String = TOKENISH.replace(BEARER.replace(HEX.replace(s, "<hex>"), "Bearer <redacted>"), "$1=<redacted>")
}
