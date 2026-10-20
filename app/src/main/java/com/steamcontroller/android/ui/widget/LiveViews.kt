package com.steamcontroller.android.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.PathParser
import com.steamcontroller.android.ui.diagram.ControllerGeometry
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.Type
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private fun labelPaint(ctx: Context, sizeSp: Float, color: Int = C.text3) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    typeface = Type.condensedBold(ctx)
    textSize = sizeSp * ctx.resources.displayMetrics.scaledDensity
    letterSpacing = 0.08f
    this.color = color
}

private fun numberPaint(ctx: Context, sizeSp: Float, color: Int = C.text) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    typeface = Type.medium(ctx)
    textSize = sizeSp * ctx.resources.displayMetrics.scaledDensity
    fontFeatureSettings = Type.TABULAR
    this.color = color
}

/**
 * Stick visualiser: travel circle, inner and outer deadzone rings, the raw position (hollow) and
 * the curve-processed output (accent), with IN / OUT magnitudes in tabular figures.
 */
class StickView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    var inner = 0.08f
    var outer = 1f
    var exponent = 1f
    private var x = 0f
    private var y = 0f
    private var ox = 0f
    private var oy = 0f
    private var outMag = 0f
    private var inMag = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dash = DashPathEffect(floatArrayOf(2f * d, 3f * d), 0f)
    private val label = labelPaint(context, 10f)
    private val num = numberPaint(context, 13f, C.text2)
    private val numAccent = numberPaint(context, 13f, C.accent).apply { textAlign = Paint.Align.RIGHT }
    private val sb = StringBuilder(8)

    /**
     * Centred raw position and the runtime's shaped output (StickCalibration.applyShaped), both
     * -1..1 with up positive; the display flips Y for drawing, games are unaffected.
     */
    fun set(rx: Float, ry: Float, outX: Float, outY: Float) {
        val nx = rx.coerceIn(-1f, 1f)
        val ny = ry.coerceIn(-1f, 1f)
        if (abs(nx - x) < 0.003f && abs(ny - y) < 0.003f && abs(outX - ox) < 0.003f && abs(outY - oy) < 0.003f) return
        x = nx; y = ny; ox = outX; oy = outY
        inMag = min(1f, hypot(x, y))
        outMag = min(1f, hypot(ox, oy))
        invalidate()
    }

    /** Ring positions (inner / outer deadzone as fractions of travel). */
    fun setShape(inner: Float, outer: Float, exponent: Float) {
        this.inner = inner; this.outer = outer; this.exponent = exponent
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val footer = 22f * d
        val size = min(width.toFloat(), height - footer)
        val cx = width / 2f
        val cy = size / 2f
        val r = size / 2f - 10f * d
        p.pathEffect = null
        p.style = Paint.Style.STROKE; p.strokeWidth = d
        p.color = 0x0DFFFFFF
        c.drawLine(cx - r, cy, cx + r, cy, p)
        c.drawLine(cx, cy - r, cx, cy + r, p)
        p.color = C.lineStrong
        c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.FILL; p.color = 0x09FFFFFF
        c.drawCircle(cx, cy, r * inner, p)
        p.style = Paint.Style.STROKE; p.pathEffect = dash; p.color = C.text3
        if (inner > 0f) c.drawCircle(cx, cy, r * inner, p)
        if (outer < 0.999f) c.drawCircle(cx, cy, r * outer, p)
        p.pathEffect = null
        // raw
        p.color = C.text2; p.strokeWidth = 1.5f * d
        c.drawCircle(cx + x * r, cy - y * r, 5f * d, p)
        // processed output: what the game receives
        if (outMag > 0.001f) {
            val px = cx + ox * r
            val py = cy - oy * r
            p.strokeWidth = d; p.color = C.alpha(C.accent, 0.4f)
            c.drawLine(cx, cy, px, py, p)
            p.style = Paint.Style.FILL
            p.color = C.alpha(C.accent, 0.25f); c.drawCircle(px, py, 9f * d, p)
            p.color = C.accent; c.drawCircle(px, py, 5f * d, p)
        } else {
            p.style = Paint.Style.FILL; p.color = C.accent; c.drawCircle(cx, cy, 4f * d, p)
        }
        val by = height - 6f * d
        c.drawText("IN", 0f, by, label)
        sb.setLength(0); sb.append((inMag * 100).roundToInt()).append('%')
        c.drawText(sb, 0, sb.length, label.measureText("IN") + 6f * d, by, num)
        sb.setLength(0); sb.append((outMag * 100).roundToInt()).append('%')
        c.drawText(sb, 0, sb.length, width.toFloat(), by, numAccent)
        val ow = numAccent.measureText(sb, 0, sb.length)
        c.drawText("OUT", width - ow - 6f * d - label.measureText("OUT"), by, label)
    }
}

