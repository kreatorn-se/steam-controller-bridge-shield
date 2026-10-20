package com.steamcontroller.android.ui.pages

import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.R
import com.steamcontroller.android.input.SteamButton
import com.steamcontroller.android.input.XboxTarget
import com.steamcontroller.android.live.ControllerLive
import com.steamcontroller.android.ui.Host
import com.steamcontroller.android.ui.Page
import com.steamcontroller.android.ui.PageId
import com.steamcontroller.android.ui.Sheet
import com.steamcontroller.android.ui.diagram.ControllerDiagramView
import com.steamcontroller.android.ui.diagram.ControllerDiagramView.ChipStyle
import com.steamcontroller.android.ui.diagram.ControllerGeometry
import com.steamcontroller.android.ui.live.Ctl
import com.steamcontroller.android.ui.live.EdgeDetector
import com.steamcontroller.android.ui.live.LiveFrame
import com.steamcontroller.android.ui.mapping.Layer
import com.steamcontroller.android.ui.mapping.Mappings
import com.steamcontroller.android.ui.mapping.Names
import com.steamcontroller.android.ui.pill.Pill
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.dp
import com.steamcontroller.android.ui.theme.isWide
import com.steamcontroller.android.ui.theme.surface
import com.steamcontroller.android.ui.widget.ActionRow
import com.steamcontroller.android.ui.widget.HoldRingView
import com.steamcontroller.android.ui.widget.Kit
import com.steamcontroller.android.ui.widget.ListRow
import com.steamcontroller.android.ui.widget.Segmented

/**
 * CONTROLLER: the mapping home. The controller outline with every control's mapping on a callout;
 * pick a callout with the D-pad, tap it, or just press the button on the controller.
 */
class ControllerPage(host: Host) : Page(host) {
    private val wide = ctx.isWide()
    private var layer = Layer.GAMEPAD
    private val diagram = ControllerDiagramView(ctx).apply { callouts = wide }
    private val layerTabs = Segmented(ctx, Layer.values().map { it.title }, selectOnFocus = true) { setLayer(Layer.values()[it]) }
    private val faceTabs = Segmented(ctx, listOf(ctx.getString(R.string.ctl_front), ctx.getString(R.string.ctl_back)), selectOnFocus = true) {
        diagram.face = if (it == 0) ControllerDiagramView.Face.FRONT else ControllerDiagramView.Face.BACK
    }
    private val identifyBtn = Kit.button(ctx, ctx.getString(R.string.ctl_identify), Kit.Btn.PRIMARY, R.drawable.ic_target) { armIdentify() }
    private val resetBtn = Kit.button(ctx, ctx.getString(R.string.ctl_reset), Kit.Btn.SECONDARY) { confirmReset() }
    private val hint = Kit.label(ctx, "", 11f, C.text3)
    private val rows = LinkedHashMap<String, ActionRow>()
    private val identifyCard = buildIdentifyCard()
    private val ring = identifyCard.findViewWithTag<HoldRingView>("ring")
    private val countdown = identifyCard.findViewWithTag<TextView>("count")

    override val view: View = if (wide) buildWide() else buildNarrow()
    override val defaultFocus: View get() = diagram

    // live
    private val levels = FloatArray(Ctl.COUNT)
    private val edges = EdgeDetector()
    private var sheetOpenFor: String? = null

    // identify
    private enum class Capture { OFF, ARMED, WAIT_RELEASE }
    private var capture = Capture.OFF
    private var armedAt = 0L
    private var lastSuppress = 0L
    private var captured = -1

    init {
        diagram.listener = object : ControllerDiagramView.Listener {
            override fun onSelect(id: String) = openEditor(id)
            override fun onFocusMoved(id: String?) {
                faceTabs.select(if (diagram.face == ControllerDiagramView.Face.FRONT) 0 else 1, notify = false)
            }
        }
        layerTabs.select(0, notify = false)
        faceTabs.select(0, notify = false)
        render()
    }

    // ── layout ────────────────────────────────────────────────────────────────────────

