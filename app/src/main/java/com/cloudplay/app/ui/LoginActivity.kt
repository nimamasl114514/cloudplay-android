package com.cloudplay.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.cloudplay.app.R

/**
 * 网盘网页登录（WebView）—— 登录完成后把 CookieManager 里的 Cookie 回传给主界面。
 * 这是「内置浏览器」的核心用途；不申请任何存储/定位权限，只做登录。
 */
class LoginActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_DOMAIN = "domain"
        const val EXTRA_COOKIE = "cookie"
    }

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val url = intent.getStringExtra(EXTRA_URL)
        val domain = intent.getStringExtra(EXTRA_DOMAIN).orEmpty()
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        val txtUrl = findViewById<TextView>(R.id.txtUrl)
        txtUrl.text = url

        web = findViewById(R.id.webView)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) {
                txtUrl.text = u ?: url
            }
        }
        web.loadUrl(url)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnDone).setOnClickListener {
            val cookie = runCatching { CookieManager.getInstance().getCookie(domain) }.getOrNull().orEmpty()
            setResult(RESULT_OK, Intent().putExtra(EXTRA_COOKIE, cookie))
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (this::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
