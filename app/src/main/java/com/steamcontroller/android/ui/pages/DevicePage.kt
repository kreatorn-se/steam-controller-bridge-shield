package com.steamcontroller.android.ui.pages

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.steamcontroller.android.BuildConfig
import com.steamcontroller.android.DebugActivity
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.R
import com.steamcontroller.android.Transport
import com.steamcontroller.android.service.ControllerService
import com.steamcontroller.android.uinput.GamepadProfile
import com.steamcontroller.android.uinput.UInputNative
import com.steamcontroller.android.ui.Host
import com.steamcontroller.android.ui.Page
import com.steamcontroller.android.ui.ServiceControl
import com.steamcontroller.android.ui.Sheet
import com.steamcontroller.android.ui.live.LiveFrame
import com.steamcontroller.android.ui.pill.Pill
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.dp
import com.steamcontroller.android.ui.theme.isWide
import com.steamcontroller.android.ui.widget.ActionRow
import com.steamcontroller.android.ui.widget.ChoiceRow
import com.steamcontroller.android.ui.widget.DotView
import com.steamcontroller.android.ui.widget.Kit
import com.steamcontroller.android.ui.widget.SettingRow
import com.steamcontroller.android.ui.widget.StepperRow
import com.steamcontroller.android.ui.widget.ToggleRow

/**
 * DEVICE: the controller (battery, link, firmware, report rate, reconnect), the bridge's health
 * (service, virtual pad, Shizuku) with a fix for each, connection and output mode, behaviour,
 * backup and diagnostics.
 */
class DevicePage(host: Host) : Page(host) {
    private val wide = ctx.isWide()
    private val battery = Kit.value(ctx, "—", 34f)
    private val batteryNote = Kit.label(ctx, "", 11f, C.text2)
    private val linkValue = Kit.value(ctx, "—", 14f)
    private val fwValue = Kit.value(ctx, "—", 14f)
    private val rateValue = Kit.value(ctx, "—", 14f)
    private val controllerDot = DotView(ctx, 8f)
    private val controllerState = Kit.label(ctx, "", 11f, C.text2)
    private val health = ArrayList<HealthRow>()
    private val rows = ArrayList<SettingRow>()
    private var btRow: ChoiceRow? = null
    private val btBox = Kit.column(ctx)
    private var bt: List<ServiceControl.BtDevice> = emptyList()
    private var seen = -1
    private var slowAt = 0L
    private lateinit var reconnectBtn: TextView

    // Declared before view: build() sets it.
    private var updateRow: ActionRow? = null
    override val view: View = build()
    override val defaultFocus: View get() = reconnectBtn

