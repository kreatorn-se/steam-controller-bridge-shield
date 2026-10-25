package com.steamcontroller.android.hub

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.steamcontroller.android.R
import com.steamcontroller.android.shizuku.ShizukuStarterService
import com.steamcontroller.android.ui.pill.Pill
import java.io.File
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
 * - [noteMemory], every few minutes while a game is on: this process's memory and the Shield's,
 *   and its oom_score_adj (200 as a foreground service; 100 while the accessibility service is
 *   bound, which the Shield's low-memory killer spares longer, see [KEEPER]).
 *
 * KEEPER: on 25 Oct 2026 Android killed the bridge twelve times in 18 minutes of GeForce NOW
 * (SIGKILL, not lmkd's low-memory report: the Shield's kernel killer, which takes the biggest
 * process of the least important level). A foreground service shares level 200 with every other
 * one; an enabled accessibility service is bound by the system and lifts the whole process to
 * 100, next to what is on screen. So after a kill, if it is off, a pill says where to turn it on.
 */
object ProcessHealth {
    private const val PREFS = "process_health"
    private const val K_LAST_EXIT = "last_exit"   // timestamp of the newest exit already reported
    private const val MAX_EXITS = 16
    private const val K_NUDGED = "keeper_nudged"   // when the pill about the keeper last showed
    private const val NUDGE_EVERY_MS = 20 * 3_600_000L

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
        val killed = fresh.any {
            (it.reason == ApplicationExitInfo.REASON_SIGNALED || it.reason == ApplicationExitInfo.REASON_LOW_MEMORY) &&
                System.currentTimeMillis() - it.timestamp < 24 * 3_600_000L
        }
        val now = System.currentTimeMillis()
        if (killed && !ShizukuStarterService.isEnabledInSettings(c) && now - prefs.getLong(K_NUDGED, 0L) > NUDGE_EVERY_MS) {
            prefs.edit().putLong(K_NUDGED, now).apply()
            Diag.note("keeper off after a kill: pill shown", tag = "exit")
            Pill.show(c, Pill.Kind.INFO, c.getString(R.string.keeper_off_pill))
        }
    }

    /** 0 on screen, 100 visible (or kept by the accessibility service), 200 a foreground service. */
    private fun adj(): String = try { File("/proc/self/oom_score_adj").readText().trim() } catch (_: Throwable) { "?" }

    /** The kernel's low-memory killer levels if the Shield uses it (readable or not), else lmkd. */
    private fun killer(): String = try {
        val dir = "/sys/module/lowmemorykiller/parameters"
        if (!File(dir).exists()) "lmkd" else {
            val pages = File("$dir/minfree").readText().trim().split(',').map { it.trim().toLong() * 4 / 1024 }
            "kernel lmk adj ${File("$dir/adj").readText().trim()} minfree MB $pages"
        }
    } catch (_: Throwable) { "kernel lmk (levels not readable)" }

    /** One line: this process's memory, and how much the Shield has left. */
    fun noteMemory(c: Context, why: String) {
        val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val sys = ActivityManager.MemoryInfo()
        c.getSystemService(ActivityManager::class.java)?.getMemoryInfo(sys)
        val mb = { kb: Int -> kb / 1024 }
        Diag.note(
            "mem ($why): pss ${mb(mi.totalPss)} MB (java ${mb(mi.dalvikPss)}, native ${mb(mi.nativePss)}, " +
                "graphics ${mi.getMemoryStat("summary.graphics")?.toIntOrNull()?.let(mb) ?: "?"}); " +
                "Shield free ${sys.availMem shr 20} of ${sys.totalMem shr 20} MB, low ${sys.lowMemory}; " +
                "adj ${adj()}, keeper ${if (ShizukuStarterService.isConnected()) "on" else "off"}" +
                (if (why == "start") "; up ${SystemClock.elapsedRealtime() / 3_600_000L} h, ${killer()}" else ""),
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
