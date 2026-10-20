package com.steamcontroller.android

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.steamcontroller.android.service.UsageStatsHelper
import com.steamcontroller.android.ui.Screen
import com.steamcontroller.android.ui.pill.Pill
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.dp
import com.steamcontroller.android.ui.theme.surface
import com.steamcontroller.android.ui.widget.Kit
import com.steamcontroller.android.ui.widget.SwitchView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lets the user tick installed launcher apps that should auto-switch to a given
 * NamedProfile. The selection is saved back onto the profile's `boundPackages` list.
 */
class AppPickerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_PRESELECTED = "preselected_packages"
    }

    private var profileId: String = ""
    private val checkedPackages = mutableSetOf<String>()
    private lateinit var list: LinearLayout
    private lateinit var count: android.widget.TextView

    private data class AppEntry(val packageName: String, val label: String, val icon: Drawable)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty()
        intent.getStringArrayListExtra(EXTRA_PRESELECTED)?.let { checkedPackages.addAll(it) }
        val name = Prefs.listNamedProfiles(this).firstOrNull { it.id == profileId }?.name ?: ""

        list = Kit.column(this)
        list.addView(Kit.body(this, getString(R.string.app_picker_hint), 13.5f, C.text2), Kit.lp(l = 4, b = 12, ctx = this))
        list.addView(Kit.label(this, getString(R.string.app_picker_loading), 11f, C.text3), Kit.lp(l = 4, ctx = this))
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; addView(list) }
        count = Kit.label(this, "", 11f, C.text2)
        val save = Kit.button(this, getString(R.string.app_picker_done), Kit.Btn.PRIMARY) { persistAndFinish() }
        setContentView(Screen.build(this, getString(R.string.app_picker_title), name, listOf(count, save), scroll))
        renderCount()
        loadAppsAsync()
        maybePromptUsageAccess()
    }

    /**
     * Auto-switch only works once the user grants PACKAGE_USAGE_STATS access in Settings.
     * If we're about to bind apps without that, surface a one-tap path to the system screen.
     */
    private fun maybePromptUsageAccess() {
        if (UsageStatsHelper.hasPermission(this)) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_picker_usage_title)
            .setMessage(R.string.app_picker_usage_body)
            .setPositiveButton(R.string.prof_usage_open) { d, _ ->
                UsageStatsHelper.openSettingsScreen(this)
                d.dismiss()
            }.setNegativeButton(R.string.app_picker_skip, null)
            .show()
    }

    private fun loadAppsAsync() {
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) { queryLauncherApps() }
            renderRows(entries)
        }
    }

    /**
     * Returns every app the launcher would normally show. Filters by intent CATEGORY_LAUNCHER
     * (or LEANBACK_LAUNCHER on Android TV) so the list is the same one the user sees on their
     * home screen - no system services or background-only packages.
     */
    private fun queryLauncherApps(): List<AppEntry> {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val leanbackIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val combined = pm.queryIntentActivities(mainIntent, 0) + pm.queryIntentActivities(leanbackIntent, 0)
        val seen = HashSet<String>()
        return combined.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == packageName || !seen.add(pkg)) return@mapNotNull null
            try {
                AppEntry(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm))
            } catch (_: Throwable) {
                null
            }
        }.sortedWith(compareBy<AppEntry>({ it.packageName !in checkedPackages }, { it.label.lowercase() }))
    }

    private fun renderRows(entries: List<AppEntry>) {
        while (list.childCount > 1) list.removeViewAt(1)
        var first: View? = null
        for (e in entries) {
            val sw = SwitchView(this)
            sw.setChecked(e.packageName in checkedPackages, animate = false)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                surface(radiusDp = 10f, focusable = true, scale = 1.02f)
                minimumHeight = dp(58)
                setPadding(dp(17), dp(8), dp(17), dp(8))
                addView(ImageView(this@AppPickerActivity).apply { setImageDrawable(e.icon) }, LinearLayout.LayoutParams(dp(34), dp(34)))
                addView(Kit.body(this@AppPickerActivity, e.label, 15f, C.text, medium = true).apply { maxLines = 1 },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
                addView(sw)
                setOnClickListener {
                    val on = e.packageName !in checkedPackages
                    if (on) checkedPackages.add(e.packageName) else checkedPackages.remove(e.packageName)
                    sw.setChecked(on, animate = true)
                    renderCount()
                    persist()
                }
            }
            if (first == null) first = row
            list.addView(row, Kit.lp(b = 2, ctx = this))
        }
        first?.requestFocus()
    }

    private fun renderCount() {
        count.text = resources.getQuantityString(R.plurals.prof_apps, checkedPackages.size, checkedPackages.size)
    }

    /** Every toggle is saved at once, so Back never loses a change. */
    private fun persist(): Boolean {
        if (profileId.isEmpty()) return false
        val existing = Prefs.listNamedProfiles(this).firstOrNull { it.id == profileId } ?: return false
        Prefs.saveNamedProfile(this, existing.copy(boundPackages = checkedPackages.toList()))
        return true
    }

    private fun persistAndFinish() {
        if (profileId.isNotEmpty() && !persist()) {
            Pill.show(this, Pill.Kind.FAILED, getString(R.string.app_picker_gone))
        } else if (profileId.isNotEmpty()) {
            Pill.show(this, Pill.Kind.SAVED, resources.getQuantityString(R.plurals.app_picker_saved, checkedPackages.size, checkedPackages.size))
        }
        finish()
    }
}
