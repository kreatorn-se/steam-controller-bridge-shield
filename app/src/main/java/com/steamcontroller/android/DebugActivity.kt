package com.steamcontroller.android

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.steamcontroller.android.parser.Buttons
import com.steamcontroller.android.parser.SteamControllerState
import com.steamcontroller.android.service.ControllerService
import com.steamcontroller.android.service.Diagnostics
import com.steamcontroller.android.ui.Screen
import com.steamcontroller.android.ui.live.LiveFrame
import com.steamcontroller.android.ui.live.LiveTicker
import com.steamcontroller.android.ui.pill.Pill
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.dp
import com.steamcontroller.android.ui.widget.Kit

/**
 * HID debug: every raw bit and axis of the last frame, the raw report in hex, the report rate,
 * and a diagnostics export for bug reports. For reference only; nothing here is interactive.
 */
class DebugActivity : AppCompatActivity() {
    private lateinit var ticker: LiveTicker
    private val chips = ArrayList<Pair<TextView, Int>>()
    private val chipOn = HashMap<TextView, Boolean>()
    private val axes = LinkedHashMap<String, TextView>()
    private lateinit var hex: TextView
    private lateinit var rate: TextView
    private var lastRaw: ByteArray? = null
    private var tick = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ticker = LiveTicker(this) { onFrame(it) }
        val left = Kit.column(this)
        val buttons = Kit.card(this, 18)
        buttons.addView(Kit.label(this, getString(R.string.debug_buttons)))
        val groups = listOf(
            listOf("A" to Buttons.A, "B" to Buttons.B, "X" to Buttons.X, "Y" to Buttons.Y, "LB" to Buttons.LB, "RB" to Buttons.RB, "LS" to Buttons.LS, "RS" to Buttons.RS),
            listOf("VIEW" to Buttons.VIEW, "STEAM" to Buttons.STEAM, "MENU" to Buttons.MENU, "QAM" to Buttons.QUICK_ACCESS, "↑" to Buttons.DPAD_UP, "↓" to Buttons.DPAD_DOWN, "←" to Buttons.DPAD_LEFT, "→" to Buttons.DPAD_RIGHT),
            listOf("L4" to Buttons.L4, "L5" to Buttons.L5, "R4" to Buttons.R4, "R5" to Buttons.R5, "LG" to Buttons.GRIP_LT, "RG" to Buttons.GRIP_RT, "LS T" to Buttons.LS_TOUCH, "RS T" to Buttons.RS_TOUCH),
            listOf("LP T" to Buttons.TP_LT, "LP C" to Buttons.TP_LT_CLICK, "RP T" to Buttons.TP_RT, "RP C" to Buttons.TP_RT_CLICK),
        )
        for (g in groups) {
            val row = Kit.row(this)
            for ((name, mask) in g) {
                val t = Kit.label(this, name, 11f, C.text2).apply {
                    gravity = Gravity.CENTER
                    minWidth = dp(44)
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    background = android.graphics.drawable.GradientDrawable().apply { setColor(C.raised); cornerRadius = dp(13f) }
                }
                chips += t to mask
                row.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
            }
            buttons.addView(row, Kit.lp(t = 8, ctx = this))
        }
        left.addView(buttons)
        val ax = Kit.card(this, 18)
        ax.addView(Kit.label(this, getString(R.string.debug_axes)))
        for (k in listOf("LT", "RT", "LS X", "LS Y", "RS X", "RS Y", "LP X", "LP Y", "RP X", "RP Y", "Q W", "Q X", "Q Y", "Q Z", "BUTTONS")) {
            val v = Kit.value(this, "—", 13f)
            axes[k] = v
            ax.addView(Kit.row(this).apply {
                minimumHeight = dp(24)
                addView(Kit.label(this@DebugActivity, k, 10.5f, C.text3), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(v)
            })
        }
        val right = Kit.column(this)
        right.addView(ax)
        val raw = Kit.card(this, 18)
        rate = Kit.label(this, "", 11f, C.accent)
        raw.addView(Kit.row(this).apply {
            addView(Kit.label(this@DebugActivity, getString(R.string.debug_raw_report)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(rate)
        })
        hex = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(C.textDefault)
            setLineSpacing(0f, 1.2f)
        }
        raw.addView(hex, Kit.lp(t = 10, ctx = this))
        left.addView(raw, Kit.lp(t = 4, ctx = this))

        val body = Kit.row(this, Gravity.TOP).apply {
            addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f))
            addView(Kit.space(this@DebugActivity, 16))
            addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(body) }
        val export = Kit.button(this, getString(R.string.menu_log_to_file), Kit.Btn.SECONDARY, R.drawable.ic_share) { shareDiagnostics() }
        setContentView(Screen.build(this, getString(R.string.debug_title), getString(R.string.debug_readonly_notice), listOf(export), scroll))
        export.post { export.requestFocus() }
    }

    override fun onResume() { super.onResume(); ticker.start() }
    override fun onPause() { ticker.stop(); super.onPause() }

    private fun onFrame(f: LiveFrame) {
        // Thirty updates a second is plenty to read; formatting every HID frame is not free.
        if (++tick % 2 != 0) return
        val s = f.state ?: return
        for ((t, mask) in chips) {
            val on = s.isButtonPressed(mask)
            if (chipOn[t] == on) continue
            chipOn[t] = on
            (t.background as android.graphics.drawable.GradientDrawable).setColor(if (on) C.accent else C.raised)
            t.setTextColor(if (on) C.onAccent else C.text2)
        }
        setAxes(s)
        rate.text = if (f.reportRate > 0) "${f.reportRate} Hz" else ""
        val r = ControllerService.rawReportFlow.value
        if (r != null && r !== lastRaw) {
            lastRaw = r
            hex.text = formatHex(r)
        }
    }

    private fun setAxes(s: SteamControllerState) {
        fun put(k: String, v: Int) { axes[k]?.text = "%6d".format(v) }
        put("LT", s.leftTrigger); put("RT", s.rightTrigger)
        put("LS X", s.leftJoyX.toInt()); put("LS Y", s.leftJoyY.toInt())
        put("RS X", s.rightJoyX.toInt()); put("RS Y", s.rightJoyY.toInt())
        put("LP X", s.leftPadX.toInt()); put("LP Y", s.leftPadY.toInt())
        put("RP X", s.rightPadX.toInt()); put("RP Y", s.rightPadY.toInt())
        put("Q W", s.quatW.toInt()); put("Q X", s.quatX.toInt()); put("Q Y", s.quatY.toInt()); put("Q Z", s.quatZ.toInt())
        axes["BUTTONS"]?.text = "0x%08X".format(s.buttons)
    }

    private fun shareDiagnostics() {
        try {
            val file = Diagnostics.export(this)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.menu_log_to_file)))
        } catch (t: Throwable) {
            Pill.show(this, Pill.Kind.FAILED, getString(R.string.debug_export_failed, t.message))
        }
    }

    private fun formatHex(buf: ByteArray): String {
        val sb = StringBuilder(buf.size * 3)
        buf.forEachIndexed { i, b ->
            sb.append("%02X".format(b.toInt() and 0xFF))
            sb.append(if (i % 16 == 15) '\n' else ' ')
        }
        return sb.toString().trimEnd()
    }
}
