package com.steamcontroller.android.ui.tv

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.steamcontroller.android.R
import com.steamcontroller.android.hub.GamehubTv
import com.steamcontroller.android.hub.GamehubTv.Shot
import com.steamcontroller.android.ui.Host
import com.steamcontroller.android.ui.Sheet
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.Motion
import com.steamcontroller.android.ui.theme.dp
import com.steamcontroller.android.ui.widget.Kit
import com.steamcontroller.android.ui.widget.ListRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Official art per game name, from every games list and hub the TV has seen (logos on rows). */
object GameArt {
    private val map = HashMap<String, GamehubTv.Art>()
    fun put(name: String, art: GamehubTv.Art?) { if (art != null) map[name] = art }
    fun putAll(list: List<GamehubTv.Coll>) { for (c in list) if (c.collection.startsWith("game:")) put(c.title, c.art) }
    fun get(name: String?): GamehubTv.Art? = name?.let { map[it] }
    val isEmpty get() = map.isEmpty()
}

/**
 * A hub for every game: the header (official hero art, or a capture chosen as background, with the
 * official logo), a stats strip (Steam playtime, sessions, captures, achievements with the rarest
 * and latest unlocks), filters (All, Favourites, Session, Year, Source), then every capture as a
 * dense justified grid in day sections, newest first. Focus enters on the latest capture; any
 * capture opens show mode; a long press opens the hold menu.
 */
class GameHub(private val host: Host, val game: String, private val onClose: () -> Unit) {
    private val ctx: Context = host.activity
    private val res = ctx.resources
    private val screenW = res.displayMetrics.widthPixels
    private val screenH = res.displayMetrics.heightPixels
    private val safeH = res.getDimensionPixelSize(R.dimen.safe_h)
    private val safeV = res.getDimensionPixelSize(R.dimen.safe_v)
    private val left = safeH + ctx.dp(64 + 8)
    private val contentW = screenW - left - safeH
    private val rowH = ctx.dp(112)
    private val gap = ctx.dp(8)

    val root = FrameLayout(ctx)
    private val bg = ImageView(ctx)
    private val list = TvRecycler(ctx, TvRecycler.LOCK_RIGHT or TvRecycler.LOCK_DOWN)
    private val header = Kit.column(ctx)
    private val logo = ImageView(ctx)
    private val title = Kit.title(ctx, game, 40f)
    private val meta = Kit.body(ctx, "", 15f, C.text2)
    private val showBtn = Kit.button(ctx, ctx.getString(R.string.tv_play_show), Kit.Btn.PRIMARY, R.drawable.ic_play) { playFrom(null) }
    private val stats = Kit.row(ctx, Gravity.TOP)
    private val ach = Kit.row(ctx)
    private val filters = Kit.row(ctx)
    private val message = Kit.body(ctx, "", 15f, C.text2)

    private var hub: GamehubTv.GameHub? = null
    private val shots = ArrayList<Shot>()
    private var next: String? = null
    private var loadingMore = false
    private var job: Job? = null
    private var fav = false
    private var year: Int? = null
    private var source: String? = null
    private var session: GamehubTv.SessionInfo? = null
    private var entered = false
    private var closed = false

    private sealed class Item {
        object Header : Item()
        data class Section(val title: String) : Item()
        data class Row(val shots: List<Shot>, val widths: List<Int>, val height: Int, val start: Int) : Item()
    }

    private var items: List<Item> = listOf(Item.Header)
    private val adapter = Adapter()

