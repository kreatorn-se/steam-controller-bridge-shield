package com.steamcontroller.android.ui.tv

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.steamcontroller.android.R
import com.steamcontroller.android.hub.GamehubTv
import com.steamcontroller.android.hub.GamehubTv.Coll
import com.steamcontroller.android.hub.GamehubTv.Shot
import com.steamcontroller.android.ui.FocusMemoryFrame
import com.steamcontroller.android.ui.Host
import com.steamcontroller.android.ui.Page
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.dp
import com.steamcontroller.android.ui.widget.Kit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * LIBRARY: games (official grid art), albums, people, favourites and keywords as D-pad grids.
 * Tabs switch as focus lands on them; any tile opens show mode, with LB/RB moving between its
 * neighbours. Keywords: a row of keyword chips (A toggles; a capture must have all of them) over
 * the captures that match. The tab is quietly absent while the hub has no keywords.
 */
class LibraryPage(host: Host) : Page(host) {
    private enum class Tab(val label: Int, val w: Int, val h: Int, val circle: Boolean) {
        GAMES(R.string.lib_games, 112, 168, false),
        ALBUMS(R.string.lib_albums, 240, 135, false),
        PEOPLE(R.string.lib_people, 120, 120, true),
        FAVOURITES(R.string.lib_favourites, 180, 101, false),
        KEYWORDS(R.string.lib_keywords, 180, 101, false),
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tabs = Kit.row(ctx)
    /** ↑ from the grid lands on the current tab, never a neighbour (which would switch tabs). */
    private val tabsMemory = FocusMemoryFrame(ctx)
    private val sortRow = Kit.row(ctx)
    private val sortViews = LinkedHashMap<String, TextView>()
    private var sort = "recent"
    private val tabViews = LinkedHashMap<Tab, TextView>()
    private val gridMemory = FocusMemoryFrame(ctx)
    private val grid = TvRecycler(ctx, TvRecycler.LOCK_RIGHT or TvRecycler.LOCK_DOWN)
    private val message = Kit.body(ctx, "", 16f, C.text2)
    private var tab = Tab.GAMES
    private val lists = HashMap<Tab, List<Coll>>()
    private val counts = HashMap<Tab, Int>()
    private val favs = ArrayList<Shot>()
    private var favNext: String? = null
    private var favLoading = false
    private val errors = HashMap<Tab, GamehubTv.Res.Err>()
    // Keywords: the chips, the chosen ones and the captures that have them all.
    private val kwScroll = android.widget.HorizontalScrollView(ctx)
    private val kwRow = Kit.row(ctx)
    private val kwViews = LinkedHashMap<String, TextView>()
    private var kwList: List<GamehubTv.Keyword> = emptyList()
    private val kwChosen = ArrayList<String>()
    private val kwShots = ArrayList<Shot>()
    private var kwNext: String? = null
    private var kwLoading = false
    private var kwToken = 0
    private val gap = ctx.dp(14)

    override val view: View = build()
    override val defaultFocus: View? get() = tabViews[tab]

