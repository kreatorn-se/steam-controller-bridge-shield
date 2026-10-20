package com.steamcontroller.android.ui.pages

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.steamcontroller.android.CalibrationActivity
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.R
import com.steamcontroller.android.input.GyroActivation
import com.steamcontroller.android.input.GyroAim
import com.steamcontroller.android.parser.Buttons
import com.steamcontroller.android.ui.Host
import com.steamcontroller.android.ui.Page
import com.steamcontroller.android.ui.live.LiveFrame
import com.steamcontroller.android.ui.pill.Pill
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.dp
import com.steamcontroller.android.ui.theme.isWide
import com.steamcontroller.android.ui.widget.ActionRow
import com.steamcontroller.android.ui.widget.ChoiceRow
import com.steamcontroller.android.ui.widget.Curve
import com.steamcontroller.android.ui.widget.CurveGraphView
import com.steamcontroller.android.ui.widget.GyroView
import com.steamcontroller.android.ui.widget.Kit
import com.steamcontroller.android.ui.widget.Segmented
import com.steamcontroller.android.ui.widget.SettingRow
import com.steamcontroller.android.ui.widget.StepperRow
import com.steamcontroller.android.ui.widget.ToggleRow
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * TUNING: response curves with a live preview. Left stick, right stick, trackpads and gyro, each
 * a list of TV steppers (D-pad ←/→ changes the focused value) next to a graph that the current
 * input rides along.
 */
class TuningPage(host: Host) : Page(host) {
    private val wide = ctx.isWide()
    private val tabs = Segmented(ctx, listOf(
        ctx.getString(R.string.tune_left), ctx.getString(R.string.tune_right),
        ctx.getString(R.string.tune_pads), ctx.getString(R.string.tune_gyro),
    ), selectOnFocus = true) { show(it) }
    private val rowsBox = Kit.column(ctx)
    private val rowsScroll = ScrollView(ctx).apply {
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        setPadding(0, ctx.dp(4), 0, ctx.dp(8))
        addView(rowsBox)
    }
    private val previewTitle = Kit.label(ctx)
    private val previewTag = Kit.label(ctx, ctx.getString(R.string.tune_live), 11f, C.accent)
    private val previewBox = FrameLayout(ctx)
    private val graph = CurveGraphView(ctx)
    private val gyroView = GyroView(ctx).apply { showReadout = false }
    private val gyroGraph = CurveGraphView(ctx).apply { xLabel = "Rate"; yLabel = "Aim"; bandLabel = null }
    private val gyroStack = Kit.column(ctx).apply {
        addView(gyroView, Kit.lp(h = 0, weight = 1.1f, ctx = ctx))
        addView(gyroGraph, Kit.lp(h = 0, weight = 1f, t = 8, ctx = ctx))
    }
    private val rows = ArrayList<SettingRow>()
    private var tab = 0
    private var presetRow: LinearLayout? = null
    private var calibrator: GyroAim? = null
    private var calibrateRow: ActionRow? = null
    private var lastPadX = 0f
    private var lastPadY = 0f
    private var padSpeed = 0f

    override val view: View = build()
    override val defaultFocus: View? get() = tabs.tabs.getOrNull(tab)

