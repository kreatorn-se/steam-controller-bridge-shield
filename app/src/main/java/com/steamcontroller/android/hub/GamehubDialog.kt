package com.steamcontroller.android.hub

import android.app.Activity
import android.text.InputFilter
import android.text.InputType
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.steamcontroller.android.shizuku.ShizukuAdbStarter

/** The Gamehub card on the main screen: link/unlink, sync status, and start-after-reboot. */
object GamehubDialog {

    fun show(activity: Activity) {
        val ctx = activity
        val pad = dp(activity, 20)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(activity, 8), pad, 0)
        }
        fun text(s: CharSequence, sizeSp: Float = 15f, dim: Boolean = false) = TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (dim) alpha = 0.72f
            setPadding(0, dp(activity, 4), 0, dp(activity, 4))
        }

        val linked = GamehubStore.isLinked(ctx)
        val host = GamehubStore.url(ctx).removePrefix("https://")
        var codeInput: EditText? = null

        if (linked) {
            val pending = CaptureStore.pendingCount(ctx)
            val lastOk = GamehubStore.lastOkAt(ctx)
            val err = GamehubStore.lastError(ctx)
            root.addView(text("Linked to $host as ${GamehubStore.deviceName(ctx)}."))
            root.addView(text(
                buildString {
                    append(if (pending == 0) "All captures uploaded." else "$pending capture${if (pending == 1) "" else "s"} waiting to upload.")
                    append(' ')
                    append(if (lastOk > 0) "Last sync ${DateUtils.getRelativeTimeSpanString(lastOk)}." else "Not synced yet. Start the service.")
                    if (err != null) append("\nLast problem: $err")
                },
                14f, dim = true,
            ))
        } else {
            root.addView(text("Upload captures and set up the controller from $host."))
            root.addView(text("On the website open Devices, choose Get a code, and type it here.", 14f, dim = true))
            codeInput = EditText(ctx).apply {
                hint = "Six-digit code"
                inputType = InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(InputFilter.LengthFilter(6))
                imeOptions = EditorInfo.IME_ACTION_DONE
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                letterSpacing = 0.2f
            }
            root.addView(codeInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(activity, 8)
            })
        }

        // Start after reboot
        root.addView(text("Start after a reboot", 16f).apply { setPadding(0, dp(activity, 18), 0, 0) })
        val sw = MaterialSwitch(ctx).apply {
            text = "Start Shizuku and the bridge automatically"
            isChecked = GamehubStore.autoStartShizuku(ctx)
            setOnCheckedChangeListener { _, on -> GamehubStore.setAutoStartShizuku(ctx, on) }
        }
        root.addView(sw)
        val testResult = text("Needs Network debugging on. The first test shows a debugging prompt on the TV: tick Always allow.", 13.5f, dim = true)
        val testBtn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Test"
            setOnClickListener {
                isEnabled = false
                testResult.text = "Connecting… accept the prompt on the TV if it appears."
                Thread {
                    val r = ShizukuAdbStarter.testConnection(ctx)
                    activity.runOnUiThread {
                        isEnabled = true
                        testResult.text = if (r.ok) "Ready. After a reboot Shizuku and the bridge start by themselves." else r.message
                    }
                }.start()
            }
        }
        root.addView(testBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(testResult)

        val builder = MaterialAlertDialogBuilder(ctx).setTitle("Gamehub").setView(root)
        if (linked) {
            builder.setPositiveButton("Sync now") { _, _ ->
                GamehubSync.kick(ctx)
                Toast.makeText(ctx, "Syncing with Gamehub", Toast.LENGTH_SHORT).show()
            }
            builder.setNeutralButton("Unlink") { _, _ ->
                GamehubStore.clearLink(ctx)
                Toast.makeText(ctx, "Unlinked from Gamehub", Toast.LENGTH_SHORT).show()
            }
            builder.setNegativeButton("Close", null)
            builder.show()
        } else {
            builder.setPositiveButton("Link", null).setNegativeButton("Cancel", null)
            val dialog = builder.create()
            dialog.setOnShowListener {
                val link = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                fun submit() {
                    val code = codeInput?.text?.toString()?.filter { it.isDigit() } ?: ""
                    if (code.length != 6) { codeInput?.error = "Six digits"; return }
                    link.isEnabled = false
                    Thread {
                        val url = GamehubStore.url(ctx)
                        val name = GamehubStore.defaultName()
                        val result = try {
                            val (id, token) = GamehubClient(url, null).pair(code, name)
                            GamehubStore.saveLink(ctx, url, id, token, name)
                            null
                        } catch (e: GamehubClient.HubException) {
                            if (e.status == 403) "That code is wrong or has expired. Get a new one." else "Gamehub said: ${e.message}"
                        } catch (t: Throwable) {
                            "Could not reach $host: ${t.message}"
                        }
                        activity.runOnUiThread {
                            link.isEnabled = true
                            if (result == null) {
                                dialog.dismiss()
                                Toast.makeText(ctx, "Linked to Gamehub", Toast.LENGTH_LONG).show()
                                GamehubSync.kick(ctx)
                            } else {
                                codeInput?.error = result
                            }
                        }
                    }.start()
                }
                link.setOnClickListener { submit() }
                codeInput?.setOnEditorActionListener { _, _, _ -> submit(); true }
                codeInput?.requestFocus()
            }
            dialog.show()
        }
    }

    private fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()
}
