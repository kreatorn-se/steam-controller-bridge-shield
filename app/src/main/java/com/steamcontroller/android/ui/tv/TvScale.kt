package com.steamcontroller.android.ui.tv

import android.content.Context
import android.content.res.Configuration
import kotlin.math.roundToInt

/**
 * The whole UI at 85 % on a TV (Kent's 98" screen): the activity's density is lowered, so every
 * dp and sp shrinks together and more fits on screen. 960 x 540 dp becomes about 1129 x 635 dp.
 * The overscan-safe margins in values-television are sized for the scaled density so they
 * still come to 5 % of the screen. Phones and foldables are untouched.
 */
object TvScale {
    const val FACTOR = 0.85f

    fun isTv(c: Context): Boolean =
        (c.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION

    /** For Activity.attachBaseContext. */
    fun wrap(base: Context): Context {
        if (!isTv(base)) return base
        val cfg = Configuration(base.resources.configuration)
        cfg.densityDpi = (cfg.densityDpi * FACTOR).roundToInt()
        cfg.screenWidthDp = (cfg.screenWidthDp / FACTOR).toInt()
        cfg.screenHeightDp = (cfg.screenHeightDp / FACTOR).toInt()
        cfg.smallestScreenWidthDp = (cfg.smallestScreenWidthDp / FACTOR).toInt()
        return base.createConfigurationContext(cfg)
    }
}
