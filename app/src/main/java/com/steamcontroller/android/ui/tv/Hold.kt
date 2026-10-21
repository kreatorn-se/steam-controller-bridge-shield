package com.steamcontroller.android.ui.tv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.lifecycle.lifecycleScope
import com.steamcontroller.android.R
import com.steamcontroller.android.hub.GamehubTv
import com.steamcontroller.android.hub.GamehubTv.Shot
import com.steamcontroller.android.ui.Host
import com.steamcontroller.android.ui.pages.ProfileOps
import com.steamcontroller.android.ui.pill.Pill
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.Motion
import com.steamcontroller.android.ui.theme.dp
import com.steamcontroller.android.ui.widget.Kit
import com.steamcontroller.android.ui.widget.ListRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a long press on a picture acts on: the capture, and the collection it was shown in. */
data class HoldItem(val shot: Shot, val coll: CollRef? = null, val list: List<Shot>? = null)

/** Marks [this] view as a picture that opens the hold menu. */
fun View.holdItem(item: HoldItem?) = setTag(R.id.hold_item, item)

/** How the place a menu was opened from reacts to its actions. Pages use [Hold]'s defaults. */
interface HoldContext {
    fun slideshowFrom(item: HoldItem)
    fun showSession(item: HoldItem)
    fun openHub(game: String)
    /** The capture changed (favourite, background, album, people) or was [hidden]. */
    fun changed(item: HoldItem, hidden: Boolean)
}

