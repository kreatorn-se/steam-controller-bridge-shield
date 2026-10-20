package com.steamcontroller.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.steamcontroller.android.ui.theme.C
import kotlin.math.abs
import kotlin.math.min

/**
 * Square stick preview for Calibration: travel circle, crosshair, the deadzone, and the calibrated
 * position. Callers pass Y already flipped for display (the controller reports up as +Y).
 */
class StickPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    var posX: Float = 0f
    var posY: Float = 0f
    var deadzoneFraction: Float = 0.08f
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dash = DashPathEffect(floatArrayOf(2f * d, 3f * d), 0f)

    fun setPosition(x: Float, y: Float) {
        val nx = x.coerceIn(-1f, 1f)
        val ny = y.coerceIn(-1f, 1f)
        if (abs(nx - posX) < 0.003f && abs(ny - posY) < 0.003f) return
        posX = nx
        posY = ny
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = min(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 10f * d
        p.pathEffect = null
        p.style = Paint.Style.FILL
        p.color = C.surface2
        canvas.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = d
        p.color = 0x0DFFFFFF
        canvas.drawLine(cx - r, cy, cx + r, cy, p)
        canvas.drawLine(cx, cy - r, cx, cy + r, p)
        p.color = C.lineStrong
        canvas.drawCircle(cx, cy, r, p)
        if (deadzoneFraction > 0f) {
            p.style = Paint.Style.FILL
            p.color = 0x0AFFFFFF
            canvas.drawCircle(cx, cy, r * deadzoneFraction, p)
            p.style = Paint.Style.STROKE
            p.pathEffect = dash
            p.color = C.text3
            canvas.drawCircle(cx, cy, r * deadzoneFraction, p)
            p.pathEffect = null
        }
        val x = cx + posX * r
        val y = cy + posY * r
        p.style = Paint.Style.FILL
        p.color = C.alpha(C.accent, 0.25f)
        canvas.drawCircle(x, y, 11f * d, p)
        p.color = C.accent
        canvas.drawCircle(x, y, 6f * d, p)
    }
}
