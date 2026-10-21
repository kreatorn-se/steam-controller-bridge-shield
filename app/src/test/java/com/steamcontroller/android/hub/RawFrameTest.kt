package com.steamcontroller.android.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RawFrameTest {
    private fun le(vararg v: Long): ByteArray {
        val b = ByteArray(v.size * 4)
        for ((i, x) in v.withIndex()) for (k in 0 until 4) b[i * 4 + k] = ((x shr (8 * k)) and 0xFF).toByte()
        return b
    }

    private fun ok(c: RawFrame.Check): RawFrame.Header {
        assertTrue("expected Ok, got $c", c is RawFrame.Check.Ok)
        return (c as RawFrame.Check.Ok).header
    }

    private fun bad(c: RawFrame.Check, contains: String) {
        assertTrue("expected Bad, got $c", c is RawFrame.Check.Bad)
        assertTrue((c as RawFrame.Check.Bad).reason, c.reason.contains(contains))
    }

    @Test
    fun sixteenByteHeaderWithDataspace() {
        // Android 10+: width, height, format, dataspace (e.g. 142671872 = sRGB)
        val h = ok(RawFrame.parse(le(3840, 2160, 1, 142_671_872), 16L + 3840L * 2160 * 4))
        assertEquals(RawFrame.Header(3840, 2160, 1, 16), h)
        assertEquals(3840 * 4, h.rowBytes)
    }

    @Test
    fun twelveByteHeader() {
        // Older screencap: no dataspace; the pixels start at byte 12 (the 4th "int" is pixel data).
        val h = ok(RawFrame.parse(le(1920, 1080, 1, 0xFF00FF00), 12L + 1920L * 1080 * 4))
        assertEquals(RawFrame.Header(1920, 1080, 1, 12), h)
    }

    @Test
    fun twelveByteHeaderReadAsOnlyTwelveBytes() {
        val h = ok(RawFrame.parse(le(4, 2, 1), 12L + 4 * 2 * 4))
        assertEquals(12, h.offset)
        bad(RawFrame.parse(le(4, 2, 1), 16L + 4 * 2 * 4), "short header")
    }

    @Test
    fun wrongLengthIsRefused() {
        bad(RawFrame.parse(le(3840, 2160, 1, 0), 16L + 3840L * 2160 * 4 - 1), "does not fit")
        bad(RawFrame.parse(le(3840, 2160, 1, 0), 16L + 3840L * 2160 * 4 + 4), "does not fit")
        bad(RawFrame.parse(le(3840, 2160, 1, 0), 12L + 3840L * 2160 * 4 + 8), "does not fit")
        // a truncated write (screencap killed mid-frame)
        bad(RawFrame.parse(le(3840, 2160, 1, 0), 16L + 3840L * 2160 * 2), "does not fit")
    }

    @Test
    fun otherPixelFormatsAreRefused() {
        for (f in longArrayOf(2, 3, 4, 5, 22, 43, 0)) {
            bad(RawFrame.parse(le(64, 32, f, 0), 16L + 64 * 32 * 4), "pixel format $f")
        }
    }

    @Test
    fun nonsenseHeadersAreRefused() {
        bad(RawFrame.parse(le(0, 2160, 1, 0), 16), "bad size")
        bad(RawFrame.parse(le(3840, 0, 1, 0), 16), "bad size")
        bad(RawFrame.parse(le(100_000, 2, 1, 0), 16L + 100_000L * 2 * 4), "bad size")
        bad(RawFrame.parse(le(0xFFFFFFFF, 2, 1, 0), 16), "bad size")
        bad(RawFrame.parse(ByteArray(8), 8), "too short")
        // a PNG is not a raw frame
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte())
        assertTrue(RawFrame.isPng(png))
        assertTrue(RawFrame.parse(png, 2_000_000) is RawFrame.Check.Bad)
        assertFalse(RawFrame.isPng(le(3840, 2160, 1, 0)))
    }

    @Test
    fun checkReadsAFile() {
        val f = File.createTempFile("frame", ".raw")
        try {
            val px = byteArrayOf(10, 20, 30, -1, 40, 50, 60, -1)
            f.writeBytes(le(2, 1, 1, 0) + px)
            assertEquals(RawFrame.Header(2, 1, 1, 16), ok(RawFrame.check(f)))
            f.writeBytes(le(2, 1, 1) + px)
            assertEquals(12, ok(RawFrame.check(f)).offset)
            f.writeBytes(le(2, 1, 1, 0) + px.copyOf(7))
            bad(RawFrame.check(f), "does not fit")
            f.writeBytes(ByteArray(3))
            bad(RawFrame.check(f), "too short")
        } finally {
            f.delete()
        }
        bad(RawFrame.check(File("/nonexistent/frame.raw")), "")
    }

    @Test
    fun rgbaToArgb() {
        val src = byteArrayOf(0, 0, 0, 0, 1, 2, 3, -1, -1, 0, 0x7F, 0x80.toByte(), 0x11, 0x22, 0x33, 0x44)
        val dst = IntArray(5) { -7 }
        RawFrame.toArgb(src, 4, dst, 1, 3)
        assertEquals(-7, dst[0])
        assertEquals(0xFF010203.toInt(), dst[1])
        assertEquals(0x80FF007F.toInt(), dst[2])
        assertEquals(0x44112233, dst[3])
        assertEquals(-7, dst[4])
    }

    @Test
    fun rgbaToArgbThenLevelsMatchesLuma() {
        // A converted opaque grey goes through the levels/boost pass like any decoded pixel.
        val src = byteArrayOf(16, 16, 16, -1, 235.toByte(), 235.toByte(), 235.toByte(), -1)
        val px = IntArray(2)
        RawFrame.toArgb(src, 0, px, 0, 2)
        assertEquals(0xFF, LevelsMath.process(px, 2, LevelsMath.EXPAND_LUT, 12))
        assertEquals(0xFF000000.toInt(), px[0])
        assertEquals(0xFFFFFFFF.toInt(), px[1])
    }
}
