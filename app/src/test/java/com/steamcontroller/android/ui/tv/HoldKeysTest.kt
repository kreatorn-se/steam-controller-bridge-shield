package com.steamcontroller.android.ui.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Key sequences through [HoldKeys] the way MainActivity dispatches them, with a stand-in for a
 * focused card (or a menu row) and for the system's BUTTON_A → DPAD_CENTER fallback key.
 */
class HoldKeysTest {
    private val A = 96          // KEYCODE_BUTTON_A
    private val CENTER = 23     // KEYCODE_DPAD_CENTER
    private val ENTER = 66      // KEYCODE_ENTER
    private val RIGHT = 22      // KEYCODE_DPAD_RIGHT

    private companion object {
        const val REPEATS_IN_HOLD = 7
    }

    private class Sim(val holdable: Boolean = true) {
        val keys = HoldKeys()
        var menuOpen = false
        var menusOpened = 0
        var pressed = false
        var clicks = 0
        var rowPressed = false
        var rowClicks = 0
        private var fallbackLatched: Int? = null // null: none, -1: unknown, else keycode

        /** The ring's timer completing. */
        fun timer() {
            if (keys.fire()) { pressed = false; menuOpen = true; menusOpened++ }
        }

        private fun isConfirm(k: Int) = k == 23 || k == 66 || k == 160

        /** Activity.dispatchKeyEvent; true when handled. */
        private fun dispatch(code: Int, down: Boolean, repeat: Int, canceled: Boolean): Boolean {
            if (menuOpen) {
                if (keys.menu(code, down, repeat)) return true
                if (!isConfirm(code)) return false
                if (down) { if (repeat == 0) rowPressed = true } else if (rowPressed) {
                    rowPressed = false; if (!canceled) rowClicks++
                }
                return true
            }
            when (keys.page(code, down, repeat, holdable)) {
                HoldKeys.Act.CONSUME -> { if (!down) pressed = false; return true }
                HoldKeys.Act.PASS, HoldKeys.Act.ARM, HoldKeys.Act.CANCEL -> {}
            }
            if (!isConfirm(code)) return false
            if (down) { if (repeat == 0) pressed = true } else if (pressed) {
                pressed = false; if (!canceled) clicks++
            }
            return true
        }

        /** A key from the device, with the input dispatcher's fallback behaviour for BUTTON_A. */
        fun key(code: Int, down: Boolean, repeat: Int = 0) {
            val handled = dispatch(code, down, repeat, false)
            if (code != 96) return
            val latched = fallbackLatched
            if (!down) fallbackLatched = null
            if (handled) {
                if (latched != null && latched != -1) { dispatch(latched, false, 0, true); fallbackLatched = null }
                return
            }
            val initialDown = down && repeat == 0
            if (latched == null && !initialDown) return
            val fb = if (initialDown) 23.also { fallbackLatched = it } else latched!!
            if (fb == -1) return
            dispatch(fb, down, repeat, false)
        }

        fun tap(code: Int) { key(code, true); key(code, false) }

        /**
         * Held past [HoldKeys.HOLD_MS]: Android repeats a held key after about 400 ms and then every
         * 50 ms, so 0.75 s is a press plus about seven repeats before the ring completes.
         */
        fun hold(code: Int, repeatsBefore: Int = REPEATS_IN_HOLD, repeatsAfter: Int = 10) {
            key(code, true)
            for (r in 1..repeatsBefore) key(code, true, r)
            timer()
            for (r in repeatsBefore + 1..repeatsBefore + repeatsAfter) key(code, true, r)
            key(code, false)
        }
    }

    @Test fun tapClicksForEverySelectKey() {
        for (k in listOf(A, CENTER, ENTER)) {
            val s = Sim()
            s.tap(k)
            assertEquals("tap $k", 1, s.clicks)
            assertEquals(0, s.menusOpened)
            assertFalse(s.keys.armed)
        }
    }

    @Test fun tapClicksOnViewsThatCannotBeHeld() {
        for (k in listOf(A, CENTER, ENTER)) {
            val s = Sim(holdable = false)
            s.tap(k)
            assertEquals(1, s.clicks)
        }
    }

    @Test fun holdIsThreeQuartersOfASecond() {
        assertEquals(750L, HoldKeys.HOLD_MS)
        // A press plus its repeats up to the ring completing, at Android's 400 ms + 50 ms cadence.
        assertEquals(REPEATS_IN_HOLD, ((HoldKeys.HOLD_MS - 400L) / 50L).toInt())
    }

    @Test fun slowTapWithRepeatsStillClicks() {
        for (k in listOf(A, CENTER, ENTER)) {
            val s = Sim()
            s.key(k, true)
            // Released at about 0.65 s: repeats have started, the ring has not completed.
            for (r in 1..5) s.key(k, true, r)
            s.key(k, false)
            s.timer() // late timer after release must not fire
            assertEquals(1, s.clicks)
            assertEquals(0, s.menusOpened)
        }
    }

    @Test fun holdOpensMenuAndClicksNothing() {
        for (k in listOf(A, CENTER, ENTER)) {
            val s = Sim()
            s.hold(k)
            assertEquals("hold $k", 1, s.menusOpened)
            assertEquals(0, s.clicks)
            assertEquals(0, s.rowClicks)
            assertFalse(s.rowPressed)
        }
    }

    @Test fun menuRowsTakeAFreshTapAfterTheHold() {
        for (k in listOf(A, CENTER, ENTER)) {
            val s = Sim()
            s.hold(k)
            s.tap(k)
            assertEquals(1, s.rowClicks)
            // and again
            s.tap(k)
            assertEquals(2, s.rowClicks)
        }
    }

    @Test fun pageTapsWorkAfterTheMenuCloses() {
        for (k in listOf(A, CENTER, ENTER)) {
            val s = Sim()
            s.hold(k)
            s.menuOpen = false
            s.tap(k)
            assertEquals(1, s.clicks)
        }
    }

    @Test fun anotherKeyCancelsTheHold() {
        val s = Sim()
        s.key(CENTER, true)
        s.key(RIGHT, true)
        s.timer()
        assertEquals(0, s.menusOpened)
        assertTrue(!s.keys.armed)
    }

    @Test fun mixedCodesDoNotLeakAClick() {
        // A held to the menu, then released while ENTER taps a row.
        val s = Sim()
        s.key(A, true)
        for (r in 1..REPEATS_IN_HOLD) s.key(A, true, r)
        s.timer()
        s.key(A, false)
        s.tap(ENTER)
        assertEquals(0, s.clicks)
        assertEquals(1, s.rowClicks)
    }
}
