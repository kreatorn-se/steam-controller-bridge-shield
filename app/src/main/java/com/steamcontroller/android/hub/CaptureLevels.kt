package com.steamcontroller.android.hub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.steamcontroller.android.service.Diagnostics
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Expands limited-range (16–235) captures to full range before anything else sees them.
 *
 * screencap leaves a capture in the queue as `<id>.raw`, which neither the uploader nor the
 * housekeeping touches. One background thread (captures are 33 MB bitmaps at 4K, so never two at
 * once) decodes it, measures it on a coarse grid ([LevelsMath]) and, if it is limited range,
 * expands the levels and re-encodes it to a temporary file. Only then does the capture appear as
 * `<id>.png`, by rename, so the uploader and the preview JPEG only ever see the finished image.
 * Anything that goes wrong (decode failure, out of memory, a failed encode) publishes the file
 * exactly as screencap wrote it.
 *
 * A `<id>.lvtry` marker exists while a capture is being decoded. If the process dies with it in
 * place (lmkd killing the app for the 33 MB bitmap is the likely cause), the orphan pass publishes
 * that capture as captured instead of trying again, so one capture can never cause a kill loop.
 * The decision travels with the upload as `levels: {p1, p99, expanded}` in `<id>.json`.
 */
object CaptureLevels {
    private const val TAG = "CaptureLevels"
    private const val BAND_ROWS = 16
    private const val IO_BUFFER = 256 * 1024
    /** A `.raw` left this long without a job (the process died mid-way) is picked up again. */
    private const val ORPHAN_AGE_MS = 60_000L
    /** GamehubSync skips PNGs younger than this; a published capture should not have to wait. */
    private const val UPLOAD_SETTLE_MS = 2_000L

    sealed class Outcome {
        data class Expanded(val levels: LevelsMath.Levels) : Outcome()
        data class Unchanged(val levels: LevelsMath.Levels) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread({
            // Below the stream decoder and the UI: this is batch work and may take seconds.
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "capture-levels").apply { isDaemon = true }
    }

    /**
     * Queues [raw] (`<id>.raw` in [queueDir]) for processing; [done] runs on the worker thread
     * after `<id>.png` is in place (true) or could not be published at all (false).
     */
    fun submit(queueDir: File, id: String, raw: File, pictures: File? = null, done: (Boolean) -> Unit) {
        submitJob(id, done) { finish(queueDir, id, raw, pictures) }
    }

    private fun submitJob(id: String, done: (Boolean) -> Unit, job: () -> Boolean) {
        if (!inFlight.add(id)) return
        worker.execute {
            var ok = false
            try {
                ok = job()
            } catch (t: Throwable) {
                Log.e(TAG, "levels job for $id threw: ${t.message}")
            } finally {
                inFlight.remove(id)
            }
            try { done(ok) } catch (t: Throwable) { Log.w(TAG, "levels callback threw: ${t.message}") }
        }
    }

    /**
     * Captures a killed process left behind (called from the sync loop): a `.raw` with no attempt
     * marker is processed again; one whose attempt was interrupted is published as captured.
     */
    fun resumeOrphans(queueDir: File, done: (Boolean) -> Unit) {
        val now = System.currentTimeMillis()
        val files = queueDir.listFiles() ?: return
        val raws = files.filter { it.isFile && it.name.endsWith(CaptureStore.RAW_SUFFIX) }
            .associateBy { it.name.removeSuffix(CaptureStore.RAW_SUFFIX) }
        for (f in files) {
            // A marker whose capture is gone (published, then the process died): just litter.
            if (f.name.endsWith(CaptureStore.TRY_SUFFIX)) {
                val id = f.name.removeSuffix(CaptureStore.TRY_SUFFIX)
                if (id !in raws && id !in inFlight) f.delete()
            }
        }
        for ((id, raw) in raws) {
            if (id in inFlight || now - raw.lastModified() < ORPHAN_AGE_MS) continue
            val marker = File(queueDir, "$id${CaptureStore.TRY_SUFFIX}")
            if (marker.exists()) {
                submitJob(id, done) {
                    val line = "capture levels $id skipped after interrupted attempt -> left as captured"
                    Log.w(TAG, line)
                    Diagnostics.record(TAG, line)
                    CaptureStore.addLevels(queueDir, id, JSONObject().put("expanded", false).put("error", "interrupted"))
                    val ok = File(queueDir, "$id.png").isFile && raw.delete() || publish(raw, File(queueDir, "$id.png"), raw.lastModified())
                    marker.delete()
                    ok
                }
            } else {
                Log.i(TAG, "resuming orphaned capture $id")
                submit(queueDir, id, raw, null, done)
            }
        }
    }

