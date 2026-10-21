package com.steamcontroller.android.ui.tv

import com.steamcontroller.android.ui.tv.ShowKeys.Act
import com.steamcontroller.android.ui.tv.ShowKeys.Ctl
import com.steamcontroller.android.ui.tv.ShowKeys.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Show mode's key routing: the control bar must not steal the keys pictures and the filmstrip use. */
class ShowKeysTest {
    private val LEFT = 21
    private val RIGHT = 22
    private val UP = 19
    private val DOWN = 20
    private val L1 = 102
    private val R1 = 103
    private val L2 = 104
    private val R2 = 105
    private val Y = 100
    private val X = 99
    private val START = 108
    private val PLAY_PAUSE = 85
    private val MENU = 82

    @Test fun pictureKeysStepAndOpen() {
        assertEquals(Act.PREV, ShowKeys.down(Stage.PICTURE, LEFT, 0))
        assertEquals(Act.NEXT, ShowKeys.down(Stage.PICTURE, RIGHT, 0))
        assertEquals(Act.NEXT, ShowKeys.down(Stage.PICTURE, RIGHT, 5)) // held: scrubs
        assertEquals(Act.STRIP, ShowKeys.down(Stage.PICTURE, DOWN, 0))
        assertEquals(Act.BACK, ShowKeys.down(Stage.PICTURE, UP, 0))
        assertEquals(Act.NONE, ShowKeys.down(Stage.PICTURE, UP, 3)) // a held ↑ never closes
        assertEquals(Act.OPEN_BAR, ShowKeys.tap(Stage.PICTURE))
        assertEquals(Act.OPEN_BAR, ShowKeys.down(Stage.PICTURE, MENU, 0))
        assertEquals(Act.OPEN_BAR, ShowKeys.down(Stage.PICTURE, START, 0))
        assertEquals(Act.TOGGLE_PLAY, ShowKeys.down(Stage.PICTURE, PLAY_PAUSE, 0))
        assertEquals(Act.TOGGLE_PLAY, ShowKeys.down(Stage.PICTURE, X, 0))
        assertEquals(Act.BACK, ShowKeys.back(Stage.PICTURE))
    }

    @Test fun barTakesTheDpadButNotTheBumpersOrTriggers() {
        assertEquals(Act.BAR_LEFT, ShowKeys.down(Stage.BAR, LEFT, 0))
        assertEquals(Act.BAR_RIGHT, ShowKeys.down(Stage.BAR, RIGHT, 2))
        assertEquals(Act.PREV, ShowKeys.down(Stage.BAR, L1, 0))
        assertEquals(Act.NEXT, ShowKeys.down(Stage.BAR, R1, 0))
        assertEquals(Act.PREV_COLL, ShowKeys.down(Stage.BAR, L2, 0))
        assertEquals(Act.NEXT_COLL, ShowKeys.down(Stage.BAR, R2, 0))
        assertEquals(Act.FAVOURITE, ShowKeys.down(Stage.BAR, Y, 0))
        assertEquals(Act.BAR_PRESS, ShowKeys.tap(Stage.BAR))
        assertFalse(ShowKeys.holdable(Stage.BAR))
    }

    @Test fun barStepsBackOrOnToTheFilmstrip() {
        assertEquals(Act.STRIP, ShowKeys.down(Stage.BAR, DOWN, 0))
        assertEquals(Act.NONE, ShowKeys.down(Stage.BAR, DOWN, 1))
        assertEquals(Act.CLOSE_BAR, ShowKeys.down(Stage.BAR, UP, 0))
        assertEquals(Act.CLOSE_BAR, ShowKeys.back(Stage.BAR))
        assertEquals(Act.CLOSE_BAR, ShowKeys.down(Stage.BAR, MENU, 0))
        assertEquals(Act.TOGGLE_PLAY, ShowKeys.down(Stage.BAR, PLAY_PAUSE, 0))
    }

    @Test fun filmstripAndGridKeepTheirKeys() {
        assertEquals(Act.SEL_LEFT, ShowKeys.down(Stage.STRIP, LEFT, 0))
        assertEquals(Act.SEL_RIGHT, ShowKeys.down(Stage.STRIP, R1, 0))
        assertEquals(Act.GRID, ShowKeys.down(Stage.STRIP, DOWN, 0))
        assertEquals(Act.BACK, ShowKeys.down(Stage.STRIP, UP, 0))
        assertEquals(Act.SEL_DOWN, ShowKeys.down(Stage.GRID, DOWN, 4))
        assertEquals(Act.SEL_UP, ShowKeys.down(Stage.GRID, UP, 0))
        assertEquals(Act.PICK, ShowKeys.tap(Stage.STRIP))
        assertEquals(Act.PICK, ShowKeys.tap(Stage.GRID))
        assertEquals(Act.BACK, ShowKeys.back(Stage.GRID))
        assertEquals(Act.NONE, ShowKeys.down(Stage.STRIP, MENU, 0))
        assertTrue(ShowKeys.holdable(Stage.STRIP))
        assertTrue(ShowKeys.holdable(Stage.PICTURE))
    }

    @Test fun barSelectionStopsAtTheEnds() {
        assertEquals(Ctl.PLAY, ShowKeys.DEFAULT_CTL)
        assertEquals(Ctl.PREV, ShowKeys.move(Ctl.PLAY, -1))
        assertEquals(Ctl.PREV, ShowKeys.move(Ctl.PREV, -1))
        assertEquals(Ctl.ZOOM, ShowKeys.move(Ctl.FAV, 1))
        assertEquals(Ctl.ZOOM, ShowKeys.move(Ctl.ZOOM, 1))
    }

    @Test fun timerSteps() {
        val steps = intArrayOf(3, 5, 8, 12, 20, 30, 60)
        assertEquals(12, ShowKeys.nextInterval(8, 1, steps, wrap = true))
        assertEquals(3, ShowKeys.nextInterval(60, 1, steps, wrap = true))
        assertEquals(60, ShowKeys.nextInterval(60, 1, steps, wrap = false))
        assertEquals(3, ShowKeys.nextInterval(3, -1, steps, wrap = false))
        assertEquals(60, ShowKeys.nextInterval(3, -1, steps, wrap = true))
        // A screensaver interval between steps (20 s from the hub is a step; 25 s is not).
        assertEquals(30, ShowKeys.nextInterval(25, 1, steps, wrap = true))
        assertEquals(20, ShowKeys.nextInterval(25, -1, steps, wrap = false))
    }

    @Test fun barHidesOnlyWhilePlaying() {
        assertFalse(ShowKeys.barExpired(now = 4_999, lastInput = 0, playing = true))
        assertTrue(ShowKeys.barExpired(now = 5_000, lastInput = 0, playing = true))
        assertFalse(ShowKeys.barExpired(now = 60_000, lastInput = 0, playing = false))
    }
}
