package com.steamcontroller.android.ui.tv

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.steamcontroller.android.R
import com.steamcontroller.android.hub.GamehubTv
import com.steamcontroller.android.hub.GamehubTv.Shot
import com.steamcontroller.android.ui.pill.Pill
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.Motion
import com.steamcontroller.android.ui.theme.dp
import com.steamcontroller.android.ui.theme.surface
import com.steamcontroller.android.ui.widget.Kit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Show mode: full-screen originals for guests, from any row, game, album, person or session.
 *
 *  - ←/→ step, A pauses (there is no progress bar: a pause state and an interval are enough)
 *  - the caption (logo or name, date, people) fades out after a few seconds and stays put while
 *    consecutive captures are from the same game
 *  - ↓ a filmstrip of small thumbnails to jump in time, ↓ again a grid of larger ones;
 *    ↑ or B steps back out
 *  - ↑ or B one step back (grid → filmstrip → picture → close), LB/RB step like ←/→
 *  - Menu/Start the options (play/pause, time per picture, Ken Burns); +/− time per picture
 *  - LT/RT the previous/next collection, Y favourite at any stage
 *
 * Selection inside the filmstrip and grid is drawn by hand rather than with Android focus, so
 * nothing here can lose or trap focus. The screen stays awake while it plays.
 */
class ShowMode(private val act: AppCompatActivity, private val req: ShowRequest, private val onClose: () -> Unit) {
    private enum class Stage { NORMAL, STRIP, GRID, OPTIONS }

    private val ctx = act
    private val dm = ctx.resources.displayMetrics
    private val screenW = dm.widthPixels
    private val screenH = dm.heightPixels
    private val safeH = ctx.resources.getDimensionPixelSize(R.dimen.safe_h)
    private val safeV = ctx.resources.getDimensionPixelSize(R.dimen.safe_v)
    private val full = Img.Spec(screenW)
    private val handler = Handler(Looper.getMainLooper())

    val root = FrameLayout(ctx)
    private val backdrop = ImageView(ctx)
    private val layers = FrameLayout(ctx)
    private val imgA = ImageView(ctx)
    private val imgB = ImageView(ctx)
    private var front = imgA
    private val bottomScrim = View(ctx)
    private val caption = LinearLayout(ctx)
    private val capLogo = ImageView(ctx)
    private val capTitle = Kit.title(ctx, "", 34f)
    private val capMeta = Kit.body(ctx, "", 17f, C.text2)
    private val capHeart = ImageView(ctx)
    private val hud = LinearLayout(ctx)
    private val hudState = Kit.label(ctx, "", 12f, C.text)
    private val hudCount = Kit.value(ctx, "", 13f, C.text2)
    private val collCard = LinearLayout(ctx)
    private val collEyebrow = Kit.label(ctx, "", 11f, C.text3)
    private val collTitle = Kit.title(ctx, "", 28f)
    private val heart = ImageView(ctx)
    private val message = Kit.body(ctx, "", 18f, C.text2)

    private val stripPanel = FrameLayout(ctx)
    private val stripInfo = Kit.label(ctx, "", 12f, C.text)
    private val strip = RecyclerView(ctx)
    private val gridPanel = FrameLayout(ctx)
    private val gridInfo = Kit.label(ctx, "", 12f, C.text)
    private val grid = RecyclerView(ctx)
    private val options = LinearLayout(ctx)
    private val optRows = ArrayList<TextView>()

    private var colls = req.siblings
    private var collIndex = req.index.coerceIn(0, (req.siblings.size - 1).coerceAtLeast(0))
    private val items = ArrayList<Shot>()
    private var next: String? = null
    private var loadingMore = false
    private var index = 0
    private var sel = 0
    private var optSel = 0
    private var stage = Stage.NORMAL
    private var playing = true
    private var interval = TvPrefs.interval(ctx)
    private var captionGame: String? = null
    private var captionShown = false
    private var collToken = 0
    private var imgToken = 0
    private var kb: Animator? = null
    private var job: Job? = null
    private var closed = false

    private val stripW = 128
    private val stripH = 72
    private val gridCols = 5
    private val gridW = 160
    private val gridH = 90

    // ── build ─────────────────────────────────────────────────────────────────────────

    init {
        root.setBackgroundColor(0xFF000000.toInt())
        root.isFocusable = true
        root.isFocusableInTouchMode = true
        backdrop.scaleType = ImageView.ScaleType.CENTER_CROP
        backdrop.alpha = 0f
        root.addView(backdrop, match())
        root.addView(View(ctx).apply { setBackgroundColor(C.alpha(0xFF000000.toInt(), 0.45f)) }, match())
        for (iv in listOf(imgA, imgB)) {
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            layers.addView(iv, match())
        }
        root.addView(layers, match())

        bottomScrim.background = Scrim(intArrayOf(0, C.alpha(0xFF000000.toInt(), 0.55f), C.alpha(0xFF000000.toInt(), 0.82f)), floatArrayOf(0f, 0.55f, 1f))
        bottomScrim.alpha = 0f
        root.addView(bottomScrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (screenH * 0.42f).toInt(), Gravity.BOTTOM))

