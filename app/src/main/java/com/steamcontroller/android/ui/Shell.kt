package com.steamcontroller.android.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.steamcontroller.android.R
import com.steamcontroller.android.ui.live.LiveFrame
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.Motion
import com.steamcontroller.android.ui.theme.dp
import com.steamcontroller.android.ui.theme.surface
import com.steamcontroller.android.ui.widget.Kit
import com.steamcontroller.android.ui.widget.setOnFocusChangeListenerChained

/**
 * Top level: the Gamehub sections. [settings] pages are the controller bridge and the hub link,
 * grouped under SETTINGS in the rail.
 */
enum class PageId(val title: String, val icon: Int, val settings: Boolean) {
    HOME("Home", R.drawable.ic_nav_home, false),
    LIBRARY("Library", R.drawable.ic_nav_library, false),
    IMPORT("Import", R.drawable.ic_nav_import, false),
    CONTROLLER("Controller", R.drawable.ic_nav_controller, true),
    TEST("Test", R.drawable.ic_nav_test, true),
    TUNING("Tuning", R.drawable.ic_nav_tuning, true),
    PROFILES("Profiles", R.drawable.ic_nav_profiles, true),
    DEVICE("Device", R.drawable.ic_nav_device, true),
    GAMEHUB("Hub link", R.drawable.ic_nav_gamehub, true),
}

/** What a page may ask of the shell. */
interface Host {
    val activity: AppCompatActivity
    val frame: LiveFrame
    val service: ServiceControl
    val current: PageId
    fun go(id: PageId, focusContent: Boolean = false)
    /** Full-screen show mode over everything (Back closes it). */
    fun openShow(req: com.steamcontroller.android.ui.tv.ShowRequest)
    val showOpen: Boolean
    /** A game's hub, over the current page (Back closes it). */
    fun openGame(name: String)
    /** A capture changed through the hold menu (favourite, album, background) or was [hidden]. */
    fun contentChanged(item: com.steamcontroller.android.ui.tv.HoldItem? = null, hidden: Boolean = false)
    fun openSheet(sheet: Sheet)
    fun closeSheet()
    fun openQuickSwitch()
    /** Settings backup as a JSON file (the activity owns the document launchers). */
    fun exportBackup()
    fun importBackup()
    /** Re-reads anything that may have changed underneath (profile applied, restore, unlink). */
    fun refreshAll()
}

abstract class Page(val host: Host) {
    val ctx: Context get() = host.activity
    abstract val view: View
    /** Draws edge to edge under the status strip and the rail (the home hero). */
    open val fullBleed: Boolean get() = false
    /** The page became visible with the activity resumed. */
    open fun onShow() {}
    /** The page is leaving, or the activity paused. */
    open fun onHide() {}
    /** Show mode (the screensaver too), a side sheet or a game hub now covers the page, or no longer does. */
    open fun onCovered(covered: Boolean) {}
    open fun onFrame(f: LiveFrame) {}
    /** Consume Back (close an inner state); false lets the shell handle it. */
    open fun onBack(): Boolean = false
    /** Settings may have changed elsewhere (profile load, restore): re-read them. */
    open fun refresh() {}
    /** Hardware key routed while this page is current; true consumes. */
    open fun onKey(event: KeyEvent): Boolean = false
    /** Show mode closed: put focus back where it was opened from; false lets the shell do it. */
    open fun restoreFocus(): Boolean = false
    /** The activity is no longer visible (a game is running): let go of image bitmaps. */
    open fun releaseImages() {}
    /** Visible again after [releaseImages]. */
    open fun restoreImages() {}
    /** Where focus lands when the page is entered for the first time. */
    open val defaultFocus: View? get() = null
}

/**
 * A container that remembers which descendant had focus and, when focus arrives from outside,
 * offers only that one (or [fallback]) to the focus search. TV focus memory: going to the rail and
 * back lands where you were, not on whatever happens to be geometrically closest.
 */
