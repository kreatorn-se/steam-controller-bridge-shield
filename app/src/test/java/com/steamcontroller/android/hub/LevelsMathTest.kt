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
        LevelsMath.applyLut(px, 3)
        assertEquals(argb(0xFF, 0, 130, 255), px[0])
        assertEquals(argb(0x80, 255, 0, 130), px[1])
        assertEquals(argb(0x00, 0, 255, 1), px[2])
        assertEquals("beyond count untouched", 0x7, px[3])
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
        rows += IntArray(256) { x -> grey(if (x < 60) 0 else 255) }
        val hist = histOf(rows)
        assertEquals(0, hist.indexOfFirst { it > 0 }) // min is 0 ...
        val lv = LevelsMath.measure(hist)
        assertTrue("... but p1=${lv.p1}", lv.limited)
    }

    @Test
    fun measuredShieldNumbersAreLimited() {
        // p1 14..21 as measured on the 3840×2160 GFN captures
        for (p1 in 14..21) {
            val h = IntArray(256)
            h[p1] = 1_000
            h[(p1 + 120).coerceAtMost(211)] = 99_000
            assertTrue("p1=$p1", LevelsMath.measure(h).limited)
        }
    }

    @Test
    fun fullRangeFrameIsLeftAlone() {
        // Steam's own screenshots: p1 ≈ 1-2.
        val rows = List(64) { y -> IntArray(256) { x -> grey((x + y * 4) % 256) } }
        val lv = LevelsMath.measure(histOf(rows))
        assertTrue("p1=${lv.p1}", lv.p1 <= 2)
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
    fun thresholdIsInclusiveAtTwelve() {
        val h = IntArray(256)
        h[12] = 50; h[100] = 4_950
        assertTrue(LevelsMath.measure(h).limited)
        h[12] = 0; h[11] = 50
        assertFalse(LevelsMath.measure(h).limited)
    }

    @Test
    fun emptySampleIsNotLimited() {
        assertFalse(LevelsMath.measure(IntArray(256)).limited)
    }
}
