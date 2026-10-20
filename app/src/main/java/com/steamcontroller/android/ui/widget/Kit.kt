package com.steamcontroller.android.ui.widget

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.Type
import com.steamcontroller.android.ui.theme.dp
import com.steamcontroller.android.ui.theme.surface

/**
 * View factories for the type ramp and the standard controls, so every screen is built from the
 * same few parts: caps labels, titles, body text, tabular values, pill buttons, cards.
 */
object Kit {
    // ── type ──────────────────────────────────────────────────────────────────────────

    /** Condensed capitals, tracked 0.08em: section labels, chips, buttons. */
    fun label(ctx: Context, text: CharSequence = "", sizeSp: Float = 11f, color: Int = C.text2): TextView = TextView(ctx).apply {
        this.text = text
        typeface = Type.condensedBold(ctx)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        letterSpacing = Type.LABEL_TRACKING
        isAllCaps = true
        setTextColor(color)
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    /** Page and card headings: semibold, tight tracking. */
    fun title(ctx: Context, text: CharSequence = "", sizeSp: Float = 20f, color: Int = C.text): TextView = TextView(ctx).apply {
        this.text = text
        typeface = Type.semibold(ctx)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        letterSpacing = -0.01f
        setTextColor(color)
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    fun body(ctx: Context, text: CharSequence = "", sizeSp: Float = 14f, color: Int = C.text, medium: Boolean = false): TextView = TextView(ctx).apply {
        this.text = text
        typeface = if (medium) Type.medium(ctx) else Type.regular(ctx)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        setLineSpacing(0f, 1.15f)
        includeFontPadding = false
    }

    /** Numbers: tabular figures so live values do not jitter. */
    fun value(ctx: Context, text: CharSequence = "", sizeSp: Float = 16f, color: Int = C.text): TextView = TextView(ctx).apply {
        this.text = text
        typeface = Type.medium(ctx)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        fontFeatureSettings = Type.TABULAR
        setTextColor(color)
        includeFontPadding = false
        maxLines = 1
    }

    // ── controls ──────────────────────────────────────────────────────────────────────

    enum class Btn { PRIMARY, SECONDARY, GHOST, DANGER }

    /** A pill button: 36dp surface (+3dp focus glow room on every side), caps label, optional icon. */
    fun button(ctx: Context, text: CharSequence, style: Btn = Btn.SECONDARY, icon: Int = 0, onClick: ((View) -> Unit)? = null): TextView {
        val fg = when (style) {
            Btn.PRIMARY -> C.accent
            Btn.DANGER -> C.danger
            else -> C.text
        }
        return label(ctx, text, 12f, fg).apply {
            gravity = Gravity.CENTER
            minHeight = ctx.dp(42)
            setPadding(ctx.dp(19), 0, ctx.dp(19), 0)
            val bg = surface(
                radiusDp = -1f,
                fill = when (style) {
                    Btn.PRIMARY -> C.alpha(C.accent, 0.14f)
                    Btn.DANGER -> C.alpha(C.danger, 0.10f)
                    Btn.GHOST -> 0
                    Btn.SECONDARY -> C.surface
                },
                stroke = when (style) {
                    Btn.PRIMARY -> C.alpha(C.accent, 0.55f)
                    Btn.DANGER -> C.alpha(C.danger, 0.45f)
                    Btn.GHOST -> 0
                    Btn.SECONDARY -> C.line
                },
                focusable = true,
            )
            bg.activeFill = C.alpha(C.accent, 0.14f)
            if (icon != 0) {
                val dr = icon(ctx, icon, 16, fg)
                setCompoundDrawablesRelative(dr, null, null, null)
                compoundDrawablePadding = ctx.dp(8)
            }
            if (onClick != null) setOnClickListener(onClick)
        }
    }

    fun icon(ctx: Context, res: Int, sizeDp: Int, tint: Int): Drawable? =
        ContextCompat.getDrawable(ctx, res)?.mutate()?.apply {
            setTint(tint)
            setBounds(0, 0, ctx.dp(sizeDp), ctx.dp(sizeDp))
        }

    /** A static card (non-focusable); the same 3dp inset as focusable surfaces keeps edges aligned. */
    fun card(ctx: Context, padDp: Int = 20, vertical: Boolean = true): LinearLayout = LinearLayout(ctx).apply {
        orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        surface(radiusDp = 10f)
        val p = ctx.dp(padDp + 3)
        setPadding(p, p, p, p)
    }

    fun column(ctx: Context): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    fun row(ctx: Context, gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        this.gravity = gravity
    }

    fun space(ctx: Context, wDp: Int = 0, hDp: Int = 0): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ctx.dp(wDp), ctx.dp(hDp))
    }

    /** A flexible spacer for horizontal rows. */
    fun grow(ctx: Context): View = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f,
           l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0, ctx: Context? = null): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply {
            if (ctx != null) setMargins(ctx.dp(l), ctx.dp(t), ctx.dp(r), ctx.dp(b)) else setMargins(l, t, r, b)
        }

    fun frame(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.MATCH_PARENT, gravity: Int = Gravity.NO_GRAVITY) =
        FrameLayout.LayoutParams(w, h, gravity)

    /** Section heading inside a page: caps label with breathing room above. */
    fun section(ctx: Context, text: CharSequence, topDp: Int = 20): TextView = label(ctx, text, 11f, C.text3).apply {
        layoutParams = lp(t = topDp, b = 8, l = 3, ctx = ctx)
    }
}