    private fun build(): View {
        val col = Kit.column(ctx)
        col.addView(tabs, Kit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ctx = ctx))
        val preview = Kit.card(ctx, 18)
        val ph = Kit.row(ctx)
        ph.addView(previewTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ph.addView(previewTag)
        preview.addView(ph)
        preview.addView(previewBox, Kit.lp(h = 0, weight = 1f, t = 10, ctx = ctx))
        if (wide) {
            val body = Kit.row(ctx, android.view.Gravity.TOP)
            body.addView(rowsScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            body.addView(Kit.space(ctx, 16))
            body.addView(preview, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            col.addView(body, Kit.lp(h = 0, weight = 1f, t = 12, ctx = ctx))
            return col
        }
        // Phone: preview on top, rows below, one scroll for both.
        rowsScroll.removeView(rowsBox)
        col.addView(preview, Kit.lp(h = ctx.dp(260), t = 12, ctx = ctx))
        col.addView(rowsBox, Kit.lp(t = 8, ctx = ctx))
        return ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(col) }
    }

    override fun onShow() {
        pendingTab?.let { tabs.select(it, notify = true); pendingTab = null }
        if (tabs.selected < 0) tabs.select(0, notify = true)
        gyroView.recenter()
    }

    override fun onHide() {
        calibrator = null
        calibrateRow?.setTitle(ctx.getString(R.string.calib_gyro_calibrate))
    }

    override fun refresh() {
        for (r in rows) r.refresh()
        updatePreview()
        syncPresets()
    }

    // ── tabs ──────────────────────────────────────────────────────────────────────────

    private fun show(i: Int) {
        tab = i
        liveCal = null
        rowsBox.removeAllViews()
        rows.clear()
        presetRow = null
        calibrateRow = null
        previewBox.removeAllViews()
        when (i) {
            0, 1 -> stickTab(left = i == 0)
            2 -> padTab()
            else -> gyroTab()
        }
        updatePreview()
        syncPresets()
    }

    private fun add(r: SettingRow): SettingRow { rows += r; rowsBox.addView(r); return r }

    private fun stickTab(left: Boolean) {
        previewTitle.text = ctx.getString(R.string.tune_response)
        previewBox.addView(graph, Kit.frame())
        graph.xLabel = "Input"; graph.yLabel = "Output"; graph.bandLabel = "Deadzone"
        fun cal() = if (left) Prefs.getLeftCalibration(ctx) else Prefs.getRightCalibration(ctx)
        fun setCal(c: com.steamcontroller.android.input.StickCalibration) = if (left) Prefs.setLeftCalibration(ctx, c) else Prefs.setRightCalibration(ctx, c)

        rowsBox.addView(Kit.section(ctx, ctx.getString(R.string.tune_presets), 2))
        val presets = Kit.row(ctx)
        for (p in Curve.PRESETS) {
            presets.addView(Kit.button(ctx, p.name, Kit.Btn.SECONDARY) {
                setCal(cal().copy(deadzonePercent = p.inner))
                Prefs.setStickOuter(ctx, left, p.outer)
                Prefs.setStickCurve(ctx, left, p.exponent)
                refresh()
                Pill.show(ctx, Pill.Kind.SAVED, "${p.name} curve")
            }, Kit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, r = 4, ctx = ctx))
        }
        presetRow = presets
        rowsBox.addView(presets, Kit.lp(b = 10, ctx = ctx))

        add(StepperRow(ctx, ctx.getString(R.string.tune_inner), ctx.getString(R.string.tune_inner_hint), 0f, 30f, 1f,
            { "${it.toInt()} %" }, { cal().deadzonePercent.toFloat() }, { setCal(cal().copy(deadzonePercent = it.toInt())) }) { onCurveChanged() })
        add(StepperRow(ctx, ctx.getString(R.string.tune_outer), ctx.getString(R.string.tune_outer_hint), 70f, 100f, 1f,
            { "${it.toInt()} %" }, { Prefs.getStickOuter(ctx, left).toFloat() }, { Prefs.setStickOuter(ctx, left, it.toInt()) }) { onCurveChanged() })
        add(StepperRow(ctx, ctx.getString(R.string.tune_curve), ctx.getString(R.string.tune_curve_hint), 0.5f, 3f, 0.05f,
            { "%.2f".format(it) }, { Prefs.getStickCurve(ctx, left) }, { Prefs.setStickCurve(ctx, left, it) }) { onCurveChanged() })
        add(ToggleRow(ctx, ctx.getString(R.string.tune_invert_y), ctx.getString(R.string.tune_invert_y_hint),
            { cal().invertY }, { setCal(cal().copy(invertY = it)) }))
        add(ActionRow(ctx, ctx.getString(R.string.tune_calibrate), ctx.getString(R.string.tune_calibrate_hint)) {
            ctx.startActivity(Intent(ctx, CalibrationActivity::class.java))
        })
    }

    private fun padTab() {
        previewTitle.text = ctx.getString(R.string.tune_pointer)
        previewBox.addView(graph, Kit.frame())
        graph.xLabel = "Finger speed"; graph.yLabel = "Pointer"; graph.bandLabel = null
        add(StepperRow(ctx, ctx.getString(R.string.tune_pad_speed), ctx.getString(R.string.tune_pad_speed_hint), 0.1f, 3f, 0.1f,
            { "%.1f×".format(it) }, { Prefs.getMouseSensitivity(ctx) }, { Prefs.setMouseSensitivity(ctx, it) }))
        add(StepperRow(ctx, ctx.getString(R.string.tune_pad_accel), ctx.getString(R.string.tune_pad_accel_hint), 0f, 2f, 0.1f,
            { if (it < 0.05f) "Off" else "%.1f".format(it) }, { Prefs.getPadAcceleration(ctx) }, { Prefs.setPadAcceleration(ctx, it) }) { updatePreview() })
        add(ToggleRow(ctx, ctx.getString(R.string.calib_trackpad_switch_label), ctx.getString(R.string.calib_trackpad_hint),
            { Prefs.getTrackpadAsMouseInGamepad(ctx) }, { Prefs.setTrackpadAsMouseInGamepad(ctx, it) }))
    }

