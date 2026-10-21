package com.steamcontroller.android.ui.tv

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.steamcontroller.android.ui.theme.Type
import com.steamcontroller.android.ui.theme.dp
import com.steamcontroller.android.ui.tv.ShowKeys.Act
import com.steamcontroller.android.ui.tv.ShowKeys.Ctl
import com.steamcontroller.android.ui.tv.ShowKeys.Stage
import com.steamcontroller.android.ui.widget.Kit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Show mode: full-screen originals for guests, from any row, game, album, person or session.
 *
 *  - pictures at native fit: contained, centred, never scaled past the screen and still unless
 *    "Slow zoom" is switched on (off by default; [TvPrefs.slowZoom])
 *  - the game's official logo small in the bottom-right corner the whole time (its name in
 *    condensed capitals when there is no logo); it changes only when the game does
 *  - the caption (date, people, favourite) fades out after a few seconds
 *  - OK or Play/Pause: a small control bar (previous, play/pause, next, time per picture,
 *    favourite, slow zoom), D-pad driven, hiding after 5 s while playing and staying while paused
 *  - ↓ a filmstrip of small thumbnails to jump in time, ↓ again a grid of larger ones;
 *    ↑ or B one step back (grid → filmstrip → picture → close), LB/RB step like ←/→
 *  - LT/RT the previous/next collection, Y favourite at any stage, +/− time per picture
 *
 * Key routing lives in [ShowKeys] (tested). Selection in the bar, filmstrip and grid is drawn by
 * hand rather than with Android focus, so nothing here can lose or trap focus. The screen stays
 * awake while it plays.
 */