        caption.orientation = LinearLayout.VERTICAL
        caption.alpha = 0f
        capLogo.scaleType = ImageView.ScaleType.FIT_START
        caption.addView(capLogo, LinearLayout.LayoutParams(ctx.dp(340), ctx.dp(92)))
        capTitle.maxLines = 2
        capTitle.setShadowLayer(ctx.dp(10f), 0f, 0f, 0x99000000.toInt())
        caption.addView(capTitle, LinearLayout.LayoutParams(ctx.dp(620), ViewGroup.LayoutParams.WRAP_CONTENT))
        val metaRow = Kit.row(ctx)
        capHeart.setImageResource(R.drawable.ic_heart_fill)
        capHeart.visibility = View.GONE
        metaRow.addView(capHeart, LinearLayout.LayoutParams(ctx.dp(18), ctx.dp(18)).apply { marginEnd = ctx.dp(8) })
        capMeta.maxLines = 1
        capMeta.setShadowLayer(ctx.dp(8f), 0f, 0f, 0x99000000.toInt())
        metaRow.addView(capMeta)
        caption.addView(metaRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(10) })
        root.addView(caption, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            marginStart = safeH; bottomMargin = safeV + ctx.dp(10)
        })

        // Top right: only while paused or just after a step.
        hud.orientation = LinearLayout.HORIZONTAL
        hud.gravity = Gravity.CENTER_VERTICAL
        hud.setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
        hud.background = android.graphics.drawable.GradientDrawable().apply { setColor(C.alpha(C.bg, 0.72f)); cornerRadius = ctx.dp(20f); setStroke(ctx.dp(1), C.alpha(C.text, 0.08f)) }
        hud.addView(hudState)
        hud.addView(hudCount, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dp(12) })
        hud.alpha = 0f
        root.addView(hud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dp(40), Gravity.TOP or Gravity.END).apply { marginEnd = safeH; topMargin = safeV })

        collCard.orientation = LinearLayout.VERTICAL
        collCard.addView(collEyebrow)
        collTitle.setShadowLayer(ctx.dp(10f), 0f, 0f, 0x99000000.toInt())
        collCard.addView(collTitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(8) })
        collCard.alpha = 0f
        root.addView(collCard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { marginStart = safeH; topMargin = safeV + ctx.dp(4) })

        heart.setImageResource(R.drawable.ic_heart_fill)
        heart.alpha = 0f
        root.addView(heart, FrameLayout.LayoutParams(ctx.dp(96), ctx.dp(96), Gravity.CENTER))

        message.gravity = Gravity.CENTER
        message.visibility = View.GONE
        root.addView(message, FrameLayout.LayoutParams(ctx.dp(560), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        buildStrip()
        buildGrid()
        buildOptions()
    }

    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun buildStrip() {
        val h = ctx.dp(stripH + 6 + 64) + safeV
        stripPanel.background = Scrim(intArrayOf(0, C.alpha(0xFF000000.toInt(), 0.78f), C.alpha(0xFF000000.toInt(), 0.9f)), floatArrayOf(0f, 0.4f, 1f))
        stripPanel.clipChildren = false
        stripInfo.alpha = 0.95f
        stripPanel.addView(stripInfo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { marginStart = safeH + ctx.dp(3); topMargin = ctx.dp(20) })
        strip.clipToPadding = false
        strip.clipChildren = false
        strip.overScrollMode = View.OVER_SCROLL_NEVER
        strip.itemAnimator = null
        strip.isFocusable = false
        strip.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        val side = screenW / 2 - ctx.dp(stripW + 6) / 2
        strip.setPadding(side, ctx.dp(8), side, ctx.dp(8))
        strip.layoutManager = LinearLayoutManager(ctx, RecyclerView.HORIZONTAL, false)
        strip.addItemDecoration(gap(ctx.dp(8), horizontalOnly = true))
        val spec = Img.Spec(ctx.px(stripW))
        strip.adapter = SimpleAdapter(items, { Thumb(ctx, stripW, stripH) }) { v, shot, i ->
            val t = v as Thumb
            Img.into(t.image, shot.previewUrl, spec)
            t.setState(i == sel, shot.favorite)
            if (i >= items.size - 6) loadMore()
        }
        stripPanel.addView(strip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(stripH + 6 + 16), Gravity.BOTTOM).apply { bottomMargin = safeV - ctx.dp(4) })
        stripPanel.visibility = View.GONE
        root.addView(stripPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h, Gravity.BOTTOM))
    }

    private fun buildGrid() {
        gridPanel.setBackgroundColor(C.alpha(0xFF000000.toInt(), 0.86f))
        gridPanel.addView(gridInfo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { marginStart = safeH + ctx.dp(3); topMargin = safeV + ctx.dp(8) })
        grid.clipToPadding = false
        grid.clipChildren = false
        grid.overScrollMode = View.OVER_SCROLL_NEVER
        grid.itemAnimator = null
        grid.isFocusable = false
        grid.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        val cellW = ctx.dp(gridW + 6)
        val gapPx = ((screenW - 2 * safeH - gridCols * cellW) / (gridCols - 1)).coerceAtLeast(ctx.dp(8))
        val usedW = gridCols * cellW + (gridCols - 1) * gapPx
        val side = (screenW - usedW) / 2
        grid.setPadding(side, ctx.dp(10), side - gapPx, safeV + ctx.dp(40))
        grid.layoutManager = GridLayoutManager(ctx, gridCols)
        grid.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                outRect.right = gapPx
                outRect.bottom = ctx.dp(14)
            }
        })
        val spec = Img.Spec(ctx.px(gridW))
        grid.adapter = SimpleAdapter(items, { Thumb(ctx, gridW, gridH) }) { v, shot, i ->
            val t = v as Thumb
            Img.into(t.image, shot.previewUrl, spec)
            t.setState(i == sel, shot.favorite)
            if (i >= items.size - 10) loadMore()
        }
        gridPanel.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { topMargin = safeV + ctx.dp(36) })
        gridPanel.visibility = View.GONE
        root.addView(gridPanel, match())
    }

    private fun gap(px: Int, horizontalOnly: Boolean) = object : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            outRect.right = px
            if (!horizontalOnly) outRect.bottom = px
        }
    }

    private fun buildOptions() {
        options.orientation = LinearLayout.VERTICAL
        options.setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
        options.background = android.graphics.drawable.GradientDrawable().apply { setColor(C.alpha(C.sheet, 0.94f)); cornerRadius = ctx.dp(12f); setStroke(ctx.dp(1), C.line) }
        options.addView(Kit.label(ctx, ctx.getString(R.string.show_options), 11f, C.text3), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(ctx.dp(14), ctx.dp(8), 0, ctx.dp(8)) })
        repeat(3) {
            val row = Kit.body(ctx, "", 16f, C.text, medium = true)
            row.minHeight = ctx.dp(44)
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
            row.surface(radiusDp = 8f, fill = 0, stroke = 0)
            optRows.add(row)
            options.addView(row, LinearLayout.LayoutParams(ctx.dp(300), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        options.addView(Kit.label(ctx, ctx.getString(R.string.show_options_keys), 10.5f, C.text3), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(ctx.dp(14), ctx.dp(8), 0, ctx.dp(6)) })
        options.visibility = View.GONE
        root.addView(options, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { marginEnd = safeH; topMargin = safeV + ctx.dp(52) })
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────────────

    fun start() {
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(Motion.SLOW).start()
        root.requestFocus()
        openColl(collIndex, req.startShotId, req.preload, req.preloadNext, announce = false)
        setKeepOn()
    }

    fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        job?.cancel()
        kb?.cancel()
        act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root.animate().alpha(0f).setDuration(Motion.SLOW).setInterpolator(AccelerateInterpolator()).withEndAction {
            (root.parent as? ViewGroup)?.removeView(root)
        }.start()
        onClose()
    }

    /** Activity paused: stop the clock, keep the place. */
    fun pause() {
        if (playing) setPlaying(false, announce = false)
    }

    private fun setKeepOn() {
        if (playing && !closed) act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ── collections ───────────────────────────────────────────────────────────────────

    private fun openColl(i: Int, startShotId: String?, preload: List<Shot>?, preloadNext: String?, announce: Boolean) {
        collIndex = i
        val token = ++collToken
        val ref = colls.getOrNull(i) ?: return
        job?.cancel()
        loadingMore = false
        items.clear()
        next = null
        captionGame = null
        var initial: List<Shot>? = ref.items ?: preload
        if (initial != null && preload != null) next = preloadNext
        if (initial == null && ref.collection != null) {
            GamehubTv.cachedShots(ctx, ref.collection)?.let { initial = it.items; next = it.next }
        }
        initial?.let { items.addAll(it) }
        if (announce) announceColl(ref)
        if (items.isNotEmpty()) {
            message.visibility = View.GONE
            index = items.indexOfFirst { it.id == startShotId }.coerceAtLeast(0)
            sel = index
            refreshThumbs()
            showCurrent(manual = false, fade = announce)
        } else {
            showMessage(ctx.getString(R.string.show_loading))
        }
        val coll = ref.collection ?: return
        if (ref.items != null) return
        job = act.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { GamehubTv.shots(ctx, coll) }
            if (token != collToken || closed) return@launch
            when (r) {
                is GamehubTv.Res.Ok -> {
                    val keep = items.getOrNull(index)?.id ?: startShotId
                    val hadItems = items.isNotEmpty()
                    // Page one replaces what was shown from the row or the cache; extra items
                    // that were preloaded beyond it stay until paging reaches them.
                    val fresh = ArrayList(r.value.items)
                    val ids = fresh.mapTo(HashSet()) { it.id }
                    if (hadItems && preload != null) for (s in items) if (s.id !in ids) { fresh.add(s); ids.add(s.id) }
                    items.clear(); items.addAll(fresh)
                    next = r.value.next
                    if (items.isEmpty()) { showMessage(ctx.getString(R.string.show_empty)); return@launch }
                    index = items.indexOfFirst { it.id == keep }.coerceAtLeast(0)
                    sel = index
                    refreshThumbs()
                    if (!hadItems) { message.visibility = View.GONE; showCurrent(manual = false, fade = true) }
                }
                is GamehubTv.Res.Err -> if (items.isEmpty()) showMessage(when (r.fail) {
                    GamehubTv.Fail.NOT_DEPLOYED -> ctx.getString(R.string.show_update)
                    else -> ctx.getString(R.string.show_offline, r.message)
                })
            }
        }
    }

    private fun loadMore() {
        val cursor = next ?: return
        val coll = colls.getOrNull(collIndex)?.collection ?: return
        if (loadingMore) return
        loadingMore = true
        val token = collToken
        act.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { GamehubTv.shots(ctx, coll, before = cursor) }
            if (token != collToken || closed) return@launch
            loadingMore = false
            if (r is GamehubTv.Res.Ok) {
                val ids = items.mapTo(HashSet()) { it.id }
                val start = items.size
                for (s in r.value.items) if (ids.add(s.id)) items.add(s)
                next = r.value.next
                if (items.size > start) {
                    strip.adapter?.notifyItemRangeInserted(start, items.size - start)
                    grid.adapter?.notifyItemRangeInserted(start, items.size - start)
                }
            }
        }
    }

    private fun switchColl(d: Int) {
        if (colls.size < 2) { nudge(d); return }
        val i = (collIndex + d + colls.size) % colls.size
        if (stage == Stage.OPTIONS) setStage(Stage.NORMAL)
        openColl(i, null, null, null, announce = true)
    }

    private fun announceColl(ref: CollRef) {
        collEyebrow.text = ctx.getString(R.string.show_collection_of, collIndex + 1, colls.size)
        collTitle.text = ref.title
        collCard.animate().cancel()
        collCard.alpha = 0f
        collCard.translationY = -ctx.dp(6f)
        collCard.animate().alpha(1f).translationY(0f).setDuration(Motion.SLOW).setInterpolator(DecelerateInterpolator()).start()
        handler.removeCallbacks(hideColl)
        handler.postDelayed(hideColl, 2600L)
    }

    private val hideColl = Runnable { collCard.animate().alpha(0f).setDuration(400L).start() }

    private fun nudge(d: Int) {
        layers.animate().cancel()
        layers.translationX = 0f
        layers.animate().translationX(d * ctx.dp(10f)).setDuration(90L).withEndAction {
            layers.animate().translationX(0f).setDuration(160L).setInterpolator(DecelerateInterpolator()).start()
        }.start()
    }

    private fun showMessage(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
        imgA.setImageDrawable(null); imgB.setImageDrawable(null)
        backdrop.alpha = 0f
        caption.alpha = 0f; bottomScrim.alpha = 0f
        refreshThumbs()
    }

    // ── showing ───────────────────────────────────────────────────────────────────────

    private fun showCurrent(manual: Boolean, fade: Boolean = true) {
        val shot = items.getOrNull(index) ?: return
        val token = ++imgToken
        handler.removeCallbacks(advance)
        val back = if (front === imgA) imgB else imgA
        Img.load(shot.previewUrl, full) { bmp ->
            if (token != imgToken || closed) return@load
            if (bmp == null) {
                // Unreachable image: move on rather than freeze on the previous one.
                if (playing) handler.postDelayed(advance, 1500L)
                return@load
            }
            present(back, bmp, shot, token, if (manual) 280L else if (fade) 900L else 1L)
        }
        updateCaption(shot, manual)
        updateHud(manual)
        prefetchAround()
        if (index >= items.size - 8) loadMore()
    }

    private fun present(back: ImageView, preview: android.graphics.Bitmap, shot: Shot, token: Int, fadeMs: Long) {
        val old = front
        back.setImageBitmap(preview)
        layers.removeView(back)
        layers.addView(back)
        back.alpha = 0f
        back.animate().cancel()
        back.animate().alpha(1f).setDuration(fadeMs).setInterpolator(DecelerateInterpolator()).withEndAction {
            if (token == imgToken) { old.setImageDrawable(null); old.animate().cancel() }
        }.start()
        front = back
        startKenBurns(back)
        // Letterboxed captures (Deck 16:10, phones) get a soft blurred copy behind them.
        val ratio = preview.width.toFloat() / preview.height.coerceAtLeast(1)
        val screen = screenW.toFloat() / screenH
        if (kotlin.math.abs(ratio - screen) > 0.04f) {
            Img.load(shot.previewUrl, Img.Spec(32)) { tiny ->
                if (token != imgToken || tiny == null) return@load
                backdrop.setImageBitmap(tiny)
                backdrop.animate().alpha(1f).setDuration(fadeMs.coerceAtLeast(200L)).start()
            }
        } else if (backdrop.alpha > 0f) {
            backdrop.animate().alpha(0f).setDuration(fadeMs.coerceAtLeast(200L)).start()
        }
        // Then the original, swapped in place: same framing, sharper.
        Img.load(shot.fullUrl, full) { orig ->
            if (token != imgToken || orig == null || front !== back) return@load
            back.setImageBitmap(orig)
        }
        if (playing) handler.postDelayed(advance, interval * 1000L)
    }

    private val advance: Runnable = object : Runnable {
        override fun run() {
            if (!playing || closed || items.isEmpty()) return
            if (stage == Stage.STRIP || stage == Stage.GRID) return
            if (index + 1 >= items.size && next != null) {
                // The next page is on its way: wait for it rather than wrap early.
                loadMore()
                handler.postDelayed(this, 1000L)
                return
            }
            index = if (index + 1 < items.size) index + 1 else 0
            sel = index
            showCurrent(manual = false)
        }
    }

    private fun prefetchAround() {
        for (d in 1..2) items.getOrNull(index + d)?.let { Img.prefetch(it.previewUrl, full) }
        items.getOrNull(index + 1)?.let { Img.prefetchDisk(it.fullUrl) }
        items.getOrNull(index - 1)?.let { Img.prefetch(it.previewUrl, full) }
    }

    private fun startKenBurns(v: ImageView) {
        kb?.cancel()
        v.scaleX = 1f; v.scaleY = 1f; v.translationX = 0f; v.translationY = 0f
        if (!TvPrefs.kenBurns(ctx)) return
        val zoomIn = index % 2 == 0
        val s0 = if (zoomIn) 1f else 1.045f
        val s1 = if (zoomIn) 1.045f else 1f
        val dx = screenW * 0.01f * (if (index % 3 == 0) -1 else 1)
        val dy = screenH * 0.006f * (if (index % 4 < 2) -1 else 1)
        kb = ObjectAnimator.ofPropertyValuesHolder(v,
            PropertyValuesHolder.ofFloat(View.SCALE_X, s0, s1),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, s0, s1),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, dx),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, dy)).apply {
            duration = interval * 1000L + 2500L
            interpolator = LinearInterpolator()
            start()
            if (!playing) pause()
        }
    }

    // ── caption and HUD ───────────────────────────────────────────────────────────────

    private fun updateCaption(shot: Shot, manual: Boolean) {
        val game = shot.gameOrUnknown()
        capHeart.visibility = if (shot.favorite) View.VISIBLE else View.GONE
        val meta = Fmt.meta(shot)
        if (game != captionGame) {
            captionGame = game
            val fill = {
                val logoUrl = shot.art?.logo
                if (logoUrl != null) {
                    capLogo.visibility = View.VISIBLE
                    capTitle.visibility = View.GONE
                    Img.into(capLogo, logoUrl, Img.Spec(ctx.dp(340), alpha = true)) { b ->
                        if (b == null && captionGame == game) { capLogo.visibility = View.GONE; capTitle.visibility = View.VISIBLE }
                    }
                } else {
                    capLogo.visibility = View.GONE
                    capTitle.visibility = View.VISIBLE
                }
                capTitle.text = game
                capMeta.text = meta
            }
            if (captionShown && caption.alpha > 0.05f) {
                caption.animate().cancel()
                caption.animate().alpha(0f).setDuration(Motion.FAST).withEndAction { fill(); showCaption() }.start()
            } else {
                fill()
                showCaption()
            }
        } else {
            // Same game: the title stays put; only the date and people follow along.
            if (capMeta.text.toString() != meta) {
                if (captionShown) {
                    capMeta.animate().alpha(0f).setDuration(Motion.FAST).withEndAction {
                        capMeta.text = meta
                        capMeta.animate().alpha(1f).setDuration(Motion.BASE).start()
                    }.start()
                } else capMeta.text = meta
            }
            if (manual) showCaption()
        }
    }

    private fun showCaption() {
        captionShown = true
        val inStrip = stage == Stage.STRIP || stage == Stage.GRID
        if (!inStrip) {
            caption.animate().cancel()
            caption.animate().alpha(1f).setDuration(Motion.SLOW * 2).setInterpolator(DecelerateInterpolator()).start()
            bottomScrim.animate().alpha(1f).setDuration(Motion.SLOW * 2).start()
        }
        handler.removeCallbacks(hideCaption)
        if (playing) handler.postDelayed(hideCaption, CAPTION_MS)
    }

    private val hideCaption = Runnable {
        captionShown = false
        caption.animate().alpha(0f).setDuration(700L).start()
        bottomScrim.animate().alpha(0f).setDuration(700L).start()
    }

    /** One quiet pill: "Paused" while paused, nothing while playing (device-api.md). */
    private fun updateHud(@Suppress("UNUSED_PARAMETER") manual: Boolean) {
        hudCount.visibility = View.GONE
        hudState.text = ctx.getString(R.string.show_paused)
        hudState.setTextColor(C.text)
        handler.removeCallbacks(hideHud)
        hud.animate().cancel()
        hud.animate().alpha(if (playing) 0f else 1f).setDuration(Motion.BASE).start()
    }

    /** A short note in the same pill (time per picture changed). */
    private fun flashHud(text: String) {
        hudState.text = text
        hudState.setTextColor(C.text)
        hud.animate().cancel()
        hud.animate().alpha(1f).setDuration(Motion.BASE).start()
        handler.removeCallbacks(hideHud)
        handler.postDelayed(hideHud, 1600L)
    }

    private val hideHud = Runnable { if (playing) hud.animate().alpha(0f).setDuration(500L).start() else updateHud(false) }

    private fun setPlaying(on: Boolean, announce: Boolean = true) {
        playing = on
        setKeepOn()
        handler.removeCallbacks(advance)
        if (on) {
            kb?.resume()
            if (stage == Stage.NORMAL || stage == Stage.OPTIONS) handler.postDelayed(advance, interval * 1000L)
            handler.postDelayed(hideCaption, 2500L)
        } else {
            kb?.pause()
            handler.removeCallbacks(hideCaption)
            if (stage == Stage.NORMAL) showCaption()
        }
        if (announce) updateHud(true) else updateHud(false)
        renderOptions()
    }

    // ── favourite ─────────────────────────────────────────────────────────────────────

    private fun toggleFavorite() {
        val i = if (stage == Stage.STRIP || stage == Stage.GRID) sel else index
        val shot = items.getOrNull(i) ?: return
        val on = !shot.favorite
        shot.favorite = on
        burst(on)
        if (i == index) capHeart.visibility = if (on) View.VISIBLE else View.GONE
        strip.adapter?.notifyItemChanged(i)
        grid.adapter?.notifyItemChanged(i)
        act.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { GamehubTv.setShot(ctx, shot.id, favorite = on) }
            if (r is GamehubTv.Res.Err) {
                shot.favorite = !on
                if (!closed) {
                    if (items.getOrNull(index)?.id == shot.id) capHeart.visibility = if (shot.favorite) View.VISIBLE else View.GONE
                    strip.adapter?.notifyItemChanged(i)
                    grid.adapter?.notifyItemChanged(i)
                }
                Pill.show(ctx, Pill.Kind.FAILED, ctx.getString(R.string.show_fav_failed))
            } else {
                HomeRepo.refresh(ctx, 0L)
            }
        }
    }

    private fun burst(on: Boolean) {
        heart.setImageResource(if (on) R.drawable.ic_heart_fill else R.drawable.ic_heart_broken)
        heart.animate().cancel()
        heart.alpha = 0f
        heart.scaleX = 0.5f; heart.scaleY = 0.5f
        heart.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260L).setInterpolator(OvershootInterpolator(2.2f)).withEndAction {
            heart.animate().alpha(0f).scaleX(1.12f).scaleY(1.12f).setStartDelay(380L).setDuration(320L).setInterpolator(AccelerateInterpolator()).withEndAction { heart.animate().setStartDelay(0) }.start()
        }.start()
    }

    // ── stages ────────────────────────────────────────────────────────────────────────

    private fun setStage(s: Stage) {
        if (s == stage) return
        val from = stage
        stage = s
        handler.removeCallbacks(followSel)
        when (s) {
            Stage.NORMAL -> {
                fade(stripPanel, false); fade(gridPanel, false); fade(options, false)
                if (sel != index && items.isNotEmpty()) { index = sel; showCurrent(manual = true) }
                else { showCaption(); if (playing) { handler.removeCallbacks(advance); handler.postDelayed(advance, interval * 1000L) } }
            }
            Stage.STRIP -> {
                handler.removeCallbacks(advance)
                if (from == Stage.NORMAL) sel = index
                fade(options, false)
                fade(gridPanel, false)
                caption.animate().alpha(0f).setDuration(Motion.BASE).start()
                bottomScrim.animate().alpha(0f).setDuration(Motion.BASE).start()
                refreshThumbs()
                fade(stripPanel, true, slide = true)
                scrollStrip(immediate = true)
                renderSelInfo()
            }
            Stage.GRID -> {
                handler.removeCallbacks(advance)
                refreshThumbs()
                fade(gridPanel, true)
                fade(stripPanel, false)
                scrollGrid(immediate = true)
                renderSelInfo()
            }
            Stage.OPTIONS -> {
                optSel = 0
                renderOptions()
                fade(options, true)
            }
        }
    }

    private fun fade(v: View, show: Boolean, slide: Boolean = false) {
        v.animate().cancel()
        if (show) {
            v.visibility = View.VISIBLE
            v.alpha = 0f
            v.translationY = if (slide) ctx.dp(16f) else 0f
            v.animate().alpha(1f).translationY(0f).setDuration(Motion.SLOW).setInterpolator(DecelerateInterpolator()).start()
        } else if (v.visibility == View.VISIBLE) {
            v.animate().alpha(0f).setDuration(Motion.FAST).setInterpolator(AccelerateInterpolator()).withEndAction { v.visibility = View.GONE }.start()
        }
    }

    private fun refreshThumbs() {
        strip.adapter?.notifyDataSetChanged()
        grid.adapter?.notifyDataSetChanged()
    }

    private fun moveSel(d: Int) {
        if (items.isEmpty()) return
        val n = (sel + d).coerceIn(0, items.size - 1)
        if (n == sel) { if (sel == items.size - 1) loadMore(); return }
        val old = sel
        sel = n
        strip.adapter?.notifyItemChanged(old); strip.adapter?.notifyItemChanged(n)
        grid.adapter?.notifyItemChanged(old); grid.adapter?.notifyItemChanged(n)
        if (stage == Stage.STRIP) scrollStrip(false) else scrollGrid(false)
        renderSelInfo()
        // The big picture follows the selection once it settles.
        handler.removeCallbacks(followSel)
        handler.postDelayed(followSel, 240L)
    }

    private val followSel = Runnable {
        if (sel != index && (stage == Stage.STRIP || stage == Stage.GRID)) {
            index = sel
            showCurrent(manual = true)
        }
    }

    private fun renderSelInfo() {
        val s = items.getOrNull(sel) ?: return
        val text = buildString {
            append(s.gameOrUnknown()).append("  ·  ").append(Fmt.dateShort(s)).append("  ·  ").append(Fmt.time(s))
            append("     ").append(sel + 1).append(" / ").append(items.size).append(if (next != null) "+" else "")
        }
        stripInfo.text = text
        val ref = colls.getOrNull(collIndex)
        gridInfo.text = (ref?.title?.let { "$it  ·  " } ?: "") + text
    }

    private fun scrollStrip(immediate: Boolean) {
        val lm = strip.layoutManager as LinearLayoutManager
        val cellW = ctx.dp(stripW + 6 + 8)
        val v = lm.findViewByPosition(sel)
        if (v == null || immediate) {
            lm.scrollToPositionWithOffset(sel, screenW / 2 - cellW / 2 - strip.paddingLeft)
            return
        }
        val dx = (v.left + v.width / 2) - screenW / 2
        if (dx != 0) strip.smoothScrollBy(dx, 0, DecelerateInterpolator(1.5f), 160)
    }

    private fun scrollGrid(immediate: Boolean) {
        val lm = grid.layoutManager as GridLayoutManager
        val rowH = ctx.dp(gridH + 6 + 14)
        val keyline = rowH // keep one row of context above the selection
        val v = lm.findViewByPosition(sel)
        if (v == null || immediate) {
            lm.scrollToPositionWithOffset(sel, keyline)
            return
        }
        val dy = v.top - grid.paddingTop - keyline
        if (dy != 0) grid.smoothScrollBy(0, dy, DecelerateInterpolator(1.5f), 180)
    }

    // ── options ───────────────────────────────────────────────────────────────────────

    private fun renderOptions() {
        if (optRows.isEmpty()) return
        val values = listOf(
            ctx.getString(R.string.show_opt_play) to ctx.getString(if (playing) R.string.show_playing else R.string.show_paused),
            ctx.getString(R.string.show_opt_interval) to ctx.getString(R.string.show_seconds, interval),
            ctx.getString(R.string.show_opt_kenburns) to ctx.getString(if (TvPrefs.kenBurns(ctx)) R.string.show_on else R.string.show_off),
        )
        for ((i, row) in optRows.withIndex()) {
            val (k, v) = values[i]
            val on = i == optSel
            row.text = android.text.SpannableStringBuilder().apply {
                append(k)
                append("   ")
                val start = length
                append(if (on && i > 0) "‹  $v  ›" else v)
                setSpan(android.text.style.ForegroundColorSpan(if (on) C.accent else C.text2), start, length, 0)
            }
            row.isActivated = on
            row.background?.setState(if (on) intArrayOf(android.R.attr.state_focused, android.R.attr.state_enabled) else intArrayOf(android.R.attr.state_enabled))
            row.background?.invalidateSelf()
        }
    }

    private fun changeOption(d: Int) {
        when (optSel) {
            0 -> setPlaying(!playing)
            1 -> { stepInterval(d); return }
            2 -> {
                TvPrefs.setKenBurns(ctx, !TvPrefs.kenBurns(ctx))
                startKenBurns(front)
            }
        }
        renderOptions()
    }

    // ── keys ──────────────────────────────────────────────────────────────────────────

    /** Every key while show mode is open; true consumes (everything but volume and power). */
    fun onKey(e: KeyEvent): Boolean {
        val k = e.keyCode
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE || k == KeyEvent.KEYCODE_POWER) return false
        if (closed) return true
        // Back acts on release, so its release never reaches the page underneath.
        if (Keys.isBack(k)) {
            if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) back()
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return true
        val first = e.repeatCount == 0
        if (bumper(k)) return true
        when {
            k == KeyEvent.KEYCODE_DPAD_LEFT -> left()
            k == KeyEvent.KEYCODE_DPAD_RIGHT -> right()
            k == KeyEvent.KEYCODE_DPAD_DOWN -> down()
            k == KeyEvent.KEYCODE_DPAD_UP -> if (first || stage != Stage.NORMAL) up()
            !first -> {}
            Keys.isSelect(k) -> select()
            Keys.isPlayPause(k) -> setPlaying(!playing)
            Keys.isFavorite(k) -> toggleFavorite()
            Keys.isPrevColl(k) -> switchColl(-1)
            Keys.isNextColl(k) -> switchColl(1)
            Keys.isMenu(k) -> setStage(if (stage == Stage.OPTIONS) Stage.NORMAL else Stage.OPTIONS)
            Keys.isLonger(k) -> stepInterval(1)
            Keys.isShorter(k) -> stepInterval(-1)
            k == KeyEvent.KEYCODE_BUTTON_X -> setPlaying(!playing)
        }
        return true
    }

    // LB/RB step like ←/→ (device-api.md); with repeat, so holding them scrubs.
    private fun bumper(k: Int): Boolean = when (k) {
        KeyEvent.KEYCODE_BUTTON_L1 -> { left(); true }
        KeyEvent.KEYCODE_BUTTON_R1 -> { right(); true }
        else -> false
    }

    private var ltDown = false
    private var rtDown = false

    /** LT/RT (analog on the virtual Xbox pad) jump to the previous/next collection. */
    fun onMotion(ev: android.view.MotionEvent) {
        if (closed) return
        val lt = maxOf(ev.getAxisValue(android.view.MotionEvent.AXIS_LTRIGGER), ev.getAxisValue(android.view.MotionEvent.AXIS_BRAKE))
        val rt = maxOf(ev.getAxisValue(android.view.MotionEvent.AXIS_RTRIGGER), ev.getAxisValue(android.view.MotionEvent.AXIS_GAS))
        if (!ltDown && lt > 0.6f) { ltDown = true; switchColl(-1) } else if (ltDown && lt < 0.3f) ltDown = false
        if (!rtDown && rt > 0.6f) { rtDown = true; switchColl(1) } else if (rtDown && rt < 0.3f) rtDown = false
    }

    private fun stepInterval(d: Int) {
        val all = TvPrefs.INTERVALS
        val i = all.indexOfFirst { it >= interval }.let { if (it < 0) all.size - 1 else it }
        interval = all[(i + d).coerceIn(0, all.size - 1)]
        TvPrefs.setInterval(ctx, interval)
        if (playing && (stage == Stage.NORMAL || stage == Stage.OPTIONS)) { handler.removeCallbacks(advance); handler.postDelayed(advance, interval * 1000L) }
        renderOptions()
        flashHud(ctx.getString(R.string.show_per_picture, interval))
    }

    private fun back() {
        when (stage) {
            Stage.NORMAL -> close()
            Stage.STRIP -> setStage(Stage.NORMAL)
            Stage.GRID -> setStage(Stage.STRIP)
            Stage.OPTIONS -> setStage(Stage.NORMAL)
        }
    }

    private fun left() = when (stage) {
        Stage.NORMAL -> step(-1)
        Stage.STRIP, Stage.GRID -> moveSel(-1)
        Stage.OPTIONS -> changeOption(-1)
    }

    private fun right() = when (stage) {
        Stage.NORMAL -> step(1)
        Stage.STRIP, Stage.GRID -> moveSel(1)
        Stage.OPTIONS -> changeOption(1)
    }

    private fun down() {
        when (stage) {
            Stage.NORMAL -> if (items.isNotEmpty()) setStage(Stage.STRIP)
            Stage.STRIP -> setStage(Stage.GRID)
            Stage.GRID -> moveSel(gridCols)
            Stage.OPTIONS -> if (optSel < optRows.size - 1) { optSel++; renderOptions() } else setStage(Stage.NORMAL)
        }
    }

    /** ↑ is one step back, like B: grid → filmstrip → picture → close. */
    private fun up() {
        when (stage) {
            Stage.NORMAL -> close()
            Stage.STRIP -> setStage(Stage.NORMAL)
            Stage.GRID -> if (sel < gridCols) setStage(Stage.STRIP) else moveSel(-gridCols)
            Stage.OPTIONS -> if (optSel > 0) { optSel--; renderOptions() }
        }
    }

    private fun select() {
        when (stage) {
            Stage.NORMAL -> setPlaying(!playing)
            Stage.STRIP, Stage.GRID -> setStage(Stage.NORMAL)
            Stage.OPTIONS -> if (optSel == 1) changeOption(1) else changeOption(0)
        }
    }

    private fun step(d: Int) {
        if (items.isEmpty()) return
        val n = index + d
        when {
            n < 0 -> { nudge(1); return }
            n >= items.size -> { if (next != null) { loadMore(); nudge(-1); return } else index = 0 }
            else -> index = n
        }
        sel = index
        showCurrent(manual = true)
    }

    private companion object {
        const val CAPTION_MS = 4500L
    }
}

