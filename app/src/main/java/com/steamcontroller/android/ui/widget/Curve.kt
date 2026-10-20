package com.steamcontroller.android.ui.widget

import kotlin.math.pow

/**
 * The stick response the runtime applies (contract in the overhaul brief): radial inner deadzone
 * from calibration, outer deadzone = deflection that already counts as full, then an exponent.
 * All inputs and outputs are 0..1 magnitudes.
 */
object Curve {
    fun stick(mag: Float, inner: Float, outer: Float, exponent: Float): Float {
        if (mag <= inner) return 0f
        val span = (outer - inner).coerceAtLeast(0.01f)
        val t = ((mag - inner) / span).coerceIn(0f, 1f)
        return t.pow(exponent)
    }

    /**
     * Trackpad pointer gain as a function of finger speed (0..1 of a fast swipe), normalised so a
     * fast swipe maps to 1: 0 = linear, higher = slow movements finer, fast ones unchanged.
     */
    fun padAccel(speed: Float, accel: Float): Float {
        val s = speed.coerceIn(0f, 1f)
        return s * (1f + accel * s) / (1f + accel)
    }

    /** Gyro: deadzone (fraction of the plotted range) then the response exponent. */
    fun gyro(rate: Float, deadzone: Float, exponent: Float): Float {
        if (rate <= deadzone) return 0f
        return ((rate - deadzone) / (1f - deadzone).coerceAtLeast(0.01f)).coerceIn(0f, 1f).pow(exponent)
    }

    data class StickPreset(val name: String, val inner: Int, val outer: Int, val exponent: Float)

    val PRESETS = listOf(
        StickPreset("Precise", 6, 95, 1.8f),
        StickPreset("Default", 8, 100, 1.0f),
        StickPreset("Aggressive", 10, 85, 0.75f),
    )
}