    private fun buildWide(): View {
        val col = Kit.column(ctx)
        val top = Kit.row(ctx)
        top.addView(layerTabs)
        top.addView(Kit.grow(ctx))
        top.addView(faceTabs, Kit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, r = 8, ctx = ctx))
        top.addView(identifyBtn, Kit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, r = 4, ctx = ctx))
        top.addView(resetBtn, Kit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ctx = ctx))
        col.addView(top)
        val stage = FrameLayout(ctx)
        stage.addView(diagram, Kit.frame())
        stage.addView(identifyCard, Kit.frame(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        col.addView(stage, Kit.lp(h = 0, weight = 1f, t = 10, ctx = ctx))
        val foot = Kit.row(ctx)
        foot.addView(hint, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        foot.addView(Kit.label(ctx, ctx.getString(R.string.ctl_keys), 11f, C.text3))
        col.addView(foot, Kit.lp(t = 6, l = 3, r = 3, ctx = ctx))
        diagram.setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
        return col
    }

    private fun buildNarrow(): View {
        val scroll = ScrollView(ctx).apply { clipToPadding = false; isVerticalScrollBarEnabled = false }
        val col = Kit.column(ctx)
        scroll.addView(col)
        col.addView(layerTabs, Kit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ctx = ctx))
        val r = Kit.row(ctx)
        r.addView(faceTabs)
        r.addView(Kit.grow(ctx))
        r.addView(identifyBtn)
        col.addView(r, Kit.lp(t = 8, ctx = ctx))
        val stage = FrameLayout(ctx)
        stage.addView(diagram, Kit.frame(h = ctx.dp(260)))
        stage.addView(identifyCard, Kit.frame(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        col.addView(stage, Kit.lp(h = ctx.dp(260), t = 8, ctx = ctx))
        val all = (ControllerGeometry.front.controls + ControllerGeometry.back.controls).filter { it.kind != "led" }.map { it.id }.distinct()
        for (id in all) {
            val row = ActionRow(ctx, Names.sourceTitle(id), null, "") { openEditor(id) }
            rows[id] = row
            col.addView(row)
        }
        col.addView(resetBtn, Kit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, t = 12, ctx = ctx))
        return scroll
    }

    private fun buildIdentifyCard(): View {
        val card = Kit.card(ctx, 18, vertical = false)
        (card.background as? com.steamcontroller.android.ui.theme.SurfaceDrawable)?.apply { fill = C.sheet; stroke = C.alpha(C.accent, 0.55f) }
        card.gravity = Gravity.CENTER_VERTICAL
        val ring = HoldRingView(ctx).apply { tag = "ring" }
        card.addView(ring, LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(34)))
        val texts = Kit.column(ctx)
        texts.addView(Kit.title(ctx, ctx.getString(R.string.ctl_identify_title), 17f))
        texts.addView(Kit.label(ctx, "", 11f, C.text2).apply { tag = "count" }, Kit.lp(t = 6, ctx = ctx))
        card.addView(texts, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dp(16) })
        card.visibility = View.GONE
        card.elevation = 0f
        return card
    }

    // ── mapping state ─────────────────────────────────────────────────────────────────

    private fun setLayer(l: Layer) {
        layer = l
        if (l == Layer.ACTION && diagram.face == ControllerDiagramView.Face.FRONT) {
            diagram.face = ControllerDiagramView.Face.BACK
            faceTabs.select(1, notify = false)
        }
        render()
    }

    override fun refresh() = render()

    private fun render() {
        val mouseMode = Prefs.getProfile(ctx).isMouseMode
        layerTabs.setDot(0, if (!mouseMode) C.success else 0)
        layerTabs.setDot(1, if (mouseMode) C.success else 0)
        resetBtn.visibility = if (Mappings.editable(layer)) View.VISIBLE else View.GONE
        val padsAsMouse = Prefs.getTrackpadAsMouseInGamepad(ctx)
        for (b in SteamButton.values()) {
            val t = Mappings.get(ctx, layer, b)
            val style = when {
                layer == Layer.ACTION && t == XboxTarget.NONE -> ChipStyle.NONE
                t == XboxTarget.NONE -> ChipStyle.NONE
                t != Mappings.default(layer, b) -> ChipStyle.CUSTOM
                else -> ChipStyle.DEFAULT
            }
            val text = if (layer == Layer.ACTION && t == XboxTarget.NONE) "—" else Names.chip(t)
            setChip(b.name, text, style)
        }
        val (dpad, lpad, rpad) = when (layer) {
            Layer.GAMEPAD -> Triple("D-pad", if (padsAsMouse) "Scroll" else "Off", if (padsAsMouse) "Mouse" else "Off")
            Layer.DESKTOP -> Triple("Arrow keys", "Scroll", "Mouse")
            Layer.ACTION -> Triple("—", "—", "—")
        }
        setChip("DPAD", dpad, ChipStyle.INFO)
        setChip("LPAD", lpad, ChipStyle.INFO)
        setChip("RPAD", rpad, ChipStyle.INFO)
        diagram.commitChips()
        hint.text = when {
            layer == Layer.ACTION -> Mappings.layerKey(ctx)?.let { ctx.getString(R.string.ctl_hint_layer, Names.source(it.name)) }
                ?: ctx.getString(R.string.ctl_hint_layer_none)
            mouseMode != (layer == Layer.DESKTOP) -> ctx.getString(R.string.ctl_hint_inactive, layer.title)
            else -> ctx.getString(R.string.ctl_hint)
        }
    }

    private fun setChip(id: String, text: String, style: ChipStyle) {
        diagram.setChip(id, text, style)
        rows[id]?.valueView?.apply {
            this.text = text
            setTextColor(when (style) { ChipStyle.NONE -> C.text3; ChipStyle.CUSTOM -> C.accent; else -> C.text2 })
        }
    }

    // ── editor ────────────────────────────────────────────────────────────────────────

    private fun openEditor(id: String) {
        val button = SteamButton.values().firstOrNull { it.name == id }
        val sheet = Sheet(ctx)
        sheetOpenFor = id
        diagram.selectedId = id
        sheet.onClosed = {
            sheetOpenFor = null
            diagram.selectedId = null
            if (wide) diagram.requestFocus() else rows[id]?.requestFocus()
            edges.reset(null)
        }
        if (button == null) {
            infoSheet(sheet, id)
            host.openSheet(sheet)
            sheet.focus(null)
            return
        }
        val current = Mappings.get(ctx, layer, button)
        val def = Mappings.default(layer, button)
        val editable = Mappings.editable(layer)
        sheet.header(
            ctx.getString(R.string.ctl_sheet_eyebrow, layer.title),
            Names.sourceTitle(id),
            when {
                !editable -> Mappings.layerKey(ctx)?.let { ctx.getString(R.string.ctl_sheet_action_fixed, Names.source(it.name)) }
                    ?: ctx.getString(R.string.ctl_hint_layer_none)
                else -> ctx.getString(R.string.ctl_sheet_live)
            },
        )
        var focusRow: View? = null
        if (!editable) {
            sheet.body.addView(ListRow(ctx, Names.full(current), Names.hint(current), tag = ctx.getString(R.string.ctl_current), current = true) { host.closeSheet() })
        } else {
            if (current != def) {
                sheet.body.addView(ListRow(ctx, ctx.getString(R.string.ctl_restore_default, Names.full(def)), null, tag = null) { pick(button, def) })
            }
            for (g in Mappings.groups(layer)) {
                sheet.body.addView(Kit.section(ctx, g.title, if (sheet.body.childCount == 0) 4 else 18))
                for (t in g.targets) {
                    val isCur = t == current
                    val tag = when {
                        isCur -> ctx.getString(R.string.ctl_current)
                        t == def -> ctx.getString(R.string.ctl_default)
                        else -> null
                    }
                    val row = ListRow(ctx, Names.full(t), Names.hint(t), tag = tag, tagColor = if (isCur) C.accent else C.text3, current = isCur) { pick(button, t) }
                    if (isCur) focusRow = row
                    sheet.body.addView(row)
                }
            }
        }
        host.openSheet(sheet)
        sheet.focus(focusRow)
    }

    private fun infoSheet(sheet: Sheet, id: String) {
        sheet.header(ctx.getString(R.string.ctl_sheet_info_eyebrow), Names.sourceTitle(id), null)
        val text = when (id) {
            "DPAD" -> R.string.ctl_info_dpad
            "LPAD" -> R.string.ctl_info_lpad
            else -> R.string.ctl_info_rpad
        }
        sheet.body.addView(Kit.body(ctx, ctx.getString(text), 14f, C.text2), Kit.lp(b = 16, l = 11, r = 11, ctx = ctx))
        if (id != "DPAD") {
            sheet.body.addView(ListRow(ctx, ctx.getString(R.string.ctl_open_trackpad_tuning), null) {
                host.closeSheet()
                TuningPage.pendingTab = TuningPage.TAB_PADS
                host.go(PageId.TUNING, focusContent = true)
            })
        }
        sheet.body.addView(ListRow(ctx, ctx.getString(R.string.ctl_open_tester), null) {
            host.closeSheet()
            host.go(PageId.TEST)
        })
    }

    private fun pick(b: SteamButton, t: XboxTarget) {
        Mappings.set(ctx, layer, b, t)
        render()
        Pill.show(ctx, Pill.Kind.SAVED, "${Names.source(b.name)} → ${Names.chip(t)}")
        host.closeSheet()
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(ctx)
            .setTitle(ctx.getString(R.string.ctl_reset_title, layer.title))
            .setMessage(R.string.ctl_reset_body)
            .setPositiveButton(R.string.ctl_reset) { _, _ ->
                Mappings.reset(ctx, layer)
                render()
                Pill.show(ctx, Pill.Kind.SAVED, ctx.getString(R.string.ctl_reset_done))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── identify ("press any button") ─────────────────────────────────────────────────

    private fun armIdentify() {
        if (!host.frame.connected) {
            Pill.show(ctx, Pill.Kind.INFO, ctx.getString(R.string.ctl_identify_no_controller))
            return
        }
        capture = Capture.ARMED
        armedAt = SystemClock.uptimeMillis()
        lastSuppress = 0L
        Ctl.levels(host.frame.state, levels)
        edges.reset(levels)
        identifyCard.visibility = View.VISIBLE
        identifyCard.alpha = 0f
        identifyCard.animate().alpha(1f).setDuration(160L).start()
    }

    private fun endIdentify() {
        capture = Capture.OFF
        ControllerLive.releaseOutput()
        identifyCard.animate().alpha(0f).setDuration(120L).withEndAction { identifyCard.visibility = View.GONE }.start()
        edges.reset(levels)
    }

    override fun onFrame(f: LiveFrame) {
        val s = f.state
        Ctl.levels(s, levels)
        diagram.setLevels(levels)
        if (s != null) {
            diagram.setStick(true, s.leftJoyX / 32767f, s.leftJoyY / 32767f)
            diagram.setStick(false, s.rightJoyX / 32767f, s.rightJoyY / 32767f)
            diagram.setTouch(true, levels[Ctl.LPAD] > 0f, s.leftPadX / 32767f, s.leftPadY / 32767f)
            diagram.setTouch(false, levels[Ctl.RPAD] > 0f, s.rightPadX / 32767f, s.rightPadY / 32767f)
        }
        val now = SystemClock.uptimeMillis()
        when (capture) {
            Capture.ARMED -> {
                if (now - lastSuppress >= 1000L) { ControllerLive.suppressOutput(1500L); lastSuppress = now }
                val left = IDENTIFY_MS - (now - armedAt)
                ring.set(left / IDENTIFY_MS.toFloat())
                countdown.text = ctx.getString(R.string.ctl_identify_count, ((left + 999) / 1000).toInt().coerceAtLeast(0))
                val hit = edges.rising(levels, EdgeDetector.ANY)
                when {
                    hit >= 0 -> { captured = hit; capture = Capture.WAIT_RELEASE; armedAt = now }
                    left <= 0 -> endIdentify()
                }
            }
            Capture.WAIT_RELEASE -> {
                // Keep the pad quiet until the button is let go, so the press that identified it
                // does not click the editor that opens next.
                if (now - lastSuppress >= 1000L) { ControllerLive.suppressOutput(1500L); lastSuppress = now }
                if (levels.all { it < 0.3f } || now - armedAt > 3000L) {
                    endIdentify()
                    val id = Ctl.IDS[captured]
                    diagram.focusControl(id, pulse = true)
                    faceTabs.select(if (diagram.face == ControllerDiagramView.Face.FRONT) 0 else 1, notify = false)
                    rows[id]?.requestFocus()
                    openEditor(id)
                }
            }
            Capture.OFF -> {
                if (sheetOpenFor == null) {
                    val hit = edges.rising(levels, EdgeDetector.PASSIVE)
                    if (hit >= 0) {
                        val id = Ctl.IDS[hit]
                        diagram.focusControl(id, pulse = true)
                        faceTabs.select(if (diagram.face == ControllerDiagramView.Face.FRONT) 0 else 1, notify = false)
                        rows[id]?.requestFocus()
                    }
                } else {
                    edges.rising(levels, EdgeDetector.PASSIVE)
                }
            }
        }
    }

    override fun onShow() {
        Ctl.levels(host.frame.state, levels)
        edges.reset(levels)
    }

    override fun onHide() {
        if (capture != Capture.OFF) endIdentify()
    }

    override fun onBack(): Boolean {
        if (capture != Capture.OFF) { endIdentify(); return true }
        return false
    }

    override fun onKey(event: KeyEvent): Boolean {
        // While capturing, the pad is muted anyway; swallow the Shield remote's keys too.
        return capture != Capture.OFF && event.keyCode != KeyEvent.KEYCODE_BACK
    }

    private companion object {
        const val IDENTIFY_MS = 6000L
    }
}
