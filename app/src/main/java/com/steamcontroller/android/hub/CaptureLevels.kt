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
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Turns a fresh capture into the PNG the uploader sends: levels (limited-range 16–235 video
 * expanded to full range) and the capture colour boost, in one pass, then one PNG encode.
 *
 * screencap leaves the capture in the queue as `<id>.raw`, which neither the uploader nor the
 * housekeeping touches. Normally that is the raw framebuffer (`screencap <file>.raw`, [RawFrame]):
 * written in about a second, read straight into a bitmap, no PNG decode. A `screencap -p` PNG
 * (the fallback when the raw frame is in a format we do not take, or too many raws are waiting)
 * goes through the same steps via BitmapFactory. One background thread does this (a 4K frame is a
 * 33 MB bitmap, so never two at once), then the capture appears as `<id>.png` by rename, so the
 * uploader and the preview JPEG only ever see the finished image.
 *
 * Failures: a PNG input is published as captured. A raw frame cannot be (it is not an image
 * file), so it is encoded once more without processing; if that fails too the capture is
 * dropped and logged. A `<id>.lvtry` marker holds the attempt ("full" or "plain") while it runs;
 * if the process dies with it in place (lmkd, the likely cause), the orphan pass does not repeat
 * that attempt: after "full" it encodes plain, after "plain" it drops the capture (a PNG input is
 * published as captured), so one capture can never cause a kill loop.
 * The decision travels with the upload as `levels: {p1, p5, p99, expanded, saturation}` in
 * `<id>.json`.
 */
object CaptureLevels {
    private const val TAG = "CaptureLevels"
    private const val BAND_ROWS = 16
    private const val IO_BUFFER = 256 * 1024
    /** A `.raw` left this long without a job (the process died mid-way) is picked up again. */
    private const val ORPHAN_AGE_MS = 60_000L
    /** GamehubSync skips PNGs younger than this; a published capture should not have to wait. */
    private const val UPLOAD_SETTLE_MS = 2_000L
    private const val ATTEMPT_FULL = "full"
    private const val ATTEMPT_PLAIN = "plain"

    sealed class Outcome {
        /** Encoded to PNG: levels [expanded] or not, the boost of [saturation] % (0: none). */
        data class Encoded(val levels: LevelsMath.Levels, val expanded: Boolean, val saturation: Int) : Outcome()
        /** A PNG input that needs nothing (full range, boost off): published as captured. */
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
     * Queues [raw] (`<id>.raw` in [queueDir]) for processing with a saturation boost of
     * [saturation] percent (0 = off, see GamehubStore.captureSaturation); [screencapMs] is how
     * long screencap took (for the log; -1 unknown). [done] runs on the worker thread after
     * `<id>.png` is in place (true) or could not be published at all (false).
     */
    fun submit(queueDir: File, id: String, raw: File, pictures: File?, saturation: Int, screencapMs: Long, done: (Boolean) -> Unit) {
        submitJob(id, done) { finish(queueDir, id, raw, pictures, saturation.coerceIn(0, 100), screencapMs, plain = false) }
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

    private fun note(line: String) {
        Log.i(TAG, line)
        Diagnostics.record(TAG, line)
    }

    private fun isPngFile(f: File): Boolean = try {
        val head = ByteArray(8)
        val n = f.inputStream().use { it.read(head) }
        n == 8 && RawFrame.isPng(head)
    } catch (_: Throwable) {
        false
    }

    /** A capture that cannot be published: gone, with its meta, so it never blocks the queue. */
    private fun drop(queueDir: File, id: String, raw: File, why: String) {
        note("capture levels $id dropped ($why)")
        raw.delete()
        File(queueDir, "$id.json").delete()
    }

    /**
     * Captures a killed process left behind (called from the sync loop): a `.raw` with no attempt
     * marker is processed again; after an interrupted attempt see the class comment.
     */
    fun resumeOrphans(queueDir: File, saturation: Int, done: (Boolean) -> Unit) {
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
            val attempt = if (marker.exists()) (try { marker.readText().trim() } catch (_: Throwable) { "" }).ifEmpty { ATTEMPT_FULL } else null
            when {
                attempt == null -> {
                    Log.i(TAG, "resuming orphaned capture $id")
                    submit(queueDir, id, raw, null, saturation, -1L, done)
                }
                isPngFile(raw) -> submitJob(id, done) {
                    note("capture levels $id skipped after interrupted attempt -> left as captured")
                    CaptureStore.addLevels(queueDir, id, JSONObject().put("expanded", false).put("saturation", 0).put("error", "interrupted"))
                    val ok = File(queueDir, "$id.png").isFile && raw.delete() || publish(raw, File(queueDir, "$id.png"), raw.lastModified())
                    marker.delete()
                    ok
                }
                attempt == ATTEMPT_PLAIN -> submitJob(id, done) {
                    drop(queueDir, id, raw, "interrupted twice")
                    marker.delete()
                    false
                }
                else -> submitJob(id, done) {
                    note("capture levels $id interrupted -> encoding without processing")
                    finish(queueDir, id, raw, null, 0, -1L, plain = true)
                }
            }
        }
    }

