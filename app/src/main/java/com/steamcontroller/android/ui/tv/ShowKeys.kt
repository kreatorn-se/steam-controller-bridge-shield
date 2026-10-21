package com.steamcontroller.android.ui.tv

import android.view.KeyEvent

/**
 * Show mode's key routing and control-bar rules, kept free of views so the conflicts between the
 * picture, the control bar, the filmstrip and the grid can be tested as plain key sequences.
 *
 * - Picture: ←/→ and LB/RB step, ↓ the filmstrip, ↑ closes, OK (a tap) opens the control bar,
 *   Play/Pause toggles playback and shows the bar.
 * - Control bar: ←/→ move along it, OK presses the selected control, LB/RB still step pictures,
 *   ↓ goes on to the filmstrip, ↑ / B / Menu hide the bar.
 * - Filmstrip and grid: as before (←/→ select, ↓ grid, ↑ / B one step back, OK jumps).
 * - Everywhere: Y favourite, LT/RT (and media prev/next) collections, +/− time per picture.
 *
 * OK and Back are routed on release by ShowMode (a held OK opens the hold menu instead); this
 * class tells what their tap means in each stage.
 */
object ShowKeys {
    enum class Stage { PICTURE, BAR, STRIP, GRID }

    enum class Act {
        NONE,
        /** Previous / next picture. */
        PREV, NEXT,
        /** Show the control bar (selection on play/pause). */
        OPEN_BAR,
        /** Hide the control bar, back to the picture. */
        CLOSE_BAR,
        /** Move the bar's selection. */
        BAR_LEFT, BAR_RIGHT,
        /** Press the selected bar control. */
        BAR_PRESS,
        /** Play / pause (and show the bar so the state is visible). */
        TOGGLE_PLAY,
        /** Open the filmstrip; from the filmstrip, the grid. */
        STRIP, GRID,
        /** Selection in the filmstrip or grid. */
        SEL_LEFT, SEL_RIGHT, SEL_UP, SEL_DOWN,
        /** Jump to the selected thumbnail. */
        PICK,
        /** One step back: grid → filmstrip → picture → close. */
        BACK,
        FAVOURITE, PREV_COLL, NEXT_COLL, LONGER, SHORTER,
    }

    /** The controls on the bar, left to right. */
    enum class Ctl { PREV, PLAY, NEXT, TIMER, FAV, ZOOM }

    /** Where the bar's selection lands when it opens. */
    val DEFAULT_CTL = Ctl.PLAY

    /** The bar hides after this long without input while playing; it stays while paused. */
    const val BAR_HIDE_MS = 5_000L

    fun isSelect(k: Int) = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER ||
        k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A
    fun isBack(k: Int) = k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_BUTTON_B || k == KeyEvent.KEYCODE_ESCAPE

    private fun isPlayPause(k: Int) = k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_MEDIA_PLAY ||
        k == KeyEvent.KEYCODE_MEDIA_PAUSE || k == KeyEvent.KEYCODE_SPACE || k == KeyEvent.KEYCODE_BUTTON_X
    private fun isMenu(k: Int) = k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_BUTTON_START
    private fun isPrevColl(k: Int) = k == KeyEvent.KEYCODE_BUTTON_L2 || k == KeyEvent.KEYCODE_MEDIA_PREVIOUS || k == KeyEvent.KEYCODE_PAGE_UP
    private fun isNextColl(k: Int) = k == KeyEvent.KEYCODE_BUTTON_R2 || k == KeyEvent.KEYCODE_MEDIA_NEXT || k == KeyEvent.KEYCODE_PAGE_DOWN
    private fun isLonger(k: Int) = k == KeyEvent.KEYCODE_PLUS || k == KeyEvent.KEYCODE_NUMPAD_ADD || k == KeyEvent.KEYCODE_EQUALS || k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
    private fun isShorter(k: Int) = k == KeyEvent.KEYCODE_MINUS || k == KeyEvent.KEYCODE_NUMPAD_SUBTRACT || k == KeyEvent.KEYCODE_MEDIA_REWIND

