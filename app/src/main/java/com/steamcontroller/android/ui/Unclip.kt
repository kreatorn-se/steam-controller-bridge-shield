package com.steamcontroller.android.ui

import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import androidx.recyclerview.widget.RecyclerView
import com.steamcontroller.android.R

/**
 * Focused controls grow (scale 1.03) and glow; a container that clips its children cuts the
 * glow off at its edges. This walks a settings page or a sheet and lets every plain container
 * draw outside its bounds. Scrolling containers must keep clipping (their content would draw
 * over the status strip), so they get [ROOM_DP] of padding on every side instead, taken back
 * with negative margins so nothing moves.
 */
object Unclip {
    private const val ROOM_DP = 8

    fun apply(v: View) {
        if (v !is ViewGroup) return
        when (v) {
            is ScrollView, is HorizontalScrollView -> makeRoom(v)
            is RecyclerView -> v.clipToPadding = false
            else -> {
                if (v.clipChildren) v.clipChildren = false
                if (v.clipToPadding) v.clipToPadding = false
            }
        }
        for (i in 0 until v.childCount) apply(v.getChildAt(i))
    }

    /** New layout params for a view [apply] already gave room: take the room back again. */
    fun <T : ViewGroup.MarginLayoutParams> keep(v: View, lp: T): T {
        val a = v.getTag(R.id.unclip_room) as? IntArray ?: return lp
        lp.marginStart = lp.marginStart - a[0]
        lp.topMargin -= a[1]
        lp.marginEnd = lp.marginEnd - a[2]
        lp.bottomMargin -= a[3]
        return lp
    }

    private fun makeRoom(v: ViewGroup) {
        v.clipToPadding = false
        if (v.getTag(R.id.unclip_room) != null) return
        val lp = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val room = (ROOM_DP * v.resources.displayMetrics.density + 0.5f).toInt()
        val l = (room - v.paddingLeft).coerceAtLeast(0)
        val t = (room - v.paddingTop).coerceAtLeast(0)
        val r = (room - v.paddingRight).coerceAtLeast(0)
        val b = (room - v.paddingBottom).coerceAtLeast(0)
        v.setTag(R.id.unclip_room, intArrayOf(l, t, r, b))
        if (l == 0 && t == 0 && r == 0 && b == 0) return
        v.setPadding(v.paddingLeft + l, v.paddingTop + t, v.paddingRight + r, v.paddingBottom + b)
        lp.marginStart = lp.marginStart - l
        lp.marginEnd = lp.marginEnd - r
        lp.topMargin -= t
        lp.bottomMargin -= b
        v.layoutParams = lp
    }
}
