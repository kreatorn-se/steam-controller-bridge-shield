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
 * LIBRARY: games (official grid art), albums, people and favourites as D-pad grids. Tabs switch
 * as focus lands on them; any tile opens show mode, with LB/RB moving between its neighbours.
 */
class LibraryPage(host: Host) : Page(host) {
    private enum class Tab(val label: Int, val w: Int, val h: Int, val circle: Boolean) {
        GAMES(R.string.lib_games, 112, 168, false),
        ALBUMS(R.string.lib_albums, 240, 135, false),
        PEOPLE(R.string.lib_people, 120, 120, true),
        FAVOURITES(R.string.lib_favourites, 180, 101, false),
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tabs = Kit.row(ctx)
    /** ↑ from the grid lands on the current tab, never a neighbour (which would switch tabs). */
    private val tabsMemory = FocusMemoryFrame(ctx)
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
            val g = withContext(Dispatchers.IO) { GamehubTv.games(ctx) }
            applyList(Tab.GAMES, g)
            val a = withContext(Dispatchers.IO) { GamehubTv.albums(ctx) }
            applyList(Tab.ALBUMS, a)
            val p = withContext(Dispatchers.IO) { GamehubTv.people(ctx) }
            applyList(Tab.PEOPLE, p)
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
        else (a as SimpleAdapter<Coll>).update(lists[tab] ?: emptyList(), { it.collection }) { x, y -> x == y }
        renderMessage(tab, a.itemCount == 0)
    }

    private fun renderTabs() {
        for ((t, v) in tabViews) {
            val n = if (t == Tab.FAVOURITES) favs.size else lists[t]?.size
            v.text = ctx.getString(t.label) + (if (n != null && n > 0) "  $n" + (if (t == Tab.FAVOURITES && favNext != null) "+" else "") else "")
            v.isActivated = t == tab
            v.setTextColor(if (t == tab) C.text else C.text2)
        }
    }

    private fun select(t: Tab) {
        if (t == tab) return
        tab = t
        tabsMemory.remembered = tabViews[t]
        show(t)
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
        val spec = Img.Spec(ctx.px(t.w))
        val empty: Boolean
        if (t == Tab.FAVOURITES) {
            grid.adapter = SimpleAdapter(ArrayList(favs), { Tile(ctx, t.w, t.h, captionOnFocus = true) }) { v, shot, i ->
                val tile = v as Tile
                Img.into(tile.image, shot.previewUrl, spec)
                tile.setCaption(shot.gameOrUnknown(), Fmt.dateShort(shot))
                tile.card.setOnClickListener { playFavourites(shot) }
                if (i >= favs.size - span * 2) loadMoreFavs()
            }
            empty = favs.isEmpty()
        } else {
            val items = lists[t] ?: emptyList()
            grid.adapter = SimpleAdapter(items, { Tile(ctx, t.w, t.h, captionOnFocus = t == Tab.GAMES, circle = t.circle) }) { v, c, _ ->
                val tile = v as Tile
                bind(tile, c, t, spec)
                tile.card.setOnClickListener { val p = grid.getChildAdapterPosition(tile); if (p >= 0) play(t, p) }
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
                e == null -> ctx.getString(if (lists.containsKey(t) || t == Tab.FAVOURITES) R.string.lib_empty else R.string.tv_loading_title)
                e.fail == GamehubTv.Fail.NOT_DEPLOYED -> ctx.getString(R.string.lib_update)
                e.fail == GamehubTv.Fail.NOT_LINKED || e.fail == GamehubTv.Fail.UNAUTHORIZED -> ctx.getString(R.string.inbox_link_body)
                else -> ctx.getString(R.string.tv_offline_body, e.message)
            }
        }
    }

    private fun bind(tile: Tile, c: Coll, t: Tab, spec: Img.Spec) {
        when (t) {
            Tab.GAMES -> {
                val art = c.art?.grid
                Img.into(tile.image, art ?: c.cover?.previewUrl, if (art != null) spec else Img.Spec(ctx.px(t.h * 16 / 9)))
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
        host.openShow(ShowRequest(items.map { CollRef(it.collection, it.title) }, i))
    }

    private fun playFavourites(start: Shot) {
        host.openShow(ShowRequest(listOf(CollRef("favourites", ctx.getString(R.string.tv_favourites))), 0,
            startShotId = start.id, preload = ArrayList(favs), preloadNext = favNext))
    }

    override fun onHide() { handler.removeCallbacksAndMessages(null) }

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
