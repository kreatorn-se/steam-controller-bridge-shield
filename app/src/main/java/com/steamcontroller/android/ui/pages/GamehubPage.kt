package com.steamcontroller.android.ui.pages

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.steamcontroller.android.R
import com.steamcontroller.android.hub.CaptureStore
import com.steamcontroller.android.hub.EnshGuide
import com.steamcontroller.android.hub.GamehubClient
import com.steamcontroller.android.hub.GamehubStore
import com.steamcontroller.android.hub.GamehubSync
import com.steamcontroller.android.hub.LevelsMath
import com.steamcontroller.android.hub.SteamCloud
import com.steamcontroller.android.hub.SteamLoginActivity
import com.steamcontroller.android.ui.widget.ActionRow
import com.steamcontroller.android.shizuku.ShizukuAdbStarter
import com.steamcontroller.android.ui.Host
import com.steamcontroller.android.ui.Page
import com.steamcontroller.android.ui.live.LiveFrame
import com.steamcontroller.android.ui.pill.Pill
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.dp
import com.steamcontroller.android.ui.theme.isWide
import com.steamcontroller.android.ui.widget.ChoiceRow
import com.steamcontroller.android.ui.widget.CodeEntryView
import com.steamcontroller.android.ui.widget.DotView
import com.steamcontroller.android.ui.widget.Kit
import com.steamcontroller.android.ui.widget.ToggleRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * GAMEHUB: link this Shield to gamehub.kreatorn.se with a six-digit code, see the upload queue and
 * sync state, and set up starting Shizuku and the bridge after a reboot.
 */
class GamehubPage(host: Host) : Page(host) {
    private val wide = ctx.isWide()
    private val linkCard = Kit.card(ctx, 22)
    private val bootCard = Kit.card(ctx, 22)
    private val tvCard = Kit.card(ctx, 22)
    private val savesCard = Kit.card(ctx, 22)
    private lateinit var cloudRow: ActionRow
    private lateinit var fetchRow: ActionRow
    private lateinit var cloudOffRow: ActionRow
    private val guideBox = Kit.column(ctx)
    private var guideAt = 0L
    private var guideBusy = false
    private lateinit var zoomRow: ToggleRow
    private lateinit var logoRow: ToggleRow
    private lateinit var satRow: ChoiceRow
    private val testResult = Kit.body(ctx, ctx.getString(R.string.hub_test_hint), 13f, C.text2)
    private var focusFirst: View? = null
    private var refreshedAt = 0L
    private var linkedShown: Boolean? = null
    private var busy = false

    override val view: View = build()
    override val defaultFocus: View? get() = focusFirst