/** A filmstrip or grid thumbnail with a hand-drawn selection (show mode does not use focus). */
private class Thumb(ctx: android.content.Context, wDp: Int, hDp: Int) : FrameLayout(ctx) {
    val image = ImageView(ctx)
    private val ring = FocusRing(resources.displayMetrics.density, 6f)
    private val heart = ImageView(ctx)

    init {
        val pad = dp(3)
        setPadding(pad, pad, pad, pad)
        clipChildren = false
        foreground = ring
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.setBackgroundColor(C.surface2)
        image.roundCorners(6f)
        addView(image, LayoutParams(dp(wDp), dp(hDp)))
        heart.setImageResource(R.drawable.ic_heart_badge)
        heart.visibility = GONE
        addView(heart, LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(7), dp(7), 0) })
        layoutParams = RecyclerView.LayoutParams(dp(wDp + 6), dp(hDp + 6))
    }

    fun setState(selected: Boolean, favorite: Boolean) {
        heart.visibility = if (favorite) VISIBLE else GONE
        val s = if (selected) 1.1f else 1f
        animate().cancel()
        animate().scaleX(s).scaleY(s).alpha(if (selected) 1f else 0.62f).setDuration(Motion.BASE).setInterpolator(DecelerateInterpolator()).start()
        translationZ = if (selected) 2f else 0f
        ring.setState(if (selected) intArrayOf(android.R.attr.state_focused) else intArrayOf())
    }
}
