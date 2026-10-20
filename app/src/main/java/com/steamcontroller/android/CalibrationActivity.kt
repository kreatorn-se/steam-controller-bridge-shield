package com.steamcontroller.android

import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.steamcontroller.android.input.GyroAim
import com.steamcontroller.android.input.StickCalibration
import com.steamcontroller.android.ui.Screen
import com.steamcontroller.android.ui.live.LiveFrame
import com.steamcontroller.android.ui.live.LiveTicker
import com.steamcontroller.android.ui.pill.Pill
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.dp
import com.steamcontroller.android.ui.theme.isWide
import com.steamcontroller.android.ui.widget.Kit

/**
 * Calibration: set the stick centres from where the sticks rest, and measure the gyro's resting
 * bias. Deadzones, curves and the rest of the tuning live on the Tuning page.
 */
class CalibrationActivity : AppCompatActivity() {
    private lateinit var ticker: LiveTicker
    private var leftCal = StickCalibration()
    private var rightCal = StickCalibration()
    private var lastLeftX = 0
    private var lastLeftY = 0
    private var lastRightX = 0
    private var lastRightY = 0
    private var gyro: GyroAim? = null
    private var haveState = false

    private lateinit var padLeft: StickPadView
    private lateinit var padRight: StickPadView
    private lateinit var rawLeft: TextView
    private lateinit var rawRight: TextView
    private lateinit var offLeft: TextView
    private lateinit var offRight: TextView
    private lateinit var gyroBtn: TextView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        leftCal = Prefs.getLeftCalibration(this)
        rightCal = Prefs.getRightCalibration(this)
        ticker = LiveTicker(this) { onFrame(it) }

