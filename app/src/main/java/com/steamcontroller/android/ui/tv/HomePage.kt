package com.steamcontroller.android.ui.tv

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.steamcontroller.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.steamcontroller.android.hub.GamehubTv
import com.steamcontroller.android.hub.GamehubTv.Shot
import com.steamcontroller.android.ui.FocusMemoryFrame
import com.steamcontroller.android.ui.Host
import com.steamcontroller.android.ui.Page
import com.steamcontroller.android.ui.PageId
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.FocusFx
import com.steamcontroller.android.ui.theme.Motion
import com.steamcontroller.android.ui.theme.dp
import com.steamcontroller.android.ui.widget.DotView
import com.steamcontroller.android.ui.widget.Kit

/**
 * HOME: the showcase. A full-bleed hero that slowly rotates through favourites and the latest
 * session (the capture itself, the game's official logo as its title), a Now-playing card while
 * Steam says a game is running, then the hub's rows, D-pad scrollable, each opening show mode.
 *
 * Renders the cached home instantly and swaps in the hub's copy when it arrives; it never waits
 * on the network.
 */
class HomePage(host: Host) : Page(host) {
    override val fullBleed: Boolean get() = true

    private val res = ctx.resources
    private val screenW = res.displayMetrics.widthPixels
    private val screenH = res.displayMetrics.heightPixels
    private val safeH = res.getDimensionPixelSize(R.dimen.safe_h)
    private val safeV = res.getDimensionPixelSize(R.dimen.safe_v)
    private val left = safeH + ctx.dp(64 + 8)
    private val heroH = (screenH * 0.70f).toInt()
    private val keylineTop = safeV + ctx.dp(48 + 14)

    private val root = FrameLayout(ctx)
    private val heroLayer = FrameLayout(ctx)
    private val heroA = ImageView(ctx)
    private val heroB = ImageView(ctx)
    private var heroFront = heroA
    private var drift: android.animation.Animator? = null
    private val scroll = HomeScroll(ctx)
    private val column = LinearLayout(ctx)
    private val heroBlock = FrameLayout(ctx)
    private val heroText = LinearLayout(ctx)
    private val eyebrow = Kit.label(ctx, "", 12f, C.text2)
    private val logo = ImageView(ctx)
    private val heroTitle = Kit.title(ctx, "", 44f)
    private val heroMeta = Kit.body(ctx, "", 16f, C.text2)
    private val buttons = Kit.row(ctx)
    private val playBtn = Kit.button(ctx, ctx.getString(R.string.tv_play_show), Kit.Btn.PRIMARY, R.drawable.ic_play) { playHero() }
    private val favBtn = Kit.button(ctx, ctx.getString(R.string.tv_all_favourites), Kit.Btn.SECONDARY, R.drawable.ic_heart) { playFavourites() }
    private val dots = Kit.row(ctx)
    private val status = Kit.label(ctx, "", 11f, C.text3)
    private val nowCard = NowPlayingCard(ctx)
    private val rowsBox = LinearLayout(ctx)
    private val topFade = View(ctx)

    private val handler = Handler(Looper.getMainLooper())
    private var home: GamehubTv.Home? = null
    private var signature = ""
    private var heroList: List<Shot> = emptyList()
    private var heroIndex = 0
    private var shown = false
    private var mode: Mode = Mode.LOADING
    private var stateAction: (() -> Unit)? = null
    private var stateAction2: (() -> Unit)? = null

    private enum class Mode { LOADING, CONTENT, MESSAGE }

    private val listener: (HomeRepo.State) -> Unit = { render(it) }

    override val view: View = build()
    override val defaultFocus: View? get() = if (playBtn.isShown) playBtn else buttons.getChildAt(0)

