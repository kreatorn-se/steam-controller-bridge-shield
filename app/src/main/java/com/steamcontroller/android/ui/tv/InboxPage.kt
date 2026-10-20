package com.steamcontroller.android.ui.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.steamcontroller.android.R
import com.steamcontroller.android.hub.GamehubTv
import com.steamcontroller.android.hub.GamehubTv.Shot
import com.steamcontroller.android.ui.FocusMemoryFrame
import com.steamcontroller.android.ui.Host
import com.steamcontroller.android.ui.Page
import com.steamcontroller.android.ui.PageId
import com.steamcontroller.android.ui.pill.Pill
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.FocusFx
import com.steamcontroller.android.ui.theme.Motion
import com.steamcontroller.android.ui.theme.Type
import com.steamcontroller.android.ui.theme.dp
import com.steamcontroller.android.ui.theme.surface
import com.steamcontroller.android.ui.widget.Kit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * INBOX: imports from Steam, the Deck and other sources wait here to be sorted, grouped by game.
 * On the capture: A keeps, X skips, Y favourites (and keeps), LB undoes. "Keep all" takes the
 * whole game. Every action is applied at once and sent in the background; the next captures are
 * already decoded so sorting is as fast as the thumb.
 */
class InboxPage(host: Host) : Page(host) {
    private val groups = LinkedHashMap<String, MutableList<Shot>>()
    private var game: String? = null
    private var loaded = false
    private var problem: GamehubTv.Res.Err? = null

    private data class Undo(val game: String, val shots: List<Shot>, val wasFavorite: List<Boolean>)
    private val undo = ArrayDeque<Undo>()

    private val root = FrameLayout(ctx)
    private val content = Kit.row(ctx, Gravity.TOP)
    private val listMemory = FocusMemoryFrame(ctx)
    private val list = Kit.column(ctx)
    private val listScroll = ScrollView(ctx)
    private val total = Kit.label(ctx, "", 11f, C.text3)
    private val title = Kit.title(ctx, "", 24f)
    private val sub = Kit.label(ctx, "", 11f, C.text3)
    private val card = FrameLayout(ctx)
    private val imgA = ImageView(ctx)
    private val imgB = ImageView(ctx)
    private var front = imgA
    private val stamp = Kit.label(ctx, "", 13f, C.text)
    private val nextRow = Kit.row(ctx)
    private val keepBtn: TextView
    private val skipBtn: TextView
    private val favBtn: TextView
    private val undoBtn: TextView
    private val allBtn: TextView
    private val empty = Kit.column(ctx)
    private val emptyTitle = Kit.title(ctx, "", 26f)
    private val emptyBody = Kit.body(ctx, "", 15f, C.text2)
    private val emptyBtn = Kit.button(ctx, "", Kit.Btn.SECONDARY)
    private var emptyAction: (() -> Unit)? = null

    private val imgW = 456
    private val imgH = 256
    private val nextW = 88
    private val nextH = 50

    init {
        keepBtn = keyButton("A", R.string.inbox_keep, Kit.Btn.PRIMARY) { keep(false) }
        skipBtn = keyButton("X", R.string.inbox_skip, Kit.Btn.SECONDARY) { skip() }
        favBtn = keyButton("Y", R.string.inbox_fav, Kit.Btn.SECONDARY) { keep(true) }
        undoBtn = keyButton("LB", R.string.inbox_undo, Kit.Btn.GHOST) { undo() }
        allBtn = Kit.button(ctx, "", Kit.Btn.SECONDARY) { keepAll() }
    }

    override val view: View = build()
    override val defaultFocus: View? get() = if (card.isShown) card else if (emptyBtn.isShown) emptyBtn else list.getChildAt(0)

    private fun keyButton(key: String, label: Int, style: Kit.Btn, onClick: () -> Unit): TextView =
        Kit.button(ctx, ctx.getString(label), style) { onClick() }.apply {
            setCompoundDrawablesRelative(KeyCap(ctx, key, if (style == Kit.Btn.PRIMARY) C.accent else C.text2), null, null, null)
            compoundDrawablePadding = ctx.dp(9)
        }

