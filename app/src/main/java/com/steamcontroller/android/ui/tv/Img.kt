package com.steamcontroller.android.ui.tv

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.widget.ImageView
import com.steamcontroller.android.R
import com.steamcontroller.android.hub.GamehubTv
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Image pipeline for the TV: authenticated download → disk cache (cacheDir/img, LRU by access
 * time) → decode at the size it is shown → memory LRU with a strict byte budget.
 *
 * A 4K original is ~33 MB as an ARGB bitmap, so nothing is ever decoded larger than needed:
 * inSampleSize gets within 2× of the target and inDensity scaling does the rest during the
 * decode. Activity views get HARDWARE bitmaps (pixels live in graphics memory, not on the Java
 * heap); the screensaver asks for software ones. Requests are newest-first (LIFO) so fast
 * scrolling loads what is on screen now, not what was there a second ago.
 *
 * Public API is main-thread only.
 */
object Img {
    /** How an image is decoded. [w] is the target width in px; [alpha] keeps transparency (logos). */
    data class Spec(val w: Int, val hw: Boolean = true, val alpha: Boolean = false) {
        fun key(url: String) = url + "|" + w + (if (hw) "h" else "s") + (if (alpha) "a" else "")
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private lateinit var mem: LruCache<String, Bitmap>
    private val waiting = HashMap<String, MutableList<(Bitmap?) -> Unit>>()
    private val failedAt = HashMap<String, Long>()
    @Volatile private var lastTrim = 0L
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    // LIFO: the most recent request (what is on screen now) goes first.
    private val pool = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, object : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable): Boolean = offerFirst(e)
    }).apply { allowCoreThreadTimeOut(true) }
    private val trimmer = Executors.newSingleThreadExecutor()

    private const val DISK_MAX = 420L * 1024 * 1024
    private const val DISK_TARGET = 320L * 1024 * 1024
    private const val RETRY_MS = 15_000L

    fun init(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        // Hardware bitmaps do not use the heap but still cost graphics memory: one budget for both.
        val heap = am.memoryClass.toLong() * 1024 * 1024
        val budget = (heap / 4).coerceIn(24L * 1024 * 1024, 112L * 1024 * 1024).toInt()
        mem = object : LruCache<String, Bitmap>(budget) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
    }

    private fun diskDir() = File(app.cacheDir, "img").apply { mkdirs() }
    private fun diskFile(url: String) = File(diskDir(), GamehubTv.sha1(GamehubTv.abs(app, url)))

    fun cached(url: String, spec: Spec): Bitmap? = if (::mem.isInitialized) mem.get(spec.key(url)) else null

    /** Loads [url] at [spec]; [cb] runs on the main thread (null on failure). Requests are shared. */
    fun load(url: String, spec: Spec, cb: (Bitmap?) -> Unit) {
        val key = spec.key(url)
        mem.get(key)?.let { cb(it); return }
        val failed = failedAt[key]
        if (failed != null && SystemClock.uptimeMillis() - failed < RETRY_MS) { cb(null); return }
        val list = waiting[key]
        if (list != null) { list.add(cb); return }
        waiting[key] = mutableListOf(cb)
        pool.execute {
            val bmp = try { fetchAndDecode(url, spec) } catch (_: Throwable) { null }
            main.post {
                if (bmp != null) { mem.put(key, bmp); failedAt.remove(key) } else failedAt[key] = SystemClock.uptimeMillis()
                waiting.remove(key)?.forEach { it(bmp) }
            }
        }
    }

    /** Warm the memory cache (and disk) for something about to be shown. */
    fun prefetch(url: String?, spec: Spec) {
        if (url == null || cached(url, spec) != null || waiting.containsKey(spec.key(url))) return
        load(url, spec) {}
    }

    /** Download only, no decode: for originals further ahead. */
    fun prefetchDisk(url: String?) {
        if (url == null) return
        pool.execute { try { ensureOnDisk(url) } catch (_: Throwable) {} }
    }

    /**
     * Binds [url] to [iv]. A cached bitmap appears immediately; otherwise the view is cleared and
     * the bitmap fades in when it arrives, unless the view was re-bound in the meantime.
     */
    fun into(iv: ImageView, url: String?, spec: Spec, fade: Boolean = true, onDone: ((Bitmap?) -> Unit)? = null) {
        if (url == null) {
            iv.setTag(R.id.img_key, null)
            iv.setImageDrawable(null)
            onDone?.invoke(null)
            return
        }
        val key = spec.key(url)
        if (iv.getTag(R.id.img_key) == key && iv.drawable != null) { onDone?.invoke(null); return }
        iv.setTag(R.id.img_key, key)
        val hit = mem.get(key)
        if (hit != null) {
            iv.animate().cancel()
            iv.alpha = 1f
            iv.setImageBitmap(hit)
            onDone?.invoke(hit)
            return
        }
        iv.setImageDrawable(null)
        load(url, spec) { bmp ->
            if (iv.getTag(R.id.img_key) != key) return@load
            if (bmp != null) {
                iv.setImageBitmap(bmp)
                if (fade) { iv.alpha = 0f; iv.animate().alpha(1f).setDuration(160L).start() }
            }
            onDone?.invoke(bmp)
        }
    }

    // ── worker side ───────────────────────────────────────────────────────────────────

    private fun ensureOnDisk(url: String): File? {
        val f = diskFile(url)
        // One download per file, however many sizes of it are being decoded.
        val lock = locks.getOrPut(f.name) { Any() }
        synchronized(lock) {
            try {
                if (f.isFile && f.length() > 0) {
                    f.setLastModified(System.currentTimeMillis())
                    return f
                }
                if (!GamehubTv.download(app, url, f)) return null
            } finally {
                locks.remove(f.name, lock)
            }
        }
        maybeTrim()
        return f
    }

    private fun fetchAndDecode(url: String, spec: Spec): Bitmap? {
        val f = ensureOnDisk(url) ?: return null
        return decode(f, spec) ?: run {
            // A truncated or corrupt file: drop it so the next attempt downloads again.
            f.delete()
            null
        }
    }

    private fun decode(f: File, spec: Spec): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        val w = bounds.outWidth
        if (w <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (w / (sample * 2) >= spec.w) sample *= 2
        val sampled = w / sample
        fun opts(config: Bitmap.Config) = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = config
            if (sampled > spec.w * 1.1f) {
                inScaled = true
                inDensity = sampled
                inTargetDensity = spec.w
            }
        }
        val soft = if (spec.alpha) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
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
            try {
                val files = diskDir().listFiles() ?: return@execute
                var total = files.sumOf { it.length() }
                if (total <= DISK_MAX) return@execute
                for (f in files.sortedBy { it.lastModified() }) {
                    if (total <= DISK_TARGET) break
                    total -= f.length()
                    f.delete()
                }
            } catch (_: Throwable) {
            }
        }
    }

    /** Memory pressure from the system: keep only what is on screen. */
    fun trimMemory(level: Int) {
        if (!::mem.isInitialized) return
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE) mem.evictAll()
        else if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) mem.trimToSize(mem.maxSize() / 2)
    }
}
