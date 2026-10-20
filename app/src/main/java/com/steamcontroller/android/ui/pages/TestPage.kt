package com.steamcontroller.android.ui.pages

import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.R
import com.steamcontroller.android.input.StickCalibration
import com.steamcontroller.android.live.ControllerLive
import com.steamcontroller.android.parser.Buttons
import com.steamcontroller.android.ui.Host
import com.steamcontroller.android.ui.Page
import com.steamcontroller.android.ui.PageId
import com.steamcontroller.android.ui.diagram.ControllerDiagramView
import com.steamcontroller.android.ui.live.Ctl
import com.steamcontroller.android.ui.live.LiveFrame
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.dp
import com.steamcontroller.android.ui.theme.isWide
import com.steamcontroller.android.ui.widget.GyroView
import com.steamcontroller.android.ui.widget.HoldRingView
import com.steamcontroller.android.ui.widget.Kit
import com.steamcontroller.android.ui.widget.StickView
import com.steamcontroller.android.ui.widget.TriggerBarView

/**
 * TEST: a full live input tester. While it is on screen the virtual pad is muted (so pressing B
 * does not leave and the sticks do not scroll the UI); the page reads the raw controller instead,
 * and holding B for a second leaves.
 */
class TestPage(host: Host) : Page(host) {
    private val diagram = ControllerDiagramView(ctx).apply { callouts = false; interactive = false }
    private val left = StickView(ctx)
    private val right = StickView(ctx)
    private val lt = TriggerBarView(ctx, "LT")
    private val rt = TriggerBarView(ctx, "RT")
    private val gyro = GyroView(ctx).apply { recenterHint = ctx.getString(R.string.test_recenter) }
    private val ring = HoldRingView(ctx)
    private val stats = Kit.label(ctx, "", 11f, C.text2)
    private val empty = Kit.body(ctx, ctx.getString(R.string.test_empty), 14f, C.text2).apply { gravity = Gravity.CENTER }
    private val levels = FloatArray(Ctl.COUNT)

    override val view: View = build()

    private var bHeldSince = 0L
    private var leaving = false
    private var yWas = false
    private var lastSuppress = 0L
    private var lastStats = ""
    private var statsAt = 0L
    private var calAt = 0L
    private val shaped = IntArray(2)
    private var lCal: StickCalibration? = null
    private var rCal: StickCalibration? = null
    private var lOuter = 100
    private var rOuter = 100
    private var lCurve = 1f
    private var rCurve = 1f

