package com.steamcontroller.android.input

import com.steamcontroller.android.parser.Buttons
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Gyro steering: the controller held like a steering wheel. The angle it is turned from the pose
 * it had when steering switched on becomes left-stick X, so a game that steers with the left
 * stick (every console driving game, GTA included) gets a wheel without knowing about gyro.
 *
 * Unlike [GyroAim], which turns angular *rate* into camera speed, this reads an *angle*: hold the
 * controller turned 20 degrees and the stick stays at 20 degrees' worth. The IMU quaternion is
 * fused with the accelerometer, so roll stays put against gravity and does not drift.
 *
 * Axes follow GyroAim and GyroView: X right, Y forward through the triggers, Z out of the face.
 */
enum class SteerAxis(val id: Int, val displayName: String) {
    /** Turn it like a wheel in front of you (rotation about the forward axis). */
    ROLL(0, "Roll · like a wheel"),

    /** Turn it flat, like a dial on the table (rotation about the face axis). Drifts slowly. */
    YAW(1, "Yaw · flat turn");

    companion object {
        val ALL: Array<SteerAxis> = values()
        fun fromId(id: Int): SteerAxis = ALL.firstOrNull { it.id == id } ?: ROLL
    }
}

/** What switches steering on. Toggles flip on each press; holds steer while held. */
enum class SteerActivation(val id: Int, val displayName: String, val mask: Int, val toggle: Boolean) {
    TOGGLE_L5(0, "Tap L5 to toggle", Buttons.L5, true),
    TOGGLE_R5(1, "Tap R5 to toggle", Buttons.R5, true),
    HOLD_L5(2, "Hold L5", Buttons.L5, false),
    HOLD_R5(3, "Hold R5", Buttons.R5, false),
    HOLD_L4(4, "Hold L4", Buttons.L4, false),
    HOLD_R4(5, "Hold R4", Buttons.R4, false),
    ALWAYS(6, "Always on", 0, false);

    companion object {
        val ALL: Array<SteerActivation> = values()
        fun fromId(id: Int): SteerActivation = ALL.firstOrNull { it.id == id } ?: TOGGLE_L5
    }
}

data class GyroSteerTuning(
    val enabled: Boolean = false,
    val activation: SteerActivation = SteerActivation.TOGGLE_L5,
    val axis: SteerAxis = SteerAxis.ROLL,
    /** Degrees of turn that give full stick deflection. */
    val lockDeg: Float = 45f,
    /** Degrees around centre that stay straight ahead. */
    val deadzoneDeg: Float = 2f,
    /** Response exponent after the deadzone, 1 = linear, above 1 = finer near centre. */
    val curve: Float = 1f,
    /** 0 = raw, 0.9 = heavy. */
    val smoothing: Float = 0.15f,
    val invert: Boolean = false,
) {
    /** Values pulled into range, so a hand-edited profile cannot produce nonsense. */
    fun clamped(): GyroSteerTuning {
        val lock = lockDeg.orIf(45f).coerceIn(MIN_LOCK, MAX_LOCK)
        return copy(
            lockDeg = lock,
            deadzoneDeg = deadzoneDeg.orIf(2f).coerceIn(0f, minOf(MAX_DEADZONE, lock - 1f)),
            curve = curve.orIf(1f).coerceIn(0.5f, 3f),
            smoothing = smoothing.orIf(0.15f).coerceIn(0f, 0.9f),
        )
    }

    companion object {
        const val MIN_LOCK = 10f
        const val MAX_LOCK = 120f
        const val MAX_DEADZONE = 15f
        private fun Float.orIf(fallback: Float) = if (isNaN()) fallback else this
    }
}

class GyroSteer {
    /** Left-stick X contribution, -32767..32767 (positive = right). */
    var stickX: Int = 0
        private set

    /** Signed turn from centre in degrees after invert, before the curve; NaN without data. */
    var angleDeg: Float = Float.NaN
        private set

    /** Whether steering is currently switched on (after hold / toggle logic). */
    var engaged: Boolean = false
        private set

