package com.steamcontroller.android.hub

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.steamcontroller.android.backup.BackupManager
import com.steamcontroller.android.ui.pill.Pill
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Background loop that runs while the controller service runs:
 *  - pulls controller setup changed in Gamehub and applies it within about five seconds,
 *  - pushes setup changed on the Shield back to Gamehub,
 *  - drains the capture upload queue.
 * UInputGamepad re-reads settings every 250 ms, so imported mappings take effect without a restart.
 */
object GamehubSync {
    private const val TAG = "GamehubSync"
    private const val INTERVAL_MS = 5_000L
    private const val UPLOADS_PER_TICK = 4

    // A capture is announced as "saved" only when it was taken this recently: a backlog uploaded
    // after a long offline spell must not pop a pill in the middle of a game.
    private const val ANNOUNCE_MAX_AGE_MS = 10 * 60_000L

    // Uploads finishing within this window share one pill ("3 SAVED TO GAMEHUB").
    private const val ANNOUNCE_COALESCE_MS = 1_200L

    @Volatile private var thread: HandlerThread? = null
    @Volatile private var handler: Handler? = null
    // Guards network passes only. start()/stop() use the object monitor and never wait on this,
    // so stopping the service from the main thread can't block behind an upload.
    private val passLock = Any()
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun start(context: Context) {
        app = context.applicationContext
        if (thread != null) return
        val t = HandlerThread("gamehub-sync").also { it.start() }
        thread = t
        handler = Handler(t.looper)
        handler?.post(tick)
        Log.i(TAG, "started")
    }

    @Synchronized
    fun stop() {
        handler?.removeCallbacksAndMessages(null)
        thread?.quit()
        thread = null
        handler = null
    }

    /** Run a sync pass now (after a capture, after linking). */
    fun kick(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
        val h = handler
        if (h != null) {
            h.removeCallbacks(tick)
            h.post(tick)
        } else {
            // Service not running: do one pass on a throwaway thread.
            Thread({ runOnce() }, "gamehub-once").apply { isDaemon = true; start() }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            runOnce()
            handler?.let {
                it.removeCallbacks(this) // a kick() during this pass must not start a second loop
                it.postDelayed(this, INTERVAL_MS)
            }
        }
    }

    private fun runOnce() = synchronized(passLock) {
        val ctx = app
        val token = GamehubStore.token(ctx) ?: return@synchronized
        val client = GamehubClient(GamehubStore.url(ctx), token)
        val configOk = guarded(ctx) { syncConfig(ctx, client) }
        if (GamehubStore.token(ctx) == null) return@synchronized // unlinked by a 401 just now
        val uploadsOk = guarded(ctx) { uploadPending(ctx, client) }
        if (configOk && uploadsOk) GamehubStore.markOk(ctx)
        Unit
    }

    private inline fun guarded(ctx: Context, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: GamehubClient.HubException) {
        if (e.status == 401) {
            Log.w(TAG, "token rejected; unlinking")
            GamehubStore.clearLink(ctx)
            Pill.show(ctx, Pill.Kind.FAILED, "GAMEHUB UNLINKED")
        } else {
            GamehubStore.markError(ctx, e.message ?: "HTTP ${e.status}")
        }
        false
    } catch (t: Throwable) {
        GamehubStore.markError(ctx, t.message ?: t.javaClass.simpleName)
        Log.w(TAG, "sync step failed: ${t.message}")
        false
    }

    // ---------------------------------------------------------------- controller setup

    private fun syncConfig(ctx: Context, client: GamehubClient) {
        val rev = GamehubStore.configRev(ctx)
        client.getConfig(have = rev, applied = rev)?.let { remote ->
            applyRemote(ctx, remote.rev, remote.json)
            return
        }
        val local = BackupManager.export(ctx)
        val hash = hashOf(local)
        if (hash == GamehubStore.configHash(ctx)) return
        when (val r = client.putConfig(rev, local)) {
            is GamehubClient.PutResult.Saved -> GamehubStore.setConfigState(ctx, r.rev, hash)
            is GamehubClient.PutResult.Conflict -> applyRemote(ctx, r.rev, r.json)
        }
    }

