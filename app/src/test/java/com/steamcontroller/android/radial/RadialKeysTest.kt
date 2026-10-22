package com.steamcontroller.android.radial

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadialKeysTest {
    @Test fun usages() {
        assertEquals(0x04, RadialKeys.usage("A"))
        assertEquals(0x1D, RadialKeys.usage("Z"))
        assertEquals(0x1E, RadialKeys.usage("1"))
        assertEquals(0x26, RadialKeys.usage("9"))
        assertEquals(0x27, RadialKeys.usage("0"))
        assertEquals(0x3A, RadialKeys.usage("F1"))
        assertEquals(0x45, RadialKeys.usage("F12"))
        assertEquals(0x29, RadialKeys.usage("ESC"))
        assertEquals(0x28, RadialKeys.usage("ENTER"))
        assertEquals(0x2C, RadialKeys.usage("SPACE"))
        assertEquals(0x35, RadialKeys.usage("GRAVE"))
        assertEquals(0x52, RadialKeys.usage("UP"))
        assertEquals(0x4F, RadialKeys.usage("RIGHT"))
        assertEquals(0x59, RadialKeys.usage("KP1"))
        assertEquals(0x62, RadialKeys.usage("KP0"))
        assertEquals(0x58, RadialKeys.usage("KPENTER"))
        assertEquals(0xE0, RadialKeys.usage("LCTRL"))
        assertEquals(0xE7, RadialKeys.usage("RMETA"))
        assertNull(RadialKeys.usage("a"))
        assertNull(RadialKeys.usage("F13"))
        assertNull(RadialKeys.usage("POWER"))
    }

    @Test fun everyContractNameIsMapped() {
        val names = (('A'..'Z').map { "$it" } + (0..9).map { "$it" } + (1..12).map { "F$it" } +
            "ESC TAB CAPSLOCK SPACE ENTER BACKSPACE DELETE INSERT HOME END PAGEUP PAGEDOWN UP DOWN LEFT RIGHT MINUS EQUAL LBRACKET RBRACKET BACKSLASH SEMICOLON APOSTROPHE GRAVE COMMA PERIOD SLASH LCTRL LSHIFT LALT LMETA RCTRL RSHIFT RALT RMETA KPENTER KPPLUS KPMINUS KPASTERISK KPSLASH".split(" ") +
            (0..9).map { "KP$it" })
        for (n in names) assertTrue(n, RadialKeys.usage(n) != null)
        assertEquals(names.size, RadialKeys.NAMES.size)
        // Every ordinary usage fits the boot keyboard's 0..0x65 key array.
        assertTrue(RadialKeys.ALLOWED_USAGES.all { it in 0x04..0x65 })
    }

    @Test fun reportsAndTheAllowlist() {
        val (mods, keys) = RadialKeys.report(listOf(0xE0, 0xE1, 0x16))
        assertEquals(0b11, mods)
        assertArrayEquals(intArrayOf(0x16), keys)
        assertEquals(6, RadialKeys.report((0x04..0x0C).toList()).second.size)
        assertTrue(RadialKeys.reportAllowed(0xFF, intArrayOf(0x04, 0x29)))
        assertTrue(RadialKeys.reportAllowed(0, IntArray(0)))
        assertFalse(RadialKeys.reportAllowed(0x100, IntArray(0)))
        assertFalse(RadialKeys.reportAllowed(0, IntArray(7) { 0x04 }))
        assertFalse(RadialKeys.reportAllowed(0, intArrayOf(0x66))) // Power: never
        assertFalse(RadialKeys.reportAllowed(0, intArrayOf(0xE0))) // modifiers go in the byte
        assertFalse(RadialKeys.reportAllowed(0, intArrayOf(0x32))) // non-US # not in the contract
    }
}
