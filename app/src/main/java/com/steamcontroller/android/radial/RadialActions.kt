package com.steamcontroller.android.radial

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** What the radial adds to the virtual pad's frame: buttons, trigger pulls and a D-pad direction. */
data class RadialInject(val buttons: Int = 0, val lt: Int = 0, val rt: Int = 0, val dpadX: Int = 0, val dpadY: Int = 0) {
    companion object {
        val NONE = RadialInject()
    }
}

/** Where actions land: the virtual pad (merged into its frames) and the radial keyboard. */
interface RadialOutput {
    /** Replace the pad's radial overlay and push a frame now (taps must not wait for the next report). */
    fun pad(inject: RadialInject)

    /** One keyboard report; false when there is no keyboard (the shell service lacks one). */
    fun keyboard(modifiers: Int, keys: IntArray): Boolean
}

/**
 * Runs fired slices on one dedicated thread, so neither the input thread nor the main thread ever
 * waits on a binder call or a macro's timing. Every piece of state below is touched only on that
 * thread. Buttons and keys are reference counted, so a hold and a macro pressing the same button
 * do not release each other early. [releaseAll] (profile change, menus changed, controller gone)
 * cancels running macros and lets go of everything.
 */
class RadialActions(
    private val out: RadialOutput,
    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "radial-actions").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1 }
    },
    private val onProblem: (String) -> Unit = {},
) : RadialEngine.Fire {
    private val buttonCount = IntArray(PadButton.values().size)
    private val keyCount = HashMap<Int, Int>()
    private val keyOrder = ArrayList<Int>()
    private val toggled = HashSet<String>()
    private val holds = arrayOfNulls<Action>(2)
    private val holdKeys = arrayOfNulls<String>(2)

    private var macro: Running? = null
    private var keyboardWarned = false

    private class Running(val futures: MutableList<ScheduledFuture<*>> = ArrayList()) {
        val buttons = ArrayList<PadButton>()
        val keys = ArrayList<Int>()
    }

    override fun press(left: Boolean, slice: Slice) {
        exec.execute { safe { pressNow(left, slice) } }
    }

    override fun release(left: Boolean) {
        exec.execute { safe { releaseNow(left) } }
    }

    override fun releaseAll() {
        exec.execute { safe { releaseAllNow() } }
    }

    fun shutdown() {
        releaseAll()
        exec.shutdown()
    }

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            onProblem("action failed: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    // ── on the executor ──────────────────────────────────────────────────────────────

    private fun pressNow(left: Boolean, slice: Slice) {
        val side = if (left) 0 else 1
        val key = (if (left) "L:" else "R:") + slice.id
        when (val a = slice.action) {
            is Action.Button -> when (a.mode) {
                Mode.TAP -> {
                    buttonDown(a.button)
                    exec.schedule(Runnable { safe { buttonUp(a.button) } }, MacroPlan.TAP_MS, TimeUnit.MILLISECONDS)
                }
                Mode.HOLD -> { endHold(side); buttonDown(a.button); holds[side] = a; holdKeys[side] = key }
                Mode.TOGGLE -> if (toggled.remove(key)) buttonUp(a.button) else { toggled += key; buttonDown(a.button) }
            }
            is Action.Key -> when (a.mode) {
                Mode.TAP -> {
                    keysDown(a.keys)
                    exec.schedule(Runnable { safe { keysUp(a.keys) } }, MacroPlan.TAP_MS, TimeUnit.MILLISECONDS)
                }
                Mode.HOLD -> { endHold(side); keysDown(a.keys); holds[side] = a; holdKeys[side] = key }
                Mode.TOGGLE -> if (toggled.remove(key)) keysUp(a.keys) else { toggled += key; keysDown(a.keys) }
            }
            is Action.Macro -> startMacro(a)
        }
    }

    private fun releaseNow(left: Boolean) = endHold(if (left) 0 else 1)

    private fun endHold(side: Int) {
        when (val a = holds[side]) {
            is Action.Button -> buttonUp(a.button)
            is Action.Key -> keysUp(a.keys)
            else -> {}
        }
        holds[side] = null
        holdKeys[side] = null
    }

    private fun startMacro(a: Action.Macro) {
        // One macro at a time: firing again while one runs is ignored rather than interleaved.
        if (macro != null) return
        val run = Running()
        macro = run
        for (e in MacroPlan.timeline(a.steps)) {
            run.futures += exec.schedule(Runnable {
                safe {
                    if (macro !== run) return@safe
                    when (e) {
                        is MacroPlan.Event.Button -> if (e.down) { buttonDown(e.button); run.buttons += e.button } else if (run.buttons.remove(e.button)) buttonUp(e.button)
                        is MacroPlan.Event.Keys -> if (e.down) { keysDown(e.keys); run.keys += e.keys } else {
                            val mine = e.keys.filter { run.keys.remove(it) }
                            keysUp(mine)
                        }
                        is MacroPlan.Event.ReleaseAll -> endMacro(run)
                    }
                }
            }, e.atMs, TimeUnit.MILLISECONDS)
        }
    }

    /** Lets go of what the macro still holds. */
    private fun endMacro(run: Running) {
        if (macro !== run) return
        macro = null
        for (f in run.futures) f.cancel(false)
        for (b in run.buttons.toList()) buttonUp(b)
        keysUp(run.keys.toList())
        run.buttons.clear()
        run.keys.clear()
    }

    private fun releaseAllNow() {
        macro?.let { endMacro(it) }
        holds.fill(null)
        holdKeys.fill(null)
        toggled.clear()
        val hadPad = buttonCount.any { it > 0 }
        buttonCount.fill(0)
        val hadKeys = keyOrder.isNotEmpty()
        keyCount.clear()
        keyOrder.clear()
        if (hadPad) out.pad(RadialInject.NONE)
        if (hadKeys) sendKeys()
    }

    // ── pad ──────────────────────────────────────────────────────────────────────────

    private fun buttonDown(b: PadButton) {
        buttonCount[b.ordinal]++
        out.pad(inject())
    }

    private fun buttonUp(b: PadButton) {
        if (buttonCount[b.ordinal] == 0) return
        buttonCount[b.ordinal]--
        out.pad(inject())
    }

    private fun inject(): RadialInject {
        var mask = 0
        var lt = 0
        var rt = 0
        var dx = 0
        var dy = 0
        for (b in PadButton.values()) {
            if (buttonCount[b.ordinal] == 0) continue
            when (b) {
                PadButton.LT -> lt = 255
                PadButton.RT -> rt = 255
                PadButton.DPAD_UP -> dy -= 1
                PadButton.DPAD_DOWN -> dy += 1
                PadButton.DPAD_LEFT -> dx -= 1
                PadButton.DPAD_RIGHT -> dx += 1
                else -> mask = mask or RadialButtons.mask(b)
            }
        }
        return RadialInject(mask, lt, rt, dx.coerceIn(-1, 1), dy.coerceIn(-1, 1))
    }

    // ── keyboard ─────────────────────────────────────────────────────────────────────

    /** Pressed in order, one report per key, so modifiers are down before the key they modify. */
    private fun keysDown(keys: List<Int>) {
        for (k in keys) {
            val n = keyCount[k] ?: 0
            keyCount[k] = n + 1
            if (n == 0) { keyOrder += k; sendKeys() }
        }
    }

    /** Released in reverse order. */
    private fun keysUp(keys: List<Int>) {
        for (k in keys.asReversed()) {
            val n = keyCount[k] ?: continue
            if (n <= 1) { keyCount.remove(k); keyOrder.remove(k); sendKeys() } else keyCount[k] = n - 1
        }
    }

    private fun sendKeys() {
        val (mods, keys) = RadialKeys.report(keyOrder)
        if (!out.keyboard(mods, keys) && !keyboardWarned) {
            keyboardWarned = true
            onProblem("keyboard action could not be sent: the virtual keyboard is not available")
        }
    }
}

/** Contract button names → the virtual Xbox pad's button bits (LT/RT/D-pad are axes, see [RadialInject]). */
object RadialButtons {
    fun mask(b: PadButton): Int = when (b) {
        PadButton.A -> com.steamcontroller.android.uinput.XboxButtons.A
        PadButton.B -> com.steamcontroller.android.uinput.XboxButtons.B
        PadButton.X -> com.steamcontroller.android.uinput.XboxButtons.X
        PadButton.Y -> com.steamcontroller.android.uinput.XboxButtons.Y
        PadButton.LB -> com.steamcontroller.android.uinput.XboxButtons.LB
        PadButton.RB -> com.steamcontroller.android.uinput.XboxButtons.RB
        PadButton.LS -> com.steamcontroller.android.uinput.XboxButtons.THUMBL
        PadButton.RS -> com.steamcontroller.android.uinput.XboxButtons.THUMBR
        PadButton.START -> com.steamcontroller.android.uinput.XboxButtons.START
        PadButton.BACK -> com.steamcontroller.android.uinput.XboxButtons.SELECT
        PadButton.GUIDE -> com.steamcontroller.android.uinput.XboxButtons.MODE
        else -> 0
    }
}
