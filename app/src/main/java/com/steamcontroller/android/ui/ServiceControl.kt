package com.steamcontroller.android.ui

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.R
import com.steamcontroller.android.Transport
import com.steamcontroller.android.bt.BluetoothHidManager
import com.steamcontroller.android.service.ControllerService
import com.steamcontroller.android.shizuku.ShizukuStarterService
import com.steamcontroller.android.ui.pill.Pill
import com.steamcontroller.android.usb.UsbConnectionManager
import rikka.shizuku.Shizuku

/**
 * Starting, stopping and reconnecting the bridge, and getting Shizuku ready: everything the old
 * main screen did behind its Start button and status card, without the layout. Owned by the
 * activity (USB permission broadcasts and the Shizuku permission callback need one).
 */
class ServiceControl(private val activity: AppCompatActivity, private val onChanged: () -> Unit) {
    private val tag = "ServiceControl"
    private val usbPermissionAction = "com.steamcontroller.android.USB_PERMISSION"
    private val shizukuPackage = ShizukuStarterService.SHIZUKU_PACKAGE
    private val tvSettingsStubPackage = "com.google.android.tv.frameworkpackagestubs"
    private val shizukuRequestCode = 1001
    private val btPermissionRequest = 9002

    /** What the user asked for; the live state comes from the service flows. */
    var requestedRunning = ControllerService.isRunning
        private set

