package com.steamcontroller.android.radial

import com.steamcontroller.android.radial.MacroPlan.Event
import org.junit.Assert.assertEquals
import org.junit.Test

class MacroPlanTest {
    @Test fun timelineAndDuration() {
        val steps = listOf(
            Step.Button(PadButton.Y, StepAct.TAP),
            Step.Wait(150),
            Step.Keys(listOf(0xE0, 0x0C), StepAct.DOWN),
            Step.Wait(40),
            Step.Keys(listOf(0xE0, 0x0C), StepAct.UP),
            Step.Button(PadButton.LB, StepAct.DOWN),
        )
        assertEquals(60L + 150 + 40, MacroPlan.durationMs(steps))
        assertEquals(
            listOf(
                Event.Button(0, PadButton.Y, true),
                Event.Button(60, PadButton.Y, false),
                Event.Keys(210, listOf(0xE0, 0x0C), true),
                Event.Keys(250, listOf(0xE0, 0x0C), false),
                Event.Button(250, PadButton.LB, true),
                // LB is still down at the end: released with everything else.
                Event.ReleaseAll(250),
            ),
            MacroPlan.timeline(steps),
        )
    }

    @Test fun limits() {
        assertEquals(32, MacroPlan.MAX_STEPS)
        assertEquals(10, MacroPlan.MIN_WAIT_MS)
        assertEquals(5000, MacroPlan.MAX_WAIT_MS)
        assertEquals(10_000L, MacroPlan.MAX_TOTAL_MS)
        // Taps count their hold time.
        assertEquals(32 * 60L, MacroPlan.durationMs(List(32) { Step.Keys(listOf(0x04), StepAct.TAP) }))
        // The worst valid macro still ends by the limit.
        val longest = listOf(Step.Wait(5000), Step.Wait(5000))
        assertEquals(10_000L, MacroPlan.timeline(longest).last().atMs)
    }
}
