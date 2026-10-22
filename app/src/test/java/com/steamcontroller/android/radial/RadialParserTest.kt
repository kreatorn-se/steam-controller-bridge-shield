package com.steamcontroller.android.radial

import com.steamcontroller.android.input.NamedProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadialParserTest {
    private fun slices(n: Int) = (0 until n).joinToString(",") { """{"id":"s$it","label":"S$it","icon":null,"action":{"type":"button","button":"A","mode":"tap"}}""" }

    private fun menu(n: Int = 4, extra: String = "") = """{"v":1,"slices":[${slices(n)}],"deadzone":0.35,"rotation":0,"clickToFire":true,"haptics":true$extra}"""

    @Test fun fullContractExampleParses() {
        val json = """
        {"left": {"v":1,"deadzone":0.4,"rotation":-22.5,"clickToFire":true,"haptics":false,"futureKey":{"x":1},
          "slices":[
            {"id":"map","label":"Map","icon":"gi/lorc/treasure-map","color":"#1A9FFF","action":{"type":"button","button":"DPAD_UP","mode":"hold"}},
            {"id":"save","label":"Save","icon":null,"action":{"type":"key","keys":["LCTRL","S"],"mode":"tap"}},
            {"id":"m1","label":"Combo","action":{"type":"macro","steps":[{"button":"Y","act":"tap"},{"wait":150},{"keys":["I"],"act":"down"},{"wait":40},{"keys":["I"],"act":"up"}]}}
          ]},
         "right": null}
        """
        val p = RadialParser.parse(json)
        assertTrue(p.problems.toString(), p.problems.isEmpty())
        val m = p.config.left!!
        assertNull(p.config.right)
        assertEquals(3, m.slices.size)
        assertEquals(0.4f, m.deadzone, 1e-6f)
        assertEquals(-22.5f, m.rotation, 1e-6f)
        assertEquals(false, m.haptics)
        assertEquals("gi/lorc/treasure-map", m.slices[0].icon)
        assertEquals(0xFF1A9FFF.toInt(), m.slices[0].color)
        assertEquals(Action.Button(PadButton.DPAD_UP, Mode.HOLD), m.slices[0].action)
        val key = m.slices[1].action as Action.Key
        assertEquals(listOf(0xE0, 0x16), key.keys)
        val macro = m.slices[2].action as Action.Macro
        assertEquals(5, macro.steps.size)
        assertTrue(p.config.usesKeys)
        assertEquals(setOf("gi/lorc/treasure-map"), p.config.iconIds)
    }

    @Test fun absentOrNullMeansNoMenus() {
        assertTrue(RadialParser.parse(null as String?).config.isEmpty)
        assertTrue(RadialParser.parse("{}").config.isEmpty)
        assertTrue(RadialParser.parse("""{"left":null,"right":null}""").config.isEmpty)
        assertTrue(RadialParser.parse("not json").config.isEmpty)
    }

    @Test fun sliceCountLimits() {
        assertNull(RadialParser.parse("""{"left":${menu(1)}}""").config.left)
        assertNotNull(RadialParser.parse("""{"left":${menu(2)}}""").config.left)
        assertNotNull(RadialParser.parse("""{"left":${menu(12)}}""").config.left)
        val p = RadialParser.parse("""{"left":${menu(13)},"right":${menu(6)}}""")
        assertNull(p.config.left)
        assertEquals(6, p.config.right!!.slices.size) // the other pad is unaffected
        assertTrue(p.problems.single().startsWith("left menu skipped"))
    }

    @Test fun rangesAndVersion() {
        fun ok(m: String) = RadialParser.parse("""{"right":$m}""").config.right != null
        assertTrue(ok(menu().replace("0.35", "0.15")))
        assertTrue(ok(menu().replace("0.35", "0.7")))
        assertTrue(!ok(menu().replace("0.35", "0.1")))
        assertTrue(!ok(menu().replace("0.35", "0.75")))
        assertTrue(ok(menu().replace("\"rotation\":0", "\"rotation\":-180")))
        assertTrue(!ok(menu().replace("\"rotation\":0", "\"rotation\":181")))
        assertTrue(!ok(menu().replace("\"v\":1", "\"v\":2")))
        // Defaults when optional keys are missing.
        val m = RadialParser.parse("""{"right":{"slices":[${slices(3)}]}}""").config.right!!
        assertEquals(RadialParser.DEFAULT_DEADZONE, m.deadzone, 0f)
        assertEquals(0f, m.rotation, 0f)
        assertTrue(m.haptics)
    }

    @Test fun sliceRules() {
        fun one(slice: String) = RadialParser.parse("""{"left":{"v":1,"slices":[$slice,{"id":"b","label":"B","action":{"type":"button","button":"B"}}]}}""")
        assertNull(one("""{"id":"Bad Id","label":"x","action":{"type":"button","button":"A"}}""").config.left)
        assertNull(one("""{"id":"b","label":"dup","action":{"type":"button","button":"A"}}""").config.left)
        assertNull(one("""{"id":"a","label":"seventeen chars!!","action":{"type":"button","button":"A"}}""").config.left)
        assertNull(one("""{"id":"a","label":"x","action":{"type":"button","button":"Z"}}""").config.left)
        assertNull(one("""{"id":"a","label":"x","action":{"type":"button","button":"A","mode":"spam"}}""").config.left)
        assertNull(one("""{"id":"a","label":"x","action":{"type":"teleport"}}""").config.left)
        assertNull(one("""{"id":"a","label":"","icon":null,"action":{"type":"button","button":"A"}}""").config.left)
        // A bad icon or colour only drops that detail.
        val p = one("""{"id":"a","label":"x","icon":"http://evil/x.svg","color":"blue","action":{"type":"button","button":"A"}}""")
        val s = p.config.left!!.slices[0]
        assertNull(s.icon)
        assertNull(s.color)
        assertEquals(2, p.problems.size)
    }

    @Test fun keyRules() {
        fun keys(k: String) = RadialParser.parse("""{"left":{"slices":[{"id":"a","label":"a","action":{"type":"key","keys":$k}},{"id":"b","label":"b","action":{"type":"key","keys":["ESC"]}}]}}""").config.left
        assertNotNull(keys("""["LCTRL","LSHIFT","S"]"""))
        assertNull(keys("""[]"""))
        assertNull(keys("""["A","B","C","D","E"]"""))
        assertNull(keys("""["S","LCTRL"]""")) // modifiers first
        assertNull(keys("""["NOPE"]"""))
        assertNull(keys("""["A","A"]"""))
    }

    @Test fun macroLimits() {
        fun macro(steps: String) = RadialParser.parse("""{"left":{"slices":[{"id":"a","label":"a","action":{"type":"macro","steps":[$steps]}},{"id":"b","label":"b","action":{"type":"button","button":"A"}}]}}""").config.left
        assertNotNull(macro("""{"wait":10},{"button":"A","act":"tap"}"""))
        assertNull(macro(""))
        assertNull(macro("""{"wait":9}"""))
        assertNull(macro("""{"wait":5001}"""))
        assertNull(macro("""{"wait":12.5}"""))
        assertNull(macro("""{"wait":10,"button":"A"}""")) // exactly one kind per step
        assertNull(macro((1..33).joinToString(",") { """{"button":"A","act":"down"}""" }))
        assertNotNull(macro((1..32).joinToString(",") { """{"button":"A","act":"down"}""" }))
        // 2 × 5000 ms = 10 s is the limit; one more tap goes over.
        assertNotNull(macro("""{"wait":5000},{"wait":5000}"""))
        assertNull(macro("""{"wait":5000},{"wait":5000},{"button":"A","act":"tap"}"""))
    }

    @Test fun namedProfileKeepsRadialAndUnknownKeys() {
        val radial = """{"left":${menu(3)}}"""
        val src = JSONObject("""{"id":"p1","name":"Elden Ring","profileId":0,"radial":$radial,"newHubThing":{"a":[1,2]}}""")
        val p = NamedProfile.fromJson(src)
        assertNotNull(p.radialJson)
        assertEquals(3, RadialParser.parse(p.radialJson).config.left!!.slices.size)
        val back = p.toJson()
        assertTrue(back.has("radial"))
        assertEquals(JSONObject(radial).getJSONObject("left").getJSONArray("slices").length(),
            back.getJSONObject("radial").getJSONObject("left").getJSONArray("slices").length())
        assertEquals(2, back.getJSONObject("newHubThing").getJSONArray("a").length())
        // A profile without menus stays without the key.
        assertTrue(!NamedProfile.fromJson(JSONObject("""{"id":"p2","name":"x"}""")).toJson().has("radial"))
    }
}
