package com.steamcontroller.android.ui.tv

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.LruCache
import android.widget.ImageView
import com.steamcontroller.android.R
import com.steamcontroller.android.hub.GamehubTv
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Image pipeline for the TV: authenticated download → disk cache → decode at the size it is shown
 * → memory LRU with a strict byte budget.
 *
 * - Nothing is decoded larger than needed: inSampleSize gets within 2× of the target and inDensity
 *   scaling does the rest during the decode, so a 4K original costs a screen-sized bitmap.
 * - Nothing is decoded smaller than it is drawn either: a [Spec] is the view's real pixel size at
 *   the 85 % TV density, times its focus scale, and with [Spec.h] the image covers that box the
 *   way CENTER_CROP draws it (a 21:9 capture in a 16:9 card is sized by its height). A source too
 *   small for that (an old low-res preview) is replaced by the [full] image when one is given,
 *   instead of being stretched.
 * - Cache keys are the URL exactly as the hub gives it, `?v=<width>` included: when the hub
 *   replaces a small preview with a sharp one the URL changes, and so does every key.
 * - Three queues so prefetching never starves what is on screen: visible requests on a LIFO pool
 *   (what was asked for last is what is on screen now), originals on one dedicated thread, and
 *   prefetches on one background-priority thread.
 * - Two disk caches: previews/art (cacheDir/img2, 320 MB) and originals (cacheDir/img-full,
 *   200 MB), so a long slideshow of originals never pushes out the thumbnails.
 * - Activity views get HARDWARE bitmaps (graphics memory, not the Java heap); the screensaver
 *   asks for software ARGB_8888 ones (no banding). While the app is not visible the memory
 *   budget shrinks to almost nothing: the bridge's service runs next to GeForce NOW on 3 GB.
 *
 * Public API is main-thread only.
 */
object Img {
    /**
     * How an image is decoded. [w] is the target width in px; [alpha] keeps transparency (logos);
     * [original] routes the work to the originals queue and disk cache; [deep] decodes software
     * bitmaps in ARGB_8888 instead of RGB_565.
     */
    data class Spec(val w: Int, val hw: Boolean = true, val alpha: Boolean = false, val original: Boolean = false, val deep: Boolean = false, val h: Int = 0) {
        fun key(url: String) = url + "|" + w + (if (h > 0) "x$h" else "") + (if (hw) "h" else "s") + (if (alpha) "a" else "") + (if (deep) "d" else "")
    }

    /**
     * The spec for a CENTER_CROP image [wDp] × [hDp] that grows to [focusScale] when focused, in
     * real pixels of [c]'s (scaled) density, so the focused card is still drawn 1:1 or sharper.
     */
    fun cover(c: Context, wDp: Int, hDp: Int, focusScale: Float = 1f): Spec {
        val d = c.resources.displayMetrics.density * focusScale
        return Spec(kotlin.math.ceil(wDp * d).toInt(), h = kotlin.math.ceil(hDp * d).toInt())
    }

    /** As [cover], for a box already in pixels. */
    fun coverPx(wPx: Int, hPx: Int, focusScale: Float = 1f): Spec =
        Spec(kotlin.math.ceil(wPx * focusScale).toInt(), h = kotlin.math.ceil(hPx * focusScale).toInt())

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private lateinit var mem: LruCache<String, Bitmap>
    private var budget = 0

    private class Pending(val callbacks: MutableList<(Bitmap?) -> Unit>, var visible: Boolean)
    private val waiting = HashMap<String, Pending>()
    private val failedAt = HashMap<String, Long>()
    @Volatile private var lastTrim = 0L
    // Striped and never removed: one download per file at a time, however many sizes are wanted.
    private val stripes = Array(32) { Any() }

    private fun threads(name: String, priority: Int) = ThreadFactory { r ->
        Thread({ Process.setThreadPriority(priority); r.run() }, name).apply { isDaemon = true }
    }