    private inner class HealthRow(title: String) : LinearLayout(ctx) {
        val dot = DotView(ctx, 8f)
        val value = Kit.body(ctx, "", 13f, C.text2)
        val fix = Kit.button(ctx, "", Kit.Btn.SECONDARY)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(52)
            addView(dot)
            val t = Kit.column(ctx)
            t.addView(Kit.body(ctx, title, 15f, C.text, medium = true))
            t.addView(value, Kit.lp(t = 3, ctx = ctx))
            addView(t, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ctx.dp(10) })
            addView(fix)
        }

        fun set(color: Int, text: String, action: String?, onFix: (() -> Unit)?) {
            dot.color = color
            dot.halo = color == C.success
            value.text = text
            fix.visibility = if (action == null) View.GONE else View.VISIBLE
            fix.text = action ?: ""
            fix.setOnClickListener { onFix?.invoke() }
        }
    }

    private fun build(): View {
        val left = Kit.column(ctx)
        val right = Kit.column(ctx)

        // Controller card
        val cc = Kit.card(ctx, 20)
        val head = Kit.row(ctx)
        head.addView(Kit.label(ctx, ctx.getString(R.string.dev_controller)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(controllerDot)
        head.addView(controllerState, Kit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, l = 4, ctx = ctx))
        cc.addView(head)
        val big = Kit.row(ctx, Gravity.BOTTOM)
        big.addView(battery)
        big.addView(batteryNote, Kit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, l = 10, b = 6, ctx = ctx))
        cc.addView(big, Kit.lp(t = 12, ctx = ctx))
        cc.addView(kv(R.string.dev_link, linkValue), Kit.lp(t = 12, ctx = ctx))
        cc.addView(kv(R.string.dev_firmware, fwValue))
        cc.addView(kv(R.string.dev_rate, rateValue))
        val btns = Kit.row(ctx)
        reconnectBtn = Kit.button(ctx, ctx.getString(R.string.dev_reconnect), Kit.Btn.PRIMARY, R.drawable.ic_refresh) { host.service.reconnect() }
        btns.addView(reconnectBtn)
        btns.addView(Kit.button(ctx, ctx.getString(R.string.dev_test_haptics)) { host.service.testRumble() }, Kit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, l = 4, ctx = ctx))
        cc.addView(btns, Kit.lp(t = 14, ctx = ctx))
        left.addView(cc)

        // Health card
        val hc = Kit.card(ctx, 20)
        hc.addView(Kit.label(ctx, ctx.getString(R.string.dev_health)))
        for (t in listOf(R.string.dev_service, R.string.dev_pad, R.string.dev_shizuku)) {
            val r = HealthRow(ctx.getString(t))
            health += r
            hc.addView(r, Kit.lp(t = 8, ctx = ctx))
        }
        left.addView(hc, Kit.lp(t = 4, ctx = ctx))

        // Settings column
        right.addView(Kit.section(ctx, ctx.getString(R.string.dev_connection), 2))
        add(right, ChoiceRow(ctx, ctx.getString(R.string.dev_transport), ctx.getString(R.string.dev_transport_hint),
            Transport.values().map { it.displayName }, { Prefs.getTransport(ctx).ordinal }) { i ->
            val t = Transport.values()[i]
            Prefs.setTransport(ctx, t)
            renderBt()
            if (host.frame.serviceRunning) Pill.show(ctx, Pill.Kind.INFO, ctx.getString(R.string.dev_reconnect_to_apply))
        })
        right.addView(btBox)
        add(right, ActionRow(ctx, ctx.getString(R.string.help_dialog_title), ctx.getString(R.string.dev_help_hint)) { showHelp() })

        right.addView(Kit.section(ctx, ctx.getString(R.string.dev_output)))
        add(right, ChoiceRow(ctx, ctx.getString(R.string.dev_emulate), ctx.getString(R.string.dev_emulate_hint),
            listOf(ctx.getString(R.string.dev_emulate_pad), ctx.getString(R.string.dev_emulate_desktop)),
            { if (Prefs.getProfile(ctx).isMouseMode) 1 else 0 }) { i ->
            val p = if (i == 1) GamepadProfile.MOUSE else Prefs.getLastGamepadProfile(ctx)
            Prefs.setProfile(ctx, p)
            if (host.frame.serviceRunning) Pill.show(ctx, Pill.Kind.INFO, ctx.getString(R.string.dev_reconnect_to_apply))
            host.refreshAll()
        })

        right.addView(Kit.section(ctx, ctx.getString(R.string.dev_behaviour)))
        add(right, ToggleRow(ctx, ctx.getString(R.string.calib_start_on_boot), null, { Prefs.getStartOnBoot(ctx) }, { Prefs.setStartOnBoot(ctx, it) }))
        add(right, ToggleRow(ctx, ctx.getString(R.string.calib_start_on_connect), null, { Prefs.getStartOnControllerConnect(ctx) }, { Prefs.setStartOnControllerConnect(ctx, it) }))
        add(right, StepperRow(ctx, ctx.getString(R.string.dev_rumble), ctx.getString(R.string.dev_rumble_hint), 0f, 100f, 5f,
            { if (it < 1f) "Off" else "${it.toInt()} %" }, { Prefs.getRumbleIntensity(ctx).toFloat() }, { Prefs.setRumbleIntensity(ctx, it.toInt()) }))

        right.addView(Kit.section(ctx, ctx.getString(R.string.dev_data)))
        add(right, ActionRow(ctx, ctx.getString(R.string.menu_export_backup), ctx.getString(R.string.dev_backup_hint)) { host.exportBackup() })
        add(right, ActionRow(ctx, ctx.getString(R.string.menu_import_backup), ctx.getString(R.string.dev_restore_hint)) { host.importBackup() })
        add(right, ActionRow(ctx, ctx.getString(R.string.dev_debug), ctx.getString(R.string.dev_debug_hint)) {
            ctx.startActivity(Intent(ctx, DebugActivity::class.java))
        })
        add(right, ToggleRow(ctx, ctx.getString(R.string.dev_diag_upload), ctx.getString(R.string.dev_diag_upload_hint),
            { com.steamcontroller.android.hub.Diag.enabled(ctx) }, { com.steamcontroller.android.hub.Diag.setEnabled(ctx, it) }))
        // Self-update: state from update/Updater; one press downloads and installs.
        updateRow = ActionRow(ctx, ctx.getString(R.string.dev_update), ctx.getString(R.string.dev_update_hint), "") {
            val u = com.steamcontroller.android.update.Updater
            when (val st = u.state) {
                is com.steamcontroller.android.update.Updater.State.Available -> u.install(ctx, st.rel)
                is com.steamcontroller.android.update.Updater.State.Failed -> st.rel?.let { u.install(ctx, it) } ?: u.checkIfDue(ctx, force = true)
                is com.steamcontroller.android.update.Updater.State.Idle -> { u.checkIfDue(ctx, force = true); Pill.show(ctx, Pill.Kind.INFO, ctx.getString(R.string.update_checking)) }
                else -> {}
            }
        }
        add(right, updateRow!!)
        add(right, ActionRow(ctx, ctx.getString(R.string.dev_about), "Gamehub ${BuildConfig.VERSION_NAME} · controller bridge", ctx.getString(R.string.dev_source)) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB)))
            } catch (_: Throwable) {
                Pill.show(ctx, Pill.Kind.INFO, ctx.getString(R.string.dev_no_browser))
            }
        })

        val scrollContent: View
        if (wide) {
            val row = Kit.row(ctx, Gravity.TOP)
            row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f))
            row.addView(Kit.space(ctx, 16))
            row.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            scrollContent = row
        } else {
            val col = Kit.column(ctx)
            col.addView(left)
            col.addView(right, Kit.lp(t = 8, ctx = ctx))
            scrollContent = col
        }
        return ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, 0, 0, ctx.dp(16))
            addView(scrollContent)
        }
    }

    private fun kv(key: Int, value: TextView): View = Kit.row(ctx).apply {
        minimumHeight = ctx.dp(30)
        addView(Kit.label(ctx, ctx.getString(key), 11f, C.text3), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(value)
    }

    private fun add(parent: LinearLayout, r: SettingRow) { rows += r; parent.addView(r) }

    private fun renderUpdate(st: com.steamcontroller.android.update.Updater.State) {
        val row = updateRow ?: return
        row.valueView.text = when (st) {
            is com.steamcontroller.android.update.Updater.State.Idle -> ctx.getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME)
            is com.steamcontroller.android.update.Updater.State.Available -> ctx.getString(R.string.update_available, st.rel.version)
            is com.steamcontroller.android.update.Updater.State.Downloading -> ctx.getString(R.string.update_downloading, (st.progress * 100).toInt())
            is com.steamcontroller.android.update.Updater.State.Installing -> ctx.getString(R.string.update_installing)
            is com.steamcontroller.android.update.Updater.State.Failed -> (ctx as? com.steamcontroller.android.MainActivity)?.updateFailure(st.message) ?: ctx.getString(R.string.update_retry)
        }
        row.valueView.setTextColor(if (st is com.steamcontroller.android.update.Updater.State.Idle) C.text2 else C.accent)
    }

    override fun refresh() {
        for (r in rows) r.refresh()
        renderBt()
        seen = -1
    }

    private var shown = false

    // Observed only while shown: the Updater is a process-wide singleton and would otherwise keep
    // this page (and its activity) alive after the activity is gone.
    private val updateListener: (com.steamcontroller.android.update.Updater.State) -> Unit = { st -> renderUpdate(st) }

    override fun onShow() {
        shown = true; slowAt = 0L; renderBt()
        com.steamcontroller.android.update.Updater.observe(updateListener)
        com.steamcontroller.android.update.Updater.checkIfDue(ctx, maxAgeMs = com.steamcontroller.android.update.Updater.ON_OPEN_MS)
    }
    override fun onHide() {
        shown = false
        com.steamcontroller.android.update.Updater.remove(updateListener)
    }

    /** Bluetooth: the paired controller to use (after the permission prompt on Android 12+). */
    private fun renderBt(explicit: Boolean = false) {
        btBox.removeAllViews()
        btRow = null
        if (Prefs.getTransport(ctx) != Transport.BLUETOOTH) return
        // Only ask for the Bluetooth permission while this page is on screen.
        if (!shown) return
        host.service.withBluetooth(explicit, denied = {
            btBox.removeAllViews()
            btBox.addView(ActionRow(ctx, ctx.getString(R.string.dev_bt_allow), ctx.getString(R.string.dev_bt_allow_hint)) { renderBt(explicit = true) })
        }) {
            btBox.removeAllViews()
            val list = host.service.pairedControllers()
            bt = list ?: emptyList()
            when {
                list == null -> btBox.addView(ActionRow(ctx, ctx.getString(R.string.dev_bt_off), ctx.getString(R.string.dev_bt_off_hint), chevron = true) { renderBt() })
                list.isEmpty() -> btBox.addView(ActionRow(ctx, ctx.getString(R.string.dev_bt_none), ctx.getString(R.string.dev_bt_none_hint), ctx.getString(R.string.dev_refresh)) { renderBt() })
                else -> {
                    val saved = Prefs.getBluetoothAddress(ctx)
                    val idx = list.indexOfFirst { it.address == saved }.coerceAtLeast(0)
                    Prefs.setBluetoothAddress(ctx, list[idx].address)
                    Prefs.setBluetoothName(ctx, list[idx].name)
                    val row = ChoiceRow(ctx, ctx.getString(R.string.dev_bt_device), ctx.getString(R.string.dev_bt_device_hint), list.map { it.name },
                        { list.indexOfFirst { it.address == Prefs.getBluetoothAddress(ctx) }.coerceAtLeast(0) }) { i ->
                        Prefs.setBluetoothAddress(ctx, list[i].address)
                        Prefs.setBluetoothName(ctx, list[i].name)
                    }
                    btRow = row
                    btBox.addView(row)
                }
            }
        }
    }

    override fun onFrame(f: LiveFrame) {
        val now = SystemClock.uptimeMillis()
        val slow = now - slowAt > 1000L
        if (f.statusVersion == seen && !slow) return
        seen = f.statusVersion
        if (slow) slowAt = now
        battery.text = f.battery?.let { "$it%" } ?: "—"
        battery.setTextColor(when {
            f.battery == null -> C.text3
            f.battery!! <= 15 -> C.danger
            f.battery!! <= 30 -> C.warning
            else -> C.text
        })
        batteryNote.text = when {
            f.charging == true -> ctx.getString(R.string.dev_charging)
            f.battery == null -> ctx.getString(R.string.dev_battery_unknown)
            else -> ctx.getString(R.string.dev_battery)
        }
        controllerDot.color = if (f.connected) C.success else if (f.stale || f.serviceRunning) C.warning else C.text3
        controllerDot.halo = f.connected
        controllerState.text = when {
            f.connected -> ctx.getString(R.string.dev_connected)
            f.stale -> ctx.getString(R.string.dev_link_lost)
            f.serviceRunning -> ctx.getString(R.string.dev_waiting)
            else -> ctx.getString(R.string.dev_off)
        }
        linkValue.text = if (f.connected) f.linkLabel() else "—"
        fwValue.text = f.firmware ?: "—"
        rateValue.text = if (f.reportRate > 0) "${f.reportRate} Hz" else "—"

        val svc = host.service
        health[0].set(
            if (f.serviceRunning) C.success else C.text3,
            ctx.getString(if (f.serviceRunning) R.string.dev_service_on else R.string.dev_service_off),
            ctx.getString(if (f.serviceRunning) R.string.strip_stop_service else R.string.strip_start_service),
        ) { svc.toggle(host.frame.serviceRunning) }
        val padText = when {
            f.mode == ControllerService.InjectionMode.SHIZUKU_INJECT -> ctx.getString(R.string.dev_pad_inject, f.backendDetail).trim()
            f.padUp -> ctx.getString(R.string.dev_pad_up, UInputNative.backendName(f.backend))
            f.serviceRunning -> ctx.getString(R.string.dev_pad_down)
            else -> ctx.getString(R.string.dev_pad_idle)
        }
        health[1].set(
            when {
                f.padUp -> C.success
                f.serviceRunning -> C.warning
                else -> C.text3
            },
            padText,
            if (f.serviceRunning && !f.padUp) ctx.getString(R.string.dev_reconnect) else null,
        ) { svc.reconnect() }
        if (slow || f.shizukuReady) {
            val st = if (f.shizukuReady) ServiceControl.ShizukuState.READY else svc.shizukuState()
            val (color, text, action) = when (st) {
                ServiceControl.ShizukuState.READY -> Triple(C.success, R.string.dev_shizuku_ready, 0)
                ServiceControl.ShizukuState.NOT_RUNNING -> Triple(C.warning, R.string.dev_shizuku_stopped, R.string.dev_shizuku_start)
                ServiceControl.ShizukuState.NO_PERMISSION -> Triple(C.warning, R.string.dev_shizuku_permission, R.string.dev_shizuku_grant)
                ServiceControl.ShizukuState.NOT_INSTALLED -> Triple(C.danger, R.string.dev_shizuku_missing, R.string.dev_shizuku_install)
            }
            health[2].set(color, ctx.getString(text), if (action == 0) null else ctx.getString(action)) { svc.fixShizuku() }
        }
    }

    private fun showHelp() {
        val s = Sheet(ctx, 440)
        s.header(ctx.getString(R.string.dev_help_eyebrow), ctx.getString(R.string.help_dialog_title), null)
        fun para(t: CharSequence) = s.body.addView(Kit.body(ctx, t, 14f, C.text2), Kit.lp(l = 11, r = 8, b = 12, ctx = ctx))
        fun step(keys: String, what: String, led: String) {
            val r = Kit.row(ctx)
            r.addView(Kit.label(ctx, keys, 11.5f, C.text).apply {
                setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(10), ctx.dp(6))
                background = android.graphics.drawable.GradientDrawable().apply { setColor(C.raised); cornerRadius = ctx.dp(8f) }
            })
            r.addView(Kit.body(ctx, what, 14f, C.text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ctx.dp(12) })
            r.addView(Kit.label(ctx, led, 10.5f, C.text3))
            s.body.addView(r, Kit.lp(l = 11, r = 8, b = 10, ctx = ctx))
        }
        // Something focusable so the sheet holds focus; the D-pad then scrolls the text.
        val done = Kit.button(ctx, ctx.getString(R.string.dev_help_done)) { host.closeSheet() }
        s.body.addView(done, Kit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, l = 8, b = 6, ctx = ctx))
        s.body.addView(Kit.section(ctx, ctx.getString(R.string.help_section_wireless), 12))
        para(ctx.getString(R.string.help_para_wireless_intro))
        step("A + RB + Steam", ctx.getString(R.string.help_puck_right), ctx.getString(R.string.help_led_white))
        step("A + LB + Steam", ctx.getString(R.string.help_puck_left), ctx.getString(R.string.help_led_white))
        step("B + RB + Steam", ctx.getString(R.string.help_bluetooth), ctx.getString(R.string.help_led_blue))
        para(ctx.getString(R.string.help_para_puck_slots))
        para(ctx.getString(R.string.help_para_bt_independent))
        s.body.addView(Kit.section(ctx, ctx.getString(R.string.help_section_wired)))
        para(ctx.getString(R.string.help_wired_off))
        para(ctx.getString(R.string.help_wired_on))
        para(ctx.getString(R.string.help_para_power_off))
        host.openSheet(s)
        s.focus(done)
    }

    private companion object {
        const val GITHUB = "https://github.com/lluppi/steam-controller-android-tv"
    }
}
