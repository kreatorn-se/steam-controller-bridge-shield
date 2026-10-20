package com.steamcontroller.android.ui.pages

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.steamcontroller.android.AppPickerActivity
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.R
import com.steamcontroller.android.input.NamedProfile
import com.steamcontroller.android.service.ControllerService
import com.steamcontroller.android.service.UsageStatsHelper
import com.steamcontroller.android.uinput.GamepadProfile
import com.steamcontroller.android.ui.Host
import com.steamcontroller.android.ui.Page
import com.steamcontroller.android.ui.PageId
import com.steamcontroller.android.ui.Sheet
import com.steamcontroller.android.ui.pill.Pill
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.dp
import com.steamcontroller.android.ui.theme.surface
import com.steamcontroller.android.ui.widget.Kit
import com.steamcontroller.android.ui.widget.ListRow
import com.steamcontroller.android.ui.widget.setOnFocusChangeListenerChained
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Profile operations shared by the Profiles page and the quick switch. */
object ProfileOps {
    fun apply(host: Host, p: NamedProfile) {
        val ctx = host.activity
        Prefs.applyNamedProfile(ctx, p)
        // Live: the service re-reads the profile and swaps the virtual device if the mode changed.
        ControllerService.notifyNamedProfileApplied(ctx)
        Pill.show(ctx, Pill.Kind.PROFILE, ctx.getString(R.string.prof_pill, p.name))
        host.refreshAll()
    }

    fun promptName(ctx: Context, title: String, initial: String, onSave: (String) -> Unit) {
        val input = EditText(ctx).apply {
            setText(initial)
            setSelection(text.length)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(C.text)
            setHintTextColor(C.text3)
        }
        val box = LinearLayout(ctx).apply {
            setPadding(ctx.dp(24), ctx.dp(8), ctx.dp(24), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(R.string.prof_save) { _, _ -> onSave(input.text.toString().trim().ifEmpty { initial }) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        input.setOnEditorActionListener { _, _, _ ->
            onSave(input.text.toString().trim().ifEmpty { initial }); dialog.dismiss(); true
        }
        // The controller can look like a hardware keyboard, which suppresses the IME; force it.
        input.requestFocus()
        input.post {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            @Suppress("DEPRECATION")
            imm.showSoftInput(input, InputMethodManager.SHOW_FORCED)
        }
    }

    fun nextName(ctx: Context): String {
        val existing = Prefs.listNamedProfiles(ctx).map { it.name }.toSet()
        var n = 1
        while ("Profile $n" in existing) n++
        return "Profile $n"
    }
}

/** The status strip's profile chip: a quick list to switch the active profile. */
object QuickSwitch {
    fun open(host: Host) {
        val ctx = host.activity
        val sheet = Sheet(ctx, 380)
        sheet.header(ctx.getString(R.string.prof_quick_eyebrow), ctx.getString(R.string.prof_quick_title), ctx.getString(R.string.prof_quick_hint))
        val active = Prefs.getActiveNamedProfileId(ctx)
        var focus: View? = null
        val list = Prefs.listNamedProfiles(ctx)
        if (list.isEmpty()) sheet.body.addView(Kit.body(ctx, ctx.getString(R.string.profiles_empty_title), 14f, C.text2), Kit.lp(l = 11, b = 12, ctx = ctx))
        for (p in list) {
            val cur = p.id == active
            val row = ListRow(ctx, p.name, GamepadProfile.fromId(p.profileId).displayName, tag = if (cur) ctx.getString(R.string.profiles_active_badge) else null, current = cur) {
                host.closeSheet()
                if (!cur) ProfileOps.apply(host, p)
            }
            if (cur || focus == null) focus = row
            sheet.body.addView(row)
        }
        sheet.body.addView(Kit.section(ctx, "", 8))
        sheet.body.addView(ListRow(ctx, ctx.getString(R.string.prof_manage), null) {
            host.closeSheet(); host.go(PageId.PROFILES, focusContent = true)
        })
        host.openSheet(sheet)
        sheet.focus(focus)
    }
}

/**
 * PROFILES: named setups as cards (name, mode, bound apps, active badge). Select a card for its
 * actions; bind apps to switch automatically; hold Steam + D-pad ←/→ switches on the controller.
 */
class ProfilesPage(host: Host) : Page(host) {
    private val grid = Kit.column(ctx)
    private val usageRow = Kit.row(ctx)
    private val cols = (ctx.resources.configuration.screenWidthDp - 140).coerceAtLeast(300) / 260
    private val iconCache = HashMap<String, Drawable?>()
    private var firstCard: View? = null

    override val view: View = build()
    override val defaultFocus: View? get() = firstCard

    private fun build(): View {
        val col = Kit.column(ctx)
        val top = Kit.row(ctx)
        top.addView(Kit.button(ctx, ctx.getString(R.string.prof_new), Kit.Btn.PRIMARY, R.drawable.ic_plus) { newFromCurrent() })
        top.addView(Kit.grow(ctx))
        top.addView(keyHint())
        col.addView(top)
        col.addView(grid, Kit.lp(t = 14, ctx = ctx))
        col.addView(usageRow, Kit.lp(t = 10, ctx = ctx))
        return ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, 0, 0, ctx.dp(12))
            addView(col)
        }
    }

    /** "HOLD [STEAM] + [D-PAD ← →] TO SWITCH" with keycaps. */
    private fun keyHint(): View {
        val r = Kit.row(ctx)
        fun cap(t: String) = Kit.label(ctx, t, 10.5f, C.text).apply {
            setPadding(ctx.dp(8), ctx.dp(4), ctx.dp(8), ctx.dp(4))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(C.raised); cornerRadius = ctx.dp(6f) }
        }
        fun txt(t: String) = Kit.label(ctx, t, 11f, C.text3).apply { setPadding(ctx.dp(7), 0, ctx.dp(7), 0) }
        r.addView(txt(ctx.getString(R.string.prof_hint_hold)))
        r.addView(cap("Steam"))
        r.addView(txt("+"))
        r.addView(cap("D-pad ← →"))
        r.addView(txt(ctx.getString(R.string.prof_hint_switch)))
        return r
    }