    private fun build(): View {
        val wide = ctx.isWide()
        val diagramCard = Kit.card(ctx, 16)
        val head = Kit.row(ctx)
        head.addView(Kit.label(ctx, ctx.getString(R.string.test_live)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(stats)
        diagramCard.addView(head)
        val stage = FrameLayout(ctx)
        stage.addView(diagram, Kit.frame())
        stage.addView(empty, Kit.frame(gravity = Gravity.CENTER))
        diagramCard.addView(stage, Kit.lp(h = if (wide) 0 else ctx.dp(240), weight = if (wide) 1f else 0f, t = 8, ctx = ctx))
        val bars = Kit.row(ctx)
        bars.addView(lt, LinearLayout.LayoutParams(0, ctx.dp(24), 1f))
        bars.addView(Kit.space(ctx, 20))
        bars.addView(rt, LinearLayout.LayoutParams(0, ctx.dp(24), 1f))
        diagramCard.addView(bars, Kit.lp(t = 12, ctx = ctx))
        val leave = Kit.row(ctx)
        leave.addView(Kit.label(ctx, ctx.getString(R.string.test_hold_b), 11f, C.text3), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        leave.addView(ring, LinearLayout.LayoutParams(ctx.dp(22), ctx.dp(22)))
        diagramCard.addView(leave, Kit.lp(t = 14, ctx = ctx))

        fun stickCard(title: Int, v: StickView): View = Kit.card(ctx, 16).apply {
            addView(Kit.label(ctx, ctx.getString(title)))
            addView(v, Kit.lp(h = 0, weight = 1f, t = 10, ctx = ctx))
        }
        val gyroCard = Kit.card(ctx, 16).apply {
            addView(Kit.label(ctx, ctx.getString(R.string.test_gyro)))
            addView(gyro, Kit.lp(h = 0, weight = 1f, t = 6, ctx = ctx))
        }

        if (wide) {
            val row = Kit.row(ctx, Gravity.TOP)
            row.addView(diagramCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.05f))
            val rightCol = Kit.column(ctx)
            val sticks = Kit.row(ctx)
            sticks.addView(stickCard(R.string.test_left_stick, left), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            sticks.addView(Kit.space(ctx, 4))
            sticks.addView(stickCard(R.string.test_right_stick, right), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            rightCol.addView(sticks, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.2f))
            rightCol.addView(gyroCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = ctx.dp(4) })
            row.addView(Kit.space(ctx, 4))
            row.addView(rightCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            return row
        }
        val col = Kit.column(ctx)
        col.addView(diagramCard)
        val sticks = Kit.row(ctx)
        sticks.addView(stickCard(R.string.test_left_stick, left), LinearLayout.LayoutParams(0, ctx.dp(220), 1f))
        sticks.addView(stickCard(R.string.test_right_stick, right), LinearLayout.LayoutParams(0, ctx.dp(220), 1f))
        col.addView(sticks, Kit.lp(t = 4, ctx = ctx))
        col.addView(gyroCard, Kit.lp(h = ctx.dp(200), t = 4, ctx = ctx))
        return ScrollView(ctx).apply { addView(col); isVerticalScrollBarEnabled = false }
    }

    override fun refresh() = reloadShape()

    private fun reloadShape() {
        val l = Prefs.getLeftCalibration(ctx)
        val r = Prefs.getRightCalibration(ctx)
        lCal = l; rCal = r
        lOuter = Prefs.getStickOuter(ctx, true); rOuter = Prefs.getStickOuter(ctx, false)
        lCurve = Prefs.getStickCurve(ctx, true); rCurve = Prefs.getStickCurve(ctx, false)
        left.setShape(l.deadzonePercent / 100f, lOuter / 100f, lCurve)
        right.setShape(r.deadzonePercent / 100f, rOuter / 100f, rCurve)
    }

    override fun onShow() {
        bHeldSince = 0L
        leaving = false
        lastSuppress = 0L
        gyro.recenter()
    }

    override fun onHide() {
        ControllerLive.releaseOutput()
        ring.set(0f)
    }

    override fun onFrame(f: LiveFrame) {
        val now = SystemClock.uptimeMillis()
        // Re-arm well before the 1.5 s lapse; the runtime drops it by itself if we stop.
        if (now - lastSuppress >= 1000L) { ControllerLive.suppressOutput(1500L); lastSuppress = now }
        val s = f.state
        empty.visibility = if (s == null || !f.connected) View.VISIBLE else View.GONE
        diagram.alpha = if (s == null || !f.connected) 0.35f else 1f
        Ctl.levels(s, levels)
        diagram.setLevels(levels)
        if (s != null) {
            // Calibrated centre, display-only flip handled by the views (up is positive).
            if (now - calAt > 1000L) {
                // Calibration and curves can change under us (Calibrate, a profile switch).
                calAt = now
                reloadShape()
            }
            val lCal = lCal ?: return
            val rCal = rCal ?: return
            val lx = (s.leftJoyX - lCal.centerX) / 32767f
            val ly = (s.leftJoyY - lCal.centerY) / 32767f
            val rx = (s.rightJoyX - rCal.centerX) / 32767f
            val ry = (s.rightJoyY - rCal.centerY) / 32767f
            // The processed dot is the runtime's exact transform.
            lCal.applyShaped(s.leftJoyX.toInt(), s.leftJoyY.toInt(), lOuter, lCurve, shaped)
            left.set(lx, ly, shaped[0] / 32767f, shaped[1] / 32767f)
            rCal.applyShaped(s.rightJoyX.toInt(), s.rightJoyY.toInt(), rOuter, rCurve, shaped)
            right.set(rx, ry, shaped[0] / 32767f, shaped[1] / 32767f)
            diagram.setStick(true, lx, ly)
            diagram.setStick(false, rx, ry)
            diagram.setTouch(true, levels[Ctl.LPAD] > 0f, s.leftPadX / 32767f, s.leftPadY / 32767f)
            diagram.setTouch(false, levels[Ctl.RPAD] > 0f, s.rightPadX / 32767f, s.rightPadY / 32767f)
            lt.set(s.leftTrigger)
            rt.set(s.rightTrigger)
            gyro.setQuaternion(s.quatW, s.quatX, s.quatY, s.quatZ)
            val y = s.isButtonPressed(Buttons.Y)
            if (y && !yWas) gyro.recenter()
            yWas = y
            if (s.isButtonPressed(Buttons.B)) {
                if (bHeldSince == 0L) bHeldSince = now
                val p = (now - bHeldSince) / HOLD_MS.toFloat()
                ring.set(p)
                if (p >= 1f) leaving = true
            } else {
                // Leave on release, while still muted, so the B that left does not also act as Back.
                if (leaving) { leaving = false; bHeldSince = 0L; ring.set(0f); host.go(PageId.CONTROLLER); return }
                if (bHeldSince != 0L) { bHeldSince = 0L; ring.set(0f) }
            }
        }
        if (now - statsAt > 500L) {
            statsAt = now
            val text = buildString {
                if (f.reportRate > 0) append(f.reportRate).append(" Hz · ")
                append(f.linkLabel())
                f.battery?.let { append(" · ").append(it).append('%') }
            }
            if (text != lastStats) { lastStats = text; stats.text = text }
        }
    }

    private companion object {
        const val HOLD_MS = 1000L
    }
}
