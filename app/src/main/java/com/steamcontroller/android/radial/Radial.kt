package com.steamcontroller.android.radial

import android.content.Context
import android.util.Log
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.hub.GamehubStore
import com.steamcontroller.android.parser.SteamControllerState
import com.steamcontroller.android.service.Diagnostics
import com.steamcontroller.android.uinput.UInputGamepad
import java.util.concurrent.Executors

/**
 * Radial menus while the controller service runs: the engine on the input path, the overlay, the
 * action executor and the active profile's menus. The service calls [start]/[stop], runs every
 * frame through [process], calls [reset] when the controller goes away and [reload] when the
 * active profile or the synced config changes.
 */
object Radial {
    private const val TAG = "Radial"

    @Volatile private var engine: RadialEngine? = null
    @Volatile private var actions: RadialActions? = null
    @Volatile private var overlay: RadialOverlay? = null
    @Volatile private var gamepad: UInputGamepad? = null
    @Volatile private var testMode = false
    @Volatile private var lastReport: String? = null

    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "radial-config").apply { isDaemon = true } }

    @Synchronized
    fun start(context: Context, gp: UInputGamepad) {
        if (engine != null) return
        val app = context.applicationContext
        gamepad = gp
        val out = object : RadialOutput {
            override fun pad(inject: RadialInject) {
                gp.radialInject = inject
                gp.repushForRadial()
            }

            override fun keyboard(modifiers: Int, keys: IntArray): Boolean = gp.keyboardSend(modifiers, keys)
        }
        val a = RadialActions(out, onProblem = { note(it) })
        val o = RadialOverlay(app)
        val e = RadialEngine(o, a)
        e.testMode = testMode
        actions = a
        overlay = o
        engine = e
        reload(app)
    }

    @Synchronized
    fun stop() {
        engine?.reset()
        actions?.shutdown()
        overlay?.dispose()
        gamepad?.radialInject = RadialInject.NONE
        engine = null
        actions = null
        overlay = null
        gamepad = null
    }

    /** The frame for the virtual pad: unchanged unless a menu owns a pad. Input thread. */
    fun process(state: SteamControllerState): SteamControllerState = engine?.process(state) ?: state

    /** Close menus and let go of everything held (controller gone, output suppressed). */
    fun reset() {
        engine?.reset()
    }

    /** Whether a menu owns that pad right now (the service skips its own pad haptics then). */
    fun owns(left: Boolean): Boolean = engine?.owns(left) == true

    /** One haptic tick per slice change, read by the service after the frame went out. */
    fun takeHaptic(left: Boolean): Boolean = engine?.takeHaptic(left) == true

    /** Settings' test mode: menus show on touch (a sample on a pad without one), nothing fires. */
    fun setTestMode(on: Boolean) {
        testMode = on
        engine?.testMode = on
        if (!on) engine?.reset()
    }

    val isRunning: Boolean get() = engine != null

    val isTestMode: Boolean get() = testMode

    /** The active profile's name and its menus as parsed (for the settings screen). Any thread. */
    fun active(context: Context): Pair<String?, ParsedRadial> {
        val id = Prefs.getActiveNamedProfileId(context)
        val p = id?.let { pid -> Prefs.listNamedProfiles(context).firstOrNull { it.id == pid } }
        val parsed = RadialParser.resolve(p?.radialJson, com.steamcontroller.android.backup.ConfigExtras.defaultRadial(context))
        val label = when {
            p == null -> null
            p.radialJson == null && !parsed.config.isEmpty -> "${p.name} (default menus)"
            else -> p.name
        }
        return (label ?: if (parsed.config.isEmpty) null else "Default menus") to parsed
    }

    /** Re-read the active profile's menus (profile switch, hub sync), off the calling thread. */
    fun reload(context: Context) {
        val app = context.applicationContext
        loader.execute {
            try {
                reloadNow(app)
            } catch (t: Throwable) {
                Log.w(TAG, "reload failed: ${t.message}")
            }
        }
    }

    private fun reloadNow(c: Context) {
        val (name, parsed) = active(c)
        val cfg = parsed.config
        engine?.setConfig(cfg)
        val report = buildString {
            append(name ?: "(no profile)").append(": ")
            append("left ").append(cfg.left?.let { "${it.slices.size} slices" } ?: "off")
            append(", right ").append(cfg.right?.let { "${it.slices.size} slices" } ?: "off")
            for (p in parsed.problems) append("; ").append(p)
        }
        if (report != lastReport) {
            lastReport = report
            note("menus · $report")
        }
        gamepad?.setKeyboardWanted(cfg.usesKeys)
        // Prefetch on apply: fetched once, cached on disk by id, rendered here (never on the main thread).
        val ids = cfg.iconIds
        val ready = RadialIcons.prepare(c, GamehubStore.url(c), ids)
        if (ids.isNotEmpty()) {
            Log.i(TAG, "icons ready $ready/${ids.size}")
            if (ready < ids.size) note("icons: ${ids.size - ready} of ${ids.size} not available (label only)")
        }
    }

    private fun note(line: String) {
        Log.i(TAG, line)
        try {
            Diagnostics.record("radial", line)
        } catch (_: Throwable) {
        }
    }
}
