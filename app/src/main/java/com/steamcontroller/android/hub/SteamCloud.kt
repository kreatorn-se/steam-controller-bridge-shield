package com.steamcontroller.android.hub

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Enshrouded saves from Steam Cloud into Gamehub, for a game played over GeForce NOW: the saves
 * never touch the Shield, but Steam keeps them in the cloud, and Steam's account page lists them
 * with single-use download links that need no login of their own.
 *
 * Kent logs in to Steam once on this Shield ([SteamLoginActivity], Steam's own page, QR code from
 * the Steam app on his phone). Steam's web login lives in this app's WebView cookies and renews
 * itself whenever its pages load, so nothing about it is stored or handled here. After an
 * Enshrouded session ends ([SteamSwitch] sees the game change), and every few hours as a fallback,
 * the page is loaded in a WebView that is never shown; the character and world files that changed
 * are downloaded and sent to Gamehub (`PUT /api/device/ensh/files/<name>`, character before world).
 * Gamehub's Enshrouded page reads them the next time it is opened.
 */
object SteamCloud {
    private const val TAG = "SteamCloud"
    const val ENSHROUDED_APPID = 1203620L
    const val PAGE = "https://store.steampowered.com/account/remotestorageapp/?appid=1203620"
    const val LOGIN = "https://store.steampowered.com/login/?redir=account%2Fremotestorageapp%2F%3Fappid%3D1203620"

    private const val PREFS = "steam_cloud"
    private const val K_CONNECTED = "connected"
    private const val K_LAST_SYNC = "last_sync"     // last pass that read the page (ms)
    private const val K_RESULT = "result"           // what that pass did, for the Gamehub page
    private const val K_SENT = "sent"               // {fileName: "date|size"} of what Gamehub has
    private const val K_APP = "appid"               // the Steam game last seen playing (0 = none)

    // GeForce NOW writes the last save to Steam Cloud as the session closes: give it a moment.
    private const val AFTER_SESSION_MS = 3 * 60_000L
    private const val PERIODIC_MS = 6 * 3_600_000L
    private const val PAGE_TIMEOUT_MS = 90_000L
    private const val MAX_FILE_BYTES = 16 * 1024 * 1024
    private val SAVE_FILE = Regex("^(character|world)\\d{1,2}\\.ksd$")

    // Rows of the page's file table: [{name, size, date, href}].
    private const val EXTRACT_JS = """(function(){var out=[];document.querySelectorAll('tr').forEach(function(tr){
var a=tr.querySelector('a[href*="filedownload"]');if(!a)return;var cells=[].map.call(tr.querySelectorAll('td'),function(t){return t.textContent.trim();});
var name=cells.filter(function(s){return /\.(ksd|json)$/.test(s);})[0];if(!name)return;var i=cells.indexOf(name);
out.push({name:name,size:cells[i+1]||'',date:cells[i+2]||'',href:a.href});});return JSON.stringify(out);})()"""

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun isConnected(c: Context): Boolean = p(c).getBoolean(K_CONNECTED, false)
    fun setConnected(c: Context, on: Boolean) { p(c).edit().putBoolean(K_CONNECTED, on).apply() }
    fun lastSync(c: Context): Long = p(c).getLong(K_LAST_SYNC, 0L)
    fun lastResult(c: Context): String? = p(c).getString(K_RESULT, null)
    /** The Steam game the last `/now` poll saw being played (0 = none). */
    fun playingAppId(c: Context): Long = p(c).getLong(K_APP, 0L)

    /** Log out of Steam in this app and forget what was sent (the next login sends everything again). */
    fun disconnect(c: Context) {
        p(c).edit().clear().apply()
        CookieManager.getInstance().removeAllCookies(null)
    }

    /** From [SteamSwitch]'s `/now` poll: the Steam game being played (0 = none). */
    fun observe(c: Context, appid: Long) {
        val last = p(c).getLong(K_APP, 0L)
        if (appid == last) return
        p(c).edit().putLong(K_APP, appid).apply()
        if (last == ENSHROUDED_APPID && isConnected(c)) {
            Diag.note("steam cloud: Enshrouded session ended, fetching saves in 3 min", tag = TAG)
            // Then the next steps from Gamehub on the TV for a moment (EnshGuide).
            main.postDelayed({ syncNow(c, "session") { EnshGuide.afterSession(c) } }, AFTER_SESSION_MS)
        }
    }

