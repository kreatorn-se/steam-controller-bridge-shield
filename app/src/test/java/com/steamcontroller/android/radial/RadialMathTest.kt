package com.steamcontroller.android.radial

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class RadialMathTest {
    /** A thumb at [deg] clockwise from the top, [r] of the pad radius (Y up, like the controller). */
    private fun at(deg: Float, r: Float = 0.8f): Pair<Int, Int> {
        val a = Math.toRadians(deg.toDouble())
        return (sin(a) * r * 32767).toInt() to (cos(a) * r * 32767).toInt()
    }

    private fun pick(deg: Float, n: Int, rot: Float = 0f, r: Float = 0.8f, dz: Float = 0.35f, cur: Int = -1): Int {
        val (x, y) = at(deg, r)
        return RadialMath.pick(x, y, n, rot, dz, cur)
    }

    @Test fun anglesAreClockwiseFromTheTop() {
        assertEquals(0f, RadialMath.angle(0, 1000), 0.01f)
        assertEquals(90f, RadialMath.angle(1000, 0), 0.01f)
        assertEquals(180f, RadialMath.angle(0, -1000), 0.01f)
        assertEquals(270f, RadialMath.angle(-1000, 0), 0.01f)
    }

    @Test fun sliceZeroIsCentredOnTheTopForEveryCount() {
        for (n in 2..12) {
            val w = 360f / n
            for (i in 0 until n) {
                assertEquals("n=$n centre of $i", i, pick(i * w, n))
                assertEquals("n=$n just inside the start of $i", i, pick(i * w - w / 2 + 0.5f, n))
                assertEquals("n=$n just inside the end of $i", i, pick(i * w + w / 2 - 0.5f, n))
            }
        }
        // 4 slices: up, right, down, left.
        assertEquals(1, pick(90f, 4))
        assertEquals(3, pick(-90f, 4))
        assertEquals(0, pick(359f, 4))
    }

    @Test fun rotationMovesSliceZero() {
        assertEquals(0, pick(45f, 4, rot = 45f))
        assertEquals(1, pick(135f, 4, rot = 45f))
        assertEquals(0, pick(-22.5f, 8, rot = -22.5f))
        assertEquals(7, pick(-67.5f, 8, rot = -22.5f))
        assertEquals(0, pick(180f, 2, rot = 180f))
        assertEquals(0, pick(170f, 6, rot = -180f))
    }

    @Test fun deadzone() {
        assertEquals(-1, pick(0f, 4, r = 0.34f, dz = 0.35f))
        assertEquals(0, pick(0f, 4, r = 0.36f, dz = 0.35f))
        assertEquals(-1, pick(0f, 4, r = 0f))
        // Once highlighted, the deadzone shrinks to 85 % so a thumb resting on its edge holds.
        assertEquals(0, pick(0f, 4, r = 0.31f, dz = 0.35f, cur = 0))
        assertEquals(-1, pick(0f, 4, r = 0.29f, dz = 0.35f, cur = 0))
        // Corners of the pad (beyond the circle) clamp to the rim.
        assertEquals(1f, RadialMath.radius(32767, 32767), 0f)
    }

    @Test fun hysteresisHoldsTheHighlightPastTheBorder() {
        // 4 slices: border between 0 and 1 at 45°. Hysteresis is 8° (≤ a quarter slice).
        assertEquals(1, pick(46f, 4))
        assertEquals(0, pick(46f, 4, cur = 0))
        assertEquals(0, pick(52.9f, 4, cur = 0))
        assertEquals(1, pick(53.1f, 4, cur = 0))
        // And back the other way.
        assertEquals(1, pick(40f, 4, cur = 1))
        assertEquals(0, pick(36f, 4, cur = 1))
        // 12 slices are 30° wide: hysteresis is capped at a quarter slice (7.5°).
        assertEquals(0, pick(22f, 12, cur = 0))
        assertEquals(1, pick(23f, 12, cur = 0))
        // A jump straight across picks the slice under the thumb.
        assertEquals(2, pick(180f, 4, cur = 0))
        // Across 0°/360°.
        assertEquals(0, pick(-50f, 4, cur = 0))
        assertEquals(3, pick(-54f, 4, cur = 0))
    }
}
