package com.steamcontroller.android.ui.theme

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

/**
 * The one card/button/chip background in the app: a rounded surface with a hairline, and the TV
 * focus treatment (1dp accent border + a 3dp accent glow fading outwards, never a thick ring).
 *
 * The glow is drawn inside the view's bounds: the visible surface is inset by [glowPad] on every
 * side. Every surface uses the same inset, so focusable and static cards line up with each other.
 *
 * States: focused > activated (the "current"/selected item) > default. Pressed lightens slightly.
 */
class SurfaceDrawable(
    private val density: Float,
    /** Corner radius in dp; negative means a full pill. */
    var radiusDp: Float = 10f,
    var fill: Int = C.surface,
    var stroke: Int = C.line,
    var focusFill: Int = C.focusFill,
    var activeFill: Int = C.raised,
    var activeStroke: Int = 0,
    val glowPadDp: Float = 3f,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var focused = false
    private var activated = false
    private var pressed = false
    private var enabled = true

    override fun draw(canvas: Canvas) {
        val d = density
        val pad = glowPadDp * d
        rect.set(bounds.left + pad, bounds.top + pad, bounds.right - pad, bounds.bottom - pad)
        val r = if (radiusDp < 0f) rect.height() / 2f else min(radiusDp * d, rect.height() / 2f)
        if (focused) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = d
            for (i in 3 downTo 1) {
                paint.color = C.alpha(C.accent, GLOW[i - 1])
                val o = i * d
                canvas.drawRoundRect(rect.left - o + d / 2, rect.top - o + d / 2, rect.right + o - d / 2, rect.bottom + o - d / 2, r + o, r + o, paint)
            }
        }
        paint.style = Paint.Style.FILL
        paint.color = when {
            focused -> focusFill
            activated -> activeFill
            else -> fill
        }
        if (pressed) paint.color = lighten(paint.color)
        if (paint.alpha > 0) canvas.drawRoundRect(rect, r, r, paint)
        val s = when {
            focused -> C.accent
            activated && activeStroke != 0 -> activeStroke
            else -> stroke
        }
        if (s != 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = d
            paint.color = s
            canvas.drawRoundRect(rect.left + d / 2, rect.top + d / 2, rect.right - d / 2, rect.bottom - d / 2, r, r, paint)
        }
    }

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        var f = false; var a = false; var p = false; var e = false
        for (s in state) when (s) {
            android.R.attr.state_focused -> f = true
            android.R.attr.state_activated, android.R.attr.state_selected -> a = true
            android.R.attr.state_pressed -> p = true
            android.R.attr.state_enabled -> e = true
        }
        val changed = f != focused || a != activated || p != pressed || e != enabled
        focused = f; activated = a; pressed = p; enabled = e
        if (changed) invalidateSelf()
        return changed
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private fun lighten(c: Int): Int {
        val a = c ushr 24
        val r = ((c shr 16) and 0xFF) + 10
        val g = ((c shr 8) and 0xFF) + 10
        val b = (c and 0xFF) + 10
        return (a shl 24) or (r.coerceAtMost(255) shl 16) or (g.coerceAtMost(255) shl 8) or b.coerceAtMost(255)
    }

    private companion object {
        val GLOW = floatArrayOf(0.30f, 0.18f, 0.10f)
    }
}

/** TV focus motion: scale 1.03 with an ease-out, lifted above its siblings while focused. */
object FocusFx {
    private val ease = DecelerateInterpolator()

    fun attach(v: View, scale: Float = 1.03f, onFocus: ((Boolean) -> Unit)? = null) {
        v.isFocusable = true
        v.outlineProvider = null // translationZ only reorders; no elevation shadow
        v.setOnFocusChangeListener { view, has ->
            view.animate().cancel()
            view.animate()
                .scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f)
                .translationZ(if (has) 2f else 0f)
                .setDuration(if (has) Motion.BASE else Motion.FAST)
                .setInterpolator(ease)
                .start()
            onFocus?.invoke(has)
        }
    }
}

fun View.surface(
    radiusDp: Float = 10f,
    fill: Int = C.surface,
    stroke: Int = C.line,
    focusable: Boolean = false,
    activeFill: Int = C.raised,
    activeStroke: Int = 0,
    scale: Float = 1.03f,
): SurfaceDrawable {
    val d = SurfaceDrawable(resources.displayMetrics.density, radiusDp, fill, stroke, activeFill = activeFill, activeStroke = activeStroke)
    background = d
    if (focusable) {
        FocusFx.attach(this, scale)
        isClickable = true
    }
    return d
}