open class FocusMemoryFrame(ctx: Context) : FrameLayout(ctx) {
    var remembered: View? = null
    var fallback: (() -> View?)? = null

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        remembered = focused
    }

    private fun target(): View? {
        val r = remembered
        if (r != null && r.isAttachedToWindow && r.isShown && r.isFocusable && isDescendant(r)) return r
        return fallback?.invoke()?.takeIf { it.isShown }
    }

    private fun isDescendant(v: View): Boolean {
        var p = v.parent
        while (p != null) { if (p === this) return true; p = p.parent }
        return false
    }

    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        if (!hasFocus() && descendantFocusability != FOCUS_BLOCK_DESCENDANTS) {
            val t = target()
            if (t != null) { t.addFocusables(views, direction, focusableMode); return }
        }
        super.addFocusables(views, direction, focusableMode)
    }

    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: android.graphics.Rect?): Boolean {
        val t = target()
        if (t != null && t.requestFocus(direction, previouslyFocusedRect)) return true
        return super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
    }
}

/**
 * Left navigation rail: icons when idle, icon + caps label while it holds focus (an overlay, so the
 * page does not reflow). HOME, LIBRARY and IMPORT on top; below them SETTINGS, a single gear while
 * you are in Gamehub and the six bridge pages while you are in one of them. Pages switch on
 * select; the current one carries an accent bar.
 */
class NavRail(ctx: Context, private val onSelect: (PageId, Boolean) -> Unit, private val onRight: () -> Unit) : FocusMemoryFrame(ctx) {
    private val collapsedW = ctx.dp(64)
    private val expandedW = ctx.dp(204)
    private val scroller = ScrollView(ctx)
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val items = LinkedHashMap<PageId, Item>()
    private val rows = LinkedHashMap<PageId, View>()
    private val settingsItem: Item
    private val settingsRow: View
    private val groupLabel: TextView = Kit.label(ctx, ctx.getString(R.string.nav_settings), 10.5f, C.text3)
    private val panel = View(ctx)
    private var expanded = false
    var currentId: PageId = PageId.HOME
        private set
    /** The bridge page the SETTINGS gear opens: the last one visited. */
    var lastSettings: PageId = PageId.CONTROLLER
        private set

