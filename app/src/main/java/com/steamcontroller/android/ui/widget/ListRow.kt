package com.steamcontroller.android.ui.widget

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.dp
import com.steamcontroller.android.ui.theme.surface

/**
 * A row in a side sheet list: optional badge, title, optional one-line hint, optional trailing
 * caps tag (e.g. CURRENT in accent). Focusable; [current] draws the activated fill.
 */
class ListRow(
    ctx: Context,
    title: CharSequence,
    hint: CharSequence? = null,
    badge: CharSequence? = null,
    tag: CharSequence? = null,
    tagColor: Int = C.accent,
    current: Boolean = false,
    titleColor: Int = 0,
    onClick: (ListRow) -> Unit,
) : LinearLayout(ctx) {
    private val restColor = if (titleColor != 0) titleColor else if (current) C.text else C.textDefault
    val titleView: TextView = Kit.body(ctx, title, 15f, restColor, medium = current).apply { maxLines = 1 }
    val tagView: TextView = Kit.label(ctx, tag ?: "", 10.5f, tagColor)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        surface(radiusDp = 8f, fill = 0, stroke = 0, focusable = true, activeFill = C.surface2, scale = 1.02f)
        isActivated = current
        minimumHeight = ctx.dp(if (hint == null) 46 else 58)
        setPadding(ctx.dp(15), ctx.dp(9), ctx.dp(15), ctx.dp(9))
        if (badge != null) {
            val b = Kit.label(ctx, badge, 10f, C.text).apply {
                gravity = Gravity.CENTER
                minWidth = ctx.dp(30)
                setPadding(ctx.dp(7), ctx.dp(4), ctx.dp(7), ctx.dp(4))
                background = android.graphics.drawable.GradientDrawable().apply { setColor(C.raised); cornerRadius = ctx.dp(10f) }
            }
            addView(b, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.dp(12) })
        }
        val texts = Kit.column(ctx)
        texts.addView(titleView)
        if (hint != null) texts.addView(Kit.body(ctx, hint, 12f, C.text2).apply { maxLines = 2 }, Kit.lp(t = 3, ctx = ctx))
        addView(texts, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (tag != null) addView(tagView, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dp(12) })
        setOnClickListener { onClick(this) }
        setOnFocusChangeListenerChained { has -> titleView.setTextColor(if (has && titleColor == 0) C.text else restColor) }
        layoutParams = Kit.lp(b = 2, ctx = ctx)
    }
}
