package com.steamcontroller.android.ui.tv

import android.view.KeyEvent

/**
 * The key bookkeeping behind the hold-for-menu gesture, kept free of views so it can be tested
 * as plain key sequences.
 *
 * The gamepad's A arrives as KEYCODE_BUTTON_A. No view handles that code, so the system sends a
 * DPAD_CENTER "fallback" key right after it, and that fallback is what presses and clicks a card.
 * A is therefore always passed through untouched and the hold follows its DPAD_CENTER fallback:
 * that one arms the ring, has its repeats swallowed while the ring fills, and has its release
 * swallowed once the menu fired. Consuming A itself would make the system cancel the fallback,
 * so no tap would ever click (the 1.4.1 regression).
 */
class HoldKeys {
    enum class Act {
        /** Let the key through. */
        PASS,
        /** Swallow the key. */
        CONSUME,
        /** Start the ring on the focused item, and let the key through so the card shows pressed. */
        ARM,
        /** Stop the ring, and let the key through (a tap's release clicks as usual). */
        CANCEL,
    }

    var armed = false
        private set
    var fired = false
        private set

    /** A key while no overlay is open. [hasItem]: the focused view can be held. */
    fun page(code: Int, down: Boolean, repeat: Int, hasItem: Boolean): Act {
        if (!isSelect(code)) return if (armed && down) disarm(Act.CANCEL) else Act.PASS
        if (code == KeyEvent.KEYCODE_BUTTON_A) return Act.PASS
        if (down) {
            if (repeat == 0) {
                fired = false
                if (hasItem) { armed = true; return Act.ARM }
                return if (armed) disarm(Act.CANCEL) else Act.PASS
            }
            return if (armed || fired) Act.CONSUME else Act.PASS
        }
        // Release, including the canceled fallback release the system sends after a long press.
        if (fired) return Act.CONSUME
        if (armed) return disarm(Act.CANCEL)
        return Act.PASS
    }

    /** A key while the hold menu is open; true swallows it. */
    fun menu(code: Int, down: Boolean, repeat: Int): Boolean {
        if (!isSelect(code) || code == KeyEvent.KEYCODE_BUTTON_A) return false
        if (!fired) return false
        // The long press's own repeats and release must not reach the menu's rows.
        if (!down) { fired = false; return true }
        if (repeat > 0) return true
        fired = false // a fresh press acts on the menu
        return false
    }

    /** Arms from code (show mode drives its own presses). */
    fun arm() { armed = true; fired = false }

    /** The ring completed; true when it was still armed and the hold now counts as fired. */
    fun fire(): Boolean {
        if (!armed) return false
        armed = false
        fired = true
        return true
    }

    fun cancel() { armed = false }
    fun clearFired() { fired = false }

    private fun disarm(a: Act): Act { armed = false; return a }

    companion object {
        fun isSelect(k: Int) = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER ||
            k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A
    }
}
