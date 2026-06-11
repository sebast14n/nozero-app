package com.example.logger

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * No Zero v2.0 — shell SUBTIRE: un WebView care incarca un modul web de pe noze.ro + expune
 * window.NZBridge (capabilitati native). Faza 1: location + identity + module-loader; modulul
 * implicit = transect. Restul capabilitatilor (audio/BLE/fundal) vin pe faze.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var bridge: NZBridge

    private fun moduleUrl(name: String) = BuildConfig.SERVER_URL + "/static/modules/$name/index.html"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        setContentView(web)
        WebView.setWebContentsDebuggingEnabled(true)   // debug WebView prin chrome://inspect (USB)

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

        // injecteaza window.NZBridge INAINTE de scripturile modulului (doar pe originea noze.ro)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val shim = assets.open("bridge.js").bufferedReader().use { it.readText() }
            WebViewCompat.addDocumentStartJavaScript(web, shim, setOf(BuildConfig.SERVER_URL))
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url.toString()
                if (isExternal(url)) { openExternal(url); return true }
                return false
            }
        }

        // linkuri target="_blank"/window.open (ex. Waze din modul) → deschide in app-ul extern
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
            // camera pentru scannerul QR din /device-login (login senzor)
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
            // my-location (navigator.geolocation) pe noze.ro
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: android.webkit.GeolocationPermissions.Callback) {
                callback.invoke(origin, true, false)
            }
        }

        ensurePermissions()
        web.loadUrl(startUrl())
    }

    private fun startUrl(): String {
        // logat (cookie nozero_token) → harta /app; altfel → conectare de teren (Google sau QR senzor)
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

    private fun ensurePermissions() {
        val need = mutableListOf<String>()
        for (p in arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA   // scanner QR senzor (in WebView, /device-login)
        )) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) need.add(p)
        }
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        bridge.onLocationPermissionResult()
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