/**
 * Input → output graph for a response curve: shaded deadzone bands, a dashed linear reference,
 * the curve with a soft fill, and a live dot riding the curve at the current input.
 */
class CurveGraphView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private var fn: (Float) -> Float = { it }
    private var bandLo = 0f
    private var bandHi = 1f
    private var live = -1f
    private val curve = Path()
    private val area = Path()
    private val plot = RectF()
    private var fillShader: Shader? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dash = DashPathEffect(floatArrayOf(3f * d, 3f * d), 0f)
    private val label = labelPaint(context, 10f)
    private val num = numberPaint(context, 13f, C.text2).apply { textAlign = Paint.Align.RIGHT }
    private val sb = StringBuilder(16)
    // Labels are drawn in capitals; converted once here, not per frame.
    var xLabel = "INPUT"
        set(v) { field = v.uppercase() }
    var yLabel = "OUTPUT"
        set(v) { field = v.uppercase() }
    var bandLabel: String? = "DEADZONE"
        set(v) { field = v?.uppercase() }

    /** [f] maps 0..1 → 0..1; [lo]/[hi] are the shaded dead bands at either end. */
    fun setCurve(lo: Float, hi: Float, f: (Float) -> Float) {
        fn = f; bandLo = lo; bandHi = hi
        rebuild()
        invalidate()
    }

    private var liveOut = -1f

    /** Current input magnitude 0..1 riding the curve, or negative to hide the live dot. */
    fun setLive(v: Float) = setLive(v, -1f)

    /** Live input and the output the runtime actually produced for it (negative: use the curve). */
    fun setLive(v: Float, out: Float) {
        val n = if (v < 0f) -1f else v.coerceIn(0f, 1f)
        val o = if (out < 0f) -1f else out.coerceIn(0f, 1f)
        if (abs(n - live) < 0.004f && abs(o - liveOut) < 0.004f) return
        live = n
        liveOut = o
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        plot.set(38f * d, 10f * d, w - 14f * d, h - 30f * d)
        fillShader = LinearGradient(0f, plot.top, 0f, plot.bottom, C.alpha(C.accent, 0.16f), C.alpha(C.accent, 0f), Shader.TileMode.CLAMP)
        rebuild()
    }

    private fun rebuild() {
        if (plot.isEmpty) return
        curve.rewind(); area.rewind()
        val n = 96
        for (i in 0..n) {
            val t = i / n.toFloat()
            val px = plot.left + plot.width() * t
            val py = plot.bottom - plot.height() * fn(t).coerceIn(0f, 1f)
            if (i == 0) { curve.moveTo(px, py); area.moveTo(px, plot.bottom); area.lineTo(px, py) } else { curve.lineTo(px, py); area.lineTo(px, py) }
        }
        area.lineTo(plot.right, plot.bottom)
        area.close()
    }

    override fun onDraw(c: Canvas) {
        val r = plot
        p.style = Paint.Style.FILL; p.color = 0x06FFFFFF; p.pathEffect = null
        if (bandLo > 0f) c.drawRect(r.left, r.top, r.left + r.width() * bandLo, r.bottom, p)
        if (bandHi < 1f) c.drawRect(r.left + r.width() * bandHi, r.top, r.right, r.bottom, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = d; p.color = 0x0DFFFFFF
        for (i in 0..4) {
            val gy = r.bottom - r.height() * i / 4f
            val gx = r.left + r.width() * i / 4f
            c.drawLine(r.left, gy, r.right, gy, p)
            c.drawLine(gx, r.top, gx, r.bottom, p)
        }
        p.pathEffect = dash; p.color = C.text3
        c.drawLine(r.left, r.bottom, r.right, r.top, p)
        p.pathEffect = null
        p.style = Paint.Style.FILL; p.shader = fillShader
        c.drawPath(area, p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f * d; p.color = C.accent; p.strokeJoin = Paint.Join.ROUND
        c.drawPath(curve, p)
        // axes labels
        label.textAlign = Paint.Align.LEFT
        c.drawText("0", r.left - 4f * d, r.bottom + 18f * d, label)
        label.textAlign = Paint.Align.RIGHT
        c.drawText(xLabel, r.right, r.bottom + 18f * d, label)
        c.drawText("100", r.left - 8f * d, r.top + 8f * d, label)
        c.save(); c.rotate(-90f, r.left - 24f * d, r.centerY())
        label.textAlign = Paint.Align.CENTER
        c.drawText(yLabel, r.left - 24f * d, r.centerY() + 4f * d, label)
        c.restore()
        if (bandLo > 0.06f && bandLabel != null) {
            label.textAlign = Paint.Align.LEFT
            c.save(); c.rotate(-90f, r.left + 12f * d, r.bottom - 6f * d)
            c.drawText(bandLabel!!, r.left + 12f * d, r.bottom - 6f * d, label)
            c.restore()
        }
        if (live >= 0f) {
            val out = if (liveOut >= 0f) liveOut else fn(live).coerceIn(0f, 1f)
            val lx = r.left + r.width() * live
            val ly = r.bottom - r.height() * out
            p.strokeWidth = d; p.color = C.alpha(C.accent, 0.35f); p.pathEffect = dash
            c.drawLine(lx, r.bottom, lx, ly, p)
            c.drawLine(r.left, ly, lx, ly, p)
            p.pathEffect = null; p.style = Paint.Style.FILL
            p.color = C.alpha(C.accent, 0.25f); c.drawCircle(lx, ly, 9f * d, p)
            p.color = C.accent; c.drawCircle(lx, ly, 5f * d, p)
            sb.setLength(0)
            sb.append((live * 100).roundToInt()).append(" → ").append((out * 100).roundToInt()).append(" %")
            c.drawText(sb, 0, sb.length, r.right, r.top + 14f * d, num)
        }
    }
}

/**
 * Orientation from the IMU quaternion: the controller outline in perspective, relative to the
 * pose captured by [recenter], with pitch / roll / yaw read-outs. Axes follow GyroAim: X right
 * (pitch), Y forward through the triggers (roll), Z out of the face (yaw).
 */
class GyroView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val n = 120
    private val mx = FloatArray(n)
    private val my = FloatArray(n)
    private val stickX = floatArrayOf(-0.30f, 0.30f)
    private val stickY = floatArrayOf(0.30f, 0.30f)
    private val outline = Path()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = labelPaint(context, 10f)
    private val num = numberPaint(context, 16f).apply { textAlign = Paint.Align.RIGHT }
    private val hint = labelPaint(context, 10f, C.text3)
    private val sb = StringBuilder(10)
    private var q0w = 1f; private var q0x = 0f; private var q0y = 0f; private var q0z = 0f
    private var w = 1f; private var x = 0f; private var y = 0f; private var z = 0f
    private var haveData = false
    private var needCenter = true
    var showReadout = true
    var recenterHint: String? = null

    init {
        val body = PathParser.createPathFromPathData(ControllerGeometry.front.body)
        val pm = PathMeasure(body, true)
        val len = pm.length
        val pos = FloatArray(2)
        for (i in 0 until n) {
            pm.getPosTan(len * i / n, pos, null)
            // body spans x 0..504, y 0..364 in the source; centre it and scale to ±1 wide.
            mx[i] = (pos[0] - 252f) / 252f
            my[i] = -(pos[1] - 182f) / 252f
        }
        for (k in 0..1) {
            val sx = if (k == 0) 177f else 327f
            stickX[k] = (sx - 252f) / 252f
            stickY[k] = -(105f - 182f) / 252f
        }
    }

    fun recenter() { needCenter = true }

    fun setQuaternion(qw: Short, qx: Short, qy: Short, qz: Short) {
        val a = qw / 32767f; val b = qx / 32767f; val cc = qy / 32767f; val dd = qz / 32767f
        val len = sqrt(a * a + b * b + cc * cc + dd * dd)
        if (len < 0.5f) { if (haveData) { haveData = false; invalidate() }; return }
        val nw = a / len; val nx = b / len; val ny = cc / len; val nz = dd / len
        if (needCenter || !haveData) { q0w = nw; q0x = nx; q0y = ny; q0z = nz; needCenter = false }
        // relative = conj(q0) * q
        val rw = q0w * nw + q0x * nx + q0y * ny + q0z * nz
        val rx = q0w * nx - q0x * nw - q0y * nz + q0z * ny
        val ry = q0w * ny + q0x * nz - q0y * nw - q0z * nx
        val rz = q0w * nz - q0x * ny + q0y * nx - q0z * nw
        if (haveData && abs(rw - w) + abs(rx - x) + abs(ry - y) + abs(rz - z) < 0.0015f) return
        w = rw; x = rx; y = ry; z = rz
        haveData = true
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val readW = if (showReadout) min(150f * d, width * 0.4f) else 0f
        val cw = width - readW
        val cx = cw / 2f
        val cy = height / 2f
        val size = min(cw, height.toFloat()) * 0.42f
        p.style = Paint.Style.STROKE; p.strokeWidth = d; p.pathEffect = null
        p.color = 0x0DFFFFFF
        c.drawLine(cx - size * 1.25f, cy, cx + size * 1.25f, cy, p)
        c.drawCircle(cx, cy, size * 1.15f, p)
        if (!haveData) {
            hint.textAlign = Paint.Align.CENTER
            c.drawText("NO MOTION DATA", cx, cy + 4f * d, hint)
        } else {
            // rotation matrix from the relative quaternion
            val xx = x * x; val yy = y * y; val zz = z * z
            val xy = x * y; val xz = x * z; val yz = y * z; val wx = w * x; val wy = w * y; val wz = w * z
            val m00 = 1 - 2 * (yy + zz); val m01 = 2 * (xy - wz); val m02 = 2 * (xz + wy)
            val m10 = 2 * (xy + wz); val m11 = 1 - 2 * (xx + zz); val m12 = 2 * (yz - wx)
            val m20 = 2 * (xz - wy); val m21 = 2 * (yz + wx); val m22 = 1 - 2 * (xx + yy)
            val f = 3.2f
            outline.rewind()
            for (i in 0 until n) {
                val px = m00 * mx[i] + m01 * my[i]
                val py = m10 * mx[i] + m11 * my[i]
                val pz = m20 * mx[i] + m21 * my[i]
                val s = f / (f - pz)
                val sx = cx + px * s * size
                val sy = cy - py * s * size
                if (i == 0) outline.moveTo(sx, sy) else outline.lineTo(sx, sy)
            }
            outline.close()
            // Facing away (seen from behind) draws dimmer.
            val facing = m22 >= 0f
            p.style = Paint.Style.FILL; p.color = if (facing) C.surface2 else C.surface
            c.drawPath(outline, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = if (facing) C.accent else C.alpha(C.accent, 0.5f)
            p.strokeJoin = Paint.Join.ROUND
            c.drawPath(outline, p)
            p.style = Paint.Style.FILL; p.color = C.alpha(C.accent, if (facing) 0.6f else 0.25f)
            for (k in 0..1) {
                val px = m00 * stickX[k] + m01 * stickY[k]
                val py = m10 * stickX[k] + m11 * stickY[k]
                val pz = m20 * stickX[k] + m21 * stickY[k]
                val s = f / (f - pz)
                c.drawCircle(cx + px * s * size, cy - py * s * size, 6f * d * s, p)
            }
        }
        if (!showReadout) return
        val rx = width.toFloat()
        val rows = 3
        val rowH = 30f * d
        val top = cy - rowH * (rows - 1) / 2f
        label.textAlign = Paint.Align.LEFT
        for (k in 0 until rows) {
            val ry = top + k * rowH + 5f * d
            c.drawText(AXES[k], cw + 8f * d, ry, label)
            val deg = if (!haveData) Float.NaN else when (k) {
                0 -> Math.toDegrees(atan2(2.0 * (w * x + y * z), 1.0 - 2.0 * (x * x + y * y))).toFloat()
                1 -> Math.toDegrees(asin((2.0 * (w * y - z * x)).coerceIn(-1.0, 1.0))).toFloat()
                else -> Math.toDegrees(atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (y * y + z * z))).toFloat()
            }
            sb.setLength(0)
            if (deg.isNaN()) sb.append('—') else {
                val t = (deg * 10).roundToInt()
                sb.append(if (t < 0) '−' else '+')
                val a = abs(t)
                sb.append(a / 10).append('.').append(a % 10).append('°')
            }
            c.drawText(sb, 0, sb.length, rx, ry + 2f * d, num)
        }
        recenterHint?.let {
            hint.textAlign = Paint.Align.RIGHT
            c.drawText(it, rx, height - 4f * d, hint)
        }
    }

    private companion object {
        val AXES = arrayOf("PITCH", "ROLL", "YAW")
    }
}

