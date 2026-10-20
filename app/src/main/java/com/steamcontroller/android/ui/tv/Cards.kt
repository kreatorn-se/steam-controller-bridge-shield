package com.steamcontroller.android.ui.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.steamcontroller.android.R
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.FocusFx
import com.steamcontroller.android.ui.theme.Motion
import com.steamcontroller.android.ui.theme.dp
import com.steamcontroller.android.ui.widget.Kit

/**
 * Focus treatment for image cards, drawn as the card's foreground so it sits on top of the image:
 * the brief's 1dp accent border + 3dp accent glow when focused, a faint hairline otherwise.
 * Like SurfaceDrawable, the visible card is inset [padDp] on every side to leave room for the glow.
 */
class FocusRing(private val density: Float, var radiusDp: Float = 8f, var round: Boolean = false, private val padDp: Float = 3f) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    private var focused = false
    var activated = false
        set(v) { field = v; invalidateSelf() }

    override fun draw(c: Canvas) {
        val d = density
        val pad = padDp * d
        r.set(bounds.left + pad, bounds.top + pad, bounds.right - pad, bounds.bottom - pad)
        val rad = if (round) r.height() / 2f else radiusDp * d
        p.strokeWidth = d
        if (focused) {
            for (i in 3 downTo 1) {
                p.color = C.alpha(C.accent, GLOW[i - 1])
                val o = i * d
                c.drawRoundRect(r.left - o + d / 2, r.top - o + d / 2, r.right + o - d / 2, r.bottom + o - d / 2, rad + o, rad + o, p)
            }
            p.color = C.accent
        } else {
            p.color = if (activated) C.alpha(C.accent, 0.7f) else 0x14FFFFFF
        }
        c.drawRoundRect(r.left + d / 2, r.top + d / 2, r.right - d / 2, r.bottom - d / 2, rad, rad, p)
    }

    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val f = state.contains(android.R.attr.state_focused)
        if (f == focused) return false
        focused = f
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        val GLOW = floatArrayOf(0.35f, 0.20f, 0.10f)
    }
}

/** Clips a view to a rounded rect (or a circle) on the render thread; works with HARDWARE bitmaps. */
fun View.roundCorners(radiusDp: Float, circle: Boolean = false) {
    val rad = resources.displayMetrics.density * radiusDp
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            if (circle) outline.setOval(0, 0, view.width, view.height)
            else outline.setRoundRect(0, 0, view.width, view.height, rad)
        }
    }
    clipToOutline = true
}

/** A vertical scrim: transparent at the top to [color] at the bottom (or the reverse). */
class Scrim(private val stops: IntArray, private val positions: FloatArray?, private val horizontal: Boolean = false) : Drawable() {
    private val p = Paint()
    override fun onBoundsChange(b: Rect) {
        p.shader = if (horizontal) LinearGradient(b.left.toFloat(), 0f, b.right.toFloat(), 0f, stops, positions, Shader.TileMode.CLAMP)
        else LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(), stops, positions, Shader.TileMode.CLAMP)
    }
    override fun draw(c: Canvas) = c.drawRect(bounds, p)
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * A media card: the image (rounded, or a circle for people), an optional heart badge and an
 * optional name over the image, with a caption underneath that is either always shown or fades in
 * with focus. The card itself is the focusable; it scales and glows.
 */