    private fun gyroTab() {
        previewTitle.text = ctx.getString(R.string.tune_gyro_preview)
        previewBox.addView(gyroStack, Kit.frame())
        val acts = GyroActivation.ALL.map { it.displayName }
        add(ToggleRow(ctx, ctx.getString(R.string.calib_gyro_enable), ctx.getString(R.string.tune_gyro_enable_hint),
            { Prefs.getGyroEnabled(ctx) }, { Prefs.setGyroEnabled(ctx, it) }))
        add(ChoiceRow(ctx, ctx.getString(R.string.calib_gyro_activation), ctx.getString(R.string.tune_gyro_activation_hint), acts,
            { Prefs.getGyroActivation(ctx).ordinal }, { Prefs.setGyroActivation(ctx, GyroActivation.ALL[it]) }))
        add(StepperRow(ctx, ctx.getString(R.string.calib_gyro_sensitivity), null, 0.1f, 3f, 0.1f,
            { "%.1f×".format(it) }, { Prefs.getGyroSensitivity(ctx) }, { Prefs.setGyroSensitivity(ctx, it) }))
        add(StepperRow(ctx, ctx.getString(R.string.calib_gyro_sensitivity_y), null, 0.1f, 3f, 0.1f,
            { "%.1f×".format(it) }, { Prefs.getGyroSensitivityY(ctx) }, { Prefs.setGyroSensitivityY(ctx, it) }))
        add(StepperRow(ctx, ctx.getString(R.string.calib_gyro_deadzone), ctx.getString(R.string.tune_gyro_deadzone_hint), 0f, 0.1f, 0.002f,
            { "%.3f".format(it) }, { Prefs.getGyroDeadzone(ctx) }, { Prefs.setGyroDeadzone(ctx, it) }) { updatePreview() })
        add(StepperRow(ctx, ctx.getString(R.string.calib_gyro_smoothing), null, 0f, 0.9f, 0.05f,
            { "${(it * 100).toInt()} %" }, { Prefs.getGyroSmoothing(ctx) }, { Prefs.setGyroSmoothing(ctx, it) }))
        add(StepperRow(ctx, ctx.getString(R.string.calib_gyro_response), ctx.getString(R.string.tune_gyro_response_hint), 1f, 2f, 0.1f,
            { "%.1f".format(it) }, { Prefs.getGyroResponse(ctx) }, { Prefs.setGyroResponse(ctx, it) }) { updatePreview() })
        add(ToggleRow(ctx, ctx.getString(R.string.calib_gyro_invert_y), null, { Prefs.getGyroInvertY(ctx) }, { Prefs.setGyroInvertY(ctx, it) }))
        calibrateRow = add(ActionRow(ctx, ctx.getString(R.string.calib_gyro_calibrate), ctx.getString(R.string.tune_gyro_bias_hint)) { row ->
            if (host.frame.state == null) {
                Pill.show(ctx, Pill.Kind.INFO, ctx.getString(R.string.tune_needs_controller))
            } else {
                calibrator = GyroAim()
                row.setTitle(ctx.getString(R.string.calib_gyro_calibrating))
            }
        }) as ActionRow
    }

    private fun onCurveChanged() { updatePreview(); syncPresets() }

    private fun updatePreview() {
        when (tab) {
            0, 1 -> {
                val left = tab == 0
                val inner = (if (left) Prefs.getLeftCalibration(ctx) else Prefs.getRightCalibration(ctx)).deadzonePercent / 100f
                val outer = Prefs.getStickOuter(ctx, left) / 100f
                val exp = Prefs.getStickCurve(ctx, left)
                graph.setCurve(inner, outer) { Curve.stick(it, inner, outer, exp) }
            }
            2 -> {
                val a = Prefs.getPadAcceleration(ctx)
                graph.setCurve(0f, 1f) { Curve.padAccel(it, a) }
            }
            else -> {
                // Plotted over 0..0.5 rad/s, where the deadzone and the curve's shape are visible.
                val dz = Prefs.getGyroDeadzone(ctx) / 0.5f
                val exp = Prefs.getGyroResponse(ctx)
                gyroGraph.setCurve(dz, 1f) { Curve.gyro(it, dz, exp) }
            }
        }
    }

