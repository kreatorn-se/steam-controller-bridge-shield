package com.steamcontroller.android.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.steamcontroller.android.BuildConfig
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.R
import com.steamcontroller.android.Transport
import com.steamcontroller.android.bt.BluetoothHidManager
import com.steamcontroller.android.input.GamepadMapper
import com.steamcontroller.android.input.ProfileSwitcher
import com.steamcontroller.android.input.ShizukuInputInjector
import com.steamcontroller.android.live.ControllerLive
import com.steamcontroller.android.parser.Buttons
import com.steamcontroller.android.parser.SteamControllerState
import com.steamcontroller.android.parser.BatteryStatus
import com.steamcontroller.android.parser.SteamReportParser
import com.steamcontroller.android.ui.pill.Pill
import com.steamcontroller.android.uinput.UInputGamepad
import com.steamcontroller.android.uinput.UInputNative
import com.steamcontroller.android.usb.HidReportReader
import com.steamcontroller.android.usb.SteamHidProtocol
import com.steamcontroller.android.usb.UsbConnectionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

class ControllerService : Service() {
    enum class InjectionMode {
        NONE,
        UINPUT,
        UHID,
        SHIZUKU_INJECT,
        ;

        /**
         * True when frames go through a real virtual input device. UINPUT and UHID are
         * interchangeable from the app's point of view - they differ only in which kernel
         * interface the shell-UID service used to create the device (see cpp/output_backend.h).
         */
        val isVirtualDevice: Boolean get() = this == UINPUT || this == UHID
    }

    /** What the UI should say about the link to the controller. */
    enum class LinkState {
        /** Nothing usable: service not started, or no controller paired. */
        DISCONNECTED,

        /** Frames are arriving: the only state that earns a green status card. */
        LINKED,

        /**
         * Connected at the Bluetooth level but no frames arriving. This is where the controller
         * sits when it has gone to sleep, or when the GATT handshake stalled - the old UI showed
         * a healthy card through both, which looks identical to a broken device.
         */
        STALE,
    }

    data class LinkStatus(
        val state: LinkState,
        val detail: String,
    )

    companion object {
        private const val TAG = "ControllerService"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "steam_controller"
        const val EXTRA_DEVICE = "usb_device"
        const val ACTION_STOP = "com.steamcontroller.android.STOP"
        const val ACTION_RESTART = "com.steamcontroller.android.RESTART"
        const val ACTION_RECONNECT = "com.steamcontroller.android.RECONNECT"
        /** Notification action: toggle the emulated device (gamepad <-> desktop). */
        const val ACTION_NEXT_PROFILE = "com.steamcontroller.android.NEXT_PROFILE"

        /** Step through the saved Game Profiles (same as the Steam + D-pad chord). */
        const val ACTION_NEXT_NAMED_PROFILE = "com.steamcontroller.android.NEXT_NAMED_PROFILE"
        const val ACTION_PREV_NAMED_PROFILE = "com.steamcontroller.android.PREV_NAMED_PROFILE"

        /** A named profile was just written to the prefs: live-apply what needs the service. */
        private const val ACTION_NAMED_PROFILE_APPLIED = "com.steamcontroller.android.NAMED_PROFILE_APPLIED"

        /** Set by ReviveReceiver: the process was killed and restarted, the pad never went away. */
        const val EXTRA_REVIVED = "revived"
        private const val ACTION_USB_PERMISSION = "com.steamcontroller.android.SERVICE_USB_PERMISSION"
        const val ACTION_TEST_RUMBLE = "com.steamcontroller.android.TEST_RUMBLE"
        private const val TEST_RUMBLE_DURATION_MS = 5000L
        private const val UNBIND_TIMEOUT_MS = 2000L

        private const val REPORT_STATE_USB = 0x42
        private const val REPORT_STATE_BLE = 0x45
        private const val REPORT_WIRELESS = 0x79
        private const val REPORT_WIRELESS_X = 0x46

        @Volatile
        var isRunning = false
            private set

        /** How often the link-liveness status is recomputed. */
        private const val LINK_POLL_MS = 1500L

        /** No frames for this long means the link is not actually delivering. */
        private const val FRAME_STALE_MS = 3000L

        /** Link supervisor period: stale detection, re-init of a silent controller, USB re-attach. */
        private const val SUPERVISE_MS = 1000L

        /** After a reconnect request, say so if no controller has spoken by then. */
        private const val RECONNECT_OUTCOME_MS = 6000L

        /** A silent puck slot / cable is re-initialised this often (then backs off). */
        private const val KICK_FAST_MS = 3000L
        private const val KICK_SLOW_MS = 10_000L
        private const val KICKS_BEFORE_SLOW = 10

        /** Silent this long on one USB device while another is attached: try the other one. */
        private const val SWITCH_DEVICE_AFTER_MS = 8000L

        /** Re-open attempts of an attached-but-closed device: 2 s, doubling to 30 s. */
        private const val USB_SCAN_MIN_MS = 2000L
        private const val USB_SCAN_MAX_MS = 30_000L
        /** How long a USB prompt waits for Shizuku to bring up the shell service: 8 × 500 ms. */
        private const val SHELL_WAIT_STEPS = 8
        private const val SHELL_WAIT_STEP_MS = 500L

        /** A device opened this recently is not re-opened by a second event for the same plug-in. */
        private const val USB_REOPEN_GUARD_MS = 2000L

        /** How long the CONNECTED pill waits for a battery level. */
        private const val CONNECT_PILL_BATTERY_WAIT_MS = 1500L

        /** Low-battery pills, once per connection each. */
        private const val BATTERY_LOW_PERCENT = 15
        private const val BATTERY_CRITICAL_PERCENT = 5

        /**
         * Full re-initialisation of the controller link without stopping the service: re-sends
         * the controller setup, re-opens the USB device (or rebuilds the Bluetooth link) and
         * recreates the virtual pad if it went away. Starts the service if it is not running.
         * Shows the RECONNECTING pill, then CONNECTED or why not. Callable from any thread.
         */
        fun requestReconnect(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, ControllerService::class.java)
            if (isRunning) {
                intent.action = ACTION_RECONNECT
            } else {
                // The user asked for a working controller; a deliberate earlier Stop is overridden.
                Prefs.setUserStoppedService(app, false)
                Pill.show(app, Pill.Kind.RECONNECTING, "RECONNECTING")
            }
            try {
                app.startForegroundService(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "requestReconnect: ${t.message}")
                Pill.show(app, Pill.Kind.FAILED, "RECONNECT FAILED")
            }
        }

        /** Tell a running service that Prefs now hold a different named profile (ProfileSwitcher). */
        fun notifyNamedProfileApplied(context: Context) {
            if (!isRunning) return
            val app = context.applicationContext
            try {
                app.startService(Intent(app, ControllerService::class.java).setAction(ACTION_NAMED_PROFILE_APPLIED))
            } catch (t: Throwable) {
                Log.w(TAG, "notifyNamedProfileApplied: ${t.message}")
            }
        }

        // Observed by DebugActivity / MainActivity for live display
        private val _stateFlow = MutableStateFlow<SteamControllerState?>(null)
        val stateFlow: StateFlow<SteamControllerState?> = _stateFlow.asStateFlow()

        private val _rawReportFlow = MutableStateFlow<ByteArray?>(null)
        val rawReportFlow: StateFlow<ByteArray?> = _rawReportFlow.asStateFlow()

        /** Everything the UI needs to know about the service, in one atomically-updated object. */
        data class ServiceState(
            val mode: InjectionMode = InjectionMode.NONE,
            val link: LinkStatus = LinkStatus(LinkState.DISCONNECTED, "not started"),
            val batteryPercent: Int? = null,
            val profileId: Int? = null,
            val backend: Int = UInputNative.Backend.NONE,
            val backendDetail: String = "",
            val rumbleSupported: Boolean = true,
            val reportRateHz: Int = 0,
        )

        // One flow rather than one per field: six independent flows could (and did) disagree
        // with each other, e.g. a mode that had already switched while the backend detail was
        // still describing the previous one. Consumers read a consistent snapshot instead.
        private val _serviceStateFlow = MutableStateFlow(ServiceState())
        val serviceStateFlow: StateFlow<ServiceState> = _serviceStateFlow.asStateFlow()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var usbManager: UsbConnectionManager
    private lateinit var btManager: BluetoothHidManager
    private val legacyInjector = ShizukuInputInjector()
    private lateinit var uinput: UInputGamepad
    @Volatile private var mode: InjectionMode = InjectionMode.NONE

    // Guards against a duplicate start re-initializing on top of a live connection: a second GATT
    // client against a controller that accepts one makes the handshake stall at the first
    // subscription, which presents as "the controller went unresponsive".
    @Volatile private var initializing = false
    @Volatile private var initializedTransport: Transport? = null

    // Link health. `lastFrameAt` is the only honest signal that the controller is actually there;
    // `btConnected` is what the Bluetooth stack thinks, which can be true on a link that is
    // delivering nothing.
    @Volatile private var lastFrameAt = 0L

    @Volatile private var btConnected = false

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    // Diagnostic for the trackpad clicks (see onHidFrame).
    private var lastRawLogButtons = 0
    private var lastRawLogMs = 0L
    private var rateWindowStartedAt = 0L
    private var rateFrameCount = 0
    private var reader: HidReportReader? = null
    private var heartbeatJob: Job? = null