        val wide = isWide()
        val sticks = Kit.row(this, Gravity.TOP)
        fun stickCard(title: Int, pad: StickPadView, raw: TextView, off: TextView): View = Kit.card(this, 18).apply {
            addView(Kit.label(this@CalibrationActivity, getString(title)))
            addView(pad, Kit.lp(h = dp(if (wide) 190 else 150), t = 10, ctx = this@CalibrationActivity).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(kv(R.string.calib_raw, raw), Kit.lp(t = 10, ctx = this@CalibrationActivity))
            addView(kv(R.string.calib_offset, off))
        }
        padLeft = StickPadView(this)
        padRight = StickPadView(this)
        rawLeft = Kit.value(this, "—", 14f)
        rawRight = Kit.value(this, "—", 14f)
        offLeft = Kit.value(this, "", 14f, C.text2)
        offRight = Kit.value(this, "", 14f, C.text2)
        sticks.addView(stickCard(R.string.test_left_stick, padLeft, rawLeft, offLeft), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sticks.addView(Kit.space(this, 4))
        sticks.addView(stickCard(R.string.test_right_stick, padRight, rawRight, offRight), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val side = Kit.column(this)
        val centre = Kit.card(this, 20)
        centre.addView(Kit.label(this, getString(R.string.calib_centre_label)))
        centre.addView(Kit.title(this, getString(R.string.calib_centre_title), 17f).apply { maxLines = 2 }, Kit.lp(t = 12, ctx = this))
        centre.addView(Kit.body(this, getString(R.string.calib_centre_body), 13.5f, C.text2), Kit.lp(t = 6, ctx = this))
        val centreBtns = Kit.row(this)
        val calibrate = Kit.button(this, getString(R.string.calib_calibrate_all), Kit.Btn.PRIMARY) { calibrateCentres() }
        centreBtns.addView(calibrate)
        centreBtns.addView(Kit.button(this, getString(R.string.menu_reset_defaults)) { resetCentres() }, Kit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, l = 4, ctx = this))
        centre.addView(centreBtns, Kit.lp(t = 14, ctx = this))
        side.addView(centre)

        val g = Kit.card(this, 20)
        g.addView(Kit.label(this, getString(R.string.calib_gyro_header)))
        g.addView(Kit.title(this, getString(R.string.calib_gyro_title), 17f).apply { maxLines = 2 }, Kit.lp(t = 12, ctx = this))
        g.addView(Kit.body(this, getString(R.string.calib_gyro_body), 13.5f, C.text2), Kit.lp(t = 6, ctx = this))
        gyroBtn = Kit.button(this, getString(R.string.calib_gyro_calibrate)) { startGyro() }
        g.addView(gyroBtn, Kit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, t = 14, ctx = this))
        side.addView(g, Kit.lp(t = 4, ctx = this))
        status = Kit.label(this, "", 11f, C.text3)
        side.addView(status, Kit.lp(t = 10, l = 4, ctx = this))

        val body: View = if (wide) {
            Kit.row(this, Gravity.TOP).apply {
                addView(sticks, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
                addView(Kit.space(this@CalibrationActivity, 16))
                addView(side, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        } else {
            Kit.column(this).apply { addView(sticks); addView(side, Kit.lp(t = 8, ctx = this@CalibrationActivity)) }
        }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; addView(body) }
        setContentView(Screen.build(this, getString(R.string.calib_title), getString(R.string.calib_subtitle), emptyList(), scroll))
        renderOffsets()
        calibrate.post { calibrate.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        ticker.start()
    }

    override fun onPause() {
        ticker.stop()
        super.onPause()
    }

    private fun kv(label: Int, value: TextView): View = Kit.row(this).apply {
        minimumHeight = dp(28)
        addView(Kit.label(this@CalibrationActivity, getString(label), 11f, C.text3), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(value)
    }

    private fun onFrame(f: LiveFrame) {
        val s = f.state
        haveState = s != null
        status.text = if (s == null) getString(R.string.tune_needs_controller) else ""
        if (s == null) return
        lastLeftX = s.leftJoyX.toInt(); lastLeftY = s.leftJoyY.toInt()
        lastRightX = s.rightJoyX.toInt(); lastRightY = s.rightJoyY.toInt()
        gyro?.calibrate(s.quatW, s.quatX, s.quatY, s.quatZ, SystemClock.uptimeMillis())?.let { (bx, by) ->
            Prefs.setGyroBias(this, bx, by)
            gyro = null
            gyroBtn.text = getString(R.string.calib_gyro_calibrate)
            Pill.show(this, Pill.Kind.SAVED, getString(R.string.calib_gyro_calibrated))
        }
        val (lx, ly) = leftCal.apply(lastLeftX, lastLeftY)
        val (rx, ry) = rightCal.apply(lastRightX, lastRightY)
        // The controller reports up as +Y; the preview draws +Y downward, so flip it here.
        // Display only: what games receive is unchanged (that is what "Invert Y" is for).
        padLeft.setPosition(lx / 32767f, -ly / 32767f)
        padRight.setPosition(rx / 32767f, -ry / 32767f)
        rawLeft.text = getString(R.string.calib_pair, lastLeftX, lastLeftY)
        rawRight.text = getString(R.string.calib_pair, lastRightX, lastRightY)
    }

    private fun renderOffsets() {
        offLeft.text = getString(R.string.calib_pair, leftCal.centerX, leftCal.centerY)
        offRight.text = getString(R.string.calib_pair, rightCal.centerX, rightCal.centerY)
        padLeft.deadzoneFraction = leftCal.deadzonePercent / 100f
        padRight.deadzoneFraction = rightCal.deadzonePercent / 100f
    }

    private fun calibrateCentres() {
        if (!haveState) { Pill.show(this, Pill.Kind.INFO, getString(R.string.tune_needs_controller)); return }
        // Re-read first so deadzone / invert edits made on the Tuning page are kept.
        leftCal = Prefs.getLeftCalibration(this).copy(centerX = lastLeftX, centerY = lastLeftY)
        rightCal = Prefs.getRightCalibration(this).copy(centerX = lastRightX, centerY = lastRightY)
        Prefs.setLeftCalibration(this, leftCal)
        Prefs.setRightCalibration(this, rightCal)
        renderOffsets()
        Pill.show(this, Pill.Kind.SAVED, getString(R.string.calib_done))
    }

    private fun resetCentres() {
        leftCal = Prefs.getLeftCalibration(this).copy(centerX = 0, centerY = 0)
        rightCal = Prefs.getRightCalibration(this).copy(centerX = 0, centerY = 0)
        Prefs.setLeftCalibration(this, leftCal)
        Prefs.setRightCalibration(this, rightCal)
        renderOffsets()
    }

    private fun startGyro() {
        if (!haveState) { Pill.show(this, Pill.Kind.INFO, getString(R.string.tune_needs_controller)); return }
        gyro = GyroAim()
        gyroBtn.text = getString(R.string.calib_gyro_calibrating)
    }
}
