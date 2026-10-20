package com.steamcontroller.android.ui.tv

import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.steamcontroller.android.R
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.dp

/**
 * The load screen. Android 11 has no SplashScreen API, so the launch theme's windowBackground
 * draws the mark (drawable/splash) the moment the process starts; this overlay takes over at
 * exactly the same place and size, lets the mark settle while the wordmark arrives, and fades
 * away, 580 ms in all. The home underneath is already drawn from its cache by then.
 */
object Splash {
    const val MARK_DP = 112

    fun play(root: FrameLayout) {
        val ctx = root.context
        // The starting window draws drawable/splash at the system's density, before TvScale
        // applies: size the overlay in those pixels so the hand-over does not jump.
        val sd = android.content.res.Resources.getSystem().displayMetrics.density
        fun px(dp: Int) = (dp * sd + 0.5f).toInt()
        val overlay = FrameLayout(ctx).apply {
            setBackgroundColor(C.bg)
            isClickable = true
        }
        val mark = ImageView(ctx).apply { setImageResource(R.drawable.splash_mark) }
        overlay.addView(mark, FrameLayout.LayoutParams(px(MARK_DP), px(MARK_DP), Gravity.CENTER))
        val word = ImageView(ctx).apply {
            setImageResource(R.drawable.wordmark)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        overlay.addView(word, FrameLayout.LayoutParams(px(132), px(22), Gravity.CENTER).apply { topMargin = px(72) })
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        word.alpha = 0f
        word.translationY = 8f * sd
        val ease = DecelerateInterpolator(1.4f)
        mark.animate().scaleX(0.86f).scaleY(0.86f).translationY(-16f * sd).setDuration(320L).setInterpolator(ease).start()
        word.animate().alpha(1f).translationY(0f).setStartDelay(90L).setDuration(240L).setInterpolator(ease).start()
        overlay.animate().alpha(0f).setStartDelay(360L).setDuration(220L).setInterpolator(AccelerateInterpolator())
            .withEndAction { root.removeView(overlay) }.start()
    }
}
