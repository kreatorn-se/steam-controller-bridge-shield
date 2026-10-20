package com.steamcontroller.android.ui.theme

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.steamcontroller.android.R

/**
 * Design tokens for the whole app (shared with gamehub.kreatorn.se): near-black + Steam blue.
 * res/values/colors.xml carries the same values for the theme and XML; custom views read these
 * constants so nothing resolves resources while drawing.
 */
object C {
    const val bg = 0xFF0B0C0E.toInt()
    const val surface = 0xFF14161A.toInt()
    const val surface2 = 0xFF1B1E23.toInt()
    const val raised = 0xFF22262C.toInt()
    const val line = 0xFF23262C.toInt()
    const val lineStrong = 0xFF2E3239.toInt()
    const val text = 0xFFF2F4F7.toInt()
    const val text2 = 0xFF8B929C.toInt()
    const val text3 = 0xFF5B626C.toInt()
    const val accent = 0xFF1A9FFF.toInt()
    const val onAccent = 0xFF04121F.toInt()
    const val success = 0xFF3DDC97.toInt()
    const val warning = 0xFFFFB547.toInt()
    const val danger = 0xFFFF5A5F.toInt()

    /** Fill of a focused card: surface tinted towards the accent. */
    const val focusFill = 0xFF18232E.toInt()

    /** Side sheets and the expanded rail sit one step above the page. */
    const val sheet = 0xFF111317.toInt()

    /** A control drawn on the controller body ("raised grey"). */
    const val control = 0xFF2A2E35.toInt()
    const val controlEdge = 0xFF3E444D.toInt()

    /** Default-mapping callout text: a notch below primary so remapped controls stand out. */
    const val textDefault = 0xFFC3C8CF.toInt()

    fun alpha(color: Int, a: Float): Int = Color.argb((a * 255f).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}

/** Motion tokens: restrained, 120–220 ms. */
object Motion {
    const val FAST = 120L
    const val BASE = 160L
    const val SLOW = 220L
}

/** Barlow (as on Gamehub), bundled; falls back to the platform faces if loading fails. */
object Type {
    private var cache: Array<Typeface>? = null

    private fun load(ctx: Context): Array<Typeface> {
        cache?.let { return it }
        fun f(id: Int, fallback: Typeface) = try {
            ResourcesCompat.getFont(ctx, id) ?: fallback
        } catch (_: Throwable) {
            fallback
        }
        val sans = Typeface.create("sans-serif", Typeface.NORMAL)
        val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        return arrayOf(
            f(R.font.barlow_regular, sans),
            f(R.font.barlow_medium, medium),
            f(R.font.barlow_semibold, medium),
            f(R.font.barlow_condensed_medium, condensed),
            f(R.font.barlow_condensed_semibold, condensed),
        ).also { cache = it }
    }

    fun regular(ctx: Context) = load(ctx)[0]
    fun medium(ctx: Context) = load(ctx)[1]
    fun semibold(ctx: Context) = load(ctx)[2]
    fun condensed(ctx: Context) = load(ctx)[3]
    fun condensedBold(ctx: Context) = load(ctx)[4]

    /** Labels: condensed capitals tracked at 0.08 em. */
    const val LABEL_TRACKING = 0.08f
    const val TABULAR = "tnum"
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.sp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
fun View.dp(v: Float): Float = context.dp(v)
fun View.dp(v: Int): Int = context.dp(v)

/** Two-pane layouts: the TV (960dp) and an unfolded foldable; a phone gets the stacked layouts. */
fun Context.isWide(): Boolean = resources.configuration.screenWidthDp >= 720
