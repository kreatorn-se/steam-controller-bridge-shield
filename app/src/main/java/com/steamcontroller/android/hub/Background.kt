package com.steamcontroller.android.hub

import android.content.Context
import com.steamcontroller.android.update.Updater
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The few things that run while the app or the controller service is alive: diagnostics batches
 * (every 2 min), the Steam profile switch (every 30 s, 10 s while GeForce NOW is in front) and the
 * update check (at most every 6 h). Started by MainActivity and by ControllerService; it stops
 * when neither is around.
 */
object Background {
    private val owners = HashSet<String>()
    private var exec: ScheduledExecutorService? = null

    @Synchronized
    fun start(c: Context, owner: String) {
        owners.add(owner)
        if (exec != null) return
        val app = c.applicationContext
        Diag.install(app)
        val e = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "gamehub-bg").apply { isDaemon = true } }
        exec = e
        e.scheduleWithFixedDelay({ safe { Diag.flush() } }, 30, 120, TimeUnit.SECONDS)
        e.scheduleWithFixedDelay({ safe { Updater.checkIfDue(app) } }, 5, 30 * 60, TimeUnit.SECONDS)
        fun steam(delayMs: Long) {
            if (e.isShutdown) return
            e.schedule({
                val next = try { SteamSwitch.tick(app) } catch (_: Throwable) { 30_000L }
                steam(next)
            }, delayMs, TimeUnit.MILLISECONDS)
        }
        steam(3_000L)
    }

    @Synchronized
    fun stop(owner: String) {
        owners.remove(owner)
        if (owners.isNotEmpty()) return
        exec?.shutdownNow()
        exec = null
    }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (_: Throwable) {}
    }
}