    @Volatile private var activeUsbInterfaceId: Int? = null
    private val configuredUsbInterfaces = mutableSetOf<Int>()

    // ── Link supervision (controller power cycle, puck/cable unplug and replug) ──────────────
    // True while the controller itself is talking to us. Flipped by frames (online) and by a
    // wireless-disconnect report, frame silence or a detach (offline); each flip shows a pill.
    private val controllerOnline = AtomicBoolean(false)

    @Volatile private var offlineSince = 0L

    // Serialises everything that opens/closes the USB device (supervisor, attach/detach, reconnect).
    private val usbMutex = Mutex()
    private val usbPermissionRequested = mutableSetOf<String>()
    private var lastKickAt = 0L
    private var kickCount = 0
    private var lastUsbScanAt = 0L
    private var usbScanIntervalMs = USB_SCAN_MIN_MS
    private var lastUsbOpenAt = 0L

    @Volatile private var usbReaderFailing = false

    // At most one cable <-> puck switch per offline episode (reset when the controller talks).
    @Volatile private var switchedThisEpisode = false

    // CONNECTED waits briefly for the first battery report so it can say the level.
    private val connectPillPending = AtomicBoolean(false)
    private var connectPillJob: Job? = null

    // The legacy inject fallback has released its keys for the current suppression.
    private var legacySuppressed = false
    private var reconnectJob: Job? = null
    private var outcomeJob: Job? = null

    // A revived service re-attaches to a pad that never went away: no "connected" pill for that.
    @Volatile private var quietLinkUntil = 0L

    @Volatile private var batteryLowWarned = false

    @Volatile private var batteryCriticalWarned = false

    @Volatile private var publishedProfileKey: String? = null
    private var superviseTicks = 0

    private class PadHapticState {
        var touching = false
        var clicked = false
        var previousX = 0
        var previousY = 0
        var accumulatedDistance = 0.0
        var lastSentAt = 0L
    }

    private val leftPadHaptic = PadHapticState()
    private val rightPadHaptic = PadHapticState()
    private var loggedUsbHapticFailure = false

    // Bits whose changes must pass through the debounce mechanism before being injected.
    // Mechanical switches only - the SC2026's grip squeeze sensors (GRIP_LT/RT), trackpad
    // touch flags (TP_*) and stick touch flags (LS_TOUCH/RS_TOUCH) are capacitive and
    // inherently noisy when the controller is held; if those are mapped, the debounce
    // counter will keep resetting and the mapping will misbehave (known limitation).
    // L4/L5/R4/R5 ARE included - they are real mechanical back-paddle switches.
    private val INJECTABLE_MASK =
        Buttons.A or Buttons.B or Buttons.X or Buttons.Y or
            Buttons.LB or Buttons.RB or
            Buttons.MENU or Buttons.VIEW or Buttons.STEAM or Buttons.QUICK_ACCESS or
            Buttons.LS or Buttons.RS or
            Buttons.L4 or Buttons.L5 or Buttons.R4 or Buttons.R5 or
            Buttons.DPAD_UP or Buttons.DPAD_DOWN or Buttons.DPAD_LEFT or Buttons.DPAD_RIGHT

