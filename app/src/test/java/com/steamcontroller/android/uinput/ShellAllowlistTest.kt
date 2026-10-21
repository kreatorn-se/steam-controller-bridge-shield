package com.steamcontroller.android.uinput

import com.steamcontroller.android.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellAllowlistTest {
    private val queue = "/storage/emulated/0/Android/data/${BuildConfig.APPLICATION_ID}/files/captures"
    private val queue2 = "/sdcard/Android/data/${BuildConfig.APPLICATION_ID}/files/captures"
    private val pics = ShellAllowlist.SCREENSHOT_DIR

    private fun allowed(vararg a: String) = ShellAllowlist.isAllowed(arrayOf(*a))

    @Test
    fun rawScreencapIntoTheQueueAndScreenshots() {
        assertTrue(allowed("screencap", "$queue/cap_20261021_150322_362.raw"))
        assertTrue(allowed("screencap", "$queue2/cap_20261021_150322_362.raw"))
        assertTrue(allowed("screencap", "$pics/cap_20261021_150322_362.raw"))
    }

    @Test
    fun pngScreencapStillAllowed() {
        assertTrue(allowed("screencap", "-p", "$queue/tmp_cap_1.png"))
        assertTrue(allowed("screencap", "-p", "$pics/cap_1.png"))
    }

    @Test
    fun anythingElseIsRefused() {
        assertFalse(allowed("screencap", "$queue/cap_1.png"))          // the 2-arg form is raw only
        assertFalse(allowed("screencap", "-p", "$queue/cap_1.raw"))
        assertFalse(allowed("screencap", "$queue/../cap_1.raw"))
        assertFalse(allowed("screencap", "$queue/sub/cap_1.raw"))
        assertFalse(allowed("screencap", "/data/local/tmp/cap_1.raw"))
        assertFalse(allowed("screencap", "/sdcard/cap_1.raw"))
        assertFalse(allowed("screencap", "$queue/cap 1.raw"))
        assertFalse(allowed("screencap", "$queue/cap_1.raw", "extra"))
        assertFalse(allowed("screencap"))
        assertFalse(allowed("screencap", "-d", "0", "$queue/cap_1.raw"))
        assertFalse(ShellAllowlist.isAllowed(arrayOf("screencap", "$queue/cap_1.raw"), withStdin = true))
    }
}
