package dev.tubik.app.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import dev.tubik.app.App

/**
 * Вход в Google в обычном WebView. Пароль вводится на странице Google, приложение его не видит —
 * после входа мы просто забираем cookies youtube.com.
 */
class LoginActivity : ComponentActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.setAcceptCookie(true)

        web = WebView(this)
        cm.setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (url.startsWith("https://www.youtube.com") || url.startsWith("https://m.youtube.com")) {
                    val cookies = cm.getCookie("https://www.youtube.com")
                    if (CookieUtils.hasAuth(cookies)) {
                        cm.flush()
                        App.instance.prefs.cookies = cookies
                        Toast.makeText(this@LoginActivity, "Вошёл, красавчик", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
        }
        setContentView(web)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
        web.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&passive=true&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26next%3Dhttps%253A%252F%252Fwww.youtube.com%252F")
    }


    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
