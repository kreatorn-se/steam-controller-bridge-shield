package com.steamcontroller.android.radial

import com.steamcontroller.android.parser.Buttons
import com.steamcontroller.android.parser.SteamControllerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RadialEngineTest {
    private val log = ArrayList<String>()
    private val ui = object : RadialEngine.Ui {
        override fun open(left: Boolean, menu: Menu, test: Boolean) { log += "open ${side(left)}${if (test) " test" else ""}" }
        override fun highlight(left: Boolean, index: Int) { log += "hi ${side(left)} $index" }
        override fun fired(left: Boolean, index: Int) { log += "fired ${side(left)} $index" }
        override fun close(left: Boolean) { log += "close ${side(left)}" }
    }
    private val fire = object : RadialEngine.Fire {
        override fun press(left: Boolean, slice: Slice) { log += "press ${slice.id}" }
        override fun release(left: Boolean) { log += "release ${side(left)}" }
        override fun releaseAll() { log += "releaseAll" }
    }
    private fun side(left: Boolean) = if (left) "L" else "R"

    private val menu = Menu((0 until 4).map { Slice("s$it", "S$it", null, null, Action.Button(PadButton.A, Mode.TAP)) })

    private fun right(x: Int, y: Int, touch: Boolean = true, click: Boolean = false, extra: Int = Buttons.A) =
        SteamControllerState(
            buttons = extra or (if (touch) Buttons.TP_RT else 0) or (if (click) Buttons.TP_RT_CLICK else 0),
            rightPadX = x.toShort(), rightPadY = y.toShort(), leftPadX = 1234, leftPadY = 4321,
        )

    @Test fun touchAimClickLift() {
        val e = RadialEngine(ui, fire)
        e.setConfig(RadialConfig(null, menu))
        log.clear()
        val out = e.process(right(0, 30000))
        assertEquals(listOf("open R", "hi R 0"), log)
        // The pad is taken out of the frame; everything else passes through.
        assertEquals(0, out.buttons and (Buttons.TP_RT or Buttons.TP_RT_CLICK))
        assertTrue(out.buttons and Buttons.A != 0)
        assertEquals(0.toShort(), out.rightPadX)
        assertEquals(1234.toShort(), out.leftPadX)
        e.process(right(30000, 0))
        e.process(right(30000, 0, click = true))
        e.process(right(30000, 0, click = false))
        e.process(right(30000, 0)) // still touching after the fire: stays consumed, no reopen
        e.process(right(0, 0, touch = false))
        assertEquals(listOf("open R", "hi R 0", "hi R 1", "fired R 1", "press s1", "release R"), log)
        // Lifted: the next frame passes through untouched.
        val idle = right(0, 0, touch = false)
        assertSame(idle, e.process(idle))
    }

    @Test fun liftWithoutClickCloses() {
        val e = RadialEngine(ui, fire)
        e.setConfig(RadialConfig(null, menu))
        log.clear()
        e.process(right(0, 30000))
        e.process(right(0, 0, touch = false))
        assertEquals(listOf("open R", "hi R 0", "close R"), log)
    }

    @Test fun clickInTheDeadzoneDoesNothing() {
        val e = RadialEngine(ui, fire)
        e.setConfig(RadialConfig(null, menu))
        log.clear()
        e.process(right(100, 100))
        e.process(right(100, 100, click = true))
        assertEquals(listOf("open R"), log)
    }

    @Test fun padsWithoutMenusAreUntouched() {
        val e = RadialEngine(ui, fire)
        e.setConfig(RadialConfig(menu, null))
        log.clear()
        val s = right(0, 30000)
        assertSame(s, e.process(s))
        assertTrue(log.isEmpty())
    }

    @Test fun testModeShowsButNeverFires() {
        val e = RadialEngine(ui, fire)
        e.testMode = true
        e.process(right(0, 30000))
        e.process(right(0, 30000, click = true))
        e.process(right(0, 30000, click = false))
        assertEquals(listOf("open R test", "hi R 0", "fired R 0"), log)
    }

    @Test fun newMenusCloseAndRelease() {
        val e = RadialEngine(ui, fire)
        e.setConfig(RadialConfig(null, menu))
        e.process(right(0, 30000))
        log.clear()
        e.setConfig(RadialConfig.EMPTY)
        assertEquals(listOf("close R", "releaseAll"), log)
    }
}
