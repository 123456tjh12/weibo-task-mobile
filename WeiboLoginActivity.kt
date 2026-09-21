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

/**
 * 微博登录页。
 *
 * ★ 多账号下最容易踩的坑在这里：
 *   WebView 的 Cookie 存储是**整个应用共享**的，不区分账号。
 *   如果不清空就直接打开登录页，第二个账号会看到第一个账号的登录态，
 *   于是「登录」瞬间完成，而保存下来的其实是第一个账号的 Cookie ——
 *   结果是两个槽位装着同一个账号，用户会以为多账号根本没生效。
 *
 *   所以每次进来都先 removeAllCookies，强制重新扫码/输密码。
 */
class WeiboLoginActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var lastSaveAt = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        AccountStore.init(this)
        setContentView(R.layout.activity_weibo_login)
        webView = findViewById(R.id.webView)

        val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID).orEmpty()
        val account = AccountStore.find(accountId) ?: AccountStore.ensureAtLeastOne()

        // ★ 两种模式共用这个界面：
        //   登录模式（默认）：清空 Cookie → 打开登录页 → 用户重新登录
        //   验证模式：**保留** Cookie → 直接打开微博 → 用户完成「请先验证身份」
        val verifyMode = intent.getBooleanExtra(EXTRA_VERIFY_MODE, false)

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
                trySaveCookies(account)
            }
        }

        if (verifyMode) {
            // ★ 验证模式**绝不能清 Cookie**。
            //   微博的「行为异常」验证是**按账号 + 会话**记的：换一个未登录的会话去验证，
            //   验证的是那个空会话，本应用手里的 Cookie 依然是「异常」状态 —— 白做。
            //   所以先把这个账号自己的 Cookie 灌进 WebView，让它带着**同一个会话**打开微博。
            injectCookies(account)
            CookieManager.getInstance().flush()
            webView.loadUrl("https://m.weibo.cn/")
            Toast.makeText(
                this,
                "请在页面里完成验证。验证通过后返回，本应用会自动保存新的登录状态。",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val loginUrl = "https://passport.weibo.com/sso/signin?entry=wapsso&source=wapssowb&url=" +
            java.net.URLEncoder.encode("https://m.weibo.cn/", "UTF-8")

        // 先清空 WebView 里的旧 Cookie，再打开登录页
        val cookieManager = CookieManager.getInstance()
        var opened = false
        val openLoginPage = {
            if (!opened) {
                opened = true
                webView.loadUrl(loginUrl)
            }
        }
        cookieManager.removeAllCookies {
            cookieManager.flush()
            runOnUiThread { openLoginPage() }
        }
        // 兜底：个别机型上 removeAllCookies 的回调不触发，别让页面一直白屏
        webView.postDelayed({ openLoginPage() }, 1200)
    }

    /**
     * 把这个账号已经保存的 Cookie 灌进 WebView 的 CookieManager。
     *
     * WebView 的 Cookie 存储是整个应用共享的、和 Prefs 里存的那份**互不相通**，
     * 所以验证模式下必须显式灌一次，否则 WebView 打开的是一个全新的未登录会话。
     */
    private fun injectCookies(account: WeiboAccount) {
        if (account.cookie.isBlank()) return
        val manager = CookieManager.getInstance()
        for (part in account.cookie.split(";")) {
            val item = part.trim()
            val index = item.indexOf('=')
            if (index <= 0) continue
            val name = item.substring(0, index).trim()
            val value = item.substring(index + 1).trim()
            if (name.isBlank() || value.isEmpty()) continue
            manager.setCookie(
                "https://m.weibo.cn",
                "$name=$value; Domain=.weibo.cn; Path=/; Secure; SameSite=Lax"
            )
            manager.setCookie(
                "https://weibo.com",
                "$name=$value; Domain=.weibo.com; Path=/; Secure; SameSite=Lax"
            )
        }
    }

    private fun trySaveCookies(account: WeiboAccount) {
        // 每加载一个页面都会回调一次，而验证过程会跳好几个页面。
        // 不节流的话会连开好几个线程去打微博接口，验证期间反而更密。
        val now = System.currentTimeMillis()
        if (now - lastSaveAt < 5000L) return
        lastSaveAt = now

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

        // 先写进这个账号的槽位，再验证 —— 验证时用的就是它自己的 Cookie
        account.cookie = cookieText
        account.verified = false
        AccountStore.save()

        Thread {
            var loggedIn = false
            var nickname = ""
            try {
                val config = WeiboClient.fetchConfig(account)
                loggedIn = config.optBoolean("login")
                if (loggedIn) {
                    nickname = config.optJSONObject("user")?.optString("screen_name").orEmpty()
                }
            } catch (_: Exception) {
                loggedIn = false
            }
            AccountStore.save()

            runOnUiThread {
                if (loggedIn) {
                    account.verified = true
                    account.lastCheckAt = System.currentTimeMillis()
                    account.lastCheckMessage = "登录成功"
                    // 换账号必须重置风控状态：新账号没被限流，不该继承上一个号的分数
                    account.riskScore = 0
                    account.consecutiveFailures = 0
                    // ★ 昵称只当「附加标记」，账号名里的序号**不能**被覆盖掉。
                    //   序号是用户在列表和签到结果里辨认账号的唯一依据；
                    //   微博昵称重复、改名、含特殊字符都会让「哪个账号没签上」变得无法对号。
                    account.nickname = nickname
                    account.label = "账号 " + account.order + " · " + nickname
                    AccountStore.save()
                    Toast.makeText(this, "「" + account.displayName + "」登录成功", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    account.verified = false
                    account.cookie = ""
                    AccountStore.save()
                    Toast.makeText(this, "登录未完成，请重新扫码或使用短信登录", Toast.LENGTH_LONG).show()
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

    companion object {
        /** 要登录到哪个账号槽位。不传时退回到第一个账号。 */
        const val EXTRA_ACCOUNT_ID = "account_id"

        /**
         * 验证模式：不清 Cookie，直接带着该账号的会话打开微博，
         * 让用户完成「请先验证身份」。
         */
        const val EXTRA_VERIFY_MODE = "verify_mode"
    }
}