    /** From [Background]: a pass every few hours, in case a session end was missed. Never mid-game. */
    fun checkIfDue(c: Context) {
        if (!isConnected(c) || !GamehubStore.isLinked(c)) return
        if (p(c).getLong(K_APP, 0L) == ENSHROUDED_APPID) return
        if (System.currentTimeMillis() - lastSync(c) < PERIODIC_MS) return
        syncNow(c, "periodic")
    }

    /** One pass now. [onDone] runs on the main thread with a line for the screen. */
    fun syncNow(context: Context, why: String, onDone: ((String) -> Unit)? = null) {
        val app = context.applicationContext
        main.post { start(app, why, onDone) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun start(app: Context, why: String, onDone: ((String) -> Unit)?) {
        if (running) { onDone?.invoke("Already fetching"); return }
        if (!isConnected(app)) { onDone?.invoke("Not connected to Steam"); return }
        if (!GamehubStore.isLinked(app)) { onDone?.invoke("This Shield is not linked to Gamehub"); return }
        running = true
        CookieManager.getInstance().setAcceptCookie(true)
        val wv = WebView(app) // never attached to a window
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        var finished = false
        fun finish(result: String) {
            if (finished) return
            finished = true
            main.removeCallbacksAndMessages(wv)
            wv.stopLoading()
            wv.destroy()
            running = false
            p(app).edit().putLong(K_LAST_SYNC, System.currentTimeMillis()).putString(K_RESULT, result).apply()
            Diag.note("steam cloud ($why): $result", tag = TAG)
            onDone?.invoke(result)
        }
        main.postAtTime({ finish("Steam's page did not load in time") }, wv, android.os.SystemClock.uptimeMillis() + PAGE_TIMEOUT_MS)
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (finished) return
                if (url.contains("/login")) {
                    setConnected(app, false)
                    finish("Steam logged this Shield out: connect again")
                    return
                }
                // Steam may pass through its token refresh first; wait for the page itself.
                if (!url.contains("remotestorageapp")) return
                view.evaluateJavascript(EXTRACT_JS) { raw ->
                    val files = runCatching { JSONArray(JSONTokener(raw).nextValue() as String) }.getOrNull()
                    if (files == null) { finish("Could not read Steam's page"); return@evaluateJavascript }
                    // The download links are single-use: fetch them now, off the main thread.
                    Thread({
                        val result = try { send(app, files) } catch (t: Throwable) { Log.w(TAG, "send", t); "Failed: ${t.message}" }
                        main.post { finish(result) }
                    }, "steam-cloud").apply { isDaemon = true; start() }
                }
            }
        }
        wv.loadUrl(PAGE)
    }

    /** Downloads the changed save files and sends them to Gamehub. Returns a line for the screen. */
    private fun send(app: Context, files: JSONArray): String {
        val sent = runCatching { JSONObject(p(app).getString(K_SENT, "{}") ?: "{}") }.getOrDefault(JSONObject())
        val changed = (0 until files.length()).map { files.getJSONObject(it) }
            .filter { SAVE_FILE.matches(it.optString("name")) }
            .filter { sent.optString(it.getString("name")) != stamp(it) }
            .sortedBy { if (it.getString("name").startsWith("character")) 0 else 1 }
        if (files.length() == 0) return "No Enshrouded files in Steam Cloud"
        if (changed.isEmpty()) return "Up to date"
        val hub = GamehubClient(GamehubStore.url(app), GamehubStore.token(app))
        var ok = 0
        val failed = ArrayList<String>()
        for (f in changed) {
            val name = f.getString("name")
            try {
                val bytes = download(f.getString("href"))
                hub.putEnshFile(name, bytes)
                sent.put(name, stamp(f))
                p(app).edit().putString(K_SENT, sent.toString()).apply()
                ok++
            } catch (t: Throwable) {
                Log.w(TAG, "file $name", t)
                failed += name
            }
        }
        return if (failed.isEmpty()) "Sent $ok ${if (ok == 1) "file" else "files"} to Gamehub"
        else "Sent $ok, failed ${failed.joinToString()} (tried again next time)"
    }

    private fun stamp(f: JSONObject) = f.optString("date") + "|" + f.optString("size")

    private fun download(href: String): ByteArray {
        val conn = URL(href).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        val status = conn.responseCode
        if (status != 200) throw java.io.IOException("download HTTP $status")
        val out = ByteArrayOutputStream()
        conn.inputStream.use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_FILE_BYTES) throw java.io.IOException("file too large")
            }
        }
        return out.toByteArray()
    }
}
