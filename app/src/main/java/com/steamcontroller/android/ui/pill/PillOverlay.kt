package com.steamcontroller.android.ui.pill

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.steamcontroller.android.R

/**
 * The overlay window behind [Pill]. Main thread only.
 *
 * One full-width, transparent, non-focusable, non-touchable window, just tall enough for the pill
 * and its bounce, added while a pill is visible and removed after it has shrunk away. The pill is
 * drawn by [PillView] on a canvas, so morphing to a new message animates the width and crossfades
 * the content without a single layout pass per frame.
 */
internal class PillOverlay(private val app: Context) {
    private val wm = app.getSystemService(WindowManager::class.java)
    private val dm = app.resources.displayMetrics
    private val handler = Handler(Looper.getMainLooper())

    private var view: PillView? = null
    private var attached = false
    private var enterAnim: Animator? = null
    private var exitAnim: Animator? = null
    private var bounceAnim: Animator? = null
    private val hide = Runnable { exit() }

    private fun dp(v: Float) = v * dm.density

    /** False when the window could not be added (the caller falls back to a toast). */
    fun show(kind: Pill.Kind, label: String): Boolean {
        val icon = ContextCompat.getDrawable(app, iconFor(kind))?.mutate() ?: return false
        val v = view ?: PillView(app).also { view = it }
        if (!attached) {
            v.setContent(icon, label)
            if (!attach(v)) return false
            enter(v)
        } else {
            exitAnim?.let {
                exitAnim = null
                it.cancel()
            }
            v.morphTo(icon, label)
            if (enterAnim?.isRunning != true) bounce(v)
        }
        v.setSpinning(kind == Pill.Kind.RECONNECTING)
        handler.removeCallbacks(hide)
        handler.postDelayed(hide, holdMs(kind))
        return true
    }

    private fun holdMs(kind: Pill.Kind): Long =
        when (kind) {
            Pill.Kind.FAILED -> 2_200L
            // Replaced by the outcome (connected / failed) as soon as it is known.
            Pill.Kind.RECONNECTING -> 8_000L
            else -> 1_600L
        }

    private fun attach(v: PillView): Boolean {
        val lp =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                v.windowHeightPx(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                // ~32dp from the top edge to the pill, inside the TV's overscan-safe area.
                y = (dp(TOP_DP) - v.headroomPx()).toInt().coerceAtLeast(0)
                title = "SteamControllerBridgePill"
                windowAnimations = 0
            }
        return try {
            wm.addView(v, lp)
            attached = true
            true
        } catch (t: Throwable) {
            Log.w(TAG, "addView failed: ${t.message}")
            false
        }
    }

    private fun detach() {
        handler.removeCallbacks(hide)
        val v = view ?: return
        v.setSpinning(false)
        v.stopMorph()
        if (attached) {
            try {
                wm.removeViewImmediate(v)
            } catch (t: Throwable) {
                Log.w(TAG, "removeView failed: ${t.message}")
            }
        }
        attached = false
    }

