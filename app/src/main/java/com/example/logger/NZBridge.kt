package com.example.logger

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import org.json.JSONObject
import java.util.UUID

/**
 * Bridge nativ v1 (Faza 1): location + identity + module-loader.
 * Expus in JS ca window.NZAndroid; shim-ul (assets/bridge.js) il imbraca in window.NZBridge.
 */
class NZBridge(private val activity: Activity, private val web: WebView) {

    private val fused: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(activity)
    private val prefs = activity.getSharedPreferences("nz", Context.MODE_PRIVATE)
    private var watching = false
    private var pendingOnce = false
    private var wantWatch = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            emit("window.__nzLoc", loc.latitude, loc.longitude, loc.accuracy.toDouble(), null)
        }
    }

    private fun emit(fn: String, lat: Double, lon: Double, acc: Double, err: String?) {
        val call = if (err != null) "$fn($lat,$lon,$acc,${JSONObject.quote(err)})" else "$fn($lat,$lon,$acc)"
        activity.runOnUiThread { web.evaluateJavascript(call, null) }
    }

    private fun hasLocPerm(): Boolean =
        ContextCompat.checkSelfPermission(activity, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    @JavascriptInterface
    fun startLocation() {
        activity.runOnUiThread {
            wantWatch = true
            if (watching || !hasLocPerm()) return@runOnUiThread
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
                .setMinUpdateDistanceMeters(3f).build()
            try {
                fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
                watching = true
            } catch (e: Exception) { /* ignor */ }
        }
    }

    @JavascriptInterface
    fun stopLocation() { activity.runOnUiThread { stopLocationInternal() } }

    fun stopLocationInternal() {
        if (watching) { fused.removeLocationUpdates(callback); watching = false }
    }

    @SuppressLint("MissingPermission")
    @JavascriptInterface
    fun getLocationOnce() {
        activity.runOnUiThread {
            if (!hasLocPerm()) { pendingOnce = true; return@runOnUiThread }
            try {
                fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                    .addOnSuccessListener { loc ->
                        if (loc != null) emit("window.__nzLocOnce", loc.latitude, loc.longitude, loc.accuracy.toDouble(), null)
                        else emit("window.__nzLocOnce", 0.0, 0.0, 0.0, "no-fix")
                    }
                    .addOnFailureListener { emit("window.__nzLocOnce", 0.0, 0.0, 0.0, "err") }
            } catch (e: Exception) { emit("window.__nzLocOnce", 0.0, 0.0, 0.0, "err") }
        }
    }

    /** Dupa raspunsul la permisiune: daca s-a ACORDAT, porneste watcher-ul + one-shot-ul in asteptare;
     *  daca s-a REFUZAT, respinge one-shot-ul in asteptare ca modulul sa nu ramana blocat in `await getFix()`. */
    fun onLocationPermissionResult() {
        if (hasLocPerm()) {
            if (wantWatch && !watching) startLocation()
            if (pendingOnce) { pendingOnce = false; getLocationOnce() }
        } else if (pendingOnce) {
            pendingOnce = false
            emit("window.__nzLocOnce", 0.0, 0.0, 0.0, "perm")
        }
    }

    @JavascriptInterface
    fun getIdentity(): String {
        var dev = prefs.getString("device_id", null)
        if (dev == null) {
            dev = "dev-" + UUID.randomUUID().toString()
            prefs.edit().putString("device_id", dev).apply()
        }
        val o = JSONObject()
        o.put("deviceId", dev)
        o.put("token", prefs.getString("token", null) ?: JSONObject.NULL)
        o.put("email", prefs.getString("email", null) ?: JSONObject.NULL)
        o.put("native", true)
        return o.toString()
    }

    @JavascriptInterface
    fun loadModule(name: String) {
        val safe = name.replace(Regex("[^a-z0-9_-]"), "")
        if (safe.isNotEmpty()) activity.runOnUiThread {
            web.loadUrl(BuildConfig.SERVER_URL + "/static/modules/$safe/index.html")
        }
    }

    @JavascriptInterface
    fun log(msg: String) { android.util.Log.i("NZBridge", msg) }
}
