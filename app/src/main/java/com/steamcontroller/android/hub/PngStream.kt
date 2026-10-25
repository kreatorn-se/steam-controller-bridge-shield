package com.steamcontroller.android.hub

import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * A PNG writer that takes the picture a band of rows at a time, so a 4K capture never sits in
 * memory whole. Until 1.7.2 CaptureLevels built a 33 MB bitmap per capture and let Android encode
 * it; a few captures in a row took the bridge to ~120 MB, the biggest foreground process on the
 * Shield, and the low-memory killer's pick while GeForce NOW streams (25 Oct 2026: killed six
 * seconds after four captures). This keeps two rows and a 64 KB buffer.
 *
 * 8-bit RGB (screencap frames are opaque), the Paeth filter on every row, zlib level 6 with the
 * strategy for filtered data, as libpng does. [rows] takes ARGB ints; [finish] writes the end and
 * flushes [out] without closing it.
 */
class PngStream(out: OutputStream, private val width: Int, private val height: Int) {
    private val out = BufferedOutputStream(out, 256 * 1024)
    private val stride = width * 3
    private var prev = ByteArray(stride)
    private var cur = ByteArray(stride)
    private val line = ByteArray(stride + 1)
    private val buf = ByteArray(64 * 1024)
    private val crc = CRC32()
    private val deflater = Deflater(6).apply { setStrategy(Deflater.FILTERED) }
    private var written = 0

    init {
        require(width in 1..65_535 && height in 1..65_535) { "bad size ${width}x$height" }
        this.out.write(SIGNATURE)
        val ihdr = ByteArray(13)
        be32(ihdr, 0, width)
        be32(ihdr, 4, height)
        ihdr[8] = 8 // bit depth
        ihdr[9] = 2 // colour type: RGB
        // compression, filter method and interlace stay 0
        chunk(IHDR, ihdr, ihdr.size)
    }

    /** The next [count] rows, `width * count` ARGB pixels from the start of [px]. */
    fun rows(px: IntArray, count: Int) {
        check(written + count <= height) { "more rows than the height $height" }
        for (r in 0 until count) {
            var j = 0
            var i = r * width
            val end = i + width
            while (i < end) {
                val c = px[i++]
                cur[j] = (c shr 16).toByte()
                cur[j + 1] = (c shr 8).toByte()
                cur[j + 2] = c.toByte()
                j += 3
            }
            line[0] = PAETH
            for (k in 0 until stride) {
                val a = if (k >= 3) cur[k - 3].toInt() and 0xFF else 0
                val b = prev[k].toInt() and 0xFF
                val c = if (k >= 3) prev[k - 3].toInt() and 0xFF else 0
                val p = a + b - c
                val pa = Math.abs(p - a)
                val pb = Math.abs(p - b)
                val pc = Math.abs(p - c)
                val pred = if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                line[k + 1] = ((cur[k].toInt() and 0xFF) - pred).toByte()
            }
            deflater.setInput(line)
            while (!deflater.needsInput()) drain()
            val t = prev; prev = cur; cur = t
            written++
        }
    }

    /** Ends the image (all [height] rows must be in) and flushes. */
    fun finish() {
        check(written == height) { "$written of $height rows" }
        deflater.finish()
        try {
            while (!deflater.finished()) drain()
        } finally {
            deflater.end()
        }
        chunk(IEND, ByteArray(0), 0)
        out.flush()
    }

    /** Gives up without finishing (an error on the way): frees zlib's memory. */
    fun abandon() = deflater.end()

    private fun drain() {
        val n = deflater.deflate(buf)
        if (n > 0) chunk(IDAT, buf, n)
    }

    private fun chunk(type: ByteArray, data: ByteArray, len: Int) {
        val head = ByteArray(4)
        be32(head, 0, len)
        out.write(head)
        out.write(type)
        out.write(data, 0, len)
        crc.reset()
        crc.update(type)
        crc.update(data, 0, len)
        be32(head, 0, crc.value.toInt())
        out.write(head)
    }

    private companion object {
        const val PAETH: Byte = 4
        val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val IHDR = "IHDR".toByteArray(Charsets.US_ASCII)
        val IDAT = "IDAT".toByteArray(Charsets.US_ASCII)
        val IEND = "IEND".toByteArray(Charsets.US_ASCII)

        fun be32(b: ByteArray, at: Int, v: Int) {
            b[at] = (v ushr 24).toByte()
            b[at + 1] = (v ushr 16).toByte()
            b[at + 2] = (v ushr 8).toByte()
            b[at + 3] = v.toByte()
        }
    }
}