/** Analog trigger: caps label, a rounded bar and the raw value in tabular figures. */
class TriggerBarView(context: Context, private val name: String) : View(context) {
    private val d = resources.displayMetrics.density
    private var v = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = labelPaint(context, 11f, C.text2)
    private val num = numberPaint(context, 14f).apply { textAlign = Paint.Align.RIGHT }
    private val sb = StringBuilder(6)
    private val r = RectF()

    fun set(raw: Int) {
        val n = raw.coerceIn(0, 32767)
        if (abs(n - v) < 64) return
        v = n
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        c.drawText(name, 0f, cy + 4f * d, label)
        val numW = num.measureText("00000")
        val bx = 28f * d
        val bw = width - bx - numW - 12f * d
        r.set(bx, cy - 4f * d, bx + bw, cy + 4f * d)
        p.color = C.raised
        c.drawRoundRect(r, 4f * d, 4f * d, p)
        val f = v / 32767f
        if (f > 0f) {
            r.right = bx + (bw * f).coerceAtLeast(8f * d)
            p.color = C.accent
            c.drawRoundRect(r, 4f * d, 4f * d, p)
        }
        sb.setLength(0); sb.append(v)
        c.drawText(sb, 0, sb.length, width.toFloat(), cy + 5f * d, num)
    }
}