    init {
        root.setBackgroundColor(C.bg)
        root.isClickable = true
        bg.scaleType = ImageView.ScaleType.CENTER_CROP
        root.addView(bg, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (screenH * 0.62f).toInt()))
        root.addView(View(ctx).apply { background = Scrim(intArrayOf(C.alpha(C.bg, 0.9f), C.alpha(C.bg, 0.45f), C.alpha(C.bg, 0f)), floatArrayOf(0f, 0.4f, 0.8f), horizontal = true) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (screenH * 0.62f).toInt()))
        root.addView(View(ctx).apply { background = Scrim(intArrayOf(C.alpha(C.bg, 0.7f), C.alpha(C.bg, 0f), C.alpha(C.bg, 0.6f), C.bg), floatArrayOf(0f, 0.25f, 0.7f, 1f)) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (screenH * 0.62f).toInt()))
        buildHeader()
        list.layoutManager = KeylineLinearV(ctx) { v ->
            val pos = list.getChildAdapterPosition(v)
            if (pos <= 0) 0 else (screenH * 0.42f).toInt()
        }
        list.adapter = adapter
        list.setItemViewCacheSize(6)
        list.setPadding(0, 0, 0, safeV + ctx.dp(40))
        root.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val fade = View(ctx).apply { background = Scrim(intArrayOf(C.bg, C.alpha(C.bg, 0.9f), C.alpha(C.bg, 0f)), floatArrayOf(0f, 0.6f, 1f)); alpha = 0f }
        root.addView(fade, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, safeV + ctx.dp(48 + 20)))
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val off = rv.computeVerticalScrollOffset()
                bg.translationY = -off * 0.4f
                bg.alpha = (1f - off / (screenH * 0.6f)).coerceIn(0.15f, 1f)
                fade.alpha = (off / ctx.dp(48f)).coerceIn(0f, 1f)
            }
        })
    }

    private fun buildHeader() {
        header.setPadding(left, safeV + ctx.dp(48 + 40), safeH, 0)
        header.clipChildren = false
        header.clipToPadding = false
        header.addView(Kit.label(ctx, ctx.getString(R.string.hub_eyebrow), 11f, C.text2))
        logo.scaleType = ImageView.ScaleType.FIT_START
        logo.visibility = View.GONE
        header.addView(logo, LinearLayout.LayoutParams(ctx.dp(320), ctx.dp(90)).apply { topMargin = ctx.dp(10) })
        title.maxLines = 2
        header.addView(title, LinearLayout.LayoutParams(ctx.dp(640), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(10) })
        header.addView(meta, Kit.lp(t = 10, ctx = ctx))
        val btns = Kit.row(ctx)
        btns.clipChildren = false
        btns.addView(showBtn)
        header.addView(btns, Kit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, t = 16, l = -3, ctx = ctx))
        header.addView(stats, Kit.lp(t = 26, ctx = ctx))
        header.addView(ach, Kit.lp(t = 14, ctx = ctx))
        filters.clipChildren = false
        header.addView(filters, Kit.lp(t = 18, l = -3, ctx = ctx))
        message.visibility = View.GONE
        header.addView(message, Kit.lp(t = 16, ctx = ctx))
        header.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        renderFilters()
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────────────

    fun start() {
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(Motion.SLOW).start()
        showBtn.requestFocus()
        GameArt.get(game)?.let { applyArt(it, null) }
        host.activity.lifecycleScope.launch {
            val cached = withContext(Dispatchers.IO) { GamehubTv.cachedGameHub(ctx, game) }
            if (closed) return@launch
            if (cached != null && hub == null) renderHub(cached)
            val r = withContext(Dispatchers.IO) { GamehubTv.gameHub(ctx, game) }
            if (closed) return@launch
            when (r) {
                is GamehubTv.Res.Ok -> { GamehubTv.Missing.clear(ctx, "hub"); renderHub(r.value) }
                // A hub without the new call still has the captures: show them, no stats.
                is GamehubTv.Res.Err -> if (r.fail == GamehubTv.Fail.NOT_DEPLOYED) GamehubTv.Missing.mark(ctx, "hub")
            }
        }
        load()
    }

    fun close() {
        if (closed) return
        closed = true
        job?.cancel()
        root.animate().alpha(0f).setDuration(Motion.FAST).withEndAction { (root.parent as? ViewGroup)?.removeView(root) }.start()
        onClose()
    }

    fun releaseImages() {
        Img.release(bg); Img.release(logo)
        for (k in 0 until list.childCount) {
            val row = list.getChildAt(k) as? ViewGroup ?: continue
            for (j in 0 until row.childCount) (row.getChildAt(j) as? PicCard)?.let { Img.release(it.image) }
        }
        list.recycledViewPool.clear()
    }

    fun restoreImages() {
        hub?.let { renderHub(it) } ?: GameArt.get(game)?.let { applyArt(it, null) }
        adapter.notifyItemRangeChanged(1, (items.size - 1).coerceAtLeast(0))
    }

    /** Back inside the hub: from deep in the grid, first back to the top. */
    fun onBack(): Boolean {
        if (list.computeVerticalScrollOffset() > screenH && root.hasFocus() && !showBtn.hasFocus()) {
            list.scrollToPosition(0)
            showBtn.requestFocus()
            return true
        }
        return false
    }

    // ── header ────────────────────────────────────────────────────────────────────────

    private fun applyArt(art: GamehubTv.Art, background: String?) {
        Img.into(bg, background ?: art.hero, Img.Spec(screenW))
        val l = art.logo
        if (l != null) {
            Img.into(logo, l, Img.Spec(ctx.dp(320), alpha = true)) { b ->
                logo.visibility = if (b != null) View.VISIBLE else View.GONE
                title.visibility = if (b != null) View.GONE else View.VISIBLE
            }
        }
    }

    private fun renderHub(h: GamehubTv.GameHub) {
        hub = h
        GameArt.put(game, h.art)
        h.art?.let { applyArt(it, h.backgroundUrl) } ?: h.backgroundUrl?.let { Img.into(bg, it, Img.Spec(screenW)) }
        meta.text = buildString {
            append(Fmt.count(h.captures))
            if (h.sessions > 0) append("  ·  ").append(ctx.resources.getQuantityString(R.plurals.hub_sessions, h.sessions, h.sessions))
            if (h.firstAt > 0) append("  ·  ").append(ctx.getString(R.string.hub_since, year(h.firstAt)))
        }
        renderStats(h)
        renderFilters()
    }

    private fun year(ms: Long) = Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC).year

    private fun stat(label: String, value: String, extra: View? = null): View = Kit.column(ctx).apply {
        addView(Kit.label(ctx, label, 10.5f, C.text3))
        addView(Kit.value(ctx, value, 20f, C.text), Kit.lp(t = 6, ctx = ctx))
        if (extra != null) addView(extra, Kit.lp(t = 6, ctx = ctx))
    }

    private fun renderStats(h: GamehubTv.GameHub) {
        stats.removeAllViews()
        val st = h.steam
        val cells = ArrayList<View>()
        if (st != null) {
            cells.add(stat(ctx.getString(R.string.hub_playtime), hours(st.playtimeMin)))
            if (st.last2wMin > 0) cells.add(stat(ctx.getString(R.string.hub_last2w), hours(st.last2wMin)))
            if (st.lastPlayed > 0) cells.add(stat(ctx.getString(R.string.hub_last_played), ago(st.lastPlayed)))
        }
        if (h.sessions > 0) cells.add(stat(ctx.getString(R.string.hub_sessions_label), h.sessions.toString()))
        cells.add(stat(ctx.getString(R.string.hub_captures), h.captures.toString()))
        cells.add(stat(ctx.getString(R.string.hub_favourites), h.favourites.toString()))
        if (st != null && st.achTotal > 0) {
            val bar = Bar(ctx).apply { value = st.achUnlocked.toFloat() / st.achTotal }
            cells.add(stat(ctx.getString(R.string.hub_achievements), "${st.achUnlocked} / ${st.achTotal}", bar))
        }
        for ((i, c) in cells.withIndex()) stats.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) marginStart = ctx.dp(34) })

        ach.removeAllViews()
        if (st != null && (st.rarest.isNotEmpty() || st.recent.isNotEmpty())) {
            fun group(label: Int, list: List<GamehubTv.Achievement>) {
                if (list.isEmpty()) return
                if (ach.childCount > 0) ach.addView(View(ctx), LinearLayout.LayoutParams(ctx.dp(30), 1))
                ach.addView(Kit.label(ctx, ctx.getString(label), 10.5f, C.text3), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.dp(12) })
                for (a in list.take(3)) {
                    val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; roundCorners(5f); setBackgroundColor(C.surface2) }
                    Img.into(iv, a.icon, Img.Spec(ctx.px(30)))
                    ach.addView(iv, LinearLayout.LayoutParams(ctx.dp(30), ctx.dp(30)))
                    val name = Kit.body(ctx, a.name + (a.rarity?.let { "  ${formatPct(it)}" } ?: ""), 12.5f, C.text2).apply { maxLines = 1; maxWidth = ctx.dp(170); ellipsize = android.text.TextUtils.TruncateAt.END }
                    ach.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dp(8); marginEnd = ctx.dp(16) })
                }
            }
            group(R.string.hub_rarest, st.rarest)
            group(R.string.hub_recent, st.recent)
        }
        ach.visibility = if (ach.childCount > 0) View.VISIBLE else View.GONE
    }

    private fun formatPct(r: Double) = if (r < 10) String.format(Locale.US, "%.1f %%", r) else String.format(Locale.US, "%.0f %%", r)

    private fun hours(min: Int): String = if (min < 60) "$min min" else if (min < 600) String.format(Locale.US, "%.1f h", min / 60f) else "${min / 60} h"

    private fun ago(ms: Long): String {
        val d = ((System.currentTimeMillis() - ms) / 86_400_000L).coerceAtLeast(0)
        return when {
            d == 0L -> ctx.getString(R.string.hub_today)
            d == 1L -> ctx.getString(R.string.hub_yesterday)
            d < 60 -> ctx.getString(R.string.hub_days_ago, d.toInt())
            else -> DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()).format(Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC))
        }
    }

    // ── filters ───────────────────────────────────────────────────────────────────────

    private fun chip(text: String, on: Boolean, onClick: () -> Unit): TextView =
        Kit.button(ctx, text, Kit.Btn.GHOST) { onClick() }.apply {
            isActivated = on
            setTextColor(if (on) C.text else C.text2)
        }

    private fun renderFilters() {
        val focused = (0 until filters.childCount).indexOfFirst { filters.getChildAt(it).hasFocus() }
        filters.removeAllViews()
        val h = hub
        val none = !fav && year == null && source == null && session == null
        val chips = ArrayList<TextView>()
        chips.add(chip(ctx.getString(R.string.hub_all), none) { fav = false; year = null; source = null; session = null; reload() })
        chips.add(chip(ctx.getString(R.string.hub_fav_filter), fav) { fav = !fav; reload() })
        if (h != null && h.sessionList.isNotEmpty()) chips.add(chip(session?.let { sessionLabel(it) } ?: ctx.getString(R.string.hub_session_filter) + "  ▾", session != null) { pickSession() })
        if (h != null && h.years.size > 1) chips.add(chip((year?.toString() ?: ctx.getString(R.string.hub_year_filter)) + "  ▾", year != null) { pickYear() })
        if (h != null && h.sources.size > 1) chips.add(chip((source?.uppercase() ?: ctx.getString(R.string.hub_source_filter)) + "  ▾", source != null) { pickSource() })
        for (c in chips) filters.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.dp(4) })
        if (focused >= 0) filters.getChildAt(focused.coerceAtMost(filters.childCount - 1))?.requestFocus()
    }

    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())

    private fun sessionLabel(s: GamehubTv.SessionInfo) = dayFmt.format(Instant.ofEpochMilli(s.start).atOffset(ZoneOffset.UTC)) + "  ·  " + s.count

    private fun pick(eyebrow: String, options: List<Pair<String, () -> Unit>>) {
        val sheet = Sheet(ctx, 380)
        sheet.header(eyebrow, game, null)
        for ((label, action) in options) sheet.body.addView(ListRow(ctx, label) { host.closeSheet(); action() })
        host.openSheet(sheet)
        sheet.focus(null)
    }

    private fun pickSession() {
        val h = hub ?: return
        pick(ctx.getString(R.string.hub_session_filter), listOf(ctx.getString(R.string.hub_all_sessions) to { session = null; reload() }) +
            h.sessionList.map { s -> sessionLabel(s) to { session = s; reload() } })
    }

    private fun pickYear() {
        val h = hub ?: return
        pick(ctx.getString(R.string.hub_year_filter), listOf(ctx.getString(R.string.hub_all_years) to { year = null; reload() }) +
            h.years.sortedDescending().map { y -> y.toString() to { year = y; reload() } })
    }

    private fun pickSource() {
        val h = hub ?: return
        pick(ctx.getString(R.string.hub_source_filter), listOf(ctx.getString(R.string.hub_all_sources) to { source = null; reload() }) +
            h.sources.map { src -> src.replaceFirstChar { it.uppercase() } to { source = src; reload() } })
    }

    private fun filterMap(): Map<String, String> {
        val m = HashMap<String, String>()
        if (fav) m["fav"] = "1"
        year?.let { m["year"] = it.toString() }
        source?.let { m["source"] = it }
        session?.let { m["session"] = it.key }
        return m
    }

    private fun reload() {
        renderFilters()
        shots.clear()
        next = null
        rebuild()
        load()
    }

    // ── captures ──────────────────────────────────────────────────────────────────────

    private fun load() {
        job?.cancel()
        val f = filterMap()
        loadingMore = true
        job = host.activity.lifecycleScope.launch {
            if (shots.isEmpty()) {
                val cached = withContext(Dispatchers.IO) { GamehubTv.cachedShots(ctx, "game:$game", f) }
                if (cached != null && shots.isEmpty()) { shots.addAll(cached.items); next = cached.next; rebuild() }
            }
            val r = withContext(Dispatchers.IO) { GamehubTv.shots(ctx, "game:$game", limit = 120, filters = f) }
            loadingMore = false
            when (r) {
                is GamehubTv.Res.Ok -> {
                    shots.clear(); shots.addAll(r.value.items); next = r.value.next
                    message.visibility = if (shots.isEmpty()) View.VISIBLE else View.GONE
                    message.text = ctx.getString(R.string.hub_no_captures)
                    rebuild()
                }
                is GamehubTv.Res.Err -> if (shots.isEmpty()) {
                    message.visibility = View.VISIBLE
                    message.text = ctx.getString(R.string.tv_offline_body, r.message)
                }
            }
        }
    }

    private fun loadMore() {
        val cursor = next ?: return
        if (loadingMore) return
        loadingMore = true
        val f = filterMap()
        host.activity.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { GamehubTv.shots(ctx, "game:$game", before = cursor, limit = 120, filters = f) }
            loadingMore = false
            if (closed || f != filterMap()) return@launch
            if (r is GamehubTv.Res.Ok) {
                val ids = shots.mapTo(HashSet()) { it.id }
                for (s in r.value.items) if (ids.add(s.id)) shots.add(s)
                next = r.value.next
                rebuild()
            }
        }
    }

    private fun localDay(s: Shot): String = if (s.capturedAt <= 0) "" else Fmt.date(s)

    /** Day sections of justified rows: every row exactly [contentW] wide except a section's last. */
    private fun rebuild() {
        val out = ArrayList<Item>()
        out.add(Item.Header)
        var i = 0
        while (i < shots.size) {
            val day = localDay(shots[i])
            var end = i
            while (end < shots.size && localDay(shots[end]) == day) end++
            out.add(Item.Section(day))
            justify(i, end, out)
            i = end
        }
        val old = items
        items = out
        val r = androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = out.size
            override fun areItemsTheSame(o: Int, n: Int) = key(old[o]) == key(out[n])
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == out[n]
        })
        r.dispatchUpdatesTo(adapter)
        if (!entered && shots.isNotEmpty()) {
            entered = true
            // Enter at the latest capture.
            list.post { list.layoutManager?.findViewByPosition(2)?.let { (it as? ViewGroup)?.getChildAt(0)?.requestFocus() } }
        }
    }

    private fun key(it: Item): String = when (it) {
        is Item.Header -> "h"
        is Item.Section -> "s" + it.title
        is Item.Row -> "r" + it.shots.first().id
    }

    private fun aspect(s: Shot): Float = if (s.width > 0 && s.height > 0) (s.width.toFloat() / s.height).coerceIn(0.5f, 3.6f) else 16f / 9f

    private fun justify(from: Int, to: Int, out: MutableList<Item>) {
        var i = from
        while (i < to) {
            var sum = 0f
            var j = i
            while (j < to) {
                sum += aspect(shots[j])
                j++
                if (sum * rowH + (j - i - 1) * gap >= contentW) break
            }
            val n = j - i
            val full = sum * rowH + (n - 1) * gap >= contentW
            val h = if (full) ((contentW - (n - 1) * gap) / sum).toInt() else rowH
            val widths = (i until j).map { (aspect(shots[it]) * h).toInt() }.toMutableList()
            if (full) widths[widths.size - 1] += contentW - (n - 1) * gap - widths.sum() // absorb rounding
            out.add(Item.Row(shots.subList(i, j).toList(), widths, h, i))
            i = j
        }
    }

    private fun playFrom(shot: Shot?) {
        if (shots.isEmpty()) return
        val f = filterMap()
        val ref = CollRef(if (f.isEmpty()) "game:$game" else null, game, if (f.isEmpty()) null else ArrayList(shots))
        host.openShow(ShowRequest(listOf(ref), 0, startShotId = shot?.id, preload = ArrayList(shots), preloadNext = if (f.isEmpty()) next else null))
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        inner class H(v: View) : RecyclerView.ViewHolder(v)

        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = when (items[position]) {
            is Item.Header -> 0
            is Item.Section -> 1
            is Item.Row -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
            0 -> H((header.parent as? ViewGroup)?.let { it.removeView(header); header } ?: header)
            1 -> H(Kit.label(ctx, "", 11.5f, C.text2).apply {
                setPadding(left + ctx.dp(3), ctx.dp(22), 0, ctx.dp(10))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            else -> H(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                setPadding(left - ctx.dp(3), 0, 0, gap - ctx.dp(6))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is Item.Header -> {}
                is Item.Section -> (holder.itemView as TextView).text = item.title
                is Item.Row -> bindRow(holder.itemView as LinearLayout, item)
            }
            if (position >= items.size - 4) loadMore()
        }
    }

    private fun bindRow(row: LinearLayout, r: Item.Row) {
        while (row.childCount > r.shots.size) row.removeViewAt(row.childCount - 1)
        while (row.childCount < r.shots.size) row.addView(PicCard(ctx))
        for ((k, s) in r.shots.withIndex()) {
            val card = row.getChildAt(k) as PicCard
            val w = r.widths[k]
            card.layoutParams = LinearLayout.LayoutParams(w + ctx.dp(6), r.height + ctx.dp(6)).apply { marginEnd = if (k < r.shots.size - 1) gap - ctx.dp(6) else 0 }
            Img.into(card.image, s.previewUrl, Img.Spec(w.coerceAtLeast(ctx.px(80))))
            card.setFavorite(s.favorite)
            card.setOnClickListener { playFrom(s) }
            card.holdItem(HoldItem(s, CollRef("game:$game", game), shots))
            card.contentDescription = Fmt.date(s)
        }
    }

    /** Something changed through the hold menu (favourite, hidden, background). */
    fun changed(item: HoldItem, hidden: Boolean) {
        if (hidden) { shots.removeAll { it.id == item.shot.id }; rebuild() }
        else adapter.notifyItemRangeChanged(1, (items.size - 1).coerceAtLeast(0))
        host.activity.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { GamehubTv.gameHub(ctx, game) }
            if (!closed && r is GamehubTv.Res.Ok) renderHub(r.value)
        }
    }

    fun onKey(@Suppress("UNUSED_PARAMETER") e: KeyEvent): Boolean = false
}

/** A thin progress bar (achievements). */
class Bar(ctx: Context) : View(ctx) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    var value = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(dp(96), dp(4))

    override fun onDraw(c: android.graphics.Canvas) {
        val r = height / 2f
        p.color = C.alpha(C.text, 0.14f)
        c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, p)
        p.color = C.accent
        c.drawRoundRect(0f, 0f, width * value, height.toFloat(), r, r, p)
    }
}
