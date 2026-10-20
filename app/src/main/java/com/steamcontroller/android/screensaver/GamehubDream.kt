package com.steamcontroller.android.screensaver

import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.steamcontroller.android.R
import com.steamcontroller.android.hub.GamehubStore
import com.steamcontroller.android.hub.GamehubTv
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.Type
import com.steamcontroller.android.ui.tv.Fmt
import com.steamcontroller.android.ui.tv.Img
import java.util.concurrent.Executors

/**
 * Gamehub as the Shield's screensaver ("daydream"): a calm crossfade through the collection chosen
 * in Gamehub (GET /api/device/v1/screensaver), falling back to favourites, then to whatever was
 * cached last time when the hub cannot be reached. A small caption in a corner; nothing else.
 *
 * Enabled on the Shield under Settings → Device Preferences → Screen saver → Screen saver → Gamehub.
 */
class GamehubDream : DreamService() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var a: ImageView
    private lateinit var b: ImageView
    private lateinit var front: ImageView
    private lateinit var caption: LinearLayout
    private lateinit var capTitle: TextView
    private lateinit var capMeta: TextView
    private lateinit var idle: ImageView
    private lateinit var shade: View
    private var drift: android.animation.Animator? = null
    private var items: List<GamehubTv.Shot> = emptyList()
    private var index = -1
    private var intervalMs = 12_000L
    private var running = false
    private var w = 1920
    private var h = 1080

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        Img.init(this)
        w = resources.displayMetrics.widthPixels
        h = resources.displayMetrics.heightPixels
        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF000000.toInt())
        a = ImageView(this)
        b = ImageView(this)
        for (iv in listOf(a, b)) {
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.alpha = 0f
            root.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        front = a
        // Corner caption over a soft shadow, at the overscan-safe margin.
        val d = resources.displayMetrics.density
        shade = View(this)
        shade.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0x99000000.toInt(), 0x00000000))
        root.addView(shade, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (h * 0.28f).toInt(), Gravity.BOTTOM))
        caption = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; alpha = 0f }
        capTitle = TextView(this).apply {
            typeface = Type.semibold(this@GamehubDream)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(C.text)
            letterSpacing = -0.01f
            maxLines = 1
        }
        capMeta = TextView(this).apply {
            typeface = Type.regular(this@GamehubDream)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(C.text2)
            maxLines = 1
        }
        caption.addView(capTitle)
        caption.addView(capMeta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (6 * d).toInt() })
        root.addView(caption, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            marginStart = (48 * d).toInt(); bottomMargin = (36 * d).toInt()
        })
        // With nothing to show: the mark, drifting slowly so it never burns in.
        idle = ImageView(this).apply { setImageResource(R.drawable.splash_mark); alpha = 0f }
        root.addView(idle, FrameLayout.LayoutParams((96 * d).toInt(), (96 * d).toInt(), Gravity.CENTER))
        setContentView(root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        running = true
        load()
    }

    override fun onDreamingStopped() {
        running = false
        main.removeCallbacksAndMessages(null)
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        running = false
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDetachedFromWindow()
    }

    private fun load() {
        val app = applicationContext
        // Cached first, so the first image is up within a second even offline.
        val cfg0 = GamehubTv.cachedSaver(app)
        val cached = (cfg0?.collection?.let { GamehubTv.cachedShots(app, it) } ?: GamehubTv.cachedShots(app, "favourites"))?.items.orEmpty()
        if (cached.isNotEmpty()) start(cached, cfg0?.intervalSec ?: 12, cfg0?.shuffle ?: true)
        else showIdle()
        if (!GamehubStore.isLinked(app)) return
        io.execute {
            val cfg = GamehubTv.screensaver(app).valueOrNull() ?: cfg0 ?: GamehubTv.Saver("favourites", 12, true)
            var list = collect(cfg.collection)
            if (list.isEmpty() && cfg.collection != "favourites") list = collect("favourites")
            if (list.isEmpty()) list = collect("recent")
            main.post {
                if (!running || list.isEmpty()) return@post
                if (items.map { it.id }.toSet() != list.map { it.id }.toSet() || items.isEmpty()) start(list, cfg.intervalSec, cfg.shuffle)
            }
        }
    }

    /** Up to ~240 captures of [collection]. */
    private fun collect(collection: String): List<GamehubTv.Shot> {
        val out = ArrayList<GamehubTv.Shot>()
        var cursor: String? = null
        for (page in 0 until 4) {
            val r = GamehubTv.shots(applicationContext, collection, before = cursor, limit = 60).valueOrNull() ?: break
            out.addAll(r.items)
            cursor = r.next ?: break
        }
        return out
    }

    private fun start(list: List<GamehubTv.Shot>, intervalSec: Int, shuffle: Boolean) {
        items = if (shuffle) list.shuffled() else list
        intervalMs = intervalSec.coerceIn(5, 600) * 1000L
        index = -1
        idle.animate().cancel()
        idle.alpha = 0f
        main.removeCallbacksAndMessages(null)
        next()
    }

    private fun next() {
        if (!running || items.isEmpty()) return
        index = (index + 1) % items.size
        val shot = items[index]
        // Software bitmaps: the dream window is not guaranteed to be hardware accelerated.
        Img.load(shot.previewUrl, Img.Spec(w, hw = false)) { bmp -> if (running) show(shot, bmp) }
        items.getOrNull((index + 1) % items.size)?.let { Img.prefetch(it.previewUrl, Img.Spec(w, hw = false)) }
    }

    private fun show(shot: GamehubTv.Shot, bmp: Bitmap?) {
        if (bmp == null) { main.postDelayed({ next() }, 2000L); return }
        val back = if (front === a) b else a
        val old = front
        back.setImageBitmap(bmp)
        back.bringToFront()
        shade.bringToFront()
        caption.bringToFront()
        back.animate().cancel()
        back.alpha = 0f
        back.animate().alpha(1f).setDuration(1600L).withEndAction { old.setImageDrawable(null); old.alpha = 0f }.start()
        // A barely-there drift keeps static HUDs in captures from burning in.
        drift?.cancel()
        drift = android.animation.ObjectAnimator.ofPropertyValuesHolder(back,
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.03f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.03f)).apply {
            duration = intervalMs + 1600L
            interpolator = LinearInterpolator()
            start()
        }
        front = back
        caption.animate().cancel()
        caption.animate().alpha(0f).setDuration(500L).withEndAction {
            capTitle.text = shot.game ?: ""
            capMeta.text = Fmt.date(shot)
            caption.animate().alpha(0.85f).setStartDelay(900L).setDuration(900L).withEndAction { caption.animate().setStartDelay(0) }.start()
        }.start()
        main.postDelayed({ next() }, intervalMs)
    }

    private fun showIdle() {
        idle.alpha = 0.5f
        val dx = w * 0.25f
        val dy = h * 0.2f
        fun drift() {
            if (!running || items.isNotEmpty()) return
            idle.animate().translationX((Math.random().toFloat() * 2 - 1) * dx).translationY((Math.random().toFloat() * 2 - 1) * dy)
                .setDuration(20_000L).setInterpolator(LinearInterpolator()).withEndAction { drift() }.start()
        }
        drift()
    }
}
