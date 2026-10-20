package com.steamcontroller.android.ui.diagram

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.PathParser
import com.steamcontroller.android.ui.live.Ctl
import com.steamcontroller.android.ui.mapping.Names
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.Motion
import com.steamcontroller.android.ui.theme.Type
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The Steam Controller (2026) drawn from [ControllerGeometry] in the dark style, with callout chips
 * on leader lines (each chip = control badge + what it is mapped to), live press highlights,
 * trackpad touch dots, stick deflection, and D-pad focus between the controls.
 *
 * Focus lives inside the view: while it has focus, the D-pad moves between hotspots spatially and
 * hands focus back to the framework only when there is nothing further in that direction, so the
 * rail (left) and the tab row (above) are reached naturally. Select fires [Listener.onSelect].
 *
 * Drawing is allocation-free: every Path, shader and ellipsized label is prepared in [relayout];
 * [onDraw] only reads them. Live data invalidates only when a level actually changed.
 */
class ControllerDiagramView(context: Context) : View(context) {

    enum class Face { FRONT, BACK }

    enum class ChipStyle { DEFAULT, CUSTOM, NONE, INFO }

    interface Listener {
        fun onSelect(id: String)
        fun onFocusMoved(id: String?) {}
    }

    var listener: Listener? = null

    var face: Face = Face.FRONT
        set(v) { if (field != v) { field = v; focusIndex = -1; relayout(); invalidate() } }

    /** Callout chips and leader lines; off gives a bare controller (tester, narrow screens). */
    var callouts: Boolean = true
        set(v) { if (field != v) { field = v; relayout(); invalidate() } }

    /** Whether D-pad focus and taps select controls. */
    var interactive: Boolean = true
        set(v) { field = v; isFocusable = v; isClickable = v }

    /** A control kept highlighted while not focused, e.g. the one whose editor is open. */
    var selectedId: String? = null
        set(v) { field = v; invalidate() }

    // ── geometry ──────────────────────────────────────────────────────────────────────
    private class Item(val src: ControllerGeometry.Control) {
        val id = src.id
        val kind = src.kind
        val isShoulder = kind == "trigger" || kind == "bumper"
        val outerVb: Path = PathParser.createPathFromPathData(src.paths[0])
        val allVb: Path = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            for (p in src.paths) addPath(PathParser.createPathFromPathData(p))
        }
        val multi = src.paths.size > 1
        val outer = Path()
        val all = Path().apply { fillType = Path.FillType.EVEN_ODD }
        val bbox = RectF()
        var ax = 0f
        var ay = 0f
        var topLight: Shader? = null
        val live = Ctl.index(src.id) // -1 for LED

        // callout
        var hasChip = false
        val chip = RectF()
        val leader = FloatArray(8)
        var leaderN = 0
        var badge = Names.source(src.id).uppercase()
        var badgeW = 0f
        var text = ""
        var shown: String = ""
        var style = ChipStyle.DEFAULT

        // live
        var level = 0f
        var dx = 0f
        var dy = 0f
        var touchX = 0f
        var touchY = 0f
        var touching = false

