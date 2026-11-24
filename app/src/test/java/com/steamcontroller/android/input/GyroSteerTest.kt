package com.steamcontroller.android.input

import com.steamcontroller.android.parser.Buttons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class GyroSteerTest {
    /** Quaternion for [deg] about a unit axis, in the controller's Int16 report format. */
    private fun q(deg: Double, ax: Double, ay: Double, az: Double): ShortArray {
        val h = Math.toRadians(deg) / 2
        val s = sin(h)
        fun i(v: Double) = (v * 32767).roundToInt().toShort()
        return shortArrayOf(i(cos(h)), i(ax * s), i(ay * s), i(az * s))
    }

    /** a then b, both in the controller's frame: a * b. */
    private fun mul(a: ShortArray, b: ShortArray): ShortArray {
        val aw = a[0] / 32767.0; val ax = a[1] / 32767.0; val ay = a[2] / 32767.0; val az = a[3] / 32767.0
        val bw = b[0] / 32767.0; val bx = b[1] / 32767.0; val by = b[2] / 32767.0; val bz = b[3] / 32767.0
        fun i(v: Double) = (v * 32767).roundToInt().toShort()
        return shortArrayOf(
            i(aw * bw - ax * bx - ay * by - az * bz),
            i(aw * bx + ax * bw + ay * bz - az * by),
            i(aw * by - ax * bz + ay * bw + az * bx),
            i(aw * bz + ax * by - ay * bx + az * bw),
        )
    }

    private val on = GyroSteerTuning(enabled = true, activation = SteerActivation.ALWAYS, smoothing = 0f, deadzoneDeg = 0f)

    private fun GyroSteer.feed(quat: ShortArray, t: GyroSteerTuning = on, buttons: Int = 0) =
        update(buttons, quat[0], quat[1], quat[2], quat[3], t)

    @Test fun rollRightOfCentreSteersRightProportionally() {
        val s = GyroSteer()
        s.feed(q(0.0, 0.0, 1.0, 0.0))
        s.feed(q(22.5, 0.0, 1.0, 0.0))
        assertEquals(22.5f, s.angleDeg, 0.1f)
        assertEquals(32767 / 2, s.stickX, 120)
        s.feed(q(-50.0, 0.0, 1.0, 0.0))
        assertEquals(-32767, s.stickX)
        s.feed(q(80.0, 0.0, 1.0, 0.0))
        assertEquals(32767, s.stickX) // past full lock stays at full lock
    }

    @Test fun centreIsWherePoseWasWhenEngaged() {
        val s = GyroSteer()
        // Held tilted 30° towards the player and already rolled 10° when steering switches on.
        val base = mul(q(30.0, 1.0, 0.0, 0.0), q(10.0, 0.0, 1.0, 0.0))
        s.feed(base)
        assertEquals(0, s.stickX)
        s.feed(mul(base, q(15.0, 0.0, 1.0, 0.0)))
        assertEquals(15f, s.angleDeg, 0.15f)
    }

    @Test fun pitchingTheControllerDoesNotSteer() {
        val s = GyroSteer()
        s.feed(q(0.0, 1.0, 0.0, 0.0))
        s.feed(q(40.0, 1.0, 0.0, 0.0))
        assertEquals(0f, s.angleDeg, 0.1f)
        assertEquals(0, s.stickX)
    }

    @Test fun yawAxisTurnsRightWhenTheRightSideComesBack() {
        val s = GyroSteer()
        val t = on.copy(axis = SteerAxis.YAW)
        s.feed(q(0.0, 0.0, 0.0, 1.0), t)
        s.feed(q(-20.0, 0.0, 0.0, 1.0), t)
        assertTrue(s.stickX > 0)
        s.feed(q(10.0, 0.0, 1.0, 0.0), t) // rolling is ignored on the yaw axis
        assertEquals(0, s.stickX)
    }

    @Test fun deadzoneCurveAndInvert() {
        val t = GyroSteerTuning(deadzoneDeg = 5f, lockDeg = 45f, curve = 2f)
        assertEquals(0f, GyroSteer.shape(4f, t), 0f)
        assertEquals(0.25f, GyroSteer.shape(25f, t), 1e-4f) // halfway through 5..45, squared
        assertEquals(-1f, GyroSteer.shape(-90f, t), 0f)
        val s = GyroSteer()
        val inv = on.copy(invert = true)
        s.feed(q(0.0, 0.0, 1.0, 0.0), inv)
        s.feed(q(20.0, 0.0, 1.0, 0.0), inv)
        assertTrue(s.stickX < 0)
    }

    @Test fun toggleRecentresEachTimeItTurnsOn() {
        val s = GyroSteer()
        val t = on.copy(activation = SteerActivation.TOGGLE_L5)
        s.feed(q(0.0, 0.0, 1.0, 0.0), t)
        assertFalse(s.engaged)
        s.feed(q(0.0, 0.0, 1.0, 0.0), t, Buttons.L5) // tap: on, centre here
        s.feed(q(20.0, 0.0, 1.0, 0.0), t)
        assertTrue(s.engaged)
        assertTrue(s.stickX > 0)
        s.feed(q(20.0, 0.0, 1.0, 0.0), t, Buttons.L5) // tap: off
        assertFalse(s.engaged)
        assertEquals(0, s.stickX)
        s.feed(q(20.0, 0.0, 1.0, 0.0), t)
        s.feed(q(20.0, 0.0, 1.0, 0.0), t, Buttons.L5) // on again: 20° is the new centre
        assertEquals(0, s.stickX)
    }

    @Test fun holdSteersOnlyWhileHeld() {
        val s = GyroSteer()
        val t = on.copy(activation = SteerActivation.HOLD_L4)
        s.feed(q(0.0, 0.0, 1.0, 0.0), t, Buttons.L4)
        s.feed(q(30.0, 0.0, 1.0, 0.0), t, Buttons.L4)
        assertTrue(s.stickX > 0)
        s.feed(q(30.0, 0.0, 1.0, 0.0), t)
        assertEquals(0, s.stickX)
    }

    @Test fun disabledOrNoDataGivesNothing() {
        val s = GyroSteer()
        s.feed(q(0.0, 0.0, 1.0, 0.0), on.copy(enabled = false))
        assertEquals(0, s.stickX)
        s.update(0, 0, 0, 0, 0, on)
        assertEquals(0, s.stickX)
        assertTrue(s.angleDeg.isNaN())
    }

    @Test fun smoothingEasesIn() {
        val s = GyroSteer()
        val t = on.copy(smoothing = 0.5f)
        s.feed(q(0.0, 0.0, 1.0, 0.0), t)
        s.feed(q(45.0, 0.0, 1.0, 0.0), t)
        assertEquals(32767 / 2, s.stickX, 2)
    }

    @Test fun jsonRoundTripAndClamping() {
        val t = GyroSteerTuning(true, SteerActivation.HOLD_R5, SteerAxis.YAW, 60f, 3f, 1.5f, 0.3f, true)
        assertEquals(t, GyroSteerJson.fromJson(GyroSteerJson.toJson(t)))
        val wild = GyroSteerJson.parse("""{"enabled":true,"lockDeg":500,"deadzoneDeg":-4,"curve":9,"smoothing":2,"activation":99}""")
        assertEquals(GyroSteerTuning.MAX_LOCK, wild.lockDeg, 0f)
        assertEquals(0f, wild.deadzoneDeg, 0f)
        assertEquals(3f, wild.curve, 0f)
        assertEquals(0.9f, wild.smoothing, 0f)
        assertEquals(SteerActivation.TOGGLE_L5, wild.activation)
        assertEquals(GyroSteerTuning(), GyroSteerJson.parse("not json"))
        assertEquals(GyroSteerTuning(), GyroSteerJson.parse(null))
    }
}
