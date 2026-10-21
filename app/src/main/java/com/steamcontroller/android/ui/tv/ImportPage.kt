package com.steamcontroller.android.ui.tv

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.steamcontroller.android.R
import com.steamcontroller.android.hub.GamehubStore
import com.steamcontroller.android.hub.GamehubTv
import com.steamcontroller.android.hub.GamehubTv.ImportGame
import com.steamcontroller.android.hub.GamehubTv.ImportProgress
import com.steamcontroller.android.ui.FocusMemoryFrame
import com.steamcontroller.android.ui.Host
import com.steamcontroller.android.ui.Page
import com.steamcontroller.android.ui.pill.Pill
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.dp
import com.steamcontroller.android.ui.theme.surface
import com.steamcontroller.android.ui.widget.Kit
import com.steamcontroller.android.ui.widget.SwitchView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Whether the hub is importing screenshots right now (`/now`'s `importing`), for the status
 * strip's "Importing 120/857" chip: polled every 30 s while the app is in front, and updated at
 * once by the Import page's batches. Main-thread API.
 */
object ImportStatus {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "import-status").apply { isDaemon = true } }
    private val listeners = LinkedHashSet<(ImportProgress?) -> Unit>()
    private var app: Context? = null
    private var polling = false

    /** The running import, or null when nothing is importing. */
    var progress: ImportProgress? = null
        private set

    fun observe(l: (ImportProgress?) -> Unit) { listeners.add(l); l(progress) }
    fun remove(l: (ImportProgress?) -> Unit) { listeners.remove(l) }

    /** Fresh numbers (from a poll or an import batch); null or a finished run hides the chip. */
    fun report(p: ImportProgress?) {
        val v = p?.takeIf { it.running }
        if (v == progress) return
        progress = v
        for (l in listeners.toList()) l(v)
    }

    private val tick = object : Runnable {
        override fun run() {
            val c = app ?: return
            poll(c)
            main.postDelayed(this, PERIOD)
        }
    }

    private fun poll(c: Context) {
        if (polling || !GamehubStore.isLinked(c)) return
        polling = true
        io.execute {
            val r = GamehubTv.nowStatus(c)
            main.post {
                polling = false
                when (r) {
                    is GamehubTv.Res.Ok -> report(r.value.importing)
                    is GamehubTv.Res.Err -> if (r.fail == GamehubTv.Fail.NOT_LINKED || r.fail == GamehubTv.Fail.UNAUTHORIZED) report(null)
                }
            }
        }
    }

    /** While the activity is resumed. */
    fun start(c: Context) {
        app = c.applicationContext
        main.removeCallbacks(tick)
        main.post(tick)
    }

    fun stop() {
        app = null
        main.removeCallbacks(tick)
    }

    private const val PERIOD = 30_000L
}

/**
 * IMPORT: every game with Steam screenshots, one Import switch per game (A toggles), Import all
 * and Scan again, and a progress bar with the time left. While this page is open it drives the
 * import itself, one `POST /import/batch` at a time (device-api.md): straight on while files are
 * ready, 15 s when nothing was ready, 60 s when Steam pushes back (or 429), 30 s after any other
 * error; the hub's background job carries on slowly once the page closes.
 */
class ImportPage(host: Host) : Page(host) {
    private val res = ctx.resources
    private val col = Kit.column(ctx)
    private val allBtn = Kit.button(ctx, ctx.getString(R.string.imp_all), Kit.Btn.PRIMARY, R.drawable.ic_nav_import) { importAll() }
    private val scanBtn = Kit.button(ctx, ctx.getString(R.string.imp_scan), Kit.Btn.SECONDARY, R.drawable.ic_refresh) { scan() }
    private val scanLine = Kit.label(ctx, "", 11f, C.text3)
    private val progLabel = Kit.label(ctx, ctx.getString(R.string.imp_progress_label), 11f, C.text3)
    private val progValue = Kit.value(ctx, "", 22f, C.text)
    private val progEta = Kit.body(ctx, "", 13f, C.text2)
    private val progBar = ImportBar(ctx)
    private val message = Kit.body(ctx, "", 15f, C.text2)
    private val listMemory = FocusMemoryFrame(ctx)
    private val list = TvRecycler(ctx, TvRecycler.LOCK_RIGHT or TvRecycler.LOCK_DOWN)
    private val adapter = SimpleAdapter<ImportGame>(emptyList(), { ImportRow(ctx) }) { v, g, _ -> bindRow(v as ImportRow, g) }
    private val cover = Img.cover(ctx, ImportRow.COVER_W, ImportRow.COVER_H, ImportRow.SCALE)

