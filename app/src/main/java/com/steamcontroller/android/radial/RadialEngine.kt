package com.steamcontroller.android.radial

import com.steamcontroller.android.parser.Buttons
import com.steamcontroller.android.parser.SteamControllerState

/**
 * The radial menus' input side, run on the input thread for every frame before the frame reaches
 * the virtual pad. Touching a pad that has a menu opens it; the thumb's angle outside the deadzone
 * highlights a slice; a pad CLICK fires the highlighted slice; lifting without a click closes it.
 * From the touch until the lift, that pad's position, touch and click are taken out of the frame,
 * so nothing else (pointer, scroll, gyro-on-touch, haptics) acts on it.
 *
 * Only cheap arithmetic happens here. The overlay is told what changed through [Ui] (which posts
 * to the main thread) and actions go to [Fire] (which runs them on its own executor), so input
 * latency is unaffected. Pure Kotlin, tested on the JVM.
 */
class RadialEngine(private val ui: Ui, private val fire: Fire) {
    /** The overlay. Implementations must return immediately (post, never draw here). */
    interface Ui {
        fun open(left: Boolean, menu: Menu, test: Boolean)
        fun highlight(left: Boolean, index: Int)
        /** The slice fired: flash it, then close. */
        fun fired(left: Boolean, index: Int)
        fun close(left: Boolean)
    }

    /** Actions. Implementations must return immediately. */
    interface Fire {
        /** The pad was clicked on [slice] (press). */
        fun press(left: Boolean, slice: Slice)
        /** The click that fired on this pad was let go (ends a hold). */
        fun release(left: Boolean)
        /** Let go of everything this pad's slices hold (menu changed, controller gone). */
        fun releaseAll()
    }

    enum class Phase { IDLE, OPEN, FIRED }

    private class Pad {
        var phase = Phase.IDLE
        var menu: Menu? = null
        var highlight = -1
        var clickWas = false
        var test = false
    }

    @Volatile var config: RadialConfig = RadialConfig.EMPTY
        private set

    /** Settings' test mode: the overlay shows (a sample when the pad has no menu) but nothing fires. */
    @Volatile var testMode: Boolean = false

    private val leftPad = Pad()
    private val rightPad = Pad()

    /** A slice change since the last [takeHaptic] (the service sends the tick after the frame). */
    @Volatile private var hapticLeft = false
    @Volatile private var hapticRight = false

    /** New menus (profile switch, hub sync). Anything open closes and every held input is let go. */
    @Synchronized
    fun setConfig(c: RadialConfig) {
        if (c == config) return
        config = c
        resetLocked()
    }

    /** The controller went away, or output is suppressed by a settings screen. */
    @Synchronized
    fun reset() = resetLocked()

    private fun resetLocked() {
        for (left in listOf(true, false)) {
            val p = pad(left)
            if (p.phase != Phase.IDLE) ui.close(left)
            p.phase = Phase.IDLE
            p.menu = null
            p.highlight = -1
            p.clickWas = false
        }
        fire.releaseAll()
    }

    fun phase(left: Boolean): Phase = pad(left).phase

    /** Whether the radial owns this pad right now (its normal output is suppressed). */
    fun owns(left: Boolean): Boolean = pad(left).phase != Phase.IDLE

    /** True once per slice change on that pad, when the menu asks for haptics. */
    fun takeHaptic(left: Boolean): Boolean {
        if (left) { if (!hapticLeft) return false; hapticLeft = false; return true }
        if (!hapticRight) return false
        hapticRight = false
        return true
    }

    private fun pad(left: Boolean) = if (left) leftPad else rightPad

    /** One frame in, the frame for the virtual pad out (the same object when nothing changes). */
    @Synchronized
    fun process(state: SteamControllerState): SteamControllerState {
        val c = config
        val test = testMode
        if (!test && c.isEmpty && leftPad.phase == Phase.IDLE && rightPad.phase == Phase.IDLE) return state
        val l = step(true, state, c, test)
        val r = step(false, state, c, test)
        if (!l && !r) return state
        var buttons = state.buttons
        if (l) buttons = buttons and (Buttons.TP_LT or Buttons.TP_LT_CLICK).inv()
        if (r) buttons = buttons and (Buttons.TP_RT or Buttons.TP_RT_CLICK).inv()
        val zero: Short = 0
        return state.copy(
            buttons = buttons,
            leftPadX = if (l) zero else state.leftPadX,
            leftPadY = if (l) zero else state.leftPadY,
            rightPadX = if (r) zero else state.rightPadX,
            rightPadY = if (r) zero else state.rightPadY,
        )
    }

    /** Advances one pad; true when the pad is the radial's this frame (suppress it). */
    private fun step(left: Boolean, s: SteamControllerState, c: RadialConfig, test: Boolean): Boolean {
        val p = pad(left)
        val touch = s.isButtonPressed(if (left) Buttons.TP_LT else Buttons.TP_RT)
        val click = s.isButtonPressed(if (left) Buttons.TP_LT_CLICK else Buttons.TP_RT_CLICK)
        val x = (if (left) s.leftPadX else s.rightPadX).toInt()
        val y = (if (left) s.leftPadY else s.rightPadY).toInt()
        val clickEdge = click && !p.clickWas
        val releaseEdge = !click && p.clickWas
        p.clickWas = click
        when (p.phase) {
            Phase.IDLE -> {
                // A touch opens the menu; a touch that starts with the pad already clicked does
                // not (the click belongs to whatever the pad does without the menu).
                if (!touch || click) return false
                val menu = c.menu(left) ?: (if (test) RadialSample.MENU else null) ?: return false
                p.menu = menu
                p.test = test
                p.highlight = -1
                p.phase = Phase.OPEN
                ui.open(left, menu, p.test)
                update(left, p, x, y)
                return true
            }
            Phase.OPEN -> {
                if (!touch && !click) {
                    p.phase = Phase.IDLE
                    ui.close(left)
                    return true // the lift frame itself stays suppressed
                }
                update(left, p, x, y)
                if (clickEdge && p.highlight >= 0) {
                    val slice = p.menu!!.slices[p.highlight]
                    p.phase = Phase.FIRED
                    ui.fired(left, p.highlight)
                    if (!p.test) fire.press(left, slice)
                }
                return true
            }
            Phase.FIRED -> {
                if (releaseEdge && !p.test) fire.release(left)
                // Consumed until the thumb lifts, so the menu does not pop straight back open.
                if (!touch && !click) p.phase = Phase.IDLE
                return true
            }
        }
    }

    private fun update(left: Boolean, p: Pad, x: Int, y: Int) {
        val m = p.menu ?: return
        // (0,0) is what the pad reports in the frame a touch starts or ends: no position yet.
        if (x == 0 && y == 0) return
        val h = RadialMath.pick(x, y, m.slices.size, m.rotation, m.deadzone, p.highlight)
        if (h == p.highlight) return
        p.highlight = h
        ui.highlight(left, h)
        if (h >= 0 && m.haptics) { if (left) hapticLeft = true else hapticRight = true }
    }
}

/** The menu test mode shows on a pad that has none: eight labelled slices, nothing fires. */
object RadialSample {
    val MENU: Menu = Menu(
        listOf("Map", "Inventory", "Quests", "Craft", "Mount", "Camp", "Photo", "Emote").mapIndexed { i, label ->
            Slice("t$i", label, null, null, Action.Button(PadButton.A, Mode.TAP))
        },
    )
}