    private fun build(): View {
        for (iv in listOf(heroA, heroB)) {
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            heroLayer.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        heroB.alpha = 0f
        // Soft scrims: the rail and the text on the left, the strip at the top, the rows below.
        heroLayer.addView(View(ctx).apply { background = Scrim(intArrayOf(C.alpha(C.bg, 0.92f), C.alpha(C.bg, 0.55f), C.alpha(C.bg, 0f)), floatArrayOf(0f, 0.38f, 0.75f), horizontal = true) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        heroLayer.addView(View(ctx).apply { background = Scrim(intArrayOf(C.alpha(C.bg, 0.7f), C.alpha(C.bg, 0f)), null) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(150)))
        heroLayer.addView(View(ctx).apply { background = Scrim(intArrayOf(C.alpha(C.bg, 0f), C.alpha(C.bg, 0.75f), C.bg), floatArrayOf(0f, 0.55f, 1f)) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (screenH * 0.62f).toInt(), Gravity.BOTTOM))
        root.addView(heroLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        column.orientation = LinearLayout.VERTICAL
        column.clipChildren = false
        column.clipToPadding = false
        buildHero()
        column.addView(heroBlock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heroH))
        rowsBox.orientation = LinearLayout.VERTICAL
        rowsBox.clipChildren = false
        column.addView(rowsBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Room for the last row to reach the keyline.
        column.addView(View(ctx), LinearLayout.LayoutParams(1, screenH - keylineTop - ctx.dp(240)))
        scroll.addView(column)
        scroll.target = { focused -> targetScroll(focused) }
        scroll.onScrolled = { y -> onScrolled(y) }
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Rows scrolled above the keyline fade out under the status strip instead of colliding with it.
        topFade.background = Scrim(intArrayOf(C.bg, C.alpha(C.bg, 0.92f), C.alpha(C.bg, 0f)), floatArrayOf(0f, 0.6f, 1f))
        topFade.alpha = 0f
        root.addView(topFade, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keylineTop + ctx.dp(6)))
        return root
    }

    private fun buildHero() {
        heroBlock.clipChildren = false
        heroText.orientation = LinearLayout.VERTICAL
        heroText.clipChildren = false
        heroText.gravity = Gravity.BOTTOM
        heroText.addView(eyebrow)
        logo.scaleType = ImageView.ScaleType.FIT_START
        logo.adjustViewBounds = false
        heroText.addView(logo, LinearLayout.LayoutParams(ctx.dp(340), ctx.dp(84)).apply { topMargin = ctx.dp(12) })
        heroTitle.maxLines = 2
        heroTitle.setShadowLayer(ctx.dp(12f), 0f, 0f, C.alpha(C.bg, 0.6f))
        heroText.addView(heroTitle, LinearLayout.LayoutParams(ctx.dp(520), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(10) })
        heroMeta.maxLines = 2
        heroText.addView(heroMeta, LinearLayout.LayoutParams(ctx.dp(520), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(10) })
        buttons.clipChildren = false
        buttons.addView(playBtn)
        buttons.addView(favBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dp(6) })
        heroText.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(20); marginStart = -ctx.dp(3) })
        val foot = Kit.row(ctx)
        foot.addView(dots)
        foot.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dp(14) })
        heroText.addView(foot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dp(20)).apply { topMargin = ctx.dp(14) })
        heroBlock.addView(heroText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            marginStart = left
            bottomMargin = ctx.dp(18)
        })
        nowCard.visibility = View.GONE
        nowCard.setOnClickListener { home?.nowPlaying?.let { playGame(it.game) } }
        heroBlock.addView(nowCard, FrameLayout.LayoutParams(ctx.dp(NowPlayingCard.W + 6), ctx.dp(NowPlayingCard.H + 6), Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = safeH - ctx.dp(3)
            bottomMargin = ctx.dp(18 + 14 + 20 - 3)
        })
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────────────

    override fun onShow() {
        shown = true
        HomeRepo.observe(ctx, listener)
        HomeRepo.refresh(ctx, 20_000L)
        scheduleHero()
    }

    override fun onHide() {
        shown = false
        HomeRepo.remove(listener)
        handler.removeCallbacksAndMessages(null)
        drift?.cancel()
    }

    override fun refresh() {
        if (shown) HomeRepo.refresh(ctx, 5_000L)
    }

    private var released = false

    override fun releaseImages() {
        released = true
        drift?.cancel()
        for (iv in listOf(heroA, heroB, logo)) Img.release(iv)
        nowCard.release()
        for (v in rowViews()) {
            for (k in 0 until v.list.childCount) (v.list.getChildAt(k) as? Tile)?.let { Img.release(it.image) }
            v.list.recycledViewPool.clear()
        }
    }

    override fun restoreImages() {
        if (!released) return
        released = false
        if (mode == Mode.CONTENT) showHero(heroIndex, animate = false)
        home?.nowPlaying?.let { nowCard.bind(it) }
        for (v in rowViews()) v.list.adapter?.let { it.notifyItemRangeChanged(0, it.itemCount) }
    }

    /** Back: rows first scroll back up to the hero, then the shell takes over (rail, exit). */
    override fun onBack(): Boolean {
        if (rowsBox.hasFocus()) {
            playBtn.takeIf { it.isShown }?.requestFocus() ?: buttons.getChildAt(0)?.requestFocus()
            return true
        }
        return false
    }

    override fun onKey(event: KeyEvent): Boolean = false

    // ── rendering ─────────────────────────────────────────────────────────────────────

    private fun render(s: HomeRepo.State) {
        when (s) {
            is HomeRepo.State.Loading -> if (mode != Mode.CONTENT) message(ctx.getString(R.string.tv_loading_eyebrow), ctx.getString(R.string.tv_loading_title), null, null, null)
            is HomeRepo.State.NotLinked -> message(ctx.getString(R.string.tv_link_eyebrow), ctx.getString(R.string.tv_link_title), ctx.getString(R.string.tv_link_body),
                ctx.getString(R.string.tv_link_button) to { host.go(PageId.GAMEHUB, focusContent = true) },
                ctx.getString(R.string.tv_controller_settings) to { host.go(PageId.CONTROLLER, focusContent = true) })
            is HomeRepo.State.Failed -> failed(s.err)
            is HomeRepo.State.Ready -> content(s)
        }
    }

    private fun failed(err: GamehubTv.Res.Err) {
        when (err.fail) {
            GamehubTv.Fail.NOT_DEPLOYED -> message(ctx.getString(R.string.tv_update_eyebrow), ctx.getString(R.string.tv_update_title), ctx.getString(R.string.tv_update_body, err.message),
                ctx.getString(R.string.tv_try_again) to { HomeRepo.refresh(ctx) },
                ctx.getString(R.string.tv_controller_settings) to { host.go(PageId.CONTROLLER, focusContent = true) })
            GamehubTv.Fail.UNAUTHORIZED, GamehubTv.Fail.NOT_LINKED -> message(ctx.getString(R.string.tv_link_eyebrow), ctx.getString(R.string.tv_relink_title), ctx.getString(R.string.tv_relink_body),
                ctx.getString(R.string.tv_link_button) to { host.go(PageId.GAMEHUB, focusContent = true) }, null)
            else -> message(ctx.getString(R.string.tv_offline_eyebrow), ctx.getString(R.string.tv_offline_title), ctx.getString(R.string.tv_offline_body, err.message),
                ctx.getString(R.string.tv_try_again) to { HomeRepo.refresh(ctx) },
                ctx.getString(R.string.tv_controller_settings) to { host.go(PageId.CONTROLLER, focusContent = true) })
        }
    }

    /** A hero-sized message with up to two actions (link, update, offline, empty). */
    private fun message(eyebrowText: String, title: String, body: String?, a: Pair<String, () -> Unit>?, b: Pair<String, () -> Unit>?) {
        val hadFocus = root.hasFocus()
        mode = Mode.MESSAGE
        home = null
        signature = ""
        heroList = emptyList()
        rowsBox.removeAllViews()
        nowCard.visibility = View.GONE
        dots.removeAllViews()
        status.text = ""
        eyebrow.text = eyebrowText
        eyebrow.setTextColor(C.accent)
        logo.visibility = View.GONE
        heroTitle.visibility = View.VISIBLE
        heroTitle.text = title
        heroMeta.text = body ?: ""
        heroMeta.visibility = if (body == null) View.GONE else View.VISIBLE
        stateAction = a?.second
        stateAction2 = b?.second
        playBtn.text = a?.first ?: ""
        playBtn.setCompoundDrawablesRelative(null, null, null, null)
        playBtn.setOnClickListener { stateAction?.invoke() }
        playBtn.visibility = if (a == null) View.GONE else View.VISIBLE
        favBtn.text = b?.first ?: ""
        favBtn.setCompoundDrawablesRelative(null, null, null, null)
        favBtn.setOnClickListener { stateAction2?.invoke() }
        favBtn.visibility = if (b == null) View.GONE else View.VISIBLE
        heroA.setImageDrawable(null); heroB.setImageDrawable(null)
        heroLayer.background = HeroGlow()
        if (hadFocus && a != null) playBtn.requestFocus()
    }

    private fun content(s: HomeRepo.State.Ready) {
        val h = s.home
        HomeTiming.mark(if (s.live) "render-live" else "render-cached", "rows=${h.rows.size} hero=${h.hero.size}")
        status.text = when {
            s.problem == null -> ""
            s.problem.fail == GamehubTv.Fail.NOT_DEPLOYED -> ctx.getString(R.string.tv_status_update)
            else -> ctx.getString(R.string.tv_status_saved)
        }
        val sig = signatureOf(h)
        val first = mode != Mode.CONTENT
        home = h
        renderNowPlaying(h.nowPlaying)
        if (!first && sig == signature) return
        signature = sig
        if (h.hero.isEmpty() && h.rows.isEmpty()) {
            message(ctx.getString(R.string.tv_empty_eyebrow), ctx.getString(R.string.tv_empty_title), ctx.getString(R.string.tv_empty_body), null,
                ctx.getString(R.string.tv_controller_settings) to { host.go(PageId.CONTROLLER, focusContent = true) })
            return
        }
        val hadFocus = root.hasFocus()
        if (mode != Mode.CONTENT) {
            mode = Mode.CONTENT
            // A soft glow under the hero until its picture arrives: never a black screen.
            heroLayer.background = HeroGlow()
            playBtn.text = ctx.getString(R.string.tv_play_show)
            playBtn.setCompoundDrawablesRelative(Kit.icon(ctx, R.drawable.ic_play, 16, C.accent), null, null, null)
            playBtn.setOnClickListener { playHero() }
            playBtn.visibility = View.VISIBLE
            favBtn.text = ctx.getString(R.string.tv_all_favourites)
            favBtn.setCompoundDrawablesRelative(Kit.icon(ctx, R.drawable.ic_heart, 16, C.text), null, null, null)
            favBtn.setOnClickListener { playFavourites() }
            favBtn.visibility = View.VISIBLE
        }
        val keepId = heroList.getOrNull(heroIndex)?.id
        val newHero = h.hero.ifEmpty { h.rows.firstOrNull { it.shots.isNotEmpty() }?.shots?.take(8) ?: emptyList() }
        val heroChanged = first || newHero.map { it.id } != heroList.map { it.id }
        heroList = newHero
        if (heroChanged) {
            heroIndex = heroList.indexOfFirst { it.id == keepId }.coerceAtLeast(0)
            buildDots()
            // The same picture stays up: only a new one crossfades in.
            if (first || heroList.getOrNull(heroIndex)?.id != keepId) showHero(heroIndex, animate = !first)
            prefetchHero()
        }
        learnArt(h)
        ensureGameArt()
        updateRows(h, hadFocus)
    }

    private fun signatureOf(h: GamehubTv.Home): String = buildString {
        for (s in h.hero) append(s.id).append(if (s.favorite) '+' else '-')
        append('|')
        for (r in h.rows) {
            append(r.id).append(':').append(r.count).append(':')
            for (s in r.shots) append(s.id).append(if (s.favorite) '+' else '-')
            for (c in r.colls) append(c.collection).append(c.count)
            append(';')
        }
    }

    // ── hero ──────────────────────────────────────────────────────────────────────────

    private fun buildDots() {
        dots.removeAllViews()
        if (heroList.size < 2) return
        for (i in heroList.indices) {
            val v = View(ctx)
            dots.addView(v, LinearLayout.LayoutParams(ctx.dp(6), ctx.dp(6)).apply { marginEnd = ctx.dp(6) })
        }
        paintDots()
    }

    private fun paintDots() {
        for (i in 0 until dots.childCount) {
            val v = dots.getChildAt(i)
            val on = i == heroIndex
            v.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (on) C.text else C.alpha(C.text, 0.28f))
                cornerRadius = ctx.dp(3f)
            }
            v.layoutParams = (v.layoutParams as LinearLayout.LayoutParams).apply { width = ctx.dp(if (on) 18 else 6) }
        }
    }

    private fun showHero(i: Int, animate: Boolean) {
        val shot = heroList.getOrNull(i) ?: return
        heroIndex = i
        paintDots()
        val back = if (heroFront === heroA) heroB else heroA
        val spec = heroSpec
        // The hero's preview jumps every queue; it never waits on an original or on thumbnails.
        Img.into(back, shot.previewUrl, spec, fade = false, urgent = true, full = shot.fullUrl) { _ ->
            if (heroList.getOrNull(heroIndex)?.id != shot.id || back.drawable == null) return@into
            HomeTiming.mark("hero-image")
            // The incoming image goes directly above the outgoing one, still under the scrims.
            heroLayer.removeView(back)
            heroLayer.addView(back, 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            drift?.cancel()
            drift = null
            back.scaleX = 1f; back.scaleY = 1f
            back.alpha = if (animate) 0f else 1f
            back.animate().alpha(1f).setDuration(if (animate) 900L else 1L).setInterpolator(DecelerateInterpolator()).start()
            heroFront = back
            // A slow drift while it is on screen, only with Slow zoom on (off by default).
            if (!TvPrefs.slowZoom(ctx)) return@into
            drift = android.animation.ObjectAnimator.ofPropertyValuesHolder(back,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.04f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.04f)).apply {
                duration = HERO_MS + 1500L
                interpolator = LinearInterpolator()
                start()
            }
        }
        val fill = {
            eyebrow.setTextColor(C.text2)
            eyebrow.text = eyebrowFor(shot)
            val logoUrl = shot.art?.logo
            if (logoUrl != null) {
                logo.visibility = View.VISIBLE
                heroTitle.visibility = View.GONE
                Img.into(logo, logoUrl, Img.Spec(ctx.dp(340), alpha = true)) { bmp ->
                    if (bmp == null && heroList.getOrNull(heroIndex)?.id == shot.id) { logo.visibility = View.GONE; heroTitle.visibility = View.VISIBLE }
                }
            } else {
                logo.visibility = View.GONE
                heroTitle.visibility = View.VISIBLE
            }
            heroTitle.text = shot.gameOrUnknown()
            heroMeta.text = Fmt.meta(shot)
            heroMeta.visibility = View.VISIBLE
        }
        if (animate) {
            heroText.animate().alpha(0f).setDuration(Motion.FAST).withEndAction {
                fill()
                heroText.animate().alpha(1f).setDuration(Motion.SLOW).start()
            }.start()
        } else fill()
    }

    private fun eyebrowFor(s: Shot): String {
        val latest = home?.rows?.firstOrNull { it.kind.startsWith("session") || it.id.startsWith("session") }
        return when {
            latest != null && latest.shots.any { it.id == s.id } -> ctx.getString(R.string.tv_hero_session)
            s.favorite -> ctx.getString(R.string.tv_hero_favourite)
            else -> ctx.getString(R.string.tv_hero_recent)
        }
    }

    /** Full screen, CENTER_CROP: an ultrawide capture is sized by the screen's height. */
    private val heroSpec = Img.coverPx(screenW, screenH)

    private fun prefetchHero() {
        val spec = heroSpec
        heroList.getOrNull((heroIndex + 1) % heroList.size.coerceAtLeast(1))?.let { Img.prefetch(it.previewUrl, spec) }
    }

    private val heroTick = Runnable { advanceHero() }

    private fun scheduleHero() {
        handler.removeCallbacks(heroTick)
        if (shown) handler.postDelayed(heroTick, HERO_MS)
    }

    private fun advanceHero() {
        if (!shown) return
        // Only while the hero is on screen; the rows have the stage otherwise.
        if (mode == Mode.CONTENT && heroList.size > 1 && scroll.scrollY < heroH / 3 && !host.showOpen) {
            showHero((heroIndex + 1) % heroList.size, animate = true)
            prefetchHero()
        }
        scheduleHero()
    }

    private fun renderNowPlaying(np: GamehubTv.NowPlaying?) {
        if (np == null) {
            if (nowCard.hasFocus()) playBtn.requestFocus()
            nowCard.visibility = View.GONE
            return
        }
        nowCard.bind(np)
        nowCard.visibility = View.VISIBLE
    }

    private fun onScrolled(y: Int) {
        val t = (y.toFloat() / heroH).coerceIn(0f, 1f)
        heroLayer.alpha = 1f - 0.65f * t
        // The hero's text leaves before it can reach the strip.
        val text = (1f - y / (heroH * 0.4f)).coerceIn(0f, 1f)
        heroText.alpha = text
        nowCard.alpha = text
        topFade.alpha = (y / ctx.dp(48f)).coerceIn(0f, 1f)
    }

    // ── rows ──────────────────────────────────────────────────────────────────────────

    private class RowView(ctx: Context, var row: GamehubTv.Row, val kind: TileKind) : LinearLayout(ctx) {
        val memory = FocusMemoryFrame(ctx)
        lateinit var list: TvRecycler
        val header = Kit.label(ctx, "", 12.5f, C.text2)
        val count = Kit.label(ctx, "", 11f, C.text3)
        /** The game's official logo, small, on rows of one game. */
        val logo = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_START; visibility = View.GONE }
    }

    /** One game in the row (a session, the Patina leg): its logo next to the title, when known. */
    private fun rowLogo(v: RowView) {
        val game = v.row.shots.map { it.game }.distinct().singleOrNull()
        val url = GameArt.get(game)?.logo
        v.header.text = v.row.title
        if (url == null || game == null) { v.logo.visibility = View.GONE; return }
        Img.into(v.logo, url, Img.Spec(ctx.dp(120), alpha = true)) { b ->
            v.logo.visibility = if (b != null) View.VISIBLE else View.GONE
            // The logo names the game: "Last session · Elden Ring" becomes "Last session".
            if (b != null) v.header.text = v.row.title.removeSuffix(" · $game").removeSuffix(" - $game")
        }
    }

    private fun learnArt(h: GamehubTv.Home) {
        for (s in h.hero) if (s.game != null) GameArt.put(s.game, s.art)
        h.nowPlaying?.let { GameArt.put(it.game, it.art) }
        for (r in h.rows) GameArt.putAll(r.colls)
    }

    private var askedGames = false

    /** Logos for row titles come from the games list: fetch it once in the background. */
    private fun ensureGameArt() {
        if (askedGames) return
        askedGames = true
        host.activity.lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { GamehubTv.cachedList(ctx, "games") ?: GamehubTv.games(ctx).valueOrNull() }
            if (list != null) { GameArt.putAll(list); for (v in rowViews()) rowLogo(v) }
        }
    }

    private fun rowViews(): List<RowView> = (0 until rowsBox.childCount).map { rowsBox.getChildAt(it) as RowView }

    /**
     * Rows are updated in place by id: a refresh (the one-minute tick, a favourite from show
     * mode) changes the cards that changed and nothing else, so focus and scroll stay put. Only
     * rows that are new, gone or changed kind are built or removed.
     */
    private fun updateRows(h: GamehubTv.Home, hadFocus: Boolean) {
        val existing = rowViews().associateBy { it.row.id }
        val focusedRow = rowViews().firstOrNull { it.hasFocus() }
        val focusedPos = focusedRow?.let { rv -> rv.list.findFocus()?.let { f -> rv.list.findContainingViewHolder(f)?.bindingAdapterPosition } } ?: -1
        val wanted = h.rows.map { r ->
            val old = existing[r.id]
            if (old != null && old.kind == tileKind(r)) { refreshRow(old, r); old } else buildRow(r)
        }
        if (wanted != rowViews()) {
            for (v in rowViews()) if (v !in wanted) rowsBox.removeView(v)
            for ((i, v) in wanted.withIndex()) {
                if (rowsBox.getChildAt(i) === v) continue
                (v.parent as? ViewGroup)?.removeView(v)
                rowsBox.addView(v, i)
            }
        }
        // The focused row went away: land on the same place in its replacement, or the hero.
        if (focusedRow != null && !focusedRow.hasFocus() && !root.hasFocus()) {
            val target = wanted.firstOrNull { it.row.id == focusedRow.row.id }
            if (target != null) focusRowItem(target, focusedPos) else playBtn.requestFocus()
        } else if (hadFocus && !root.hasFocus()) {
            playBtn.requestFocus()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun refreshRow(v: RowView, r: GamehubTv.Row) {
        v.row = r
        rowLogo(v)
        v.count.text = rowMeta(r)
        val a = v.list.adapter ?: return
        if (r.shots.isNotEmpty()) {
            (a as SimpleAdapter<GamehubTv.Shot>).update(r.shots, { it.id }) { x, y -> x.favorite == y.favorite && x.game == y.game && x.capturedAt == y.capturedAt }
            // Show mode flips favourites on shared objects; re-bind in place (same holders, focus kept).
            a.notifyItemRangeChanged(0, a.itemCount, "favourite")
        }
        else (a as SimpleAdapter<GamehubTv.Coll>).update(r.colls, { it.collection }) { x, y -> x.title == y.title && x.count == y.count && x.cover?.id == y.cover?.id && x.art == y.art && x.avatar == y.avatar }
    }

    private fun focusRowItem(v: RowView, pos: Int) {
        val n = v.list.adapter?.itemCount ?: 0
        if (n == 0) { playBtn.requestFocus(); return }
        val p = pos.coerceIn(0, n - 1)
        val shown = v.list.layoutManager?.findViewByPosition(p)
        if (shown != null && shown.requestFocus()) return
        v.list.scrollToPosition(p)
        v.list.post { v.list.layoutManager?.findViewByPosition(p)?.requestFocus() ?: v.memory.requestFocus() }
    }

    /** Where focus was when show mode opened: (row id, position), or null for the hero. */
    private var showOrigin: Pair<String, Int>? = null

    private fun rememberOrigin(v: View?) {
        showOrigin = null
        val row = rowViews().firstOrNull { it.hasFocus() } ?: return
        val pos = v?.let { row.list.findContainingViewHolder(it)?.bindingAdapterPosition } ?: return
        if (pos >= 0) showOrigin = row.row.id to pos
    }

    override fun restoreFocus(): Boolean {
        val (id, pos) = showOrigin ?: return false
        showOrigin = null
        val v = rowViews().firstOrNull { it.row.id == id } ?: return false
        focusRowItem(v, pos)
        return true
    }

    private fun buildRow(r: GamehubTv.Row): RowView {
        val v = RowView(ctx, r, tileKind(r))
        v.orientation = LinearLayout.VERTICAL
        v.clipChildren = false
        v.clipToPadding = false
        val head = Kit.row(ctx)
        head.addView(v.logo, LinearLayout.LayoutParams(ctx.dp(120), ctx.dp(22)).apply { marginEnd = ctx.dp(12) })
        head.addView(v.header)
        v.count.text = rowMeta(r)
        head.addView(v.count, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dp(12) })
        rowLogo(v)
        v.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = left + ctx.dp(3) })

        val list = TvRecycler(ctx, TvRecycler.LOCK_RIGHT)
        v.list = list
        list.setPadding(left, 0, safeH, 0)
        list.layoutManager = KeylineLinear(ctx, LinearLayoutManagerH, keyline = { left }).apply { initialPrefetchItemCount = 6 }
        list.setItemViewCacheSize(4)
        val kind = v.kind
        val wDp = kind.w
        val gap = ctx.dp(12)
        list.addItemDecoration(object : androidx.recyclerview.widget.RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: androidx.recyclerview.widget.RecyclerView, state: androidx.recyclerview.widget.RecyclerView.State) {
                outRect.right = gap
            }
        })
        // The card's real pixels at the 85 % density, at its focused size (Tile scales 1.05).
        val spec = Img.cover(ctx, wDp, kind.h, Tile.FOCUS_SCALE)
        if (r.shots.isNotEmpty()) {
            list.adapter = SimpleAdapter(r.shots, { Tile(ctx, kind.w, kind.h, captionOnFocus = true) }) { view, shot, i ->
                val t = view as Tile
                Img.into(t.image, shot.previewUrl, spec, full = shot.fullUrl) { b -> if (b != null) HomeTiming.mark("first-row-image") }
                t.setFavorite(shot.favorite)
                t.setCaption(shot.gameOrUnknown(), Fmt.dateShort(shot) + "  ·  " + Fmt.time(shot))
                t.card.holdItem(HoldItem(shot, v.row.collection?.let { CollRef(it, v.row.title) }, v.row.shots))
                // Positions can move with in-place updates: ask at click time.
                t.card.setOnClickListener { val p = list.getChildAdapterPosition(t); if (p >= 0) { rememberOrigin(t.card); playRow(v.row, p) } }
                t.focusListener = { has -> v.header.setTextColor(if (has) C.text else C.text2) }
                prefetchAhead(v.row.shots, i, spec)
            }
        } else {
            list.adapter = SimpleAdapter(r.colls, { Tile(ctx, kind.w, kind.h, captionOnFocus = kind.captionOnFocus, circle = kind.circle) }) { view, coll, i ->
                val t = view as Tile
                bindColl(t, coll, kind, spec)
                t.card.setOnClickListener { val p = list.getChildAdapterPosition(t); if (p >= 0) { rememberOrigin(t.card); playColls(v.row, p) } }
                t.focusListener = { has -> v.header.setTextColor(if (has) C.text else C.text2) }
            }
        }
        v.memory.clipChildren = false
        v.memory.fallback = {
            val lm = list.layoutManager as KeylineLinear
            lm.findViewByPosition(lm.findFirstCompletelyVisibleItemPosition().coerceAtLeast(lm.findFirstVisibleItemPosition()).coerceAtLeast(0))
        }
        v.memory.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        v.addView(v.memory, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(10) })
        v.setPadding(0, 0, 0, ctx.dp(26))
        v.header.setTextColor(C.text2)
        return v
    }

    private fun rowMeta(r: GamehubTv.Row): String {
        val game = r.shots.map { it.game }.distinct().singleOrNull()
        val n = if (r.count > 0) r.count else r.shots.size + r.colls.size
        return when {
            r.shots.isNotEmpty() && game != null && !r.title.contains(game, ignoreCase = true) -> "$game  ·  ${Fmt.count(n)}"
            r.shots.isNotEmpty() -> Fmt.count(n)
            else -> n.toString()
        }
    }

    internal enum class TileKind(val w: Int, val h: Int, val captionOnFocus: Boolean, val circle: Boolean) {
        SHOT(208, 117, true, false),
        WIDE(208, 117, false, false),
        GAME(120, 180, true, false),
        PERSON(112, 112, false, true),
    }

    private fun tileKind(r: GamehubTv.Row): TileKind = when {
        r.shots.isNotEmpty() -> TileKind.SHOT
        r.kind.startsWith("game") -> TileKind.GAME
        r.kind.startsWith("people") || r.kind.startsWith("person") -> TileKind.PERSON
        else -> TileKind.WIDE
    }

    private fun bindColl(t: Tile, c: GamehubTv.Coll, kind: TileKind, spec: Img.Spec) {
        when (kind) {
            TileKind.GAME -> {
                val grid = c.art?.grid
                // A cover spec: a 16:9 capture in the 2:3 card is sized by its height.
                Img.into(t.image, grid ?: c.cover?.previewUrl, spec)
                t.setOver(if (grid == null) c.title else null)
                t.setCaption(if (grid == null) null else c.title, Fmt.count(c.count))
            }
            TileKind.PERSON -> {
                Img.into(t.image, c.avatar ?: c.cover?.previewUrl, spec)
                t.setCaption(c.title, if (c.count > 0) Fmt.count(c.count) else c.subtitle)
            }
            else -> {
                Img.into(t.image, c.cover?.previewUrl, spec)
                t.setCaption(c.title, if (c.count > 0) Fmt.count(c.count) else c.subtitle)
            }
        }
    }

    private fun prefetchAhead(list: List<Shot>, i: Int, spec: Img.Spec) {
        for (k in i + 1..minOf(i + 3, list.size - 1)) Img.prefetch(list[k].previewUrl, spec)
    }

    /** Vertical keyline: the hero at the top, otherwise the focused row's top under the strip. */
    private fun targetScroll(focused: View): Int {
        var v: View = focused
        while (true) {
            val p = v.parent as? View ?: return scroll.scrollY
            if (p === rowsBox) return (rowsBox.top + v.top - keylineTop).coerceAtLeast(0)
            if (p === column) return 0
            v = p
        }
    }

    // ── show mode ─────────────────────────────────────────────────────────────────────

    private fun shotRows(): List<GamehubTv.Row> = home?.rows?.filter { it.shots.isNotEmpty() && it.collection != null } ?: emptyList()

    private fun siblings(): List<CollRef> {
        val out = ArrayList<CollRef>()
        if (heroList.isNotEmpty()) out.add(CollRef(null, ctx.getString(R.string.tv_highlights), heroList))
        for (r in shotRows()) out.add(CollRef(r.collection, r.title))
        return out
    }

    private fun playHero() {
        showOrigin = null
        if (heroList.isEmpty()) return
        host.openShow(ShowRequest(siblings(), 0, startShotId = heroList.getOrNull(heroIndex)?.id))
    }

    private fun playFavourites() {
        showOrigin = null
        val sib = siblings()
        val i = sib.indexOfFirst { it.collection == "favourites" || it.collection == "favorites" }
        if (i >= 0) host.openShow(ShowRequest(sib, i))
        else host.openShow(ShowRequest(listOf(CollRef("favourites", ctx.getString(R.string.tv_favourites))) + sib, 0))
    }

    private fun playGame(game: String) {
        showOrigin = null
        host.openShow(ShowRequest(listOf(CollRef("game:$game", game)), 0))
    }

    private fun playRow(r: GamehubTv.Row, i: Int) {
        val sib = siblings()
        val idx = sib.indexOfFirst { it.collection == r.collection }
        host.openShow(ShowRequest(sib, idx.coerceAtLeast(0), startShotId = r.shots.getOrNull(i)?.id, preload = r.shots))
    }

    private fun playColls(r: GamehubTv.Row, i: Int) {
        // Games open their hub; albums, people and sessions play.
        val c = r.colls.getOrNull(i) ?: return
        if (c.collection.startsWith("game:")) { host.openGame(c.collection.removePrefix("game:")); return }
        host.openShow(ShowRequest(r.colls.map { CollRef(it.collection, it.title) }, i))
    }

    private companion object {
        const val HERO_MS = 9_000L
        const val LinearLayoutManagerH = androidx.recyclerview.widget.RecyclerView.HORIZONTAL
    }
}

