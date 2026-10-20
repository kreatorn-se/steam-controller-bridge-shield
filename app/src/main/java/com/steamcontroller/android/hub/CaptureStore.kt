package com.steamcontroller.android.hub

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The upload queue: captures waiting to go to Gamehub live in the app's own external files
 * directory as `<id>.png` + `<id>.json` (capture time, timezone, foreground app). Files are
 * deleted only after the hub has confirmed the upload, so nothing is lost while offline.
 */
object CaptureStore {
    const val TMP_PREFIX = "tmp_"
    private const val PREVIEW_MAX_WIDTH = 1600
    private val IEND = byteArrayOf(0x49, 0x45, 0x4E, 0x44) // "IEND"

    /** True when [f] ends with a PNG IEND chunk, i.e. screencap finished writing it. */
    fun isCompletePng(f: File): Boolean = try {
        if (f.length() < 64) {
            false
        } else {
            java.io.RandomAccessFile(f, "r").use { raf ->
                val tail = ByteArray(12)
                raf.seek(f.length() - 12)
                raf.readFully(tail)
                tail.copyOfRange(4, 8).contentEquals(IEND)
            }
        }
    } catch (_: Throwable) {
        false
    }
    private const val PREVIEW_QUALITY = 82

    fun dir(context: Context): File? = context.getExternalFilesDir("captures")?.also { it.mkdirs() }

    /** A fresh capture id, also the file name stem: cap_20261020_145307_123 */
    fun newId(now: Long = System.currentTimeMillis()): String =
        "cap_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(now))

    fun writeMeta(context: Context, id: String, capturedAt: Long, fgPackage: String?) {
        val d = dir(context) ?: return
        val offsetMin = TimeZone.getDefault().getOffset(capturedAt) / 60_000
        val o = JSONObject()
            .put("clientId", id)
            .put("capturedAt", capturedAt)
            .put("tzOffsetMin", offsetMin)
        if (fgPackage != null) o.put("fgPackage", fgPackage)
        File(d, "$id.json").writeText(o.toString())
    }

    data class Pending(val id: String, val png: File, val meta: JSONObject)

    fun pending(context: Context): List<Pending> {
        val d = dir(context) ?: return emptyList()
        val now = System.currentTimeMillis()
        // Housekeeping: temp files from a capture that never finished, and truncated PNGs left by
        // older versions (which timed out mid-encode), are parked instead of uploaded broken.
        d.listFiles()?.forEach { f ->
            val stale = now - f.lastModified() > 5 * 60_000
            if (f.name.startsWith(TMP_PREFIX) && stale) f.delete()
            else if (f.name.endsWith(".png") && !f.name.startsWith(TMP_PREFIX) && stale && !isCompletePng(f)) {
                f.renameTo(File(d, f.name + ".incomplete"))
            }
        }
        return d.listFiles { f: java.io.File ->
            f.isFile && f.name.endsWith(".png") && !f.name.startsWith(TMP_PREFIX) && f.length() > 0
        }
            ?.sortedBy { it.name }
            ?.map { png ->
                val id = png.name.removeSuffix(".png")
                val metaFile = File(d, "$id.json")
                val meta = try {
                    JSONObject(metaFile.readText())
                } catch (_: Exception) {
                    JSONObject().put("clientId", id).put("capturedAt", png.lastModified())
                }
                Pending(id, png, meta)
            } ?: emptyList()
    }

    fun pendingCount(context: Context): Int =
        dir(context)?.listFiles { f: java.io.File -> f.name.endsWith(".png") && !f.name.startsWith(TMP_PREFIX) }?.size ?: 0

    /** Moves a capture the hub refused out of the queue (kept as .rejected for inspection). */
    fun reject(context: Context, id: String) {
        val d = dir(context) ?: return
        File(d, "$id.png").renameTo(File(d, "$id.png.rejected"))
        File(d, "$id.json").delete()
    }

    fun remove(context: Context, id: String) {
        val d = dir(context) ?: return
        File(d, "$id.png").delete()
        File(d, "$id.json").delete()
    }

    /**
     * Downscaled JPEG used by the hub for thumbnails and AI naming, plus the original's size.
     * Decodes with a power-of-two sample size first so a 4K PNG never sits in memory at full size.
     */
    fun preview(png: File): Triple<ByteArray, Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(png.path, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / (sample * 2) >= PREVIEW_MAX_WIDTH) sample *= 2
        val bmp = BitmapFactory.decodeFile(png.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scaled = if (bmp.width > PREVIEW_MAX_WIDTH) {
            val sh = (bmp.height.toLong() * PREVIEW_MAX_WIDTH / bmp.width).toInt()
            Bitmap.createScaledBitmap(bmp, PREVIEW_MAX_WIDTH, sh, true).also { if (it !== bmp) bmp.recycle() }
        } else {
            bmp
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, PREVIEW_QUALITY, out)
        scaled.recycle()
        return Triple(out.toByteArray(), w, h)
    }
}
