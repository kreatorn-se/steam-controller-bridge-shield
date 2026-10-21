package com.steamcontroller.android

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
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
import androidx.lifecycle.lifecycleScope
import com.steamcontroller.android.hub.GamehubTv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import com.steamcontroller.android.ui.Unclip
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
import com.steamcontroller.android.ui.tv.GameHub
import com.steamcontroller.android.ui.tv.Hold
import com.steamcontroller.android.ui.tv.HoldItem
import com.steamcontroller.android.ui.tv.HomePage
import com.steamcontroller.android.ui.tv.HomeRepo
import com.steamcontroller.android.ui.tv.Img
import com.steamcontroller.android.ui.tv.ImportPage
import com.steamcontroller.android.ui.tv.ImportStatus
import com.steamcontroller.android.ui.tv.LibraryPage
import com.steamcontroller.android.ui.tv.CollRef
import com.steamcontroller.android.ui.tv.ShowMode
import com.steamcontroller.android.ui.tv.ShowRequest
import com.steamcontroller.android.ui.tv.Splash

/**
 * The app shell: navigation rail, status strip and one page at a time. Gamehub's sections (Home,
 * Library, Import) on top, the controller bridge (Controller, Test, Tuning, Profiles, Device) and
 * the hub link under Settings. Single activity so the live state, focus memory, side sheets and
 * show mode are shared; Calibration, the HID debug screen and the app picker remain their own
 * activities.
 */
class MainActivity : AppCompatActivity(), Host {