/** The vertical scroller of the home page: it never scrolls by itself; [target] decides. */
class HomeScroll(ctx: Context) : ScrollView(ctx) {
    var target: ((View) -> Int)? = null
    var onScrolled: ((Int) -> Unit)? = null

    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        clipChildren = false
        isSmoothScrollingEnabled = true
        isFillViewport = true
    }

    override fun executeKeyEvent(event: KeyEvent): Boolean = false
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?): Int = 0

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        val f = focused ?: return
        val y = target?.invoke(f) ?: return
        if (y != scrollY) smoothScrollTo(0, y)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScrolled?.invoke(t)
    }
}

/** A faint Steam-blue light for the message states, where there is no capture to show. */
private class HeroGlow : android.graphics.drawable.Drawable() {
    private val p = android.graphics.Paint()
    override fun onBoundsChange(b: Rect) {
        p.shader = android.graphics.RadialGradient(b.width() * 0.72f, b.height() * 0.2f, b.width() * 0.6f,
            intArrayOf(C.alpha(C.accent, 0.16f), C.alpha(C.accent, 0f)), null, android.graphics.Shader.TileMode.CLAMP)
    }
    override fun draw(c: android.graphics.Canvas) { c.drawColor(C.bg); c.drawRect(bounds, p) }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
}

