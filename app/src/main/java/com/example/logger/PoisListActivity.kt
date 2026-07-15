package com.example.logger

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.util.BoundingBox
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Lista POI-urilor utilizatorului (sync din /api/pois/mobile).
 * Click pe un POI -> CompassActivity. Buton "Adaugă manual" pt destinație ad-hoc.
 */
class PoisListActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var tvStatus: TextView
    private lateinit var btnAddManual: Button
    private lateinit var btnRefresh: Button

    private var currentLocation: Location? = null
    private var pois: MutableList<JSONObject> = mutableListOf()

    // ── Selector proiect / tenant (B) — arati doar POI-urile proiectului ales ──
    private lateinit var spOrg: Spinner
    private var orgs: MutableList<Pair<String, String>> = mutableListOf()  // (slug, nume); primul = ("", "Toate")
    private var selectedOrg: String? = null   // null/"" = toate proiectele
    private var displayed: List<JSONObject> = emptyList()   // server + coada offline, randate impreuna
    private var netCb: ConnectivityManager.NetworkCallback? = null   // auto-sync la revenirea retelei

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Programmatic layout (no XML — keep simple)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
            setBackgroundColor(0xFF121212.toInt())
        }
        val title = TextView(this).apply {
            text = "📍 Puncte de interes"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 12)
        }
        tvStatus = TextView(this).apply {
            text = "Se încarcă..."
            setTextColor(0xFF90CAF9.toInt())
            textSize = 12f
            setPadding(0, 0, 0, 12)
        }
        spOrg = Spinner(this).apply { setPadding(0, 0, 0, 10) }
        listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(0xFF1E1E1E.toInt())
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 0)
        }
        btnRefresh = Button(this).apply {
            text = "↻ Refresh"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8
            }
            setOnClickListener { loadPois() }
        }
        btnAddManual = Button(this).apply {
            text = "➕ Adaugă punct"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { openMapPicker() }
        }
        btnRow.addView(btnRefresh)
        btnRow.addView(btnAddManual)

        val btnFind = Button(this).apply {
            text = "🔍 Găsește senzorul prin BLE"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 8 }
            setOnClickListener { startActivity(Intent(this@PoisListActivity, FindSensorActivity::class.java)) }
        }

        val btnImport = Button(this).apply {
            text = "📥 Import KMZ"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 }
            setOnClickListener { importKmz() }
        }
        val btnExport = Button(this).apply {
            text = "📤 Export KMZ"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { exportKmz() }
        }
        val btnRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, 8, 0, 0)
            addView(btnImport); addView(btnExport)
        }

        root.addView(title)
        root.addView(tvStatus)
        root.addView(spOrg)
        root.addView(listView)
        root.addView(btnRow)
        root.addView(btnFind)
        root.addView(btnRow2)
        setContentView(root)

        startLocationUpdates()
        selectedOrg = getSharedPreferences("bioecho_prefs", MODE_PRIVATE).getString("selected_org", null)
        loadOrgs()   // proiectele userului -> spinner (offline-first din cache)
        loadPois()
    }

    // ── Proiectele userului pentru selector. Offline-first: arata cache-ul imediat, ──
    // ── apoi reimprospateaza din retea daca are semnal. Slug-urile vin din /mobile-me. ──
    private fun loadOrgs() {
        renderOrgs(readCachedOrgs())
        val jwt = getSharedPreferences("bioecho_prefs", MODE_PRIVATE).getString("jwt_token", null)
        if (jwt.isNullOrBlank()) return
        Thread {
            try {
                val conn = (URL(BuildConfig.SERVER_URL + "/api/auth/mobile-me").openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Authorization", "Bearer $jwt"); connectTimeout = 8000; readTimeout = 12000
                }
                if (conn.responseCode == 200) {
                    val arr = JSONObject(conn.inputStream.bufferedReader().readText())
                        .optJSONArray("organizations") ?: JSONArray()
                    getSharedPreferences("bioecho_prefs", MODE_PRIVATE).edit()
                        .putString("cached_orgs", arr.toString()).apply()
                    runOnUiThread { renderOrgs(arr) }
                }
                conn.disconnect()
            } catch (_: Exception) { /* offline -> ramane cache-ul; selectorul merge tot */ }
        }.start()
    }

    private fun readCachedOrgs(): JSONArray = try {
        JSONArray(getSharedPreferences("bioecho_prefs", MODE_PRIVATE).getString("cached_orgs", "[]"))
    } catch (_: Exception) { JSONArray() }

    private fun renderOrgs(arr: JSONArray) {
        orgs = mutableListOf(Pair("", "🌐 Toate proiectele"))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            orgs.add(Pair(o.optString("slug"), o.optString("name", o.optString("slug"))))
        }
        spOrg.onItemSelectedListener = null   // evita reset spurios cand se schimba adapterul (cache -> retea)
        spOrg.adapter = object : ArrayAdapter<String>(this,
            android.R.layout.simple_spinner_dropdown_item, orgs.map { it.second }) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                (v as TextView).setTextColor(0xFFE0E0E0.toInt()); v.textSize = 14f
                return v
            }
        }
        val idx = orgs.indexOfFirst { it.first == (selectedOrg ?: "") }.coerceAtLeast(0)
        spOrg.setSelection(idx, false)
        spOrg.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val slug = orgs.getOrNull(position)?.first?.ifBlank { null }
                if (slug != selectedOrg) {
                    selectedOrg = slug
                    getSharedPreferences("bioecho_prefs", MODE_PRIVATE).edit().putString("selected_org", slug).apply()
                    loadPois()   // reincarca filtrat pe proiectul ales
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    override fun onResume() {
        super.onResume()
        syncPendingPois()    // incearca sa trimita punctele salvate offline cand revii (poate ai net acum)
        renderList()         // arata si punctele din coada offline la revenire pe ecran
    }

    // Auto-sync: cand revine reteaua (chiar fara sa redeschizi ecranul), trimite coada offline.
    override fun onStart() {
        super.onStart()
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        netCb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                runOnUiThread { syncPendingPois() }
            }
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 24) cm.registerDefaultNetworkCallback(netCb!!)
        } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        netCb?.let {
            try { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        netCb = null
    }

    // ── Coada OFFLINE de POI-uri: daca nu ai net la adaugare, se salveaza local si se ──
    // ── sincronizeaza automat cand telefonul are din nou acces la server. ──
    private fun pendingPois(): JSONArray = try {
        JSONArray(getSharedPreferences("bioecho_prefs", MODE_PRIVATE).getString("pending_pois", "[]"))
    } catch (_: Exception) { JSONArray() }

    private fun setPending(arr: JSONArray) {
        getSharedPreferences("bioecho_prefs", MODE_PRIVATE).edit()
            .putString("pending_pois", arr.toString()).apply()
    }

    private fun enqueuePoi(name: String, lat: Double, lon: Double, org: String? = null) {
        val arr = pendingPois()
        arr.put(JSONObject().apply {
            put("name", name); put("lat", lat); put("lon", lon)
            if (!org.isNullOrBlank()) put("org", org)   // pastreaza proiectul pt sync ulterior
        })
        setPending(arr)
    }

    private fun postPoi(p: JSONObject, jwt: String): Boolean {
        val conn = (URL(BuildConfig.SERVER_URL + "/api/pois").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 10000; readTimeout = 15000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $jwt")
        }
        conn.outputStream.use {
            it.write(JSONObject().apply {
                put("name", p.getString("name")); put("lat", p.getDouble("lat")); put("lon", p.getDouble("lon"))
                p.optString("org").takeIf { s -> s.isNotBlank() }?.let { o -> put("org", o) }
            }.toString().toByteArray())
        }
        val ok = conn.responseCode in 200..299
        try { conn.inputStream.use { it.readBytes() } } catch (_: Exception) {}
        conn.disconnect()
        return ok
    }

    private fun syncPendingPois() {
        val arr = pendingPois()
        if (arr.length() == 0) return
        val jwt = getSharedPreferences("bioecho_prefs", MODE_PRIVATE).getString("jwt_token", null)
        if (jwt.isNullOrBlank()) return
        Thread {
            val remain = JSONArray(); var sent = 0
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val ok = try { postPoi(p, jwt) } catch (_: Exception) { false }
                if (ok) sent++ else remain.put(p)
            }
            setPending(remain)
            if (sent > 0) runOnUiThread {
                Toast.makeText(this, "✓ $sent punct(e) sincronizate", Toast.LENGTH_SHORT).show()
                loadPois()
            }
        }.start()
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // Last known location pentru distanță inițială
        try {
            currentLocation = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) { /* ignore */ }

        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 10f,
                object : LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        currentLocation = loc
                        renderList()
                    }
                }, Looper.getMainLooper())
        } catch (e: SecurityException) { /* ignore */ }
    }

    private fun loadPois() {
        tvStatus.text = "Se sincronizează..."
        val prefs = getSharedPreferences("bioecho_prefs", MODE_PRIVATE)
        val jwt = prefs.getString("jwt_token", null)
        if (jwt.isNullOrBlank()) {
            tvStatus.text = "⚠ Nu ești autentificat — arăt doar punctele locale."
            renderList()
            return
        }
        Thread {
            try {
                val orgQ = selectedOrg?.let { "?org=$it" } ?: ""   // filtreaza pe proiectul ales
                val url = URL(BuildConfig.SERVER_URL + "/api/pois/mobile" + orgQ)
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("Authorization", "Bearer $jwt")
                conn.setRequestProperty("Accept", "application/json")
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                val code = conn.responseCode
                if (code == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val arr = JSONArray(body)
                    val list = mutableListOf<JSONObject>()
                    for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
                    runOnUiThread {
                        pois = list
                        val pend = pendingPois().length()
                        tvStatus.text = "${list.size} puncte sincronizate" +
                            (if (pend > 0) "  ·  ⏳ $pend în așteptare" else "")
                        renderList()
                        precacheSatellite(list)   // pre-incarca harta satelit offline (auto, pe WiFi)
                    }
                } else {
                    runOnUiThread {
                        tvStatus.text = "⚠ Eroare server: HTTP $code"
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                runOnUiThread {
                    val pend = pendingPois().length()
                    tvStatus.text = "📡 Offline — arăt ce am local" +
                        (if (pend > 0) "  ·  ⏳ $pend în așteptare" else "")
                    renderList()   // arata coada offline chiar fara server
                }
            }
        }.start()
    }

    /** Pre-incarca tiles satelit (Esri) in jurul POI-urilor ca harta sa mearga OFFLINE in teren.
     *  Automat, DOAR pe WiFi (sa nu consume date mobile fara stire). Cache comun cu MapActivity. */
    private fun precacheSatellite(list: List<JSONObject>) {
        if (list.isEmpty() || !isWifi()) return
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        var minLat = 90.0; var maxLat = -90.0; var minLon = 180.0; var maxLon = -180.0
        var any = false
        for (p in list) {
            val lat = p.optDouble("lat", Double.NaN); val lon = p.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            any = true
            minLat = minOf(minLat, lat); maxLat = maxOf(maxLat, lat)
            minLon = minOf(minLon, lon); maxLon = maxOf(maxLon, lon)
        }
        if (!any) return
        val buf = 0.012   // ~1.3 km tampon in jurul POI-urilor
        val box = BoundingBox(maxLat + buf, maxLon + buf, minLat - buf, minLon - buf)
        val cm = CacheManager(SatelliteTiles.esri(), SqlTileWriter(), 13, 16)
        val total = try { cm.possibleTilesInArea(box, 13, 16) } catch (e: Exception) { 0 }
        if (total <= 0) return
        if (total > 4000) {
            Toast.makeText(this, "Zona POI prea mare pt cache satelit ($total tiles) — sărit", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Pre-încarc harta satelit offline ($total tiles)...", Toast.LENGTH_SHORT).show()
        cm.downloadAreaAsync(this, box, 13, 16, object : CacheManager.CacheManagerCallback {
            override fun onTaskComplete() {
                runOnUiThread { Toast.makeText(this@PoisListActivity, "✓ Hartă satelit salvată offline", Toast.LENGTH_SHORT).show() }
            }
            override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {}
            override fun downloadStarted() {}
            override fun setPossibleTilesInArea(t: Int) {}
            override fun onTaskFailed(errors: Int) {
                runOnUiThread { Toast.makeText(this@PoisListActivity, "⚠ $errors erori la cache satelit", Toast.LENGTH_SHORT).show() }
            }
        })
    }

    private fun isWifi(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun renderList() {
        displayed = pois + pendingForDisplay()   // server + coada offline, randate impreuna
        val items = displayed.map { p ->
            val lat = p.getDouble("lat")
            val lon = p.getDouble("lon")
            val name = p.getString("name")
            val isPend = p.optBoolean("_pending")
            val tags = p.optJSONArray("tags")
            val tagStr = (0 until (tags?.length() ?: 0)).joinToString(", ") { tags!!.getString(it) }

            val distStr = currentLocation?.let { loc ->
                val results = FloatArray(1)
                Location.distanceBetween(loc.latitude, loc.longitude, lat, lon, results)
                val m = results[0]
                if (m < 1000) "${m.roundToInt()} m"
                else "%.1f km".format(m / 1000)
            } ?: "—"

            (if (isPend) "⏳  $name  (nesincronizat)" else "📍  $name") +
                "\n     $distStr · ${lat.format(5)}, ${lon.format(5)}" +
                (if (tagStr.isNotEmpty()) "\n     $tagStr" else "")
        }
        listView.adapter = object : ArrayAdapter<String>(this,
            android.R.layout.simple_list_item_1, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val pend = displayed.getOrNull(position)?.optBoolean("_pending") == true
                (v as TextView).setTextColor(if (pend) 0xFFFFB74D.toInt() else 0xFFE0E0E0.toInt())
                v.textSize = 13f
                return v
            }
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            val p = displayed[position]
            startActivity(Intent(this, CompassActivity::class.java).apply {
                putExtra("lat", p.getDouble("lat"))
                putExtra("lon", p.getDouble("lon"))
                putExtra("name", p.getString("name"))
            })
        }
    }

    /** Punctele din coada offline (pending_pois) ca sa fie VIZIBILE + navigabile chiar fara semnal.
     *  Filtrate pe proiectul selectat (cele fara org apar doar sub 'Toate proiectele'). */
    private fun pendingForDisplay(): List<JSONObject> {
        val arr = pendingPois(); val out = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val org = p.optString("org").ifBlank { null }
            if (selectedOrg == null || org == selectedOrg) {
                out.add(JSONObject(p.toString()).apply { put("_pending", true) })
            }
        }
        return out
    }

    private fun Double.format(decimals: Int) = "%.${decimals}f".format(this)

    /** Adaugare punct: metoda PRINCIPALA = selectie pe harta (satelit, offline din cache). */
    private fun openMapPicker() {
        startActivityForResult(
            Intent(this, MapActivity::class.java).putExtra("pick_mode", true), 400)
    }

    // ── Import / Export KMZ (interschimb POI cu harta web sau alte unelte) ──
    private fun importKmz() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            }, 500)
        } catch (_: Exception) {
            Toast.makeText(this, "Nu pot deschide selectorul de fisiere", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importFromUri(uri: Uri) {
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.readBytes()
                if (bytes == null) { runOnUiThread { Toast.makeText(this, "Nu pot citi fisierul", Toast.LENGTH_SHORT).show() }; return@Thread }
                // KMZ = zip (semnatura "PK"); altfel KML direct
                val kml: String? = if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                    var found: String? = null
                    java.util.zip.ZipInputStream(bytes.inputStream()).use { zis ->
                        var e = zis.nextEntry
                        while (e != null) {
                            if (e.name.endsWith(".kml", true)) { found = zis.readBytes().toString(Charsets.UTF_8); break }
                            e = zis.nextEntry
                        }
                    }
                    found
                } else String(bytes, Charsets.UTF_8)
                if (kml == null) { runOnUiThread { Toast.makeText(this, "KMZ fara doc.kml", Toast.LENGTH_SHORT).show() }; return@Thread }
                val pts = parseKmlPlacemarks(kml)
                runOnUiThread {
                    if (pts.isEmpty()) { Toast.makeText(this, "Niciun punct in fisier", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                    for ((name, lat, lon) in pts) enqueuePoi(name, lat, lon, selectedOrg)
                    renderList()          // apar imediat ca ⏳
                    syncPendingPois()     // urca acum daca ai net, altfel raman in coada
                    Toast.makeText(this, "✓ Import: ${pts.size} puncte (proiect: ${selectedOrg ?: "toate"})", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) { runOnUiThread { Toast.makeText(this, "Eroare import: ${e.message}", Toast.LENGTH_LONG).show() } }
        }.start()
    }

    private fun parseKmlPlacemarks(kml: String): List<Triple<String, Double, Double>> {
        val out = mutableListOf<Triple<String, Double, Double>>()
        val pm = Regex("<Placemark\\b.*?</Placemark>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val nameRe = Regex("<name>\\s*(.*?)\\s*</name>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val coordRe = Regex("<coordinates>\\s*(.*?)\\s*</coordinates>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        for (m in pm.findAll(kml)) {
            val block = m.value
            val coord = coordRe.find(block)?.groupValues?.get(1)?.trim() ?: continue
            val first = coord.split(Regex("\\s+")).firstOrNull() ?: continue
            val parts = first.split(",")
            if (parts.size < 2) continue
            val lon = parts[0].toDoubleOrNull() ?: continue
            val lat = parts[1].toDoubleOrNull() ?: continue
            val name = nameRe.find(block)?.groupValues?.get(1)?.let { unescapeXml(it) }?.ifBlank { null } ?: "POI"
            out.add(Triple(name, lat, lon))
        }
        return out
    }

    private fun exportKmz() {
        val items = pois + pendingForDisplay()
        if (items.isEmpty()) { Toast.makeText(this, "Nu ai POI de exportat", Toast.LENGTH_SHORT).show(); return }
        Thread {
            try {
                val dir = Storage.baseDir(this)
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val f = File(dir, "POI-export-$ts.kmz")
                java.util.zip.ZipOutputStream(f.outputStream()).use { zos ->
                    zos.putNextEntry(java.util.zip.ZipEntry("doc.kml"))
                    zos.write(buildKml(items).toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
                runOnUiThread { Toast.makeText(this, "✓ Exportat ${items.size} POI → /BioEcho/${f.name}", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) { runOnUiThread { Toast.makeText(this, "Eroare export: ${e.message}", Toast.LENGTH_LONG).show() } }
        }.start()
    }

    private fun buildKml(items: List<JSONObject>): String {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n")
        for (p in items) {
            val lat = p.optDouble("lat", Double.NaN); val lon = p.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            val name = escapeXml(p.optString("name", "POI"))
            sb.append("  <Placemark><name>$name</name><Point><coordinates>$lon,$lat,0</coordinates></Point></Placemark>\n")
        }
        sb.append("</Document>\n</kml>\n")
        return sb.toString()
    }

    private fun escapeXml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")
    private fun unescapeXml(s: String) = s.replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 400 && resultCode == RESULT_OK && data != null) {
            if (data.getBooleanExtra("manual", false)) {
                showManualDialog()                       // rezerva: fara harta -> coordonate manuale
            } else {
                val lat = data.getDoubleExtra("lat", Double.NaN)
                val lon = data.getDoubleExtra("lon", Double.NaN)
                if (!lat.isNaN() && !lon.isNaN()) askNameAndNavigate(lat, lon)
            }
        }
        if (requestCode == 500 && resultCode == RESULT_OK && data?.data != null) {
            importFromUri(data.data!!)   // KMZ/KML ales -> importa POI-uri
        }
    }

    private fun askNameAndNavigate(lat: Double, lon: Double) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val etName = EditText(this).apply { hint = "Nume (ex: Cabană, P3)" }
        layout.addView(etName)
        AlertDialog.Builder(this)
            .setTitle("Punct selectat pe hartă")
            .setMessage("📍 %.5f, %.5f".format(lat, lon))
            .setView(layout)
            // Principal: salveaza in zona privata (sync cu harta web) + navigheaza
            .setPositiveButton("Salvează + navighează") { _, _ ->
                val name = etName.text.toString().ifBlank { "Punct" }
                savePoi(name, lat, lon)
                startActivity(Intent(this, CompassActivity::class.java).apply {
                    putExtra("lat", lat); putExtra("lon", lon); putExtra("name", name)
                })
            }
            // Doar pentru navigare ad-hoc, fara a salva
            .setNeutralButton("Doar navighează") { _, _ ->
                startActivity(Intent(this, CompassActivity::class.java).apply {
                    putExtra("lat", lat); putExtra("lon", lon)
                    putExtra("name", etName.text.toString().ifBlank { "Destinație" })
                })
            }
            .setNegativeButton("Anulează", null)
            .show()
    }

    /** Salveaza POI in zona privata. Daca ai net -> POST direct. Daca NU (offline / eroare) ->
     *  se salveaza in coada locala si se sincronizeaza automat cand telefonul are din nou net. */
    private fun savePoi(name: String, lat: Double, lon: Double) {
        val jwt = getSharedPreferences("bioecho_prefs", MODE_PRIVATE).getString("jwt_token", null)
        if (jwt.isNullOrBlank()) {
            enqueuePoi(name, lat, lon, selectedOrg)
            Toast.makeText(this, "💾 Salvat local: $name (neautentificat — se trimite după login)", Toast.LENGTH_LONG).show()
            tvStatus.text = "⏳ ${pendingPois().length()} punct(e) în așteptare (offline)"
            return
        }
        Thread {
            val ok = try {
                postPoi(JSONObject().apply { put("name", name); put("lat", lat); put("lon", lon); selectedOrg?.let { put("org", it) } }, jwt)
            } catch (_: Exception) { false }
            runOnUiThread {
                if (ok) {
                    Toast.makeText(this, "✓ Punct salvat: $name", Toast.LENGTH_SHORT).show()
                    loadPois()
                } else {
                    enqueuePoi(name, lat, lon, selectedOrg)
                    Toast.makeText(this, "📡 Fără net — salvat local: $name. Se sincronizează automat când ai internet.", Toast.LENGTH_LONG).show()
                    tvStatus.text = "⏳ ${pendingPois().length()} punct(e) în așteptare (offline)"
                }
            }
        }.start()
    }

    private fun showManualDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val etName = EditText(this).apply { hint = "Nume (ex: Cabană, P3)" }
        val etLat = EditText(this).apply {
            hint = "Latitudine (ex: 45.831)"
            inputType = InputType.TYPE_CLASS_NUMBER or
                        InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val etLon = EditText(this).apply {
            hint = "Longitudine (ex: 24.121)"
            inputType = InputType.TYPE_CLASS_NUMBER or
                        InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        layout.addView(etName); layout.addView(etLat); layout.addView(etLon)

        AlertDialog.Builder(this)
            .setTitle("🧭 Destinație ad-hoc (nu se salvează)")
            .setView(layout)
            .setPositiveButton("Navighează") { _, _ ->
                val lat = etLat.text.toString().toDoubleOrNull()
                val lon = etLon.text.toString().toDoubleOrNull()
                if (lat != null && lon != null) {
                    startActivity(Intent(this, CompassActivity::class.java).apply {
                        putExtra("lat", lat); putExtra("lon", lon)
                        putExtra("name", etName.text.toString().ifBlank { "Destinație" })
                    })
                } else {
                    Toast.makeText(this, "Coordonate invalide", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Anulează", null)
            .show()
    }
}
