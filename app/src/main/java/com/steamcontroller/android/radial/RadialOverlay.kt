package com.steamcontroller.android.radial

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The radial menus on screen: one overlay window per pad (TYPE_APPLICATION_OVERLAY, the pill's
 * family), ~360 dp, on that pad's side of the screen, never focusable or touchable. All calls come
 * from the input thread and are posted to the main thread, so drawing never delays a frame.
 * A window is added when its menu first opens and removed after a while unused.
 */
class RadialOverlay(context: Context) : RadialEngine.Ui {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val wm = app.getSystemService(WindowManager::class.java)
    private val views = arrayOfNulls<RadialView>(2)

    override fun open(left: Boolean, menu: Menu, test: Boolean) {
        main.post { show(left, menu, test) }
    }

    override fun highlight(left: Boolean, index: Int) {
        main.post { views[side(left)]?.setHighlight(index) }
    }

    override fun fired(left: Boolean, index: Int) {
        main.post { views[side(left)]?.fire(index) }
    }

    override fun close(left: Boolean) {
        main.post { views[side(left)]?.hide() }
    }

    /** Removes both windows (service stopping). */
    fun dispose() {
        main.post {
            for (i in 0..1) detach(i)
        }
    }

    private fun side(left: Boolean) = if (left) 0 else 1

    private fun show(left: Boolean, menu: Menu, test: Boolean) {
        val i = side(left)
        main.removeCallbacks(detachers[i])
        val v = views[i] ?: attach(left) ?: return
        v.setMenu(menu, test)
        v.appear()
    }

    private val detachers = arrayOf(Runnable { detach(0) }, Runnable { detach(1) })

    /** [size dp, height % (+ = down), edge distance %] from liveSettings.radialLayout (web sliders). */
    private fun layout(): FloatArray = try {
        val o = org.json.JSONObject(com.steamcontroller.android.backup.ConfigExtras.live(app) ?: "{}").optJSONObject("radialLayout")
        floatArrayOf(
            (o?.optDouble("size", 220.0) ?: 220.0).toFloat().coerceIn(140f, 420f),
            (o?.optDouble("height", 0.0) ?: 0.0).toFloat().coerceIn(-40f, 40f),
            (o?.optDouble("separation", 7.0) ?: 7.0).toFloat().coerceIn(0f, 40f))
    } catch (_: Exception) { floatArrayOf(220f, 0f, 7f) }

    private fun attach(left: Boolean): RadialView? {
        if (!Settings.canDrawOverlays(app)) return null
        val lay = layout()
        RadialView.sizeDp = lay[0]
        val v = RadialView(app)
        v.onHidden = { main.removeCallbacks(detachers[side(left)]); main.postDelayed(detachers[side(left)], IDLE_DETACH_MS) }
        val size = v.sizePx
        val dm = app.resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = (if (left) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
            // Inside the overscan-safe area, a little in from the edge on the pad's side.
            x = (dm.widthPixels * lay[2] / 100f).toInt()
            y = (dm.heightPixels * lay[1] / 100f).toInt()
            title = "RadialMenu"
        }
        return try {
            wm.addView(v, lp)
            views[side(left)] = v
            v
        } catch (t: Throwable) {
            Log.w(TAG, "addView failed: ${t.message}")
            null
        }
    }

    private fun detach(i: Int) {
        val v = views[i] ?: return
        if (v.showing) return
        views[i] = null
        try {
            wm.removeViewImmediate(v)
        } catch (t: Throwable) {
            Log.w(TAG, "removeView failed: ${t.message}")
        }
    }

    private companion object {
        const val TAG = "RadialOverlay"
        const val IDLE_DETACH_MS = 20_000L
    }
}

/**
 * The drawn menu: a translucent near-black disc, equal slices clockwise from the top (slice 0
 * centred on the menu's rotation), an icon and a condensed-caps label per slice, the deadzone as
 * an inner ring with the highlighted slice's name in it, and the highlight in Steam blue (or the
 * slice's colour) as a filled sector with a bright outer arc.
 */
class RadialView(context: Context) : View(context) {
    // Everything inside the wheel is drawn in "d" units, scaled with the wheel's size.
    private val d = resources.displayMetrics.density * (sizeDp / SIZE_DP)
    val sizePx = (sizeDp * resources.displayMetrics.density).toInt()

    var showing = false
        private set
    var onHidden: (() -> Unit)? = null