    private fun build(): View {
        buildBoot()
        buildTv()
        buildSaves()
        val content: View = if (wide) {
            Kit.row(ctx, Gravity.TOP).apply {
                addView(linkCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f))
                addView(Kit.space(ctx, 16))
                addView(Kit.column(ctx).apply {
                    addView(bootCard)
                    addView(tvCard, Kit.lp(t = 16, ctx = ctx))
                    addView(savesCard, Kit.lp(t = 16, ctx = ctx))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        } else {
            Kit.column(ctx).apply {
                addView(linkCard)
                addView(bootCard, Kit.lp(t = 8, ctx = ctx))
                addView(tvCard, Kit.lp(t = 8, ctx = ctx))
                addView(savesCard, Kit.lp(t = 8, ctx = ctx))
            }
        }
        return ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(content)
        }
    }

    override fun refresh() { linkedShown = null; renderLink(); zoomRow.refresh(); logoRow.refresh(); satRow.refresh(); renderSaves(); guideAt = 0L; loadGuide() }
    override fun onShow() { renderLink(); zoomRow.refresh(); logoRow.refresh(); satRow.refresh(); renderSaves(); loadGuide() }

    override fun onFrame(f: LiveFrame) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - refreshedAt > 3000L && !busy) renderLink()
    }

    private fun renderLink() {
        refreshedAt = android.os.SystemClock.uptimeMillis()
        val linked = GamehubStore.isLinked(ctx)
        val hadFocus = linkCard.hasFocus()
        val host0 = GamehubStore.url(ctx).removePrefix("https://")
        // Only rebuild when the link state flips; otherwise update the numbers in place.
        if (linkedShown == linked && linked) { fillStats(); return }
        if (linkedShown == linked && !linked) return
        linkedShown = linked
        linkCard.removeAllViews()
        val head = Kit.row(ctx)
        head.addView(Kit.label(ctx, ctx.getString(R.string.hub_link)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(DotView(ctx, 8f).apply { color = if (linked) C.success else C.text3; halo = linked })
        head.addView(Kit.label(ctx, ctx.getString(if (linked) R.string.hub_linked else R.string.hub_not_linked), 11f, if (linked) C.success else C.text3), Kit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, l = 4, ctx = ctx))
        linkCard.addView(head)
        if (linked) {
            linkCard.addView(Kit.title(ctx, host0, 22f), Kit.lp(t = 14, ctx = ctx))
            linkCard.addView(Kit.body(ctx, ctx.getString(R.string.hub_linked_as, GamehubStore.deviceName(ctx)), 14f, C.text2), Kit.lp(t = 6, ctx = ctx))
            linkCard.addView(stat(R.string.hub_pending, "pending"), Kit.lp(t = 16, ctx = ctx))
            linkCard.addView(stat(R.string.hub_last_sync, "last"))
            linkCard.addView(Kit.body(ctx, "", 13f, C.warning).apply { tag = "error"; visibility = View.GONE }, Kit.lp(t = 6, ctx = ctx))
            val btns = Kit.row(ctx)
            val sync = Kit.button(ctx, ctx.getString(R.string.hub_sync_now), Kit.Btn.PRIMARY, R.drawable.ic_refresh) {
                GamehubSync.kick(ctx)
                Pill.show(ctx, Pill.Kind.INFO, ctx.getString(R.string.hub_syncing))
                linkCard.postDelayed({ fillStats() }, 2500L)
            }
            btns.addView(sync)
            btns.addView(Kit.button(ctx, ctx.getString(R.string.hub_unlink), Kit.Btn.DANGER) { confirmUnlink() }, Kit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, l = 4, ctx = ctx))
            linkCard.addView(btns, Kit.lp(t = 18, ctx = ctx))
            focusFirst = sync
            fillStats()
        } else {
            linkCard.addView(Kit.title(ctx, ctx.getString(R.string.hub_pair_title), 22f), Kit.lp(t = 14, ctx = ctx))
            linkCard.addView(Kit.body(ctx, ctx.getString(R.string.hub_pair_body, host0), 14f, C.text2), Kit.lp(t = 6, ctx = ctx))
            linkCard.addView(Kit.body(ctx, ctx.getString(R.string.hub_pair_steps), 14f, C.text2), Kit.lp(t = 4, ctx = ctx))
            val code = CodeEntryView(ctx)
            val error = Kit.body(ctx, "", 13f, C.danger)
            val row = Kit.row(ctx)
            row.addView(code)
            val link = Kit.button(ctx, ctx.getString(R.string.hub_link_button), Kit.Btn.PRIMARY) { pair(code, error, it) }
            row.addView(link, Kit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, l = 12, ctx = ctx))
            code.onComplete = { link.requestFocus() }
            code.onChange = { error.text = "" }
            linkCard.addView(row, Kit.lp(t = 18, ctx = ctx))
            linkCard.addView(Kit.label(ctx, ctx.getString(R.string.hub_code_keys), 10.5f, C.text3), Kit.lp(t = 8, l = 4, ctx = ctx))
            linkCard.addView(error, Kit.lp(t = 8, l = 4, ctx = ctx))
            focusFirst = code
        }
        if (hadFocus) focusFirst?.requestFocus()
    }

    private fun stat(label: Int, tag: String): View = Kit.row(ctx).apply {
        minimumHeight = ctx.dp(30)
        addView(Kit.label(ctx, ctx.getString(label), 11f, C.text3), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(Kit.value(ctx, "—", 14f).apply { this.tag = tag })
    }

    private fun fillStats() {
        host.activity.lifecycleScope.launch {
            val pending = withContext(Dispatchers.IO) { CaptureStore.pendingCount(ctx) }
            val lastOk = GamehubStore.lastOkAt(ctx)
            val err = GamehubStore.lastError(ctx)
            linkCard.findViewWithTag<android.widget.TextView>("pending")?.text =
                if (pending == 0) ctx.getString(R.string.hub_all_uploaded) else ctx.resources.getQuantityString(R.plurals.hub_waiting, pending, pending)
            linkCard.findViewWithTag<android.widget.TextView>("last")?.text =
                if (lastOk > 0) ago(lastOk) else ctx.getString(R.string.hub_not_synced)
            linkCard.findViewWithTag<android.widget.TextView>("error")?.apply {
                visibility = if (err == null) View.GONE else View.VISIBLE
                text = if (err == null) "" else ctx.getString(R.string.hub_last_problem, err)
            }
        }
    }

    private fun pair(code: CodeEntryView, error: android.widget.TextView, button: View) {
        val digits = code.code
        if (digits.length != 6) {
            error.text = ctx.getString(R.string.hub_six_digits)
            code.error = true
            code.requestFocus()
            return
        }
        busy = true
        button.isEnabled = false
        error.setTextColor(C.text2)
        error.text = ctx.getString(R.string.hub_linking)
        val url = GamehubStore.url(ctx)
        val host0 = url.removePrefix("https://")
        host.activity.lifecycleScope.launch {
            val name = GamehubStore.defaultName()
            val result = withContext(Dispatchers.IO) {
                try {
                    val (id, token) = GamehubClient(url, null).pair(digits, name)
                    GamehubStore.saveLink(ctx, url, id, token, name)
                    null
                } catch (e: GamehubClient.HubException) {
                    if (e.status == 403) ctx.getString(R.string.hub_code_wrong) else ctx.getString(R.string.hub_said, e.message)
                } catch (t: Throwable) {
                    ctx.getString(R.string.hub_unreachable, host0, t.message)
                }
            }
            busy = false
            button.isEnabled = true
            if (result == null) {
                Pill.show(ctx, Pill.Kind.SAVED, ctx.getString(R.string.hub_linked_pill))
                GamehubSync.kick(ctx)
                com.steamcontroller.android.ui.tv.HomeRepo.reset(ctx)
                linkedShown = null
                renderLink()
                focusFirst?.requestFocus()
            } else {
                error.setTextColor(C.danger)
                error.text = result
                code.error = true
                code.requestFocus()
            }
        }
    }

    private fun confirmUnlink() {
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.hub_unlink_title)
            .setMessage(R.string.hub_unlink_body)
            .setPositiveButton(R.string.hub_unlink) { _, _ ->
                GamehubStore.clearLink(ctx)
                // The TV's offline copies and images belong to that link: drop them too.
                com.steamcontroller.android.ui.tv.HomeRepo.forget(ctx)
                Pill.show(ctx, Pill.Kind.INFO, ctx.getString(R.string.hub_unlinked))
                linkedShown = null
                renderLink()
                focusFirst?.requestFocus()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Show mode and the screensaver on this TV. */
    private fun buildTv() {
        tvCard.addView(Kit.label(ctx, ctx.getString(R.string.hub_tv)))
        zoomRow = ToggleRow(ctx, ctx.getString(R.string.hub_slow_zoom), ctx.getString(R.string.hub_slow_zoom_hint),
            { com.steamcontroller.android.ui.tv.TvPrefs.slowZoom(ctx) }, { com.steamcontroller.android.ui.tv.TvPrefs.setSlowZoom(ctx, it) })
        tvCard.addView(zoomRow, Kit.lp(t = 10, ctx = ctx))
        logoRow = ToggleRow(ctx, ctx.getString(R.string.hub_show_logo), ctx.getString(R.string.hub_show_logo_hint),
            { com.steamcontroller.android.ui.tv.TvPrefs.showLogo(ctx) }, { com.steamcontroller.android.ui.tv.TvPrefs.setShowLogo(ctx, it) })
        tvCard.addView(logoRow)
        // Applied when a capture is saved (CaptureLevels); select cycles Off → 6 → 12 → 18 → 25 %.
        val sats = LevelsMath.SATURATION_CHOICES
        satRow = ChoiceRow(ctx, ctx.getString(R.string.hub_capture_sat), ctx.getString(R.string.hub_capture_sat_hint),
            sats.map { if (it == 0) ctx.getString(R.string.hub_capture_sat_off) else ctx.getString(R.string.hub_capture_sat_pct, it) },
            { sats.indexOf(GamehubStore.captureSaturation(ctx)).coerceAtLeast(0) },
            { i -> GamehubStore.setCaptureSaturation(ctx, sats[i]) })
        tvCard.addView(satRow)
    }

    /** Enshrouded saves from Steam Cloud (hub/SteamCloud.kt): connect once, then automatic. */
    private fun buildSaves() {
        savesCard.addView(Kit.label(ctx, ctx.getString(R.string.hub_saves)))
        savesCard.addView(Kit.body(ctx, ctx.getString(R.string.hub_saves_body), 14f, C.text2), Kit.lp(t = 10, b = 8, ctx = ctx))
        savesCard.addView(guideBox, Kit.lp(t = 6, b = 12, ctx = ctx))
        renderGuide(EnshGuide.last)
        cloudRow = ActionRow(ctx, ctx.getString(R.string.hub_saves_connect), ctx.getString(R.string.hub_saves_connect_hint)) {
            ctx.startActivity(android.content.Intent(ctx, SteamLoginActivity::class.java))
        }
        savesCard.addView(cloudRow)
        fetchRow = ActionRow(ctx, ctx.getString(R.string.hub_saves_fetch), null, "") { row ->
            row.valueView.text = ctx.getString(R.string.hub_saves_fetching)
            SteamCloud.syncNow(ctx, "by hand") { result ->
                Pill.show(ctx, Pill.Kind.INFO, result)
                renderSaves()
            }
        }
        savesCard.addView(fetchRow)
        cloudOffRow = ActionRow(ctx, ctx.getString(R.string.hub_saves_disconnect), ctx.getString(R.string.hub_saves_disconnect_hint)) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.hub_saves_disconnect)
                .setMessage(R.string.hub_saves_disconnect_hint)
                .setPositiveButton(R.string.hub_saves_disconnect) { _, _ -> SteamCloud.disconnect(ctx); renderSaves() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        savesCard.addView(cloudOffRow)
    }

    /** The newest Enshrouded guide from Gamehub; at most once every 30 s while the page is shown. */
    private fun loadGuide() {
        val now = android.os.SystemClock.uptimeMillis()
        if (guideBusy || (guideAt > 0 && now - guideAt < 30_000L)) return
        if (!GamehubStore.isLinked(ctx)) { renderGuide(null); return }
        guideBusy = true
        guideAt = now
        host.activity.lifecycleScope.launch {
            val g = EnshGuide.load(ctx) ?: EnshGuide.last
            guideBusy = false
            renderGuide(g)
        }
    }

    /** "NEXT STEPS · WILL IN ZENITH", the date, the numbered lines, the intro smaller. */
    private fun renderGuide(g: GamehubClient.Guide?) {
        guideBox.removeAllViews()
        if (!GamehubStore.isLinked(ctx)) { guideBox.visibility = View.GONE; return }
        guideBox.visibility = View.VISIBLE
        val head = Kit.row(ctx)
        head.addView(Kit.label(ctx, ctx.getString(R.string.hub_saves_next), 11f, C.accent))
        if (g == null || g.lines.isEmpty()) {
            guideBox.addView(head)
            guideBox.addView(Kit.body(ctx, ctx.getString(R.string.hub_saves_next_none), 13f, C.text3), Kit.lp(t = 6, ctx = ctx))
            return
        }
        EnshGuide.who(ctx, g)?.let {
            head.addView(Kit.label(ctx, "· $it", 11f, C.text3), Kit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, l = 6, ctx = ctx))
        }
        guideBox.addView(head)
        EnshGuide.dateLine(ctx, g)?.let { guideBox.addView(Kit.body(ctx, it, 12.5f, C.text3), Kit.lp(t = 4, ctx = ctx)) }
        g.lines.take(3).forEachIndexed { i, line ->
            val row = Kit.row(ctx, Gravity.TOP)
            row.addView(Kit.label(ctx, "${i + 1}", 14f, C.accent).apply { minWidth = ctx.dp(18) })
            row.addView(Kit.body(ctx, line, 14.5f, C.text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            guideBox.addView(row, Kit.lp(t = if (i == 0) 10 else 6, ctx = ctx))
        }
        g.intro?.let {
            guideBox.addView(Kit.body(ctx, it, 12.5f, C.text2).apply {
                maxLines = 4
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, Kit.lp(t = 10, ctx = ctx))
        }
    }

    private fun renderSaves() {
        val on = SteamCloud.isConnected(ctx)
        cloudRow.setTitle(ctx.getString(if (on) R.string.hub_saves_connected else R.string.hub_saves_connect))
        cloudRow.valueView.text = ""
        fetchRow.visibility = if (on) View.VISIBLE else View.GONE
        cloudOffRow.visibility = if (on) View.VISIBLE else View.GONE
        val last = SteamCloud.lastSync(ctx)
        fetchRow.valueView.text = if (last > 0) "${ago(last)} · ${SteamCloud.lastResult(ctx) ?: ""}" else ""
    }

    private fun buildBoot() {
        bootCard.addView(Kit.label(ctx, ctx.getString(R.string.hub_boot)))
        bootCard.addView(Kit.title(ctx, ctx.getString(R.string.hub_boot_title), 18f).apply { maxLines = 2 }, Kit.lp(t = 14, ctx = ctx))
        bootCard.addView(Kit.body(ctx, ctx.getString(R.string.hub_boot_body), 14f, C.text2), Kit.lp(t = 6, b = 12, ctx = ctx))
        bootCard.addView(ToggleRow(ctx, ctx.getString(R.string.hub_boot_switch), null,
            { GamehubStore.autoStartShizuku(ctx) }, { GamehubStore.setAutoStartShizuku(ctx, it) }))
        val test = Kit.button(ctx, ctx.getString(R.string.hub_test), Kit.Btn.SECONDARY) { b ->
            b.isEnabled = false
            testResult.setTextColor(C.text2)
            testResult.text = ctx.getString(R.string.hub_testing)
            host.activity.lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { ShizukuAdbStarter.testConnection(ctx) }
                b.isEnabled = true
                testResult.setTextColor(if (r.ok) C.success else C.warning)
                testResult.text = if (r.ok) ctx.getString(R.string.hub_test_ok) else r.message
            }
        }
        bootCard.addView(test, Kit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, t = 8, ctx = ctx))
        bootCard.addView(testResult, Kit.lp(t = 10, l = 4, ctx = ctx))
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
}
