package com.steamcontroller.android.hub

/**
 * Pure arithmetic behind [CaptureLevels] (no Android types, so it runs in JVM unit tests).
 *
 * GeForce NOW's video layer reaches `screencap` in limited ("TV") range: black sits at 16 and
 * white at 235 per channel, never expanded to full range. That reads as lifted blacks, low
 * contrast and dull colour. UI layers drawn on top (the status pill, system overlays) are full
 * range, so a capture may still hold true 0 and 255 pixels: detection therefore looks at the 5th
 * percentile of luminance, never the minimum. (1.4.3 used the 1st percentile; on real GFN frames
 * a dark HUD or vignette can put 1–2 % of the pixels below video black in one region, p1 9–11,
 * while full-range frames have 10–27 % dark pixels spread over the frame, p5 5–8.)
 */
object LevelsMath {
    /** Grid step for sampling, in pixels along both axes (1/64 of the frame). */
    const val SAMPLE_STEP = 8

    /** A capture whose 5th-percentile luminance is at least this is treated as limited range... */
    const val LIMITED_P5_MIN = 14

    /**
     * ...provided its 99th percentile is at most this: limited video tops out at 235, so a frame
     * with brighter highlights than that is full range already (a bright native app, say).
     */
    const val LIMITED_P99_MAX = 240

    private const val BLACK = 16
    private const val WHITE = 235

    /** out = clamp(round((v − 16) × 255 / 219)) for every 8-bit value. */
    val EXPAND_LUT: IntArray = IntArray(256) { v ->
        // round half up in integers: (2·(v−16)·255 + 219) / (2·219), clamped to 0..255
        val num = 2 * (v - BLACK) * 255 + (WHITE - BLACK)
        val q = if (num < 0) 0 else num / (2 * (WHITE - BLACK))
        q.coerceIn(0, 255)
    }

    /**
     * Rec.709 luma of an ARGB pixel, 0..255. Truncated rather than rounded, so a full-range UI
     * background such as the app's own #0B0C0E (11.93) stays below [LIMITED_P5_MIN].
     */
    fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (2126 * r + 7152 * g + 722 * b) / 10_000
    }

    /** Adds every [step]-th pixel of [row] (first [width] entries) to [hist] (256 bins). */
    fun sampleRow(row: IntArray, width: Int, hist: IntArray, step: Int = SAMPLE_STEP) {
        var x = 0
        while (x < width) {
            hist[luma(row[x])]++
            x += step
        }
    }

    /** Smallest value v whose cumulative count reaches fraction [q] of all samples; -1 if empty. */
    fun percentile(hist: IntArray, q: Double): Int {
        var total = 0L
        for (c in hist) total += c
        if (total == 0L) return -1
        // tiny epsilon so 1 % of 100 samples is 1, not 2 through floating-point noise
        val target = kotlin.math.ceil(total * q - 1e-9).toLong().coerceIn(1L, total)
        var cum = 0L
        for (v in hist.indices) {
            cum += hist[v]
            if (cum >= target) return v
        }
        return hist.size - 1
    }

    /** Luminance percentiles of the sample; [p1] is only logged, [p5] and [p99] decide. */
    data class Levels(val p1: Int, val p5: Int, val p99: Int, val samples: Long) {
        val limited: Boolean get() = samples > 0 && p5 >= LIMITED_P5_MIN && p99 <= LIMITED_P99_MAX
    }

    fun measure(hist: IntArray): Levels {
        var total = 0L
        for (c in hist) total += c
        return Levels(percentile(hist, 0.01), percentile(hist, 0.05), percentile(hist, 0.99), total)
    }

    /**
     * Maps R, G and B of the first [count] pixels through [lut] in place; alpha is untouched.
     * Returns the bitwise AND of their alpha values (0xFF: every pixel opaque).
     */
    fun applyLut(px: IntArray, count: Int, lut: IntArray = EXPAND_LUT): Int {
        var alphaAnd = 0xFF
        for (i in 0 until count) {
            val c = px[i]
            alphaAnd = alphaAnd and (c ushr 24)
            px[i] = (c and -0x1000000) or
                (lut[(c shr 16) and 0xFF] shl 16) or
                (lut[(c shr 8) and 0xFF] shl 8) or
                lut[c and 0xFF]
        }
        return alphaAnd
    }
}