    private fun finish(queueDir: File, id: String, raw: File, pictures: File?): Boolean {
        val png = File(queueDir, "$id.png")
        if (png.isFile) {
            // Published before a crash took the process; only the original is left to drop.
            raw.delete()
            File(queueDir, "$id${CaptureStore.TRY_SUFFIX}").delete()
            return true
        }
        if (!raw.isFile) return false
        val rawMtime = raw.lastModified()
        val marker = File(queueDir, "$id${CaptureStore.TRY_SUFFIX}")
        val out = File(queueDir, "${CaptureStore.TMP_PREFIX}${id}_lv.png")
        try {
            try { marker.writeText(System.currentTimeMillis().toString()) } catch (t: Throwable) { Log.w(TAG, "no attempt marker for $id: ${t.message}") }
            val started = SystemClock.elapsedRealtime()
            val outcome = try {
                expandInto(raw, out)
            } catch (_: OutOfMemoryError) {
                Outcome.Failed("out of memory")
            } catch (t: Throwable) {
                Outcome.Failed(t.javaClass.simpleName + (t.message?.let { ": $it" } ?: ""))
            }
            val secs = (SystemClock.elapsedRealtime() - started) / 1000.0
            val line = when (outcome) {
                is Outcome.Expanded -> "capture levels $id limited p1=${outcome.levels.p1} p99=${outcome.levels.p99}" +
                    " -> expanded 16-235 to 0-255 in ${fmt(secs)} s"
                is Outcome.Unchanged -> "capture levels $id full p1=${outcome.levels.p1} p99=${outcome.levels.p99}" +
                    " -> unchanged (${fmt(secs)} s)"
                is Outcome.Failed -> "capture levels $id failed (${outcome.reason}) -> left as captured"
            }
            Log.i(TAG, line)
            Diagnostics.record(TAG, line)

            // Into <id>.json before the PNG appears, so the upload carries it.
            CaptureStore.addLevels(queueDir, id, when (outcome) {
                is Outcome.Expanded -> JSONObject().put("p1", outcome.levels.p1).put("p99", outcome.levels.p99).put("expanded", true)
                is Outcome.Unchanged -> JSONObject().put("p1", outcome.levels.p1).put("p99", outcome.levels.p99).put("expanded", false)
                is Outcome.Failed -> JSONObject().put("expanded", false).put("error", outcome.reason.take(120))
            })

            val published = if (outcome is Outcome.Expanded) {
                // Publish first, drop the original after: a crash in between leaves both, and the
                // orphan pass then finds png present and only cleans up.
                val moved = out.renameTo(png)
                if (moved) {
                    settle(png, rawMtime)
                    raw.delete()
                } else {
                    Log.w(TAG, "could not publish processed $id")
                    out.delete()
                }
                moved || publish(raw, png, rawMtime)
            } else {
                out.delete()
                publish(raw, png, rawMtime)
            }
            if (published && pictures != null && outcome is Outcome.Expanded) writeBackPictures(png, pictures)
            return published
        } finally {
            marker.delete()
        }
    }

    /**
     * The uploader skips PNGs younger than 2 s (screencap may still be writing). This one is
     * complete: give it the capture's own time, or, where the file system will not take that,
     * wait out the rest of those 2 s so the kick that follows is not wasted.
     */
    private fun settle(png: File, mtime: Long) {
        val age = System.currentTimeMillis() - png.lastModified()
        if (age >= UPLOAD_SETTLE_MS) return
        if (mtime > 0 && png.setLastModified(mtime) && System.currentTimeMillis() - png.lastModified() >= UPLOAD_SETTLE_MS) return
        val wait = UPLOAD_SETTLE_MS + 100 - (System.currentTimeMillis() - png.lastModified())
        if (wait > 0) try { Thread.sleep(wait.coerceAtMost(UPLOAD_SETTLE_MS + 100)) } catch (_: InterruptedException) { }
    }

