package com.steamcontroller.android.ui.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.steamcontroller.android.hub.GamehubStore
import com.steamcontroller.android.hub.GamehubTv
import java.util.concurrent.Executors

/**
 * The home data shared by the home page and the Now-playing chip: the cached copy first (read off
 * the main thread), then the hub's, refreshed every minute while someone is watching.
 * Main-thread API.
 */
object HomeRepo {
    sealed class State {
        /** Nothing cached and nothing fetched yet. */
        object Loading : State()
        /** Not linked, or the hub no longer accepts this Shield's token. */
        object NotLinked : State()
        data class Ready(val home: GamehubTv.Home, val live: Boolean, val problem: GamehubTv.Res.Err?) : State()
        data class Failed(val err: GamehubTv.Res.Err) : State()
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val listeners = LinkedHashSet<(State) -> Unit>()
    private var fetching = false
    private var fetchedAt = 0L
    private var loadedCache = false
    /** Bumped by [forget], so results from before an unlink are thrown away. */
    private var generation = 0
    var state: State = State.Loading
        private set

    private const val PERIOD = 60_000L

    fun observe(c: Context, l: (State) -> Unit) {
        listeners.add(l)
        ensureCache(c)
        l(state)
    }

    fun remove(l: (State) -> Unit) { listeners.remove(l) }

    private fun publish(s: State) {
        state = s
        for (l in listeners.toList()) l(s)
    }

    private fun ensureCache(c: Context) {
        if (loadedCache) return
        loadedCache = true
        val app = c.applicationContext
        if (!GamehubStore.isLinked(app)) { publish(State.NotLinked); return }
        val gen = generation
        io.execute {
            val cached = GamehubTv.cachedHome(app)
            main.post {
                // Only if the network has not already answered.
                if (cached != null && gen == generation && state !is State.Ready) publish(State.Ready(cached, live = false, problem = null))
            }
        }
    }

    /** Fetch now unless a fetch is running or one finished less than [minAgeMs] ago. */
    fun refresh(c: Context, minAgeMs: Long = 0L) {
        val app = c.applicationContext
        ensureCache(app)
        if (!GamehubStore.isLinked(app)) { if (state !is State.NotLinked) publish(State.NotLinked); return }
        // Without good data there is nothing to protect: always ask again.
        val fresh = state is State.Ready && SystemClock.uptimeMillis() - fetchedAt < minAgeMs
        if (fetching || fresh) return
        fetching = true
        val gen = generation
        io.execute {
            val r = GamehubTv.home(app)
            val cached = if (r is GamehubTv.Res.Err && state !is State.Ready) GamehubTv.cachedHome(app) else null
            main.post {
                fetching = false
                if (gen != generation) return@post
                fetchedAt = SystemClock.uptimeMillis()
                val prev = state
                publish(when (r) {
                    is GamehubTv.Res.Ok -> State.Ready(r.value, live = true, problem = null)
                    is GamehubTv.Res.Err -> when {
                        r.fail == GamehubTv.Fail.NOT_LINKED || r.fail == GamehubTv.Fail.UNAUTHORIZED -> State.NotLinked
                        prev is State.Ready -> prev.copy(live = false, problem = r)
                        cached != null -> State.Ready(cached, live = false, problem = r)
                        else -> State.Failed(r)
                    }
                })
            }
        }
    }

    private var ticking: Context? = null
    private val tick = object : Runnable {
        override fun run() {
            val c = ticking ?: return
            refresh(c, PERIOD - 1000L)
            main.postDelayed(this, PERIOD)
        }
    }

    /** Periodic refresh while the activity is resumed. */
    fun start(c: Context) {
        ticking = c.applicationContext
        main.removeCallbacks(tick)
        refresh(c, 15_000L)
        main.postDelayed(tick, PERIOD)
    }

    fun stop() {
        ticking = null
        main.removeCallbacks(tick)
    }

    /** Linked again: start over. */
    fun reset(c: Context) {
        generation++
        loadedCache = false
        fetchedAt = 0L
        fetching = false
        state = State.Loading
        refresh(c)
    }

    /** Unlinked or refused: drop the data, the offline copies and the images. Any thread. */
    fun forget(c: Context) {
        val app = c.applicationContext
        main.post {
            generation++
            loadedCache = true // nothing to load: there is no link
            fetching = false
            fetchedAt = 0L
            publish(State.NotLinked)
            Img.clearAll()
            io.execute { GamehubTv.clearCache(app) }
        }
    }

    /** Wires the client's 401 signal to [forget]; call once at start. */
    fun install() {
        GamehubTv.unauthorizedListener = { c -> forget(c) }
    }
}