    inner class Item(val id: PageId?, title: String, iconRes: Int) : LinearLayout(context) {
        val icon = ImageView(context)
        val label: TextView = Kit.label(context, title, 12.5f, C.text2)
        val bar = View(context)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            surface(radiusDp = 12f, fill = 0, stroke = 0, focusable = true, activeFill = C.surface2, scale = 1f)
            setPadding(context.dp(3 + 13), 0, context.dp(12), 0)
            icon.setImageResource(iconRes)
            addView(icon, LayoutParams(context.dp(22), context.dp(22)))
            addView(label, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(16) })
            label.alpha = 0f
            setOnClickListener { if (id != null) onSelect(id, true) else onSelect(lastSettings, false) }
            setOnFocusChangeListenerChained { has ->
                post { setExpanded(this@NavRail.hasFocus()) }
                if (has) label.setTextColor(C.text) else render()
            }
            contentDescription = title
        }

        fun render() {
            val cur = id == currentId
            isActivated = cur
            icon.setColorFilter(if (cur) C.accent else C.text2)
            label.setTextColor(if (cur) C.text else C.text2)
            bar.visibility = if (cur) VISIBLE else INVISIBLE
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { onRight(); return true }
            return super.onKeyDown(keyCode, event)
        }
    }

    private fun rowFor(item: Item): View {
        val row = FrameLayout(context)
        item.bar.background = android.graphics.drawable.GradientDrawable().apply { setColor(C.accent); cornerRadius = context.dp(2f) }
        row.addView(item, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(42)).apply { marginStart = context.dp(8) })
        row.addView(item.bar, FrameLayout.LayoutParams(context.dp(3), context.dp(22), Gravity.CENTER_VERTICAL or Gravity.START))
        list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(42)).apply { bottomMargin = context.dp(2) })
        return row
    }

    init {
        clipChildren = false
        // The panel extends past the overscan margin to the screen edge while expanded.
        panel.background = object : android.graphics.drawable.Drawable() {
            val p = Paint()
            override fun onBoundsChange(b: android.graphics.Rect) {
                p.shader = LinearGradient(b.left.toFloat(), 0f, b.right.toFloat(), 0f,
                    intArrayOf(C.alpha(C.sheet, 0.98f), C.alpha(C.sheet, 0.96f), C.alpha(C.sheet, 0f)),
                    floatArrayOf(0f, 0.78f, 1f), Shader.TileMode.CLAMP)
            }
            override fun draw(c: Canvas) = c.drawRect(bounds, p)
            override fun setAlpha(a: Int) {}
            override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
            @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
        panel.alpha = 0f
        for (id in PageId.values().filter { !it.settings }) { val item = Item(id, id.title, id.icon); items[id] = item; rows[id] = rowFor(item) }
        // SETTINGS: a hairline while collapsed, the caps label while expanded.
        val sep = FrameLayout(ctx)
        sep.addView(View(ctx).apply { setBackgroundColor(C.lineStrong) }, FrameLayout.LayoutParams(ctx.dp(22), ctx.dp(1), Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = ctx.dp(8 + 3 + 13) })
        groupLabel.alpha = 0f
        sep.addView(groupLabel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = ctx.dp(8 + 3 + 13 + 22 + 16) })
        list.addView(sep, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(20)))
        settingsItem = Item(null, ctx.getString(R.string.nav_settings), R.drawable.ic_nav_settings)
        settingsRow = rowFor(settingsItem)
        for (id in PageId.values().filter { it.settings }) { val item = Item(id, id.title, id.icon); items[id] = item; rows[id] = rowFor(item) }
        scroller.isVerticalScrollBarEnabled = false
        scroller.clipChildren = false
        scroller.clipToPadding = false
        scroller.overScrollMode = OVER_SCROLL_NEVER
        scroller.addView(list)
        addView(scroller, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        fallback = { items[currentId] }
        applyGroup()
        render()
    }

    fun attachPanel(parent: FrameLayout, leftInset: Int, index: Int) {
        parent.addView(panel, index, FrameLayout.LayoutParams(leftInset + expandedW + context.dp(80), ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun setCurrent(id: PageId) {
        currentId = id
        if (id.settings) lastSettings = id
        remembered = items[id]
        // Move focus to the new item before the group it was in can disappear under it.
        val hadFocus = hasFocus()
        rows[id]?.visibility = VISIBLE
        if (hadFocus) items[id]?.requestFocus()
        applyGroup()
        render()
    }

    /** The six bridge pages show only while one of them is current; otherwise the gear stands in. */
    private fun applyGroup() {
        val inSettings = currentId.settings
        settingsRow.visibility = if (inSettings) GONE else VISIBLE
        for ((id, row) in rows) if (id.settings) {
            val v = if (inSettings) VISIBLE else GONE
            if (row.visibility != v && v == VISIBLE) { row.alpha = 0f; row.animate().alpha(1f).setDuration(Motion.SLOW).start() }
            row.visibility = v
        }
    }

    fun focusCurrent() { items[currentId]?.requestFocus() }

    private fun render() { items.values.forEach { it.render() }; settingsItem.render() }

    private fun setExpanded(e: Boolean) {
        if (e == expanded) return
        expanded = e
        val from = layoutParams.width.takeIf { it > 0 } ?: collapsedW
        val to = if (e) expandedW else collapsedW
        android.animation.ValueAnimator.ofInt(from, to).apply {
            duration = if (e) Motion.BASE else Motion.FAST
            interpolator = DecelerateInterpolator()
            addUpdateListener { layoutParams = layoutParams.apply { width = it.animatedValue as Int } }
            start()
        }
        panel.animate().alpha(if (e) 1f else 0f).setDuration(if (e) Motion.BASE else Motion.FAST).start()
        val labels = items.values.map { it.label } + settingsItem.label + groupLabel
        for (it in labels) it.animate().alpha(if (e) 1f else 0f).setDuration(if (e) Motion.SLOW else Motion.FAST).start()
    }

    val collapsedWidth get() = collapsedW
}

/**
 * Side sheet: slides in from the right over a scrim, holds focus until closed (Back closes).
 * Content goes in [body]; [header] sets the eyebrow, title and subtitle.
 */
class Sheet(ctx: Context, widthDp: Int = 400) {
    val root = FrameLayout(ctx)
    private val scrim = View(ctx)
    val panel = FocusMemoryFrame(ctx)
    val scroll = ScrollView(ctx)
    val body: LinearLayout = Kit.column(ctx)
    private val head: LinearLayout = Kit.column(ctx)
    var onClosed: (() -> Unit)? = null
    var onKey: ((KeyEvent) -> Boolean)? = null
    private val wide = ctx.resources.configuration.screenWidthDp >= 600
    private val w = if (wide) ctx.dp(widthDp) else ViewGroup.LayoutParams.MATCH_PARENT

    init {
        scrim.setBackgroundColor(0x8C050608.toInt())
        scrim.isClickable = true
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        panel.setBackgroundColor(C.sheet)
        val col = Kit.column(ctx)
        val safeV = ctx.resources.getDimensionPixelSize(R.dimen.safe_v)
        val safeR = ctx.resources.getDimensionPixelSize(R.dimen.safe_h)
        head.setPadding(ctx.dp(28), safeV + ctx.dp(16), safeR, ctx.dp(12))
        col.addView(head)
        scroll.isFillViewport = true
        scroll.clipToPadding = false
        scroll.isVerticalScrollBarEnabled = false
        scroll.setPadding(ctx.dp(17), ctx.dp(4), safeR - ctx.dp(8), safeV + ctx.dp(8))
        scroll.addView(body)
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        panel.addView(col)
        val edge = View(ctx).apply { setBackgroundColor(C.line) }
        panel.addView(edge, FrameLayout.LayoutParams(ctx.dp(1), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
        root.addView(panel, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        panel.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    fun header(eyebrow: String, title: String, subtitle: String?) {
        head.removeAllViews()
        head.addView(Kit.label(head.context, eyebrow, 11f, C.text3))
        head.addView(Kit.title(head.context, title, 24f).apply { maxLines = 2 }, Kit.lp(t = 8, ctx = head.context))
        if (!subtitle.isNullOrEmpty()) head.addView(Kit.body(head.context, subtitle, 13f, C.text2), Kit.lp(t = 6, ctx = head.context))
    }

    fun setOnScrimClick(f: () -> Unit) = scrim.setOnClickListener { f() }

    fun animateIn() {
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(Motion.BASE).start()
        panel.translationX = panel.context.dp(48f)
        panel.alpha = 0f
        panel.animate().translationX(0f).alpha(1f).setDuration(Motion.SLOW).setInterpolator(DecelerateInterpolator()).start()
    }

    fun animateOut(end: () -> Unit) {
        scrim.animate().alpha(0f).setDuration(Motion.FAST).start()
        panel.animate().translationX(panel.context.dp(32f)).alpha(0f).setDuration(Motion.FAST)
            .setInterpolator(android.view.animation.AccelerateInterpolator()).withEndAction(end).start()
    }

    /** First focusable in the body, or the given view. */
    fun focus(v: View?) {
        // No geometry yet (called right after attaching), so walk the tree rather than FocusFinder.
        val target = if (v != null && v.requestFocus()) v else body.takeIf { it.requestFocus(View.FOCUS_DOWN) }?.findFocus()
        if (target != null) {
            panel.remembered = target
        } else {
            body.post { body.requestFocus(View.FOCUS_DOWN); body.findFocus()?.let { panel.remembered = it } }
        }
    }
}

/** The app background: OLED black with a faint Steam-blue light from the top. */
class Backdrop : android.graphics.drawable.Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shader: Shader? = null
    override fun onBoundsChange(b: android.graphics.Rect) {
        shader = RadialGradient(b.exactCenterX(), -b.height() * 0.22f, b.width() * 0.72f,
            intArrayOf(C.alpha(C.accent, 0.07f), C.alpha(C.accent, 0f)), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
    }
    override fun draw(c: Canvas) {
        c.drawColor(C.bg)
        p.shader = shader
        c.drawRect(bounds, p)
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
}
