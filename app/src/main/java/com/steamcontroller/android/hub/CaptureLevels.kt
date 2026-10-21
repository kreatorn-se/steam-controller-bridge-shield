package com.steamcontroller.android.hub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.steamcontroller.android.service.Diagnostics
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
 */
object CaptureLevels {
    private const val TAG = "CaptureLevels"
    private const val BAND_ROWS = 16
    private const val IO_BUFFER = 256 * 1024
    /** A `.raw` left this long without a job (the process died mid-way) is picked up again. */
    private const val ORPHAN_AGE_MS = 60_000L

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
        if (!inFlight.add(id)) return
        worker.execute {
            var ok = false
            try {
                ok = finish(queueDir, id, raw, pictures)
            } catch (t: Throwable) {
                Log.e(TAG, "levels job for $id threw: ${t.message}")
            } finally {
                inFlight.remove(id)
            }
            try { done(ok) } catch (t: Throwable) { Log.w(TAG, "levels callback threw: ${t.message}") }
        }
    }

    /** Re-queues `.raw` captures a killed process left behind (called from the sync loop). */
    fun resumeOrphans(queueDir: File, done: (Boolean) -> Unit) {
        val now = System.currentTimeMillis()
        queueDir.listFiles()?.forEach { f ->
            if (!f.isFile || !f.name.endsWith(CaptureStore.RAW_SUFFIX)) return@forEach
            val id = f.name.removeSuffix(CaptureStore.RAW_SUFFIX)
            if (id in inFlight || now - f.lastModified() < ORPHAN_AGE_MS) return@forEach
            Log.i(TAG, "resuming orphaned capture $id")
            submit(queueDir, id, f, null, done)
        }
    }

    private fun finish(queueDir: File, id: String, raw: File, pictures: File?): Boolean {
        if (File(queueDir, "$id.png").isFile) {
            // Published before a crash took the process; only the original is left to drop.
            raw.delete()
            return true
        }
        if (!raw.isFile) return false
        val out = File(queueDir, "${CaptureStore.TMP_PREFIX}${id}_lv.png")
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

        val png = File(queueDir, "$id.png")
        val published = if (outcome is Outcome.Expanded) {
            // Publish first, drop the original after: a crash in between leaves both, and the
            // orphan pass then finds png present and only cleans up.
            val moved = out.renameTo(png)
            if (moved) raw.delete() else Log.w(TAG, "could not publish processed $id")
            moved || publishRaw(raw, png)
        } else {
            out.delete()
            publishRaw(raw, png)
        }
        if (published && pictures != null && outcome is Outcome.Expanded) writeBackPictures(png, pictures)
        return published
    }

    private fun publishRaw(raw: File, png: File): Boolean {
        if (raw.renameTo(png)) return true
        return try {
            raw.copyTo(png, overwrite = true)
            raw.delete()
            true
        } catch (t: Throwable) {
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

    /** Decodes once, measures, and if limited writes the expanded PNG to [out] (atomically complete). */
    private fun expandInto(src: File, out: File): Outcome {
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
            var y0 = 0
            while (y0 < h) {
                val rows = minOf(BAND_ROWS, h - y0)
                bmp.getPixels(buf, 0, w, 0, y0, w, rows)
                LevelsMath.applyLut(buf, w * rows, lut)
                bmp.setPixels(buf, 0, w, 0, y0, w, rows)
                y0 += rows
            }

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
