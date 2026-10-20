package com.steamcontroller.android.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.KeyEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.Motion
import com.steamcontroller.android.ui.theme.dp
import com.steamcontroller.android.ui.theme.surface
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Settings rows. Every row is one focusable card: title (+ optional hint) on the left, the control
 * on the right. Steppers and choices change with D-pad ←/→ while focused (TV-first), and also have
 * touch targets for phones.
 */
abstract class SettingRow(ctx: Context, title: CharSequence, hint: CharSequence?) : LinearLayout(ctx) {
    protected val titleView: TextView = Kit.body(ctx, title, 15f, C.text, medium = true).apply { maxLines = 1 }
    protected val hintView: TextView = Kit.body(ctx, hint ?: "", 12.5f, C.text2).apply { maxLines = 2 }
    protected val trailing: LinearLayout = Kit.row(ctx, Gravity.CENTER_VERTICAL or Gravity.END)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        surface(radiusDp = 10f, focusable = true, scale = 1.02f)
        minimumHeight = ctx.dp(if (hint.isNullOrEmpty()) 54 else 64)
        setPadding(ctx.dp(19), ctx.dp(11), ctx.dp(17), ctx.dp(11))
        val texts = Kit.column(ctx)
        texts.addView(titleView)
        if (!hint.isNullOrEmpty()) texts.addView(hintView, Kit.lp(t = 4, ctx = ctx))
        addView(texts, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(trailing, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dp(12) })
        layoutParams = Kit.lp(b = 2, ctx = ctx)
    }

    fun setHint(text: CharSequence?) {
        hintView.text = text ?: ""
    }

    /** Re-reads the stored value (after a preset, profile load or restore). */
    abstract fun refresh()
}

/** A numeric value stepped with ←/→ (held keys accelerate). */
class StepperRow(
    ctx: Context,
    title: CharSequence,
    hint: CharSequence?,
    private val min: Float,
    private val max: Float,
    private val step: Float,
    private val format: (Float) -> String,
    private val get: () -> Float,
    private val set: (Float) -> Unit,
    private val onChange: ((Float) -> Unit)? = null,
) : SettingRow(ctx, title, hint) {
    private val valueView = Kit.value(ctx, "", 17f).apply { gravity = Gravity.END; minWidth = ctx.dp(64) }
    private val less = arrow(ctx, "‹") { nudge(-1, 0) }
    private val more = arrow(ctx, "›") { nudge(1, 0) }
    var value: Float = get()
        private set

    init {
        trailing.addView(less)
        trailing.addView(valueView)
        trailing.addView(more)
        render()
        setOnFocusChangeListenerChained { has -> val c = if (has) C.accent else C.text3; less.setTextColor(c); more.setTextColor(c) }
    }

    override fun refresh() { value = get(); render() }

    private fun render() {
        valueView.text = format(value)
        less.alpha = if (value > min + step / 2) 1f else 0.35f
        more.alpha = if (value < max - step / 2) 1f else 0.35f
    }

    private fun nudge(dir: Int, repeat: Int) {
        val mult = when {
            repeat > 24 -> 5f
            repeat > 8 -> 2f
            else -> 1f
        }
        val raw = value + dir * step * mult
        val snapped = (((raw - min) / step).roundToInt() * step + min).coerceIn(min, max)
        if (abs(snapped - value) < step / 4) return
        value = snapped
        set(value)
        render()
        onChange?.invoke(value)
        playSoundEffect(if (dir < 0) SoundEffectConstants.NAVIGATION_LEFT else SoundEffectConstants.NAVIGATION_RIGHT)
    }

    /** Programmatic change (presets): stores and renders. */
    fun setValue(v: Float) {
        value = v.coerceIn(min, max)
        set(value)
        render()
        onChange?.invoke(value)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> { nudge(-1, event.repeatCount); true }
        KeyEvent.KEYCODE_DPAD_RIGHT -> { nudge(1, event.repeatCount); true }
        else -> super.onKeyDown(keyCode, event)
    }
}

/** A list of options cycled with ←/→ or select. */
class ChoiceRow(
    ctx: Context,
    title: CharSequence,
    hint: CharSequence?,
    private val options: List<String>,
    private val get: () -> Int,
    private val set: (Int) -> Unit,
) : SettingRow(ctx, title, hint) {
    private val valueView = Kit.label(ctx, "", 12.5f, C.text)
    private val less = arrow(ctx, "‹") { move(-1) }
    private val more = arrow(ctx, "›") { move(1) }
    private var index = get()

    init {
        trailing.addView(less)
        trailing.addView(valueView, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dp(4); marginEnd = ctx.dp(4) })
        trailing.addView(more)
        setOnClickListener { move(1) }
        render()
        setOnFocusChangeListenerChained { has -> val c = if (has) C.accent else C.text3; less.setTextColor(c); more.setTextColor(c) }
    }

    override fun refresh() { index = get(); render() }

    private fun render() { valueView.text = options.getOrElse(index) { "" } }

    private fun move(d: Int) {
        index = ((index + d) % options.size + options.size) % options.size
        set(index)
        render()
        playSoundEffect(if (d < 0) SoundEffectConstants.NAVIGATION_LEFT else SoundEffectConstants.NAVIGATION_RIGHT)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> { if (event.repeatCount == 0) move(-1); true }
        KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.repeatCount == 0) move(1); true }
        else -> super.onKeyDown(keyCode, event)
    }
}

