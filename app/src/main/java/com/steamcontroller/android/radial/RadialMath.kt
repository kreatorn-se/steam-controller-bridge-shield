package com.steamcontroller.android.radial

import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min

/**
 * Thumb position → highlighted slice. Pad coordinates are the controller's (−32768..32767, Y up).
 * Angles are degrees clockwise from 12 o'clock. Slice 0 is centred on [Menu.rotation] (0 = top),
 * the rest follow clockwise at equal widths.
 *
 * Two kinds of hysteresis keep the highlight from flickering on borders: a highlighted slice
 * keeps the highlight until the thumb is [HYSTERESIS_DEG] (at most a quarter of a slice) past its
 * edge, and once something is highlighted the deadzone shrinks to [DEADZONE_EXIT] of its size.
 */
object RadialMath {
    const val PAD_MAX = 32767f
    const val HYSTERESIS_DEG = 8f
    const val DEADZONE_EXIT = 0.85f

    /** Distance from the centre, 0..1 (the pad's corners clamp to 1). */
    fun radius(x: Int, y: Int): Float = min(1f, hypot(x.toFloat(), y.toFloat()) / PAD_MAX)

    /** 0..360, clockwise from the top, for a Y-up position. */
    fun angle(x: Int, y: Int): Float {
        val a = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
        return norm(a)
    }

    fun norm(deg: Float): Float {
        val r = deg % 360f
        return if (r < 0f) r + 360f else r
    }

    /** Smallest distance between two angles, 0..180. */
    fun diff(a: Float, b: Float): Float {
        val d = norm(a - b)
        return if (d > 180f) 360f - d else d
    }

    fun width(n: Int): Float = 360f / n

    /** Centre angle of slice [i]. */
    fun centre(i: Int, n: Int, rotation: Float): Float = norm(rotation + i * width(n))

    /** The slice under [angleDeg], ignoring the deadzone. */
    fun sliceAt(angleDeg: Float, n: Int, rotation: Float): Int {
        val w = width(n)
        val rel = norm(angleDeg - rotation + w / 2f)
        return (floor(rel / w).toInt()).coerceIn(0, n - 1)
    }

    /**
     * The highlighted slice for a thumb at ([x],[y]), or −1 inside the deadzone; [current] is the
     * slice highlighted now (−1 for none).
     */
    fun pick(x: Int, y: Int, n: Int, rotation: Float, deadzone: Float, current: Int): Int {
        if (n < 1) return -1
        val r = radius(x, y)
        if (current in 0 until n) {
            if (r < deadzone * DEADZONE_EXIT) return -1
            val a = angle(x, y)
            val keep = width(n) / 2f + min(HYSTERESIS_DEG, width(n) / 4f)
            if (diff(a, centre(current, n, rotation)) <= keep) return current
            return sliceAt(a, n, rotation)
        }
        if (r < deadzone) return -1
        return sliceAt(angle(x, y), n, rotation)
    }
}
