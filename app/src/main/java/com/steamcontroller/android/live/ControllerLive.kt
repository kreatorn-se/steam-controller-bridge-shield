package com.steamcontroller.android.live

import android.os.SystemClock
import com.steamcontroller.android.parser.SteamControllerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * In-process bus between the runtime (ControllerService) and the settings UI.
 *
 * The service publishes every parsed frame and every link change; screens collect [snapshot]
 * (collect on the main thread with `repeatOnLifecycle(STARTED)` and sample/throttle to ~60 Hz
 * for drawing). Nothing here touches Android framework state, so it is cheap to call per frame.
 */
object ControllerLive {

    enum class Link { NONE, PUCK, USB, BLUETOOTH }

    data class Snapshot(
        /** Latest raw controller state, null until the first frame after a (re)connect. */
        val state: SteamControllerState? = null,
        /** True while the controller itself is talking to us (not merely the puck plugged in). */
        val connected: Boolean = false,
        val link: Link = Link.NONE,
        /** 0..100, null when the firmware has not reported it. */
        val batteryPercent: Int? = null,
        val charging: Boolean? = null,
        val firmware: String? = null,
        val serviceRunning: Boolean = false,
        /** Virtual Xbox pad exists in the shell-uid service. */
        val virtualPadUp: Boolean = false,
        val shizukuReady: Boolean = false,
        val activeProfileId: String? = null,
        val activeProfileName: String? = null,
        /** Frames per second over the last second, for the device panel. */
        val reportRate: Int = 0,
        val atMs: Long = 0L,
    )

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> get() = _snapshot

    // ── Runtime side ────────────────────────────────────────────────────────────────────
    // Both writers are compare-and-set loops (the frame thread and the supervisor write
    // concurrently; a plain read-modify-write here used to be able to drop the other's change).
    fun publishFrame(state: SteamControllerState) {
        val at = SystemClock.uptimeMillis()
        _snapshot.update { it.copy(state = state, connected = true, atMs = at) }
    }

    fun update(block: (Snapshot) -> Snapshot) {
        _snapshot.update { block(it).copy(atMs = SystemClock.uptimeMillis()) }
    }

    // ── UI side: input capture ──────────────────────────────────────────────────────────
    @Volatile private var suppressUntil = 0L

    /**
     * While active, the runtime sends a neutral frame to the virtual pad instead of the user's
     * input (so pressing B in the input tester does not back out of the screen). The UI must
     * re-arm it periodically (it lapses by itself so a crashed screen never leaves the pad dead).
     */
    fun suppressOutput(forMs: Long) { suppressUntil = SystemClock.uptimeMillis() + forMs.coerceIn(0, 10_000) }
    fun releaseOutput() { suppressUntil = 0L }
    fun isOutputSuppressed(): Boolean = SystemClock.uptimeMillis() < suppressUntil
}