/** On/off. Select toggles; ←/→ set off/on directly. */
class ToggleRow(
    ctx: Context,
    title: CharSequence,
    hint: CharSequence?,
    private val get: () -> Boolean,
    private val set: (Boolean) -> Unit,
    private val onChange: ((Boolean) -> Unit)? = null,
) : SettingRow(ctx, title, hint) {
    private val sw = SwitchView(ctx)

    init {
        trailing.addView(sw)
        sw.setChecked(get(), animate = false)
        setOnClickListener { apply(!sw.checked) }
    }

    val checked get() = sw.checked

    override fun refresh() = sw.setChecked(get(), animate = false)

    private fun apply(v: Boolean) {
        if (v == sw.checked) return
        sw.setChecked(v, animate = true)
        set(v)
        onChange?.invoke(v)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> { apply(false); true }
        KeyEvent.KEYCODE_DPAD_RIGHT -> { apply(true); true }
        else -> super.onKeyDown(keyCode, event)
    }
}

/** A row that does something: trailing value text (optional) and a chevron. */
class ActionRow(
    ctx: Context,
    title: CharSequence,
    hint: CharSequence?,
    trailingText: CharSequence? = null,
    chevron: Boolean = true,
    onClick: (ActionRow) -> Unit,
) : SettingRow(ctx, title, hint) {
    val valueView: TextView = Kit.label(ctx, trailingText ?: "", 12f, C.text2)

    init {
        trailing.addView(valueView)
        if (chevron) trailing.addView(Kit.label(ctx, "›", 18f, C.text3).apply { setPadding(ctx.dp(10), 0, 0, ctx.dp(2)); isAllCaps = false })
        setOnClickListener { onClick(this) }
    }

    fun setTitle(t: CharSequence) { titleView.text = t }

    override fun refresh() {}
}

private fun arrow(ctx: Context, glyph: String, onTap: () -> Unit): TextView = Kit.label(ctx, glyph, 20f, C.text3).apply {
    isAllCaps = false
    gravity = Gravity.CENTER
    minWidth = ctx.dp(28)
    minHeight = ctx.dp(32)
    setPadding(0, 0, 0, ctx.dp(3))
    isFocusable = false
    setOnClickListener { onTap() }
}

/** Keeps FocusFx's scale animation and adds a callback. */
fun View.setOnFocusChangeListenerChained(extra: (Boolean) -> Unit) {
    val prior = onFocusChangeListener
    setOnFocusChangeListener { v, has -> prior?.onFocusChange(v, has); extra(has) }
}

/** A drawn switch: pill track, knob slides 160 ms, accent when on. */
class SwitchView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    var checked = false
        private set
    private var t = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var anim: ValueAnimator? = null

    fun setChecked(v: Boolean, animate: Boolean) {
        checked = v
        anim?.cancel()
        if (!animate) { t = if (v) 1f else 0f; invalidate(); return }
        anim = ValueAnimator.ofFloat(t, if (v) 1f else 0f).apply {
            duration = Motion.BASE
            interpolator = DecelerateInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((44 * d).toInt(), (26 * d).toInt())

    override fun onDraw(c: Canvas) {
        r.set(d, d, width - d, height - d)
        p.color = blend(C.raised, C.accent, t)
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, p)
        val kr = r.height() / 2 - 3f * d
        val kx = r.left + 3f * d + kr + (r.width() - 6f * d - 2 * kr) * t
        p.color = blend(C.text2, C.text, t)
        c.drawCircle(kx, r.centerY(), kr, p)
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) + ((((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * f)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/**
 * Segmented pill tabs. With [selectOnFocus] the content follows the focus (TV), otherwise it
 * changes on select. The selected tab keeps a raised fill; an optional dot marks e.g. the live mode.
 */
class Segmented(
    ctx: Context,
    items: List<String>,
    private val selectOnFocus: Boolean,
    private val onSelect: (Int) -> Unit,
) : LinearLayout(ctx) {
    val tabs: List<TextView>
    var selected = -1
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        surface(radiusDp = -1f)
        setPadding(ctx.dp(2), ctx.dp(2), ctx.dp(2), ctx.dp(2))
        tabs = items.mapIndexed { i, name ->
            Kit.label(ctx, name, 12f, C.text2).apply {
                gravity = Gravity.CENTER
                minHeight = ctx.dp(36)
                setPadding(ctx.dp(17), 0, ctx.dp(17), 0)
                surface(radiusDp = -1f, fill = 0, stroke = 0, focusable = true, activeFill = C.raised)
                setOnClickListener { select(i, notify = true) }
                setOnFocusChangeListenerChained { has -> if (has && selectOnFocus) select(i, notify = true) }
                this@Segmented.addView(this, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
    }

    fun select(i: Int, notify: Boolean) {
        if (i == selected) return
        selected = i
        tabs.forEachIndexed { k, t ->
            t.isActivated = k == i
            t.setTextColor(if (k == i) C.text else C.text2)
        }
        if (notify) onSelect(i)
    }

    /** Puts a small coloured dot before a tab's label (or removes it with 0). */
    fun setDot(i: Int, color: Int) {
        val t = tabs.getOrNull(i) ?: return
        if (color == 0) { t.setCompoundDrawablesRelative(null, null, null, null); return }
        val dr = android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.OvalShape()).apply {
            paint.color = color
            setBounds(0, 0, context.dp(6), context.dp(6))
        }
        t.setCompoundDrawablesRelative(dr, null, null, null)
        t.compoundDrawablePadding = context.dp(7)
    }
}
