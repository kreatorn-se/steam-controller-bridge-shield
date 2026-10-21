package com.steamcontroller.android.hub

import java.io.File
import java.io.RandomAccessFile

/**
 * `screencap <file>` without `-p`: the framebuffer as it is, no PNG encode (which is what makes
 * `screencap -p` take 10-30 s on the Shield during a stream). Layout, little-endian int32s:
 * width, height, pixel format, and on Android 10+ a fourth, the dataspace; then height rows of
 * width × 4 bytes (RGBA_8888, row stride already removed by screencap).
 *
 * Pure parsing and conversion here (no Android types), so it runs in JVM unit tests.
 */
object RawFrame {
    /** android.graphics.PixelFormat.RGBA_8888 / HAL_PIXEL_FORMAT_RGBA_8888. */
    const val FORMAT_RGBA_8888 = 1
    const val BYTES_PER_PIXEL = 4
    private const val MAX_SIDE = 16_384

    data class Header(val width: Int, val height: Int, val format: Int, val offset: Int) {
        val rowBytes: Int get() = width * BYTES_PER_PIXEL
    }

    sealed class Check {
        data class Ok(val header: Header) : Check()
        data class Bad(val reason: String) : Check()
    }

    private fun le32(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xFF) or
            ((b[i + 1].toLong() and 0xFF) shl 8) or
            ((b[i + 2].toLong() and 0xFF) shl 16) or
            ((b[i + 3].toLong() and 0xFF) shl 24))

    /**
     * The header from the file's first bytes ([head], at least 12) and its total [fileLength].
     * The header is 16 bytes (with dataspace) or 12 (older screencap): whichever makes
     * header + width × height × 4 equal the file length. Only RGBA_8888 is accepted.
     */
    fun parse(head: ByteArray, fileLength: Long): Check {
        if (head.size < 12 || fileLength < 12) return Check.Bad("too short ($fileLength bytes)")
        val w = le32(head, 0)
        val h = le32(head, 4)
        val f = le32(head, 8)
        if (w !in 1L..MAX_SIDE.toLong() || h !in 1L..MAX_SIDE.toLong()) return Check.Bad("bad size ${w}x$h")
        val pixels = w * h * BYTES_PER_PIXEL
        val offset = when (fileLength) {
            16 + pixels -> 16
            12 + pixels -> 12
            else -> return Check.Bad("length $fileLength does not fit ${w}x$h (${12 + pixels} or ${16 + pixels})")
        }
        if (offset == 16 && head.size < 16) return Check.Bad("short header read")
        if (f != FORMAT_RGBA_8888.toLong()) return Check.Bad("pixel format $f, not RGBA_8888")
        return Check.Ok(Header(w.toInt(), h.toInt(), f.toInt(), offset))
    }

    /** Reads the first 16 bytes of [file] and checks it as a raw frame. */
    fun check(file: File): Check = try {
        val len = file.length()
        val head = ByteArray(16)
        val n = RandomAccessFile(file, "r").use { raf -> raf.read(head, 0, minOf(16L, len).toInt()) }
        parse(if (n >= 16) head else head.copyOf(n.coerceAtLeast(0)), len)
    } catch (t: Throwable) {
        Check.Bad("unreadable: ${t.javaClass.simpleName}")
    }

    /** True when [head] starts with the PNG signature (a `screencap -p` capture). */
    fun isPng(head: ByteArray): Boolean =
        head.size >= 8 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'N'.code.toByte() &&
            head[3] == 'G'.code.toByte() && head[4] == 0x0D.toByte() && head[5] == 0x0A.toByte() &&
            head[6] == 0x1A.toByte() && head[7] == 0x0A.toByte()

    /** [count] RGBA_8888 pixels from [src] at byte [srcOff] into ARGB ints in [dst] at [dstOff]. */
    fun toArgb(src: ByteArray, srcOff: Int, dst: IntArray, dstOff: Int, count: Int) {
        var s = srcOff
        for (i in dstOff until dstOff + count) {
            val r = src[s].toInt() and 0xFF
            val g = src[s + 1].toInt() and 0xFF
            val b = src[s + 2].toInt() and 0xFF
            val a = src[s + 3].toInt() and 0xFF
            dst[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            s += 4
        }
    }
}
