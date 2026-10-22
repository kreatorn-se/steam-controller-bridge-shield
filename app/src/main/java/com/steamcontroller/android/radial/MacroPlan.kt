package com.steamcontroller.android.radial

/**
 * A macro as a timeline: each step's presses and releases at a time offset from the start. Taps
 * hold for [TAP_MS] and take that long; down/up are instant; waits add their time. The contract's
 * limits: 1..[MAX_STEPS] steps, waits [MIN_WAIT_MS]..[MAX_WAIT_MS], the whole macro at most
 * [MAX_TOTAL_MS]. Anything still held at the end is released ([Event.ReleaseAll]).
 */
object MacroPlan {
    const val TAP_MS = 60L
    const val MAX_STEPS = 32
    const val MIN_WAIT_MS = 10
    const val MAX_WAIT_MS = 5000
    const val MAX_TOTAL_MS = 10_000L

    sealed class Event {
        abstract val atMs: Long

        data class Button(override val atMs: Long, val button: PadButton, val down: Boolean) : Event()
        /** [keys] pressed in order (down) or released in reverse order (up). */
        data class Keys(override val atMs: Long, val keys: List<Int>, val down: Boolean) : Event()
        data class ReleaseAll(override val atMs: Long) : Event()
    }

    fun durationMs(steps: List<Step>): Long = steps.sumOf { s ->
        when (s) {
            is Step.Wait -> s.ms.toLong()
            is Step.Button -> if (s.act == StepAct.TAP) TAP_MS else 0L
            is Step.Keys -> if (s.act == StepAct.TAP) TAP_MS else 0L
        }
    }

    fun timeline(steps: List<Step>): List<Event> {
        val out = ArrayList<Event>()
        var t = 0L
        for (s in steps) {
            when (s) {
                is Step.Wait -> t += s.ms
                is Step.Button -> when (s.act) {
                    StepAct.DOWN -> out += Event.Button(t, s.button, true)
                    StepAct.UP -> out += Event.Button(t, s.button, false)
                    StepAct.TAP -> {
                        out += Event.Button(t, s.button, true)
                        t += TAP_MS
                        out += Event.Button(t, s.button, false)
                    }
                }
                is Step.Keys -> when (s.act) {
                    StepAct.DOWN -> out += Event.Keys(t, s.keys, true)
                    StepAct.UP -> out += Event.Keys(t, s.keys, false)
                    StepAct.TAP -> {
                        out += Event.Keys(t, s.keys, true)
                        t += TAP_MS
                        out += Event.Keys(t, s.keys, false)
                    }
                }
            }
        }
        out += Event.ReleaseAll(t)
        return out
    }
}