/** The Now-playing card: official hero art, the logo, and how long the game has been running. */
class NowPlayingCard(ctx: Context) : FrameLayout(ctx) {
    private val art = ImageView(ctx)
    private val logo = ImageView(ctx)
    private val name = Kit.title(ctx, "", 20f)
    private val label = Kit.label(ctx, "", 11f, C.success)
    private val dot = DotView(ctx, 7f).apply { color = C.success; halo = true }
    private var game: String? = null

    init {
        val pad = dp(3)
        setPadding(pad, pad, pad, pad)
        foreground = FocusRing(resources.displayMetrics.density, 10f)
        clipChildren = false
        FocusFx.attach(this, 1.04f)
        isClickable = true
        val inner = FrameLayout(ctx)
        inner.roundCorners(10f)
        inner.setBackgroundColor(C.surface)
        art.scaleType = ImageView.ScaleType.CENTER_CROP
        inner.addView(art, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        inner.addView(View(ctx).apply { background = Scrim(intArrayOf(C.alpha(C.bg, 0.92f), C.alpha(C.bg, 0.35f)), null, horizontal = true) },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(dot)
        top.addView(label, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })
        col.addView(top)
        logo.scaleType = ImageView.ScaleType.FIT_START
        col.addView(logo, LinearLayout.LayoutParams(dp(200), dp(46)).apply { topMargin = dp(8) })
        name.maxLines = 1
        col.addView(name, LinearLayout.LayoutParams(dp(W - 36), LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        inner.addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { setMargins(dp(16), dp(12), dp(16), dp(12)) })
        addView(inner, LayoutParams(dp(W), dp(H)))
        contentDescription = "Now playing"
    }

    fun release() {
        game = null
        Img.release(art)
        Img.release(logo)
    }

    fun bind(np: GamehubTv.NowPlaying) {
        label.text = context.getString(R.string.tv_now_playing_since, Fmt.elapsed(np.since))
        if (game == np.game) return
        game = np.game
        name.text = np.game
        val heroUrl = np.art?.hero ?: np.art?.grid
        Img.into(art, heroUrl, Img.cover(context, W, H, 1.04f))
        val logoUrl = np.art?.logo
        if (logoUrl != null) {
            logo.visibility = VISIBLE
            name.visibility = GONE
            Img.into(logo, logoUrl, Img.Spec(dp(200), alpha = true)) { b -> if (b == null) { logo.visibility = GONE; name.visibility = VISIBLE } }
        } else {
            logo.visibility = GONE
            name.visibility = VISIBLE
        }
    }

    companion object {
        const val W = 300
        const val H = 112
    }
}
