package com.steamcontroller.android.hub

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.steamcontroller.android.backup.BackupManager
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

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
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
        thread?.quitSafely()
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
            handler?.postDelayed(this, INTERVAL_MS)
        }
    }

    @Synchronized
    private fun runOnce() {
        val ctx = app
        val token = GamehubStore.token(ctx) ?: return
        val client = GamehubClient(GamehubStore.url(ctx), token)
        try {
            syncConfig(ctx, client)
            uploadPending(ctx, client)
            GamehubStore.markOk(ctx)
        } catch (e: GamehubClient.HubException) {
            if (e.status == 401) {
                Log.w(TAG, "token rejected; unlinking")
                GamehubStore.clearLink(ctx)
                toast("Gamehub unlinked this Shield")
            } else {
                GamehubStore.markError(ctx, e.message ?: "HTTP ${e.status}")
            }
        } catch (t: Throwable) {
            GamehubStore.markError(ctx, t.message ?: t.javaClass.simpleName)
            Log.w(TAG, "sync failed: ${t.message}")
        }
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
        root.optJSONObject("liveSettings")?.remove("gamepadProfileId")
        BackupManager.import(ctx, root.toString())
        GamehubStore.setConfigState(ctx, rev, hashOf(BackupManager.export(ctx)))
        Log.i(TAG, "applied hub config rev $rev")
        toast("Controller setup updated from Gamehub")
    }

    private fun hashOf(exportJson: String): String {
        val o = JSONObject(exportJson)
        o.remove("exportedAt")
        val d = MessageDigest.getInstance("SHA-256").digest(o.toString().toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- captures

    private fun uploadPending(ctx: Context, client: GamehubClient) {
        for (p in CaptureStore.pending(ctx).take(UPLOADS_PER_TICK)) {
            // screencap may still be writing: leave files younger than two seconds for next tick.
            if (System.currentTimeMillis() - p.png.lastModified() < 2_000) continue
            val preview = try { CaptureStore.preview(p.png) } catch (t: Throwable) { null }
            if (preview != null) { p.meta.put("width", preview.second); p.meta.put("height", preview.third) }
            client.uploadShot(p.png, preview?.first, p.meta)
            CaptureStore.remove(ctx, p.id)
            Log.i(TAG, "uploaded ${p.id}")
        }
    }

    private fun toast(msg: String) {
        main.post { try { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {} }
    }
}