    /** The "bubbly chunk": overshoot to 1.12, settle through 0.96 to 1, dropping in from above. */
    private fun enter(v: PillView) {
        enterAnim?.cancel()
        v.alpha = 0f
        v.scaleX = 0.55f
        v.scaleY = 0.55f
        v.translationY = -dp(10f)
        val scale = { prop: android.util.Property<View, Float> ->
            PropertyValuesHolder.ofKeyframe(
                prop,
                Keyframe.ofFloat(0f, 0.55f),
                Keyframe.ofFloat(0.42f, 1.12f).apply { interpolator = DecelerateInterpolator(1.4f) },
                Keyframe.ofFloat(0.72f, 0.96f).apply { interpolator = AccelerateDecelerateInterpolator() },
                Keyframe.ofFloat(1f, 1f).apply { interpolator = DecelerateInterpolator() },
            )
        }
        val pop = ObjectAnimator.ofPropertyValuesHolder(v, scale(View.SCALE_X), scale(View.SCALE_Y)).setDuration(380)
        val fade = ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 1f).setDuration(120)
        val drop =
            ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, -dp(10f), 0f).setDuration(300).apply {
                interpolator = DecelerateInterpolator(1.6f)
            }
        enterAnim =
            AnimatorSet().apply {
                playTogether(pop, fade, drop)
                start()
            }
    }

    /** A new message while one is showing: a small 1.0 → 1.06 → 1.0 nudge (and back to full alpha). */
    private fun bounce(v: PillView) {
        bounceAnim?.cancel()
        val from = v.scaleX
        val kf = { prop: android.util.Property<View, Float> ->
            PropertyValuesHolder.ofKeyframe(
                prop,
                Keyframe.ofFloat(0f, from),
                Keyframe.ofFloat(0.4f, 1.06f).apply { interpolator = DecelerateInterpolator() },
                Keyframe.ofFloat(1f, 1f).apply { interpolator = AccelerateDecelerateInterpolator() },
            )
        }
        bounceAnim =
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofPropertyValuesHolder(v, kf(View.SCALE_X), kf(View.SCALE_Y)).setDuration(240),
                    ObjectAnimator.ofFloat(v, View.ALPHA, v.alpha, 1f).setDuration(100),
                    ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, v.translationY, 0f).setDuration(160),
                )
                start()
            }
    }

    /** Off the screen now, no exit animation (before a screencap). */
    fun hideNow() {
        enterAnim?.cancel()
        bounceAnim?.cancel()
        exitAnim?.let {
            exitAnim = null
            it.cancel()
        }
        detach()
    }

    /** Quick shrink away, ease-in. */
    private fun exit() {
        val v = view ?: return
        if (!attached) return
        enterAnim?.cancel()
        bounceAnim?.cancel()
        val set =
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(v, View.SCALE_X, v.scaleX, 0.8f),
                    ObjectAnimator.ofFloat(v, View.SCALE_Y, v.scaleY, 0.8f),
                    ObjectAnimator.ofFloat(v, View.ALPHA, v.alpha, 0f),
                )
                duration = 150
                interpolator = AccelerateInterpolator(1.5f)
            }
        set.addListener(
            object : AnimatorListenerAdapter() {
                private var canceled = false

                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!canceled && exitAnim === set) {
                        exitAnim = null
                        detach()
                    }
                }
            },
        )
        exitAnim = set
        set.start()
    }

    private fun iconFor(kind: Pill.Kind): Int =
        when (kind) {
            Pill.Kind.CAPTURED -> R.drawable.ic_pill_camera
            Pill.Kind.SAVED -> R.drawable.ic_pill_cloud_done
            Pill.Kind.FAILED -> R.drawable.ic_pill_alert
            Pill.Kind.CONNECTED -> R.drawable.ic_pill_controller
            Pill.Kind.DISCONNECTED -> R.drawable.ic_pill_controller_off
            Pill.Kind.RECONNECTING -> R.drawable.ic_pill_sync
            Pill.Kind.PROFILE -> R.drawable.ic_pill_layers
            Pill.Kind.BATTERY_LOW -> R.drawable.ic_pill_battery_low
            Pill.Kind.INFO -> R.drawable.ic_pill_info
        }

    private companion object {
        const val TAG = "PillOverlay"
        const val TOP_DP = 32f
    }
}

/**
 * Draws the pill: black at 92 % alpha, fully rounded, wrapped tightly around an 18dp icon, an 8dp
 * gap and the label in bold condensed capitals. The view spans the window's full width and keeps
 * the pill centred, so scale animations pivot on the pill's centre.
 */
@SuppressLint("ViewConstructor")
internal class PillView(context: Context) : View(context) {
    private val dm = context.resources.displayMetrics

    private fun dp(v: Float) = v * dm.density

    private val pillH = dp(38f)
    private val padH = dp(14f)
    private val iconSize = dp(18f)
    private val gap = dp(8f)