    // Visible: LIFO, so fast scrolling loads what is on screen now, not what was there a second ago.
    private val visiblePool: ExecutorService = ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS, object : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable): Boolean = offerFirst(e)
    }, threads("img", Process.THREAD_PRIORITY_DEFAULT)).apply { allowCoreThreadTimeOut(true) }
    // The one picture that matters most right now (the home hero, show mode's current image):
    // its own thread, so a screenful of thumbnails can never queue in front of it.
    private val urgentPool: ExecutorService = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, object : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable): Boolean = offerFirst(e)
    }, threads("img-urgent", Process.THREAD_PRIORITY_DISPLAY)).apply { allowCoreThreadTimeOut(true) }
    private val originalPool: ExecutorService = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingDeque(),
        threads("img-full", Process.THREAD_PRIORITY_DEFAULT)).apply { allowCoreThreadTimeOut(true) }
    private val prefetchPool: ExecutorService = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingDeque(),
        threads("img-prefetch", Process.THREAD_PRIORITY_BACKGROUND)).apply { allowCoreThreadTimeOut(true) }
    /**
     * Sharper copies for thumbnails whose preview is too small ([upgrade]): their own background
     * thread, newest first, at most [UPGRADE_QUEUE] waiting (the oldest is dropped), so they never
     * queue in front of show mode's originals.
     */
    private val upgradePool: ExecutorService = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, object : LinkedBlockingDeque<Runnable>(UPGRADE_QUEUE) {
        override fun offer(e: Runnable): Boolean {
            if (offerFirst(e)) return true
            pollLast()
            return offerFirst(e)
        }
    }, threads("img-up", Process.THREAD_PRIORITY_BACKGROUND)).apply { allowCoreThreadTimeOut(true) }
    private val trimmer: ExecutorService = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingDeque(),
        threads("img-trim", Process.THREAD_PRIORITY_BACKGROUND)).apply { allowCoreThreadTimeOut(true) }

    private const val PREVIEW_MAX = 320L * 1024 * 1024
    private const val PREVIEW_TARGET = 260L * 1024 * 1024
    private const val FULL_MAX = 200L * 1024 * 1024
    private const val FULL_TARGET = 150L * 1024 * 1024
    /** Originals fetched only to sharpen thumbnails: a cache of their own, so they never push out slideshow originals. */
    private const val UP_MAX = 80L * 1024 * 1024
    private const val UP_TARGET = 60L * 1024 * 1024
    private const val UP_DIR = "img-up"
    private const val UPGRADE_QUEUE = 8
    private const val RETRY_MS = 15_000L

    /**
     * The preview cache's folder. 1.4.2 moved it (img → img2) so the low-res Steam thumbnails
     * cached before the hub's sharp previews are dropped once; the old folder is deleted in the
     * background at start.
     */
    private const val PREVIEW_DIR = "img2"
    private val OLD_PREVIEW_DIRS = listOf("img")

    /** A decode this much smaller than its spec (in both directions) is a too-small source. */
    private const val SMALL = 0.75f

    fun init(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        // Hardware bitmaps do not use the heap but still cost graphics memory: one budget for both.
        val heap = am.memoryClass.toLong() * 1024 * 1024
        budget = (heap / 4).coerceIn(24L * 1024 * 1024, 96L * 1024 * 1024).toInt()
        mem = object : LruCache<String, Bitmap>(budget) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
        trimmer.execute {
            for (name in OLD_PREVIEW_DIRS) try { File(app.cacheDir, name).deleteRecursively() } catch (_: Throwable) {}
        }
    }

    /** Width of the UI surface in px (1920 on a Shield, whatever the TV panel is). */
    fun screenWidth(c: Context): Int = c.resources.displayMetrics.widthPixels

    private fun dir(original: Boolean) = File(app.cacheDir, if (original) "img-full" else PREVIEW_DIR).apply { mkdirs() }
    private fun upDir() = File(app.cacheDir, UP_DIR).apply { mkdirs() }
    private fun diskFile(url: String, original: Boolean) = File(dir(original), GamehubTv.sha1(GamehubTv.abs(app, url)))

    fun cached(url: String, spec: Spec): Bitmap? = if (::mem.isInitialized) mem.get(spec.key(url)) else null

    /** Loads [url] at [spec] for something on screen; [cb] runs on the main thread (null on failure). */
    fun load(url: String, spec: Spec, urgent: Boolean = false, cb: (Bitmap?) -> Unit) = request(url, spec, visible = true, urgent = urgent, cb = cb)

    /** Warms the memory cache for something about to be shown, at background priority. */
    fun prefetch(url: String?, spec: Spec) {
        if (url == null || !::mem.isInitialized || cached(url, spec) != null || waiting.containsKey(spec.key(url))) return
        request(url, spec, visible = false) {}
    }

    /** Download only, no decode, at background priority: originals further ahead. */
    fun prefetchDisk(url: String?, original: Boolean = true) {
        if (url == null || !::app.isInitialized) return
        prefetchPool.execute { try { ensureOnDisk(url, original) } catch (_: Throwable) {} }
    }

    private fun request(url: String, spec: Spec, visible: Boolean, urgent: Boolean = false, cb: (Bitmap?) -> Unit) {
        val key = spec.key(url)
        mem.get(key)?.let { cb(it); return }
        val failed = failedAt[key]
        if (failed != null && SystemClock.uptimeMillis() - failed < RETRY_MS) { cb(null); return }
        val p = waiting[key]
        if (p != null) {
            p.callbacks.add(cb)
            // Something on screen now wants what was only being prefetched: do it properly too.
            if ((visible && !p.visible) || urgent) { p.visible = true; submit(url, spec, key, visible = true, urgent = urgent) }
            return
        }
        waiting[key] = Pending(mutableListOf(cb), visible)
        submit(url, spec, key, visible, urgent)
    }

    private fun submit(url: String, spec: Spec, key: String, visible: Boolean, urgent: Boolean = false) {
        val pool = when {
            urgent -> urgentPool
            !visible -> prefetchPool
            spec.original -> originalPool
            else -> visiblePool
        }
        pool.execute {
            // Already done by a twin request: nothing to decode.
            if (mem.get(key) != null) { main.post { finish(key, mem.get(key)) }; return@execute }
            val bmp = try { fetchAndDecode(url, spec) } catch (_: Throwable) { null }
            main.post { finish(key, bmp) }
        }
    }

    /** Smaller than [spec] asks for in both directions: the source itself is too small. */
    internal fun tooSmall(bw: Int, bh: Int, spec: Spec): Boolean =
        bw < spec.w * SMALL && (spec.h <= 0 || bh < spec.h * SMALL)

    private fun finish(key: String, bmp: Bitmap?) {
        val p = waiting.remove(key) ?: return // a twin finished first
        if (bmp != null) { mem.put(key, bmp); failedAt.remove(key) } else failedAt[key] = SystemClock.uptimeMillis()
        for (c in p.callbacks) c(bmp)
    }

    /**
     * Binds [url] to [iv]. A cached bitmap appears immediately; otherwise the view is cleared and
     * the bitmap fades in when it arrives, unless the view was re-bound in the meantime. [onDone]
     * always gets the bitmap the view shows (also when it was already showing it), or null.
     *
     * [full]: the capture's original. When [url] decodes too small for [spec] (an old low-res
     * preview), the original is decoded at the same size on the originals queue and swapped in,
     * rather than the small one being stretched; next time the sharp copy is used straight away.
     */
    fun into(iv: ImageView, url: String?, spec: Spec, fade: Boolean = true, urgent: Boolean = false, full: String? = null, onDone: ((Bitmap?) -> Unit)? = null) {
        if (url == null) {
            (iv.getTag(R.id.img_upgrade) as? Upgrade)?.cancelled = true
            iv.setTag(R.id.img_key, null)
            iv.setImageDrawable(null)
            onDone?.invoke(null)
            return
        }
        val key = spec.key(url)
        if (iv.getTag(R.id.img_key) == key && iv.drawable != null) {
            onDone?.invoke((iv.drawable as? BitmapDrawable)?.bitmap)
            return
        }
        (iv.getTag(R.id.img_upgrade) as? Upgrade)?.let { if (it.key != key) it.cancelled = true }
        iv.setTag(R.id.img_key, key)
        val fullSpec = spec.copy(original = true)
        val hit = (if (full != null && full != url) mem.get(fullSpec.key(full)) else null) ?: mem.get(key)
        if (hit != null) {
            iv.animate().cancel()
            iv.alpha = 1f
            iv.setImageBitmap(hit)
            onDone?.invoke(hit)
            upgrade(iv, key, hit, spec, full, url)
            return
        }
        iv.setImageDrawable(null)
        load(url, spec, urgent) { bmp ->
            if (iv.getTag(R.id.img_key) != key) return@load
            if (bmp != null) {
                iv.setImageBitmap(bmp)
                if (fade) { iv.alpha = 0f; iv.animate().alpha(1f).setDuration(160L).start() }
            }
            onDone?.invoke(bmp)
            if (bmp != null) upgrade(iv, key, bmp, spec, full, url)
        }
    }

    /** One sharper-copy job; [cancelled] once its view is bound to something else. */
    private class Upgrade(val key: String) { @Volatile var cancelled = false }

    /** The preview's pixel width from the hub's `?v=<width>`, when the URL carries it. */
    internal fun previewWidth(url: String): Int? =
        Regex("""[?&]v=(\d{1,6})(?:&|$)""").find(url)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * [shown] is smaller than [spec] needs and the hub does not say its preview is big enough:
     * decode [full] at that size on [upgradePool] (into its own disk cache) and swap it in, unless
     * the view was re-bound before the job started or finished.
     */
    private fun upgrade(iv: ImageView, key: String, shown: Bitmap, spec: Spec, full: String?, url: String) {
        if (full == null || full == url || !tooSmall(shown.width, shown.height, spec)) return
        val v = previewWidth(url)
        if (v != null && !tooSmall(v, Int.MAX_VALUE, spec.copy(h = 0))) return
        val fullKey = spec.copy(original = true).key(full)
        mem.get(fullKey)?.let { b -> if (b.width > shown.width && iv.getTag(R.id.img_key) == key) iv.setImageBitmap(b); return }
        (iv.getTag(R.id.img_upgrade) as? Upgrade)?.cancelled = true
        val job = Upgrade(key)
        iv.setTag(R.id.img_upgrade, job)
        upgradePool.execute {
            if (job.cancelled) return@execute
            val b = try { ensureIn(full, upDir())?.let { f -> decode(f, spec) } } catch (_: Throwable) { null }
            maybeTrim()
            main.post {
                if (b == null) return@post
                mem.put(fullKey, b)
                if (!job.cancelled && iv.getTag(R.id.img_key) == key && b.width > shown.width) iv.setImageBitmap(b)
            }
        }
    }

    /** Drops what [iv] shows so the bitmap can be freed; [into] binds it again later. */
    fun release(iv: ImageView) {
        (iv.getTag(R.id.img_upgrade) as? Upgrade)?.cancelled = true
        iv.animate().cancel()
        iv.setTag(R.id.img_key, null)
        iv.setImageDrawable(null)
    }

    // ── worker side ───────────────────────────────────────────────────────────────────

    private fun ensureOnDisk(url: String, original: Boolean): File? = ensureIn(url, dir(original))

    private fun ensureIn(url: String, d: File): File? {
        val f = File(d, GamehubTv.sha1(GamehubTv.abs(app, url)))
        synchronized(stripes[(f.name.hashCode() and 0x7FFFFFFF) % stripes.size]) {
            if (f.isFile && f.length() > 0) {
                f.setLastModified(System.currentTimeMillis())
                return f
            }
            if (!GamehubTv.download(app, url, f)) return null
        }
        maybeTrim()
        return f
    }

    private fun fetchAndDecode(url: String, spec: Spec): Bitmap? {
        val f = ensureOnDisk(url, spec.original) ?: return null
        return decode(f, spec) ?: run {
            // A truncated or corrupt file: drop it so the next attempt downloads again.
            f.delete()
            null
        }
    }

    /**
     * The width to decode [w] × [h] at for [spec]: the spec's width, or for a cover spec the width
     * at which the image covers the whole box (a wide image in a narrower box is sized by its
     * height). Never wider than the source: a small source is never upscaled here.
     */
    internal fun targetWidth(w: Int, h: Int, spec: Spec): Int {
        var t = spec.w
        if (spec.h > 0 && h > 0) t = maxOf(t, kotlin.math.ceil(spec.h.toDouble() * w / h).toInt())
        return t.coerceAtMost(w).coerceAtLeast(1)
    }

    private fun decode(f: File, spec: Spec): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        val w = bounds.outWidth
        if (w <= 0 || bounds.outHeight <= 0) return null
        val target = targetWidth(w, bounds.outHeight, spec)
        var sample = 1
        while (w / (sample * 2) >= target) sample *= 2
        val sampled = w / sample
        fun opts(config: Bitmap.Config) = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = config
            if (sampled > target * 1.05f) {
                // Never hold more pixels than the view shows.
                inScaled = true
                inDensity = sampled
                inTargetDensity = target
            }
        }
        val soft = if (spec.alpha || spec.deep) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        if (spec.hw && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                BitmapFactory.decodeFile(f.path, opts(Bitmap.Config.HARDWARE))?.let { return it }
            } catch (_: Throwable) {
            }
        }
        return try { BitmapFactory.decodeFile(f.path, opts(soft)) } catch (_: OutOfMemoryError) { null }
    }

    private fun maybeTrim() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTrim < 30_000L) return
        lastTrim = now
        trimmer.execute {
            trimDir(dir(false), PREVIEW_MAX, PREVIEW_TARGET)
            trimDir(dir(true), FULL_MAX, FULL_TARGET)
            trimDir(upDir(), UP_MAX, UP_TARGET)
        }
    }

    private fun trimDir(d: File, max: Long, target: Long) {
        try {
            val files = d.listFiles() ?: return
            var total = files.sumOf { it.length() }
            if (total <= max) return
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= target) break
                total -= f.length()
                f.delete()
            }
        } catch (_: Throwable) {
        }
    }

    // ── memory ────────────────────────────────────────────────────────────────────────

    private var visibleUsers = 0

    /**
     * A screen of ours became visible or went away (the activity's onStart/onStop, the screensaver
     * starting/stopping). With none visible (a game, GeForce NOW) the cache holds almost nothing.
     */
    fun setVisible(visible: Boolean) {
        if (!::mem.isInitialized) return
        visibleUsers = (visibleUsers + if (visible) 1 else -1).coerceAtLeast(0)
        if (visibleUsers > 0) mem.resize(budget) else { mem.evictAll(); mem.resize(4 * 1024 * 1024) }
    }

    /** Memory pressure from the system. */
    fun trimMemory(level: Int) {
        if (!::mem.isInitialized) return
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) mem.evictAll()
        else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) mem.trimToSize(mem.maxSize() / 2)
    }

    /** Unlinked or the token was refused: forget every cached image. */
    fun clearAll() {
        if (!::app.isInitialized) return
        mem.evictAll()
        failedAt.clear()
        trimmer.execute {
            for (d in listOf(dir(false), dir(true), upDir())) try { d.listFiles()?.forEach { it.delete() } } catch (_: Throwable) {}
        }
    }
}
