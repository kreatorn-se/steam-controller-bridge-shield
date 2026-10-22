package com.steamcontroller.android.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract radial-v1.1 amendment 3: unknown keys survive the Shield's round trip. */
class ConfigExtrasTest {
    @Test fun unknownTopLevelAndLiveSettingsKeysRoundTrip() {
        val hub = JSONObject("""
            {"schemaVersion":1,"exportedAt":5,"activeNamedProfileId":null,"namedProfiles":[],
             "hubOnly":{"theme":"dark"},
             "liveSettings":{"rumbleIntensity":80,"radial":{"left":null,"right":{"v":1,"slices":[]}},"future":[1,2]}}
        """)
        val root = ConfigExtras.unknown(hub, ConfigExtras.ROOT_KNOWN)
        val live = ConfigExtras.unknown(hub.getJSONObject("liveSettings"), ConfigExtras.LIVE_KNOWN)
        assertEquals(setOf("hubOnly"), JSONObject(root!!).keys().asSequence().toSet())
        assertEquals(setOf("radial", "future"), JSONObject(live!!).keys().asSequence().toSet())

        // The Shield's own export knows nothing of them; merging puts them back.
        val exportLive = JSONObject("""{"rumbleIntensity":80}""")
        ConfigExtras.merge(exportLive, live)
        assertTrue(exportLive.getJSONObject("radial").isNull("left"))
        assertEquals(2, exportLive.getJSONArray("future").length())
        val exportRoot = JSONObject("""{"schemaVersion":1,"liveSettings":{}}""")
        ConfigExtras.merge(exportRoot, root)
        assertEquals("dark", exportRoot.getJSONObject("hubOnly").getString("theme"))
    }

    @Test fun knownKeysAreNeverExtrasAndNeverOverwritten() {
        assertNull(ConfigExtras.unknown(JSONObject("""{"leftCal":{},"gyro":{},"mapping":{}}"""), ConfigExtras.LIVE_KNOWN))
        val target = JSONObject("""{"rumbleIntensity":50}""")
        ConfigExtras.merge(target, """{"rumbleIntensity":100,"x":1}""")
        assertEquals(50, target.getInt("rumbleIntensity"))
        assertEquals(1, target.getInt("x"))
        ConfigExtras.merge(target, "not json") // ignored
    }
}