    // A time window keeps mechanical-button latency consistent across USB and Bluetooth.
    private val DEBOUNCE_MS = 12L
    private var confirmedState: SteamControllerState? = null
    private var pendingButtons = 0
    private var pendingSinceMs = 0L

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            wakeLock =
                getSystemService(android.os.PowerManager::class.java)
                    ?.newWakeLock(
                        android.os.PowerManager.PARTIAL_WAKE_LOCK,
                        "SteamController::bluetooth",
                    )?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not acquire Bluetooth wake lock: ${t.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not release Bluetooth wake lock: ${t.message}")
        } finally {
            wakeLock = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Diagnostics.record(TAG, "service created")
        usbManager = UsbConnectionManager(this)
        btManager = BluetoothHidManager(this)
        uinput = UInputGamepad(this, Prefs.getProfile(this))
        com.steamcontroller.android.hub.GamehubSync.start(this)
        // Gamehub background work (diagnostics, Steam profile switch, update check); off the
        // input path, on its own thread.
        com.steamcontroller.android.hub.Background.start(this, "service")
        uinput.onRumble = { strong, weak -> forwardRumble(strong, weak) }
        // The shell service is up: let it grant USB access to the Valve devices attached, so the
        // puck needs no "allow" prompt after a reboot, and pick up a device that was waiting.
        uinput.onShellReady = { onShellReadyForUsb() }
        createNotificationChannel()
        _serviceStateFlow.value =
            _serviceStateFlow.value.copy(profileId = Prefs.getProfile(this).id)
        ControllerLive.update { it.copy(serviceRunning = true, shizukuReady = shizukuRunning()) }
        publishActiveProfile(force = true)
        registerUsbReceiver()

        // Refresh the foreground notification whenever the controller's battery level changes.
        // StateFlow only emits on actual value changes, so this triggers ~once per percent dropped.
        scope.launch {
            _serviceStateFlow.collect { refreshNotification() }
        }

        startForegroundAppMonitor()

        // Link liveness ticker. Drives the honest status card: frames in the last few seconds, or
        // an explicit reason why not.
        scope.launch {
            while (isActive) {
                refreshLinkStatus()
                delay(LINK_POLL_MS)
            }
        }

        // Link supervisor: notices a controller that went quiet, re-initialises one that came
        // back without talking (fresh from a power cycle, still in lizard mode), and re-opens
        // the puck or cable after it was unplugged and plugged back in. Blocking USB control
        // transfers, so it gets an IO thread of its own.
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    superviseLink()
                } catch (t: Throwable) {
                    Log.w(TAG, "link supervisor: ${t.message}")
                }
                delay(SUPERVISE_MS)
            }
        }
    }

    /**
     * Recompute what to tell the user about the link.
     *
     * Deliberately pessimistic: `LINKED` requires recent frames, because a bonded controller that
     * is silent is indistinguishable from a broken one and the UI used to show a green card either
     * way.
     */

    /** Shizuku's binder, defensively - the api throws when it is not ready. */
    private fun shizukuRunning(): Boolean =
        try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }

    private fun refreshLinkStatus() {
        val status =
            when {
                mode == InjectionMode.NONE -> {
                    if (shizukuRunning()) {
                        LinkStatus(LinkState.DISCONNECTED, "service stopped")
                    } else {
                        // The post-reboot state on Android 9: there is no wireless-debugging
                        // pairing, so Shizuku can only be started from a PC. Say so.
                        LinkStatus(
                            LinkState.DISCONNECTED,
                            "Shizuku is not running - after a reboot, start it from a PC (see Help)",
                        )
                    }
                }

                mode == InjectionMode.SHIZUKU_INJECT -> {
                    LinkStatus(LinkState.STALE, "inject fallback - most apps ignore input")
                }

                lastFrameAt != 0L &&
                    android.os.SystemClock.uptimeMillis() - lastFrameAt < FRAME_STALE_MS -> {
                    LinkStatus(LinkState.LINKED, "")
                }

                initializedTransport == Transport.USB -> {
                    LinkStatus(
                        LinkState.DISCONNECTED,
                        "controller not connected - press Steam or switch to a paired Puck slot",
                    )
                }

                btManager.listPairedSteamControllers().isEmpty() -> {
                    LinkStatus(
                        LinkState.DISCONNECTED,
                        "no Steam Controller paired - pair it in Bluetooth settings",
                    )
                }

                !btConnected -> {
                    LinkStatus(
                        LinkState.DISCONNECTED,
                        "controller not connected - press Steam to wake it",
                    )
                }

                else -> {
                    LinkStatus(
                        LinkState.STALE,
                        "link stalled - controller asleep, or hold B + R1 + Steam for a blue LED",
                    )
                }
            }
        if (_serviceStateFlow.value.link != status) {
            val message =
                "link status: ${status.state}" +
                    if (status.detail.isEmpty()) "" else " - ${status.detail}"
            Log.i(TAG, message)
            Diagnostics.record(TAG, message)
        }
        _serviceStateFlow.value =
            _serviceStateFlow.value.copy(
                link = status,
                reportRateHz =
                    if (status.state == LinkState.LINKED) {
                        _serviceStateFlow.value.reportRateHz
                    } else {
                        0
                    },
            )
    }

    // ─── Foreground-app auto-switch ─────────────────────────────────────────────
    private var foregroundAppMonitorJob: Job? = null
    private var lastForegroundPackage: String? = null

    /**
     * Poll UsageStatsManager every 1.5s for the focused app. On change, look up
     * a matching named-profile binding and live-switch the gamepad profile.
     * Silently inert if the user hasn't granted PACKAGE_USAGE_STATS.
     */
    private fun startForegroundAppMonitor() {
        foregroundAppMonitorJob?.cancel()
        foregroundAppMonitorJob =
            scope.launch {
                while (isActive) {
                    try {
                        tickForegroundAppMonitor()
                    } catch (t: Throwable) {
                        Log.w(TAG, "Foreground monitor tick failed: ${t.message}")
                    }
                    delay(1500)
                }
            }
    }

    private var loggedNoUsagePerm = false
    private val GEFORCE_NOW_PACKAGE = "com.nvidia.geforcenow"

    private fun tickForegroundAppMonitor() {
        if (!UsageStatsHelper.hasPermission(this)) {
            if (!loggedNoUsagePerm) {
                Log.w(TAG, "Auto-switch inert: PACKAGE_USAGE_STATS not granted")
                loggedNoUsagePerm = true
            }
            return
        }
        loggedNoUsagePerm = false

        val current = UsageStatsHelper.getCurrentForegroundApp(this) ?: return
        if (current == lastForegroundPackage) return
        Log.v(TAG, "Foreground changed: $lastForegroundPackage → $current")
        lastForegroundPackage = current
        // Ignore self - opening our own UI shouldn't trigger anything.
        if (current == packageName) return
        // Inside GeForce NOW the app says nothing about the game: there profiles follow Steam's
        // Now playing (hub/SteamSwitch). Other bound apps (RetroArch, Moonlight...) still switch.
        if (current == GEFORCE_NOW_PACKAGE) return

        val bound =
            Prefs
                .listNamedProfiles(this)
                .firstOrNull { current in it.boundPackages }
        if (bound == null) {
            Log.v(TAG, "  no profile bound to $current")
            return
        }

        // Gate: skip only if BOTH the active named-profile id AND the live emulated
        // gamepad already match the binding. Without the live-profile check we'd skip
        // when the user has manually picked a different gamepad variant via the radios
        // (which doesn't clear activeNamedProfileId).
        val liveProfileMatches = Prefs.getProfile(this).id == bound.profileId
        val activeMatches = Prefs.getActiveNamedProfileId(this) == bound.id
        if (activeMatches && liveProfileMatches) {
            Log.v(TAG, "  '${bound.name}' already applied - skipping")
            return
        }

        Log.i(
            TAG,
            "Auto-switch → '${bound.name}' (foreground=$current, activeMatches=$activeMatches, liveMatches=$liveProfileMatches)",
        )
        Prefs.applyNamedProfile(this, bound)
        announceProfileLoaded(bound.name)
        publishActiveProfile(force = true)
        syncGamepadProfile()
    }

    // Set when a profile change arrives while a device swap is still running, so the swap loop
    // picks up the newest choice instead of dropping it.
    @Volatile private var profileResyncWanted = false

    /**
     * Bring the virtual device in line with Prefs.getProfile: recreate it only when the emulated
     * device actually changes (gamepad <-> desktop). Two Game Profiles that both emulate the
     * Xbox pad switch without the pad ever disappearing from under the game.
     */
    private fun syncGamepadProfile() {
        if (!mode.isVirtualDevice) {
            _serviceStateFlow.value = _serviceStateFlow.value.copy(profileId = Prefs.getProfile(this).id)
            refreshNotification()
            return
        }
        if (profileSwitchInFlight) {
            profileResyncWanted = true
            return
        }
        profileSwitchInFlight = true
        scope.launch {
            try {
                do {
                    profileResyncWanted = false
                    val gp = Prefs.getProfile(this@ControllerService)
                    if (_serviceStateFlow.value.profileId != gp.id) {
                        confirmedState = null
                        if (uinput.switchProfile(gp)) {
                            _serviceStateFlow.value = _serviceStateFlow.value.copy(profileId = gp.id)
                        } else {
                            Log.w(TAG, "switchProfile(${gp.displayName}) failed")
                        }
                    }
                } while (profileResyncWanted)
                refreshNotification()
            } finally {
                profileSwitchInFlight = false
            }
        }
    }

    /** ProfileSwitcher (chord or UI) wrote a named profile to the prefs. */
    private fun onNamedProfileApplied() {
        publishActiveProfile(force = true)
        syncGamepadProfile()
    }

    /** Active named profile into ControllerLive; cheap unless it changed (or [force]). */
    private fun publishActiveProfile(force: Boolean = false) {
        val id = Prefs.getActiveNamedProfileId(this)
        val key = id ?: ""
        if (!force && key == publishedProfileKey) return
        publishedProfileKey = key
        val name = id?.let { pid -> Prefs.listNamedProfiles(this).firstOrNull { it.id == pid }?.name }
        val snap = ControllerLive.snapshot.value
        if (snap.activeProfileId != id || snap.activeProfileName != name) {
            ControllerLive.update { it.copy(activeProfileId = id, activeProfileName = name) }
        }
    }

    /**
     * Surface the auto-switch to the user via:
     *  1. The status pill (above whatever app just came to the front).
     *  2. The foreground-service notification text gets the profile name appended
     *     (persistent until the next swap), so the user can always pull the shade
     *     to confirm which preset is live.
     */
    private fun announceProfileLoaded(profileName: String) {
        Log.i(TAG, "announceProfileLoaded: $profileName")
        Pill.show(this, Pill.Kind.PROFILE, "PROFILE · $profileName")
        // Notification refresh happens via refreshNotification() in the caller
        // after the service state's profileId is updated.
    }

    private var lastRumbleStrong = -1
    private var lastRumbleWeak = -1
    private var lastRumbleSentAt = 0L

    /**
     * Manual rumble test: pulse both motors at full strength for TEST_RUMBLE_DURATION_MS,
     * then stop. Triggered by the "Test rumble" button in CalibrationActivity.
     */
    private fun testRumble() {
        Log.i(TAG, "Test rumble requested")
        // Bypass the throttle by resetting last-sent timestamps
        lastRumbleSentAt = 0
        forwardRumble(0xFFFF, 0xFFFF)
        scope.launch {
            delay(TEST_RUMBLE_DURATION_MS)
            lastRumbleSentAt = 0
            forwardRumble(0, 0)
        }
    }

    /**
     * Called when a game triggers a rumble effect on the virtual gamepad.
     * Magnitudes are 0..65535. Forwarded to the controller via the active transport.
     *
     * Throttled: we re-send at most every 50ms if the magnitudes change, or every
     * 200ms if they're the same (keep-alive for long-lasting effects).
     */
    private fun forwardRumble(
        strong: Int,
        weak: Int,
    ) {
        // Apply user-configured intensity (0..100% of game-requested magnitude)
        val intensity = Prefs.getRumbleIntensity(this)
        val scaledStrong = (strong * intensity / 100).coerceIn(0, 0xFFFF)
        val scaledWeak = (weak * intensity / 100).coerceIn(0, 0xFFFF)

        val now = System.currentTimeMillis()
        val changed = (scaledStrong != lastRumbleStrong || scaledWeak != lastRumbleWeak)
        val stopping = scaledStrong == 0 && scaledWeak == 0
        val tooSoon = !stopping && (now - lastRumbleSentAt) < (if (changed) 50 else 200)
        if (tooSoon) return
        lastRumbleStrong = scaledStrong
        lastRumbleWeak = scaledWeak
        lastRumbleSentAt = now

        Log.v(
            TAG,
            "Rumble → controller: strong=$scaledStrong weak=$scaledWeak (intensity=$intensity%)",
        )
        when (Prefs.getTransport(this)) {
            Transport.BLUETOOTH -> {
                btManager.sendRumble(scaledStrong, scaledWeak)
            }

            Transport.USB -> {
                val connection = usbManager.connection
                val interfaceId =
                    activeUsbInterfaceId
                        ?: usbManager.inputPipes
                            .firstOrNull()
                            ?.usbInterface
                            ?.id
                if (connection == null ||
                    interfaceId == null ||
                    !SteamHidProtocol.sendRumble(
                        connection,
                        interfaceId,
                        scaledStrong,
                        scaledWeak,
                    )
                ) {
                    Log.w(TAG, "USB rumble feature report failed")
                }
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP || intent?.action == ACTION_RESTART) {
            // Only an explicit Stop disarms controller-connect auto-start. Reconnect and internal
            // failures must stay armed so a failed retry does not disable future auto-starts.
            if (intent.action == ACTION_STOP) {
                Prefs.setUserStoppedService(this, true)
                Pill.show(this, Pill.Kind.DISCONNECTED, "BRIDGE STOPPED")
            }
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_NEXT_PROFILE -> {
                cycleProfile()
                return START_STICKY
            }

            ACTION_NEXT_NAMED_PROFILE, ACTION_PREV_NAMED_PROFILE -> {
                ProfileSwitcher.cycle(this, if (intent.action == ACTION_NEXT_NAMED_PROFILE) 1 else -1)
                return START_STICKY
            }

            ACTION_NAMED_PROFILE_APPLIED -> {
                onNamedProfileApplied()
                return START_STICKY
            }

            ACTION_TEST_RUMBLE -> {
                testRumble()
                return START_STICKY
            }
        }

        // Every remaining path may have come in through startForegroundService.
        startForegroundCompat()

        if (intent?.action == ACTION_RECONNECT) {
            if (initializing) return START_STICKY
            if (mode != InjectionMode.NONE || initializedTransport != null) {
                fullReconnect()
                return START_STICKY
            }
            // Nothing initialised yet: a reconnect is simply a start.
            Pill.show(this, Pill.Kind.RECONNECTING, "RECONNECTING")
            armOutcomePill()
        }

        if (intent?.getBooleanExtra(EXTRA_REVIVED, false) == true) {
            quietLinkUntil = SystemClock.uptimeMillis() + 20_000L
        }

        val device = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DEVICE, UsbDevice::class.java) }

        // A duplicate start must be a no-op - see the fields above. A start for a *different*
        // transport is a deliberate restart (the UI tells the user to restart to apply a transport
        // change), so that one is allowed through.
        val requestedTransport = Prefs.getTransport(this)
        if (initializing ||
            (mode != InjectionMode.NONE && initializedTransport == requestedTransport)
        ) {
            // ...except a start that names a USB device other than the one open, or arrives while
            // the controller is silent: that is a replugged puck or a cable, and used to be
            // ignored, leaving the bridge reading a device that no longer existed.
            // A working link is never torn down for this (a charge cable plugged in mid-game).
            if (!initializing && requestedTransport == Transport.USB && device != null &&
                UsbConnectionManager.isSupportedDevice(device) && !controllerOnline.get()
            ) {
                scope.launch(Dispatchers.IO) { reinitUsb(device, "start with ${device.deviceName}") }
                return START_STICKY
            }
            Log.i(
                TAG,
                "Ignoring duplicate start (initializing=$initializing, mode=$mode, " +
                    "transport=$requestedTransport)",
            )
            return START_STICKY
        }
        // Must be set *before* the launch: otherwise two starts arriving in quick succession both
        // pass the guard and initialize twice, which is the wedge this exists to prevent.
        initializing = true

        scope.launch { initialize(device) }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private suspend fun initialize(device: UsbDevice?) {
        val transport = Prefs.getTransport(this@ControllerService)
        Log.i(TAG, "Initializing with transport=$transport")
        chooseInjectionMode()

        val ok =
            when (transport) {
                Transport.USB -> initUsb(device)
                Transport.BLUETOOTH -> initBluetooth()
            }
        if (!ok && transport == Transport.USB && waitingForUsbPermission()) {
            // The device is there; Android asked the user for access. Stay up for the answer
            // (ACTION_USB_PERMISSION) instead of stopping and orphaning the grant.
            Log.i(TAG, "USB device present without permission; waiting for the user")
            Pill.show(this, Pill.Kind.INFO, "ALLOW USB ACCESS ON THE TV")
            initializing = false
            initializedTransport = transport
            publishLive()
            return
        }
        if (!ok) {
            Log.e(TAG, "Transport init failed, stopping service")
            Pill.show(
                this,
                Pill.Kind.FAILED,
                if (transport == Transport.USB) "NO STEAM CONTROLLER FOUND" else "BLUETOOTH CONTROLLER NOT FOUND",
            )
            initializing = false
            initializedTransport = null
            stopSelf()
            return
        }
        initializing = false
        initializedTransport = transport
        publishLive()
        if (transport == Transport.BLUETOOTH) acquireWakeLock()
        val message = "controller running transport=$transport mode=$mode"
        Log.i(TAG, message)
        Diagnostics.record(TAG, message)
    }

    private fun initUsb(device: UsbDevice?): Boolean {
        val candidates = usbManager.findSteamControllers()
        val dev = device ?: candidates.firstOrNull { usbManager.hasPermission(it) } ?: candidates.firstOrNull()
        if (dev == null) {
            Log.e(TAG, "No Steam Controller found over USB")
            return false
        }
        // At service start (and after a replug): access through the shell service, no prompt.
        if (!usbManager.hasPermission(dev)) grantViaShell(dev)
        if (!usbManager.connect(dev)) {
            Log.e(TAG, "Failed to connect USB device")
            if (!usbManager.hasPermission(dev)) requestUsbPermission(dev)
            return false
        }
        val conn = usbManager.connection ?: return false
        val pipes = usbManager.inputPipes

        reader =
            HidReportReader(
                conn,
                pipes,
                onReport = { state, raw, interfaceId ->
                    onUsbHidFrame(conn, interfaceId, state, raw)
                },
                onError = { msg ->
                    Log.e(TAG, "USB read error: $msg")
                    usbReaderFailing = true
                    // Most likely unplugged: let the supervisor confirm and recover right away.
                    scope.launch(Dispatchers.IO) { superviseUsbNow() }
                },
            )
        reader?.start(scope)
        usbReaderFailing = false
        lastUsbOpenAt = SystemClock.uptimeMillis()
        kickCount = 0
        lastKickAt = SystemClock.uptimeMillis() // give the controller a moment before kicking it
        Diagnostics.record(TAG, "USB open: ${describe(dev)}")
        return true
    }

    private fun waitingForUsbPermission(): Boolean {
        val devices = usbManager.findSteamControllers()
        return devices.isNotEmpty() && devices.none { usbManager.hasPermission(it) }
    }

    private fun describe(dev: UsbDevice): String =
        (if (UsbConnectionManager.isReceiver(dev)) "puck" else "cable") + " ${dev.deviceName}"

    private fun onUsbHidFrame(
        connection: android.hardware.usb.UsbDeviceConnection,
        interfaceId: Int,
        state: SteamControllerState?,
        raw: ByteArray,
    ) {
        val reportId = if (raw.isEmpty()) -1 else raw[0].toInt() and 0xFF
        when (reportId) {
            REPORT_STATE_USB, REPORT_STATE_BLE -> {
                activeUsbInterfaceId = interfaceId
                configureUsbInterface(connection, interfaceId, force = false)
            }

            // Wireless status from the puck: the controller on this slot connected or dropped.
            REPORT_WIRELESS, REPORT_WIRELESS_X -> {
                when (if (raw.size > 1) raw[1].toInt() and 0xFF else 0) {
                    SteamHidProtocol.WIRELESS_CONNECTED -> {
                        Diagnostics.record(TAG, "puck slot $interfaceId: controller connected")
                        activeUsbInterfaceId = interfaceId
                        kickCount = 0
                        // A controller that was switched off comes back with its default settings
                        // (lizard mode on, Steam watchdog on): the init must be sent again. The
                        // first-connect-only bookkeeping used to skip exactly this.
                        configureUsbInterface(connection, interfaceId, force = true)
                    }

                    SteamHidProtocol.WIRELESS_DISCONNECTED -> {
                        Diagnostics.record(TAG, "puck slot $interfaceId: controller disconnected")
                        synchronized(configuredUsbInterfaces) { configuredUsbInterfaces.remove(interfaceId) }
                        val active = activeUsbInterfaceId
                        if (active == null || active == interfaceId) {
                            markControllerOffline("wireless disconnect on slot $interfaceId")
                        }
                    }
                }
            }
        }
        onHidFrame(state, raw)
    }

    /**
     * Send the controller its setup (lizard mode and Steam watchdog off), once per connection of
     * the controller on that interface - [force] for a fresh connection. Then read the firmware.
     */
    private fun configureUsbInterface(
        connection: UsbDeviceConnection,
        interfaceId: Int,
        force: Boolean,
    ) {
        val needs =
            synchronized(configuredUsbInterfaces) {
                configuredUsbInterfaces.add(interfaceId) || force
            }
        if (!needs) return
        scope.launch(Dispatchers.IO) {
            val ok =
                try {
                    SteamHidProtocol.disableLizardMode(connection, interfaceId)
                } catch (t: Throwable) {
                    false
                }
            if (!ok) {
                synchronized(configuredUsbInterfaces) { configuredUsbInterfaces.remove(interfaceId) }
                return@launch
            }
            if (ControllerLive.snapshot.value.firmware == null) {
                val built =
                    try {
                        SteamHidProtocol.getFirmwareBuildTime(connection, interfaceId)
                    } catch (t: Throwable) {
                        null
                    }
                if (built != null) {
                    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                    val fw = "build " + fmt.format(Date(built * 1000L))
                    ControllerLive.update { it.copy(firmware = fw) }
                    Diagnostics.record(TAG, "controller firmware $fw")
                }
            }
        }
    }

    private fun initBluetooth(): Boolean {
        if (!btManager.isBluetoothAvailable) {
            Log.e(TAG, "Bluetooth disabled or unavailable")
            return false
        }
        val address = Prefs.getBluetoothAddress(this)
        if (address == null) {
            Log.e(TAG, "No paired Bluetooth Steam Controller selected")
            return false
        }

        // Resolve the controller from the bonded list instead of trusting the cached address.
        // A BLE peripheral is given a fresh random address every time it is paired, so after a
        // re-pair the cached address refers to a device that no longer exists: getRemoteDevice()
        // still returns an object for it, connectGatt() then never succeeds, and the app retries
        // forever. This is why clearing app data used to be part of the fix-up ritual.
        val bonded = btManager.listPairedSteamControllers()
        val device = bonded.firstOrNull { it.address == address } ?: bonded.firstOrNull()
        if (device == null) {
            Log.e(TAG, "No bonded Steam Controller found (cached address: $address)")
            return false
        }
        if (device.address != address) {
            Log.i(TAG, "Controller address changed ($address → ${device.address}), updating")
            Prefs.setBluetoothAddress(this, device.address)
        }
        try {
            Prefs.setBluetoothName(this, device.name)
        } catch (_: SecurityException) {
        }

        btManager.connect(
            device,
            onReport = { raw -> onHidFrame(SteamReportParser.parse(raw), raw) },
            onConnectionChange = { connected ->
                btConnected = connected
                Log.i(TAG, "BT connection state: $connected")
                refreshLinkStatus()
            },
        )

        heartbeatJob =
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(800)
                    btManager.sendHeartbeat()
                }
            }
        return true
    }

    /**
     * One HID report off the wire.
     *
     * [state] is null whenever the report is not a state report - a short id-0x43 battery
     * status, or the 5-byte ack the controller sends back for each heartbeat write. Those used
     * to arrive here as a SteamControllerState() with every field zero, which was published to
     * _stateFlow: the calibration readout snapped to 0 and back a couple of times a second, and
     * anything else watching that flow saw a controller with centred sticks. The raw bytes still
     * flow through so the debug screen can display them; no state is invented for them.
     */
    private fun onHidFrame(
        state: SteamControllerState?,
        raw: ByteArray,
    ) {
        _rawReportFlow.value = raw

        // The parser returns null for anything that is not a 0x42/0x45 state report.
        if (state != null) {
            _stateFlow.value = state

            // Frames are the proof of life. Flip to LINKED immediately rather than waiting for
            // the ticker, so a reconnected controller goes green the moment it starts talking.
            val nowMs = android.os.SystemClock.uptimeMillis()
            lastFrameAt = nowMs
            if (!controllerOnline.get()) markControllerOnline()
            if (_serviceStateFlow.value.link.state != LinkState.LINKED) refreshLinkStatus()

            updateReportRate(nowMs)

            // Input delivery is the latency-critical path. Diagnostics and controller haptics
            // happen afterwards so a log format or USB output write cannot delay this frame.
            handleState(state)
            ControllerLive.publishFrame(state)
            if (initializedTransport == Transport.USB) updateUsbTrackpadHaptics(state, nowMs)

            // Diagnostic: dump the whole report on button changes, but only after injection.
            if (BuildConfig.DEBUG &&
                (state.buttons != lastRawLogButtons || nowMs - lastRawLogMs > 5000L)
            ) {
                lastRawLogButtons = state.buttons
                lastRawLogMs = nowMs
                Log.i(
                    TAG,
                    "raw[${raw.size}] mask=0x${state.buttons.toString(16)} " +
                        raw.joinToString(" ") { "%02x".format(it) },
                )
            }
        }

        // Dedicated battery/charge report (id 0x43) - works on both USB and BT, percent is
        // already 0-100. Deliberately outside the state-report branch: this is the report that
        // carries no state at all. Sole battery source: bytes 44-45 of the 0x45 state report
        // were assumed to be a static battery field but turned out to be live, fast-changing
        // data (empirically: flickers 0%/99% on USB), so that guess isn't used.
        SteamReportParser.parseBatteryStatus(raw)?.let { onBattery(it) }
    }

    private fun onBattery(status: BatteryStatus) {
        if (_serviceStateFlow.value.batteryPercent != status.percent) {
            _serviceStateFlow.value =
                _serviceStateFlow.value.copy(batteryPercent = status.percent)
        }
        val charging = status.charging
        val snap = ControllerLive.snapshot.value
        if (snap.batteryPercent != status.percent || snap.charging != charging) {
            ControllerLive.update { it.copy(batteryPercent = status.percent, charging = charging) }
        }
        if (connectPillPending.compareAndSet(true, false)) {
            connectPillJob?.cancel()
            showConnectedPill()
        }
        // Once per connection at 15 %, once more at 5 %. Never while on a charger, and not for a
        // 0 that is far more likely a report without a level than a controller still running.
        val pct = status.percent
        if (charging == true || pct <= 0) return
        if (pct <= BATTERY_CRITICAL_PERCENT && !batteryCriticalWarned) {
            batteryCriticalWarned = true
            batteryLowWarned = true
            Pill.show(this, Pill.Kind.BATTERY_LOW, "BATTERY $pct% · CHARGE NOW")
        } else if (pct <= BATTERY_LOW_PERCENT && !batteryLowWarned) {
            batteryLowWarned = true
            Pill.show(this, Pill.Kind.BATTERY_LOW, "BATTERY LOW · $pct%")
        }
    }

    // ─── Link supervision ───────────────────────────────────────────────────────

    private fun liveLink(): ControllerLive.Link =
        when (initializedTransport) {
            Transport.BLUETOOTH -> ControllerLive.Link.BLUETOOTH
            Transport.USB ->
                usbManager.device?.let {
                    if (UsbConnectionManager.isReceiver(it)) ControllerLive.Link.PUCK else ControllerLive.Link.USB
                } ?: ControllerLive.Link.NONE
            null -> ControllerLive.Link.NONE
        }

    /** Link / pad / Shizuku status into ControllerLive, only when something changed. */
    private fun publishLive() {
        val link = liveLink()
        val online = controllerOnline.get()
        val padUp = mode.isVirtualDevice && uinput.isReady
        val shizuku = shizukuRunning()
        val s = ControllerLive.snapshot.value
        if (s.link != link || s.connected != online || !s.serviceRunning || s.virtualPadUp != padUp ||
            s.shizukuReady != shizuku
        ) {
            ControllerLive.update {
                it.copy(
                    link = link,
                    connected = online,
                    serviceRunning = true,
                    virtualPadUp = padUp,
                    shizukuReady = shizuku,
                    state = if (online) it.state else null,
                )
            }
        }
    }

    /** First frame after the controller was away (or ever): announce it. */
    private fun markControllerOnline() {
        if (!controllerOnline.compareAndSet(false, true)) return
        offlineSince = 0L
        kickCount = 0
        batteryLowWarned = false
        batteryCriticalWarned = false
        outcomeJob?.cancel()
        outcomeJob = null
        val quiet = SystemClock.uptimeMillis() < quietLinkUntil
        quietLinkUntil = 0L
        Diagnostics.record(TAG, "controller online (${liveLink()})" + if (quiet) ", revived quietly" else "")
        switchedThisEpisode = false
        usbScanIntervalMs = USB_SCAN_MIN_MS
        ControllerLive.update { it.copy(connected = true, link = liveLink()) }
        if (quiet) return
        if (_serviceStateFlow.value.batteryPercent != null) {
            showConnectedPill()
        } else {
            // The battery report comes every few seconds; give it a moment to say the level.
            connectPillPending.set(true)
            connectPillJob?.cancel()
            connectPillJob =
                scope.launch {
                    delay(CONNECT_PILL_BATTERY_WAIT_MS)
                    if (connectPillPending.compareAndSet(true, false)) showConnectedPill()
                }
        }
    }

    private fun showConnectedPill() {
        val pct = _serviceStateFlow.value.batteryPercent
        Pill.show(
            this,
            Pill.Kind.CONNECTED,
            if (pct != null && pct > 0) "CONTROLLER CONNECTED · $pct%" else "CONTROLLER CONNECTED",
        )
    }

    /**
     * The controller stopped talking: switched off, asleep, out of range, or its puck/cable gone.
     * Releases everything held on the virtual pad, forgets the controller's setup (it returns
     * with its defaults) and the slot it was on. Returns false if it was already offline.
     */
    private fun markControllerOffline(
        reason: String,
        pillText: String? = "CONTROLLER OFF",
    ): Boolean {
        if (!controllerOnline.compareAndSet(true, false)) return false
        offlineSince = SystemClock.uptimeMillis()
        connectPillJob?.cancel()
        connectPillPending.set(false)
        Log.i(TAG, "controller offline: $reason")
        Diagnostics.record(TAG, "controller offline: $reason")
        synchronized(configuredUsbInterfaces) { configuredUsbInterfaces.clear() }
        reader?.unlockInterface()
        confirmedState = null
        if (mode.isVirtualDevice) uinput.pushNeutral()
        _stateFlow.value = null
        // Battery and firmware belong to the controller that left: the next one (or the same one
        // after a charge) reports its own, and firmware is read again on the next connect.
        _serviceStateFlow.value = _serviceStateFlow.value.copy(batteryPercent = null)
        ControllerLive.update {
            it.copy(connected = false, state = null, reportRate = 0, batteryPercent = null, charging = null, firmware = null)
        }
        refreshLinkStatus()
        if (pillText != null) Pill.show(this, Pill.Kind.DISCONNECTED, pillText)
        return true
    }

    private suspend fun superviseLink() {
        val now = SystemClock.uptimeMillis()
        val last = lastFrameAt
        if (controllerOnline.get() && last != 0L && now - last > FRAME_STALE_MS) {
            markControllerOffline("no frames for ${now - last} ms")
        }
        if (++superviseTicks % 10 == 0) publishActiveProfile(force = true) else publishActiveProfile()
        publishLive()
        if (initializedTransport == Transport.USB && !initializing) superviseUsbNow()
    }

    /** USB half of the supervisor; skipped while a reconnect or attach is busy with the device. */
    private suspend fun superviseUsbNow() {
        if (!usbMutex.tryLock()) return
        try {
            superviseUsbLocked(SystemClock.uptimeMillis())
        } finally {
            usbMutex.unlock()
        }
    }

    private suspend fun superviseUsbLocked(now: Long) {
        val dev = usbManager.device
        if (dev != null && !usbManager.isAttached(dev)) {
            onUsbDeviceGoneLocked(dev)
            return
        }
        if (dev == null) {
            // Nothing open (unplugged earlier): pick up a supported device that has come back,
            // backing off (2 s doubling to 30 s) while one is there but cannot be opened.
            if (now - lastUsbScanAt < usbScanIntervalMs) return
            lastUsbScanAt = now
            val candidates = usbManager.findSteamControllers()
            val ready = candidates.firstOrNull { usbManager.hasPermission(it) } ?: candidates.firstOrNull { grantViaShell(it) }
            if (ready != null) {
                if (connectUsbLocked(ready, "device available again")) {
                    usbScanIntervalMs = USB_SCAN_MIN_MS
                } else {
                    usbScanIntervalMs = (usbScanIntervalMs * 2).coerceAtMost(USB_SCAN_MAX_MS)
                }
            } else {
                candidates.firstOrNull()?.let { requestUsbPermission(it) }
                // Nothing usable: rescan less and less often (an attach resets this).
                usbScanIntervalMs = (usbScanIntervalMs * 2).coerceAtMost(USB_SCAN_MAX_MS)
            }
            return
        }
        if (controllerOnline.get()) return

        // The device is open but the controller is silent. A controller that was powered off
        // and on reconnects to the puck with its defaults, and may say nothing until it gets its
        // setup again - so re-send it to any slot the puck reports as connected (to the cable's
        // interface directly), a few seconds apart, slower after a while.
        val interval = if (kickCount < KICKS_BEFORE_SLOW) KICK_FAST_MS else KICK_SLOW_MS
        if (now - lastKickAt < interval) return
        lastKickAt = now
        kickCount++
        val conn = usbManager.connection ?: return
        val receiver = UsbConnectionManager.isReceiver(dev)
        for (pipe in usbManager.inputPipes) {
            val iface = pipe.usbInterface.id
            if (receiver) {
                val st =
                    try {
                        SteamHidProtocol.getWirelessState(conn, iface)
                    } catch (t: Throwable) {
                        null
                    }
                if (st == SteamHidProtocol.WIRELESS_CONNECTED) {
                    Log.i(TAG, "slot $iface reports a controller but no frames: re-sending its setup")
                    configureUsbInterface(conn, iface, force = true)
                }
            } else {
                configureUsbInterface(conn, iface, force = true)
            }
        }
        reader?.unlockInterface()

        // A cable plugged in or pulled while the other device stays attached: if this one has been
        // silent for a while and another Steam Controller device is there, try that one.
        // Once per offline episode, so a puck and a cable both attached with the controller off
        // do not ping-pong forever.
        val since = offlineSince
        if (!switchedThisEpisode && since != 0L && now - since > SWITCH_DEVICE_AFTER_MS) {
            val alt =
                usbManager.findSteamControllers().firstOrNull {
                    it.deviceName != dev.deviceName && usbManager.hasPermission(it)
                }
            if (alt != null) {
                switchedThisEpisode = true
                connectUsbLocked(alt, "silent ${describe(dev)}, trying ${describe(alt)}", force = true)
            }
        }
    }

    /**
     * Close whatever is open and open [device] (or the best attached one). Holds [usbMutex].
     * Unless [force]d, a second event for the same plug-in (attach broadcast, the activity's start,
     * the supervisor's scan) does not re-open: not the device already open and reading fine, and
     * nothing within [USB_REOPEN_GUARD_MS] of the last open.
     */
    private suspend fun connectUsbLocked(
        device: UsbDevice?,
        reason: String,
        force: Boolean = false,
    ): Boolean {
        val open = usbManager.device
        if (!force && open != null) {
            val sameDevice = device == null || device.deviceName == open.deviceName
            if ((sameDevice && !usbReaderFailing) || SystemClock.uptimeMillis() - lastUsbOpenAt < USB_REOPEN_GUARD_MS) {
                Log.i(TAG, "USB (re)connect skipped ($reason): ${describe(open)} is open")
                return true
            }
        }
        Log.i(TAG, "USB (re)connect: $reason")
        Diagnostics.record(TAG, "USB (re)connect: $reason")
        reader?.stopAndJoin()
        reader = null
        // Nothing reads the old device any more: release the pad and announce the next frame.
        markControllerOffline("USB re-open: $reason", pillText = null)
        usbManager.disconnect()
        activeUsbInterfaceId = null
        synchronized(configuredUsbInterfaces) { configuredUsbInterfaces.clear() }
        val ok = initUsb(device)
        publishLive()
        return ok
    }

    private suspend fun reinitUsb(
        device: UsbDevice?,
        reason: String,
    ): Boolean =
        usbMutex.withLock {
            if (usbManager.device != null && controllerOnline.get()) {
                true // a working link stays, whichever device was asked for
            } else {
                connectUsbLocked(device, reason)
            }
        }

    /** The open USB device went away (unplugged). Holds [usbMutex]. */
    private suspend fun onUsbDeviceGoneLocked(dev: UsbDevice) {
        reader?.stopAndJoin()
        reader = null
        usbManager.disconnect()
        activeUsbInterfaceId = null
        synchronized(configuredUsbInterfaces) { configuredUsbInterfaces.clear() }
        val text = if (UsbConnectionManager.isReceiver(dev)) "PUCK UNPLUGGED" else "CABLE UNPLUGGED"
        if (!markControllerOffline("${describe(dev)} detached", text)) {
            Pill.show(this, Pill.Kind.DISCONNECTED, text)
        }
        publishLive()
        // The cable came out but the puck is still in (or the other way round): carry on there.
        val alt =
            usbManager.findSteamControllers().firstOrNull {
                it.deviceName != dev.deviceName && usbManager.hasPermission(it)
            }
        if (alt != null) connectUsbLocked(alt, "${describe(dev)} gone, using ${describe(alt)}")
    }

    /**
     * USB access for a Valve device through the Shizuku shell service (UsbManager's own grant, as
     * the shell uid that holds MANAGE_USB): no system prompt, so the puck is ready after every
     * reboot. Only Valve devices, only for this app. False when the shell service is not connected
     * or refuses; then [requestUsbPermission] shows the system prompt as before. Blocking: call
     * off the main thread.
     */
    private fun grantViaShell(dev: UsbDevice): Boolean {
        if (dev.vendorId != UsbConnectionManager.STEAM_VID) return false
        if (usbManager.hasPermission(dev)) return true
        val r = uinput.grantUsbPermission(dev.deviceName)
        val ok = r == 1 && usbManager.hasPermission(dev)
        // Logged once per device and outcome, not on every rescan.
        val outcome = "$r/$ok"
        val changed = synchronized(shellGrantSeen) { shellGrantSeen.put(dev.deviceName, outcome) != outcome }
        if (r != -2 && changed) {
            Log.i(TAG, "shell USB grant for ${describe(dev)}: result=$r access=$ok")
            Diagnostics.record(TAG, "shell USB grant ${describe(dev)}: $outcome")
        }
        return ok
    }

    /** The last shell-grant outcome per device name, so it is logged only when it changes. */
    private val shellGrantSeen = HashMap<String, String>()

    /** The shell service connected (service start): grant every attached Valve device, then connect. */
    private fun onShellReadyForUsb() {
        scope.launch(Dispatchers.IO) {
            val devices = usbManager.findSteamControllers()
            val granted = devices.filter { !usbManager.hasPermission(it) }.count { grantViaShell(it) }
            if (granted > 0 && initializedTransport == Transport.USB && usbManager.device == null) {
                reinitUsb(null, "USB access granted by the shell service")
            }
        }
    }

    private fun requestUsbPermission(dev: UsbDevice) {
        val added = synchronized(usbPermissionRequested) { usbPermissionRequested.add(dev.deviceName) }
        if (!added) return
        // Shizuku is still bringing the shell service up (right after boot): give it a few
        // seconds to grant access before showing the system prompt, which would then be moot.
        if (uinput.isShellBinding) {
            scope.launch(Dispatchers.IO) {
                for (i in 0 until SHELL_WAIT_STEPS) {
                    delay(SHELL_WAIT_STEP_MS)
                    if (!uinput.isShellBinding) break
                }
                if (usbManager.hasPermission(dev) || grantViaShell(dev)) {
                    if (usbManager.device == null) reinitUsb(dev, "USB access granted by the shell service")
                } else {
                    showUsbPrompt(dev)
                }
            }
            return
        }
        showUsbPrompt(dev)
    }

    /** The system's own "allow access" prompt: the fallback without the shell service. */
    private fun showUsbPrompt(dev: UsbDevice) {
        try {
            val pi =
                PendingIntent.getBroadcast(
                    this,
                    3,
                    Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            getSystemService(UsbManager::class.java)?.requestPermission(dev, pi)
            Log.i(TAG, "requested USB permission for ${describe(dev)}")
        } catch (t: Throwable) {
            Log.w(TAG, "USB permission request failed: ${t.message}")
        }
    }

    private val usbReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                val dev =
                    IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        ?: return
                if (!UsbConnectionManager.isSupportedDevice(dev)) return
                if (initializedTransport != Transport.USB) return
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        Log.i(TAG, "detached: ${describe(dev)}")
                        scope.launch(Dispatchers.IO) {
                            usbMutex.withLock {
                                if (usbManager.device?.deviceName == dev.deviceName) onUsbDeviceGoneLocked(dev)
                            }
                        }
                    }

                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        Log.i(TAG, "attached: ${describe(dev)}")
                        synchronized(usbPermissionRequested) { usbPermissionRequested.remove(dev.deviceName) }
                        usbScanIntervalMs = USB_SCAN_MIN_MS
                        scope.launch(Dispatchers.IO) {
                            delay(300) // let the new device settle before claiming it
                            usbMutex.withLock {
                                // A working link stays; the supervisor moves over if it goes silent.
                                if (usbManager.device != null && controllerOnline.get()) return@withLock
                                if (usbManager.hasPermission(dev) || grantViaShell(dev)) {
                                    connectUsbLocked(dev, "${describe(dev)} attached")
                                } else {
                                    requestUsbPermission(dev)
                                }
                            }
                        }
                    }

                    ACTION_USB_PERMISSION -> {
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            scope.launch(Dispatchers.IO) { reinitUsb(dev, "USB permission granted") }
                        } else if (usbManager.hasPermission(dev)) {
                            // The shell service granted access meanwhile: the prompt's "deny" is moot.
                            Log.i(TAG, "prompt answered no, but ${describe(dev)} already has access")
                            scope.launch(Dispatchers.IO) { reinitUsb(dev, "USB access granted by the shell service") }
                        } else {
                            Pill.show(this@ControllerService, Pill.Kind.FAILED, "USB ACCESS DENIED")
                        }
                    }
                }
            }
        }

    private fun registerUsbReceiver() {
        val filter =
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_USB_PERMISSION)
            }
        try {
            ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (t: Throwable) {
            Log.w(TAG, "USB receiver: ${t.message}")
        }
    }

    /** After a reconnect request: if no controller has spoken by then, say what to do. */
    private fun armOutcomePill() {
        outcomeJob?.cancel()
        outcomeJob =
            scope.launch {
                delay(RECONNECT_OUTCOME_MS)
                if (!controllerOnline.get()) {
                    Pill.show(this@ControllerService, Pill.Kind.DISCONNECTED, "NO CONTROLLER · PRESS STEAM")
                }
            }
    }

    /**
     * ACTION_RECONNECT / requestReconnect: rebuild the controller link from scratch while the
     * service (and the game's view of the pad) stays up.
     */
    private fun fullReconnect() {
        if (reconnectJob?.isActive == true) return
        Pill.show(this, Pill.Kind.RECONNECTING, "RECONNECTING")
        Diagnostics.record(TAG, "reconnect requested")
        reconnectJob =
            scope.launch {
                // 1. The virtual pad: recreate it in the live user service, or rebind the service.
                if (!(mode.isVirtualDevice && (uinput.isReady || uinput.ensurePad()))) chooseInjectionMode()

                // 2. The link. Each path stops the old frame source first and then marks the
                // controller offline quietly (pad neutral), so the next frame - from the new link
                // only - is announced as CONNECTED.
                val transport = Prefs.getTransport(this@ControllerService)
                if (initializedTransport != null && initializedTransport != transport) teardownTransport()
                val ok =
                    when (transport) {
                        Transport.USB ->
                            withContext(Dispatchers.IO) {
                                usbMutex.withLock { connectUsbLocked(null, "reconnect requested", force = true) }
                            }

                        Transport.BLUETOOTH ->
                            if (initializedTransport == Transport.BLUETOOTH) {
                                btManager.reconnect() // closes the old GATT client synchronously
                                markControllerOffline("reconnect requested", pillText = null)
                                true
                            } else {
                                markControllerOffline("reconnect requested", pillText = null)
                                initBluetooth().also { if (it) acquireWakeLock() }
                            }
                    }
                if (ok) {
                    initializedTransport = transport
                    armOutcomePill()
                } else {
                    Pill.show(
                        this@ControllerService,
                        Pill.Kind.FAILED,
                        if (transport == Transport.USB) "NO STEAM CONTROLLER FOUND" else "BLUETOOTH CONTROLLER NOT FOUND",
                    )
                }
                publishLive()
            }
    }

    /** Close the current transport entirely (only for a reconnect that changes transport). */
    private suspend fun teardownTransport() {
        withContext(Dispatchers.IO) {
            usbMutex.withLock {
                reader?.stopAndJoin()
                reader = null
                markControllerOffline("transport change", pillText = null)
                usbManager.disconnect()
                activeUsbInterfaceId = null
                synchronized(configuredUsbInterfaces) { configuredUsbInterfaces.clear() }
            }
        }
        heartbeatJob?.cancel()
        heartbeatJob = null
        try {
            btManager.disconnect()
        } catch (_: Throwable) {
        }
        releaseWakeLock()
        initializedTransport = null
    }

    private fun updateUsbTrackpadHaptics(
        state: SteamControllerState,
        nowMs: Long,
    ) {
        val interfaceId = activeUsbInterfaceId ?: return
        val pipe =
            usbManager.inputPipes.firstOrNull { it.usbInterface.id == interfaceId } ?: return
        val endpoint = pipe.endpointOut ?: return
        val connection = usbManager.connection ?: return

        updateUsbTrackpadHaptic(
            connection,
            endpoint,
            left = true,
            touching = state.isButtonPressed(Buttons.TP_LT),
            clicked = state.isButtonPressed(Buttons.TP_LT_CLICK),
            x = state.leftPadX.toInt(),
            y = state.leftPadY.toInt(),
            tracker = leftPadHaptic,
            nowMs = nowMs,
        )
        updateUsbTrackpadHaptic(
            connection,
            endpoint,
            left = false,
            touching = state.isButtonPressed(Buttons.TP_RT),
            clicked = state.isButtonPressed(Buttons.TP_RT_CLICK),
            x = state.rightPadX.toInt(),
            y = state.rightPadY.toInt(),
            tracker = rightPadHaptic,
            nowMs = nowMs,
        )
    }

    private fun updateUsbTrackpadHaptic(
        connection: android.hardware.usb.UsbDeviceConnection,
        endpoint: android.hardware.usb.UsbEndpoint,
        left: Boolean,
        touching: Boolean,
        clicked: Boolean,
        x: Int,
        y: Int,
        tracker: PadHapticState,
        nowMs: Long,
    ) {
        var strongClick = false
        var shouldSend = false

        if (clicked != tracker.clicked) {
            strongClick = true
            shouldSend = true
            tracker.accumulatedDistance = 0.0
        } else if (touching && tracker.touching && !clicked) {
            val dx = (x - tracker.previousX).toDouble()
            val dy = (y - tracker.previousY).toDouble()
            tracker.accumulatedDistance += kotlin.math.hypot(dx, dy)
            if (tracker.accumulatedDistance >= 6500.0 && nowMs - tracker.lastSentAt >= 50L) {
                shouldSend = true
                tracker.accumulatedDistance = 0.0
            }
        } else if (!touching) {
            tracker.accumulatedDistance = 0.0
        }

        if (shouldSend) {
            val sent =
                SteamHidProtocol.sendTrackpadHaptic(
                    connection,
                    endpoint,
                    left,
                    strongClick,
                )
            if (sent) {
                tracker.lastSentAt = nowMs
                loggedUsbHapticFailure = false
            } else if (!loggedUsbHapticFailure) {
                Log.w(TAG, "USB trackpad haptic output failed")
                loggedUsbHapticFailure = true
            }
        }

        tracker.touching = touching
        tracker.clicked = clicked
        tracker.previousX = x
        tracker.previousY = y
    }

    private fun updateReportRate(nowMs: Long) {
        if (rateWindowStartedAt == 0L) rateWindowStartedAt = nowMs
        rateFrameCount++
        val elapsed = nowMs - rateWindowStartedAt
        if (elapsed < 1000L) return
        val hz = (rateFrameCount * 1000L / elapsed.coerceAtLeast(1L)).toInt()
        if (_serviceStateFlow.value.reportRateHz != hz) {
            _serviceStateFlow.value = _serviceStateFlow.value.copy(reportRateHz = hz)
        }
        if (ControllerLive.snapshot.value.reportRate != hz) ControllerLive.update { it.copy(reportRate = hz) }
        rateWindowStartedAt = nowMs
        rateFrameCount = 0
    }

    // Try a virtual-output backend first (a real InputDevice, so games honour it): uinput
    // where the shell UID may open /dev/uinput, uhid where it may not. Fall back to inject.
    private suspend fun chooseInjectionMode() {
        // 1. Virtual output device via the Shizuku user service.
        try {
            uinput.bind()
            for (attempt in 0 until 20) {
                if (uinput.isReady) break
                delay(150)
            }
            // Published before setMode() so the UI label can read both when the mode
            // change reaches it - see MainActivity.refreshModeLabel().
            _serviceStateFlow.value =
                _serviceStateFlow.value.copy(
                    backend = uinput.backendId,
                    backendDetail = uinput.backendDetail,
                )
            if (uinput.isReady) {
                setMode(
                    if (uinput.backendId == UInputNative.Backend.UHID) {
                        InjectionMode.UHID
                    } else {
                        InjectionMode.UINPUT
                    },
                )
                Log.i(
                    TAG,
                    "Using ${UInputNative.backendName(uinput.backendId)} virtual gamepad " +
                        "(${Prefs.getProfile(
                            this@ControllerService,
                        ).displayName}) - ${uinput.backendDetail}",
                )
                return
            }
            Log.w(
                TAG,
                "No virtual output backend (${uinput.backendDetail}), falling back to inject",
            )
            uinput.unbind()
        } catch (t: Throwable) {
            _serviceStateFlow.value =
                _serviceStateFlow.value.copy(
                    backendDetail = "virtual output error: ${t.message}",
                )
            Log.w(TAG, "virtual output bind failed: ${t.message}, falling back to inject")
        }

        // 2. Fallback: legacy injectInputEvent via Shizuku reflection
        if (legacyInjector.init()) {
            setMode(InjectionMode.SHIZUKU_INJECT)
            Log.i(TAG, "Using legacy Shizuku injectInputEvent (games may filter this)")
        } else {
            setMode(InjectionMode.NONE)
            Log.e(TAG, "No injection method available")
        }
    }

    private fun setMode(newMode: InjectionMode) {
        mode = newMode
        // Rumble travels over force feedback from the virtual device, which only the uinput
        // backend implements - and only while that device is actually alive.
        _serviceStateFlow.value =
            _serviceStateFlow.value.copy(
                mode = newMode,
                rumbleSupported = newMode.isVirtualDevice && uinput.rumbleSupported,
            )
        refreshNotification()
        publishLive()
    }

    private fun refreshNotification() {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr?.notify(NOTIFICATION_ID, buildNotification())
    }

    /**
     * Triggered by the notification action: switch between gamepad and Desktop modes.
     * Only meaningful in uinput mode; in fallback inject mode the profile is ignored.
     */
    // Guards against re-entrant taps on the "Switch profile" notification action while
    // a previous switch is still tearing down / recreating uinput devices.
    @Volatile private var profileSwitchInFlight = false

    private fun cycleProfile() {
        if (profileSwitchInFlight) {
            Log.w(TAG, "Cycle profile ignored: a switch is already in progress")
            return
        }
        profileSwitchInFlight = true

        val current = Prefs.getProfile(this)
        val next = current.next()
        Prefs.setProfile(this, next)
        Log.i(TAG, "Cycle profile: ${current.displayName} → ${next.displayName}")
        Pill.show(this, Pill.Kind.PROFILE, if (next.isMouseMode) "DESKTOP MODE" else "GAMEPAD MODE")

        // Reset baseline state immediately so any buttons "held" during the swap
        // don't get injected via the now-defunct device.
        confirmedState = null
        pendingButtons = 0
        pendingSinceMs = 0L

        // Run the actual device teardown/recreate off the service main thread -
        // it's a blocking binder + native ioctl pair that can take 100ms+.
        // Doing it on the main thread risks ANR / lost broadcast intents and was
        // the most likely cause of the "I can't change profile until I reboot" bug.
        scope.launch {
            try {
                if (mode.isVirtualDevice) {
                    val ok = uinput.switchProfile(next)
                    if (!ok) {
                        Log.w(
                            TAG,
                            "switchProfile failed; the gamepad may need a service restart",
                        )
                    }
                }
                _serviceStateFlow.value = _serviceStateFlow.value.copy(profileId = next.id)
                refreshNotification()
            } finally {
                profileSwitchInFlight = false
            }
        }
    }

    private fun handleState(state: SteamControllerState) {
        if (mode == InjectionMode.NONE) return

        // A settings screen is capturing input (input tester, "press a button"): the pad gets a
        // neutral frame and no action fires; the frame itself still went to ControllerLive.
        if (ControllerLive.isOutputSuppressed()) {
            if (mode.isVirtualDevice) {
                uinput.pushSuppressed(state)
            } else if (mode == InjectionMode.SHIZUKU_INJECT && !legacySuppressed) {
                // Injected keys stay down until a key-up: release what was confirmed, centre axes.
                legacySuppressed = true
                val held = confirmedState
                val idle = SteamControllerState()
                if (held != null) {
                    GamepadMapper.buttons(idle, held).forEach { (keyCode, down) -> legacyInjector.injectKey(keyCode, down) }
                }
                legacyInjector.injectMotion(GamepadMapper.axes(idle))
            }
            confirmedState = null
            return
        }
        legacySuppressed = false

        // Virtual devices can consume the controller's already-stable HID report directly.
        // Debouncing here added one or two full wireless frames to every button press.
        if (mode.isVirtualDevice) {
            confirmedState = state
            uinput.pushFrame(state)
            return
        }

        val now = android.os.SystemClock.uptimeMillis()

        // First frame = baseline for the legacy event-injection fallback.
        if (confirmedState == null) {
            confirmedState = state
            pendingButtons = state.buttons and INJECTABLE_MASK
            pendingSinceMs = now
            return
        }

        // Keep the old baseline for fallback injection before a confirmed change replaces it.
        val previousConfirmed = confirmedState!!
        val injectableBits = state.buttons and INJECTABLE_MASK
        val buttonsConfirmedThisFrame: Boolean
        if (injectableBits == pendingButtons) {
            buttonsConfirmedThisFrame =
                now - pendingSinceMs >= DEBOUNCE_MS &&
                injectableBits != (previousConfirmed.buttons and INJECTABLE_MASK)
            if (buttonsConfirmedThisFrame) confirmedState = state
        } else {
            pendingButtons = injectableBits
            pendingSinceMs = now
            buttonsConfirmedThisFrame = false
        }

        // Only the input-injection fallback is left at this point (re-read: mode can change
        // from another thread mid-frame).
        if (mode != InjectionMode.SHIZUKU_INJECT) return

        // Axes every frame for smoothness, with live-reloaded calibration
        legacyInjector.injectMotion(
            GamepadMapper.axes(
                state,
                Prefs.getLeftCalibration(this),
                Prefs.getRightCalibration(this),
            ),
        )
        // Buttons only on debounced change
        if (buttonsConfirmedThisFrame) {
            GamepadMapper.buttons(state, previousConfirmed).forEach { (keyCode, down) ->
                legacyInjector.injectKey(keyCode, down)
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        com.steamcontroller.android.hub.Background.stop("service")
        try {
            unregisterReceiver(usbReceiver)
        } catch (_: Throwable) {
        }
        com.steamcontroller.android.hub.GamehubSync.stop()
        Diagnostics.record(TAG, "service destroyed")
        releaseWakeLock()
        reader?.stop()
        // Continuous 0x8F pulse trains must be explicitly cancelled before either link closes.
        try {
            val interfaceId = activeUsbInterfaceId
            if (interfaceId != null) {
                usbManager.connection?.let {
                    SteamHidProtocol.sendRumble(it, interfaceId, 0, 0)
                }
            }
            btManager.sendRumble(0, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to stop controller haptics during teardown: ${t.message}")
        }
        scope.cancel()

        val teardown =
            Thread(
                {
                    try {
                        uinput.unbind()
                    } catch (_: Throwable) {
                    }
                },
                "uinput-unbind",
            ).apply {
                isDaemon = true
                start()
            }

        usbManager.disconnect()
        try {
            btManager.disconnect()
        } catch (_: Throwable) {
        }
        try {
            teardown.join(UNBIND_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        _serviceStateFlow.value =
            _serviceStateFlow.value.copy(
                mode = InjectionMode.NONE,
                link = LinkStatus(LinkState.DISCONNECTED, "service stopped"),
                batteryPercent = null,
                profileId = null,
                reportRateHz = 0,
            )
        initializing = false
        initializedTransport = null
        activeUsbInterfaceId = null
        synchronized(configuredUsbInterfaces) { configuredUsbInterfaces.clear() }
        // Reset the last controller frame so MainActivity's observer flips back to disconnected.
        _stateFlow.value = null
        controllerOnline.set(false)
        ControllerLive.update {
            it.copy(
                state = null,
                connected = false,
                link = ControllerLive.Link.NONE,
                batteryPercent = null,
                charging = null,
                serviceRunning = false,
                virtualPadUp = false,
                reportRate = 0,
            )
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows controller status and active emulation profile"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val profile = Prefs.getProfile(this)
        val battery = _serviceStateFlow.value.batteryPercent

        val modeText =
            when (mode) {
                InjectionMode.UINPUT,
                InjectionMode.UHID,
                -> getString(R.string.notif_mode_uinput, profile.displayName)

                InjectionMode.SHIZUKU_INJECT -> getString(R.string.notif_mode_inject)

                InjectionMode.NONE -> getString(R.string.notif_starting)
            }
        val title = getString(R.string.notification_title)
        // Append the active named profile name so the user can pull the shade and
        // see which preset auto-switch loaded for them.
        val activeProfileName =
            Prefs.getActiveNamedProfileId(this)?.let { id ->
                Prefs.listNamedProfiles(this).firstOrNull { it.id == id }?.name
            }
        val baseLine = if (battery != null) "$modeText  •  🔋 $battery%" else modeText
        val text = if (activeProfileName != null) "$baseLine\n🎯 $activeProfileName" else baseLine

        // Tap on the notification → open MainActivity
        val openIntent =
            Intent(this, com.steamcontroller.android.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val openPi =
            PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val stopIntent =
            PendingIntent.getService(
                this,
                1,
                Intent(this, ControllerService::class.java).apply { action = ACTION_STOP },
                PendingIntent.FLAG_IMMUTABLE,
            )
        val stopAction =
            Notification.Action
                .Builder(
                    android.graphics.drawable.Icon
                        .createWithResource(this, android.R.drawable.ic_media_pause),
                    getString(android.R.string.cancel),
                    stopIntent,
                ).build()

        // "Switch profile" action: cycles to the next emulated controller (virtual devices only).
        val nextProfileIntent =
            PendingIntent.getService(
                this,
                2,
                Intent(this, ControllerService::class.java).apply { action = ACTION_NEXT_PROFILE },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val nextLabel =
            if (mode.isVirtualDevice) {
                "→ ${profile.next().displayName}"
            } else {
                "Switch profile"
            }
        val switchAction =
            Notification.Action
                .Builder(
                    android.graphics.drawable.Icon
                        .createWithResource(this, android.R.drawable.ic_menu_rotate),
                    nextLabel,
                    nextProfileIntent,
                ).build()

        val icon =
            when (mode) {
                InjectionMode.UINPUT,
                InjectionMode.UHID,
                -> android.R.drawable.ic_media_play

                InjectionMode.SHIZUKU_INJECT -> android.R.drawable.ic_media_play

                InjectionMode.NONE -> android.R.drawable.stat_notify_sync
            }

        val builder =
            Notification
                .Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(icon)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)

        // Only show the switch action when a virtual device is active - pointless in fallback or starting
        if (mode.isVirtualDevice) {
            builder.addAction(switchAction)
        }
        builder.addAction(stopAction)

        return builder.build()
    }
}
