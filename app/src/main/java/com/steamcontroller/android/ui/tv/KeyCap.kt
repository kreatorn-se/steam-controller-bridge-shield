package com.steamcontroller.android.ui.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.Type

/** A gamepad key as a small keycap: "A", "X", "LB". Drawn, so no font needs the circled glyphs. */
class KeyCap(ctx: Context, private val key: String, private val color: Int) : Drawable() {
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Type.condensedBold(ctx)
        textSize = 11f * ctx.resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val r = RectF()
    private val w = ((if (key.length > 1) 26 else 18) * d).toInt()
    private val h = (18 * d).toInt()

    init { setBounds(0, 0, w, h) }

    override fun getIntrinsicWidth() = w
    override fun getIntrinsicHeight() = h

    override fun draw(c: Canvas) {
        r.set(bounds.left + d / 2, bounds.top + d / 2, bounds.right - d / 2, bounds.bottom - d / 2)
        p.style = Paint.Style.STROKE
        p.strokeWidth = d
        p.color = C.alpha(color, 0.7f)
        c.drawRoundRect(r, 5 * d, 5 * d, p)
        t.color = color
        c.drawText(key, r.centerX(), r.centerY() - (t.descent() + t.ascent()) / 2, t)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
