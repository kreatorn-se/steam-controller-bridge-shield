package com.steamcontroller.android.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.steamcontroller.android.R
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.dp
import com.steamcontroller.android.ui.widget.Kit

/**
 * Chrome for the secondary screens (Calibration, HID debug, app picker): the same backdrop and
 * overscan margins as the shell, a back pill, title + subtitle, trailing actions, then content.
 */
object Screen {
    fun build(
        activity: AppCompatActivity,
        title: String,
        subtitle: String?,
        actions: List<View>,
        content: View,
    ): View {
        val ctx = activity
        val safeH = ctx.resources.getDimensionPixelSize(R.dimen.safe_h)
        val safeV = ctx.resources.getDimensionPixelSize(R.dimen.safe_v)
        val root = FrameLayout(ctx).apply { background = Backdrop() }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(safeH, safeV, safeH, safeV)
            clipChildren = false
            clipToPadding = false
        }
        val head = Kit.row(ctx)
        head.addView(Kit.button(ctx, ctx.getString(R.string.screen_back), Kit.Btn.GHOST, R.drawable.ic_back) { activity.onBackPressedDispatcher.onBackPressed() })
        val texts = Kit.column(ctx)
        texts.addView(Kit.title(ctx, title, 21f))
        if (!subtitle.isNullOrEmpty()) texts.addView(Kit.label(ctx, subtitle, 11f, C.text3), Kit.lp(t = 4, ctx = ctx))
        head.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ctx.dp(12) })
        for (a in actions) head.addView(a, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dp(4) })
        col.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(48)).apply { gravity = Gravity.CENTER_VERTICAL })
        col.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = ctx.dp(12) })
        root.addView(col)
        return root
    }
}
