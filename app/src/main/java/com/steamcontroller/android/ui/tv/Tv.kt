package com.steamcontroller.android.ui.tv

import android.content.Context
import android.view.KeyEvent
import com.steamcontroller.android.hub.GamehubTv
import com.steamcontroller.android.hub.GamehubTv.Shot
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Show mode and screensaver settings that belong to this TV, not to a controller profile. */
object TvPrefs {
    private fun p(c: Context) = c.getSharedPreferences("tv", Context.MODE_PRIVATE)

    /** Time per picture, as on the web slideshow. */
    val INTERVALS = intArrayOf(3, 5, 8, 12, 20, 30, 60)

    fun interval(c: Context): Int = p(c).getInt("interval", 8)
    fun setInterval(c: Context, s: Int) = p(c).edit().putInt("interval", s).apply()
    fun kenBurns(c: Context): Boolean = p(c).getBoolean("ken_burns", true)
    fun setKenBurns(c: Context, on: Boolean) = p(c).edit().putBoolean("ken_burns", on).apply()
}

/** Capture dates in the time zone they were taken in. */
object Fmt {
    private val day = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())
    private val dayShort = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

    private fun at(s: Shot) = Instant.ofEpochMilli(s.capturedAt).atOffset(ZoneOffset.ofTotalSeconds((s.tzOffsetMin * 60).coerceIn(-64800, 64800)))

    fun date(s: Shot): String = if (s.capturedAt <= 0) "" else day.format(at(s))
    fun dateShort(s: Shot): String = if (s.capturedAt <= 0) "" else dayShort.format(at(s))
    fun time(s: Shot): String = if (s.capturedAt <= 0) "" else clock.format(at(s))

    /** "Sat 12 Oct 2026 · 21:44 · with Vincent and Kent". */
    fun meta(s: Shot, withPeople: Boolean = true): String = buildString {
        if (s.capturedAt > 0) append(date(s)).append("  ·  ").append(time(s))
        if (withPeople && s.people.isNotEmpty()) {
            if (isNotEmpty()) append("  ·  ")
            append("with ").append(people(s.people))
        }
    }

    fun people(p: List<String>): String = when (p.size) {
        0 -> ""
        1 -> p[0]
        else -> p.dropLast(1).joinToString(", ") + " and " + p.last()
    }

    /** "1 h 12 min" since [ms]. */
    fun elapsed(ms: Long): String {
        val m = ((System.currentTimeMillis() - ms) / 60_000L).coerceAtLeast(0)
        return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
    }

    fun count(n: Int): String = if (n == 1) "1 capture" else "$n captures"
}

/** What show mode plays: a collection by key, or a fixed list (the home hero). */
data class CollRef(val collection: String?, val title: String, val items: List<Shot>? = null)

data class ShowRequest(
    val siblings: List<CollRef>,
    val index: Int,
    /** Start at this capture when it is in the first page. */
    val startShotId: String? = null,
    /** Already-loaded captures of siblings[index], so the first image appears at once. */
    val preload: List<Shot>? = null,
    val preloadNext: String? = null,
    /** The in-app screensaver: no caption or HUD, slow crossfades, any key exits. */
    val calm: Boolean = false,
    val intervalSec: Int? = null,
    val shuffle: Boolean = false,
)

/** Gamepad and remote keys, as the virtual Xbox pad and the Shield remote send them. */
object Keys {
    fun isSelect(k: Int) = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER ||
        k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A
    fun isBack(k: Int) = k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_BUTTON_B || k == KeyEvent.KEYCODE_ESCAPE
    fun isMenu(k: Int) = k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_BUTTON_START
    fun isPlayPause(k: Int) = k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_MEDIA_PLAY ||
        k == KeyEvent.KEYCODE_MEDIA_PAUSE || k == KeyEvent.KEYCODE_SPACE
    fun isFavorite(k: Int) = k == KeyEvent.KEYCODE_BUTTON_Y
    /** Collections: LT/RT when the pad sends them as keys (also read as axes), media and page keys. */
    fun isPrevColl(k: Int) = k == KeyEvent.KEYCODE_BUTTON_L2 || k == KeyEvent.KEYCODE_MEDIA_PREVIOUS || k == KeyEvent.KEYCODE_PAGE_UP
    fun isNextColl(k: Int) = k == KeyEvent.KEYCODE_BUTTON_R2 || k == KeyEvent.KEYCODE_MEDIA_NEXT || k == KeyEvent.KEYCODE_PAGE_DOWN
    fun isLonger(k: Int) = k == KeyEvent.KEYCODE_PLUS || k == KeyEvent.KEYCODE_NUMPAD_ADD || k == KeyEvent.KEYCODE_EQUALS || k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
    fun isShorter(k: Int) = k == KeyEvent.KEYCODE_MINUS || k == KeyEvent.KEYCODE_NUMPAD_SUBTRACT || k == KeyEvent.KEYCODE_MEDIA_REWIND
}

/** Pixel sizes images are decoded at, from dp. */
fun Context.px(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

fun GamehubTv.Shot.gameOrUnknown(): String = game ?: "Unsorted"

/**
 * Timings of the home's first load, from process start, in the Diagnostics history (and logcat
 * as GamehubTV) so they can be measured on the Shield: cold start → cache read → first render →
 * /home answered (network and parse) → hero image → first row images. Each phase once per process.
 */
object HomeTiming {
    private val seen = HashSet<String>()
    private val t0: Long = try { android.os.Process.getStartElapsedRealtime() } catch (_: Throwable) { android.os.SystemClock.elapsedRealtime() }

    @Synchronized
    fun mark(phase: String, detail: String = "") {
        if (!seen.add(phase)) return
        val ms = android.os.SystemClock.elapsedRealtime() - t0
        val line = "home $phase +${ms}ms" + (if (detail.isNotEmpty()) " $detail" else "")
        android.util.Log.i("GamehubTV", line)
        com.steamcontroller.android.service.Diagnostics.record("tv", line)
        com.steamcontroller.android.hub.Diag.note(line, "i", "Home")
    }
}
