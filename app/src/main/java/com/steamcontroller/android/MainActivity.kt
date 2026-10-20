package com.steamcontroller.android

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.steamcontroller.android.backup.BackupManager
import com.steamcontroller.android.ui.pill.Pill
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.steamcontroller.android.hub.CaptureStore
import com.steamcontroller.android.hub.GamehubStore
import com.steamcontroller.android.live.ControllerLive
import com.steamcontroller.android.ui.FocusMemoryFrame
import com.steamcontroller.android.ui.Host
import com.steamcontroller.android.ui.NavRail
import com.steamcontroller.android.ui.Page
import com.steamcontroller.android.ui.PageId
import com.steamcontroller.android.ui.ServiceControl
import com.steamcontroller.android.ui.Sheet
import com.steamcontroller.android.ui.Backdrop
import com.steamcontroller.android.ui.live.LiveFrame
import com.steamcontroller.android.ui.live.LiveTicker
import com.steamcontroller.android.ui.pages.ControllerPage
import com.steamcontroller.android.ui.pages.DevicePage
import com.steamcontroller.android.ui.pages.GamehubPage
import com.steamcontroller.android.ui.pages.ProfilesPage
import com.steamcontroller.android.ui.pages.QuickSwitch
import com.steamcontroller.android.ui.pages.TestPage
import com.steamcontroller.android.ui.pages.TuningPage
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.dp
import com.steamcontroller.android.ui.theme.surface
import com.steamcontroller.android.ui.widget.Anim
import com.steamcontroller.android.ui.widget.DotView
import com.steamcontroller.android.ui.widget.Kit

/**
 * The app shell: navigation rail, status strip and one page at a time (Controller, Test, Tuning,
 * Profiles, Device, Gamehub). Single activity so the live state, focus memory and side sheets are
 * shared; Calibration, the HID debug screen and the app picker remain their own activities.
 */
class MainActivity : AppCompatActivity(), Host {

    override val activity: AppCompatActivity get() = this
    private lateinit var svc: ServiceControl
    override val service: ServiceControl get() = svc
    private lateinit var ticker: LiveTicker
    override val frame: LiveFrame get() = ticker.frame
    private var cur: PageId = PageId.CONTROLLER
    override val current: PageId get() = cur

    private val pages = HashMap<PageId, Page>()
    private lateinit var rail: NavRail
    private lateinit var pageFrame: FocusMemoryFrame
    private lateinit var overlay: FrameLayout
    private lateinit var title: TextView
    private var sheet: Sheet? = null
    private var resumed = false