    private var onBtReady: (() -> Unit)? = null
    private var onBtDenied: (() -> Unit)? = null
    private var btAsked = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                usbPermissionAction -> {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)?.let { startUsb(it) }
                    } else {
                        Pill.show(activity, Pill.Kind.FAILED, "USB permission denied")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)?.let { onDeviceAttached(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    // The service handles detach itself (pill, re-open on replug, fall back to the
                    // other device). Stopping it here would also disarm auto-start.
                    log("Controller disconnected")
                    onChanged()
                }
            }
        }
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) {
            log("Shizuku permission granted")
            onChanged()
            startForTransport()
        } else {
            Pill.show(activity, Pill.Kind.FAILED, activity.getString(R.string.shizuku_permission_denied))
        }
    }

    fun register() {
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        val filter = IntentFilter().apply {
            addAction(usbPermissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(activity, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun unregister() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        try { activity.unregisterReceiver(usbReceiver) } catch (_: Throwable) {}
    }

    fun handleIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)?.let { onDeviceAttached(it) }
        }
    }

    fun syncFromService(running: Boolean) { requestedRunning = running }

    fun toggle(running: Boolean) = if (running) stop() else start()

    /** Start: Shizuku first (install / start / permission), then the chosen transport. */
    fun start() {
        when {
            !shizukuRunning() -> {
                Pill.show(activity, Pill.Kind.FAILED, "Shizuku is not running")
                startShizuku()
            }
            !shizukuPermitted() -> Shizuku.requestPermission(shizukuRequestCode)
            else -> startForTransport()
        }
    }

    fun stop() {
        activity.startService(Intent(activity, ControllerService::class.java).setAction(ControllerService.ACTION_STOP))
        requestedRunning = false
        log("Service stopped")
        onChanged()
    }

    /**
     * Reconnect the controller. Bluetooth reconnects in place; USB restarts the service, which
     * covers a stalled handshake as well as a sleeping controller.
     */
    fun reconnect() {
        if (ControllerService.isRunning) {
            // Full re-init without stopping; the service shows RECONNECTING and the outcome.
            ControllerService.requestReconnect(activity)
            return
        }
        start()
    }

    fun testRumble() {
        if (!ControllerService.isRunning) {
            Pill.show(activity, Pill.Kind.INFO, "Start the service first")
            return
        }
        activity.startService(Intent(activity, ControllerService::class.java).setAction(ControllerService.ACTION_TEST_RUMBLE))
    }

    private fun startForTransport() {
        when (Prefs.getTransport(activity)) {
            Transport.USB -> startUsbIfPresent()
            Transport.BLUETOOTH -> startBluetooth()
        }
    }

    private fun startBluetooth() {
        if (Prefs.getBluetoothAddress(activity) == null) {
            Pill.show(activity, Pill.Kind.INFO, "Pick a paired controller first")
            return
        }
        Prefs.setUserStoppedService(activity, false)
        activity.startForegroundService(Intent(activity, ControllerService::class.java))
        requestedRunning = true
        log("Service started (Bluetooth)")
        onChanged()
    }

    private fun startUsbIfPresent() {
        val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = usb.deviceList.values.firstOrNull(UsbConnectionManager::isSupportedDevice)
        if (device == null) {
            Pill.show(activity, Pill.Kind.FAILED, "No Steam Controller detected")
            return
        }
        if (usb.hasPermission(device)) {
            startUsb(device)
        } else {
            val pi = PendingIntent.getBroadcast(
                activity, 0, Intent(usbPermissionAction).setPackage(activity.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            usb.requestPermission(device, pi)
        }
    }

    private fun onDeviceAttached(device: UsbDevice) {
        if (!UsbConnectionManager.isSupportedDevice(device)) return
        log("Steam Controller attached")
        start()
    }

    private fun startUsb(device: UsbDevice) {
        Prefs.setUserStoppedService(activity, false)
        activity.startForegroundService(Intent(activity, ControllerService::class.java).putExtra(ControllerService.EXTRA_DEVICE, device))
        requestedRunning = true
        log("Service started")
        onChanged()
    }

    // ── Shizuku ───────────────────────────────────────────────────────────────────────

    enum class ShizukuState { READY, NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION }

    fun shizukuState(): ShizukuState = when {
        !isShizukuInstalled() -> ShizukuState.NOT_INSTALLED
        !shizukuRunning() -> ShizukuState.NOT_RUNNING
        !shizukuPermitted() -> ShizukuState.NO_PERMISSION
        else -> ShizukuState.READY
    }

    /** The one "fix it" action for whatever Shizuku needs next. */
    fun fixShizuku() {
        when (shizukuState()) {
            ShizukuState.NOT_INSTALLED -> openShizukuInstall()
            ShizukuState.NOT_RUNNING -> startShizuku()
            ShizukuState.NO_PERMISSION -> Shizuku.requestPermission(shizukuRequestCode)
            ShizukuState.READY -> reconnect()
        }
    }

    private fun shizukuRunning() = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    private fun shizukuPermitted() = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    private fun isShizukuInstalled(): Boolean = try {
        activity.packageManager.getPackageInfo(shizukuPackage, 0); true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: Throwable) {
        true
    }

    private fun startShizuku() {
        if (!isShizukuInstalled()) { openShizukuInstall(); return }
        if (!ShizukuStarterService.isEnabledInSettings(activity)) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.shizuku_starter_prompt_title)
                .setMessage(R.string.shizuku_starter_prompt_message)
                .setPositiveButton(R.string.shizuku_starter_prompt_open_settings) { _, _ -> openAccessibilitySettings() }
                .setNeutralButton(R.string.shizuku_starter_prompt_open_shizuku) { _, _ -> openShizukuApp() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        Prefs.setUserStoppedService(activity, false)
        ShizukuStarterService.arm()
        Pill.show(activity, Pill.Kind.INFO, activity.getString(R.string.shizuku_starter_armed))
        openShizukuApp()
    }

    private fun openAccessibilitySettings() {
        val direct = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        val resolved = direct.resolveActivity(activity.packageManager)?.packageName
        val target = if (resolved != null && resolved != tvSettingsStubPackage) direct else Intent(Settings.ACTION_SETTINGS)
        try {
            activity.startActivity(target)
            if (target !== direct) Pill.show(activity, Pill.Kind.INFO, activity.getString(R.string.shizuku_starter_settings_path))
        } catch (_: Throwable) {
            Pill.show(activity, Pill.Kind.FAILED, activity.getString(R.string.shizuku_starter_settings_failed))
        }
    }

    private fun openShizukuApp() {
        val intent = activity.packageManager.getLaunchIntentForPackage(shizukuPackage)
        try {
            if (intent == null) throw IllegalStateException()
            activity.startActivity(intent)
        } catch (_: Throwable) {
            Pill.show(activity, Pill.Kind.FAILED, activity.getString(R.string.main_shizuku_open_failed))
        }
    }

    private fun openShizukuInstall() {
        for (target in listOf(
            "market://details?id=$shizukuPackage",
            "https://play.google.com/store/apps/details?id=$shizukuPackage",
            "https://github.com/RikkaApps/Shizuku/releases/latest",
        )) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                return
            } catch (_: Throwable) {
            }
        }
        Pill.show(activity, Pill.Kind.FAILED, activity.getString(R.string.main_shizuku_store_failed))
    }

    // ── Bluetooth ─────────────────────────────────────────────────────────────────────

    class BtDevice(val address: String, val name: String)

    /**
     * Runs [then] once the Bluetooth permissions are granted. Without them it asks at most once
     * per session on its own (a denial would otherwise re-prompt on every resume), and again only
     * when [explicit]; otherwise [denied] runs so the caller can offer a button.
     */
    fun withBluetooth(explicit: Boolean, denied: () -> Unit, then: () -> Unit) {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.BLUETOOTH_CONNECT
            if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.BLUETOOTH_SCAN
        }
        when {
            needed.isEmpty() -> then()
            btAsked && !explicit -> denied()
            else -> {
                btAsked = true
                onBtReady = then
                onBtDenied = denied
                activity.requestPermissions(needed.toTypedArray(), btPermissionRequest)
            }
        }
    }

    fun onPermissionsResult(requestCode: Int, grants: IntArray) {
        if (requestCode != btPermissionRequest) return
        if (grants.isNotEmpty() && grants.all { it == PackageManager.PERMISSION_GRANTED }) onBtReady?.invoke() else onBtDenied?.invoke()
        onBtReady = null
        onBtDenied = null
    }

    fun pairedControllers(): List<BtDevice>? {
        val mgr = activity.getSystemService(BluetoothManager::class.java)
        if (mgr?.adapter?.isEnabled != true) return null
        return BluetoothHidManager(activity).listPairedSteamControllers().map { dev: BluetoothDevice ->
            val name = try { dev.name } catch (_: SecurityException) { null } ?: "Steam Controller"
            BtDevice(dev.address, name)
        }
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9001)
        }
    }

    private fun log(msg: String) = Log.d(tag, msg)
}