    private var state: GamehubTv.ImportState? = null
    private var progress = ImportProgress(0, 0, null)
    private var games: List<ImportGame> = emptyList()
    private var shown = false
    private var reader: Job? = null
    private var driver: Job? = null
    private var scanner: Job? = null
    private var readAt = 0L
    /** A note under the bar while the loop waits (Steam asked for a pause, the hub did not answer). */
    private var waitNote: String? = null
    /** Switches written but not answered yet: their rows keep the local value through a re-read. */
    private val pendingSwitch = HashMap<Long, Boolean>()

    override val view: View = build()
    override val defaultFocus: View? get() = list.layoutManager?.findViewByPosition(0) ?: allBtn

    private fun build(): View {
        col.clipChildren = false
        val head = Kit.row(ctx, Gravity.BOTTOM)
        head.clipChildren = false
        val texts = Kit.column(ctx)
        texts.addView(Kit.label(ctx, ctx.getString(R.string.imp_eyebrow), 11f, C.text3))
        texts.addView(Kit.title(ctx, ctx.getString(R.string.imp_title), 24f), Kit.lp(t = 8, ctx = ctx))
        texts.addView(Kit.body(ctx, ctx.getString(R.string.imp_body), 13.5f, C.text2).apply { maxLines = 2 }, Kit.lp(t = 6, ctx = ctx))
        head.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ctx.dp(24) })
        head.addView(allBtn)
        head.addView(scanBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dp(4) })
        col.addView(head, Kit.lp(l = 3, ctx = ctx))

        // The run: numbers, the bar, the time left; the scan's state underneath.
        val card = Kit.card(ctx, 18)
        val top = Kit.row(ctx, Gravity.BOTTOM)
        val left = Kit.column(ctx)
        left.addView(progLabel)
        left.addView(progValue, Kit.lp(t = 6, ctx = ctx))
        top.addView(left, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        top.addView(Kit.grow(ctx))
        top.addView(progEta)
        card.addView(top)
        card.addView(progBar, Kit.lp(t = 12, ctx = ctx))
        card.addView(scanLine, Kit.lp(t = 12, ctx = ctx))
        col.addView(card, Kit.lp(t = 16, ctx = ctx))

        message.visibility = View.GONE
        col.addView(message, Kit.lp(t = 18, l = 3, ctx = ctx).apply { width = ctx.dp(620) })

        list.layoutManager = KeylineLinearV(ctx) { (list.height * 0.3f).toInt() }
        list.adapter = adapter
        list.setItemViewCacheSize(4)
        list.setPadding(0, ctx.dp(8), 0, ctx.dp(40))
        list.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) { outRect.bottom = ctx.dp(4) }
        })
        listMemory.clipChildren = false
        listMemory.fallback = { (list.layoutManager as? LinearLayoutManager)?.let { lm -> lm.findViewByPosition(lm.findFirstCompletelyVisibleItemPosition().coerceAtLeast(0)) } }
        listMemory.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        col.addView(listMemory, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = ctx.dp(12) })
        renderProgress()
        return col
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────────────

    override fun onShow() {
        shown = true
        read(force = true)
        reader?.cancel()
        reader = host.activity.lifecycleScope.launch {
            // The whole inventory at most every 30 s; the batch answers carry the progress between.
            while (shown) {
                delay(READ_EVERY_MS)
                if (shown) read(force = false)
            }
        }
    }

    override fun onHide() {
        shown = false
        reader?.cancel(); reader = null
        driver?.cancel(); driver = null
        scanner?.cancel(); scanner = null
    }

    override fun refresh() { if (shown) read(force = false) }

    override fun releaseImages() {
        for (k in 0 until list.childCount) (list.getChildAt(k) as? ImportRow)?.let { Img.release(it.cover) }
        list.recycledViewPool.clear()
    }

    override fun restoreImages() { adapter.notifyItemRangeChanged(0, adapter.itemCount) }

    // ── reading ───────────────────────────────────────────────────────────────────────

    private var reading = false

    private fun read(force: Boolean) {
        if (reading) return
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - readAt < READ_EVERY_MS - 1000L) return
        reading = true
        host.activity.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { GamehubTv.importState(ctx) }
            reading = false
            readAt = android.os.SystemClock.uptimeMillis()
            when (r) {
                is GamehubTv.Res.Ok -> applyState(r.value)
                is GamehubTv.Res.Err -> failed(r)
            }
        }
    }

    private fun applyState(s: GamehubTv.ImportState) {
        state = s
        progress = s.progress
        ImportStatus.report(progress)
        // A switch still on its way keeps the value Kent gave it.
        games = s.games.map { g -> pendingSwitch[g.appid]?.let { g.copy(importing = it) } ?: g }
        updateList()
        renderProgress()
        when {
            !s.enabled -> showMessage(ctx.getString(if (s.reason == "no_steam_id") R.string.imp_no_id else R.string.imp_no_key))
            s.games.isEmpty() && !s.scan.running -> showMessage(ctx.getString(R.string.imp_empty))
            else -> showMessage(null)
        }
        allBtn.isEnabled = s.enabled
        scanBtn.isEnabled = s.enabled
        if (s.scan.running) keepScanning()
        drive()
    }

    private fun failed(e: GamehubTv.Res.Err) {
        when (e.fail) {
            GamehubTv.Fail.NOT_DEPLOYED -> showMessage(ctx.getString(R.string.imp_update))
            GamehubTv.Fail.NOT_LINKED, GamehubTv.Fail.UNAUTHORIZED -> showMessage(ctx.getString(R.string.lib_link_body))
            else -> if (state == null) showMessage(ctx.getString(R.string.tv_offline_body, e.message))
        }
    }

    private fun showMessage(text: String?) {
        message.text = text ?: ""
        message.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun updateList() {
        val hadFocus = list.hasFocus()
        adapter.update(games, { it.appid }) { a, b -> a == b }
        if (hadFocus && !list.hasFocus()) listMemory.requestFocus()
    }

    // ── progress ──────────────────────────────────────────────────────────────────────

    private fun renderProgress() {
        val p = progress
        val s = state
        if (p.running) {
            progLabel.text = ctx.getString(R.string.imp_progress_label)
            progValue.text = ctx.getString(R.string.imp_progress, p.done, p.total)
            progValue.setTextColor(C.text)
            progBar.value = p.done.toFloat() / p.total
            progBar.active = true
            progEta.text = waitNote ?: p.etaSec?.let { ctx.getString(R.string.imp_eta, eta(it)) } ?: ctx.getString(R.string.imp_eta_unknown)
            progEta.setTextColor(if (waitNote != null) C.warning else C.text2)
        } else {
            progLabel.text = ctx.getString(R.string.imp_progress_label)
            progValue.text = ctx.getString(R.string.imp_idle)
            progValue.setTextColor(C.text2)
            progBar.value = if (p.total > 0) 1f else 0f
            progBar.active = false
            progEta.text = ""
        }
        scanLine.text = when {
            s == null -> ""
            s.scan.running -> ctx.getString(R.string.imp_scanning)
            s.scan.lastDone > 0 -> ctx.getString(R.string.imp_scanned, s.scan.total, ago(s.scan.lastDone))
            else -> ctx.getString(R.string.imp_never)
        }
        scanLine.setTextColor(if (s?.scan?.running == true) C.accent else C.text3)
    }

    private fun eta(sec: Int): String = when {
        sec < 60 -> "$sec s"
        sec < 3600 -> "${(sec + 30) / 60} min"
        else -> "${sec / 3600} h ${(sec % 3600) / 60} min"
    }

    private fun ago(ms: Long): String {
        val s = (System.currentTimeMillis() - ms) / 1000
        return when {
            s < 60 -> ctx.getString(R.string.hub_ago_now)
            s < 3600 -> ctx.getString(R.string.hub_ago_min, s / 60)
            s < 86_400 -> ctx.getString(R.string.hub_ago_h, s / 3600)
            else -> ctx.getString(R.string.hub_ago_days, s / 86_400)
        }
    }

    private fun setProgress(p: ImportProgress?) {
        if (p == null) return
        progress = p
        ImportStatus.report(p)
        renderProgress()
    }

    // ── driving the import ────────────────────────────────────────────────────────────

    /** One batch at a time while something is left and the page is open. */
    private fun drive() {
        if (!shown || driver?.isActive == true || !progress.running || state?.enabled == false) return
        driver = host.activity.lifecycleScope.launch {
            while (shown && progress.running) {
                val r = withContext(Dispatchers.IO) { GamehubTv.importBatch(ctx) }
                if (!shown) break
                val wait: Long = when (r) {
                    is GamehubTv.Res.Ok -> {
                        val b = r.value
                        mergeGames(b.games)
                        waitNote = if (b.stopped != null) ctx.getString(R.string.imp_backoff) else null
                        setProgress(b.progress)
                        when {
                            b.stopped != null -> WAIT_STOPPED_MS
                            b.claimed == 0 -> WAIT_IDLE_MS
                            else -> 0L
                        }
                    }
                    is GamehubTv.Res.Err -> {
                        // No Steam key (503 no_key), a hub without the call, a lost link: stop and re-read.
                        if (r.message == "no_key" || r.fail == GamehubTv.Fail.NOT_DEPLOYED || r.fail == GamehubTv.Fail.NOT_LINKED || r.fail == GamehubTv.Fail.UNAUTHORIZED) {
                            waitNote = null
                            renderProgress()
                            read(force = true)
                            break
                        }
                        waitNote = ctx.getString(if (r.fail == GamehubTv.Fail.BUSY) R.string.imp_backoff else R.string.imp_retry)
                        renderProgress()
                        if (r.fail == GamehubTv.Fail.BUSY) WAIT_STOPPED_MS else WAIT_ERROR_MS
                    }
                }
                if (wait > 0) delay(wait)
            }
            waitNote = null
            renderProgress()
        }
    }

    private fun mergeGames(fresh: List<ImportGame>) {
        if (fresh.isEmpty()) return
        val byId = fresh.associateBy { it.appid }
        games = games.map { g -> byId[g.appid]?.let { f -> GamehubTv.mergeImportGame(g, f).let { m -> pendingSwitch[g.appid]?.let { m.copy(importing = it) } ?: m } } ?: g }
        updateList()
    }

    /** Keeps the inventory scan reading, one call at a time, while it runs and the page is open. */
    private fun keepScanning() {
        if (!shown || scanner?.isActive == true) return
        scanner = host.activity.lifecycleScope.launch {
            var running = true
            while (shown && running) {
                val r = withContext(Dispatchers.IO) { GamehubTv.importScan(ctx) }
                if (!shown) break
                when (r) {
                    is GamehubTv.Res.Ok -> {
                        running = r.value?.running ?: false
                        r.value?.let { sc -> state = state?.copy(scan = sc) }
                        renderProgress()
                        if (running) delay(SCAN_GAP_MS)
                    }
                    is GamehubTv.Res.Err -> {
                        if (r.fail == GamehubTv.Fail.NOT_DEPLOYED || r.message == "no_key" || r.message == "no_steam_id") break
                        delay(if (r.fail == GamehubTv.Fail.BUSY) WAIT_STOPPED_MS else WAIT_ERROR_MS)
                    }
                }
            }
            // The inventory changed: games and counts are new.
            if (shown) read(force = true)
        }
    }

    // ── actions ───────────────────────────────────────────────────────────────────────

    private fun toggle(g: ImportGame) {
        val on = !g.importing
        pendingSwitch[g.appid] = on
        games = games.map { if (it.appid == g.appid) it.copy(importing = on) else it }
        updateList()
        host.activity.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { GamehubTv.setImport(ctx, g.appid, on) }
            pendingSwitch.remove(g.appid)
            when (r) {
                is GamehubTv.Res.Ok -> {
                    setProgress(r.value)
                    Pill.show(ctx, Pill.Kind.SAVED, ctx.getString(if (on) R.string.imp_switch_on else R.string.imp_switch_off, g.name))
                    drive()
                    // The counts (queued) come with the next re-read.
                    read(force = true)
                }
                is GamehubTv.Res.Err -> {
                    games = games.map { if (it.appid == g.appid) it.copy(importing = !on) else it }
                    updateList()
                    Pill.show(ctx, Pill.Kind.FAILED, ctx.getString(if (r.fail == GamehubTv.Fail.NOT_DEPLOYED) R.string.hold_needs_update else R.string.hold_failed))
                }
            }
        }
    }

    private fun importAll() {
        if (state?.enabled == false) return
        games = games.map { it.copy(importing = true) }
        updateList()
        host.activity.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { GamehubTv.importAll(ctx) }
            when (r) {
                is GamehubTv.Res.Ok -> {
                    setProgress(r.value)
                    Pill.show(ctx, Pill.Kind.SAVED, ctx.getString(R.string.imp_all_done))
                    drive()
                }
                is GamehubTv.Res.Err -> Pill.show(ctx, Pill.Kind.FAILED, ctx.getString(if (r.fail == GamehubTv.Fail.NOT_DEPLOYED) R.string.hold_needs_update else R.string.hold_failed))
            }
            read(force = true)
        }
    }

    private fun scan() {
        if (state?.enabled == false) return
        state = state?.let { it.copy(scan = it.scan.copy(running = true)) }
        renderProgress()
        scanner?.cancel()
        scanner = null
        keepScanning()
    }

    // ── rows ──────────────────────────────────────────────────────────────────────────

    private fun bindRow(row: ImportRow, g: ImportGame) {
        Img.into(row.cover, g.cover, cover)
        row.name.text = g.name
        row.counts.text = buildString {
            append(ctx.getString(R.string.imp_game_counts, g.imported, g.total))
            if (g.queued > 0) append("  ·  ").append(ctx.getString(R.string.imp_game_queued, g.queued))
            if (g.failed > 0) append("  ·  ").append(ctx.getString(R.string.imp_game_failed, g.failed))
        }
        row.counts.setTextColor(if (g.failed > 0) C.warning else C.text2)
        row.bar.value = if (g.total > 0) g.imported.toFloat() / g.total else 0f
        row.bar.active = g.importing && g.queued > 0
        if (row.sw.checked != g.importing) row.sw.setChecked(g.importing, animate = row.boundId == g.appid)
        row.boundId = g.appid
        row.setOnClickListener { games.firstOrNull { it.appid == g.appid }?.let { toggle(it) } }
        row.contentDescription = g.name
    }

    private companion object {
        const val READ_EVERY_MS = 30_000L
        const val WAIT_IDLE_MS = 15_000L
        const val WAIT_STOPPED_MS = 60_000L
        const val WAIT_ERROR_MS = 30_000L
        const val SCAN_GAP_MS = 1_500L
    }
}