    // The UI at 85 % on a TV (ui/tv/TvScale).
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.steamcontroller.android.ui.tv.TvScale.wrap(newBase))
    }

    override val activity: AppCompatActivity get() = this
    private lateinit var svc: ServiceControl
    override val service: ServiceControl get() = svc
    private lateinit var ticker: LiveTicker
    override val frame: LiveFrame get() = ticker.frame
    private var cur: PageId = PageId.HOME
    override val current: PageId get() = cur

    private val pages = HashMap<PageId, Page>()
    private lateinit var rail: NavRail
    private lateinit var pageFrame: FocusMemoryFrame
    private lateinit var overlay: FrameLayout
    private lateinit var showLayer: FrameLayout
    private lateinit var hubLayer: FrameLayout
    private lateinit var holdLayer: FrameLayout
    private lateinit var hold: Hold
    private var gameHub: GameHub? = null
    private var focusBeforeHub: View? = null
    private lateinit var strip: View
    private lateinit var title: TextView
    private var sheet: Sheet? = null
    private var show: ShowMode? = null
    private var focusBeforeShow: View? = null
    private var resumed = false

    // status strip
    private lateinit var linkChip: StripChip
    private lateinit var profileChip: StripChip
    private lateinit var serviceChip: StripChip
    private lateinit var hubChip: StripChip
    private lateinit var nowChip: StripChip
    private lateinit var updateChip: StripChip
    private lateinit var importChip: StripChip
    private val importListener: (GamehubTv.ImportProgress?) -> Unit = { renderImporting(it) }
    private val updateBar by lazy { com.steamcontroller.android.ui.tv.Bar(this) }
    private val updateListener: (com.steamcontroller.android.update.Updater.State) -> Unit = { renderUpdate(it) }
    private var nowGame: String? = null
    private val homeListener: (HomeRepo.State) -> Unit = { renderNow(it) }
    private var statusSeen = -1
    private var hubCheckedAt = 0L
    private var lastProfileKey: String? = null

    // Stick → D-pad synthesis: the framework turns the virtual pad's left stick into repeating
    // D-pad keys, which would nudge the focused stepper while you watch a live stick read-out.
    // Stick motion is consumed app-wide; D-pad (hat) changes still pass so navigation works, and
    // once a passed event carried a deflected stick, events keep passing until it re-centres so
    // the synthesised key is always released.
    private var hatX = 0f
    private var hatY = 0f
    private var stickLeaked = false

    // ── in-app screensaver ───────────────────────────────────────────────────────────
    // After five minutes without input, a calm slideshow of the hub's screensaver collection.
    // Any key returns to where you were. Outside the app the Shield's own screensaver runs.

    private val idleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastInput = android.os.SystemClock.uptimeMillis()
    private val idleCheck = object : Runnable {
        override fun run() {
            checkIdle()
            idleHandler.postDelayed(this, 20_000L)
        }
    }

    private fun checkIdle() {
        val idle = android.os.SystemClock.uptimeMillis() - lastInput
        if (!resumed || idle < IDLE_MS || show != null || sheet != null || hold.menuOpen || !GamehubStore.isLinked(this)) return
        lastInput = android.os.SystemClock.uptimeMillis() // one attempt per idle period
        lifecycleScope.launch {
            val cfg = withContext(Dispatchers.IO) {
                GamehubTv.screensaver(this@MainActivity).valueOrNull() ?: GamehubTv.cachedSaver(this@MainActivity) ?: GamehubTv.Saver("favourites", 20, true)
            }
            if (!resumed || show != null || sheet != null || hold.menuOpen) return@launch
            openShow(ShowRequest(listOf(CollRef(cfg.collection, "")), 0, calm = true, intervalSec = cfg.intervalSec, shuffle = cfg.shuffle))
        }
    }

    /** Any key, touch or trackball input the window gets (Activity calls this before dispatch). */
    override fun onUserInteraction() {
        super.onUserInteraction()
        lastInput = android.os.SystemClock.uptimeMillis()
    }

    // Set when a stick or trigger woke the screensaver: its motion is swallowed until it is back
    // at rest, so the same push does not also move focus on the page underneath.
    private var wakeMotion = false
    private var wakeTouch = false

    /** A stick, trigger or D-pad push, not the drift of a pad at rest. */
    private fun deliberate(ev: MotionEvent): Boolean {
        fun a(axis: Int) = kotlin.math.abs(ev.getAxisValue(axis))
        return a(MotionEvent.AXIS_X) > 0.4f || a(MotionEvent.AXIS_Y) > 0.4f || a(MotionEvent.AXIS_Z) > 0.4f || a(MotionEvent.AXIS_RZ) > 0.4f ||
            ev.getAxisValue(MotionEvent.AXIS_HAT_X) != 0f || ev.getAxisValue(MotionEvent.AXIS_HAT_Y) != 0f ||
            a(MotionEvent.AXIS_LTRIGGER) > 0.4f || a(MotionEvent.AXIS_RTRIGGER) > 0.4f || a(MotionEvent.AXIS_BRAKE) > 0.4f || a(MotionEvent.AXIS_GAS) > 0.4f
    }

    private fun atRest(ev: MotionEvent): Boolean {
        fun a(axis: Int) = kotlin.math.abs(ev.getAxisValue(axis))
        return a(MotionEvent.AXIS_X) < 0.25f && a(MotionEvent.AXIS_Y) < 0.25f && a(MotionEvent.AXIS_Z) < 0.25f && a(MotionEvent.AXIS_RZ) < 0.25f &&
            ev.getAxisValue(MotionEvent.AXIS_HAT_X) == 0f && ev.getAxisValue(MotionEvent.AXIS_HAT_Y) == 0f &&
            a(MotionEvent.AXIS_LTRIGGER) < 0.2f && a(MotionEvent.AXIS_RTRIGGER) < 0.2f && a(MotionEvent.AXIS_BRAKE) < 0.2f && a(MotionEvent.AXIS_GAS) < 0.2f
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastInput = android.os.SystemClock.uptimeMillis()
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && show?.isCalm == true) wakeTouch = true
        if (wakeTouch) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                wakeTouch = false
                show?.takeIf { it.isCalm }?.close()
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        val joyMove = ev.isFromSource(InputDevice.SOURCE_JOYSTICK) && ev.actionMasked == MotionEvent.ACTION_MOVE
        // Mouse and touchpad movement (hover, scroll) counts as activity too.
        val pointer = ev.isFromSource(InputDevice.SOURCE_CLASS_POINTER) || ev.isFromSource(InputDevice.SOURCE_MOUSE)
        val push = joyMove && deliberate(ev)
        if (pointer || push) lastInput = now
        if (joyMove && wakeMotion) {
            if (atRest(ev)) wakeMotion = false
            hatX = ev.getAxisValue(MotionEvent.AXIS_HAT_X); hatY = ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
            return true
        }
        // The screensaver closes on a deliberate push (or the mouse), and that input stops here.
        if ((push || (pointer && ev.actionMasked != MotionEvent.ACTION_HOVER_EXIT)) && show?.isCalm == true) {
            show?.close()
            if (joyMove) {
                wakeMotion = !atRest(ev)
                hatX = ev.getAxisValue(MotionEvent.AXIS_HAT_X); hatY = ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
            }
            return true
        }
        // Show mode reads the triggers (LT/RT: previous/next collection); nothing else changes.
        if (joyMove) show?.onMotion(ev)
        if (joyMove) {
            val hx = ev.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val hatChanged = hx != hatX || hy != hatY
            hatX = hx; hatY = hy
            if (hatChanged || stickLeaked) {
                stickLeaked = kotlin.math.abs(ev.getAxisValue(MotionEvent.AXIS_X)) > 0.1f || kotlin.math.abs(ev.getAxisValue(MotionEvent.AXIS_Y)) > 0.1f
                return super.dispatchGenericMotionEvent(ev)
            }
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The manifest starts us on the launch theme (its window background is the splash mark).
        setTheme(R.style.Theme_SteamController)
        super.onCreate(savedInstanceState)
        com.steamcontroller.android.ui.tv.HomeTiming.mark("activity-create")
        WindowCompat.setDecorFitsSystemWindows(window, true)
        // Show-mode settings are read on the main thread later: load their file off it now.
        Thread({ try { com.steamcontroller.android.ui.tv.TvPrefs.slowZoom(applicationContext) } catch (_: Throwable) {} }, "tv-prefs").start()
        Img.init(this)
        HomeRepo.install()
        svc = ServiceControl(this) { ticker.invalidateSlow() }
        ticker = LiveTicker(this) { onFrame(it) }
        buildShell()
        service.register()
        service.requestNotificationPermission()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })
        val start = savedInstanceState?.getString(KEY_PAGE)?.let { runCatching { PageId.valueOf(it) }.getOrNull() } ?: PageId.HOME
        go(start)
        HomeRepo.observe(this, homeListener)
        ImportStatus.observe(importListener)
        com.steamcontroller.android.hub.Background.start(this, "app")
        com.steamcontroller.android.update.Updater.observe(updateListener)
        com.steamcontroller.android.update.Updater.checkIfDue(this)
        if (savedInstanceState == null) Splash.play(rootView)
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
        HomeRepo.start(this)
        ImportStatus.start(this)
        lastInput = android.os.SystemClock.uptimeMillis()
        idleHandler.removeCallbacks(idleCheck)
        idleHandler.postDelayed(idleCheck, 20_000L)
    }

    override fun onPause() {
        idleHandler.removeCallbacks(idleCheck)
        HomeRepo.stop()
        ImportStatus.stop()
        show?.pause()
        pages[current]?.onHide()
        ticker.stop()
        ControllerLive.releaseOutput()
        resumed = false
        super.onPause()
    }

    private var stopped = false

    override fun onStart() {
        super.onStart()
        Img.setVisible(true)
        if (stopped) {
            stopped = false
            pages[current]?.restoreImages()
            gameHub?.restoreImages()
            show?.restoreImages()
        }
    }

    // While a game runs (GeForce NOW) the process stays alive for the bridge's service on a
    // 3 GB box: give back every bitmap until the UI is visible again.
    override fun onStop() {
        stopped = true
        for (p in pages.values) p.releaseImages()
        gameHub?.releaseImages()
        show?.releaseImages()
        Img.setVisible(false)
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Img.trimMemory(level)
    }

    override fun onDestroy() {
        HomeRepo.remove(homeListener)
        ImportStatus.remove(importListener)
        com.steamcontroller.android.update.Updater.remove(updateListener)
        com.steamcontroller.android.hub.Background.stop("app")
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

    private lateinit var rootView: FrameLayout

    private fun buildShell() {
        val safeH = resources.getDimensionPixelSize(R.dimen.safe_h)
        val safeV = resources.getDimensionPixelSize(R.dimen.safe_v)
        val stripH = dp(48)
        val root = FrameLayout(this).apply { background = Backdrop() }
        rootView = root

        // Pages fill the screen; each one is inset to the content area unless it is full-bleed.
        pageFrame = FocusMemoryFrame(this).apply { clipChildren = false; clipToPadding = false }
        pageFrame.fallback = { pages[cur]?.defaultFocus }
        root.addView(pageFrame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // A game's hub sits over the page, under the rail and the strip.
        hubLayer = FrameLayout(this)
        root.addView(hubLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        rail = NavRail(this, onSelect = { id, focusContent -> go(id, focusContent) }, onRight = { focusContent() })
        rail.attachPanel(root, safeH, 2)

        strip = buildStrip()
        root.addView(strip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, stripH, Gravity.TOP).apply {
            setMargins(safeH, safeV, safeH, 0)
        })
        root.addView(rail, FrameLayout.LayoutParams(rail.collapsedWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.TOP).apply {
            marginStart = safeH
            topMargin = safeV + stripH + dp(10)
            bottomMargin = safeV
        })
        overlay = FrameLayout(this)
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        showLayer = FrameLayout(this)
        root.addView(showLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // The hold ring, the hold menu and its undo bar: above everything, show mode included.
        holdLayer = FrameLayout(this)
        root.addView(holdLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        hold = Hold(this, holdLayer)
        setContentView(root)
        // Settings pages and sheets: focused controls grow and glow past container edges, so no
        // container may clip them (ui/Unclip). Re-applied as pages rebuild their content.
        root.viewTreeObserver.addOnGlobalLayoutListener {
            if (cur.settings) pages[cur]?.view?.let { Unclip.apply(it) }
            sheet?.let { Unclip.apply(it.root) }
        }
    }

    private fun pageParams(p: Page): FrameLayout.LayoutParams {
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        if (!p.fullBleed) {
            val safeH = resources.getDimensionPixelSize(R.dimen.safe_h)
            val safeV = resources.getDimensionPixelSize(R.dimen.safe_v)
            lp.setMargins(safeH + rail.collapsedWidth + dp(8), safeV + dp(48 + 10), safeH, safeV)
        }
        return lp
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
        nowChip = StripChip(true).also { c ->
            c.dot.color = C.success
            c.dot.halo = true
            c.label.setTextColor(C.success)
            c.visibility = View.GONE
            c.setOnClickListener { nowGame?.let { g -> openShow(ShowRequest(listOf(CollRef("game:$g", g)), 0)) } }
        }
        updateChip = StripChip(true).also { c ->
            c.dot.color = C.accent
            c.dot.halo = true
            c.visibility = View.GONE
            c.addView(updateBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
            updateBar.visibility = View.GONE
            c.setOnClickListener { startUpdate() }
        }
        importChip = StripChip(true).also { c ->
            c.dot.color = C.accent
            c.dot.halo = true
            c.visibility = View.GONE
            c.setOnClickListener { go(PageId.IMPORT, focusContent = true) }
        }
        for (c in listOf(updateChip, importChip, nowChip, linkChip, profileChip, serviceChip, hubChip)) chips.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })
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

    // ── self-update ──────────────────────────────────────────────────────────────────

    private fun startUpdate() {
        val u = com.steamcontroller.android.update.Updater
        when (val st = u.state) {
            is com.steamcontroller.android.update.Updater.State.Available -> u.install(this, st.rel)
            is com.steamcontroller.android.update.Updater.State.Failed -> st.rel?.let { u.install(this, it) }
            else -> {}
        }
    }

    private fun renderUpdate(st: com.steamcontroller.android.update.Updater.State) {
        when (st) {
            is com.steamcontroller.android.update.Updater.State.Idle -> { updateChip.visibility = View.GONE; return }
            is com.steamcontroller.android.update.Updater.State.Available -> {
                updateChip.label.text = getString(R.string.update_available, st.rel.version)
                updateBar.visibility = View.GONE
            }
            is com.steamcontroller.android.update.Updater.State.Downloading -> {
                updateChip.label.text = getString(R.string.update_downloading, (st.progress * 100).toInt())
                updateBar.visibility = View.VISIBLE
                updateBar.value = st.progress
            }
            is com.steamcontroller.android.update.Updater.State.Installing -> {
                updateChip.label.text = getString(R.string.update_installing)
                updateBar.visibility = View.GONE
            }
            is com.steamcontroller.android.update.Updater.State.Failed -> {
                updateChip.label.text = getString(R.string.update_retry)
                updateBar.visibility = View.GONE
                if (com.steamcontroller.android.update.Updater.announce(st)) Pill.show(this, Pill.Kind.FAILED, updateFailure(st.message))
            }
        }
        updateChip.visibility = View.VISIBLE
    }

    fun updateFailure(why: String): String = when {
        why == "needs_service" -> getString(R.string.update_needs_service)
        why == "checksum" || why == "size" || why == "unreadable" -> getString(R.string.update_checksum)
        why == "no_digest" -> getString(R.string.update_no_digest)
        why == "signer" || why == "package" -> getString(R.string.update_signer)
        why == "version" -> getString(R.string.update_not_newer)
        why.startsWith("install") -> getString(R.string.update_install_failed)
        else -> getString(R.string.update_download_failed)
    }

    /** "Importing 120/857" while the hub imports screenshots (/now's importing), off the Import page. */
    private fun renderImporting(p: GamehubTv.ImportProgress?) {
        if (p == null || !p.running || cur == PageId.IMPORT) {
            if (importChip.hasFocus()) linkChip.requestFocus()
            importChip.visibility = View.GONE
            return
        }
        importChip.label.text = getString(R.string.strip_importing, p.done, p.total)
        importChip.visibility = View.VISIBLE
    }

    /** The compact Now-playing chip: Steam presence via the hub's home. */
    private fun renderNow(s: HomeRepo.State) {
        val np = (s as? HomeRepo.State.Ready)?.home?.nowPlaying
        nowGame = np?.game
        // Home has the full Now-playing card; the chip is for every other page.
        if (np == null || cur == PageId.HOME) {
            if (nowChip.hasFocus()) linkChip.requestFocus()
            nowChip.visibility = View.GONE
            return
        }
        nowChip.label.text = getString(R.string.strip_now_playing, np.game)
        nowChip.visibility = View.VISIBLE
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
        // The service can switch profile by itself (Steam + D-pad chord, foreground auto-switch);
        // every page re-reads its settings when that happens.
        val profileKey = (f.profileId ?: "") + "|" + (f.profileName ?: "")
        if (profileKey != lastProfileKey) {
            val first = lastProfileKey == null
            lastProfileKey = profileKey
            if (!first) refreshAll()
        }
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
            PageId.HOME -> HomePage(this)
            PageId.LIBRARY -> LibraryPage(this)
            PageId.IMPORT -> ImportPage(this)
        }
    }

    override fun go(id: PageId, focusContent: Boolean) {
        gameHub?.close()
        val switching = id != current || pageFrame.childCount == 0
        if (switching) {
            pages[current]?.let { old -> if (pageFrame.childCount > 0) { if (resumed) old.onHide(); pageFrame.removeView(old.view) } }
            cur = id
            val p = page(id)
            (p.view.parent as? ViewGroup)?.removeView(p.view)
            pageFrame.remembered = null
            pageFrame.addView(p.view, Unclip.keep(p.view, pageParams(p)))
            Anim.fadeIn(p.view)
            title.text = if (id == PageId.HOME) "" else id.title
            rail.setCurrent(id)
            renderNow(HomeRepo.state)
            renderImporting(ImportStatus.progress)
            p.refresh()
            if (resumed) p.onShow()
        }
        if (focusContent) pageFrame.post { focusPage() }
    }

    private fun focusPage() {
        if (!pageFrame.requestFocus(View.FOCUS_RIGHT)) rail.focusCurrent()
    }

    /** Right from the rail: into the game hub when one is open, else the page. */
    private fun focusContent() {
        val h = gameHub
        if (h != null && h.root.requestFocus(View.FOCUS_RIGHT)) return
        focusPage()
    }

    // ── game hubs ─────────────────────────────────────────────────────────────────────

    override fun openGame(name: String) {
        show?.close()
        gameHub?.close()
        focusBeforeHub = currentFocus
        val h = GameHub(this, name) { onHubClosed() }
        gameHub = h
        hubLayer.addView(h.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pageFrame.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        title.text = ""
        h.start()
    }

    private fun onHubClosed() {
        gameHub = null
        if (sheet == null && show == null) pageFrame.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        title.text = if (cur == PageId.HOME) "" else cur.title
        val b = focusBeforeHub
        focusBeforeHub = null
        if (b != null && b.isAttachedToWindow && b.isShown) b.requestFocus() else focusPage()
    }

    override fun contentChanged(item: HoldItem?, hidden: Boolean) {
        HomeRepo.refresh(this, 0L)
        pages[cur]?.refresh()
        if (item != null) gameHub?.changed(item, hidden)
    }

    private var focusBeforeSheet: View? = null

    override fun openSheet(sheet: Sheet) {
        closeSheet(immediate = true)
        focusBeforeSheet = currentFocus
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
        // Back to where the sheet was opened from (a page may move it on in onClosed).
        val back = focusBeforeSheet
        focusBeforeSheet = null
        if (back != null && back.isAttachedToWindow && back.isShown) back.requestFocus() else if (!immediate) focusPage()
        s.onClosed?.invoke()
    }

    private fun setContentFocusBlocked(blocked: Boolean) {
        val mode = if (blocked) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
        // The page stays blocked under an open game hub.
        pageFrame.descendantFocusability = if (gameHub != null) ViewGroup.FOCUS_BLOCK_DESCENDANTS else mode
        hubLayer.descendantFocusability = mode
        (strip as ViewGroup).descendantFocusability = mode
        rail.descendantFocusability = mode
    }

    // ── show mode ─────────────────────────────────────────────────────────────────────

    override val showOpen: Boolean get() = show != null

    override fun openShow(req: ShowRequest) {
        if (req.siblings.isEmpty() || show != null) return
        if (sheet != null) closeSheet(immediate = true)
        focusBeforeShow = currentFocus
        hold.cancel()
        val s = ShowMode(this, req, hold, onOpenGame = { openGame(it) }, onChanged = { item, hidden -> contentChanged(item, hidden) }) { onShowClosed() }
        show = s
        showLayer.addView(s.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentFocusBlocked(true)
        s.start()
    }

    private fun onShowClosed() {
        show = null
        setContentFocusBlocked(false)
        val back = focusBeforeShow
        focusBeforeShow = null
        val restored = gameHub == null && pages[current]?.restoreFocus() == true
        if (!restored) {
            if (back != null && back.isAttachedToWindow && back.isShown) back.requestFocus() else focusPage()
        }
        // Favourites may have changed in show mode; Home updates in place.
        HomeRepo.refresh(this, 0L)
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

    /** Back: overlays first (show mode, a sheet), then the page, then the rail, then Home, then out. */
    private fun back() {
        if (hold.menuOpen) { hold.close(); return }
        show?.let { it.close(); return }
        if (sheet != null) { closeSheet(); return }
        gameHub?.let { h -> if (!h.onBack()) h.close(); return }
        val p = pages[current]
        if (p != null && p.onBack()) return
        if (pageFrame.hasFocus() || strip.hasFocus()) { rail.focusCurrent(); return }
        if (current != PageId.HOME) { go(PageId.HOME); rail.focusCurrent(); return }
        finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        lastInput = android.os.SystemClock.uptimeMillis()
        // The hold menu owns the keys while open (its rows use normal focus).
        if (hold.menuOpen) {
            if (hold.menuKey(event)) return true
            return super.dispatchKeyEvent(event)
        }
        if (hold.undoKey(event)) return true
        show?.let { if (it.onKey(event)) return true }
        // Holding OK on a picture: the ring, then the menu. A tap passes through untouched.
        if (sheet == null && hold.pageKey(event)) return true
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
        const val IDLE_MS = 5 * 60_000L
    }
}