    private fun build(): View {
        // Left: games waiting, newest first.
        val left = Kit.column(ctx)
        left.addView(Kit.label(ctx, ctx.getString(R.string.inbox_games), 11f, C.text3), Kit.lp(l = 3, b = 2, ctx = ctx))
        left.addView(total, Kit.lp(l = 3, b = 10, ctx = ctx))
        listScroll.isVerticalScrollBarEnabled = false
        listScroll.clipToPadding = false
        listScroll.addView(list)
        listMemory.addView(listScroll)
        listMemory.fallback = { list.getChildAt(0) }
        left.addView(listMemory, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(left, LinearLayout.LayoutParams(ctx.dp(208), ViewGroup.LayoutParams.MATCH_PARENT))

        // Right: the capture being sorted.
        val right = Kit.column(ctx)
        right.clipChildren = false
        val head = Kit.row(ctx)
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(sub)
        right.addView(head, Kit.lp(l = 3, r = 3, ctx = ctx))
        card.setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
        card.foreground = FocusRing(ctx.resources.displayMetrics.density, 10f)
        card.clipChildren = false
        FocusFx.attach(card, 1.02f)
        card.isClickable = true
        card.setOnClickListener { keep(false) }
        val inner = FrameLayout(ctx)
        inner.roundCorners(10f)
        inner.setBackgroundColor(C.surface)
        for (iv in listOf(imgA, imgB)) {
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            inner.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        stamp.setPadding(ctx.dp(12), ctx.dp(7), ctx.dp(12), ctx.dp(7))
        stamp.alpha = 0f
        inner.addView(stamp, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(ctx.dp(14), ctx.dp(14), 0, 0) })
        card.addView(inner, FrameLayout.LayoutParams(ctx.dp(imgW), ctx.dp(imgH)))
        val stage = Kit.row(ctx, Gravity.TOP)
        stage.clipChildren = false
        stage.addView(card, LinearLayout.LayoutParams(ctx.dp(imgW + 6), ctx.dp(imgH + 6)))
        // Up next: a short column of what follows in this game.
        nextRow.orientation = LinearLayout.VERTICAL
        stage.addView(nextRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dp(14); topMargin = ctx.dp(3) })
        right.addView(stage, Kit.lp(t = 10, ctx = ctx))
        val btns = Kit.row(ctx)
        btns.clipChildren = false
        for ((i, b) in listOf(keepBtn, skipBtn, favBtn, undoBtn).withIndex()) btns.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) marginStart = ctx.dp(4) })
        btns.addView(Kit.grow(ctx))
        btns.addView(allBtn)
        right.addView(btns, LinearLayout.LayoutParams(ctx.dp(imgW + 6 + 14 + nextW), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(12) })
        content.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = ctx.dp(20) })
        content.clipChildren = false
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        empty.gravity = Gravity.CENTER_VERTICAL
        empty.addView(Kit.label(ctx, ctx.getString(R.string.inbox_title), 12f, C.accent))
        empty.addView(emptyTitle, Kit.lp(t = 12, ctx = ctx))
        emptyBody.maxWidth = ctx.dp(520)
        empty.addView(emptyBody, Kit.lp(t = 10, ctx = ctx))
        emptyBtn.setOnClickListener { emptyAction?.invoke() }
        empty.addView(emptyBtn, Kit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, t = 22, l = -3, ctx = ctx))
        empty.visibility = View.GONE
        root.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { marginStart = ctx.dp(3) })
        return root
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────────────

    override fun onShow() {
        if (!loaded) {
            host.activity.lifecycleScope.launch {
                val cached = withContext(Dispatchers.IO) { GamehubTv.cachedShots(ctx, "inbox") }
                if (cached != null && !loaded) setItems(cached.items)
            }
        }
        fetch()
    }

    override fun refresh() { if (loaded) fetch() }

    private var fetching = false

    // ── writes: one serial queue ──────────────────────────────────────────────────────
    // Every triage change goes through one thread in order, so an undo always lands after the
    // write it undoes. Ids with a write in flight are kept out of any list fetched meanwhile, and
    // a list fetched while writes were queued is thrown away and asked for again once they are done.

    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val inflight = HashMap<String, Int>()
    private var pendingWrites = 0
    private var writeGen = 0
    private var refillWanted = false

    private fun enqueue(ids: List<String>, work: () -> GamehubTv.Res<*>, done: (GamehubTv.Res<*>) -> Unit) {
        pendingWrites++
        writeGen++
        for (id in ids) inflight[id] = (inflight[id] ?: 0) + 1
        writer.execute {
            val r: GamehubTv.Res<*> = try { work() } catch (e: Exception) { GamehubTv.Res.Err(GamehubTv.Fail.SERVER, e.message ?: "error") }
            handler.post {
                for (id in ids) { val n = (inflight[id] ?: 1) - 1; if (n <= 0) inflight.remove(id) else inflight[id] = n }
                pendingWrites--
                done(r)
                if (pendingWrites == 0 && refillWanted) { refillWanted = false; fetch() }
            }
        }
    }

    private fun fetch() {
        // Never read the Inbox while our own changes are still on their way.
        if (pendingWrites > 0) { refillWanted = true; return }
        if (fetching) return
        fetching = true
        val gen = writeGen
        host.activity.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { GamehubTv.shots(ctx, "inbox", limit = 120) }
            fetching = false
            if (gen != writeGen) {
                // A write started while this was loading: the answer may predate it.
                if (pendingWrites > 0) refillWanted = true else fetch()
                return@launch
            }
            when (r) {
                is GamehubTv.Res.Ok -> { problem = null; setItems(r.value.items) }
                is GamehubTv.Res.Err -> {
                    problem = r
                    if (r.fail == GamehubTv.Fail.NOT_LINKED || r.fail == GamehubTv.Fail.UNAUTHORIZED) { groups.clear(); game = null; loaded = false; render() }
                    else if (!loaded) render()
                }
            }
        }
    }

    private fun setItems(items: List<Shot>) {
        val keepGame = game
        val hadFocus = root.hasFocus()
        groups.clear()
        for (s in items) if ((s.status == null || s.status == "inbox") && s.id !in inflight) groups.getOrPut(s.game ?: UNNAMED) { ArrayList() }.add(s)
        loaded = true
        game = keepGame?.takeIf { groups.containsKey(it) } ?: groups.keys.firstOrNull()
        render()
        if (hadFocus && !root.hasFocus()) defaultFocus?.requestFocus()
    }

    // ── rendering ─────────────────────────────────────────────────────────────────────

    private fun render() {
        renderList()
        val g = game
        val shots = g?.let { groups[it] }
        if (g == null || shots.isNullOrEmpty()) {
            content.visibility = View.GONE
            empty.visibility = View.VISIBLE
            val p = problem
            when {
                p != null && (p.fail == GamehubTv.Fail.NOT_LINKED || p.fail == GamehubTv.Fail.UNAUTHORIZED) -> emptyState(R.string.inbox_link_title, ctx.getString(R.string.inbox_link_body), R.string.tv_link_button) { host.go(PageId.GAMEHUB, focusContent = true) }
                p != null && p.fail == GamehubTv.Fail.NOT_DEPLOYED -> emptyState(R.string.tv_update_title, ctx.getString(R.string.tv_update_body, p.message), R.string.tv_try_again) { fetch() }
                p != null && !loaded -> emptyState(R.string.tv_offline_title, ctx.getString(R.string.tv_offline_body, p.message), R.string.tv_try_again) { fetch() }
                !loaded -> emptyState(R.string.tv_loading_title, "", 0, null)
                else -> emptyState(R.string.inbox_clear_title, ctx.getString(R.string.inbox_clear_body), R.string.tv_go_home) { host.go(PageId.HOME, focusContent = true) }
            }
            return
        }
        empty.visibility = View.GONE
        content.visibility = View.VISIBLE
        val shot = shots[0]
        title.text = label(g)
        val sources = shots.mapNotNull { it.source }.distinct()
        sub.text = (if (sources.size == 1) sources[0].uppercase() + "  ·  " else "") + ctx.getString(R.string.inbox_left, shots.size)
        allBtn.text = ctx.getString(R.string.inbox_keep_all, shots.size)
        undoBtn.alpha = if (undo.isEmpty()) 0.45f else 1f
        showImage(shot)
        renderNext(shots)
        prefetch(shots)
    }

    private fun emptyState(t: Int, body: String, button: Int, action: (() -> Unit)?) {
        emptyTitle.text = ctx.getString(t)
        emptyBody.text = body
        emptyBody.visibility = if (body.isEmpty()) View.GONE else View.VISIBLE
        emptyAction = action
        emptyBtn.visibility = if (action == null) View.GONE else View.VISIBLE
        if (button != 0) emptyBtn.text = ctx.getString(button)
    }

    private fun renderList() {
        val n = groups.values.sumOf { it.size }
        total.text = ctx.getString(R.string.inbox_total, n)
        // Rebuild only when the set of games changed; counts update in place.
        val existing = (0 until list.childCount).map { list.getChildAt(it).tag as String }
        if (existing != groups.keys.toList()) {
            val focusedGame = (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.hasFocus() }?.tag as? String
            list.removeAllViews()
            for (g in groups.keys) list.addView(gameRow(g), Kit.lp(b = 2, ctx = ctx))
            if (focusedGame != null) {
                val again = (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.tag == focusedGame }
                (again ?: list.getChildAt(0) ?: card).requestFocus()
            }
        }
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i) as LinearLayout
            val g = row.tag as String
            (row.getChildAt(1) as TextView).text = (groups[g]?.size ?: 0).toString()
            row.isActivated = g == game
            (row.getChildAt(0) as TextView).setTextColor(if (g == game) C.text else C.text2)
        }
    }

    private fun gameRow(g: String): View = Kit.row(ctx).apply {
        tag = g
        minimumHeight = ctx.dp(46)
        setPadding(ctx.dp(15), 0, ctx.dp(15), 0)
        surface(radiusDp = 8f, fill = 0, stroke = 0, focusable = true, activeFill = C.surface2, scale = 1.02f)
        val name = Kit.body(ctx, label(g), 15f, C.text2, medium = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(Kit.value(ctx, "", 13f, C.text3), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dp(8) })
        setOnClickListener { selectGame(g); card.requestFocus() }
        setOnFocusChangeListenerChainedLocal { has -> if (has && game != g) { pendingGame = g; handler.removeCallbacks(selectPending); handler.postDelayed(selectPending, 220L) } }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingGame: String? = null
    private val selectPending = Runnable { pendingGame?.let { selectGame(it) } }

    private fun selectGame(g: String) {
        if (game == g) return
        game = g
        render()
    }

    override fun releaseImages() {
        for (iv in listOf(imgA, imgB)) Img.release(iv)
        for (k in 0 until nextRow.childCount) (nextRow.getChildAt(k) as? ImageView)?.let { Img.release(it) }
    }

    override fun restoreImages() { if (loaded) render() }

    private fun showImage(shot: Shot, fromDir: Int = 0) {
        val back = if (front === imgA) imgB else imgA
        val spec = Img.Spec(ctx.px(imgW))
        val token = shot.id
        back.tag = token
        Img.load(shot.previewUrl, spec) { bmp ->
            if (back.tag != token) return@load
            back.setImageBitmap(bmp)
            back.bringToFront()
            stamp.bringToFront()
            back.alpha = 0f
            back.translationX = fromDir * ctx.dp(18f)
            back.animate().alpha(1f).translationX(0f).setDuration(Motion.SLOW).setInterpolator(DecelerateInterpolator()).start()
            front = back
        }
    }

    private fun renderNext(shots: List<Shot>) {
        nextRow.removeAllViews()
        val spec = Img.Spec(ctx.px(nextW))
        for (s in shots.drop(1).take(4)) {
            val iv = ImageView(ctx)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setBackgroundColor(C.surface2)
            iv.roundCorners(6f)
            iv.alpha = 0.7f
            Img.into(iv, s.previewUrl, spec)
            nextRow.addView(iv, LinearLayout.LayoutParams(ctx.dp(nextW), ctx.dp(nextH)).apply { bottomMargin = ctx.dp(9) })
        }
    }

    private fun prefetch(shots: List<Shot>) {
        val big = Img.Spec(ctx.px(imgW))
        for (s in shots.drop(1).take(5)) Img.prefetch(s.previewUrl, big)
    }

    // ── actions ───────────────────────────────────────────────────────────────────────

    private fun head(): Pair<String, Shot>? {
        val g = game ?: return null
        val s = groups[g]?.firstOrNull() ?: return null
        return g to s
    }

    private fun keep(favorite: Boolean) {
        val (g, s) = head() ?: return
        val was = s.favorite
        if (favorite) s.favorite = true
        s.status = "kept"
        consume(g, listOf(s), listOf(was), if (favorite) R.string.inbox_stamp_fav else R.string.inbox_stamp_kept, if (favorite) C.danger else C.success, -1)
        send(s, status = "kept", favorite = if (favorite) true else null)
    }

    private fun skip() {
        val (g, s) = head() ?: return
        s.status = "skipped"
        consume(g, listOf(s), listOf(s.favorite), R.string.inbox_stamp_skipped, C.text2, 1)
        send(s, status = "skipped", favorite = null)
    }

    private fun keepAll() {
        val g = game ?: return
        val shots = groups[g]?.toList() ?: return
        if (shots.isEmpty()) return
        for (s in shots) s.status = "kept"
        consume(g, shots, shots.map { it.favorite }, R.string.inbox_stamp_kept, C.success, -1)
        enqueue(shots.map { it.id }, { GamehubTv.keepAll(ctx, if (g == UNNAMED) null else g) }) { r ->
            if (r is GamehubTv.Res.Err) Pill.show(ctx, Pill.Kind.FAILED, ctx.getString(R.string.inbox_send_failed))
            else {
                // What the hub actually kept (it may know more than the page on screen).
                val n = ((r as? GamehubTv.Res.Ok<*>)?.value as? Int)?.takeIf { it >= 0 } ?: shots.size
                Pill.show(ctx, Pill.Kind.SAVED, ctx.getString(R.string.inbox_kept_all, n, label(g)))
            }
        }
    }

    /** Takes [shots] out of game [g], stamps the card and moves on (to the next game when empty). */
    private fun consume(g: String, shots: List<Shot>, wasFav: List<Boolean>, stampText: Int, stampColor: Int, dir: Int) {
        undo.addLast(Undo(g, shots, wasFav))
        while (undo.size > 50) undo.removeFirst()
        val list = groups[g] ?: return
        list.removeAll(shots.toSet())
        stampCard(ctx.getString(stampText), stampColor)
        val f = front
        f.animate().cancel()
        f.animate().alpha(0f).translationX(-dir * ctx.dp(24f)).setDuration(Motion.BASE).setInterpolator(AccelerateInterpolator()).start()
        if (list.isEmpty()) {
            groups.remove(g)
            val keys = groups.keys.toList()
            game = keys.firstOrNull()
            if (game == null) { refillCheck(); render(); defaultFocus?.requestFocus(); return }
        }
        render()
        // Keep the thumb on the capture when the game changed under it.
        if (!card.hasFocus() && !list.isEmpty() && view.hasFocus()) card.requestFocus()
    }

    /** The first page may not have had everything: look again once the visible Inbox is empty
     *  (deferred by [fetch] until the write queue has drained). */
    private fun refillCheck() {
        fetch()
    }

    private fun undo() {
        val u = undo.removeLastOrNull() ?: return
        val list = groups.getOrPut(u.game) { ArrayList() }
        for ((i, s) in u.shots.withIndex()) {
            s.status = "inbox"
            val fav = u.wasFavorite[i]
            val favChanged = s.favorite != fav
            s.favorite = fav
            send(s, status = "inbox", favorite = if (favChanged) fav else null)
        }
        list.addAll(0, u.shots)
        game = u.game
        render()
        stampCard(ctx.getString(R.string.inbox_stamp_undo), C.accent)
        if (!card.hasFocus()) card.requestFocus()
    }

    private fun send(s: Shot, status: String, favorite: Boolean?) {
        enqueue(listOf(s.id), { GamehubTv.setShot(ctx, s.id, favorite = favorite, status = status) }) { r ->
            if (r is GamehubTv.Res.Err) Pill.show(ctx, Pill.Kind.FAILED, ctx.getString(R.string.inbox_send_failed))
        }
    }

    private fun stampCard(text: String, color: Int) {
        stamp.text = text
        stamp.setTextColor(color)
        stamp.background = android.graphics.drawable.GradientDrawable().apply { setColor(C.alpha(C.bg, 0.82f)); cornerRadius = ctx.dp(16f); setStroke(ctx.dp(1), C.alpha(color, 0.6f)) }
        stamp.bringToFront()
        stamp.animate().cancel()
        stamp.alpha = 0f
        stamp.scaleX = 0.9f; stamp.scaleY = 0.9f
        stamp.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.BASE).setInterpolator(android.view.animation.OvershootInterpolator(2f)).withEndAction {
            stamp.animate().alpha(0f).setStartDelay(650L).setDuration(300L).withEndAction { stamp.animate().setStartDelay(0) }.start()
        }.start()
    }

    private fun label(g: String) = if (g == UNNAMED) ctx.getString(R.string.inbox_unnamed) else g

    private companion object {
        /** Group key for captures without a game ("__none__" in the API). */
        const val UNNAMED = "\u0000unnamed"
    }

    // ── keys ──────────────────────────────────────────────────────────────────────────

    override fun onKey(event: KeyEvent): Boolean {
        if (!content.isShown || !view.hasFocus()) return false
        val k = event.keyCode
        val action: (() -> Unit)? = when {
            k == KeyEvent.KEYCODE_BUTTON_X -> { { skip() } }
            k == KeyEvent.KEYCODE_BUTTON_Y -> { { keep(true) } }
            k == KeyEvent.KEYCODE_BUTTON_L1 -> { { undo() } }
            Keys.isSelect(k) && card.hasFocus() -> { { keep(false) } }
            else -> null
        }
        if (action == null) return false
        // Down acts, up is swallowed too so no fallback key (X → DPAD_CENTER) clicks anything.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) action()
        return true
    }
}