    private fun applyRemote(ctx: Context, rev: Int, json: String) {
        // Switching between gamepad and desktop mode needs a service restart, so the hub never
        // changes it; everything else applies live.
        val root = JSONObject(json)
        val remote = root.optJSONObject("liveSettings")
        remote?.remove("gamepadProfileId")
        // Stick centres and gyro bias are measured on the Shield; never take them from the hub.
        val local = JSONObject(BackupManager.export(ctx)).optJSONObject("liveSettings")
        if (remote != null && local != null) {
            for (side in listOf("leftCal", "rightCal")) {
                val r = remote.optJSONObject(side) ?: continue
                val l = local.optJSONObject(side) ?: continue
                r.put("cx", l.optInt("cx", 0)).put("cy", l.optInt("cy", 0))
            }
            val rg = remote.optJSONObject("gyro")
            val lg = local.optJSONObject("gyro")
            if (rg != null && lg != null) {
                rg.put("biasX", lg.optDouble("biasX", 0.0)).put("biasY", lg.optDouble("biasY", 0.0))
            }
        }
        BackupManager.import(ctx, root.toString())
        GamehubStore.setConfigState(ctx, rev, hashOf(BackupManager.export(ctx)))
        Log.i(TAG, "applied hub config rev $rev")
        Pill.show(ctx, Pill.Kind.INFO, "SETUP UPDATED FROM GAMEHUB")
    }

    private fun hashOf(exportJson: String): String {
        val o = JSONObject(exportJson)
        o.remove("exportedAt")
        val d = MessageDigest.getInstance("SHA-256").digest(o.toString().toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- captures

    private fun uploadPending(ctx: Context, client: GamehubClient) {
        // Captures whose level fix was cut short by a process kill go back through it first.
        CaptureStore.dir(ctx)?.let { d -> CaptureLevels.resumeOrphans(d) { ok -> if (ok) kick(ctx) } }
        for (p in CaptureStore.pending(ctx).take(UPLOADS_PER_TICK)) {
            // screencap may still be writing: leave files younger than two seconds for next tick.
            if (System.currentTimeMillis() - p.png.lastModified() < 2_000) continue
            val preview = try { CaptureStore.preview(p.png) } catch (t: Throwable) { null }
            if (preview != null) { p.meta.put("width", preview.second); p.meta.put("height", preview.third) }
            try {
                client.uploadShot(p.png, preview?.first, p.meta)
                CaptureStore.remove(ctx, p.id)
                Log.i(TAG, "uploaded ${p.id}")
                val capturedAt = p.meta.optLong("capturedAt", 0L)
                if (capturedAt > 0 && System.currentTimeMillis() - capturedAt in 0..ANNOUNCE_MAX_AGE_MS) {
                    announceSaved()
                }
            } catch (e: GamehubClient.HubException) {
                // The hub will never accept this file (bad image, too large): park it so newer
                // captures are not stuck behind it. Auth and server errors propagate and retry.
                if (e.status in 400..499 && e.status != 401 && e.status != 408 && e.status != 429) {
                    CaptureStore.reject(ctx, p.id)
                    Log.w(TAG, "hub rejected ${p.id} (${e.status}); moved aside")
                } else {
                    throw e
                }
            }
        }
    }

    // ---------------------------------------------------------------- "saved" pill

    private var savedPending = 0 // main thread only

    private val showSaved = Runnable {
        val n = savedPending
        savedPending = 0
        if (n > 0) Pill.show(app, Pill.Kind.SAVED, if (n == 1) "SAVED TO GAMEHUB" else "$n SAVED TO GAMEHUB")
    }

    private fun announceSaved() {
        main.post {
            savedPending++
            main.removeCallbacks(showSaved)
            main.postDelayed(showSaved, ANNOUNCE_COALESCE_MS)
        }
    }
}