    private fun build(): View {
        val col = Kit.column(ctx)
        col.clipChildren = false
        tabs.clipChildren = false
        for (t in Tab.values()) {
            val b = Kit.button(ctx, ctx.getString(t.label), Kit.Btn.GHOST) { focusGrid() }
            b.setOnFocusChangeListenerChainedTv { has -> if (has && tab != t) { pending = t; handler.removeCallbacks(switchPending); handler.postDelayed(switchPending, 140L) } }
            tabViews[t] = b
            tabs.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.dp(4) })
        }
        tabsMemory.clipChildren = false
        tabsMemory.addView(tabs)
        tabsMemory.fallback = { tabViews[tab] }
        tabsMemory.remembered = tabViews[tab]
        col.addView(tabsMemory, Kit.lp(l = -3, ctx = ctx))
        // Games: sorted by the hub (recent, captures, playtime, name); each opens its hub.
        sortRow.clipChildren = false
        for ((key, label) in SORTS) {
            val b = Kit.button(ctx, ctx.getString(label), Kit.Btn.GHOST) { setSort(key) }
            sortViews[key] = b
            sortRow.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.dp(2) })
        }
        sortRow.addView(Kit.label(ctx, ctx.getString(R.string.lib_sort), 10.5f, C.text3), 0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dp(6); marginEnd = ctx.dp(8) })
        col.addView(sortRow, Kit.lp(l = -3, t = 2, ctx = ctx))
        kwRow.clipChildren = false
        kwScroll.isHorizontalScrollBarEnabled = false
        kwScroll.clipChildren = false
        kwScroll.clipToPadding = false
        kwScroll.overScrollMode = View.OVER_SCROLL_NEVER
        kwScroll.addView(kwRow)
        col.addView(kwScroll, Kit.lp(l = -3, t = 2, ctx = ctx))
        renderSort()
        val body = FrameLayout(ctx)
        body.clipChildren = false
        grid.setPadding(0, ctx.dp(14), 0, ctx.dp(40))
        grid.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                outRect.right = gap
                outRect.bottom = gap
            }
        })
        gridMemory.clipChildren = false
        gridMemory.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        gridMemory.fallback = { grid.layoutManager?.let { lm -> (lm as KeylineGrid).findViewByPosition(lm.findFirstCompletelyVisibleItemPosition().coerceAtLeast(0)) } }
        body.addView(gridMemory, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        message.visibility = View.GONE
        body.addView(message, FrameLayout.LayoutParams(ctx.dp(520), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { topMargin = ctx.dp(28); marginStart = ctx.dp(3) })
        col.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = ctx.dp(6) })
        return col
    }

    private var pending: Tab? = null
    private val switchPending = Runnable { pending?.let { select(it) } }

    private fun focusGrid() {
        if (grid.adapter?.itemCount ?: 0 > 0) gridMemory.requestFocus()
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────────────

    private var loadedOnce = false

    override fun onShow() {
        if (!loadedOnce) {
            loadedOnce = true
            show(tab)
            host.activity.lifecycleScope.launch {
                // The saved copies, read and parsed off the main thread, then the hub's.
                val g = withContext(Dispatchers.IO) { GamehubTv.cachedList(ctx, "games") }
                val a = withContext(Dispatchers.IO) { GamehubTv.cachedList(ctx, "albums") }
                val p = withContext(Dispatchers.IO) { GamehubTv.cachedList(ctx, "people") }
                val f = withContext(Dispatchers.IO) { GamehubTv.cachedShots(ctx, "favourites") }
                val missingKw = withContext(Dispatchers.IO) { GamehubTv.Missing.has(ctx, Hold.KEYWORDS) }
                tabViews[Tab.KEYWORDS]?.visibility = if (missingKw) View.GONE else View.VISIBLE
                if (g != null && !lists.containsKey(Tab.GAMES)) lists[Tab.GAMES] = g
                if (a != null && !lists.containsKey(Tab.ALBUMS)) lists[Tab.ALBUMS] = a
                if (p != null && !lists.containsKey(Tab.PEOPLE)) lists[Tab.PEOPLE] = p
                if (f != null && favs.isEmpty()) { favs.addAll(f.items); favNext = f.next }
                updateGrid()
                fetchAll()
            }
            return
        }
        fetchAll()
    }

    override fun refresh() { if (loadedOnce) fetchAll() }

    private var fetching = false

    private fun fetchAll() {
        if (fetching) return
        fetching = true
        host.activity.lifecycleScope.launch {
            val g = withContext(Dispatchers.IO) { gamesSorted() }
            applyList(Tab.GAMES, g)
            val a = withContext(Dispatchers.IO) { GamehubTv.albums(ctx) }
            applyList(Tab.ALBUMS, a)
            val p = withContext(Dispatchers.IO) { GamehubTv.people(ctx) }
            applyList(Tab.PEOPLE, p)
            val k = withContext(Dispatchers.IO) { GamehubTv.keywords(ctx) }
            applyKeywords(k)
            val f = withContext(Dispatchers.IO) { GamehubTv.shots(ctx, "favourites") }
            when (f) {
                is GamehubTv.Res.Ok -> {
                    errors.remove(Tab.FAVOURITES)
                    val fresh = f.value.items
                    // Keep pages already loaded beyond the first when page one did not change.
                    if (favs.take(fresh.size).map { it.id } != fresh.map { it.id }) {
                        favs.clear(); favs.addAll(fresh); favNext = f.value.next
                    }
                }
                is GamehubTv.Res.Err -> {
                    errors[Tab.FAVOURITES] = f
                    if (f.fail == GamehubTv.Fail.NOT_LINKED || f.fail == GamehubTv.Fail.UNAUTHORIZED) { favs.clear(); favNext = null }
                }
            }
            fetching = false
            updateGrid()
            if (tab == Tab.KEYWORDS) loadKeywordShots(reset = false)
        }
    }

    // ── keywords ──────────────────────────────────────────────────────────────────────

    private fun applyKeywords(r: GamehubTv.Res<List<GamehubTv.Keyword>>) {
        when (r) {
            is GamehubTv.Res.Ok -> {
                GamehubTv.Missing.clear(ctx, Hold.KEYWORDS)
                tabViews[Tab.KEYWORDS]?.visibility = View.VISIBLE
                kwList = r.value
                renderKeywordChips()
            }
            is GamehubTv.Res.Err -> if (r.fail == GamehubTv.Fail.NOT_DEPLOYED) {
                // An older hub: no keywords anywhere, and no "update" note either.
                GamehubTv.Missing.mark(ctx, Hold.KEYWORDS)
                if (tab == Tab.KEYWORDS) tabViews[Tab.GAMES]?.requestFocus()
                tabViews[Tab.KEYWORDS]?.visibility = View.GONE
            }
        }
    }

    /** Chips for the keywords in use (and any chosen), updated in place so focus stays put. */
    private fun renderKeywordChips() {
        val names = Hold.mergeNames(kwList.filter { it.count > 0 }.map { it.name }, kwChosen)
        val counts = kwList.associate { it.name.lowercase() to it.count }
        if (names != kwViews.keys.toList()) {
            val focused = kwViews.entries.firstOrNull { it.value.hasFocus() }?.key
            kwRow.removeAllViews()
            kwViews.clear()
            for (n in names) {
                val b = Kit.button(ctx, n, Kit.Btn.GHOST) { toggleKeyword(n) }
                kwViews[n] = b
                kwRow.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.dp(2) })
            }
            focused?.let { kwViews[it]?.requestFocus() }
        }
        for ((n, v) in kwViews) {
            val on = kwChosen.any { it.equals(n, ignoreCase = true) }
            v.isActivated = on
            v.setTextColor(if (on) C.text else C.text2)
            val c = counts[n.lowercase()] ?: 0
            v.text = if (c > 0) "$n  $c" else n
        }
    }

    private fun toggleKeyword(name: String) {
        val had = kwChosen.firstOrNull { it.equals(name, ignoreCase = true) }
        if (had != null) kwChosen.remove(had) else kwChosen.add(name)
        renderKeywordChips()
        loadKeywordShots(reset = true)
    }

    /** Page one of the captures with every chosen keyword (or more of them, [reset] false). */
    private fun loadKeywordShots(reset: Boolean, more: Boolean = false) {
        val token = if (more) kwToken else ++kwToken
        if (reset) { kwShots.clear(); kwNext = null; if (tab == Tab.KEYWORDS) updateGrid() }
        if (kwChosen.isEmpty()) { kwLoading = false; if (tab == Tab.KEYWORDS) updateGrid(); return }
        val chosen = kwChosen.toList()
        val cursor = if (more) kwNext ?: return else null
        if (more && kwLoading) return
        kwLoading = true
        host.activity.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { GamehubTv.shots(ctx, "recent", before = cursor, filters = emptyMap(), kw = chosen) }
            if (token != kwToken) return@launch
            kwLoading = false
            when (r) {
                is GamehubTv.Res.Ok -> {
                    errors.remove(Tab.KEYWORDS)
                    if (!more) kwShots.clear()
                    val ids = kwShots.mapTo(HashSet()) { it.id }
                    for (sh in r.value.items) if (ids.add(sh.id)) kwShots.add(sh)
                    kwNext = r.value.next
                }
                is GamehubTv.Res.Err -> if (!more) errors[Tab.KEYWORDS] = r
            }
            if (tab == Tab.KEYWORDS) updateGrid()
        }
    }

    private fun applyList(t: Tab, r: GamehubTv.Res<List<Coll>>) {
        when (r) {
            is GamehubTv.Res.Ok -> { errors.remove(t); lists[t] = r.value }
            is GamehubTv.Res.Err -> {
                errors[t] = r
                // Unlinked or refused: what was shown belongs to that link.
                if (r.fail == GamehubTv.Fail.NOT_LINKED || r.fail == GamehubTv.Fail.UNAUTHORIZED) lists.remove(t)
            }
        }
        if (t == tab) updateGrid() else renderTabs()
    }

    /**
     * New data for the current tab goes into the existing adapter as a diff, so focus and scroll
     * stay where they are; [show] (a new adapter) is only for switching tabs.
     */
    @Suppress("UNCHECKED_CAST")
    private fun updateGrid() {
        renderTabs()
        val a = grid.adapter
        if (a == null) { show(tab); return }
        if (tab == Tab.FAVOURITES) (a as SimpleAdapter<Shot>).update(ArrayList(favs), { it.id }) { x, y -> x.favorite == y.favorite && x.game == y.game }
        else if (tab == Tab.KEYWORDS) (a as SimpleAdapter<Shot>).update(ArrayList(kwShots), { it.id }) { x, y -> x.favorite == y.favorite && x.game == y.game }
        else (a as SimpleAdapter<Coll>).update(lists[tab] ?: emptyList(), { it.collection }) { x, y -> x == y }
        renderMessage(tab, a.itemCount == 0)
    }

    private fun renderSort() {
        sortRow.visibility = if (tab == Tab.GAMES) View.VISIBLE else View.GONE
        kwScroll.visibility = if (tab == Tab.KEYWORDS) View.VISIBLE else View.GONE
        for ((k, v) in sortViews) { v.isActivated = k == sort; v.setTextColor(if (k == sort) C.text else C.text2) }
    }

    private fun setSort(key: String) {
        if (key == sort) return
        sort = key
        renderSort()
        host.activity.lifecycleScope.launch {
            val g = withContext(Dispatchers.IO) { gamesSorted() }
            applyList(Tab.GAMES, g)
            grid.scrollToPosition(0)
        }
    }

    /** The hub's sorted list; an older hub without ?sort gets the plain list sorted here. */
    private fun gamesSorted(): GamehubTv.Res<List<Coll>> {
        val r = GamehubTv.gamesSorted(ctx, sort)
        val list = r.valueOrNull() ?: GamehubTv.games(ctx).valueOrNull() ?: return r
        GameArt.putAll(list)
        val sorted = when (sort) {
            "captures" -> list.sortedByDescending { it.count }
            "playtime" -> list.sortedByDescending { it.playtimeMin }
            "name" -> list.sortedBy { it.title.lowercase() }
            else -> if (r is GamehubTv.Res.Ok) list else list.sortedByDescending { it.last }
        }
        return GamehubTv.Res.Ok(sorted)
    }

    private fun renderTabs() {
        for ((t, v) in tabViews) {
            val n = when (t) {
                Tab.FAVOURITES -> favs.size
                Tab.KEYWORDS -> null
                else -> lists[t]?.size
            }
            v.text = ctx.getString(t.label) + (if (n != null && n > 0) "  $n" + (if (t == Tab.FAVOURITES && favNext != null) "+" else "") else "")
            v.isActivated = t == tab
            v.setTextColor(if (t == tab) C.text else C.text2)
        }
    }

    private fun select(t: Tab) {
        if (t == tab) return
        tab = t
        tabsMemory.remembered = tabViews[t]
        renderSort()
        show(t)
        if (t == Tab.KEYWORDS && kwChosen.isNotEmpty() && kwShots.isEmpty()) loadKeywordShots(reset = false)
    }

    // ── grid ──────────────────────────────────────────────────────────────────────────

    private fun show(t: Tab) {
        renderTabs()
        val avail = grid.width.takeIf { it > 0 } ?: (ctx.resources.displayMetrics.widthPixels - 2 * ctx.resources.getDimensionPixelSize(R.dimen.safe_h) - ctx.dp(72))
        val cell = ctx.dp(t.w + 6) + gap
        val span = ((avail + gap) / cell).coerceAtLeast(1)
        val lm = KeylineGrid(ctx, span) { (grid.height * 0.26f).toInt() }
        grid.layoutManager = lm
        gridMemory.remembered = null
        val spec = Img.cover(ctx, t.w, t.h, Tile.FOCUS_SCALE)
        val empty: Boolean
        if (t == Tab.FAVOURITES) {
            grid.adapter = SimpleAdapter(ArrayList(favs), { Tile(ctx, t.w, t.h, captionOnFocus = true) }) { v, shot, i ->
                val tile = v as Tile
                Img.into(tile.image, shot.previewUrl, spec, full = shot.fullUrl)
                tile.setCaption(shot.gameOrUnknown(), Fmt.dateShort(shot))
                tile.card.setOnClickListener { playFavourites(shot) }
                tile.card.holdItem(HoldItem(shot, CollRef("favourites", ctx.getString(R.string.tv_favourites)), favs))
                if (i >= favs.size - span * 2) loadMoreFavs()
            }
            empty = favs.isEmpty()
        } else if (t == Tab.KEYWORDS) {
            grid.adapter = SimpleAdapter(ArrayList(kwShots), { Tile(ctx, t.w, t.h, captionOnFocus = true) }) { v, shot, i ->
                val tile = v as Tile
                Img.into(tile.image, shot.previewUrl, spec, full = shot.fullUrl)
                tile.setFavorite(shot.favorite)
                tile.setCaption(shot.gameOrUnknown(), Fmt.dateShort(shot))
                tile.card.setOnClickListener { playKeywords(shot) }
                tile.card.holdItem(HoldItem(shot, null, kwShots))
                if (i >= kwShots.size - span * 2) loadKeywordShots(reset = false, more = true)
            }
            empty = kwShots.isEmpty()
            if (kwViews.isEmpty() && kwList.isNotEmpty()) renderKeywordChips()
        } else {
            val items = lists[t] ?: emptyList()
            grid.adapter = SimpleAdapter(items, { Tile(ctx, t.w, t.h, captionOnFocus = t == Tab.GAMES, circle = t.circle) }) { v, c, _ ->
                val tile = v as Tile
                bind(tile, c, t, spec)
                tile.card.setOnClickListener { val p = grid.getChildAdapterPosition(tile); if (p >= 0) play(t, p) }
                tile.card.holdItem(null)
            }
            empty = items.isEmpty()
        }
        renderMessage(t, empty)
    }

    private fun renderMessage(t: Tab, empty: Boolean) {
        message.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) {
            val e = errors[t]
            message.text = when {
                t == Tab.KEYWORDS && e == null -> ctx.getString(when {
                    kwViews.isEmpty() -> if (fetching) R.string.tv_loading_title else R.string.kw_none
                    kwChosen.isEmpty() -> R.string.lib_kw_hint
                    kwLoading -> R.string.tv_loading_title
                    else -> R.string.lib_kw_empty
                })
                e == null -> ctx.getString(if (lists.containsKey(t) || t == Tab.FAVOURITES) R.string.lib_empty else R.string.tv_loading_title)
                e.fail == GamehubTv.Fail.NOT_DEPLOYED -> ctx.getString(R.string.lib_update)
                e.fail == GamehubTv.Fail.NOT_LINKED || e.fail == GamehubTv.Fail.UNAUTHORIZED -> ctx.getString(R.string.lib_link_body)
                else -> ctx.getString(R.string.tv_offline_body, e.message)
            }
        }
    }

    private fun bind(tile: Tile, c: Coll, t: Tab, spec: Img.Spec) {
        when (t) {
            Tab.GAMES -> {
                val art = c.art?.grid
                Img.into(tile.image, art ?: c.cover?.previewUrl, spec)
                tile.setOver(if (art == null) c.title else null)
                tile.setCaption(if (art == null) null else c.title, Fmt.count(c.count))
            }
            Tab.PEOPLE -> {
                Img.into(tile.image, c.avatar ?: c.cover?.previewUrl, spec)
                tile.setCaption(c.title, if (c.count > 0) Fmt.count(c.count) else c.subtitle)
            }
            else -> {
                Img.into(tile.image, c.cover?.previewUrl, spec)
                tile.setCaption(c.title, if (c.count > 0) Fmt.count(c.count) else c.subtitle)
            }
        }
    }

    private fun loadMoreFavs() {
        val cursor = favNext ?: return
        if (favLoading) return
        favLoading = true
        host.activity.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { GamehubTv.shots(ctx, "favourites", before = cursor) }
            favLoading = false
            if (r is GamehubTv.Res.Ok) {
                val ids = favs.mapTo(HashSet()) { it.id }
                for (s in r.value.items) if (ids.add(s.id)) favs.add(s)
                favNext = r.value.next
                if (tab == Tab.FAVOURITES) updateGrid() else renderTabs()
            }
        }
    }

    private fun play(t: Tab, i: Int) {
        val items = lists[t] ?: return
        if (t == Tab.GAMES) { items.getOrNull(i)?.let { host.openGame(it.title) }; return }
        host.openShow(ShowRequest(items.map { CollRef(it.collection, it.title) }, i))
    }

    private fun playKeywords(start: Shot) {
        val title = kwChosen.joinToString(" + ")
        host.openShow(ShowRequest(listOf(CollRef(null, title, ArrayList(kwShots))), 0, startShotId = start.id))
    }

    private fun playFavourites(start: Shot) {
        host.openShow(ShowRequest(listOf(CollRef("favourites", ctx.getString(R.string.tv_favourites))), 0,
            startShotId = start.id, preload = ArrayList(favs), preloadNext = favNext))
    }

    override fun onHide() { handler.removeCallbacksAndMessages(null) }

    private companion object {
        val SORTS = listOf("recent" to R.string.lib_sort_recent, "captures" to R.string.lib_sort_captures, "playtime" to R.string.lib_sort_playtime, "name" to R.string.lib_sort_name)
    }

    override fun releaseImages() {
        for (k in 0 until grid.childCount) (grid.getChildAt(k) as? Tile)?.let { Img.release(it.image) }
        grid.recycledViewPool.clear()
    }

    override fun restoreImages() {
        grid.adapter?.let { it.notifyItemRangeChanged(0, it.itemCount) }
    }
}

private fun View.setOnFocusChangeListenerChainedTv(extra: (Boolean) -> Unit) {
    val prior = onFocusChangeListener
    setOnFocusChangeListener { v, has -> prior?.onFocusChange(v, has); extra(has) }
}