    // status strip
    private lateinit var linkChip: StripChip
    private lateinit var profileChip: StripChip
    private lateinit var serviceChip: StripChip
    private lateinit var hubChip: StripChip
    private var statusSeen = -1
    private var hubCheckedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        svc = ServiceControl(this) { ticker.invalidateSlow() }
        ticker = LiveTicker(this) { onFrame(it) }
        buildShell()
        service.register()
        service.requestNotificationPermission()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })
        val start = savedInstanceState?.getString(KEY_PAGE)?.let { runCatching { PageId.valueOf(it) }.getOrNull() } ?: PageId.CONTROLLER
        go(start)
        service.handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PAGE, current.name)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        service.handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        ticker.invalidateSlow()
        ticker.start()
        pages[current]?.let { it.refresh(); it.onShow() }
        statusSeen = -1
    }

    override fun onPause() {
        pages[current]?.onHide()
        ticker.stop()
        ControllerLive.releaseOutput()
        resumed = false
        super.onPause()
    }

    override fun onDestroy() {
        service.unregister()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        service.onPermissionsResult(requestCode, grantResults)
    }

    // ── shell ─────────────────────────────────────────────────────────────────────────

    private fun buildShell() {
        val safeH = resources.getDimensionPixelSize(R.dimen.safe_h)
        val safeV = resources.getDimensionPixelSize(R.dimen.safe_v)
        val stripH = dp(48)
        val root = FrameLayout(this).apply { background = Backdrop() }

        rail = NavRail(this, onSelect = { go(it, focusContent = true) }, onRight = { focusPage() })
        rail.attachPanel(root, safeH)

        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(safeH, safeV, safeH, safeV)
            clipToPadding = false
            clipChildren = false
        }
        main.addView(buildStrip(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, stripH))
        pageFrame = FocusMemoryFrame(this).apply { clipChildren = false; clipToPadding = false }
        pageFrame.fallback = { pages[cur]?.defaultFocus }
        main.addView(pageFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            marginStart = rail.collapsedWidth + dp(8)
            topMargin = dp(10)
        })
        root.addView(main, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(rail, FrameLayout.LayoutParams(rail.collapsedWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.TOP).apply {
            marginStart = safeH
            topMargin = safeV + stripH + dp(10)
        })
        overlay = FrameLayout(this)
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    private fun buildStrip(): View {
        val strip = Kit.row(this)
        val brand = ImageView(this).apply { setImageResource(R.drawable.ic_brand) }
        strip.addView(brand, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginStart = dp(8 + 3 + 13 - 4) })
        title = Kit.title(this, "", 21f)
        strip.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(24) })
        strip.addView(Kit.grow(this))
        val chips = Kit.row(this)
        linkChip = StripChip(true).also { c -> c.setOnClickListener { go(PageId.DEVICE, focusContent = true) } }
        profileChip = StripChip(false).also { c -> c.setOnClickListener { openQuickSwitch() } }
        serviceChip = StripChip(true).also { c -> c.setOnClickListener { service.toggle(frame.serviceRunning) } }
        hubChip = StripChip(true).also { c -> c.setOnClickListener { go(PageId.GAMEHUB, focusContent = true) } }
        for (c in listOf(linkChip, profileChip, serviceChip, hubChip)) chips.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(chips)
        }
        strip.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f))
        profileChip.label.text = getString(R.string.strip_no_profile)
        return strip
    }

    /** A status chip: optional dot + caps label in a pill. */
    private inner class StripChip(withDot: Boolean) : LinearLayout(this@MainActivity) {
        val dot = DotView(context, 7f)
        val label: TextView = Kit.label(context, "", 11.5f, C.text)
        val bg = surface(radiusDp = -1f, focusable = true)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(40)
            setPadding(dp(if (withDot) 11 else 17), 0, dp(17), 0)
            if (withDot) addView(dot, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(5) })
            addView(label)
        }

        fun primary(on: Boolean) {
            bg.fill = if (on) C.alpha(C.accent, 0.14f) else C.surface
            bg.stroke = if (on) C.alpha(C.accent, 0.55f) else C.line
            label.setTextColor(if (on) C.accent else C.text)
            bg.invalidateSelf()
        }
    }

    private fun renderStrip(f: LiveFrame) {
        linkChip.label.text = buildString {
            append(f.linkLabel())
            f.battery?.let { append(" · ").append(it).append('%') }
            if (f.charging == true) append(" · ").append(getString(R.string.strip_charging))
        }
        linkChip.dot.color = when {
            f.connected -> C.success
            f.serviceRunning || f.stale -> C.warning
            else -> C.text3
        }
        linkChip.dot.halo = f.connected
        profileChip.label.text = (f.profileName ?: getString(R.string.strip_no_profile)) + "  ▾"
        serviceChip.label.text = getString(if (f.serviceRunning) R.string.strip_stop_service else R.string.strip_start_service)
        serviceChip.dot.color = if (f.serviceRunning) C.success else C.text3
        serviceChip.primary(!f.serviceRunning)
    }

    private fun renderHub() {
        val linked = GamehubStore.isLinked(this)
        hubChip.label.text = getString(R.string.nav_gamehub)
        hubChip.dot.color = when {
            !linked -> C.text3
            GamehubStore.lastError(this) != null -> C.warning
            CaptureStore.pendingCount(this) > 0 -> C.warning
            else -> C.success
        }
    }

    private fun onFrame(f: LiveFrame) {
        if (f.statusVersion != statusSeen) {
            statusSeen = f.statusVersion
            renderStrip(f)
            service.syncFromService(f.serviceRunning)
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (now - hubCheckedAt > 2000L) { hubCheckedAt = now; renderHub() }
        pages[current]?.onFrame(f)
    }

    // ── navigation ────────────────────────────────────────────────────────────────────

    private fun page(id: PageId): Page = pages.getOrPut(id) {
        when (id) {
            PageId.CONTROLLER -> ControllerPage(this)
            PageId.TEST -> TestPage(this)
            PageId.TUNING -> TuningPage(this)
            PageId.PROFILES -> ProfilesPage(this)
            PageId.DEVICE -> DevicePage(this)
            PageId.GAMEHUB -> GamehubPage(this)
        }
    }

    override fun go(id: PageId, focusContent: Boolean) {
        val switching = id != current || pageFrame.childCount == 0
        if (switching) {
            pages[current]?.let { old -> if (pageFrame.childCount > 0) { if (resumed) old.onHide(); pageFrame.removeView(old.view) } }
            cur = id
            val p = page(id)
            (p.view.parent as? ViewGroup)?.removeView(p.view)
            pageFrame.remembered = null
            pageFrame.addView(p.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            Anim.fadeIn(p.view)
            title.text = id.title
            rail.setCurrent(id)
            p.refresh()
            if (resumed) p.onShow()
        }
        if (focusContent) pageFrame.post { focusPage() }
    }

    private fun focusPage() {
        if (!pageFrame.requestFocus(View.FOCUS_RIGHT)) rail.focusCurrent()
    }

    override fun openSheet(sheet: Sheet) {
        closeSheet(immediate = true)
        this.sheet = sheet
        overlay.addView(sheet.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentFocusBlocked(true)
        sheet.setOnScrimClick { closeSheet() }
        sheet.animateIn()
    }

    override fun closeSheet() = closeSheet(immediate = false)

    private fun closeSheet(immediate: Boolean) {
        val s = sheet ?: return
        sheet = null
        setContentFocusBlocked(false)
        if (immediate) overlay.removeView(s.root) else s.animateOut { overlay.removeView(s.root) }
        s.onClosed?.invoke()
    }

    private fun setContentFocusBlocked(blocked: Boolean) {
        val mode = if (blocked) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
        (pageFrame.parent as ViewGroup).descendantFocusability = mode
        rail.descendantFocusability = mode
    }

    override fun openQuickSwitch() = QuickSwitch.open(this)

    // Registered here, not in a page: pages are created after onStart, too late to register.
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(BackupManager.export(this).toByteArray()) }
            Pill.show(this, Pill.Kind.SAVED, getString(R.string.backup_export_toast))
        } catch (t: Throwable) {
            Pill.show(this, Pill.Kind.FAILED, getString(R.string.backup_export_failed_toast, t.message))
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val json = contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: throw IllegalStateException("empty file")
            val n = BackupManager.import(this, json)
            refreshAll()
            Pill.show(this, Pill.Kind.SAVED, getString(R.string.backup_import_toast, n))
        } catch (t: Throwable) {
            Pill.show(this, Pill.Kind.FAILED, getString(R.string.backup_import_failed_toast, t.message))
        }
    }

    override fun exportBackup() {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        try { exportLauncher.launch("steamcontroller_backup_$stamp.json") } catch (_: Throwable) {
            Pill.show(this, Pill.Kind.FAILED, getString(R.string.dev_no_files_app))
        }
    }

    override fun importBackup() {
        try { importLauncher.launch(arrayOf("application/json", "text/plain")) } catch (_: Throwable) {
            Pill.show(this, Pill.Kind.FAILED, getString(R.string.dev_no_files_app))
        }
    }

    override fun refreshAll() {
        ticker.invalidateSlow()
        statusSeen = -1
        for (p in pages.values) p.refresh()
    }

    private fun back() {
        if (sheet != null) { closeSheet(); return }
        val p = pages[current]
        if (p != null && p.onBack()) return
        if (pageFrame.hasFocus()) { rail.focusCurrent(); return }
        if (current != PageId.CONTROLLER) { go(PageId.CONTROLLER); rail.focusCurrent(); return }
        finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val s = sheet
        if (s != null) {
            if (s.onKey?.invoke(event) == true) return true
        } else if (pages[current]?.onKey(event) == true) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private companion object {
        const val KEY_PAGE = "page"
    }
}