/** "HOLD B TO LEAVE": a small progress ring that fills while B is held. */
class HoldRingView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private var progress = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val oval = RectF()

    fun set(v: Float) {
        val n = v.coerceIn(0f, 1f)
        if (abs(n - progress) < 0.01f && !(n == 0f && progress != 0f)) return
        progress = n
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        val inset = 2f * d
        oval.set((width - s) / 2f + inset, (height - s) / 2f + inset, (width + s) / 2f - inset, (height + s) / 2f - inset)
        p.strokeWidth = 2.5f * d
        p.color = C.raised
        c.drawOval(oval, p)
        if (progress > 0f) {
            p.color = C.accent
            c.drawArc(oval, -90f, 360f * progress, false, p)
        }
    }
}

/** A small status dot with an optional soft halo (live / warning / error). */
class DotView(context: Context, sizeDp: Float = 8f) : View(context) {
    private val d = resources.displayMetrics.density
    private val size = sizeDp
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var color: Int = C.text3
        set(v) { if (field != v) { field = v; invalidate() } }
    var halo: Boolean = false
        set(v) { if (field != v) { field = v; invalidate() } }

    override fun onMeasure(w: Int, h: Int) {
        val px = ((size + 6f) * d).toInt()
        setMeasuredDimension(px, px)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        if (halo) { p.color = C.alpha(color, 0.25f); c.drawCircle(cx, cy, size * d / 2f + 3f * d, p) }
        p.color = color
        c.drawCircle(cx, cy, size * d / 2f, p)
    }
}

/** Fade + slide helper for page and sheet transitions. */
object Anim {
    private val ease = DecelerateInterpolator()
    fun fadeIn(v: View, dyDp: Float = 6f) {
        v.alpha = 0f
        v.translationY = dyDp * v.resources.displayMetrics.density
        v.animate().alpha(1f).translationY(0f).setDuration(180L).setInterpolator(ease).start()
    }

    fun pulse(update: (Float) -> Unit, durationMs: Long = 220L): ValueAnimator =
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = ease
            addUpdateListener { update(it.animatedValue as Float) }
            start()
        }
}
