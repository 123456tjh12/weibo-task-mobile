package com.tjh.weibotask

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class WeiboLoginActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContentView(R.layout.activity_weibo_login)
        webView = findViewById(R.id.webView)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
        }
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                trySaveCookies()
            }
        }

        val loginUrl = "https://passport.weibo.com/sso/signin?entry=wapsso&source=wapssowb&url=" +
            java.net.URLEncoder.encode("https://m.weibo.cn/", "UTF-8")
        webView.loadUrl(loginUrl)
    }

    private fun trySaveCookies() {
        val cookieText = collectCookieHeader()
        if (!cookieText.contains("SUB=")) return

        val values = parseCookieValues(cookieText)
        for (name in listOf("SUB", "SUBP", "SSOLoginState", "ALF", "SRT", "SRF", "XSRF-TOKEN")) {
            val value = values[name] ?: continue
            CookieManager.getInstance().setCookie(
                "https://m.weibo.cn",
                "$name=$value; Domain=.weibo.cn; Path=/; Secure; SameSite=Lax"
            )
        }
        CookieManager.getInstance().flush()
        Prefs.cookieHeader = cookieText
        Prefs.weiboVerified = false

        Thread {
            val loggedIn = try {
                WeiboClient.fetchConfig().optBoolean("login")
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                if (loggedIn) {
                    Prefs.weiboVerified = true
                    Prefs.lastLoginCheckAt = System.currentTimeMillis()
                    Prefs.lastLoginCheckMessage = "登录成功"
                    Prefs.riskScore = 0
                    Prefs.consecutiveFailures = 0
                    Toast.makeText(this, "微博登录成功", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Prefs.weiboVerified = false
                    Prefs.cookieHeader = ""
                    Toast.makeText(this, "微博登录未完成，请重新扫码或使用短信登录", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun collectCookieHeader(): String {
        val mobileRaw = CookieManager.getInstance().getCookie("https://m.weibo.cn").orEmpty()
        val mobileValues = parseCookieValues(mobileRaw)
        if (mobileValues.containsKey("SUB")) {
            return mobileValues.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }

        val values = linkedMapOf<String, String>()
        values.putAll(mobileValues)
        for (url in listOf("https://weibo.com", "https://passport.weibo.com")) {
            val raw = CookieManager.getInstance().getCookie(url) ?: continue
            values.putAll(parseCookieValues(raw))
        }
        return values.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun parseCookieValues(raw: String): Map<String, String> {
        val values = linkedMapOf<String, String>()
        for (part in raw.split(";")) {
            val item = part.trim()
            val index = item.indexOf('=')
            if (index <= 0) continue
            val name = item.substring(0, index).trim()
            val value = item.substring(index + 1).trim()
            if (value.isNotEmpty()) values[name] = value
        }
        return values
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