    private fun finish(queueDir: File, id: String, raw: File, pictures: File?, saturation: Int, screencapMs: Long, plain: Boolean): Boolean {
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
        val pngInput = isPngFile(raw)
        try {
            fun attempt(plainPass: Boolean): Outcome {
                try { marker.writeText(if (plainPass) ATTEMPT_PLAIN else ATTEMPT_FULL) } catch (t: Throwable) { Log.w(TAG, "no attempt marker for $id: ${t.message}") }
                return try {
                    processInto(raw, out, if (plainPass) 0 else saturation, plainPass, pngInput)
                } catch (_: OutOfMemoryError) {
                    Outcome.Failed("out of memory")
                } catch (t: Throwable) {
                    Outcome.Failed(t.javaClass.simpleName + (t.message?.let { ": $it" } ?: ""))
                }
            }
            val started = SystemClock.elapsedRealtime()
            var outcome = attempt(plain)
            if (outcome is Outcome.Failed && !pngInput && !plain) {
                // A raw frame is not an image file: without a PNG there is no capture. Once more, plain.
                note("capture levels $id failed (${outcome.reason}) -> encoding without processing")
                outcome = attempt(true)
            }
            val secs = (SystemClock.elapsedRealtime() - started) / 1000.0
            val timing = (if (screencapMs >= 0) "screencap ${fmt(screencapMs / 1000.0)} s, " else "") + "processing ${fmt(secs)} s" +
                (if (pngInput) " (png input)" else "")
            val line = when (outcome) {
                is Outcome.Encoded -> {
                    val sat = if (outcome.saturation > 0) "sat+${outcome.saturation}%" else "sat off"
                    if (outcome.levels.samples == 0L) "capture levels $id -> encoded without processing, $sat; $timing"
                    else "capture levels $id ${if (outcome.expanded) "limited" else "full"} ${fmtLevels(outcome.levels)}" +
                        " -> ${if (outcome.expanded) "expanded 16-235 to 0-255" else "levels unchanged"}, $sat; $timing"
                }
                is Outcome.Unchanged -> "capture levels $id full ${fmtLevels(outcome.levels)} -> unchanged, sat off; $timing"
                is Outcome.Failed -> "capture levels $id failed (${outcome.reason}) -> " +
                    (if (pngInput) "left as captured" else "dropped") + ", sat+$saturation% not applied; $timing"
            }
            note(line)

            if (outcome is Outcome.Failed && !pngInput) {
                drop(queueDir, id, raw, "no PNG could be written")
                return false
            }

            // Into <id>.json before the PNG appears, so the upload carries it.
            CaptureStore.addLevels(queueDir, id, when (outcome) {
                // saturation: the boost actually applied, in percent (0 when off or not applied)
                is Outcome.Encoded -> levelsJson(outcome.levels).put("expanded", outcome.expanded).put("saturation", outcome.saturation)
                is Outcome.Unchanged -> levelsJson(outcome.levels).put("expanded", false).put("saturation", 0)
                is Outcome.Failed -> JSONObject().put("expanded", false).put("saturation", 0).put("error", outcome.reason.take(120))
            })

            val published = if (outcome is Outcome.Encoded) {
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
                moved || (pngInput && publish(raw, png, rawMtime))
            } else {
                out.delete()
                publish(raw, png, rawMtime)
            }
            if (published && pictures != null && outcome is Outcome.Encoded) writeBackPictures(png, pictures)
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
     * Reads [src] (a raw frame, or a PNG when [pngInput]), measures it, applies levels and the
     * boost in one row-batched pass (neither when [plain]) and writes the PNG to [out] (complete
     * before this returns). Holds [CaptureStore.decodeLock] throughout, so the uploader's preview
     * decode never runs alongside it.
     */
    private fun processInto(src: File, out: File, saturation: Int, plain: Boolean, pngInput: Boolean): Outcome {
        return synchronized(CaptureStore.decodeLock) {
            if (pngInput) processPngLocked(src, out, if (plain) 0 else saturation, plain) else processRawLocked(src, out, saturation, plain)
        }
    }

    /** No samples: a plain pass, nothing measured. */
    private val NOT_MEASURED = LevelsMath.Levels(-1, -1, -1, 0)

    private fun processRawLocked(src: File, out: File, saturation: Int, plain: Boolean): Outcome {
        val hdr = when (val c = RawFrame.check(src)) {
            is RawFrame.Check.Ok -> c.header
            is RawFrame.Check.Bad -> return Outcome.Failed("raw frame: ${c.reason}")
        }
        val w = hdr.width
        val h = hdr.height
        val rowBytes = hdr.rowBytes
        return RandomAccessFile(src, "r").use { raf ->
            val bytes = ByteArray(rowBytes * BAND_ROWS)
            val px = IntArray(w * BAND_ROWS)
            // Measure first, from every 8th row (an eighth of the file), so the one pass below
            // can apply the decision.
            val levels = if (plain) NOT_MEASURED else {
                val hist = IntArray(256)
                var y = 0
                while (y < h) {
                    raf.seek(hdr.offset + y.toLong() * rowBytes)
                    raf.readFully(bytes, 0, rowBytes)
                    RawFrame.toArgb(bytes, 0, px, 0, w)
                    LevelsMath.sampleRow(px, w, hist)
                    y += LevelsMath.SAMPLE_STEP
                }
                LevelsMath.measure(hist)
            }
            val expand = !plain && levels.limited
            val sat = if (plain) 0 else saturation
            val lut = if (expand) LevelsMath.EXPAND_LUT else null

            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                raf.seek(hdr.offset.toLong())
                var alphaAnd = 0xFF
                var y0 = 0
                while (y0 < h) {
                    val rows = minOf(BAND_ROWS, h - y0)
                    raf.readFully(bytes, 0, rows * rowBytes)
                    RawFrame.toArgb(bytes, 0, px, 0, w * rows)
                    alphaAnd = alphaAnd and LevelsMath.process(px, w * rows, lut, sat)
                    bmp.setPixels(px, 0, w, 0, y0, w, rows)
                    y0 += rows
                }
                encode(bmp, alphaAnd, out)?.let { return it }
                return Outcome.Encoded(levels, expand, sat)
            } catch (t: Throwable) {
                out.delete()
                throw t
            } finally {
                bmp.recycle()
            }
        }
    }

    /** Writes [bmp] as PNG to [out]; null on success, else the failure. */
    private fun encode(bmp: Bitmap, alphaAnd: Int, out: File): Outcome.Failed? {
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
        return null
    }

    private fun processPngLocked(src: File, out: File, saturation: Int, plain: Boolean): Outcome {
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
            if (plain) return Outcome.Unchanged(NOT_MEASURED)

            val buf = IntArray(w * BAND_ROWS)
            val hist = IntArray(256)
            var y = 0
            while (y < h) {
                bmp.getPixels(buf, 0, w, 0, y, w, 1)
                LevelsMath.sampleRow(buf, w, hist)
                y += LevelsMath.SAMPLE_STEP
            }
            val levels = LevelsMath.measure(hist)
            val expand = levels.limited
            if (!expand && saturation <= 0) return Outcome.Unchanged(levels)

            val lut = if (expand) LevelsMath.EXPAND_LUT else null
            var alphaAnd = 0xFF
            var y0 = 0
            while (y0 < h) {
                val rows = minOf(BAND_ROWS, h - y0)
                bmp.getPixels(buf, 0, w, 0, y0, w, rows)
                alphaAnd = alphaAnd and LevelsMath.process(buf, w * rows, lut, saturation)
                bmp.setPixels(buf, 0, w, 0, y0, w, rows)
                y0 += rows
            }
            encode(bmp, alphaAnd, out)?.let { return it }
            return Outcome.Encoded(levels, expand, saturation)
        } catch (t: Throwable) {
            out.delete()
            throw t
        } finally {
            bmp.recycle()
        }
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)

    private fun fmtLevels(l: LevelsMath.Levels) = "p1=${l.p1} p5=${l.p5} p99=${l.p99}"

    private fun levelsJson(l: LevelsMath.Levels) = JSONObject().put("p1", l.p1).put("p5", l.p5).put("p99", l.p99)
}