    override fun refresh() = render()
    override fun onShow() = render()

    private fun render() {
        val focusedId = (grid.findFocus()?.tag as? String)
        grid.removeAllViews()
        firstCard = null
        val list = Prefs.listNamedProfiles(ctx)
        val active = Prefs.getActiveNamedProfileId(ctx)
        if (list.isEmpty()) {
            val empty = Kit.card(ctx, 24)
            empty.addView(Kit.title(ctx, ctx.getString(R.string.profiles_empty_title), 18f))
            empty.addView(Kit.body(ctx, ctx.getString(R.string.prof_empty_body), 14f, C.text2), Kit.lp(t = 8, ctx = ctx))
            grid.addView(empty)
        }
        var row: LinearLayout? = null
        var refocus: View? = null
        list.forEachIndexed { i, p ->
            if (i % cols == 0) {
                row = Kit.row(ctx, Gravity.TOP).also { grid.addView(it, Kit.lp(b = 4, ctx = ctx)) }
            }
            val card = card(p, p.id == active)
            if (firstCard == null || p.id == active) firstCard = card
            if (p.id == focusedId) refocus = card
            row!!.addView(card, LinearLayout.LayoutParams(0, ctx.dp(128), 1f).apply { if (i % cols != 0) marginStart = ctx.dp(4) })
        }
        // keep the last row's cards the same width as the others
        val rem = list.size % cols
        if (rem != 0) repeat(cols - rem) { row?.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = ctx.dp(4) }) }
        refocus?.requestFocus()

        usageRow.removeAllViews()
        usageRow.addView(Kit.body(ctx, ctx.getString(R.string.prof_now_playing_note), 13f, C.text3), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun card(p: NamedProfile, active: Boolean): View {
        val card = Kit.column(ctx).apply {
            surface(radiusDp = 10f, focusable = true, activeFill = C.surface2, activeStroke = C.alpha(C.accent, 0.45f))
            isActivated = active
            setPadding(ctx.dp(21), ctx.dp(17), ctx.dp(21), ctx.dp(15))
            tag = p.id
            setOnClickListener { actions(p, active) }
        }
        val head = Kit.row(ctx)
        val name = Kit.title(ctx, p.name, 17f)
        head.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (active) head.addView(Kit.label(ctx, ctx.getString(R.string.profiles_active_badge), 10f, C.onAccent).apply {
            setPadding(ctx.dp(8), ctx.dp(3), ctx.dp(8), ctx.dp(3))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(C.accent); cornerRadius = ctx.dp(10f) }
        })
        card.addView(head)
        val gp = GamepadProfile.fromId(p.profileId)
        card.addView(Kit.label(ctx, if (gp.isMouseMode) ctx.getString(R.string.prof_mode_desktop) else ctx.getString(R.string.prof_mode_gamepad), 11f, C.text2), Kit.lp(t = 6, ctx = ctx))
        card.addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))
        // The Steam games this profile follows (see actions()).
        if (p.steamAppIds.isNotEmpty()) card.addView(Kit.label(ctx, ctx.resources.getQuantityString(R.plurals.steam_games, p.steamAppIds.size, p.steamAppIds.size), 10.5f, C.accent))
        card.setOnFocusChangeListenerChained { has -> name.setTextColor(if (has) C.text else C.text) }
        return card
    }

    private fun loadIcon(pkg: String, into: ImageView) {
        if (iconCache.containsKey(pkg)) { into.setImageDrawable(iconCache[pkg]); return }
        host.activity.lifecycleScope.launch {
            val d = withContext(Dispatchers.IO) { try { ctx.packageManager.getApplicationIcon(pkg) } catch (_: Throwable) { null } }
            iconCache[pkg] = d
            into.setImageDrawable(d)
        }
    }

    private fun actions(p: NamedProfile, active: Boolean) {
        val sheet = Sheet(ctx)
        sheet.header(ctx.getString(R.string.prof_sheet_eyebrow), p.name, GamepadProfile.fromId(p.profileId).displayName)
        val first = ListRow(ctx, ctx.getString(if (active) R.string.prof_reapply else R.string.prof_apply), ctx.getString(R.string.prof_apply_hint)) {
            host.closeSheet(); ProfileOps.apply(host, p); render()
        }
        sheet.body.addView(first)
        sheet.body.addView(ListRow(ctx, ctx.getString(R.string.prof_update), ctx.getString(R.string.prof_update_hint)) {
            host.closeSheet()
            Prefs.saveNamedProfile(ctx, Prefs.captureCurrentAsProfile(ctx, p.name, existingId = p.id))
            Pill.show(ctx, Pill.Kind.SAVED, ctx.getString(R.string.prof_updated, p.name))
            render()
        })
        // "Bind to apps" is gone: on the Shield everything runs inside GeForce NOW, so the
        // foreground app never tells games apart. Profiles follow Steam games instead
        // (NamedProfile.steamAppIds, applied by hub/SteamSwitch); app bindings stay in the data.
        sheet.body.addView(ListRow(ctx, ctx.getString(R.string.steam_bind),
            if (p.steamAppIds.isEmpty()) ctx.getString(R.string.steam_bind_none_yet) else ctx.resources.getQuantityString(R.plurals.steam_games, p.steamAppIds.size, p.steamAppIds.size)) {
            host.closeSheet()
            com.steamcontroller.android.ui.tv.SteamPicker.open(host, p.id) { render() }
        })
        sheet.body.addView(ListRow(ctx, ctx.getString(R.string.prof_rename), null) {
            host.closeSheet()
            ProfileOps.promptName(ctx, ctx.getString(R.string.prof_rename), p.name) { n -> Prefs.saveNamedProfile(ctx, p.copy(name = n)); host.refreshAll() }
        })
        sheet.body.addView(ListRow(ctx, ctx.getString(R.string.prof_duplicate), null) {
            host.closeSheet()
            Prefs.saveNamedProfile(ctx, p.copy(id = java.util.UUID.randomUUID().toString(), name = ctx.getString(R.string.prof_copy_name, p.name), boundPackages = emptyList(), steamAppIds = emptyList()))
            render()
        })
        sheet.body.addView(ListRow(ctx, ctx.getString(R.string.prof_delete), null, titleColor = C.danger) {
            host.closeSheet()
            MaterialAlertDialogBuilder(ctx)
                .setTitle(ctx.getString(R.string.prof_delete_title, p.name))
                .setMessage(R.string.prof_delete_body)
                .setPositiveButton(R.string.prof_delete) { _, _ -> Prefs.deleteNamedProfile(ctx, p.id); host.refreshAll() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        })
        host.openSheet(sheet)
        sheet.focus(first)
    }

    private fun newFromCurrent() {
        ProfileOps.promptName(ctx, ctx.getString(R.string.prof_new_title), ProfileOps.nextName(ctx)) { name ->
            val p = Prefs.captureCurrentAsProfile(ctx, name)
            Prefs.saveNamedProfile(ctx, p)
            Prefs.setActiveNamedProfileId(ctx, p.id)
            Pill.show(ctx, Pill.Kind.SAVED, ctx.getString(R.string.prof_created, name))
            host.refreshAll()
        }
    }
}