class Tile(
    ctx: Context,
    val wDp: Int,
    val hDp: Int,
    private val captionOnFocus: Boolean,
    private val circle: Boolean = false,
    scale: Float = 1.05f,
) : LinearLayout(ctx) {
    val card = FrameLayout(ctx)
    val image = ImageView(ctx)
    private val heart = ImageView(ctx)
    /** Name drawn over the image, for tiles that show a capture instead of official art. */
    val over: TextView = Kit.title(ctx, "", 15f)
    private val overScrim = View(ctx)
    val title: TextView = Kit.title(ctx, "", 14f)
    val meta: TextView = Kit.label(ctx, "", 10.5f, C.text3)
    private val ring = FocusRing(resources.displayMetrics.density, round = circle)

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        gravity = if (circle) Gravity.CENTER_HORIZONTAL else Gravity.START
        val pad = dp(3)
        card.setPadding(pad, pad, pad, pad)
        card.foreground = ring
        card.isFocusable = true
        card.isClickable = true
        card.clipChildren = false
        FocusFx.attach(card, scale) { has -> onFocus(has) }
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.background = GradientDrawable().apply { setColor(C.surface2) }
        image.roundCorners(8f, circle)
        val inner = FrameLayout(ctx)
        inner.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overScrim.background = Scrim(intArrayOf(0, C.alpha(C.bg, 0.85f)), null)
        overScrim.visibility = GONE
        inner.addView(overScrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(hDp / 2), Gravity.BOTTOM))
        over.maxLines = 2
        over.visibility = GONE
        over.setShadowLayer(dp(6f), 0f, 0f, C.alpha(C.bg, 0.8f))
        inner.addView(over, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(dp(10), 0, dp(10), dp(9)) })
        heart.setImageResource(R.drawable.ic_heart_badge)
        heart.visibility = GONE
        inner.addView(heart, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
        inner.roundCorners(8f, circle)
        card.addView(inner, FrameLayout.LayoutParams(dp(wDp), dp(hDp)))
        addView(card, LayoutParams(dp(wDp + 6), dp(hDp + 6)))
        title.gravity = if (circle) Gravity.CENTER_HORIZONTAL else Gravity.START
        meta.gravity = title.gravity
        addView(title, LayoutParams(dp(wDp), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(9); marginStart = if (circle) 0 else dp(3) })
        addView(meta, LayoutParams(dp(wDp), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5); marginStart = if (circle) 0 else dp(3) })
        if (captionOnFocus) { title.alpha = 0f; meta.alpha = 0f }
        title.setTextColor(C.text2)
    }

    private val ease = DecelerateInterpolator()

    /** Told about focus changes, after the tile's own treatment. */
    var focusListener: ((Boolean) -> Unit)? = null

    private fun onFocus(has: Boolean) {
        focusListener?.invoke(has)
        title.setTextColor(if (has) C.text else C.text2)
        if (captionOnFocus) {
            val a = if (has) 1f else 0f
            title.animate().alpha(a).setDuration(if (has) Motion.SLOW else Motion.FAST).setInterpolator(ease).start()
            meta.animate().alpha(a).setDuration(if (has) Motion.SLOW else Motion.FAST).setInterpolator(ease).start()
        }
    }

    fun setFavorite(on: Boolean) { heart.visibility = if (on) VISIBLE else GONE }

    /** A name over the image (game tiles without official art). */
    fun setOver(text: String?) {
        over.text = text ?: ""
        val v = if (text.isNullOrEmpty()) GONE else VISIBLE
        over.visibility = v
        overScrim.visibility = v
    }

    fun setCaption(t: CharSequence?, m: CharSequence?) {
        title.text = t ?: ""
        meta.text = m ?: ""
        title.visibility = if (t.isNullOrEmpty()) GONE else VISIBLE
        meta.visibility = if (m.isNullOrEmpty()) GONE else VISIBLE
    }

    fun setActivated2(on: Boolean) { ring.activated = on }
}

/** Horizontal or vertical list that keeps the focused item's start on a fixed keyline. */
class KeylineLinear(ctx: Context, orientation: Int, private val keyline: () -> Int) : LinearLayoutManager(ctx, orientation, false) {
    override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean): Boolean =
        keylineScroll(this, orientation == HORIZONTAL, parent, child, keyline(), immediate)
}

class KeylineGrid(ctx: Context, span: Int, private val keyline: () -> Int) : GridLayoutManager(ctx, span) {
    override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean): Boolean =
        keylineScroll(this, false, parent, child, keyline(), immediate)
}

private val SCROLL_EASE = DecelerateInterpolator(1.6f)

private fun keylineScroll(lm: RecyclerView.LayoutManager, horizontal: Boolean, parent: RecyclerView, child: View, keyline: Int, immediate: Boolean): Boolean {
    val start = if (horizontal) lm.getDecoratedLeft(child) else lm.getDecoratedTop(child)
    val delta = start - keyline
    if (delta == 0) return false
    if (immediate) {
        if (horizontal) parent.scrollBy(delta, 0) else parent.scrollBy(0, delta)
    } else {
        if (horizontal) parent.smoothScrollBy(delta, 0, SCROLL_EASE, 220) else parent.smoothScrollBy(0, delta, SCROLL_EASE, 220)
    }
    return true
}

/**
 * RecyclerView for D-pad use: focus never escapes past the end of a row (or below the last row
 * of a grid) to some unrelated control elsewhere on screen; it simply stays put.
 */
class TvRecycler(ctx: Context, private val lockDirections: Int) : RecyclerView(ctx) {
    init {
        clipChildren = false
        clipToPadding = false
        overScrollMode = OVER_SCROLL_NEVER
        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = false
        itemAnimator = null
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
    }

    override fun focusSearch(focused: View?, direction: Int): View? {
        val r = super.focusSearch(focused, direction)
        if (focused != null && lockDirections and dirBit(direction) != 0 && (r == null || !contains(r))) return focused
        return r
    }

    private fun contains(v: View): Boolean {
        var p = v.parent
        while (p != null) { if (p === this) return true; p = p.parent }
        return false
    }

    companion object {
        const val LOCK_RIGHT = 1
        const val LOCK_DOWN = 2
        const val LOCK_LEFT = 4
        const val LOCK_UP = 8
        fun dirBit(d: Int) = when (d) {
            View.FOCUS_RIGHT -> LOCK_RIGHT
            View.FOCUS_DOWN -> LOCK_DOWN
            View.FOCUS_LEFT -> LOCK_LEFT
            View.FOCUS_UP -> LOCK_UP
            else -> 0
        }
    }
}

/** A minimal adapter: [make] builds a view, [bind] fills it for position i. */
class SimpleAdapter<T>(
    var items: List<T>,
    private val make: (ViewGroup) -> View,
    private val bind: (View, T, Int) -> Unit,
) : RecyclerView.Adapter<SimpleAdapter.Holder>() {
    class Holder(v: View) : RecyclerView.ViewHolder(v)

    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(make(parent))
    override fun onBindViewHolder(holder: Holder, position: Int) = bind(holder.itemView, items[position], position)
}
