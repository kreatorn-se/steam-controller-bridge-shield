package com.steamcontroller.android.hub

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import androidx.annotation.RequiresApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Why Android ended this app's process, and how big it was, for Gamehub's diagnostics: the bridge
 * restarts itself within seconds when it is killed (ReviveReceiver), but every restart drops the
 * controller for a moment, and the lines of the process that died are lost with it.
 *
 * - [reportExits], once per process: the exits Android recorded since the last report
 *   (`ActivityManager.getHistoricalProcessExitReasons`, Android 11+): the reason (low memory,
 *   crash, native crash, ANR, ...), the process's memory when it went, and its importance.
 * - [noteMemory], every few minutes while a game is on: this process's memory and the Shield's.
 */
object ProcessHealth {
    private const val PREFS = "process_health"
    private const val K_LAST_EXIT = "last_exit"   // timestamp of the newest exit already reported
    private const val MAX_EXITS = 16

    @Volatile private var reported = false

    fun reportExits(c: Context) {
        if (reported || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        reported = true
        report(c)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun report(c: Context) {
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val since = prefs.getLong(K_LAST_EXIT, 0L)
        val am = c.getSystemService(ActivityManager::class.java) ?: return
        val exits = try { am.getHistoricalProcessExitReasons(c.packageName, 0, MAX_EXITS) } catch (_: Throwable) { return }
        val fresh = exits.filter { it.timestamp > since }.sortedBy { it.timestamp }
        if (fresh.isEmpty()) return
        val f = SimpleDateFormat("d MMM HH:mm:ss", Locale.ENGLISH)
        for (e in fresh) {
            val proc = e.processName.removePrefix(c.packageName).ifEmpty { "main" }
            val desc = e.description?.take(80)?.let { " '$it'" } ?: ""
            Diag.note(
                "exit ${f.format(Date(e.timestamp))} $proc: ${reason(e.reason)}" +
                    (if (e.status != 0) " status ${e.status}" else "") +
                    " pss ${e.pss / 1024} MB rss ${e.rss / 1024} MB importance ${e.importance}$desc",
                level = if (e.reason == ApplicationExitInfo.REASON_LOW_MEMORY || e.reason == ApplicationExitInfo.REASON_SIGNALED) "w" else "e",
                tag = "exit",
            )
        }
        prefs.edit().putLong(K_LAST_EXIT, fresh.last().timestamp).apply()
        Diag.flushSoon(15_000L)
    }

    /** One line: this process's memory, and how much the Shield has left. */
    fun noteMemory(c: Context, why: String) {
        val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val sys = ActivityManager.MemoryInfo()
        c.getSystemService(ActivityManager::class.java)?.getMemoryInfo(sys)
        val mb = { kb: Int -> kb / 1024 }
        Diag.note(
            "mem ($why): pss ${mb(mi.totalPss)} MB (java ${mb(mi.dalvikPss)}, native ${mb(mi.nativePss)}, " +
                "graphics ${mi.getMemoryStat("summary.graphics")?.toIntOrNull()?.let(mb) ?: "?"}); " +
                "Shield free ${sys.availMem shr 20} of ${sys.totalMem shr 20} MB, low ${sys.lowMemory}",
            tag = "mem",
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reason(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory (killed)"
        ApplicationExitInfo.REASON_SIGNALED -> "signal (killed)"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "too many resources"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited itself"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission changed"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the user or an update"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a process it depends on died"
        ApplicationExitInfo.REASON_OTHER -> "other"
        else -> "reason $r"
    }
}