    private val bgPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xEB000000.toInt() // #000 at ~92 %
            style = Paint.Style.FILL
        }
    private val textPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, dm)
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            letterSpacing = 0.08f
        }

    // Optical centring on cap height: labels are always capitals, so ascent/descent would sit low.
    private val capHalf: Float =
        Rect().let {
            textPaint.getTextBounds("H", 0, 1, it)
            it.height() / 2f
        }

    private class Slot(val icon: Drawable, val text: String, val textW: Float, val width: Float)

    private var current: Slot? = null
    private var previous: Slot? = null
    private var curW = 0f
    private var morphT = 1f
    private var morphAnim: ValueAnimator? = null
    private var spinAngle = 0f
    private var spinAnim: ValueAnimator? = null
    private val rect = RectF()

    fun headroomPx(): Float = dp(14f)

    fun windowHeightPx(): Int = (pillH + 2 * headroomPx()).toInt()

    private fun slot(icon: Drawable, text: String): Slot {
        val tw = textPaint.measureText(text)
        return Slot(icon, text, tw, padH * 2 + iconSize + gap + tw)
    }

    fun setContent(icon: Drawable, text: String) {
        stopMorph()
        val s = slot(icon, text)
        current = s
        previous = null
        curW = s.width
        morphT = 1f
        invalidate()
    }

    /** Crossfade icon and label while the width follows. */
    fun morphTo(icon: Drawable, text: String) {
        val old = current
        if (old == null) {
            setContent(icon, text)
            return
        }
        // Start from wherever the width is now, even mid-way through an earlier morph.
        val fromW = curW
        cancelMorph()
        val next = slot(icon, text)
        previous = old
        current = next
        morphT = 0f
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 220
        anim.interpolator = DecelerateInterpolator(1.5f)
        anim.addUpdateListener {
            morphT = it.animatedValue as Float
            curW = fromW + (next.width - fromW) * morphT
            invalidate()
        }
        anim.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (morphAnim !== anim) return // cancelled by a newer morph
                    morphAnim = null
                    previous = null
                    morphT = 1f
                    curW = next.width
                    invalidate()
                }
            },
        )
        morphAnim = anim
        anim.start()
    }

    private fun cancelMorph() {
        morphAnim?.let {
            morphAnim = null
            it.cancel()
        }
    }

    fun stopMorph() {
        cancelMorph()
        previous = null
        morphT = 1f
        current?.let { curW = it.width }
    }

    fun setSpinning(on: Boolean) {
        if (on == (spinAnim != null)) return
        if (on) {
            spinAnim =
                ValueAnimator.ofFloat(0f, 360f).apply {
                    duration = 900
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        spinAngle = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
        } else {
            spinAnim?.cancel()
            spinAnim = null
            spinAngle = 0f
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        setSpinning(false)
    }

    override fun onDraw(canvas: Canvas) {
        val cur = current ?: return
        val cx = width / 2f
        val cy = height / 2f
        rect.set(cx - curW / 2f, cy - pillH / 2f, cx + curW / 2f, cy + pillH / 2f)
        canvas.drawRoundRect(rect, pillH / 2f, pillH / 2f, bgPaint)

        canvas.save()
        canvas.clipRect(rect)
        val prev = previous
        if (prev != null) {
            // Old content gone by 60 % of the morph, new content in from 30 %.
            drawSlot(canvas, prev, (1f - morphT / 0.6f).coerceIn(0f, 1f), cx, cy, spin = false)
            drawSlot(canvas, cur, ((morphT - 0.3f) / 0.7f).coerceIn(0f, 1f), cx, cy, spin = true)
        } else {
            drawSlot(canvas, cur, 1f, cx, cy, spin = true)
        }
        canvas.restore()
    }

    private fun drawSlot(
        canvas: Canvas,
        s: Slot,
        alpha: Float,
        cx: Float,
        cy: Float,
        spin: Boolean,
    ) {
        if (alpha <= 0f) return
        val a = (alpha * 255).toInt()
        val left = cx - (s.width - 2 * padH) / 2f
        val top = cy - iconSize / 2f
        s.icon.setBounds(left.toInt(), top.toInt(), (left + iconSize).toInt(), (top + iconSize).toInt())
        s.icon.alpha = a
        if (spin && spinAngle != 0f) {
            canvas.save()
            canvas.rotate(spinAngle, left + iconSize / 2f, cy)
            s.icon.draw(canvas)
            canvas.restore()
        } else {
            s.icon.draw(canvas)
        }
        textPaint.alpha = a
        canvas.drawText(s.text, left + iconSize + gap, cy + capHalf, textPaint)
    }
}