    /** Publishes the file as screencap wrote it: rename, or copy via a temp name and rename. */
    private fun publish(raw: File, png: File, rawMtime: Long): Boolean {
        if (raw.renameTo(png)) {
            settle(png, rawMtime)
            return true
        }
        val tmp = File(png.parentFile, "${CaptureStore.TMP_PREFIX}${png.name}")
        return try {
            raw.copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(png)) throw IOException("rename failed")
            settle(png, rawMtime)
            raw.delete()
            true
        } catch (t: Throwable) {
            // Never leave a truncated PNG where the uploader (or the orphan pass) would trust it.
            tmp.delete()
            Log.w(TAG, "could not publish ${raw.name}: ${t.message}")
            false
        }
    }

    /**
     * The Pictures fallback keeps a copy in Pictures/Screenshots; replace it with the corrected
     * image when the app may write there (the shell user owns the file, so often it may not).
     */
    private fun writeBackPictures(png: File, pictures: File) {
        val note = try {
            png.inputStream().use { input ->
                FileOutputStream(pictures, false).use { input.copyTo(it, IO_BUFFER) }
            }
            "capture levels ${pictures.name}: Pictures copy updated"
        } catch (t: Throwable) {
            "capture levels ${pictures.name}: Pictures copy kept as captured (${t.javaClass.simpleName})"
        }
        Log.i(TAG, note)
        Diagnostics.record(TAG, note)
    }

    /**
     * Decodes once, measures, and if limited writes the expanded PNG to [out] (complete before
     * this returns). Holds [CaptureStore.decodeLock] throughout, so the uploader's preview decode
     * never runs alongside it.
     */
    private fun expandInto(src: File, out: File): Outcome {
        return synchronized(CaptureStore.decodeLock) { expandLocked(src, out) }
    }

    private fun expandLocked(src: File, out: File): Outcome {
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(src.path, opts) ?: return Outcome.Failed("decode failed")
        try {
            val w = bmp.width
            val h = bmp.height
            if (w <= 0 || h <= 0) return Outcome.Failed("empty image")
            if (!bmp.isMutable) return Outcome.Failed("bitmap not mutable")

            val buf = IntArray(w * BAND_ROWS)
            val hist = IntArray(256)
            var y = 0
            while (y < h) {
                bmp.getPixels(buf, 0, w, 0, y, w, 1)
                LevelsMath.sampleRow(buf, w, hist)
                y += LevelsMath.SAMPLE_STEP
            }
            val levels = LevelsMath.measure(hist)
            if (!levels.limited) return Outcome.Unchanged(levels)

            val lut = LevelsMath.EXPAND_LUT
            var alphaAnd = 0xFF
            var y0 = 0
            while (y0 < h) {
                val rows = minOf(BAND_ROWS, h - y0)
                bmp.getPixels(buf, 0, w, 0, y0, w, rows)
                alphaAnd = alphaAnd and LevelsMath.applyLut(buf, w * rows, lut)
                bmp.setPixels(buf, 0, w, 0, y0, w, rows)
                y0 += rows
            }
            // screencap frames are opaque: an RGB PNG is a quarter smaller and quicker to encode.
            if (alphaAnd == 0xFF) bmp.setHasAlpha(false)

            out.delete()
            FileOutputStream(out).use { fos ->
                val bos = BufferedOutputStream(fos, IO_BUFFER)
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)) throw IOException("PNG encode failed")
                bos.flush()
                fos.fd.sync()
            }
            if (!CaptureStore.isCompletePng(out)) {
                out.delete()
                return Outcome.Failed("re-encoded PNG incomplete")
            }
            return Outcome.Expanded(levels)
        } catch (t: Throwable) {
            out.delete()
            throw t
        } finally {
            bmp.recycle()
        }
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)
}