    private var menu: Menu? = null
    private var test = false
    private var highlight = -1
    private var flash = -1

    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(214, 11, 12, 14) }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = 0xFF2E3239.toInt() }
    private val sep = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; color = 0x3DF2F4F7 }
    private val hiFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hiArc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d; strokeCap = Paint.Cap.BUTT }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 8f * d; maskFilter = BlurMaskFilter(6f * d, BlurMaskFilter.Blur.NORMAL)
    }
    private val hub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 11, 12, 14) }
    private val hubRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; color = 0xFF2E3239.toInt() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.08f
    }
    private val centre = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.06f
        color = TEXT
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val oval = RectF()
    private val inner = RectF()
    private val dst = RectF()

    init {
        // The glow's blur needs a software layer on older GPUs; the view is small.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        alpha = 0f
        visibility = INVISIBLE
    }

    fun setMenu(m: Menu, isTest: Boolean) {
        menu = m
        test = isTest
        highlight = -1
        flash = -1
        invalidate()
    }

    fun setHighlight(i: Int) {
        if (i == highlight) return
        highlight = i
        invalidate()
    }

    fun appear() {
        showing = true
        visibility = VISIBLE
        animate().cancel()
        scaleX = 0.94f; scaleY = 0.94f
        animate().setStartDelay(0).alpha(1f).scaleX(1f).scaleY(1f).setDuration(140L).setInterpolator(DecelerateInterpolator()).start()
    }

    /** The fired slice flashes, then the menu closes. */
    fun fire(i: Int) {
        flash = i
        highlight = i
        invalidate()
        animate().cancel()
        animate().setStartDelay(0).scaleX(1.03f).scaleY(1.03f).setDuration(90L).setInterpolator(DecelerateInterpolator()).withEndAction {
            hide(delay = 60L)
        }.start()
    }

    fun hide(delay: Long = 0L) {
        if (!showing) return
        showing = false
        animate().cancel()
        animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setStartDelay(delay).setDuration(120L).setInterpolator(AccelerateInterpolator()).withEndAction {
            animate().setStartDelay(0)
            visibility = INVISIBLE
            flash = -1
            onHidden?.invoke()
        }.start()
    }

    override fun onDraw(c: Canvas) {
        val m = menu ?: return
        val n = m.slices.size
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 14f * d
        // The hub ring shows the deadzone, never so small the centre label cannot fit.
        val rd = (r * m.deadzone).coerceIn(r * 0.3f, r * 0.62f)
        oval.set(cx - r, cy - r, cx + r, cy + r)
        inner.set(cx - rd, cy - rd, cx + rd, cy + rd)
        val w = 360f / n

        c.drawCircle(cx, cy, r, disc)

        val h = highlight
        if (h in 0 until n) {
            val accent = m.slices[h].color ?: ACCENT
            val start = m.rotation + h * w - w / 2f - 90f
            path.reset()
            path.arcTo(oval, start, w)
            path.arcTo(inner, start + w, -w)
            path.close()
            hiFill.color = withAlpha(accent, if (flash == h) 150 else 70)
            c.drawPath(path, hiFill)
            glow.color = withAlpha(accent, 140)
            c.drawArc(oval, start + 1f, w - 2f, false, glow)
            hiArc.color = accent
            c.drawArc(oval, start + 1f, w - 2f, false, hiArc)
        }

        for (i in 0 until n) {
            val a = Math.toRadians((m.rotation + i * w - w / 2f - 90f).toDouble())
            c.drawLine(cx + rd * cos(a).toFloat(), cy + rd * sin(a).toFloat(), cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), sep)
        }
        c.drawCircle(cx, cy, r, rim)

        // Icons and labels on the middle of each slice's band.
        val mid = (r + rd) / 2f
        val arcLen = (Math.PI * 2 * mid / n).toFloat()
        val iconSize = min(28f * d, min(arcLen * 0.52f, (r - rd) * 0.5f))
        val labelSp = if (n <= 8) 7.5f else 6.5f
        label.textSize = labelSp * d
        for (i in 0 until n) {
            val s = m.slices[i]
            val a = Math.toRadians((m.rotation + i * w - 90f).toDouble())
            val px = cx + mid * cos(a).toFloat()
            val py = cy + mid * sin(a).toFloat()
            val on = i == h
            val tint = if (on) (s.color ?: TEXT) else (s.color?.let { withAlpha(it, 230) } ?: TEXT2)
            val bmp = RadialIcons.bitmap(s.icon)
            val text = fit(s.label.uppercase(), arcLen * 0.95f)
            if (bmp != null) {
                val top = py - (iconSize + (if (text.isNotEmpty()) 4f * d + label.textSize else 0f)) / 2f
                dst.set(px - iconSize / 2f, top, px + iconSize / 2f, top + iconSize)
                iconPaint.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
                iconPaint.alpha = if (on) 255 else 225
                c.drawBitmap(bmp, null, dst, iconPaint)
                if (text.isNotEmpty()) {
                    label.color = if (on) TEXT else TEXT2
                    c.drawText(text, px, dst.bottom + 4f * d + label.textSize * 0.8f, label)
                }
            } else {
                label.color = if (on) TEXT else 0xFFC9CED6.toInt()
                label.textSize = (labelSp + 2f) * d
                c.drawText(fit(s.label.uppercase(), arcLen * 0.95f), px, py + label.textSize * 0.35f, label)
                label.textSize = labelSp * d
            }
        }

        // Deadzone hub with the highlighted name (or the test badge).
        c.drawCircle(cx, cy, rd, hub)
        c.drawCircle(cx, cy, rd, hubRing)
        if (h in 0 until n) {
            centre.textSize = 9.5f * d
            centre.color = TEXT
            c.drawText(fit(m.slices[h].label.uppercase(), rd * 1.7f, centre), cx, cy + centre.textSize * 0.35f, centre)
        } else {
            centre.textSize = 7f * d
            centre.color = if (test) WARN else TEXT3
            c.drawText(if (test) "TEST" else "•", cx, cy + centre.textSize * 0.35f, centre)
        }
    }

    private fun fit(t: String, maxW: Float, p: Paint = label): String {
        if (p.measureText(t) <= maxW) return t
        var s = t
        while (s.length > 1 && p.measureText("$s…") > maxW) s = s.dropLast(1)
        return "$s…"
    }

    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

    companion object {
        const val SIZE_DP = 220f
        /** Size for the next wheel, from liveSettings.radialLayout.size (set before construction). */
        @Volatile var sizeDp = SIZE_DP
        const val ACCENT = 0xFF1A9FFF.toInt()
        const val TEXT = 0xFFF2F4F7.toInt()
        const val TEXT2 = 0xFF8B929C.toInt()
        const val TEXT3 = 0xFF5B626C.toInt()
        const val WARN = 0xFFFFB547.toInt()
    }
}
