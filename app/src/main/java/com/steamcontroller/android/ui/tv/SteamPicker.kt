package com.steamcontroller.android.ui.tv

import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.R
import com.steamcontroller.android.hub.GamehubTv
import com.steamcontroller.android.input.NamedProfile
import com.steamcontroller.android.ui.Host
import com.steamcontroller.android.ui.Sheet
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.dp
import com.steamcontroller.android.ui.theme.surface
import com.steamcontroller.android.ui.widget.Kit
import com.steamcontroller.android.ui.widget.setOnFocusChangeListenerChained
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Bind to Steam game…": Kent's Steam library from Gamehub (`/api/device/v1/steam/apps?q=`),
 * recent first, searchable. A profile can follow several games (NamedProfile.steamAppIds); the
 * switch itself is hub/SteamSwitch.
 */
object SteamPicker {
    fun open(host: Host, profileId: String, onChanged: () -> Unit) {
        val ctx = host.activity
        val sheet = Sheet(ctx, 440)
        val profile0 = Prefs.listNamedProfiles(ctx).firstOrNull { it.id == profileId } ?: return
        sheet.header(ctx.getString(R.string.steam_bind_eyebrow), profile0.name, ctx.getString(R.string.steam_bind_hint))
        val search = EditText(ctx).apply {
            hint = ctx.getString(R.string.steam_bind_search)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(C.text)
            setHintTextColor(C.text3)
            textSize = 15f
            setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(16), ctx.dp(10))
            surface(radiusDp = 8f, focusable = true, scale = 1.02f)
        }
        sheet.body.addView(search, Kit.lp(b = 10, ctx = ctx))
        val list = Kit.column(ctx)
        sheet.body.addView(list)
        val status = Kit.body(ctx, ctx.getString(R.string.tv_loading_title), 13f, C.text2)
        list.addView(status)
        var job: Job? = null

        fun profile(): NamedProfile? = Prefs.listNamedProfiles(ctx).firstOrNull { it.id == profileId }

        fun render(apps: List<GamehubTv.SteamApp>) {
            val focusedId = (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.hasFocus() }?.tag
            list.removeAllViews()
            if (apps.isEmpty()) { list.addView(Kit.body(ctx, ctx.getString(R.string.steam_bind_none), 13f, C.text2)); return }
            val bound = profile()?.steamAppIds ?: emptyList()
            for (a in apps) {
                val on = a.appid in bound
                val row = Kit.row(ctx).apply {
                    tag = a.appid
                    minimumHeight = ctx.dp(52)
                    setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(14), ctx.dp(6))
                    surface(radiusDp = 8f, fill = 0, stroke = 0, focusable = true, activeFill = C.surface2, scale = 1.02f)
                    isActivated = on
                }
                val icon = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; roundCorners(5f); setBackgroundColor(C.surface2) }
                Img.into(icon, a.icon, Img.Spec(ctx.px(36)))
                row.addView(icon, LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(36)))
                val name = Kit.body(ctx, a.name, 15f, if (on) C.text else C.textDefault, medium = on).apply { maxLines = 1 }
                row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ctx.dp(14) })
                row.addView(Kit.label(ctx, if (on) "✓" else "", 13f, C.accent))
                row.setOnFocusChangeListenerChained { has -> name.setTextColor(if (has || on) C.text else C.textDefault) }
                row.setOnClickListener {
                    val p = profile() ?: return@setOnClickListener
                    val ids = if (a.appid in p.steamAppIds) p.steamAppIds - a.appid else p.steamAppIds + a.appid
                    Prefs.saveNamedProfile(ctx, p.copy(steamAppIds = ids))
                    onChanged()
                    render(apps)
                }
                list.addView(row, Kit.lp(b = 2, ctx = ctx))
            }
            if (focusedId != null) (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.tag == focusedId }?.requestFocus()
        }

        fun query(q: String, wait: Long) {
            job?.cancel()
            job = ctx.lifecycleScope.launch {
                if (wait > 0) delay(wait)
                val r = withContext(Dispatchers.IO) { GamehubTv.steamApps(ctx, q) }
                when (r) {
                    is GamehubTv.Res.Ok -> render(r.value.sortedByDescending { it.lastPlayed })
                    is GamehubTv.Res.Err -> {
                        list.removeAllViews()
                        list.addView(Kit.body(ctx, ctx.getString(if (r.fail == GamehubTv.Fail.NOT_DEPLOYED) R.string.steam_bind_update else R.string.steam_bind_offline), 13f, C.text2))
                    }
                }
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { query(s?.toString()?.trim() ?: "", 400L) }
        })
        search.gravity = Gravity.CENTER_VERTICAL
        host.openSheet(sheet)
        sheet.focus(null)
        query("", 0L)
    }
}
