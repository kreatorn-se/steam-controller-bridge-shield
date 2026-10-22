package com.steamcontroller.android.radial

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Slice icons (game-icons.net, CC BY 3.0, served by the hub as white-on-transparent SVGs at
 * `/icons/gi/<author>/<name>.svg`, public and immutable). The ones the active menus use are
 * fetched once, kept on disk by id, and rendered off the main thread to white bitmaps that the
 * overlay tints (white, or the slice's colour). A missing icon (404, offline, bad file) leaves the
 * slice label-only and is tried again on the next config apply.
 */
object RadialIcons {
    private const val TAG = "RadialIcons"
    private const val MAX_BYTES = 256 * 1024
    /** Rendered size; the overlay draws at most ~48 dp (96 px on the Shield's xhdpi). */
    const val RENDER_PX = 112

    private val bitmaps = ConcurrentHashMap<String, Bitmap>()

    /** The rendered icon, or null (not fetched yet, missing, or label-only). Main-thread safe. */
    fun bitmap(id: String?): Bitmap? = id?.let { bitmaps[it] }

    fun file(c: Context, id: String): File =
        File(File(c.filesDir, "radial-icons"), id.replace('/', '_') + ".svg")

    /**
     * Fetches what is not on disk yet and renders every icon in [ids]; drops renders no longer
     * used. Blocking (network): call on a background thread. Returns how many icons are ready.
     */
    fun prepare(c: Context, baseUrl: String, ids: Set<String>): Int {
        bitmaps.keys.retainAll(ids)
        var ready = 0
        for (id in ids) {
            if (!RadialParser.ICON_ID.matches(id)) continue
            if (bitmaps.containsKey(id)) { ready++; continue }
            val f = file(c, id)
            if (!f.isFile) fetch(baseUrl, id, f)
            if (!f.isFile) continue
            val bmp = render(f)
            if (bmp == null) {
                f.delete() // corrupt: fetch again next time
                continue
            }
            bitmaps[id] = bmp
            ready++
        }
        return ready
    }

    private fun fetch(baseUrl: String, id: String, dest: File) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(baseUrl.trimEnd('/') + "/icons/" + id + ".svg").openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 8_000
                instanceFollowRedirects = false
            }
            val code = conn.responseCode
            if (code != 200) {
                Log.i(TAG, "$id: HTTP $code")
                return
            }
            val bytes = conn.inputStream.use { input ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() > MAX_BYTES) return
                }
                buf.toByteArray()
            }
            val text = String(bytes, Charsets.UTF_8)
            if (!text.contains("<svg")) return
            dest.parentFile?.mkdirs()
            val tmp = File(dest.path + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(dest)) tmp.delete()
        } catch (t: Throwable) {
            Log.i(TAG, "$id: ${t.javaClass.simpleName} ${t.message}")
        } finally {
            conn?.disconnect()
        }
    }

    private fun render(f: File): Bitmap? = try {
        val svg = SVG.getFromString(f.readText())
        val bmp = Bitmap.createBitmap(RENDER_PX, RENDER_PX, Bitmap.Config.ARGB_8888)
        svg.renderToCanvas(Canvas(bmp), RenderOptions.create().viewPort(0f, 0f, RENDER_PX.toFloat(), RENDER_PX.toFloat()))
        bmp
    } catch (t: Throwable) {
        Log.w(TAG, "${f.name}: ${t.message}")
        null
    }
}