        fun fx(callouts: Boolean) = if (callouts && hasChip) chip.centerX() else ax
        fun fy(callouts: Boolean) = if (callouts && hasChip) chip.centerY() else ay
    }

    private class FaceGeo(val v: ControllerGeometry.View) {
        val bodyVb: Path = PathParser.createPathFromPathData(v.body)
        val contourVb: Path? = v.contour?.let { PathParser.createPathFromPathData(it) }
        val body = Path()
        val contour = Path()
        val items: Array<Item> = v.controls.map { Item(it) }.toTypedArray()
        var bodyShader: Shader? = null
        /** live index -> item index, -1 when this face does not show that control */
        val byLive = IntArray(Ctl.COUNT) { -1 }.also { arr -> items.forEachIndexed { i, it -> if (it.live >= 0) arr[it.live] = i } }
    }

    private val front = FaceGeo(ControllerGeometry.front)
    private val back = FaceGeo(ControllerGeometry.back)
    private val faces = arrayOf(front, back)
    private val geo get() = if (face == Face.FRONT) front else back

    private val m = Matrix()
    private var scale = 1f

    // ── paints ────────────────────────────────────────────────────────────────────────
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val badgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Type.condensedBold(context); textSize = 10f * resources.displayMetrics.scaledDensity
        letterSpacing = 0.06f; textAlign = Paint.Align.CENTER
    }
    private val chipPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Type.condensedBold(context); textSize = 11.5f * resources.displayMetrics.scaledDensity
        letterSpacing = 0.07f
    }
    private val tmp = RectF()

    // ── focus & animation ─────────────────────────────────────────────────────────────
    private var focusIndex = -1
    private var focusT = 1f
    private val focusAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = Motion.BASE; interpolator = DecelerateInterpolator()
        addUpdateListener { focusT = it.animatedValue as Float; invalidate() }
    }
    private var pulseIndex = -1
    private var pulseT = 1f
    private val pulseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 560L; interpolator = DecelerateInterpolator()
        addUpdateListener { pulseT = it.animatedValue as Float; invalidate() }
    }

    init {
        isFocusable = true
        isClickable = true
    }

    // ── public API ────────────────────────────────────────────────────────────────────

    /** Sets a callout; call [commitChips] after a batch. */
    fun setChip(id: String, text: String, style: ChipStyle) {
        for (g in faces) g.items.firstOrNull { it.id == id }?.let { it.text = text.uppercase(); it.style = style }
    }

    fun commitChips() { ellipsize(); invalidate() }

    val focusedId: String? get() = geo.items.getOrNull(focusIndex)?.id

    /** Moves the internal focus to [id] (switching face if needed) and optionally pulses it. */
    fun focusControl(id: String, pulse: Boolean) {
        val inFront = front.items.any { it.id == id && it.kind != "led" }
        face = if (inFront) Face.FRONT else Face.BACK
        val i = geo.items.indexOfFirst { it.id == id }
        if (i < 0) return
        if (!isFocused) { focusIndex = i; requestFocus() }
        setFocusIndex(i)
        if (pulse) { pulseIndex = i; pulseAnim.cancel(); pulseAnim.start() }
    }

    /** Copies live levels (indexed by [Ctl]); redraws only when something visibly changed. */
    fun setLevels(levels: FloatArray) {
        var changed = false
        for (g in faces) for (it in g.items) {
            if (it.live < 0) continue
            val v = levels[it.live]
            if (abs(v - it.level) > 0.01f) { it.level = v; if (g === geo) changed = true }
        }
        if (changed) invalidate()
    }

    /** Stick deflection in -1..1 (up positive); the cap shifts a few dp. */
    fun setStick(left: Boolean, x: Float, y: Float) {
        val it = front.items.firstOrNull { i -> i.id == if (left) "LS" else "RS" } ?: return
        val k = 5f * d
        val nx = x * k
        val ny = -y * k
        if (abs(nx - it.dx) > 0.4f || abs(ny - it.dy) > 0.4f) { it.dx = nx; it.dy = ny; if (face == Face.FRONT) invalidate() }
    }

    /** Trackpad finger position in -1..1 (up positive), or not touching. */
    fun setTouch(left: Boolean, touching: Boolean, x: Float, y: Float) {
        val it = front.items.firstOrNull { i -> i.id == if (left) "LPAD" else "RPAD" } ?: return
        if (touching != it.touching || (touching && (abs(x - it.touchX) > 0.005f || abs(y - it.touchY) > 0.005f))) {
            it.touching = touching; it.touchX = x; it.touchY = y
            if (face == Face.FRONT) invalidate()
        }
    }

    // ── layout ────────────────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
    }

    private fun relayout() {
        if (width == 0 || height == 0) return
        val g = geo
        val vb = g.v.viewBox
        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        val w = (width - paddingLeft - paddingRight).toFloat()
        val h = (height - paddingTop - paddingBottom).toFloat()
        val hasTop = callouts && g.items.any { it.src.side == "top" && it.kind != "led" }
        val colW = if (callouts) (w * 0.2f).coerceIn(128f * d, 176f * d) else 0f
        val gap = if (callouts) 30f * d else 0f
        val topReserve = if (hasTop) CHIP_H * d + 14f * d else 0f
        val areaX = left + colW + gap
        val areaW = w - 2 * (colW + gap)
        val areaY = top + topReserve
        val areaH = h - topReserve
        scale = min(areaW / vb[2], areaH / vb[3])
        val ox = areaX + (areaW - vb[2] * scale) / 2f - vb[0] * scale
        val oy = areaY + (areaH - vb[3] * scale) / 2f - vb[1] * scale
        m.setScale(scale, scale)
        m.postTranslate(ox, oy)

        g.bodyVb.transform(m, g.body)
        g.contourVb?.transform(m, g.contour)
        g.bodyShader = LinearGradient(
            0f, oy + (vb[1] + 40f) * scale, 0f, oy + (vb[1] + vb[3]) * scale,
            intArrayOf(0xFF252A31.toInt(), C.surface2, 0xFF141619.toInt()), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        val pt = FloatArray(2)
        for (it in g.items) {
            it.outerVb.transform(m, it.outer)
            it.allVb.transform(m, it.all)
            it.all.fillType = Path.FillType.EVEN_ODD
            val b = it.src.bbox
            it.bbox.set(ox + b[0] * scale, oy + b[1] * scale, ox + b[2] * scale, oy + b[3] * scale)
            pt[0] = it.src.ax; pt[1] = it.src.ay
            m.mapPoints(pt)
            it.ax = pt[0]; it.ay = pt[1]
            it.topLight = LinearGradient(0f, it.bbox.top, 0f, it.bbox.bottom, 0x12FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            it.hasChip = false
        }
        if (callouts) layoutChips(g, left, top, w, h, colW, ox, oy)
        ellipsize()
    }

    private fun layoutChips(g: FaceGeo, left: Float, top: Float, w: Float, h: Float, colW: Float, ox: Float, oy: Float) {
        val ch = CHIP_H * d
        val bottom = top + h
        for (side in arrayOf("left", "right")) {
            val list = g.items.filter { it.src.side == side && it.kind != "led" }
                .sortedWith(compareBy<Item>({ it.ay }, { if (side == "left") it.ax else -it.ax }))
            // Equal anchor heights: the control nearer the column takes the upper chip, so the
            // leader lines never cross (sort key above, then this stable pass).
            val ys = FloatArray(list.size) { list[it].ay }
            val sp = ch + CHIP_GAP * d
            for (i in ys.indices) ys[i] = max(ys[i], if (i == 0) top + ch / 2f else ys[i - 1] + sp)
            for (i in ys.indices.reversed()) ys[i] = min(ys[i], if (i == ys.lastIndex) bottom - ch / 2f else ys[i + 1] - sp)
            list.forEachIndexed { i, it ->
                val x0 = if (side == "left") left else left + w - colW
                it.chip.set(x0, ys[i] - ch / 2f, x0 + colW, ys[i] + ch / 2f)
                val attachX = if (side == "left") it.chip.right else it.chip.left
                val knee = attachX + (if (side == "left") 1 else -1) * 14f * d
                it.leader[0] = it.ax; it.leader[1] = it.ay
                it.leader[2] = knee; it.leader[3] = ys[i]
                it.leader[4] = attachX; it.leader[5] = ys[i]
                it.leaderN = 3
                it.hasChip = true
            }
        }
        for (it in g.items) {
            val side = it.src.side
            if (it.kind == "led" || (side != "top" && side != "bottom")) continue
            val cw = min(colW + 24f * d, 196f * d)
            val cy = if (side == "top") top + ch / 2f + 2f * d else min(oy + 318f * scale, bottom - ch / 2f)
            it.chip.set(it.ax - cw / 2f, cy - ch / 2f, it.ax + cw / 2f, cy + ch / 2f)
            it.leader[0] = it.ax; it.leader[1] = it.ay
            it.leader[2] = it.ax; it.leader[3] = if (side == "top") it.chip.bottom else it.chip.top
            it.leaderN = 2
            it.hasChip = true
        }
    }

    private fun ellipsize() {
        for (g in faces) for (it in g.items) {
            it.badgeW = max(18f * d, badgePaint.measureText(it.badge) + 10f * d)
            if (!it.hasChip) continue
            val avail = it.chip.width() - (4f * d + it.badgeW + 8f * d) - (if (it.style == ChipStyle.CUSTOM) 20f * d else 10f * d)
            it.shown = TextUtils.ellipsize(it.text, chipPaint, max(0f, avail), TextUtils.TruncateAt.END).toString()
        }
    }

    // ── drawing ───────────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val g = geo
        val hi = highlightIndex()
        // Shoulders sit behind the body.
        if (face == Face.FRONT) {
            for (i in g.items.indices) if (g.items[i].kind == "trigger") drawControl(canvas, g.items[i], i == hi)
            for (i in g.items.indices) if (g.items[i].kind == "bumper") drawControl(canvas, g.items[i], i == hi)
        }
        fill.shader = g.bodyShader
        canvas.drawPath(g.body, fill)
        fill.shader = null
        // Rim light: the body edge offset down by a hair, clipped inside, reads as a lit top edge.
        canvas.save()
        canvas.clipPath(g.body)
        canvas.translate(0f, 1.2f * d)
        stroke.strokeWidth = 2f * d; stroke.color = 0x0DFFFFFF
        canvas.drawPath(g.body, stroke)
        canvas.restore()
        stroke.strokeWidth = 1.5f * d; stroke.color = C.lineStrong
        canvas.drawPath(g.body, stroke)
        if (g.contourVb != null) {
            stroke.strokeWidth = d; stroke.color = 0x0BFFFFFF
            canvas.drawPath(g.contour, stroke)
        }
        if (face == Face.BACK) { canvas.save(); canvas.clipPath(g.body) }
        for (i in g.items.indices) if (!g.items[i].isShoulder) drawControl(canvas, g.items[i], i == hi)
        if (face == Face.BACK) canvas.restore()
        for (it in g.items) if (it.touching && it.kind == "pad") drawTouch(canvas, it)
        if (pulseIndex in g.items.indices && pulseT < 1f) drawPulse(canvas, g.items[pulseIndex])
        if (callouts) for (i in g.items.indices) if (g.items[i].hasChip) drawCallout(canvas, g.items[i], i == hi)
    }

    private fun highlightIndex(): Int {
        if (isFocused && focusIndex >= 0) return focusIndex
        val sel = selectedId ?: return -1
        return geo.items.indexOfFirst { it.id == sel }
    }

    private fun drawControl(c: Canvas, it: Item, focus: Boolean) {
        if (it.kind == "led") {
            fill.color = 0x1FFFFFFF
            c.drawPath(it.all, fill)
            return
        }
        val stick = it.kind == "stick" && (it.dx != 0f || it.dy != 0f)
        if (stick) { c.save(); c.translate(it.dx, it.dy) }
        val lv = it.level
        val on = lv > 0.02f
        val strong = on && (lv >= 0.9f || it.kind == "trigger")
        if (on || focus) {
            for (k in 0 until 3) {
                stroke.strokeWidth = GLOW_W[k] * d
                stroke.color = C.alpha(C.accent, GLOW_A[k] * (if (strong) 1f else if (focus) 0.8f else 0.5f))
                c.drawPath(it.outer, stroke)
            }
        }
        fill.color = C.control
        c.drawPath(it.outer, fill)
        shade.shader = it.topLight
        c.drawPath(it.outer, shade)
        shade.shader = null
        when {
            it.kind == "trigger" && on -> {
                c.save()
                c.clipRect(it.bbox.left - d, it.bbox.bottom - it.bbox.height() * lv, it.bbox.right + d, it.bbox.bottom + d)
                fill.color = C.alpha(C.accent, 0.9f)
                c.drawPath(it.outer, fill)
                c.restore()
            }
            strong -> { fill.color = C.alpha(C.accent, 0.92f); c.drawPath(it.outer, fill) }
            on -> { fill.color = C.alpha(C.accent, 0.2f); c.drawPath(it.outer, fill) }
            focus -> { fill.color = C.alpha(C.accent, 0.16f * focusT); c.drawPath(it.outer, fill) }
        }
        if (it.multi) {
            fill.color = if (strong && it.kind != "trigger") 0x5904121F else 0x14FFFFFF
            c.drawPath(it.all, fill)
        }
        stroke.strokeWidth = (if (focus) 1.25f else 1f) * d
        stroke.color = if (focus || on) C.accent else C.controlEdge
        c.drawPath(it.outer, stroke)
        if (stick) c.restore()
    }

    private fun drawTouch(c: Canvas, it: Item) {
        val b = it.bbox
        val px = b.left + b.width() * (0.5f + it.touchX * 0.42f)
        val py = b.top + b.height() * (0.5f - it.touchY * 0.42f)
        fill.color = C.alpha(C.accent, 0.10f); c.drawCircle(px, py, 11f * d, fill)
        fill.color = C.alpha(C.accent, 0.22f); c.drawCircle(px, py, 7f * d, fill)
        fill.color = C.accent; c.drawCircle(px, py, 3.5f * d, fill)
        fill.color = C.text; c.drawCircle(px, py, 1.4f * d, fill)
    }

    private fun drawPulse(c: Canvas, it: Item) {
        stroke.strokeWidth = (2f + 14f * pulseT) * d
        stroke.color = C.alpha(C.accent, 0.45f * (1f - pulseT))
        c.drawPath(it.outer, stroke)
    }

    private fun drawCallout(c: Canvas, it: Item, focus: Boolean) {
        val lit = focus || it.level >= 0.5f
        // leader
        stroke.strokeWidth = d
        stroke.color = if (lit) C.accent else 0x29FFFFFF
        var px = it.leader[0]; var py = it.leader[1]
        for (k in 1 until it.leaderN) {
            val nx = it.leader[k * 2]; val ny = it.leader[k * 2 + 1]
            c.drawLine(px, py, nx, ny, stroke)
            px = nx; py = ny
        }
        if (focus) { fill.color = C.alpha(C.accent, 0.25f); c.drawCircle(it.ax, it.ay, 6f * d, fill) }
        fill.color = if (lit) C.accent else C.text3
        c.drawCircle(it.ax, it.ay, (if (focus) 3f else 2.2f) * d, fill)

        // chip
        val r = it.chip
        val rad = r.height() / 2f
        val sc = if (focus) 1f + 0.03f * focusT else 1f
        c.save()
        if (sc != 1f) c.scale(sc, sc, r.centerX(), r.centerY())
        if (focus) {
            stroke.strokeWidth = d
            for (k in 3 downTo 1) {
                stroke.color = C.alpha(C.accent, FOCUS_A[k - 1] * focusT)
                val o = k * d
                tmp.set(r.left - o + d / 2, r.top - o + d / 2, r.right + o - d / 2, r.bottom + o - d / 2)
                c.drawRoundRect(tmp, rad + o, rad + o, stroke)
            }
        }
        fill.color = if (focus) C.focusFill else C.surface
        c.drawRoundRect(r, rad, rad, fill)
        stroke.strokeWidth = d
        stroke.color = if (focus) C.accent else C.line
        tmp.set(r.left + d / 2, r.top + d / 2, r.right - d / 2, r.bottom - d / 2)
        c.drawRoundRect(tmp, rad - d / 2, rad - d / 2, stroke)
        // badge
        val pressed = it.level >= 0.5f
        tmp.set(r.left + 4f * d, r.top + 4f * d, r.left + 4f * d + it.badgeW, r.bottom - 4f * d)
        fill.color = if (pressed) C.accent else C.raised
        c.drawRoundRect(tmp, tmp.height() / 2f, tmp.height() / 2f, fill)
        badgePaint.color = if (pressed) C.onAccent else C.text
        c.drawText(it.badge, tmp.centerX(), r.centerY() + badgePaint.textSize * 0.36f, badgePaint)
        // target
        chipPaint.color = when {
            it.style == ChipStyle.NONE -> C.text3
            it.style == ChipStyle.INFO -> C.text2
            focus || it.style == ChipStyle.CUSTOM -> C.text
            else -> C.textDefault
        }
        c.drawText(it.shown, tmp.right + 8f * d, r.centerY() + chipPaint.textSize * 0.36f, chipPaint)
        if (it.style == ChipStyle.CUSTOM) {
            fill.color = C.accent
            c.drawCircle(r.right - 11f * d, r.centerY(), 2.5f * d, fill)
        }
        c.restore()
    }

    // ── focus & keys ──────────────────────────────────────────────────────────────────

    private fun focusables(): List<Int> = geo.items.indices.filter { i ->
        val it = geo.items[i]
        it.kind != "led" && (!callouts || it.hasChip)
    }

    private fun setFocusIndex(i: Int) {
        if (i == focusIndex) { invalidate(); return }
        focusIndex = i
        focusAnim.cancel(); focusT = 0f; focusAnim.start()
        listener?.onFocusMoved(focusedId)
        invalidate()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) {
            listener?.onFocusMoved(null)
            invalidate()
            return
        }
        val list = focusables()
        if (list.isEmpty()) return
        if (previouslyFocusedRect == null && focusIndex in list) {
            setFocusIndex(focusIndex)
            return
        }
        // Enter at the hotspot nearest to where focus came from, biased along the travel direction.
        val rx = previouslyFocusedRect?.exactCenterX() ?: (width / 2f)
        val ry = previouslyFocusedRect?.exactCenterY() ?: (height / 2f)
        val best = list.minByOrNull { i ->
            val it = geo.items[i]
            val x = it.fx(callouts)
            val y = it.fy(callouts)
            when (direction) {
                FOCUS_RIGHT -> x + 2f * abs(y - ry)
                FOCUS_LEFT -> (width - x) + 2f * abs(y - ry)
                FOCUS_DOWN -> y + 2f * abs(x - rx)
                FOCUS_UP -> (height - y) + 2f * abs(x - rx)
                else -> hypot(x - rx, y - ry)
            }
        } ?: list.first()
        setFocusIndex(best)
    }

    override fun getFocusedRect(r: Rect) {
        val it = geo.items.getOrNull(focusIndex)
        if (it == null) { super.getFocusedRect(r); return }
        if (callouts && it.hasChip) it.chip.round(r) else r.set((it.ax - 12 * d).toInt(), (it.ay - 12 * d).toInt(), (it.ax + 12 * d).toInt(), (it.ay + 12 * d).toInt())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!interactive) return super.onKeyDown(keyCode, event)
        val dir = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> FOCUS_RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> FOCUS_DOWN
            else -> 0
        }
        if (dir != 0) {
            val next = findNext(dir)
            if (next >= 0) { setFocusIndex(next); return true }
            return false
        }
        if (isSelectKey(keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (interactive && isSelectKey(keyCode)) {
            focusedId?.let { id -> playSoundEffect(android.view.SoundEffectConstants.CLICK); listener?.onSelect(id) }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun isSelectKey(k: Int) = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER ||
        k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A

    private fun findNext(dir: Int): Int {
        val list = focusables()
        val cur = geo.items.getOrNull(focusIndex) ?: return list.firstOrNull() ?: -1
        val cx = cur.fx(callouts)
        val cy = cur.fy(callouts)
        var best = -1
        var bestScore = Float.MAX_VALUE
        for (i in list) {
            if (i == focusIndex) continue
            val it = geo.items[i]
            val dx = it.fx(callouts) - cx
            val dy = it.fy(callouts) - cy
            val major: Float
            val minor: Float
            when (dir) {
                FOCUS_RIGHT -> { major = dx; minor = abs(dy) }
                FOCUS_LEFT -> { major = -dx; minor = abs(dy) }
                FOCUS_DOWN -> { major = dy; minor = abs(dx) }
                else -> { major = -dy; minor = abs(dx) }
            }
            if (major <= 4f * d) continue
            // A 63° cone for up/down keeps "up" from leaping sideways across the controller.
            if ((dir == FOCUS_UP || dir == FOCUS_DOWN) && minor > major * 2f) continue
            val score = major + minor * 3f
            if (score < bestScore) { bestScore = score; best = i }
        }
        return best
    }

    // ── touch ─────────────────────────────────────────────────────────────────────────

    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!interactive) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; return true }
            MotionEvent.ACTION_UP -> {
                if (hypot(e.x - downX, e.y - downY) > slop) return true
                val i = hitTest(e.x, e.y)
                if (i >= 0) {
                    setFocusIndex(i)
                    playSoundEffect(android.view.SoundEffectConstants.CLICK)
                    listener?.onSelect(geo.items[i].id)
                }
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun hitTest(x: Float, y: Float): Int {
        val g = geo
        if (callouts) g.items.forEachIndexed { i, it -> if (it.hasChip && it.chip.contains(x, y)) return i }
        var best = -1
        var bestArea = Float.MAX_VALUE
        val pad = 6f * d
        g.items.forEachIndexed { i, it ->
            if (it.kind == "led") return@forEachIndexed
            val b = it.bbox
            if (x >= b.left - pad && x <= b.right + pad && y >= b.top - pad && y <= b.bottom + pad) {
                val a = b.width() * b.height()
                if (a < bestArea) { bestArea = a; best = i }
            }
        }
        return best
    }

    private companion object {
        const val CHIP_H = 26f
        const val CHIP_GAP = 6f
        val GLOW_W = floatArrayOf(7f, 4.5f, 2.5f)
        val GLOW_A = floatArrayOf(0.07f, 0.12f, 0.22f)
        val FOCUS_A = floatArrayOf(0.30f, 0.18f, 0.10f)
    }
}