class ShowMode(
    private val act: AppCompatActivity,
    private val req: ShowRequest,
    private val hold: Hold?,
    private val onOpenGame: (String) -> Unit,
    private val onChanged: (HoldItem, Boolean) -> Unit,
    private val onClose: () -> Unit,
) {
    private val ctx = act
    private val dm = ctx.resources.displayMetrics
    private val screenW = dm.widthPixels
    private val screenH = dm.heightPixels
    private val safeH = ctx.resources.getDimensionPixelSize(R.dimen.safe_h)
    private val safeV = ctx.resources.getDimensionPixelSize(R.dimen.safe_v)
    /** Previews decode at the UI surface's size (1920 × 1080 on a Shield); nothing larger is held. */
    private val full = Img.Spec(screenW)
    private val originalSpec = Img.Spec(screenW, original = true)
    private val handler = Handler(Looper.getMainLooper())

    val root = FrameLayout(ctx)
    private val backdrop = ImageView(ctx)
    private val layers = FrameLayout(ctx)
    private val imgA = ImageView(ctx)
    private val imgB = ImageView(ctx)
    private var front = imgA
    private val bottomScrim = View(ctx)
    private val caption = LinearLayout(ctx)
    private val capMeta = Kit.body(ctx, "", 17f, C.text2)
    private val capHeart = ImageView(ctx)
    private val hud = LinearLayout(ctx)
    private val hudState = Kit.label(ctx, "", 12f, C.text)
    private val collCard = LinearLayout(ctx)
    private val collEyebrow = Kit.label(ctx, "", 11f, C.text3)
    private val collTitle = Kit.title(ctx, "", 28f)
    private val heart = ImageView(ctx)
    private val message = Kit.body(ctx, "", 18f, C.text2)

    // The game's logo in the corner.
    private val logoBox = FrameLayout(ctx)
    private val logoShade = View(ctx)
    private val logoShadow = ImageView(ctx)
    private val logoImg = ImageView(ctx)
    private val logoName = Kit.label(ctx, "", 15f, C.text)
    private var logoGame: String? = null
    private var logoToken = 0

    // The control bar.
    private val bar = LinearLayout(ctx)
    private val barCount = Kit.value(ctx, "", 13f, C.text3)
    private val ctlViews = LinkedHashMap<Ctl, BarButton>()
    private var barSel = ShowKeys.DEFAULT_CTL
    private var lastInput = 0L

    private val stripPanel = FrameLayout(ctx)
    private val stripInfo = Kit.label(ctx, "", 12f, C.text)
    private val strip = RecyclerView(ctx)
    private val gridPanel = FrameLayout(ctx)
    private val gridInfo = Kit.label(ctx, "", 12f, C.text)
    private val grid = RecyclerView(ctx)

    private var colls = req.siblings
    private var collIndex = req.index.coerceIn(0, (req.siblings.size - 1).coerceAtLeast(0))
    private val items = ArrayList<Shot>()
    private var next: String? = null
    private var loadingMore = false
    private var index = 0
    private var sel = 0
    private var stage = Stage.PICTURE
    private var playing = true
    private val calm = req.calm
    /** The screensaver: any input closes it, and the waking input goes nowhere else. */
    val isCalm: Boolean get() = calm && !closed
    private var interval = req.intervalSec?.coerceIn(3, 600) ?: TvPrefs.interval(ctx)
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
    /** A selected thumbnail grows to this; it is decoded for that size. */
    private val thumbScale = 1.1f

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
            // Native fit: contained and centred, never cropped or zoomed past the screen.
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            layers.addView(iv, match())
        }
        root.addView(layers, match())

        bottomScrim.background = Scrim(intArrayOf(0, C.alpha(0xFF000000.toInt(), 0.55f), C.alpha(0xFF000000.toInt(), 0.82f)), floatArrayOf(0f, 0.55f, 1f))
        bottomScrim.alpha = 0f
        root.addView(bottomScrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (screenH * 0.42f).toInt(), Gravity.BOTTOM))

        buildLogo()

        caption.orientation = LinearLayout.HORIZONTAL
        caption.gravity = Gravity.CENTER_VERTICAL
        caption.alpha = 0f
        capHeart.setImageResource(R.drawable.ic_heart_fill)
        capHeart.visibility = View.GONE
        caption.addView(capHeart, LinearLayout.LayoutParams(ctx.dp(18), ctx.dp(18)).apply { marginEnd = ctx.dp(8) })
        capMeta.maxLines = 1
        capMeta.setShadowLayer(ctx.dp(8f), 0f, 0f, 0x99000000.toInt())
        caption.addView(capMeta)
        root.addView(caption, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            marginStart = safeH; bottomMargin = safeV + ctx.dp(14)
        })

        // Top right: only while paused (and the bar is not up) or just after a change.
        hud.orientation = LinearLayout.HORIZONTAL
        hud.gravity = Gravity.CENTER_VERTICAL
        hud.setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
        hud.background = android.graphics.drawable.GradientDrawable().apply { setColor(C.alpha(C.bg, 0.72f)); cornerRadius = ctx.dp(20f); setStroke(ctx.dp(1), C.alpha(C.text, 0.08f)) }
        hud.addView(hudState)
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

        buildBar()
        buildStrip()
        buildGrid()
    }

    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    /**
     * Bottom right, inside the safe area: a faint corner shade so a bright picture never swallows
     * it, the logo with a soft dark copy 1.5dp below it as its shadow, or the name in condensed caps.
     */
    private fun buildLogo() {
        logoShade.background = CornerShade(C.alpha(0xFF000000.toInt(), 0.42f))
        logoShade.alpha = 0f
        root.addView(logoShade, FrameLayout.LayoutParams(ctx.dp(LOGO_W + 260), ctx.dp(LOGO_H + 150), Gravity.BOTTOM or Gravity.END))
        for (iv in listOf(logoShadow, logoImg)) {
            iv.scaleType = ImageView.ScaleType.FIT_END
            logoBox.addView(iv, FrameLayout.LayoutParams(ctx.dp(LOGO_W), ctx.dp(LOGO_H), Gravity.BOTTOM or Gravity.END))
        }
        logoShadow.setColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN)
        logoShadow.alpha = 0.55f
        logoShadow.translationY = ctx.dp(1.5f)
        logoShadow.translationX = ctx.dp(0.5f)
        logoName.gravity = Gravity.END
        logoName.typeface = Type.condensedBold(ctx)
        logoName.letterSpacing = 0.08f
        logoName.setShadowLayer(ctx.dp(6f), 0f, ctx.dp(1f), 0xB3000000.toInt())
        logoName.visibility = View.GONE
        logoBox.addView(logoName, FrameLayout.LayoutParams(ctx.dp(LOGO_W + 80), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        logoBox.alpha = 0f
        root.addView(logoBox, FrameLayout.LayoutParams(ctx.dp(LOGO_W + 80), ctx.dp(LOGO_H), Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = safeH; bottomMargin = safeV + ctx.dp(8)
        })
    }

    private fun buildBar() {
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.clipChildren = false
        bar.clipToPadding = false
        bar.setPadding(ctx.dp(18), ctx.dp(6), ctx.dp(10), ctx.dp(6))
        bar.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(C.alpha(C.sheet, 0.92f)); cornerRadius = ctx.dp(34f); setStroke(ctx.dp(1), C.alpha(C.text, 0.08f))
        }
        barCount.minWidth = ctx.dp(72)
        bar.addView(barCount, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.dp(6) })
        fun add(c: Ctl, b: BarButton, gapBefore: Int = 4) {
            ctlViews[c] = b
            bar.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dp(gapBefore) })
        }
        add(Ctl.PREV, BarButton(ctx, R.drawable.ic_prev, 48, 22))
        add(Ctl.PLAY, BarButton(ctx, R.drawable.ic_pause, 56, 26))
        add(Ctl.NEXT, BarButton(ctx, R.drawable.ic_next, 48, 22))
        bar.addView(View(ctx).apply { setBackgroundColor(C.lineStrong) }, LinearLayout.LayoutParams(ctx.dp(1), ctx.dp(28)).apply { marginStart = ctx.dp(12); marginEnd = ctx.dp(8) })
        add(Ctl.TIMER, BarButton(ctx, R.drawable.ic_timer, 48, 18, withText = true))
        add(Ctl.FAV, BarButton(ctx, R.drawable.ic_heart, 48, 22))
        add(Ctl.ZOOM, BarButton(ctx, R.drawable.ic_zoom, 48, 18, withText = true))
        bar.visibility = View.GONE
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = safeV + ctx.dp(6)
        })
    }

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
        val spec = Img.cover(ctx, stripW, stripH, thumbScale)
        strip.adapter = SimpleAdapter(items, { Thumb(ctx, stripW, stripH) }) { v, shot, i ->
            val t = v as Thumb
            Img.into(t.image, shot.previewUrl, spec, full = shot.fullUrl)
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
        val spec = Img.cover(ctx, gridW, gridH, thumbScale)
        grid.adapter = SimpleAdapter(items, { Thumb(ctx, gridW, gridH) }) { v, shot, i ->
            val t = v as Thumb
            Img.into(t.image, shot.previewUrl, spec, full = shot.fullUrl)
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

    // ── lifecycle ─────────────────────────────────────────────────────────────────────

    fun start() {
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(if (calm) 900L else Motion.SLOW).start()
        root.requestFocus()
        openColl(collIndex, req.startShotId, req.preload, req.preloadNext, announce = false)
        setKeepOn()
        if (!calm && GameArt.isEmpty) learnLogos()
    }

    /** Logos come from the games list; read the saved one (or ask the hub) off the main thread. */
    private fun learnLogos() {
        act.lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { GamehubTv.cachedList(ctx, "games") ?: GamehubTv.games(ctx).valueOrNull() }
            if (closed || list == null) return@launch
            GameArt.putAll(list)
            logoGame = null
            items.getOrNull(index)?.let { updateLogo(it) }
        }
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

    private var released = false

    /** The activity was stopped (a game started): let go of every bitmap, keep the place. */
    fun releaseImages() {
        released = true
        imgToken++
        logoToken++
        kb?.cancel()
        for (iv in listOf(imgA, imgB, backdrop, logoImg, logoShadow)) Img.release(iv)
        logoGame = null
        for (rv in listOf(strip, grid)) {
            for (k in 0 until rv.childCount) (rv.getChildAt(k) as? Thumb)?.let { Img.release(it.image) }
            rv.recycledViewPool.clear()
        }
    }

    fun restoreImages() {
        if (!released || closed) return
        released = false
        if (items.isNotEmpty()) { refreshThumbs(); showCurrent(manual = false, fade = true) }
    }

    /** Activity paused: stop the clock, keep the place. */
    fun pause() {
        if (playing) setPlaying(false)
    }

    private fun setKeepOn() {
        // The in-app screensaver never holds the screen on: the system may still sleep the TV.
        if (playing && !closed && !calm) act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        val initial: List<Shot>? = ref.items ?: preload
        if (initial != null && preload != null) next = preloadNext
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
            // The saved first page (read off the main thread) while the hub answers.
            if (items.isEmpty()) {
                val cached = withContext(Dispatchers.IO) { GamehubTv.cachedShots(ctx, coll) }
                if (token != collToken || closed) return@launch
                if (cached != null && cached.items.isNotEmpty() && items.isEmpty()) {
                    items.addAll(cached.items)
                    next = cached.next
                    message.visibility = View.GONE
                    index = items.indexOfFirst { it.id == startShotId }.coerceAtLeast(0)
                    sel = index
                    refreshThumbs()
                    showCurrent(manual = false, fade = true)
                }
            }
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
                    if (calm && req.shuffle) {
                        // Keep what is on screen first, shuffle the rest.
                        val head = items.firstOrNull { it.id == keep }
                        items.shuffle()
                        if (head != null) { items.remove(head); items.add(0, head) }
                    }
                    next = r.value.next
                    moreFailedAt = 0L
                    if (items.isEmpty()) { if (calm) close() else showMessage(ctx.getString(R.string.show_empty)); return@launch }
                    index = items.indexOfFirst { it.id == keep }.coerceAtLeast(0)
                    sel = index
                    refreshThumbs()
                    if (!hadItems) { message.visibility = View.GONE; showCurrent(manual = false, fade = true) } else renderBar()
                }
                is GamehubTv.Res.Err -> if (items.isEmpty() && calm) close() else if (items.isEmpty()) showMessage(when (r.fail) {
                    GamehubTv.Fail.NOT_DEPLOYED -> ctx.getString(R.string.show_update)
                    else -> ctx.getString(R.string.show_offline, r.message)
                })
            }
        }
    }

    /** When the last page load failed (offline): no new attempt for a while, the show loops instead. */
    private var moreFailedAt = 0L
    private fun moreBlocked() = moreFailedAt != 0L && SystemClock.uptimeMillis() - moreFailedAt < MORE_BACKOFF_MS

    private fun loadMore() {
        val cursor = next ?: return
        val coll = colls.getOrNull(collIndex)?.collection ?: return
        if (loadingMore || moreBlocked()) return
        loadingMore = true
        val token = collToken
        act.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { GamehubTv.shots(ctx, coll, before = cursor) }
            if (token != collToken || closed) return@launch
            loadingMore = false
            if (r is GamehubTv.Res.Err) moreFailedAt = SystemClock.uptimeMillis()
            if (r is GamehubTv.Res.Ok) {
                moreFailedAt = 0L
                val ids = items.mapTo(HashSet()) { it.id }
                val start = items.size
                for (s in r.value.items) if (ids.add(s.id)) items.add(s)
                next = r.value.next
                if (items.size > start) {
                    strip.adapter?.notifyItemRangeInserted(start, items.size - start)
                    grid.adapter?.notifyItemRangeInserted(start, items.size - start)
                    renderBar()
                }
            }
        }
    }

    private fun switchColl(d: Int) {
        if (colls.size < 2) { nudge(d); return }
        val i = (collIndex + d + colls.size) % colls.size
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
        logoBox.alpha = 0f; logoShade.alpha = 0f; logoGame = null
        refreshThumbs()
    }

    // ── showing ───────────────────────────────────────────────────────────────────────

    private fun showCurrent(manual: Boolean, fade: Boolean = true) {
        val shot = items.getOrNull(index) ?: return
        val token = ++imgToken
        handler.removeCallbacks(advance)
        val back = if (front === imgA) imgB else imgA
        Img.load(shot.previewUrl, full, urgent = true) { bmp ->
            if (token != imgToken || closed) return@load
            if (bmp == null) {
                // Unreachable image: move on rather than freeze on the previous one.
                if (playing) handler.postDelayed(advance, 1500L)
                return@load
            }
            present(back, bmp, shot, token, if (calm) 1600L else if (manual) 280L else if (fade) 900L else 1L)
        }
        updateCaption(shot, manual)
        updateLogo(shot)
        updateHud()
        renderBar()
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
        // Then the original, swapped in place (same framing, sharper), whenever the preview is
        // narrower than the screen (device-api.md: use the full image then).
        if (preview.width < screenW * 0.97f && (shot.width == 0 || shot.width > preview.width)) {
            Img.load(shot.fullUrl, originalSpec) { orig ->
                if (token != imgToken || orig == null || front !== back) return@load
                back.setImageBitmap(orig)
            }
        }
        if (playing && (stage == Stage.PICTURE || stage == Stage.BAR)) handler.postDelayed(advance, interval * 1000L)
    }

    private val advance: Runnable = object : Runnable {
        override fun run() {
            if (!playing || closed || items.isEmpty()) return
            if (stage == Stage.STRIP || stage == Stage.GRID) return
            if (index + 1 >= items.size && next != null && !moreBlocked()) {
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
        items.getOrNull(index + 1)?.let { if (it.width > screenW * 0.85f || it.width == 0) Img.prefetchDisk(it.fullUrl) }
        items.getOrNull(index - 1)?.let { Img.prefetch(it.previewUrl, full) }
    }

    /** Slow zoom, only when switched on (show mode and the screensaver alike); else perfectly still. */
    private fun startKenBurns(v: ImageView) {
        kb?.cancel()
        kb = null
        v.scaleX = 1f; v.scaleY = 1f; v.translationX = 0f; v.translationY = 0f
        if (!TvPrefs.slowZoom(ctx)) return
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

    // ── logo, caption and HUD ─────────────────────────────────────────────────────────

    /** The game's logo, changed (with a short crossfade) only when the game changes. */
    private fun updateLogo(shot: Shot) {
        if (calm) return
        val game = shot.gameOrUnknown()
        if (game == logoGame) return
        logoGame = game
        val token = ++logoToken
        val url = shot.art?.logo ?: GameArt.get(shot.game)?.logo
        val fill = {
            logoName.text = game
            if (url == null) {
                Img.release(logoImg); Img.release(logoShadow)
                logoName.visibility = View.VISIBLE
                revealLogo()
            } else {
                logoName.visibility = View.GONE
                val spec = Img.Spec(ctx.dp(LOGO_W), alpha = true)
                Img.into(logoImg, url, spec, fade = false) { b ->
                    if (token != logoToken) return@into
                    if (b == null) {
                        Img.release(logoShadow)
                        logoName.visibility = View.VISIBLE
                    } else {
                        logoShadow.setImageBitmap(b)
                    }
                    revealLogo()
                }
            }
        }
        if (logoBox.alpha > 0.05f) {
            logoBox.animate().cancel()
            logoBox.animate().alpha(0f).setDuration(Motion.FAST).setInterpolator(AccelerateInterpolator()).withEndAction { if (token == logoToken) fill() }.start()
        } else fill()
    }

    private fun logoVisible() = !calm && logoGame != null && (stage == Stage.PICTURE || stage == Stage.BAR) && message.visibility != View.VISIBLE

    private fun revealLogo() {
        val a = if (logoVisible()) 0.94f else 0f
        logoBox.animate().cancel()
        // Up with the caption while the control bar is shown, so the two never touch.
        logoBox.animate().alpha(a).translationY(captionLift()).setDuration(Motion.SLOW * 2).setInterpolator(DecelerateInterpolator()).start()
        logoShade.animate().cancel()
        logoShade.animate().alpha(if (a > 0f) 1f else 0f).setDuration(Motion.SLOW * 2).start()
    }

    private fun updateCaption(shot: Shot, manual: Boolean) {
        if (calm) return
        capHeart.visibility = if (shot.favorite) View.VISIBLE else View.GONE
        val meta = Fmt.meta(shot)
        if (capMeta.text.toString() != meta) {
            // The date updates in place; the game is the corner logo's job.
            if (captionShown && caption.alpha > 0.05f) {
                capMeta.animate().cancel()
                capMeta.animate().alpha(0f).setDuration(Motion.FAST).withEndAction {
                    capMeta.text = meta
                    capMeta.animate().alpha(1f).setDuration(Motion.BASE).start()
                }.start()
            } else capMeta.text = meta
        }
        if (manual || !captionShown) showCaption()
    }

    private fun showCaption() {
        if (calm) return
        captionShown = true
        val inStrip = stage == Stage.STRIP || stage == Stage.GRID
        if (!inStrip) {
            caption.animate().cancel()
            caption.animate().alpha(1f).translationY(captionLift()).setDuration(Motion.SLOW * 2).setInterpolator(DecelerateInterpolator()).start()
            bottomScrim.animate().alpha(1f).setDuration(Motion.SLOW * 2).start()
        }
        handler.removeCallbacks(hideCaption)
        if (playing && stage != Stage.BAR) handler.postDelayed(hideCaption, CAPTION_MS)
    }

    /** The caption and the logo move up above the control bar while it is shown. */
    private fun captionLift(): Float = if (stage == Stage.BAR) -ctx.dp(66f) else 0f

    private val hideCaption = Runnable {
        captionShown = false
        caption.animate().alpha(0f).setDuration(700L).start()
        bottomScrim.animate().alpha(0f).setDuration(700L).start()
    }

    /** One quiet pill: "Paused" while paused and the bar is not up, nothing while playing. */
    private fun updateHud() {
        if (calm) { hud.alpha = 0f; return }
        hudState.text = ctx.getString(R.string.show_paused)
        handler.removeCallbacks(hideHud)
        hud.animate().cancel()
        hud.animate().alpha(if (!playing && stage == Stage.PICTURE) 1f else 0f).setDuration(Motion.BASE).start()
    }

    /** A short note in the same pill (time per picture changed with +/−). */
    private fun flashHud(text: String) {
        if (stage == Stage.BAR) return // the bar shows it
        hudState.text = text
        hud.animate().cancel()
        hud.animate().alpha(1f).setDuration(Motion.BASE).start()
        handler.removeCallbacks(hideHud)
        handler.postDelayed(hideHud, 1600L)
    }

    private val hideHud = Runnable { updateHud() }

    private fun setPlaying(on: Boolean) {
        playing = on
        setKeepOn()
        handler.removeCallbacks(advance)
        if (on) {
            kb?.resume()
            if (stage == Stage.PICTURE || stage == Stage.BAR) handler.postDelayed(advance, interval * 1000L)
            if (stage != Stage.BAR) handler.postDelayed(hideCaption, 2500L)
        } else {
            kb?.pause()
            handler.removeCallbacks(hideCaption)
            if (stage == Stage.PICTURE) showCaption()
        }
        updateHud()
        renderBar()
        scheduleBarHide()
    }

    // ── control bar ───────────────────────────────────────────────────────────────────

    private fun openBar() {
        if (items.isEmpty() || calm) return
        if (stage != Stage.BAR) barSel = ShowKeys.DEFAULT_CTL
        setStage(Stage.BAR)
    }

    private fun renderBar() {
        val shot = items.getOrNull(index)
        barCount.text = if (items.isEmpty()) "" else ctx.getString(R.string.show_position, index + 1, items.size) + (if (next != null) "+" else "")
        ctlViews[Ctl.PLAY]?.setIcon(if (playing) R.drawable.ic_pause else R.drawable.ic_play, C.text)
        ctlViews[Ctl.TIMER]?.setText(ctx.getString(R.string.show_seconds, interval), C.text)
        val fav = shot?.favorite == true
        ctlViews[Ctl.FAV]?.setIcon(if (fav) R.drawable.ic_heart_fill else R.drawable.ic_heart, if (fav) C.danger else C.text)
        val zoom = TvPrefs.slowZoom(ctx)
        ctlViews[Ctl.ZOOM]?.setText(ctx.getString(if (zoom) R.string.show_zoom_on else R.string.show_zoom_off), if (zoom) C.accent else C.text2)
        for ((c, v) in ctlViews) v.setSelectedState(stage == Stage.BAR && c == barSel)
        ctlViews[Ctl.PLAY]?.contentDescription = ctx.getString(if (playing) R.string.show_paused else R.string.show_playing)
    }

    private fun press(c: Ctl) {
        when (c) {
            Ctl.PREV -> step(-1)
            Ctl.NEXT -> step(1)
            Ctl.PLAY -> setPlaying(!playing)
            Ctl.TIMER -> stepInterval(1, wrap = true)
            Ctl.FAV -> toggleFavorite()
            Ctl.ZOOM -> {
                TvPrefs.setSlowZoom(ctx, !TvPrefs.slowZoom(ctx))
                startKenBurns(front)
            }
        }
        renderBar()
    }

    private val hideBar = Runnable {
        if (stage == Stage.BAR && ShowKeys.barExpired(SystemClock.uptimeMillis(), lastInput, playing)) setStage(Stage.PICTURE)
        else scheduleBarHide()
    }

    /** While playing the bar goes after [ShowKeys.BAR_HIDE_MS] without input; paused, it stays. */
    private fun scheduleBarHide() {
        handler.removeCallbacks(hideBar)
        if (stage != Stage.BAR || !playing) return
        val left = (lastInput + ShowKeys.BAR_HIDE_MS - SystemClock.uptimeMillis()).coerceAtLeast(50L)
        handler.postDelayed(hideBar, left)
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
        renderBar()
        act.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { GamehubTv.setShot(ctx, shot.id, favorite = on) }
            if (r is GamehubTv.Res.Err) {
                shot.favorite = !on
                if (!closed) {
                    if (items.getOrNull(index)?.id == shot.id) capHeart.visibility = if (shot.favorite) View.VISIBLE else View.GONE
                    strip.adapter?.notifyItemChanged(i)
                    grid.adapter?.notifyItemChanged(i)
                    renderBar()
                }
                Pill.show(ctx, Pill.Kind.FAILED, ctx.getString(R.string.show_fav_failed))
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
        handler.removeCallbacks(hideBar)
        when (s) {
            Stage.PICTURE -> {
                fade(stripPanel, false); fade(gridPanel, false); fade(bar, false, slide = true)
                if (sel != index && items.isNotEmpty()) { index = sel; showCurrent(manual = true) }
                else if (from == Stage.BAR) {
                    // The bar went away: the picture and its clock carry on untouched.
                    caption.animate().translationY(0f).setDuration(Motion.SLOW).start()
                    if (playing) { handler.removeCallbacks(hideCaption); handler.postDelayed(hideCaption, 2500L) }
                } else {
                    showCaption()
                    if (playing) { handler.removeCallbacks(advance); handler.postDelayed(advance, interval * 1000L) }
                }
            }
            Stage.BAR -> {
                fade(stripPanel, false); fade(gridPanel, false)
                renderBar()
                fade(bar, true, slide = true)
                showCaption()
                lastInput = SystemClock.uptimeMillis()
                scheduleBarHide()
            }
            Stage.STRIP -> {
                handler.removeCallbacks(advance)
                if (from == Stage.PICTURE || from == Stage.BAR) sel = index
                fade(bar, false, slide = true)
                fade(gridPanel, false)
                caption.animate().alpha(0f).translationY(0f).setDuration(Motion.BASE).start()
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
        }
        renderBar()
        updateHud()
        revealLogo()
    }

    private fun fade(v: View, show: Boolean, slide: Boolean = false) {
        v.animate().cancel()
        if (show) {
            v.visibility = View.VISIBLE
            v.alpha = 0f
            v.translationY = if (slide) ctx.dp(16f) else 0f
            v.animate().alpha(1f).translationY(0f).setDuration(Motion.SLOW).setInterpolator(DecelerateInterpolator()).start()
        } else if (v.visibility == View.VISIBLE) {
            v.animate().alpha(0f).translationY(if (slide) ctx.dp(10f) else 0f).setDuration(Motion.FAST).setInterpolator(AccelerateInterpolator()).withEndAction { v.visibility = View.GONE }.start()
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
        val title = ref?.title
        // The collection's title, then the selection; the game only when the title does not say it.
        gridInfo.text = if (title == null) text else buildString {
            append(title)
            if (!title.contains(s.gameOrUnknown(), ignoreCase = true)) append("  ·  ").append(s.gameOrUnknown())
            append("  ·  ").append(Fmt.dateShort(s)).append("  ·  ").append(Fmt.time(s))
            append("     ").append(sel + 1).append(" / ").append(items.size).append(if (next != null) "+" else "")
        }
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

    // ── keys ──────────────────────────────────────────────────────────────────────────

    /** Every key while show mode is open; true consumes (everything but volume and power). */
    fun onKey(e: KeyEvent): Boolean {
        val k = e.keyCode
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE || k == KeyEvent.KEYCODE_POWER) return false
        if (closed) return true
        // Screensaver: any key wakes, on release so the release never reaches the page below.
        if (calm) {
            if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) close()
            return true
        }
        val down = e.action == KeyEvent.ACTION_DOWN
        if (down) { lastInput = SystemClock.uptimeMillis(); scheduleBarHide() }
        // Back acts on release, so its release never reaches the page underneath.
        if (ShowKeys.isBack(k)) {
            if (!down && !e.isCanceled) perform(ShowKeys.back(stage))
            return true
        }
        if (ShowKeys.isSelect(k)) {
            if (items.isEmpty()) return true
            if (hold != null && ShowKeys.holdable(stage)) {
                // A tap acts on release (bar, jump); held for 0.75 s it opens the hold menu.
                if (down) {
                    if (e.repeatCount == 0) hold.arm(null) { holdFired() }
                } else if (hold.firedHold) hold.clearFired() else { hold.cancel(); perform(ShowKeys.tap(stage)) }
            } else if (down && e.repeatCount == 0) {
                // On the bar OK presses the selected control at once.
                perform(ShowKeys.tap(stage))
            }
            return true
        }
        if (!down) return true
        hold?.cancel()
        perform(ShowKeys.down(stage, k, e.repeatCount))
        return true
    }

    private fun perform(a: Act) {
        when (a) {
            Act.NONE -> {}
            Act.PREV -> step(-1)
            Act.NEXT -> step(1)
            Act.OPEN_BAR -> openBar()
            Act.CLOSE_BAR -> setStage(Stage.PICTURE)
            Act.BAR_LEFT -> { barSel = ShowKeys.move(barSel, -1); renderBar() }
            Act.BAR_RIGHT -> { barSel = ShowKeys.move(barSel, 1); renderBar() }
            Act.BAR_PRESS -> press(barSel)
            Act.TOGGLE_PLAY -> {
                setPlaying(!playing)
                if (stage == Stage.PICTURE) { barSel = Ctl.PLAY; openBar() }
                else if (stage != Stage.BAR) flashHud(ctx.getString(if (playing) R.string.show_playing else R.string.show_paused))
            }
            Act.STRIP -> if (items.isNotEmpty()) setStage(Stage.STRIP)
            Act.GRID -> setStage(Stage.GRID)
            Act.SEL_LEFT -> moveSel(-1)
            Act.SEL_RIGHT -> moveSel(1)
            Act.SEL_UP -> if (sel < gridCols) setStage(Stage.STRIP) else moveSel(-gridCols)
            Act.SEL_DOWN -> moveSel(gridCols)
            Act.PICK -> setStage(Stage.PICTURE)
            Act.BACK -> when (stage) {
                Stage.PICTURE -> close()
                Stage.BAR -> setStage(Stage.PICTURE)
                Stage.STRIP -> setStage(Stage.PICTURE)
                Stage.GRID -> setStage(Stage.STRIP)
            }
            Act.FAVOURITE -> toggleFavorite()
            Act.PREV_COLL -> switchColl(-1)
            Act.NEXT_COLL -> switchColl(1)
            Act.LONGER -> stepInterval(1, wrap = false)
            Act.SHORTER -> stepInterval(-1, wrap = false)
        }
    }

    private var ltDown = false
    private var rtDown = false

    /** LT/RT (analog on the virtual Xbox pad) jump to the previous/next collection. */
    fun onMotion(ev: android.view.MotionEvent) {
        if (closed) return
        val lt = maxOf(ev.getAxisValue(android.view.MotionEvent.AXIS_LTRIGGER), ev.getAxisValue(android.view.MotionEvent.AXIS_BRAKE))
        val rt = maxOf(ev.getAxisValue(android.view.MotionEvent.AXIS_RTRIGGER), ev.getAxisValue(android.view.MotionEvent.AXIS_GAS))
        if (!ltDown && lt > 0.6f) { ltDown = true; lastInput = SystemClock.uptimeMillis(); switchColl(-1) } else if (ltDown && lt < 0.3f) ltDown = false
        if (!rtDown && rt > 0.6f) { rtDown = true; lastInput = SystemClock.uptimeMillis(); switchColl(1) } else if (rtDown && rt < 0.3f) rtDown = false
    }

    private fun stepInterval(d: Int, wrap: Boolean) {
        interval = ShowKeys.nextInterval(interval, d, TvPrefs.INTERVALS, wrap)
        TvPrefs.setInterval(ctx, interval)
        if (playing && (stage == Stage.PICTURE || stage == Stage.BAR)) { handler.removeCallbacks(advance); handler.postDelayed(advance, interval * 1000L) }
        // The zoom's length follows the time per picture.
        if (kb != null) startKenBurns(front)
        renderBar()
        flashHud(ctx.getString(R.string.show_per_picture, interval))
    }

    // ── hold menu ─────────────────────────────────────────────────────────────────────

    private fun holdFired() {
        val i = if (stage == Stage.STRIP || stage == Stage.GRID) sel else index
        val shot = items.getOrNull(i) ?: return
        if (playing) setPlaying(false)
        hold?.openMenu(HoldItem(shot, colls.getOrNull(collIndex), ArrayList(items)), holdContext)
    }

    private val holdContext = object : HoldContext {
        override fun slideshowFrom(item: HoldItem) {
            val i = items.indexOfFirst { it.id == item.shot.id }
            if (i < 0) return
            index = i; sel = i
            setStage(Stage.PICTURE)
            showCurrent(manual = true)
            setPlaying(true)
        }
        override fun showSession(item: HoldItem) {
            colls = listOf(CollRef("session:" + item.shot.id, ctx.getString(R.string.hold_session_title)))
            openColl(0, item.shot.id, null, null, announce = true)
            setStage(Stage.PICTURE)
        }
        override fun openHub(game: String) {
            close()
            onOpenGame(game)
        }
        override fun changed(item: HoldItem, hidden: Boolean) {
            onChanged(item, hidden)
            if (closed) return
            if (hidden) {
                val i = items.indexOfFirst { it.id == item.shot.id }
                if (i >= 0) {
                    items.removeAt(i)
                    if (items.isEmpty()) { close(); return }
                    if (index >= items.size) index = 0
                    sel = sel.coerceAtMost(items.size - 1)
                    refreshThumbs()
                    showCurrent(manual = true)
                }
            } else {
                val cur = items.getOrNull(index)
                if (cur != null) capHeart.visibility = if (cur.favorite) View.VISIBLE else View.GONE
                refreshThumbs()
                renderBar()
            }
        }
    }

    private fun step(d: Int) {
        if (items.isEmpty()) return
        val n = index + d
        when {
            n < 0 -> { nudge(1); return }
            n >= items.size -> { if (next != null && !moreBlocked()) { loadMore(); nudge(-1); return } else index = 0 }
            else -> index = n
        }
        sel = index
        showCurrent(manual = true)
    }

    private companion object {
        const val CAPTION_MS = 4500L
        const val MORE_BACKOFF_MS = 30_000L
        const val LOGO_W = 210
        const val LOGO_H = 64
    }
}

/** One control on show mode's bar: an icon (and a short value), hand-selected like the thumbnails. */
private class BarButton(ctx: android.content.Context, iconRes: Int, private val hDp: Int, private val iconDp: Int, withText: Boolean = false) : FrameLayout(ctx) {
    private val inner = LinearLayout(ctx)
    private val icon = ImageView(ctx)
    private val text: TextView? = if (withText) Kit.value(ctx, "", 14f, C.text).apply { letterSpacing = 0.04f } else null
    private val ring = FocusRing(resources.displayMetrics.density, hDp / 2f, round = true)
    private val fill = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(hDp / 2f) }
    private var iconRes = 0
    private var iconTint = 0

    init {
        val pad = dp(3)
        setPadding(pad, pad, pad, pad)
        clipChildren = false
        foreground = ring
        inner.orientation = LinearLayout.HORIZONTAL
        inner.gravity = Gravity.CENTER
        inner.background = fill
        if (text != null) inner.setPadding(dp(14), 0, dp(16), 0)
        inner.addView(icon, LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)))
        if (text != null) inner.addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        addView(inner, LayoutParams(if (text == null) dp(hDp) else ViewGroup.LayoutParams.WRAP_CONTENT, dp(hDp)))
        setIcon(iconRes, C.text)
        setSelectedState(false)
    }

    fun setIcon(res: Int, tint: Int) {
        if (res == iconRes && tint == iconTint) return
        iconRes = res; iconTint = tint
        icon.setImageResource(res)
        icon.setColorFilter(tint)
    }

    fun setText(t: String, color: Int) {
        text?.text = t
        text?.setTextColor(color)
    }

    fun setSelectedState(on: Boolean) {
        fill.setColor(if (on) C.alpha(C.text, 0.12f) else 0)
        ring.setState(if (on) intArrayOf(android.R.attr.state_focused) else intArrayOf())
        val s = if (on) 1.06f else 1f
        if (scaleX != s) {
            animate().cancel()
            animate().scaleX(s).scaleY(s).setDuration(Motion.BASE).setInterpolator(DecelerateInterpolator()).start()
        }
    }
}

/** A soft dark corner (bottom right) so the logo reads over any picture. */
private class CornerShade(private val color: Int) : android.graphics.drawable.Drawable() {
    private val p = android.graphics.Paint()
    override fun onBoundsChange(b: Rect) {
        p.shader = android.graphics.RadialGradient(b.right.toFloat(), b.bottom.toFloat(), maxOf(b.width(), b.height()).toFloat(),
            intArrayOf(color, C.alpha(color, 0.5f * Color.alpha(color) / 255f), 0), floatArrayOf(0f, 0.45f, 1f), android.graphics.Shader.TileMode.CLAMP)
    }
    override fun draw(c: android.graphics.Canvas) = c.drawRect(bounds, p)
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
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
