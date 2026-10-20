package com.steamcontroller.android.ui.live

import android.content.Context
import android.os.SystemClock
import android.view.Choreographer
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.Transport
import com.steamcontroller.android.live.ControllerLive
import com.steamcontroller.android.parser.Buttons
import com.steamcontroller.android.parser.SteamControllerState
import com.steamcontroller.android.service.ControllerService
import kotlin.math.max

/**
 * Every control the diagram can light, in one fixed order so per-frame state lives in plain
 * arrays. Ids equal ControllerGeometry control ids (= SteamButton names plus DPAD/LPAD/RPAD).
 */
object Ctl {
    val IDS = arrayOf(
        "A", "B", "X", "Y", "LB", "RB", "LT", "RT", "LS", "RS", "MENU", "VIEW", "STEAM", "QUICK_ACCESS",
        "L4", "L5", "R4", "R5", "GRIP_LT", "GRIP_RT", "DPAD", "LPAD", "RPAD",
    )
    val COUNT = IDS.size
    fun index(id: String): Int = IDS.indexOf(id)

    const val A = 0
    const val B = 1
    const val LT = 6
    const val RT = 7
    const val GRIP_LT = 18
    const val GRIP_RT = 19
    const val DPAD = 20
    const val LPAD = 21
    const val RPAD = 22

    private val MASKS = intArrayOf(
        Buttons.A, Buttons.B, Buttons.X, Buttons.Y, Buttons.LB, Buttons.RB, 0, 0, Buttons.LS, Buttons.RS,
        Buttons.MENU, Buttons.VIEW, Buttons.STEAM, Buttons.QUICK_ACCESS, Buttons.L4, Buttons.L5, Buttons.R4,
        Buttons.R5, Buttons.GRIP_LT, Buttons.GRIP_RT, 0, 0, 0,
    )
    private const val DPAD_ANY = Buttons.DPAD_UP or Buttons.DPAD_DOWN or Buttons.DPAD_LEFT or Buttons.DPAD_RIGHT

    /** Pad touch without a click lights the pad at this level; a click is 1. */
    const val PAD_TOUCH_LEVEL = 0.35f

    /** Writes a 0..1 level per control into [out] (triggers are analog, pads 0.35 touched / 1 clicked). */
    fun levels(s: SteamControllerState?, out: FloatArray) {
        if (s == null) { out.fill(0f); return }
        val b = s.buttons
        for (i in 0 until COUNT) out[i] = if (MASKS[i] != 0 && (b and MASKS[i]) != 0) 1f else 0f
        out[LT] = (s.leftTrigger / 32767f).coerceIn(0f, 1f)
        out[RT] = (s.rightTrigger / 32767f).coerceIn(0f, 1f)
        out[DPAD] = if ((b and DPAD_ANY) != 0) 1f else 0f
        out[LPAD] = when {
            (b and Buttons.TP_LT_CLICK) != 0 -> 1f
            (b and Buttons.TP_LT) != 0 -> PAD_TOUCH_LEVEL
            else -> 0f
        }
        out[RPAD] = when {
            (b and Buttons.TP_RT_CLICK) != 0 -> 1f
            (b and Buttons.TP_RT) != 0 -> PAD_TOUCH_LEVEL
            else -> 0f
        }
    }
}

/**
 * Rising-edge detector over [Ctl] levels. A per-control threshold array decides what counts as
 * "pressed" (0 = ignore that control), so the same detector serves the passive jump (clicks only)
 * and identify (anything, touches included).
 */
class EdgeDetector {
    private val prev = FloatArray(Ctl.COUNT)
    private var primed = false

    /** Seeds the baseline so a control already held when capture starts does not fire. */
    fun reset(levels: FloatArray?) {
        if (levels == null) prev.fill(0f) else levels.copyInto(prev)
        primed = levels != null
    }

    /** First control index whose level crossed its threshold since the last call, or -1. */
    fun rising(levels: FloatArray, thresholds: FloatArray): Int {
        var hit = -1
        if (primed) {
            for (i in 0 until Ctl.COUNT) {
                val t = thresholds[i]
                if (t > 0f && prev[i] < t && levels[i] >= t) { hit = i; break }
            }
        }
        levels.copyInto(prev)
        primed = true
        return hit
    }

    companion object {
        /** Passive jump on the controller page: everything except A, B, the D-pad, pad touches and the capacitive grips. */
        val PASSIVE = FloatArray(Ctl.COUNT) { 0.5f }.also {
            it[Ctl.A] = 0f; it[Ctl.B] = 0f; it[Ctl.DPAD] = 0f
            it[Ctl.GRIP_LT] = 0f; it[Ctl.GRIP_RT] = 0f
            it[Ctl.LPAD] = 0.9f; it[Ctl.RPAD] = 0.9f
        }

        /** Identify: the next press of anything, pad touches and grips included. */
        val ANY = FloatArray(Ctl.COUNT) { 0.5f }.also {
            it[Ctl.LT] = 0.3f; it[Ctl.RT] = 0.3f
            it[Ctl.LPAD] = Ctl.PAD_TOUCH_LEVEL; it[Ctl.RPAD] = Ctl.PAD_TOUCH_LEVEL
        }
    }
}