    private var toggledOn = false
    private var buttonWasDown = false
    private var haveCentre = false
    private var c0w = 1f
    private var c0x = 0f
    private var c0y = 0f
    private var c0z = 0f
    private var smoothed = 0f

    /** Forget everything: steering off, toggle off, no centre. */
    fun reset() {
        toggledOn = false
        buttonWasDown = false
        release()
    }

    /** Next update takes the controller's pose as straight ahead. */
    fun recenter() {
        haveCentre = false
        smoothed = 0f
    }

    /**
     * One controller report. [buttons] is the raw button mask, used for the activation button.
     * Returns [stickX] for convenience.
     */
    fun update(buttons: Int, qw: Short, qx: Short, qy: Short, qz: Short, tuning: GyroSteerTuning): Int {
        if (!tuning.enabled) {
            reset()
            return 0
        }
        val on = gate(buttons, tuning.activation)
        if (!on) {
            release()
            return 0
        }
        if (!engaged) {
            engaged = true
            recenter()
        }
        val angle = relativeAngleDeg(qw, qx, qy, qz, tuning.axis)
        if (angle.isNaN()) {
            angleDeg = Float.NaN
            stickX = 0
            return 0
        }
        val signed = if (tuning.invert) -angle else angle
        angleDeg = signed
        val target = shape(signed, tuning)
        val blend = (1f - tuning.smoothing).coerceIn(0.05f, 1f)
        smoothed += (target - smoothed) * blend
        stickX = (smoothed * 32767f).roundToInt().coerceIn(-32767, 32767)
        return stickX
    }

    private fun release() {
        engaged = false
        haveCentre = false
        smoothed = 0f
        stickX = 0
        angleDeg = Float.NaN
    }

    private fun gate(buttons: Int, activation: SteerActivation): Boolean {
        if (activation.mask == 0) return true
        val down = buttons and activation.mask != 0
        val pressed = down && !buttonWasDown
        buttonWasDown = down
        if (!activation.toggle) {
            toggledOn = false
            return down
        }
        if (pressed) toggledOn = !toggledOn
        return toggledOn
    }

    /** Turn about [axis] from the stored centre, in degrees; the centre is set if missing. */
    private fun relativeAngleDeg(qw: Short, qx: Short, qy: Short, qz: Short, axis: SteerAxis): Float {
        var w = qw / 32767f
        var x = qx / 32767f
        var y = qy / 32767f
        var z = qz / 32767f
        val len = sqrt(w * w + x * x + y * y + z * z)
        if (len < 0.5f) return Float.NaN
        w /= len; x /= len; y /= len; z /= len
        if (!haveCentre) {
            c0w = w; c0x = x; c0y = y; c0z = z
            haveCentre = true
        }
        // relative = conj(centre) * now, in the controller's own frame at the centre pose
        var rw = c0w * w + c0x * x + c0y * y + c0z * z
        var ry = c0w * y + c0x * z - c0y * w - c0z * x
        var rz = c0w * z - c0x * y + c0y * x - c0z * w
        if (rw < 0f) { rw = -rw; ry = -ry; rz = -rz }
        // Twist about one axis (swing-twist decomposition): angle = 2·atan2(component, w).
        return when (axis) {
            // +roll about +Y lowers the right side: steer right.
            SteerAxis.ROLL -> 2f * atan2(ry, rw) * RAD_TO_DEG
            // +yaw about +Z swings the right side forward, i.e. a left turn: flip it.
            SteerAxis.YAW -> -2f * atan2(rz, rw) * RAD_TO_DEG
        }
    }

    companion object {
        private const val RAD_TO_DEG = (180.0 / Math.PI).toFloat()

        /** Degrees → -1..1 stick: deadzone, then the curve up to full lock. Shared with the UI graph. */
        fun shape(angleDeg: Float, tuning: GyroSteerTuning): Float {
            val m = abs(angleDeg)
            val dz = tuning.deadzoneDeg
            if (m <= dz) return 0f
            val span = (tuning.lockDeg - dz).coerceAtLeast(1f)
            val t = ((m - dz) / span).coerceAtMost(1f)
            return t.pow(tuning.curve.coerceIn(0.5f, 3f)) * sign(angleDeg)
        }
    }
}
