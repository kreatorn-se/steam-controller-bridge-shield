package com.steamcontroller.android.input

import kotlin.math.pow
import kotlin.math.sqrt

// Per-stick calibration applied to raw SC2026 Int16 values (-32768..32767)
// before they go to uinput / GamepadMapper.
data class StickCalibration(
    val centerX: Int = 0,
    val centerY: Int = 0,
    val deadzonePercent: Int = 8, // 0..100 - radial deadzone as % of full range
    val invertY: Boolean = false,
) {
    // Returns calibrated (x, y) in the same Int16 range, with deadzone applied radially.
    fun apply(
        rawX: Int,
        rawY: Int,
    ): Pair<Int, Int> {
        // 1. Subtract center offset
        var x = rawX - centerX
        var y = rawY - centerY

        // 2. Invert Y if requested
        if (invertY) y = -y

        // 3. Radial deadzone - if magnitude < deadzone, output zero;
        //    otherwise rescale so the deadzone edge becomes the new zero,
        //    keeping max deflection intact.
        val dz = (32767.0 * deadzonePercent / 100.0)
        if (dz > 0) {
            val mag = sqrt((x * x + y * y).toDouble())
            if (mag < dz) {
                return 0 to 0
            }
            val scale = (mag - dz) / (32767.0 - dz) * 32767.0 / mag
            x = (x * scale).toInt()
            y = (y * scale).toInt()
        }

        // Clamp to Int16
        x = x.coerceIn(-32767, 32767)
        y = y.coerceIn(-32767, 32767)
        return x to y
    }

    /**
     * [apply] plus the response curve, without allocating (the uinput path calls this per frame).
     *
     * After centring, inversion and the inner (radial) deadzone, the magnitude is rescaled so the
     * inner edge is 0 and [outerPercent] of full travel is 1, clamped to 0..1, raised to
     * [exponent], and put back along the original direction. With the defaults (outer 100 %,
     * exponent 1) this is exactly [apply], including its per-axis clamp on the diagonals.
     * Writes x to out[0] and y to out[1].
     */
    fun applyShaped(
        rawX: Int,
        rawY: Int,
        outerPercent: Int,
        exponent: Float,
        out: IntArray,
    ) {
        val x = (rawX - centerX).toDouble()
        val y = (if (invertY) -(rawY - centerY) else rawY - centerY).toDouble()
        val mag = sqrt(x * x + y * y)
        val inner = 32767.0 * deadzonePercent.coerceIn(0, 99) / 100.0
        if (mag == 0.0 || (inner > 0 && mag < inner)) {
            out[0] = 0
            out[1] = 0
            return
        }
        val shaped = outerPercent < 100 || exponent != 1f
        val scale: Double =
            if (shaped) {
                val outer = (32767.0 * outerPercent.coerceIn(1, 100) / 100.0).coerceAtLeast(inner + 1.0)
                var n = ((mag - inner) / (outer - inner)).coerceIn(0.0, 1.0)
                if (exponent != 1f) n = n.pow(exponent.toDouble())
                n * 32767.0 / mag
            } else if (inner > 0) {
                (mag - inner) / (32767.0 - inner) * 32767.0 / mag
            } else {
                1.0
            }
        out[0] = (x * scale).toInt().coerceIn(-32767, 32767)
        out[1] = (y * scale).toInt().coerceIn(-32767, 32767)
    }

    companion object {
        val DEFAULT = StickCalibration()
    }
}
