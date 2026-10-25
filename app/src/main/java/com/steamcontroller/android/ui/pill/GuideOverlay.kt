package com.steamcontroller.android.ui.pill

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.dp
import com.steamcontroller.android.ui.widget.Kit
import kotlin.math.min

/**
 * A short card in the pill's family (black at ~92 %, top centre, same drop-in), for a few lines
 * that do not fit in a pill: the Enshrouded next steps after a session. Callable from any thread.
 *
 * Unlike the pill the window takes key focus, so any button on the remote or the controller
 * closes it (the press is used up, volume keys pass through); otherwise it goes by itself after
 * [SHOW_MS]. Callers only show it when no game is running. Without the overlay permission the
 * [fallback] text goes to the pill (which falls back to a toast).
 */
object GuideOverlay {
    private const val TAG = "GuideOverlay"
    private const val SHOW_MS = 12_000L
    private const val TOP_DP = 32
    private const val MAX_W_DP = 560

    private val main by lazy { Handler(Looper.getMainLooper()) }

    // Main thread only.
    private var card: Card? = null
    private var enterAnim: Animator? = null
    private var exitAnim: Animator? = null
    private val timeout = Runnable { dismiss() }

    fun show(context: Context, title: String, sub: String?, lines: List<String>, hint: String, fallback: String) {
        val app = context.applicationContext
        main.post {
            if (!showOnMain(app, title, sub, lines, hint)) Pill.show(app, Pill.Kind.INFO, fallback)
        }
    }

    /** Off the screen now, without animation (before a screencap, see [Pill.holdForCapture]). Main thread. */
    fun hideNow() {
        main.removeCallbacks(timeout)
        enterAnim?.cancel()
        enterAnim = null
        exitAnim?.cancel()
        exitAnim = null
        detach()
    }

    private fun showOnMain(app: Context, title: String, sub: String?, lines: List<String>, hint: String): Boolean {
        if (lines.isEmpty()) return true
        hideNow()
        if (!Settings.canDrawOverlays(app)) return false
        val c = Card(app) { dismiss() }
        c.fill(title, sub, lines, hint)
        val dm = app.resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            min(app.dp(MAX_W_DP), dm.widthPixels - app.dp(96)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Focusable (for the dismissing key press), but touches outside go to the app below.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = app.dp(TOP_DP)
            setTitle("SteamControllerBridgeGuide")
            windowAnimations = 0
        }
        return try {
            app.getSystemService(WindowManager::class.java).addView(c, lp)
            card = c
            c.requestFocus()
            enter(c)
            main.postDelayed(timeout, SHOW_MS)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "addView failed: ${t.message}")
            false
        }
    }

    private fun enter(v: View) {
        v.alpha = 0f
        v.scaleX = 0.9f
        v.scaleY = 0.9f
        v.translationY = -v.context.dp(10f)
        enterAnim = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 1f).setDuration(140),
                ObjectAnimator.ofFloat(v, View.SCALE_X, 0.9f, 1f).setDuration(320),
                ObjectAnimator.ofFloat(v, View.SCALE_Y, 0.9f, 1f).setDuration(320),
                ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, v.translationY, 0f).setDuration(300),
            )
            interpolator = OvershootInterpolator(1.4f)
            start()
        }
    }

    /** Quick shrink away, then the window goes. */
    private fun dismiss() {
        main.removeCallbacks(timeout)
        val v = card ?: return
        if (exitAnim != null) return
        enterAnim?.cancel()
        enterAnim = null
        val set = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(v, View.SCALE_X, v.scaleX, 0.94f),
                ObjectAnimator.ofFloat(v, View.SCALE_Y, v.scaleY, 0.94f),
                ObjectAnimator.ofFloat(v, View.ALPHA, v.alpha, 0f),
            )
            duration = 150
            interpolator = AccelerateInterpolator(1.5f)
        }
        set.addListener(object : AnimatorListenerAdapter() {
            private var canceled = false
            override fun onAnimationCancel(animation: Animator) { canceled = true }
            override fun onAnimationEnd(animation: Animator) {
                if (canceled || exitAnim !== set) return
                exitAnim = null
                detach()
            }
        })
        exitAnim = set
        set.start()
    }

    private fun detach() {
        val v = card ?: return
        card = null
        try {
            v.context.getSystemService(WindowManager::class.java).removeViewImmediate(v)
        } catch (t: Throwable) {
            Log.w(TAG, "removeView failed: ${t.message}")
        }
    }

    /** The card itself: caps title, a dim sub line, numbered lines, a hint. Any key closes it. */
    @SuppressLint("ViewConstructor")
    private class Card(ctx: Context, private val onKey: () -> Unit) : LinearLayout(ctx) {
        init {
            orientation = VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            background = GradientDrawable().apply {
                setColor(0xEB000000.toInt()) // the pill's #000 at ~92 %
                cornerRadius = ctx.dp(18f)
            }
            val ph = ctx.dp(24)
            val pv = ctx.dp(18)
            setPadding(ph, pv, ph, pv)
        }

        fun fill(title: String, sub: String?, lines: List<String>, hint: String) {
            val ctx = context
            addView(Kit.label(ctx, title, 12f, C.accent))
            if (!sub.isNullOrEmpty()) addView(Kit.body(ctx, sub, 12.5f, C.text2), Kit.lp(t = 6, ctx = ctx))
            lines.forEachIndexed { i, line ->
                val row = Kit.row(ctx, Gravity.TOP)
                row.addView(Kit.label(ctx, "${i + 1}", 15f, C.accent).apply { minWidth = ctx.dp(20) })
                row.addView(Kit.body(ctx, line, 15f, C.text).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(row, Kit.lp(t = if (i == 0) 12 else 8, ctx = ctx))
            }
            addView(Kit.label(ctx, hint, 10.5f, C.text3), Kit.lp(t = 14, ctx = ctx))
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> return false
            }
            // Close on release, so the release does not land in the app below.
            if (event.action == KeyEvent.ACTION_UP) onKey()
            return true
        }
    }
}
