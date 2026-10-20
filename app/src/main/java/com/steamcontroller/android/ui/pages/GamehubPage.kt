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
import com.steamcontroller.android.hub.GamehubClient
import com.steamcontroller.android.hub.GamehubStore
import com.steamcontroller.android.hub.GamehubSync
import com.steamcontroller.android.shizuku.ShizukuAdbStarter
import com.steamcontroller.android.ui.Host
import com.steamcontroller.android.ui.Page
import com.steamcontroller.android.ui.live.LiveFrame
import com.steamcontroller.android.ui.pill.Pill
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.dp
import com.steamcontroller.android.ui.theme.isWide
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
    private val testResult = Kit.body(ctx, ctx.getString(R.string.hub_test_hint), 13f, C.text2)
    private var focusFirst: View? = null
    private var refreshedAt = 0L
    private var linkedShown: Boolean? = null
    private var busy = false

    override val view: View = build()
    override val defaultFocus: View? get() = focusFirst

    private fun build(): View {
        buildBoot()
        val content: View = if (wide) {
            Kit.row(ctx, Gravity.TOP).apply {
                addView(linkCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f))
                addView(Kit.space(ctx, 16))
                addView(bootCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        } else {
            Kit.column(ctx).apply {
                addView(linkCard)
                addView(bootCard, Kit.lp(t = 8, ctx = ctx))
            }
        }
        return ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(content)
        }
    }

    override fun refresh() { linkedShown = null; renderLink() }
    override fun onShow() = renderLink()

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