    /**
     * A key press (ACTION_DOWN, [repeat] its repeat count) in [stage]. Steps and selection moves
     * repeat while held; everything else acts on the first press only. OK and Back are not routed
     * here (see [tap] and [back]).
     */
    fun down(stage: Stage, k: Int, repeat: Int): Act {
        val first = repeat == 0
        // Bumpers always step pictures (they scrub while held), whatever has the D-pad.
        if (k == KeyEvent.KEYCODE_BUTTON_L1) return if (stage == Stage.STRIP || stage == Stage.GRID) Act.SEL_LEFT else Act.PREV
        if (k == KeyEvent.KEYCODE_BUTTON_R1) return if (stage == Stage.STRIP || stage == Stage.GRID) Act.SEL_RIGHT else Act.NEXT
        when (k) {
            KeyEvent.KEYCODE_DPAD_LEFT -> return when (stage) {
                Stage.PICTURE -> Act.PREV
                Stage.BAR -> Act.BAR_LEFT
                Stage.STRIP, Stage.GRID -> Act.SEL_LEFT
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> return when (stage) {
                Stage.PICTURE -> Act.NEXT
                Stage.BAR -> Act.BAR_RIGHT
                Stage.STRIP, Stage.GRID -> Act.SEL_RIGHT
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> return when (stage) {
                Stage.PICTURE, Stage.BAR -> if (first) Act.STRIP else Act.NONE
                Stage.STRIP -> if (first) Act.GRID else Act.NONE
                Stage.GRID -> Act.SEL_DOWN
            }
            KeyEvent.KEYCODE_DPAD_UP -> return when (stage) {
                // Held ↑ must not close the show by accident.
                Stage.PICTURE, Stage.STRIP -> if (first) Act.BACK else Act.NONE
                Stage.BAR -> if (first) Act.CLOSE_BAR else Act.NONE
                Stage.GRID -> Act.SEL_UP
            }
        }
        if (!first) return Act.NONE
        return when {
            isPlayPause(k) -> Act.TOGGLE_PLAY
            isMenu(k) -> if (stage == Stage.BAR) Act.CLOSE_BAR else if (stage == Stage.PICTURE) Act.OPEN_BAR else Act.NONE
            k == KeyEvent.KEYCODE_BUTTON_Y -> Act.FAVOURITE
            isPrevColl(k) -> Act.PREV_COLL
            isNextColl(k) -> Act.NEXT_COLL
            isLonger(k) -> Act.LONGER
            isShorter(k) -> Act.SHORTER
            else -> Act.NONE
        }
    }

    /** A tap of OK (released before the hold menu fired). */
    fun tap(stage: Stage): Act = when (stage) {
        Stage.PICTURE -> Act.OPEN_BAR
        Stage.BAR -> Act.BAR_PRESS
        Stage.STRIP, Stage.GRID -> Act.PICK
    }

    /** Back / B released. */
    fun back(stage: Stage): Act = if (stage == Stage.BAR) Act.CLOSE_BAR else Act.BACK

    /** OK held in [stage] can open the hold menu (on the bar, OK presses its controls). */
    fun holdable(stage: Stage) = stage != Stage.BAR

    /** The bar's selection moved by [d], stopping at the ends. */
    fun move(sel: Ctl, d: Int): Ctl {
        val all = Ctl.values()
        return all[(sel.ordinal + d).coerceIn(0, all.size - 1)]
    }

    /**
     * Time per picture stepped by [d] through [steps]; [wrap] (the bar's timer button) goes from the
     * longest back to the shortest, +/− stop at the ends. A value between steps snaps up first.
     */
    fun nextInterval(cur: Int, d: Int, steps: IntArray, wrap: Boolean): Int {
        val i = steps.indexOfFirst { it >= cur }.let { if (it < 0) steps.size - 1 else it }
        val snapped = steps[i] != cur
        val n = when {
            snapped && d > 0 -> i
            wrap -> ((i + d) % steps.size + steps.size) % steps.size
            else -> (i + d).coerceIn(0, steps.size - 1)
        }
        return steps[n]
    }

    /** Whether the bar should hide now: only while playing, [BAR_HIDE_MS] after the last input. */
    fun barExpired(now: Long, lastInput: Long, playing: Boolean): Boolean = playing && now - lastInput >= BAR_HIDE_MS
}
