package com.steamcontroller.android.usb

import android.hardware.usb.UsbDeviceConnection
import android.os.SystemClock
import android.util.Log
import com.steamcontroller.android.parser.SteamControllerState
import com.steamcontroller.android.parser.SteamReportParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * Reads the controller's interrupt endpoints. On the puck that is four controller slots
 * (ifaces 2..5): it probes all of them until one delivers, then reads only that one - until
 * [unlockInterface] is called (the controller went away), when it probes all slots again, since a
 * controller coming back may land on another slot.
 */
class HidReportReader(
    private val connection: UsbDeviceConnection,
    private val inputPipes: List<UsbConnectionManager.InputPipe>,
    private val onReport: (SteamControllerState?, ByteArray, Int) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val TAG = "HidReportReader"
    private val BUF_SIZE = 64
    private val PROBE_TIMEOUT_MS = 2
    private val ACTIVE_TIMEOUT_MS = 100

    // A detached device fails every transfer at once instead of timing out; that many in a row
    // means it is gone, and the loop backs off instead of spinning a core until the service
    // notices the detach.
    private val FAST_FAILS_BEFORE_BACKOFF = 64
    private val BACKOFF_MS = 50L

    private var job: Job? = null

    @Volatile private var activeInterfaceId: Int? = null
    private var totalReports = 0

    /** Forget the slot the controller was on and probe every slot again. */
    fun unlockInterface() {
        activeInterfaceId = null
    }

    fun start(scope: CoroutineScope) {
        job =
            scope.launch(Dispatchers.IO) {
                val pipes = inputPipes.toTypedArray()
                val buffers = Array(pipes.size) { ByteArray(BUF_SIZE) }
                Log.i(
                    TAG,
                    "Read loop started on " +
                        inputPipes.joinToString { pipe ->
                            "iface ${pipe.usbInterface.id}/ep 0x${pipe.endpoint.address.toString(16)}"
                        },
                )
                var fastFails = 0
                var reportedFailing = false
                while (isActive) {
                    val activeId = activeInterfaceId
                    var readAny = false
                    for (i in pipes.indices) {
                        if (!isActive) break
                        val pipe = pipes[i]
                        if (activeId != null && pipe.usbInterface.id != activeId) continue
                        val buffer = buffers[i]
                        val timeout = if (activeId == null) PROBE_TIMEOUT_MS else ACTIVE_TIMEOUT_MS
                        val startedAt = SystemClock.uptimeMillis()
                        val len = connection.bulkTransfer(pipe.endpoint, buffer, BUF_SIZE, timeout)
                        if (len > 0) {
                            readAny = true
                            fastFails = 0
                            reportedFailing = false
                            activeInterfaceId = pipe.usbInterface.id
                            totalReports++
                            if (totalReports == 1 || totalReports % 1000 == 0) {
                                Log.i(
                                    TAG,
                                    "Reports received: $totalReports, iface=${pipe.usbInterface.id}, " +
                                        "last ID=0x${(buffer[0].toInt() and 0xFF).toString(16)}, len=$len",
                                )
                            }
                            val raw = buffer.copyOf(len)
                            onReport(SteamReportParser.parse(raw), raw, pipe.usbInterface.id)
                        } else if (len < 0 && SystemClock.uptimeMillis() - startedAt < (timeout / 2).coerceAtLeast(1)) {
                            fastFails++ // failed well before its timeout: not a timeout at all
                        } else {
                            fastFails = 0
                        }
                    }
                    if (!readAny) {
                        if (fastFails >= FAST_FAILS_BEFORE_BACKOFF) {
                            if (!reportedFailing) {
                                reportedFailing = true
                                onError("transfers failing immediately - device gone?")
                            }
                            delay(BACKOFF_MS)
                        } else {
                            yield() // 0 = empty read, -1 = timeout: both normal
                        }
                    }
                }
                Log.i(TAG, "Read loop stopped after $totalReports reports")
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Stop and wait (bounded) for the loop to leave its last transfer, before closing the device. */
    suspend fun stopAndJoin() {
        val j = job ?: return
        job = null
        withTimeoutOrNull(500) { j.cancelAndJoin() }
    }
}