    /** Lights the preset that matches the current values, if any. */
    private fun syncPresets() {
        val row = presetRow ?: return
        val left = tab == 0
        val inner = (if (left) Prefs.getLeftCalibration(ctx) else Prefs.getRightCalibration(ctx)).deadzonePercent
        val outer = Prefs.getStickOuter(ctx, left)
        val exp = Prefs.getStickCurve(ctx, left)
        Curve.PRESETS.forEachIndexed { i, p ->
            val on = p.inner == inner && p.outer == outer && abs(p.exponent - exp) < 0.01f
            val b = row.getChildAt(i) as? android.widget.TextView ?: return@forEachIndexed
            b.isActivated = on
            b.setTextColor(if (on) C.accent else C.text)
        }
    }

    override fun onFrame(f: LiveFrame) {
        val s = f.state ?: run { graph.setLive(-1f); gyroGraph.setLive(-1f); return }
        when (tab) {
            0, 1 -> {
                val left = tab == 0
                val now = SystemClock.uptimeMillis()
                if (now - calAt > 250L || liveCal == null) {
                    calAt = now
                    liveCal = if (left) Prefs.getLeftCalibration(ctx) else Prefs.getRightCalibration(ctx)
                    liveOuter = Prefs.getStickOuter(ctx, left)
                    liveCurve = Prefs.getStickCurve(ctx, left)
                }
                val c = liveCal ?: return
                val rawX = (if (left) s.leftJoyX else s.rightJoyX).toInt()
                val rawY = (if (left) s.leftJoyY else s.rightJoyY).toInt()
                val x = (rawX - c.centerX) / 32767f
                val y = (rawY - c.centerY) / 32767f
                // The dot's height is the runtime's exact transform, not a re-derivation.
                c.applyShaped(rawX, rawY, liveOuter, liveCurve, shaped)
                graph.setLive(min(1f, hypot(x, y)), min(1f, hypot(shaped[0].toFloat(), shaped[1].toFloat()) / 32767f))
            }
            2 -> {
                val touching = s.isButtonPressed(Buttons.TP_RT)
                val x = s.rightPadX / 32767f
                val y = s.rightPadY / 32767f
                val v = if (touching) min(1f, hypot(x - lastPadX, y - lastPadY) / 0.06f) else 0f
                padSpeed += (v - padSpeed) * 0.3f
                lastPadX = x; lastPadY = y
                graph.setLive(if (touching) padSpeed else -1f)
            }
            else -> {
                gyroView.setQuaternion(s.quatW, s.quatX, s.quatY, s.quatZ)
                val cal = calibrator
                if (cal != null) {
                    cal.calibrate(s.quatW, s.quatX, s.quatY, s.quatZ, SystemClock.uptimeMillis())?.let { (bx, by) ->
                        Prefs.setGyroBias(ctx, bx, by)
                        calibrator = null
                        calibrateRow?.setTitle(ctx.getString(R.string.calib_gyro_calibrate))
                        Pill.show(ctx, Pill.Kind.SAVED, ctx.getString(R.string.calib_gyro_calibrated))
                    }
                }
                gyroRate(s.quatW, s.quatX, s.quatY, s.quatZ)
            }
        }
    }

    // A rough angular speed for the live dot on the gyro curve.
    private var qw = 1f; private var qx = 0f; private var qy = 0f; private var qz = 0f
    private var rateAt = 0L
    private var rate = 0f
    private fun gyroRate(w: Short, x: Short, y: Short, z: Short) {
        val now = SystemClock.uptimeMillis()
        val nw = w / 32767f; val nx = x / 32767f; val ny = y / 32767f; val nz = z / 32767f
        val dt = (now - rateAt) / 1000f
        if (rateAt != 0L && dt in 0.004f..0.2f) {
            val dot = abs(qw * nw + qx * nx + qy * ny + qz * nz).coerceAtMost(1f)
            val ang = 2f * kotlin.math.acos(dot) / dt
            rate += (ang - rate) * 0.25f
            gyroGraph.setLive((rate / 0.5f).coerceIn(0f, 1f))
        }
        qw = nw; qx = nx; qy = ny; qz = nz; rateAt = now
    }

    private var calAt = 0L
    private var liveCal: com.steamcontroller.android.input.StickCalibration? = null
    private var liveOuter = 100
    private var liveCurve = 1f
    private val shaped = IntArray(2)

    companion object {
        const val TAB_PADS = 2
        /** Set before navigating here to open a specific tab (e.g. from a trackpad callout). */
        var pendingTab: Int? = null
    }
}
