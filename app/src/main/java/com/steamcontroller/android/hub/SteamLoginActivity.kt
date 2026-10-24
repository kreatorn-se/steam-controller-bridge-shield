package com.steamcontroller.android.hub

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.steamcontroller.android.ui.pill.Pill
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.dp
import com.steamcontroller.android.ui.widget.Kit

/**
 * Connect Steam Cloud ([SteamCloud]): Steam's own login page, full screen. Kent scans the QR code
 * with the Steam app on his phone (nothing to type with the remote). Steam keeps its login in the
 * WebView's cookies; this screen only notices when Steam lands on the Enshrouded cloud page, which
 * means the login worked, and then fetches the saves once. Back leaves without connecting.
 */
class SteamLoginActivity : AppCompatActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            isFocusable = true
            isFocusableInTouchMode = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        val hint = Kit.label(this, "Scan the QR code with the Steam app on your phone. Back leaves without connecting.", 13f, C.text2).apply {
            isAllCaps = false
            setPadding(dp(24), dp(10), dp(24), dp(10))
            setBackgroundColor(0xE6101216.toInt())
        }
        setContentView(FrameLayout(this).apply {
            addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM))
        })
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (url.contains("remotestorageapp") && !isFinishing) {
                    CookieManager.getInstance().flush()
                    SteamCloud.setConnected(this@SteamLoginActivity, true)
                    Pill.show(this@SteamLoginActivity, Pill.Kind.CONNECTED, "Steam Cloud connected")
                    SteamCloud.syncNow(this@SteamLoginActivity, "connected")
                    finish()
                }
            }
        }
        web.loadUrl(SteamCloud.LOGIN)
        web.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && web.canGoBack()) { web.goBack(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
