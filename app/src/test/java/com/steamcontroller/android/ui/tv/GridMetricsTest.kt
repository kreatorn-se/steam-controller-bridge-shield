package com.steamcontroller.android.ui.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The game hub grid: images start on the content inset (right of the rail) and end on the right
 * safe margin, whatever the width and density; 16:9; at least four columns.
 */
class GridMetricsTest {
    private fun dp(v: Int, d: Float) = (v * d + 0.5f).toInt()

    /** Image edges of a full row laid out as GameHub does (row padding left − ring, gap between cards). */
    private fun edges(width: Int, left: Int, right: Int, d: Float): Triple<GridMetrics, Int, Int> {
        val g = GridMetrics.compute(width, left, right, d)
        val rowStart = left - dp(3, d)
        val firstImageLeft = rowStart + g.ring
        val lastImageRight = rowStart + g.cols * g.cardW + (g.cols - 1) * dp(GridMetrics.GAP_DP, d) - g.ring
        return Triple(g, firstImageLeft, lastImageRight)
    }

    @Test
    fun shieldAt85PercentFillsTheContentSpan() {
        // 1920 px at 1.7 px/dp (xhdpi × 0.85): safe_h 57dp = 97 px, content inset 97 + 72dp.
        val d = 1.7f
        val safe = dp(57, d)
        val left = safe + dp(72, d)
        val (g, l, r) = edges(1920, left, safe, d)
        assertEquals(5, g.cols)
        assertEquals(left, l)
        assertTrue("last image ends at $r, the strip's chips at ${1920 - safe}", r in (1920 - safe - g.cols)..(1920 - safe))
    }

    @Test
    fun everyWidthAndDensity() {
        for (d in floatArrayOf(1.0f, 1.5f, 1.7f, 2.0f, 2.55f, 3.4f)) {
            for (wDp in intArrayOf(600, 800, 960, 1129, 1280, 1600, 2259)) {
                val width = (wDp * d).toInt()
                val safe = dp(57, d)
                val left = safe + dp(72, d)
                val (g, l, r) = edges(width, left, safe, d)
                val tag = "d=$d w=${wDp}dp"
                assertTrue(tag, g.cols >= GridMetrics.MIN_COLS)
                assertEquals(tag, left, l)
                assertTrue("$tag: right edge $r vs ${width - safe}", r in (width - safe - g.cols)..(width - safe))
                assertTrue(tag, kotlin.math.abs(g.imageH * 16 - g.imageW * 9) <= 16)
                assertEquals(tag, g.imageW + 2 * g.ring, g.cardW)
                assertEquals(tag, g.imageH + 2 * g.ring, g.cardH)
                // more columns only while images stay at least MIN_IMAGE_DP wide
                if (g.cols > GridMetrics.MIN_COLS) assertTrue(tag, g.imageW >= dp(GridMetrics.MIN_IMAGE_DP, d) - 1)
            }
        }
    }

    @Test
    fun wideScreensGetMoreColumns() {
        val d = 1.7f
        val safe = dp(57, d)
        val left = safe + dp(72, d)
        val narrow = GridMetrics.compute(1920, left, safe, d).cols
        val wide = GridMetrics.compute(3840, left, safe, d).cols
        assertTrue("$narrow < $wide", wide > narrow)
        assertEquals(4, GridMetrics.compute(800, left, safe, d).cols)
    }
}