/**
 * Everything the settings UI shows about the controller and the service, merged from the
 * [ControllerLive] contract and the service's existing flows (so the UI is correct both before and
 * after the runtime publishes every field). One instance, refilled in place every frame.
 */
class LiveFrame {
    var state: SteamControllerState? = null
    var connected = false
    var stale = false
    var link: ControllerLive.Link = ControllerLive.Link.NONE
    var battery: Int? = null
    var charging: Boolean? = null
    var firmware: String? = null
    var serviceRunning = false
    var padUp = false
    var shizukuReady = false
    var profileName: String? = null
    var profileId: String? = null
    var reportRate = 0
    var mode: ControllerService.InjectionMode = ControllerService.InjectionMode.NONE
    var backend = 0
    var backendDetail = ""
    var rumbleSupported = true
    var linkDetail = ""

    /** Bumped whenever a field the status strip shows changes. */
    var statusVersion = 0
        private set
    private var statusKey = 0

    fun refill(shizuku: Boolean, activeName: String?, activeId: String?, transportBt: Boolean) {
        val snap = ControllerLive.snapshot.value
        val svc = ControllerService.serviceStateFlow.value
        state = snap.state ?: ControllerService.stateFlow.value
        stale = svc.link.state == ControllerService.LinkState.STALE
        connected = snap.connected || svc.link.state == ControllerService.LinkState.LINKED
        mode = svc.mode
        serviceRunning = snap.serviceRunning || svc.mode != ControllerService.InjectionMode.NONE || ControllerService.isRunning
        link = when {
            snap.link != ControllerLive.Link.NONE -> snap.link
            !connected -> ControllerLive.Link.NONE
            transportBt -> ControllerLive.Link.BLUETOOTH
            else -> ControllerLive.Link.PUCK
        }
        battery = snap.batteryPercent ?: svc.batteryPercent
        charging = snap.charging
        firmware = snap.firmware
        padUp = snap.virtualPadUp || svc.mode.isVirtualDevice
        shizukuReady = snap.shizukuReady || shizuku
        profileName = snap.activeProfileName ?: activeName
        profileId = snap.activeProfileId ?: activeId
        reportRate = max(snap.reportRate, svc.reportRateHz)
        backend = svc.backend
        backendDetail = svc.backendDetail
        rumbleSupported = svc.rumbleSupported
        linkDetail = svc.link.detail
        var k = 17
        k = k * 31 + connected.hashCode()
        k = k * 31 + stale.hashCode()
        k = k * 31 + link.ordinal
        k = k * 31 + (battery ?: -1)
        k = k * 31 + (charging?.hashCode() ?: 3)
        k = k * 31 + serviceRunning.hashCode()
        k = k * 31 + padUp.hashCode()
        k = k * 31 + shizukuReady.hashCode()
        k = k * 31 + (profileName?.hashCode() ?: 0)
        k = k * 31 + mode.ordinal
        k = k * 31 + (reportRate / 10)
        if (k != statusKey) { statusKey = k; statusVersion++ }
    }

    fun linkLabel(): String = when (link) {
        ControllerLive.Link.PUCK -> "Puck"
        ControllerLive.Link.USB -> "USB"
        ControllerLive.Link.BLUETOOTH -> "Bluetooth"
        ControllerLive.Link.NONE -> if (stale) "Link lost" else "No controller"
    }
}

/**
 * Drives the UI at display rate, capped at ~60 Hz, only while started. Each tick refills the shared
 * [LiveFrame] and hands it to [onFrame]; views decide themselves whether anything changed.
 */
class LiveTicker(private val ctx: Context, private val onFrame: (LiveFrame) -> Unit) : Choreographer.FrameCallback {
    val frame = LiveFrame()
    private var running = false
    private var lastMs = 0L
    private var slowAt = 0L
    private var shizuku = false
    private var activeName: String? = null
    private var activeId: String? = null
    private var transportBt = false

    fun start() {
        if (running) return
        running = true
        slowAt = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        Choreographer.getInstance().postFrameCallback(this)
        val now = SystemClock.uptimeMillis()
        if (now - lastMs < 15L) return
        lastMs = now
        if (now - slowAt > 1000L) {
            // Binder and preference reads: once a second is plenty.
            slowAt = now
            shizuku = try {
                rikka.shizuku.Shizuku.pingBinder() &&
                    rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) {
                false
            }
            transportBt = Prefs.getTransport(ctx) == Transport.BLUETOOTH
            val id = Prefs.getActiveNamedProfileId(ctx)
            activeId = id
            activeName = id?.let { pid -> Prefs.listNamedProfiles(ctx).firstOrNull { it.id == pid }?.name }
        }
        frame.refill(shizuku, activeName, activeId, transportBt)
        onFrame(frame)
    }

    /** Forces the slow fields (Shizuku, active profile) to be re-read on the next frame. */
    fun invalidateSlow() { slowAt = 0L }
}