private fun View.setOnFocusChangeListenerChainedLocal(extra: (Boolean) -> Unit) {
    val prior = onFocusChangeListener
    setOnFocusChangeListener { v, has -> prior?.onFocusChange(v, has); extra(has) }
}

/** A gamepad key as a small keycap: "A", "X", "LB". Drawn, so no font needs the circled glyphs. */
class KeyCap(ctx: Context, private val key: String, private val color: Int) : Drawable() {
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Type.condensedBold(ctx)
        textSize = 11f * ctx.resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val r = RectF()
    private val w = ((if (key.length > 1) 26 else 18) * d).toInt()
    private val h = (18 * d).toInt()

    init { setBounds(0, 0, w, h) }

    override fun getIntrinsicWidth() = w
    override fun getIntrinsicHeight() = h

    override fun draw(c: Canvas) {
        r.set(bounds.left + d / 2, bounds.top + d / 2, bounds.right - d / 2, bounds.bottom - d / 2)
        p.style = Paint.Style.STROKE
        p.strokeWidth = d
        p.color = C.alpha(color, 0.7f)
        c.drawRoundRect(r, 5 * d, 5 * d, p)
        t.color = color
        c.drawText(key, r.centerX(), r.centerY() - (t.descent() + t.ascent()) / 2, t)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