/** One game on the Import page: cover, name, counts, a thin bar and the switch (A toggles). */
private class ImportRow(ctx: Context) : LinearLayout(ctx) {
    val cover = ImageView(ctx)
    val name: TextView = Kit.title(ctx, "", 16f)
    val counts: TextView = Kit.body(ctx, "", 12.5f, C.text2).apply { maxLines = 1 }
    val bar = ImportBar(ctx, thin = true)
    val sw = SwitchView(ctx)
    var boundId = 0L

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        surface(radiusDp = 10f, fill = C.surface, stroke = C.line, focusable = true, activeFill = C.surface2, scale = SCALE)
        setPadding(dp(13), dp(10), dp(19), dp(10))
        cover.scaleType = ImageView.ScaleType.CENTER_CROP
        cover.setBackgroundColor(C.surface2)
        cover.roundCorners(6f)
        addView(cover, LayoutParams(dp(COVER_W), dp(COVER_H)))
        val texts = Kit.column(ctx)
        texts.addView(name)
        texts.addView(counts, Kit.lp(t = 5, ctx = ctx))
        texts.addView(bar, Kit.lp(t = 8, ctx = ctx))
        addView(texts, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16); marginEnd = dp(20) })
        addView(sw)
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    companion object {
        const val COVER_W = 128
        const val COVER_H = 60
        const val SCALE = 1.02f
    }
}

/** A progress bar: accent fill on a faint track; [active] adds a soft sheen while it runs. */
private class ImportBar(ctx: Context, private val thin: Boolean = false) : View(ctx) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    var value = 0f
        set(v) { val c = v.coerceIn(0f, 1f); if (c != field) { field = c; invalidate() } }
    var active = false
        set(v) { if (v != field) { field = v; invalidate() } }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), dp(if (thin) 3 else 6))

    override fun onDraw(c: android.graphics.Canvas) {
        val r = height / 2f
        p.shader = null
        p.color = C.alpha(C.text, 0.10f)
        c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, p)
        if (value <= 0f) return
        p.color = if (active) C.accent else C.alpha(C.accent, if (thin) 0.45f else 0.6f)
        c.drawRoundRect(0f, 0f, (width * value).coerceAtLeast(height.toFloat()), height.toFloat(), r, r, p)
    }
}
