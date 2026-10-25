package com.steamcontroller.android.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Inflater

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

    private fun be32(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or
            ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)

    /** A minimal reader for what PngStream writes: checks every CRC, returns RGB rows. */
    private fun decode(png: ByteArray): Triple<Int, Int, Array<IntArray>> {
        var at = 8
        var w = 0
        var h = 0
        val idat = ByteArrayOutputStream()
        val types = ArrayList<String>()
        while (at < png.size) {
            val len = be32(png, at)
            val type = String(png, at + 4, 4, Charsets.US_ASCII)
            val crc = CRC32().apply { update(png, at + 4, 4 + len) }
            assertEquals("crc of $type", crc.value.toInt(), be32(png, at + 8 + len))
            when (type) {
                "IHDR" -> { w = be32(png, at + 8); h = be32(png, at + 12); assertEquals(8, png[at + 16].toInt()); assertEquals(2, png[at + 17].toInt()) }
                "IDAT" -> idat.write(png, at + 8, len)
            }
            types += type
            at += 12 + len
        }
        assertEquals("IHDR", types.first())
        assertEquals("IEND", types.last())
        val stride = w * 3
        val raw = ByteArray((stride + 1) * h)
        val inf = Inflater().apply { setInput(idat.toByteArray()) }
        var n = 0
        while (n < raw.size && !inf.finished()) n += inf.inflate(raw, n, raw.size - n)
        assertEquals(raw.size, n)
        val rows = Array(h) { IntArray(w) }
        var prev = ByteArray(stride)
        for (y in 0 until h) {
            val base = y * (stride + 1)
            assertEquals(4, raw[base].toInt())
            val cur = ByteArray(stride)
            for (k in 0 until stride) {
                val a = if (k >= 3) cur[k - 3].toInt() and 0xFF else 0
                val b = prev[k].toInt() and 0xFF
                val c = if (k >= 3) prev[k - 3].toInt() and 0xFF else 0
                val p = a + b - c
                val pa = Math.abs(p - a); val pb = Math.abs(p - b); val pc = Math.abs(p - c)
                val pred = if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                cur[k] = ((raw[base + 1 + k].toInt() + pred) and 0xFF).toByte()
            }
            for (x in 0 until w) {
                rows[y][x] = ((cur[x * 3].toInt() and 0xFF) shl 16) or ((cur[x * 3 + 1].toInt() and 0xFF) shl 8) or (cur[x * 3 + 2].toInt() and 0xFF)
            }
            prev = cur
        }
        return Triple(w, h, rows)
    }

    @Test
    fun decodesBackToTheSamePixels() {
        val w = 97
        val h = 53 // not a multiple of the band
        val (dw, dh, rows) = decode(encode(w, h, band = 16))
        assertEquals(w, dw)
        assertEquals(h, dh)
        for (y in 0 until h) for (x in 0 until w) assertEquals("at $x,$y", pixel(x, y) and 0xFFFFFF, rows[y][x])
    }

    @Test
    fun startsWithTheSignatureAndEndsWithIend() {
        val bytes = encode(40, 30, band = 7)
        assertEquals(0x89, bytes[0].toInt() and 0xFF)
        assertEquals("PNG", String(bytes, 1, 3, Charsets.US_ASCII))
        assertTrue(bytes.copyOfRange(bytes.size - 8, bytes.size - 4).contentEquals("IEND".toByteArray()))
    }

    @Test(expected = IllegalStateException::class)
    fun refusesToFinishShort() {
        val png = PngStream(ByteArrayOutputStream(), 4, 4)
        png.rows(IntArray(4 * 2), 2)
        png.finish()
    }
}