/** The ring that fills while OK is held. */
class HoldRing(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    var progress = 0f
        set(v) { field = v; invalidate() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val rad = minOf(width, height) / 2f - 3 * d
        p.style = Paint.Style.FILL
        p.color = C.alpha(C.bg, 0.72f)
        c.drawCircle(cx, cy, rad + 2 * d, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3 * d
        p.strokeCap = Paint.Cap.ROUND
        p.color = C.alpha(C.text, 0.18f)
        c.drawCircle(cx, cy, rad - 2 * d, p)
        p.color = C.accent
        r.set(cx - rad + 2 * d, cy - rad + 2 * d, cx + rad - 2 * d, cy + rad - 2 * d)
        c.drawArc(r, -90f, 360f * progress, false, p)
    }
}

/**
 * Hold OK for 0.75 s ([HoldKeys.HOLD_MS]) on any picture (home rows, game hubs, library grids, show mode and its
 * filmstrip and grid): a small ring fills, then a compact menu opens with Favourite on top and
 * focused. A short tap still clicks as before; after a long press the key's release is
 * swallowed so it cannot click anything.
 */
class Hold(private val host: Host, private val layer: FrameLayout) {
    private val ctx = host.activity
    private val main = Handler(Looper.getMainLooper())
    private val ring = HoldRing(ctx)
    private var ringAnim: ValueAnimator? = null
    private val keys = HoldKeys()
    private var target: View? = null
    private var menu: Menu? = null
    private var undo: View? = null
    private var undoAction: (() -> Unit)? = null

    val menuOpen: Boolean get() = menu != null
    val undoActive: Boolean get() = undo != null

    init {
        ring.alpha = 0f
        layer.addView(ring, FrameLayout.LayoutParams(ctx.dp(RING_DP), ctx.dp(RING_DP)))
    }

    // ── detecting the hold on pages ───────────────────────────────────────────────────

    /** Keys while no overlay is open. True consumes. The rules live in [HoldKeys]. */
    fun pageKey(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        val v = if (down && e.repeatCount == 0 && HoldKeys.isSelect(e.keyCode)) ctx.currentFocus else null
        val item = v?.getTag(R.id.hold_item) as? HoldItem
        return when (keys.page(e.keyCode, down, e.repeatCount, item != null)) {
            HoldKeys.Act.PASS -> false
            HoldKeys.Act.CONSUME -> { if (!down) target?.isPressed = false; true }
            HoldKeys.Act.ARM -> { arm(v) { openMenu(item!!, pageContext) }; false }
            HoldKeys.Act.CANCEL -> { cancel(); false }
        }
    }

    /** Starts the ring over [v] (null: bottom centre, show mode) and [fire] after [HOLD_MS]. */
    fun arm(v: View?, fire: () -> Unit) {
        cancel()
        keys.arm()
        target = v
        place(v)
        ring.progress = 0f
        ring.alpha = 0f
        ringAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = HOLD_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                // Invisible for a tap; it appears once it is clearly a hold.
                ring.alpha = ((t - 0.15f) / 0.1f).coerceIn(0f, 1f)
                ring.progress = ((t - 0.15f) / 0.85f).coerceIn(0f, 1f)
            }
            start()
        }
        pendingFire = fire
        main.removeCallbacks(fireRunnable)
        main.postDelayed(fireRunnable, HOLD_MS)
    }

    private var pendingFire: (() -> Unit)? = null
    private val fireRunnable = Runnable {
        if (keys.fire()) {
            target?.isPressed = false
            ring.animate().alpha(0f).setDuration(Motion.FAST).start()
            pendingFire?.invoke()
        }
    }

    fun cancel() {
        keys.cancel()
        main.removeCallbacks(fireRunnable)
        ringAnim?.cancel()
        ring.alpha = 0f
    }

    /** True once a hold fired, until the next fresh press. */
    val firedHold: Boolean get() = keys.fired
    fun clearFired() = keys.clearFired()

    private fun place(v: View?) {
        val lp = ring.layoutParams as FrameLayout.LayoutParams
        val size = ctx.dp(RING_DP)
        if (v == null || !v.isAttachedToWindow) {
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.leftMargin = 0; lp.topMargin = 0
            lp.bottomMargin = ctx.resources.getDimensionPixelSize(R.dimen.safe_v) + ctx.dp(24)
        } else {
            val a = IntArray(2); val b = IntArray(2)
            v.getLocationInWindow(a); layer.getLocationInWindow(b)
            lp.gravity = Gravity.TOP or Gravity.START
            lp.leftMargin = a[0] - b[0] + (v.width * v.scaleX).toInt() / 2 - size / 2
            lp.topMargin = a[1] - b[1] + (v.height * v.scaleY).toInt() / 2 - size / 2
            lp.bottomMargin = 0
        }
        ring.layoutParams = lp
        ring.bringToFront()
    }

    // ── the menu ──────────────────────────────────────────────────────────────────────

    private val pageContext = object : HoldContext {
        override fun slideshowFrom(item: HoldItem) {
            val coll = item.coll ?: item.shot.game?.let { CollRef("game:$it", it) } ?: CollRef("recent", ctx.getString(R.string.hold_recent))
            host.openShow(ShowRequest(listOf(coll), 0, startShotId = item.shot.id, preload = item.list))
        }
        override fun showSession(item: HoldItem) {
            host.openShow(ShowRequest(listOf(CollRef("session:" + item.shot.id, ctx.getString(R.string.hold_session_title))), 0, startShotId = item.shot.id))
        }
        override fun openHub(game: String) = host.openGame(game)
        override fun changed(item: HoldItem, hidden: Boolean) = host.contentChanged()
    }

    /** Overlay keys while the menu is open: Back steps out of a submenu, then closes. */
    fun menuKey(e: KeyEvent): Boolean {
        val m = menu ?: return false
        if (Keys.isBack(e.keyCode)) {
            if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) m.back()
            return true
        }
        // After the long press, its own release and repeats must not reach the menu's rows.
        return keys.menu(e.keyCode, e.action == KeyEvent.ACTION_DOWN, e.repeatCount)
    }

    fun openMenu(item: HoldItem, hc: HoldContext? = null) {
        close()
        val m = Menu(item, hc ?: pageContext)
        menu = m
        layer.addView(m.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        m.root.alpha = 0f
        m.root.animate().alpha(1f).setDuration(Motion.BASE).start()
        m.panel.scaleX = 0.96f; m.panel.scaleY = 0.96f
        m.panel.animate().scaleX(1f).scaleY(1f).setDuration(Motion.SLOW).setInterpolator(DecelerateInterpolator()).start()
        focusBefore = ctx.currentFocus
        m.showMain()
    }

    private var focusBefore: View? = null

    fun close() {
        val m = menu ?: return
        menu = null
        m.root.animate().alpha(0f).setDuration(Motion.FAST).withEndAction { layer.removeView(m.root) }.start()
        m.root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        val b = focusBefore
        focusBefore = null
        if (b != null && b.isAttachedToWindow && b.isShown) b.requestFocus()
    }

    private inner class Menu(val item: HoldItem, val hc: HoldContext) {
        val root = FrameLayout(ctx)
        val panel = TrapFrame(ctx)
        private val col = Kit.column(ctx)
        private val scroll = MaxScroll(ctx, (ctx.resources.displayMetrics.heightPixels * 0.62f).toInt())
        private val stack = ArrayDeque<() -> Unit>()
        private val shot = item.shot

        init {
            root.setBackgroundColor(C.alpha(0xFF000000.toInt(), 0.5f))
            root.isClickable = true
            panel.background = android.graphics.drawable.GradientDrawable().apply { setColor(C.sheet); cornerRadius = ctx.dp(14f); setStroke(ctx.dp(1), C.line) }
            panel.setPadding(ctx.dp(10), ctx.dp(12), ctx.dp(10), ctx.dp(12))
            val outer = Kit.column(ctx)
            outer.addView(header(), Kit.lp(l = 8, r = 8, b = 10, ctx = ctx))
            scroll.isVerticalScrollBarEnabled = false
            scroll.clipToPadding = false
            scroll.setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
            scroll.addView(col)
            outer.addView(scroll)
            panel.addView(outer)
            root.addView(panel, FrameLayout.LayoutParams(ctx.dp(380), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }

        private fun header(): View {
            val row = Kit.row(ctx)
            val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; roundCorners(6f); setBackgroundColor(C.surface2) }
            Img.into(iv, shot.previewUrl, Img.cover(ctx, 112, 63), full = shot.fullUrl)
            row.addView(iv, LinearLayout.LayoutParams(ctx.dp(112), ctx.dp(63)))
            val t = Kit.column(ctx)
            t.addView(Kit.title(ctx, shot.gameOrUnknown(), 16f).apply { maxLines = 2 })
            t.addView(Kit.label(ctx, Fmt.dateShort(shot) + "  ·  " + Fmt.time(shot), 10.5f, C.text3), Kit.lp(t = 6, ctx = ctx))
            row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ctx.dp(14) })
            return row
        }

        fun back() {
            if (stack.size > 1) { stack.removeLast(); stack.last().invoke() } else close()
        }

        private fun page(render: () -> Unit) { stack.addLast(render); render() }

        /** Rebuilds the rows; [keep] puts focus back on the same position (a toggle in a list). */
        private fun rows(keep: Boolean = false, build: (MutableList<View>) -> Unit) {
            val at = if (keep) (0 until col.childCount).indexOfFirst { col.getChildAt(it).hasFocus() } else -1
            col.removeAllViews()
            val list = ArrayList<View>()
            build(list)
            for (v in list) col.addView(v)
            if (at >= 0) {
                col.getChildAt(at.coerceAtMost(col.childCount - 1))?.requestFocus()
            } else {
                scroll.scrollTo(0, 0)
                col.getChildAt(0)?.requestFocus()
            }
        }

        private fun row(title: String, hint: String? = null, tag: String? = null, tagColor: Int = C.accent, onClick: () -> Unit): ListRow =
            ListRow(ctx, title, hint, tag = tag, tagColor = tagColor) { onClick() }

        fun showMain() = page {
            rows { r ->
                val fav = shot.favorite
                r.add(row(ctx.getString(if (fav) R.string.hold_unfavourite else R.string.hold_favourite), tag = if (fav) "♥" else null, tagColor = C.danger) { favourite() })
                // Gated only by GET probes of the lists (1.4.1 also marked these after a write's
                // 404, hence the new keys), never by a failed write.
                if (!GamehubTv.Missing.has(ctx, "albums_list")) r.add(row(ctx.getString(R.string.hold_album)) { albums() })
                if (!GamehubTv.Missing.has(ctx, "people_list")) r.add(row(ctx.getString(R.string.hold_people), hint = shot.people.takeIf { it.isNotEmpty() }?.let { Fmt.people(it) }) { people() })
                // Hidden quietly (no "update" note) while the hub has no keywords.
                if (!GamehubTv.Missing.has(ctx, KEYWORDS)) r.add(row(ctx.getString(R.string.hold_keywords), hint = shot.keywords.takeIf { it.isNotEmpty() }?.joinToString(", ")) { keywords() })
                val game = shot.game
                if (game != null) r.add(row(ctx.getString(R.string.hold_background)) { background(game) })
                r.add(row(ctx.getString(R.string.hold_slideshow)) { close(); hc.slideshowFrom(item) })
                r.add(row(ctx.getString(R.string.hold_session)) { close(); hc.showSession(item) })
                if (game != null) r.add(row(ctx.getString(R.string.hold_hub)) { close(); hc.openHub(game) })
                r.add(row(ctx.getString(R.string.hold_info)) { info() })
                r.add(row(ctx.getString(R.string.hold_hide), tagColor = C.danger) { hide() })
            }
        }

        // ── actions ───────────────────────────────────────────────────────────────────

        private fun favourite() {
            val on = !shot.favorite
            shot.favorite = on
            close()
            Pill.show(ctx, Pill.Kind.SAVED, ctx.getString(if (on) R.string.hold_fav_done else R.string.hold_unfav_done))
            hc.changed(item, hidden = false)
            ctx.lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { GamehubTv.setShot(ctx, shot.id, favorite = on) }
                if (r is GamehubTv.Res.Err) {
                    shot.favorite = !on
                    hc.changed(item, hidden = false)
                    Pill.show(ctx, Pill.Kind.FAILED, ctx.getString(R.string.show_fav_failed))
                }
            }
        }

        private fun background(game: String) {
            close()
            ctx.lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { GamehubTv.setBackground(ctx, game, shot.id) }
                when {
                    r is GamehubTv.Res.Ok -> { Pill.show(ctx, Pill.Kind.SAVED, ctx.getString(R.string.hold_background_done, game)); hc.changed(item, false) }
                    r is GamehubTv.Res.Err -> failed(r)
                }
            }
        }

        private fun albums() = page {
            rows { r -> r.add(row(ctx.getString(R.string.tv_loading_title)) {}) }
            ctx.lifecycleScope.launch {
                val list = withContext(Dispatchers.IO) { GamehubTv.albums(ctx) }.let { r -> probe(r, "albums_list") ?: withContext(Dispatchers.IO) { GamehubTv.cachedList(ctx, "albums") } ?: emptyList() }
                if (menu == null || stack.lastOrNull() == null) return@launch
                rows { r ->
                    r.add(row(ctx.getString(R.string.hold_album_new)) { newAlbum() })
                    for (a in list) {
                        val id = a.collection.removePrefix("album:")
                        r.add(row(a.title, tag = if (a.count > 0) a.count.toString() else null, tagColor = C.text3) { addTo(id, a.title) })
                    }
                }
            }
        }

        private fun newAlbum() {
            ProfileOps.promptName(ctx, ctx.getString(R.string.hold_album_new), "") { name ->
                if (name.isBlank()) return@promptName
                ctx.lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) { GamehubTv.createAlbum(ctx, name) }
                    if (r is GamehubTv.Res.Ok) addTo(r.value, name) else failed(r as GamehubTv.Res.Err)
                }
            }
        }

        private fun addTo(albumId: String, name: String) {
            close()
            ctx.lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { GamehubTv.addToAlbum(ctx, albumId, listOf(shot.id)) }
                if (r is GamehubTv.Res.Ok) { Pill.show(ctx, Pill.Kind.SAVED, ctx.getString(R.string.hold_album_done, name)); hc.changed(item, false) }
                else failed(r as GamehubTv.Res.Err)
            }
        }

        /**
         * A write failed. Writes never hide a menu row: a 404 there means the shot or album is
         * gone (refresh), not that the hub lacks the call. Only a GET probe marks a feature
         * missing ([probe]).
         */
        private fun failed(e: GamehubTv.Res.Err) {
            when (e.fail) {
                GamehubTv.Fail.GONE -> { Pill.show(ctx, Pill.Kind.FAILED, ctx.getString(R.string.hold_not_saved)); hc.changed(item, false) }
                GamehubTv.Fail.NOT_DEPLOYED -> Pill.show(ctx, Pill.Kind.FAILED, ctx.getString(R.string.hold_needs_update))
                else -> Pill.show(ctx, Pill.Kind.FAILED, ctx.getString(R.string.hold_failed))
            }
        }

        /** A submenu's list read; a 404 there means the hub has no such list yet. */
        private fun <T> probe(r: GamehubTv.Res<T>, feature: String): T? {
            if (r is GamehubTv.Res.Err && r.fail == GamehubTv.Fail.NOT_DEPLOYED) {
                GamehubTv.Missing.mark(ctx, feature)
                Pill.show(ctx, Pill.Kind.FAILED, ctx.getString(R.string.hold_needs_update))
            }
            return r.valueOrNull()
        }

        /**
         * Everyone who can be tagged (`/people?all=1`, read each time the picker opens, so someone
         * just added on the website is there at once); offline, the people seen last.
         */
        private fun people() = page {
            rows { r -> r.add(row(ctx.getString(R.string.tv_loading_title)) {}) }
            ctx.lifecycleScope.launch {
                val all = withContext(Dispatchers.IO) { GamehubTv.peopleAll(ctx) }
                val list: List<Pair<String, String>> = probe(all, "people_list")?.map { it.id to it.name }
                    ?: withContext(Dispatchers.IO) { GamehubTv.cachedList(ctx, "people") }?.map { it.collection.removePrefix("person:") to it.title }
                    ?: emptyList()
                if (menu == null) return@launch
                // People are tagged by id; both lists carry ids and names.
                renderPeople(list, keep = false)
            }
        }

        private fun renderPeople(all: List<Pair<String, String>>, keep: Boolean = true) {
            rows(keep) { r ->
                if (all.isEmpty()) r.add(row(ctx.getString(R.string.hold_people_none)) {})
                for ((id, name) in all) {
                    val on = id in shot.peopleIds || (shot.peopleIds.isEmpty() && name in shot.people)
                    r.add(row(name, tag = if (on) "✓" else null) { togglePerson(id, name, on, all) })
                }
            }
        }

        private fun togglePerson(id: String, name: String, wasOn: Boolean, all: List<Pair<String, String>>) {
            val beforeIds = shot.peopleIds
            val beforeNames = shot.people
            if (wasOn) { shot.peopleIds = beforeIds - id; shot.people = beforeNames - name }
            else { shot.peopleIds = beforeIds + id; shot.people = beforeNames + name }
            renderPeople(all)
            ctx.lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { GamehubTv.setPeople(ctx, shot.id, if (wasOn) emptyList() else listOf(id), if (wasOn) listOf(id) else emptyList()) }
                when (r) {
                    is GamehubTv.Res.Ok -> {
                        r.value?.let { fresh -> shot.people = fresh.people; shot.peopleIds = fresh.peopleIds }
                        hc.changed(item, false)
                    }
                    is GamehubTv.Res.Err -> {
                        shot.peopleIds = beforeIds; shot.people = beforeNames
                        failed(r)
                    }
                }
                if (menu != null && stack.isNotEmpty()) renderPeople(all)
            }
        }

        // ── keywords ──────────────────────────────────────────────────────────────────

        /** The presets plus every keyword in use (GET /keywords); toggled with A, saved at once. */
        private fun keywords() = page {
            rows { r -> r.add(row(ctx.getString(R.string.tv_loading_title)) {}) }
            ctx.lifecycleScope.launch {
                val res = withContext(Dispatchers.IO) { GamehubTv.keywords(ctx) }
                if (res is GamehubTv.Res.Err && res.fail == GamehubTv.Fail.NOT_DEPLOYED) {
                    // A hub without keywords: hide them quietly, no "update Gamehub" note.
                    GamehubTv.Missing.mark(ctx, KEYWORDS)
                    if (menu != null && stack.size > 1) back()
                    return@launch
                }
                if (res is GamehubTv.Res.Ok) GamehubTv.Missing.clear(ctx, KEYWORDS)
                if (menu == null) return@launch
                val names = res.valueOrNull()?.map { it.name } ?: PRESET_KEYWORDS
                renderKeywords(mergeNames(names, shot.keywords), keep = false)
            }
        }

        private fun has(name: String) = shot.keywords.any { it.equals(name, ignoreCase = true) }

        private fun renderKeywords(all: List<String>, keep: Boolean = true) {
            rows(keep) { r ->
                for (name in all) {
                    val on = has(name)
                    r.add(row(name, tag = if (on) "✓" else null) { toggleKeyword(name, all) })
                }
                r.add(row(ctx.getString(R.string.hold_keywords_new), tagColor = C.text3) { newKeyword(all) })
            }
        }

        private fun toggleKeyword(name: String, all: List<String>) {
            val wasOn = has(name)
            val before = shot.keywords
            shot.keywords = if (wasOn) before.filterNot { it.equals(name, ignoreCase = true) } else (before + name).sortedBy { it.lowercase() }
            renderKeywords(all)
            ctx.lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { GamehubTv.setKeywords(ctx, shot.id, if (wasOn) emptyList() else listOf(name), if (wasOn) listOf(name) else emptyList()) }
                when (r) {
                    is GamehubTv.Res.Ok -> {
                        r.value?.let { fresh -> shot.keywords = fresh.keywords }
                        hc.changed(item, false)
                    }
                    is GamehubTv.Res.Err -> {
                        shot.keywords = before
                        failed(r)
                    }
                }
                if (menu != null && stack.isNotEmpty()) renderKeywords(all)
            }
        }

        private fun newKeyword(all: List<String>) {
            ProfileOps.promptName(ctx, ctx.getString(R.string.hold_keywords_new), "") { raw ->
                // The hub's rules: trimmed, at most 40 characters, no commas.
                val name = raw.replace(",", " ").trim().replace(Regex("\\s+"), " ").take(40)
                if (name.isEmpty() || menu == null) return@promptName
                val list = mergeNames(all, listOf(name))
                if (!has(name)) toggleKeyword(list.first { it.equals(name, ignoreCase = true) }, list) else renderKeywords(list)
            }
        }

        private fun info() = page {
            rows { r ->
                fun line(label: Int, value: String?) { if (!value.isNullOrBlank()) r.add(row(value, hint = ctx.getString(label)) {}) }
                line(R.string.hold_info_date, Fmt.date(shot) + "  ·  " + Fmt.time(shot))
                line(R.string.hold_info_game, shot.game)
                line(R.string.hold_info_source, sourceName(shot.source))
                line(R.string.hold_info_size, if (shot.width > 0) "${shot.width} × ${shot.height}" else null)
                line(R.string.hold_info_people, Fmt.people(shot.people))
                line(R.string.hold_info_keywords, shot.keywords.joinToString(", "))
                line(R.string.hold_info_note, shot.note)
            }
        }

        private fun hide() {
            close()
            val was = shot.status ?: "kept"
            shot.status = "skipped"
            hc.changed(item, hidden = true)
            ctx.lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { GamehubTv.setShot(ctx, shot.id, status = "skipped") }
                if (r is GamehubTv.Res.Err) { shot.status = was; hc.changed(item, false); Pill.show(ctx, Pill.Kind.FAILED, ctx.getString(R.string.hold_failed)); return@launch }
                showUndo(ctx.getString(R.string.hold_hidden)) {
                    shot.status = was
                    ctx.lifecycleScope.launch {
                        val u = withContext(Dispatchers.IO) { GamehubTv.setShot(ctx, shot.id, status = was) }
                        if (u is GamehubTv.Res.Err) Pill.show(ctx, Pill.Kind.FAILED, ctx.getString(R.string.hold_failed))
                        hc.changed(item, false)
                    }
                }
            }
        }
    }

    private fun sourceName(s: String?): String? = when (s) {
        null -> null
        "shield" -> "Shield"
        "steam" -> "Steam"
        "deck" -> "Steam Deck"
        "friend" -> ctx.getString(R.string.hold_source_friend)
        "ps5" -> "PlayStation 5"
        else -> s.replaceFirstChar { it.uppercase() }
    }

    // ── undo ──────────────────────────────────────────────────────────────────────────

    /** "Hidden · A Undo" for six seconds: A undoes, Back or any other key lets it go. */
    fun showUndo(text: String, action: () -> Unit) {
        dismissUndo()
        val bar = Kit.row(ctx).apply {
            setPadding(ctx.dp(18), 0, ctx.dp(18), 0)
            minimumHeight = ctx.dp(42)
            background = android.graphics.drawable.GradientDrawable().apply { setColor(C.alpha(C.sheet, 0.96f)); cornerRadius = ctx.dp(21f); setStroke(ctx.dp(1), C.lineStrong) }
            addView(Kit.label(ctx, text, 12f, C.text))
            addView(Kit.label(ctx, "", 12f, C.accent).apply {
                setText(ctx.getString(R.string.hold_undo))
                setCompoundDrawablesRelative(KeyCap(ctx, "A", C.accent), null, null, null)
                compoundDrawablePadding = ctx.dp(8)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dp(18) })
        }
        undo = bar
        undoAction = action
        layer.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = ctx.resources.getDimensionPixelSize(R.dimen.safe_v) + ctx.dp(8)
        })
        bar.alpha = 0f
        bar.translationY = ctx.dp(10f)
        bar.animate().alpha(1f).translationY(0f).setDuration(Motion.SLOW).setInterpolator(OvershootInterpolator(1.4f)).start()
        main.postDelayed(dismissUndoRunnable, 6000L)
    }

    private val dismissUndoRunnable = Runnable { dismissUndo() }

    fun dismissUndo(): Boolean {
        val b = undo ?: return false
        undo = null
        undoAction = null
        main.removeCallbacks(dismissUndoRunnable)
        b.animate().alpha(0f).setDuration(Motion.FAST).withEndAction { layer.removeView(b) }.start()
        return true
    }

    /** Keys while the undo bar shows. True consumes; other keys dismiss it and carry on. */
    fun undoKey(e: KeyEvent): Boolean {
        if (undo == null) return false
        val k = e.keyCode
        if (Keys.isSelect(k)) {
            if (e.action == KeyEvent.ACTION_UP) { val a = undoAction; dismissUndo(); a?.invoke() }
            return true
        }
        if (Keys.isBack(k)) {
            if (e.action == KeyEvent.ACTION_UP) dismissUndo()
            return true
        }
        if (e.action == KeyEvent.ACTION_DOWN) dismissUndo()
        return false
    }

    companion object {
        const val HOLD_MS = HoldKeys.HOLD_MS
        const val RING_DP = 46
        /** [GamehubTv.Missing] feature name for keywords (hub round 5). */
        const val KEYWORDS = "keywords"

        /** The hub's presets, in its order: shown when the list cannot be read. */
        val PRESET_KEYWORDS = listOf("Vista", "Building", "Mood", "Action", "Character", "Night", "Funny", "Boss", "Photo mode")

        /** [a] then whatever of [b] is not in it yet (case-insensitively), keeping [a]'s spelling. */
        fun mergeNames(a: List<String>, b: List<String>): List<String> {
            val seen = a.mapTo(HashSet()) { it.lowercase() }
            return a + b.filter { seen.add(it.lowercase()) }
        }

    }
}

/** Keeps focus inside the menu: nothing outside it can be reached by the D-pad. */
class TrapFrame(ctx: Context) : FrameLayout(ctx) {
    override fun focusSearch(focused: View?, direction: Int): View? {
        val r = super.focusSearch(focused, direction)
        if (r == null || !isInside(r)) return focused
        return r
    }

    private fun isInside(v: View): Boolean {
        var p = v.parent
        while (p != null) { if (p === this) return true; p = p.parent }
        return false
    }
}

/** A ScrollView no taller than [max]. */
class MaxScroll(ctx: Context, private val max: Int) : ScrollView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST))
    }
}
