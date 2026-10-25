package com.steamcontroller.android.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class PngStreamTest {
    private fun pixel(x: Int, y: Int): Int =
        (0xFF shl 24) or ((x * 7 + y) and 0xFF shl 16) or ((y * 3) and 0xFF shl 8) or ((x xor y) and 0xFF)

    private fun encode(w: Int, h: Int, band: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        val png = PngStream(bos, w, h)
        val px = IntArray(w * band)
        var y0 = 0
        while (y0 < h) {
            val rows = minOf(band, h - y0)
            for (r in 0 until rows) for (x in 0 until w) px[r * w + x] = pixel(x, y0 + r)
            png.rows(px, rows)
            y0 += rows
        }
        png.finish()
        return bos.toByteArray()
    }

    @Test
    fun decodesBackToTheSamePixels() {
        val w = 97
        val h = 53 // not a multiple of the band
        val bytes = encode(w, h, band = 16)
        val img = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(w, img.width)
        assertEquals(h, img.height)
        for (y in 0 until h) for (x in 0 until w) {
            assertEquals("at $x,$y", pixel(x, y) and 0xFFFFFF, img.getRGB(x, y) and 0xFFFFFF)
        }
    }

    @Test
    fun endsWithIendSoTheUploaderTakesIt() {
        val bytes = encode(40, 30, band = 7)
        val tail = bytes.copyOfRange(bytes.size - 8, bytes.size - 4)
        assertTrue(tail.contentEquals("IEND".toByteArray()))
    }

    @Test(expected = IllegalStateException::class)
    fun refusesToFinishShort() {
        val png = PngStream(ByteArrayOutputStream(), 4, 4)
        png.rows(IntArray(4 * 2), 2)
        png.finish()
    }
}
