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
 * Diagnostics to Gamehub (POST /api/device/v1/diag, `{appVersion, logs: [{t, level, tag, msg}],
 * crashes: [{t, thread, stack, appVersion}]}`): the existing Diagnostics history plus the
 * TV's own notes (home load timings), in batches every two minutes while the app or the
 * controller service runs, soon after an error (at most one such early send per two minutes),
 * and a crash report on the next start. A batch is at most 60 lines and 48 KB of UTF-8 JSON;
 * every send runs on this object's single thread, one at a time. The hub may answer 200
 * `{dropped: n}` when it trims a batch: that is still a success.
 *
 * Redacted: never the token (and nothing that looks like one), no file contents, no user text.
 * Off switch on DEVICE. A hub without the endpoint (404) is left alone: the data is dropped.
 */
object Diag {
    private const val PREFS = "diag"
    private const val K_ON = "upload"
    private const val MAX_BATCH = 200          // notes kept while waiting to be sent
    private const val MAX_LINES = 60           // per request
    private const val MAX_BYTES = 48 * 1024    // per request, serialized UTF-8
    private const val MAX_CRASH = 12 * 1024
    private const val MAX_MSG = 300
    private const val ERROR_SEND_GAP_MS = 120_000L

    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "diag").apply { isDaemon = true } }
    private class Note(val t: Long, val level: String, val tag: String, val msg: String)
    private val notes = ArrayList<Note>()
    @Volatile private var mark = 0L
    private var soon: ScheduledFuture<*>? = null
    private var lastErrorSend = 0L
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
    fun note(line: String, level: String = "i", tag: String = "tv") {
        synchronized(notes) {
            if (notes.size >= MAX_BATCH) notes.removeAt(0)
            notes.add(Note(System.currentTimeMillis(), level, tag, redact(line).take(MAX_MSG)))
        }
    }

    /** Something went wrong: note it, and send soon unless an error batch went out lately. */
    fun error(line: String) {
        note(line, "e")
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(this) {
            if (lastErrorSend != 0L && now - lastErrorSend < ERROR_SEND_GAP_MS) return
            lastErrorSend = now
        }
        flushSoon(5_000L)
    }

    fun flushSoon(delayMs: Long) {
        synchronized(this) {
            soon?.cancel(false)
            soon = exec.schedule({ safeFlush() }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    /** The periodic batch (Background): queued on the diag thread like every other send. */
    fun flushNow() {
        exec.execute { safeFlush() }
    }

    // Its own lock (not this object's), so error() never waits on a send in progress.
    private val flushLock = Any()

    private fun safeFlush() {
        try { synchronized(flushLock) { flush() } } catch (_: Throwable) {}
    }

    /** One batch; only ever on [exec], and never two at once. */
    private fun flush() {
        val c = app ?: return
        if (!enabled(c) || !GamehubStore.isLinked(c) || GamehubTv.Missing.has(c, "diag")) {
            synchronized(notes) { notes.clear() }
            mark = Diagnostics.since(mark).first
            crashFiles(c).forEach { it.delete() }
            return
        }
        val files = crashFiles(c)
        if (files.isNotEmpty()) {
            // As many crash reports as fit; the rest go with the next batch.
            val crashes = JSONArray()
            val sent = ArrayList<File>()
            var bytes = ENVELOPE
            for (f in files) {
                val o = try { JSONObject(f.readText()) } catch (_: Throwable) { f.delete(); continue }
                val n = utf8(o.toString()) + 1
                if (sent.isNotEmpty() && bytes + n > MAX_BYTES) break
                crashes.put(o); sent.add(f); bytes += n
            }
            if (sent.isNotEmpty()) {
                val r = send(c, JSONArray(), crashes)
                if (r != Sent.RETRY) sent.forEach { it.delete() }
                if (r == Sent.MISSING) return
            }
        }
        val (newMark, recent) = Diagnostics.since(mark)
        val now = System.currentTimeMillis()
        // Our own notes first (errors, timings); what does not fit waits for the next batch.
        // The service's history fills the rest, newest first; older lines are dropped.
        val ours = synchronized(notes) { notes.toList() }
        val logs = JSONArray()
        var bytes = ENVELOPE
        var taken = 0
        for (n in ours) {
            val o = JSONObject().put("t", n.t).put("level", n.level).put("tag", n.tag).put("msg", n.msg)
            val size = utf8(o.toString()) + 1
            if (logs.length() >= MAX_LINES || bytes + size > MAX_BYTES) break
            logs.put(o); bytes += size; taken++
        }
        val service = ArrayList<JSONObject>()
        // Each service line carries its own timestamp in the text.
        for (l in recent.asReversed()) {
            if (logs.length() + service.size >= MAX_LINES) break
            val o = JSONObject().put("t", now).put("level", "i").put("tag", "app").put("msg", redact(l).take(MAX_MSG))
            val size = utf8(o.toString()) + 1
            if (bytes + size > MAX_BYTES) break
            service.add(o); bytes += size
        }
        for (o in service.asReversed()) logs.put(o)
        if (logs.length() == 0) { mark = newMark; return }
        when (send(c, logs, null)) {
            Sent.OK, Sent.MISSING -> {
                mark = newMark
                val gone = ours.take(taken).toHashSet() // by identity: note() may have trimmed the front meanwhile
                synchronized(notes) { notes.removeAll { it in gone } }
            }
            Sent.RETRY -> {} // notes stay queued; the service lines are offered again
        }
    }

    private const val ENVELOPE = 512 // appVersion and the JSON around the arrays
    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8).size

    private enum class Sent { OK, RETRY, MISSING }

    private fun send(c: Context, logs: JSONArray, crashes: JSONArray?): Sent {
        val body = JSONObject()
            .put("appVersion", BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ") " + (Build.MODEL ?: "") + " sdk " + Build.VERSION.SDK_INT)
            .put("logs", logs)
        if (crashes != null) body.put("crashes", crashes)
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
        val o = JSONObject().put("t", System.currentTimeMillis()).put("thread", t.name)
            .put("stack", redact(sw.toString()).take(MAX_CRASH)).put("appVersion", BuildConfig.VERSION_NAME)
        File(crashDir(c), "crash-${System.currentTimeMillis()}.json").writeText(o.toString())
    }

    private val HEX = Regex("""\b[0-9a-fA-F]{32,}\b""")
    private val BEARER = Regex("""(?i)bearer\s+\S+""")
    private val TOKENISH = Regex("""(?i)(token|authorization)[=:]\s*\S+""")

    /** Strips anything that could be a credential. */
    fun redact(s: String): String = TOKENISH.replace(BEARER.replace(HEX.replace(s, "<hex>"), "Bearer <redacted>"), "$1=<redacted>")
}
