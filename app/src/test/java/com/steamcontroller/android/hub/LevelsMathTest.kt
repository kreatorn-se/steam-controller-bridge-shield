package com.steamcontroller.android.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class LevelsMathTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun grey(v: Int) = argb(0xFF, v, v, v)

    /** A histogram as [LevelsMath.sampleRow] builds it, from rows of pixels. */
    private fun histOf(rows: List<IntArray>, step: Int = 1): IntArray {
        val h = IntArray(256)
        for (r in rows) LevelsMath.sampleRow(r, r.size, h, step)
        return h
    }

    // ------------------------------------------------------------------ LUT

    @Test
    fun lutMatchesFormulaForEveryValue() {
        for (v in 0..255) {
            val expected = ((v - 16) * 255.0 / 219.0).roundToInt().coerceIn(0, 255)
            assertEquals("v=$v", expected, LevelsMath.EXPAND_LUT[v])
        }
    }

    @Test
    fun lutEndpoints() {
        val lut = LevelsMath.EXPAND_LUT
        assertEquals(256, lut.size)
        assertEquals(0, lut[0])
        assertEquals(0, lut[15])
        assertEquals(0, lut[16])
        assertEquals(1, lut[17])
        assertEquals(130, lut[128])
        assertEquals(255, lut[235])
        assertEquals(255, lut[236])
        assertEquals(255, lut[255])
    }

    @Test
    fun lutIsMonotonic() {
        val lut = LevelsMath.EXPAND_LUT
        for (v in 1..255) assertTrue("v=$v", lut[v] >= lut[v - 1])
    }

    @Test
    fun applyLutMapsChannelsAndKeepsAlpha() {
        val px = intArrayOf(argb(0xFF, 16, 128, 235), argb(0x80, 235, 16, 128), argb(0x00, 0, 255, 17), 0x7)
        assertEquals("alpha AND of the first three", 0x00, LevelsMath.applyLut(px, 3))
        assertEquals(argb(0xFF, 0, 130, 255), px[0])
        assertEquals(argb(0x80, 255, 0, 130), px[1])
        assertEquals(argb(0x00, 0, 255, 1), px[2])
        assertEquals("beyond count untouched", 0x7, px[3])
    }

    @Test
    fun applyLutReportsOpaque() {
        val px = IntArray(4) { grey(100) }
        assertEquals(0xFF, LevelsMath.applyLut(px, px.size))
        px[2] = argb(0xFE, 1, 2, 3)
        assertEquals(0xFE, LevelsMath.applyLut(px, px.size))
    }

    // ------------------------------------------------------------------ luma / percentiles

    @Test
    fun lumaRec709() {
        assertEquals(0, LevelsMath.luma(grey(0)))
        assertEquals(255, LevelsMath.luma(grey(255)))
        assertEquals(16, LevelsMath.luma(grey(16)))
        assertEquals(54, LevelsMath.luma(argb(0xFF, 255, 0, 0)))   // 0.2126 × 255 = 54.2
        assertEquals(182, LevelsMath.luma(argb(0xFF, 0, 255, 0)))  // 0.7152 × 255 = 182.4
        assertEquals(18, LevelsMath.luma(argb(0xFF, 0, 0, 255)))   // 0.0722 × 255 = 18.4
        assertEquals("alpha ignored", 128, LevelsMath.luma(argb(0x00, 128, 128, 128)))
    }

    @Test
    fun percentileBasics() {
        val h = IntArray(256)
        assertEquals(-1, LevelsMath.percentile(h, 0.01))
        for (v in 0 until 100) h[v] = 1 // 100 samples, one at each of 0..99
        assertEquals(0, LevelsMath.percentile(h, 0.01))
        assertEquals(49, LevelsMath.percentile(h, 0.50))
        assertEquals(98, LevelsMath.percentile(h, 0.99))
    }

    @Test
    fun sampleRowTakesEveryStepPixel() {
        val row = IntArray(17) { grey(if (it % 8 == 0) 200 else 10) }
        val h = histOf(listOf(row), step = 8)
        assertEquals(3, h[200]) // x = 0, 8, 16
        assertEquals(0, h[10])
    }

    // ------------------------------------------------------------------ detection

    /** A GFN-like limited-range frame: everything between 16 and 235, mostly mid/dark. */
    private fun limitedFrame(): List<IntArray> = List(64) { y ->
        IntArray(256) { x -> grey(16 + ((x * 7 + y * 13) % 140)) }
    }

    @Test
    fun limitedRangeVideoIsDetected() {
        val lv = LevelsMath.measure(histOf(limitedFrame()))
        assertTrue("p1=${lv.p1}", lv.p1 >= 16)
        assertTrue(lv.limited)
    }

    @Test
    fun limitedRangeWithFullRangePillIsStillDetected() {
        // The CAPTURED pill: a few true-black and true-white pixels (well under 1 %).
        val rows = limitedFrame().toMutableList()
        // A dark glass pill with white text: true 0 and 255 pixels, each well under 1 %.
        rows += IntArray(256) { x -> grey(if (x < 60) 0 else if (x < 120) 255 else 30) }
        val hist = histOf(rows)
        assertEquals(0, hist.indexOfFirst { it > 0 }) // min is 0 ...
        assertEquals(255, hist.indexOfLast { it > 0 }) // ... max is 255 ...
        val lv = LevelsMath.measure(hist)
        assertTrue("... but p5=${lv.p5} p99=${lv.p99}", lv.limited)
        assertTrue(lv.p99 <= LevelsMath.LIMITED_P99_MAX)
    }

    @Test
    fun brightFullRangeFrameWithoutBlacksIsLeftAlone() {
        // A bright native screen (snow, a light UI): no true blacks, so p5 >= 14, but highlights
        // well above video white.
        val rows = List(64) { y -> IntArray(256) { x -> grey(60 + ((x * 3 + y * 7) % 196)) } }
        val lv = LevelsMath.measure(histOf(rows))
        assertTrue("p5=${lv.p5}", lv.p5 >= LevelsMath.LIMITED_P5_MIN)
        assertTrue("p99=${lv.p99}", lv.p99 > LevelsMath.LIMITED_P99_MAX)
        assertFalse(lv.limited)
    }

    @Test
    fun p99BoundaryIsInclusiveAt240() {
        val h = IntArray(256)
        h[20] = 4_900; h[240] = 100
        assertEquals(240, LevelsMath.measure(h).p99)
        assertTrue(LevelsMath.measure(h).limited)
        h[240] = 0; h[241] = 100
        assertEquals(241, LevelsMath.measure(h).p99)
        assertFalse(LevelsMath.measure(h).limited)
    }

    /**
     * A histogram of 10 000 samples with exactly these percentiles: 1 % at [p1], 4 % at [p5],
     * 93.5 % in the mid-tones and 1.5 % at [p99].
     */
    private fun histWith(p1: Int, p5: Int, p99: Int): IntArray {
        require(p1 < p5 && p5 < p99 - 1)
        val h = IntArray(256)
        h[p1] += 100
        h[p5] += 400
        h[(p5 + p99) / 2] += 9_350
        h[p99] += 150
        return h
    }

    private fun assertMeasured(p1: Int, p5: Int, p99: Int, limited: Boolean) {
        val lv = LevelsMath.measure(histWith(p1, p5, p99))
        assertEquals(Triple(p1, p5, p99), Triple(lv.p1, lv.p5, lv.p99))
        assertEquals("p1=$p1 p5=$p5 p99=$p99", limited, lv.limited)
    }

    @Test
    fun measuredShieldCapturesWithDarkClusterAreLimited() {
        // Real GFN captures, visibly washed out, that 1.4.3's p1 >= 12 rule missed: 1-2 % dark
        // pixels clustered in one region pull p1 to 9-11, but p5 is 15-18.
        assertMeasured(9, 16, 139, limited = true)
        assertMeasured(9, 18, 72, limited = true)
        assertMeasured(11, 15, 109, limited = true)
        assertMeasured(11, 15, 106, limited = true)
    }

    @Test
    fun measuredFullRangeCapturesAreLeftAlone() {
        // The two genuinely full-range captures in the same set: dark pixels all over the frame.
        assertMeasured(4, 8, 138, limited = false)
        assertMeasured(3, 5, 112, limited = false)
    }

    @Test
    fun earlierCorrectedCapturesStayLimited() {
        // 1.4.3 expanded captures with p1 12-41; they must still qualify.
        assertMeasured(12, 16, 150, limited = true)
        assertMeasured(14, 20, 135, limited = true)
        assertMeasured(21, 30, 211, limited = true)
        assertMeasured(41, 50, 200, limited = true)
    }

    @Test
    fun fullRangeFrameIsLeftAlone() {
        // Steam's own screenshots: p1 ≈ 1-2.
        val rows = List(64) { y -> IntArray(256) { x -> grey((x + y * 4) % 256) } }
        val lv = LevelsMath.measure(histOf(rows))
        assertTrue("p1=${lv.p1}", lv.p1 <= 2)
        assertTrue("p5=${lv.p5}", lv.p5 < LevelsMath.LIMITED_P5_MIN)
        assertFalse(lv.limited)
    }

    @Test
    fun appUiOnNearBlackBackgroundIsLeftAlone() {
        // The app's own #0B0C0E background is luma 11.93; it must not be mistaken for video black.
        val bg = argb(0xFF, 0x0B, 0x0C, 0x0E)
        assertEquals(11, LevelsMath.luma(bg))
        val rows = List(64) { y -> IntArray(256) { x -> if ((x + y) % 5 == 0) grey(0xF2) else bg } }
        assertFalse(LevelsMath.measure(histOf(rows)).limited)
    }

    @Test
    fun p5ThresholdIsInclusiveAtFourteen() {
        val h = IntArray(256)
        h[14] = 250; h[100] = 4_750
        assertEquals(14, LevelsMath.measure(h).p5)
        assertTrue(LevelsMath.measure(h).limited)
        h[14] = 0; h[13] = 250
        assertEquals(13, LevelsMath.measure(h).p5)
        assertFalse(LevelsMath.measure(h).limited)
    }

    @Test
    fun emptySampleIsNotLimited() {
        assertFalse(LevelsMath.measure(IntArray(256)).limited)
    }

    // ------------------------------------------------------------------ saturation

    /** Reference in doubles: round(Y + (c − Y) × k), clamped. */
    private fun refSat(argb: Int, pct: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val k = 1 + pct / 100.0
        fun ch(c: Int) = Math.round(y + (c - y) * k).toInt().coerceIn(0, 255)
        return (argb and -0x1000000) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    private fun assertWithinOne(expected: Int, actual: Int, msg: String) {
        for (sh in intArrayOf(24, 16, 8, 0)) {
            val e = (expected shr sh) and 0xFF
            val a = (actual shr sh) and 0xFF
            assertTrue("$msg: byte@$sh expected $e got $a", kotlin.math.abs(e - a) <= if (sh == 24) 0 else 1)
        }
    }

    /** A spread of colours: every channel through 0..255 in coarse steps, plus random-ish ones. */
    private val palette: IntArray = buildList {
        for (r in 0..255 step 17) for (g in 0..255 step 17) for (b in 0..255 step 17) add(argb(0xFF, r, g, b))
        var x = 0x2545F491
        repeat(2_000) { x = x * 1103515245 + 12345; add(argb(0xFF, (x shr 16) and 0xFF, (x shr 8) and 0xFF, x and 0xFF)) }
    }.toIntArray()

    @Test
    fun saturationChoices() {
        assertEquals(listOf(0, 6, 12, 18, 25), LevelsMath.SATURATION_CHOICES)
        assertEquals(12, LevelsMath.SATURATION_DEFAULT)
    }

    @Test
    fun greyStaysGrey() {
        for (pct in LevelsMath.SATURATION_CHOICES) for (v in 0..255) {
            assertEquals("v=$v pct=$pct", grey(v), LevelsMath.saturate(grey(v), pct))
            val px = intArrayOf(grey(v))
            LevelsMath.process(px, 1, null, pct)
            assertEquals("process v=$v pct=$pct", grey(v), px[0])
        }
    }

    @Test
    fun boostZeroIsIdentity() {
        val px = palette.copyOf()
        assertEquals(0xFF, LevelsMath.process(px, px.size, null, 0))
        assertTrue(px.contentEquals(palette))
        for (c in palette) assertEquals(c, LevelsMath.saturate(c, 0))
    }

    @Test
    fun boostMatchesFormulaAroundLuma() {
        for (pct in listOf(6, 12, 18, 25)) for (c in palette) {
            assertWithinOne(refSat(c, pct), LevelsMath.saturate(c, pct), "c=${Integer.toHexString(c)} pct=$pct")
        }
        // Worked example, k = 1.12: (200, 100, 50) has Y = 0.2126·200 + 0.7152·100 + 0.0722·50 = 117.65
        // R = 117.65 + 82.35·1.12 = 209.88 -> 210; G = 117.65 − 17.65·1.12 = 97.88 -> 98;
        // B = 117.65 − 67.65·1.12 = 41.88 -> 42
        assertEquals(argb(0xFF, 210, 98, 42), LevelsMath.saturate(argb(0xFF, 200, 100, 50), 12))
    }

    @Test
    fun boostPushesChannelsAwayFromLumaAndKeepsLuma() {
        for (c in palette) {
            val out = LevelsMath.saturate(c, 25)
            val y = LevelsMath.luma(c)
            for (sh in intArrayOf(16, 8, 0)) {
                val a = (c shr sh) and 0xFF
                val b = (out shr sh) and 0xFF
                // each channel moves away from luma (or stays, at the clamp / on luma)
                if (a > y + 1) assertTrue(b >= a)
                if (a < y - 1) assertTrue(b <= a)
            }
            // without clamping, luma is preserved (within rounding)
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val bb = c and 0xFF
            val yf = 0.2126 * r + 0.7152 * g + 0.0722 * bb
            val clamps = listOf(r, g, bb).any { val v = yf + (it - yf) * 1.25; v < 0 || v > 255 }
            if (!clamps) assertTrue(kotlin.math.abs(LevelsMath.luma(out) - y) <= 1)
        }
    }

    @Test
    fun boostClamps() {
        assertEquals(argb(0xFF, 255, 0, 0), LevelsMath.saturate(argb(0xFF, 255, 0, 0), 25))
        assertEquals(argb(0xFF, 0, 255, 0), LevelsMath.saturate(argb(0xFF, 0, 255, 0), 25))
        assertEquals(argb(0xFF, 0, 0, 255), LevelsMath.saturate(argb(0xFF, 0, 0, 255), 25))
        // 250 with low luma would exceed 255; 5 with high luma would go below 0
        val hot = LevelsMath.saturate(argb(0xFF, 250, 10, 10), 25)
        assertEquals(255, (hot shr 16) and 0xFF)
        val cold = LevelsMath.saturate(argb(0xFF, 5, 250, 250), 25)
        assertEquals(0, (cold shr 16) and 0xFF)
        for (pct in LevelsMath.SATURATION_CHOICES) for (c in palette) {
            val o = LevelsMath.saturate(c, pct)
            for (sh in intArrayOf(16, 8, 0)) assertTrue(((o shr sh) and 0xFF) in 0..255)
        }
    }

    @Test
    fun boostKeepsAlpha() {
        val px = intArrayOf(argb(0x80, 200, 100, 50), argb(0x00, 10, 200, 30))
        assertEquals(0x00, LevelsMath.process(px, 2, null, 12))
        assertEquals(0x80, px[0] ushr 24)
        assertEquals(0x00, px[1] ushr 24)
    }

    @Test
    fun processWithSaturationMatchesSaturate() {
        val px = palette.copyOf()
        LevelsMath.process(px, px.size, null, 18)
        for (i in palette.indices) assertEquals(LevelsMath.saturate(palette[i], 18), px[i])
    }

    @Test
    fun combinedLutAndBoostEqualsSequential() {
        for (pct in LevelsMath.SATURATION_CHOICES) {
            val combined = palette.copyOf()
            LevelsMath.process(combined, combined.size, LevelsMath.EXPAND_LUT, pct)
            val sequential = palette.copyOf()
            LevelsMath.applyLut(sequential, sequential.size)
            for (i in sequential.indices) sequential[i] = LevelsMath.saturate(sequential[i], pct)
            for (i in palette.indices) assertWithinOne(sequential[i], combined[i], "c=${Integer.toHexString(palette[i])} pct=$pct")
            // and against the double-precision reference of LUT then boost
            for (i in palette.indices) {
                val lv = intArrayOf(palette[i]).also { LevelsMath.applyLut(it, 1) }[0]
                assertWithinOne(refSat(lv, pct), combined[i], "ref c=${Integer.toHexString(palette[i])} pct=$pct")
            }
        }
    }

    @Test
    fun processWithLutOnlyEqualsApplyLut() {
        val a = palette.copyOf()
        val b = palette.copyOf()
        LevelsMath.process(a, a.size, LevelsMath.EXPAND_LUT, 0)
        LevelsMath.applyLut(b, b.size)
        assertTrue(a.contentEquals(b))
    }
}
