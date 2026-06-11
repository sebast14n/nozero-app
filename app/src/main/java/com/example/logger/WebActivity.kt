package com.example.logger

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * No Zero — fereastra WEB: incarca harta /app sau ecranul de conectare /device-login, expune
 * window.NZBridge (GPS nativ) si gestioneaza login-ul Google (browser -> deep link nozero://auth).
 * Lansata din hub (MainActivity) cu extra "url"; gestioneaza si deep link-ul de login.
 */
class WebActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var bridge: NZBridge

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        setContentView(web)
        WebView.setWebContentsDebuggingEnabled(true)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(true)
        }

        bridge = NZBridge(this, web)
        web.addJavascriptInterface(bridge, "NZAndroid")

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val shim = assets.open("bridge.js").bufferedReader().use { it.readText() }
            WebViewCompat.addDocumentStartJavaScript(web, shim, setOf(BuildConfig.SERVER_URL))
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url.toString()
                // Google NU permite OAuth in WebView -> browser cu ?app=1; tokenul revine prin nozero://auth
                if (url.startsWith(BuildConfig.SERVER_URL + "/api/auth/login")) {
                    openExternal(BuildConfig.SERVER_URL + "/api/auth/login?app=1&prompt=select_account")
                    return true
                }
                if (isExternal(url)) { openExternal(url); return true }
                return false
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val tmp = WebView(view.context)
                tmp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                        openExternal(r.url.toString()); return true
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = tmp
                resultMsg.sendToTarget()
                return true
            }
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: android.webkit.GeolocationPermissions.Callback) {
                callback.invoke(origin, true, false)
            }
        }

        val target = intent?.getStringExtra("url")
        if (!handleAuthDeepLink(intent)) web.loadUrl(target ?: startUrl())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthDeepLink(intent)
    }

    /** Prinde nozero://auth?token=<JWT> (intors de OAuth-ul Google din browser): cookie + token in prefs + /app. */
    private fun handleAuthDeepLink(intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        if (data.scheme == "nozero" && data.host == "auth") {
            val token = data.getQueryParameter("token")
            if (!token.isNullOrEmpty()) {
                val cm = android.webkit.CookieManager.getInstance()
                cm.setCookie(BuildConfig.SERVER_URL, "nozero_token=$token; path=/; secure")
                cm.flush()
                // pune tokenul si in prefs ca sa-l foloseasca uploadul/C&C nativ (Bearer)
                getSharedPreferences("bioecho_prefs", MODE_PRIVATE).edit()
                    .putString("nz_token", token).apply()
                web.loadUrl(BuildConfig.SERVER_URL + "/app")
                return true
            }
        }
        return false
    }

    private fun startUrl(): String {
        val ck = android.webkit.CookieManager.getInstance().getCookie(BuildConfig.SERVER_URL)
        val authed = ck?.contains("nozero_token=") == true
        return BuildConfig.SERVER_URL + if (authed) "/app" else "/device-login"
    }

    private fun isExternal(url: String): Boolean {
        if (url.startsWith(BuildConfig.SERVER_URL)) return false
        return url.startsWith("http://") || url.startsWith("https://") ||
            url.startsWith("geo:") || url.startsWith("tel:") || url.startsWith("mailto:")
    }

    private fun openExternal(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { /* ignor */ }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        bridge.stopLocationInternal()
        super.onDestroy()
    }
}
