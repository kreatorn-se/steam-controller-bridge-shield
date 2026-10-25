package com.steamcontroller.android.hub

import android.content.Context
import android.util.Log
import com.steamcontroller.android.R
import com.steamcontroller.android.ui.pill.GuideOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Enshrouded "next steps": three short lines Claude writes on Gamehub's Enshrouded page, once per
 * save (`GET /api/device/ensh/guide`). Gamehub needs its page to decode a save first, so right
 * after a session the newest guide may still be from the save before: it is always shown with its
 * date. Shown on the Gamehub page's "Enshrouded saves" card and, briefly, on the TV after a
 * session once [SteamCloud] has sent the saves.
 */
object EnshGuide {
    private const val TAG = "EnshGuide"

    /** The last guide fetched in this process, so the card has something to show at once. */
    @Volatile var last: GamehubClient.Guide? = null
        private set

    /** Blocking; off the main thread. Null when not linked, none yet, or the hub is unreachable. */
    fun fetch(c: Context): GamehubClient.Guide? {
        if (!GamehubStore.isLinked(c)) return null
        val g = GamehubClient(GamehubStore.url(c), GamehubStore.token(c)).getEnshGuide()
        if (g != null) last = g
        return g
    }

    suspend fun load(c: Context): GamehubClient.Guide? = withContext(Dispatchers.IO) { fetch(c) }

    /** "From your save of 24 Oct, 19:24". */
    fun dateLine(c: Context, g: GamehubClient.Guide): String? {
        if (g.createdAt <= 0L) return null
        val f = SimpleDateFormat("d MMM, HH:mm", Locale.ENGLISH)
        return c.getString(R.string.ensh_guide_from, f.format(Date(g.createdAt)))
    }

    /** "Will in Zenith", or whichever half is known. */
    fun who(c: Context, g: GamehubClient.Guide): String? = when {
        g.character.isNotEmpty() && g.world.isNotEmpty() -> c.getString(R.string.ensh_guide_who, g.character, g.world)
        g.character.isNotEmpty() -> g.character
        g.world.isNotEmpty() -> g.world
        else -> null
    }

    /**
     * From [SteamCloud] when the pass after a session is done: fetch the guide and show it on the TV
     * for a few seconds. Skipped when it has no lines, or when another game has started meanwhile
     * (the card would take the controller's keys from it).
     */
    fun afterSession(context: Context) {
        val app = context.applicationContext
        Thread({
            val g = try { fetch(app) } catch (t: Throwable) { Log.w(TAG, "fetch", t); null }
            if (g == null || g.lines.isEmpty()) return@Thread
            if (SteamCloud.playingAppId(app) != 0L) {
                Diag.note("ensh guide: a game is running, not showing it", tag = TAG)
                return@Thread
            }
            val sub = listOfNotNull(who(app, g), dateLine(app, g)).joinToString(" · ").takeIf { it.isNotEmpty() }
            GuideOverlay.show(
                app,
                title = app.getString(R.string.ensh_guide_title),
                sub = sub,
                lines = g.lines.take(3),
                hint = app.getString(R.string.ensh_guide_close),
                fallback = app.getString(R.string.ensh_guide_pill),
            )
        }, "ensh-guide").apply { isDaemon = true; start() }
    }
}
