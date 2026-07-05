package com.example.logger

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Gaseste un senzor (sau orice emitator BLE) acoperit de frunze: "detector cald/rece" pe RSSI.
 * Telefonul NU poate citi DIRECTIA unui semnal BLE (cere hardware AoA) — dar puterea semnalului
 * (RSSI) creste cand te apropii. Te plimbi: daca semnalul creste, esti pe drumul bun; daca scade,
 * intorci. Bipuri + vibratie care se intetesc cand te apropii (ca un detector de metale).
 */
class FindSensorActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var listView: ListView
    private lateinit var homing: LinearLayout
    private lateinit var tvName: TextView
    private lateinit var tvRssi: TextView
    private lateinit var tvDist: TextView
    private lateinit var tvTrend: TextView
    private lateinit var bar: ProgressBar
    private lateinit var btnBack: Button

    private var scanner: BluetoothLeScanner? = null
    private val handler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private var vibrator: Vibrator? = null

    // address -> [name, rssi, lastSeenMs, brand din manufacturer data, eticheta senzor cunoscut din OUI]
    private data class Dev(var name: String, var rssi: Int, var seen: Long,
                           var brand: String? = null, var known: String? = null,
                           val firstSeen: Long = seen, var show: Double = rssi.toDouble())
    private val devices = HashMap<String, Dev>()
    private var order: List<String> = emptyList()

    private var targetAddr: String? = null
    private var ema = -100.0          // RSSI mediat exponential (pt netezire)
    private var emaPrev = -100.0
    private var lastBeep = 0L
    private lateinit var btnMark: Button

    // "Clasa senzor": prefixele MAC (OUI = primele 3 octeti) ale senzorilor cunoscuti.
    // Se invata: marchezi un senzor o data -> toti cei cu acelasi OUI sunt recunoscuti automat.
    private val sensorOuis = HashSet<String>()
    private fun oui(addr: String) = if (addr.length >= 8) addr.substring(0, 8).uppercase() else addr.uppercase()
    /** E (probabil) un senzor BLE? Dupa OUI invatat SAU dupa numele advertised. */
    private fun isSensor(addr: String, name: String): Boolean {
        if (oui(addr) in sensorOuis) return true
        val n = name.lowercase()
        return listOf("song", "meter", "smm", "smu", "audiomoth", "wildlife", "bat", "bioecho", "sensor", "senzor")
            .any { n.contains(it) }
    }
    private fun loadOuis() {
        // senzori cunoscuti din fabrica (ex. Song Meter Wildlife Acoustics 9C:25:BE) — recunoscuti
        // automat, fara sa-i inveti manual
        BleVendors.KNOWN_SENSOR_OUI.keys.forEach { sensorOuis.add(it) }
        getSharedPreferences("ble_finder", MODE_PRIVATE).getString("sensor_ouis", "")
            ?.split(",")?.filter { it.isNotBlank() }?.forEach { sensorOuis.add(it) }
    }
    private fun saveOuis() {
        getSharedPreferences("ble_finder", MODE_PRIVATE).edit()
            .putString("sensor_ouis", sensorOuis.joinToString(",")).apply()
    }

    // Dispozitive marcate "cunoscute / neinteresante" (ex. telefonul tau, al colegului) -> prioritate
    // mica (jos in lista, estompate). NU se sterg — doar coboara ca sa nu incurce cautarea senzorilor.
    private val ignoredAddrs = HashSet<String>()
    private fun isIgnored(addr: String) = addr.uppercase() in ignoredAddrs
    private fun loadIgnored() {
        getSharedPreferences("ble_finder", MODE_PRIVATE).getString("ignored_addrs", "")
            ?.split(",")?.filter { it.isNotBlank() }?.forEach { ignoredAddrs.add(it.uppercase()) }
    }
    private fun toggleIgnore(addr: String) {
        val a = addr.uppercase()
        if (!ignoredAddrs.remove(a)) ignoredAddrs.add(a)
        getSharedPreferences("ble_finder", MODE_PRIVATE).edit()
            .putString("ignored_addrs", ignoredAddrs.joinToString(",")).apply()
        renderList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 24)
            setBackgroundColor(0xFF121212.toInt())
        }
        root.addView(TextView(this).apply {
            text = "🔍 Găsește senzorul (BLE)"; textSize = 20f
            setTextColor(0xFFFFFFFF.toInt()); setPadding(0, 0, 0, 8)
        })
        tvStatus = TextView(this).apply {
            text = "Pornesc scanarea…"; setTextColor(0xFF90CAF9.toInt()); textSize = 12f
            setPadding(0, 0, 0, 10)
        }
        root.addView(tvStatus)

        // panou "homing" (ascuns pana alegi o tinta)
        homing = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding(16, 16, 16, 16); setBackgroundColor(0xFF1B1B1B.toInt())
        }
        tvName = TextView(this).apply { textSize = 16f; setTextColor(0xFFFFFFFF.toInt()) }
        tvDist = TextView(this).apply { textSize = 40f; setTextColor(0xFF4CAF50.toInt()); setPadding(0, 8, 0, 0) }
        tvTrend = TextView(this).apply { textSize = 22f; setPadding(0, 4, 0, 4) }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        tvRssi = TextView(this).apply { textSize = 12f; setTextColor(0xFF9E9E9E.toInt()); setPadding(0, 6, 0, 0) }
        btnMark = Button(this).apply {
            text = "📌 E senzor (învață clasa MAC)"
            setOnClickListener { markTargetAsSensor() }
        }
        btnBack = Button(this).apply { text = "← Altă țintă"; setOnClickListener { clearTarget() } }
        homing.addView(tvName); homing.addView(tvDist); homing.addView(tvTrend)
        homing.addView(bar); homing.addView(tvRssi); homing.addView(btnMark); homing.addView(btnBack)
        root.addView(homing)

        listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(0xFF1E1E1E.toInt())
        }
        root.addView(TextView(this).apply {
            text = "Dispozitive BLE din apropiere. Apasă = caută (homing) · Ține apăsat = ignoră/marchează."
            setTextColor(0xFF9E9E9E.toInt()); textSize = 12f; setPadding(0, 10, 0, 6)
        })
        root.addView(listView)
        setContentView(root)

        listView.setOnItemClickListener { _, _, pos, _ ->
            order.getOrNull(pos)?.let { setTarget(it) }
        }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            order.getOrNull(pos)?.let { addr ->
                val d = devices[addr]
                val ign = isIgnored(addr)
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(d?.known ?: d?.name?.takeIf { it != "(fără nume)" } ?: addr)
                    .setItems(arrayOf(
                        "🎯 Caută (homing)",
                        if (ign) "🔔 Nu mai ignora" else "🔕 Ignoră (cunoscut, prioritate mică)"
                    )) { _, w -> if (w == 0) setTarget(addr) else toggleIgnore(addr) }
                    .show()
            }
            true
        }

        loadOuis()
        loadIgnored()
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        try { tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90) } catch (_: Exception) {}

        if (!hasScanPerm()) {
            ActivityCompat.requestPermissions(this, scanPerms(), 7)
        } else {
            startScan()
        }
    }

    private fun scanPerms(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasScanPerm(): Boolean = if (Build.VERSION.SDK_INT >= 31)
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    else ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (hasScanPerm()) startScan()
        else tvStatus.text = "⚠ Fără permisiune Bluetooth nu pot scana."
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            val addr = try { result.device?.address } catch (_: SecurityException) { null } ?: return
            val nm = (result.scanRecord?.deviceName ?: "").ifBlank { "(fără nume)" }
            val now = System.currentTimeMillis()
            // brand din company ID (manufacturer specific data) + eticheta senzor cunoscut din OUI
            val msd = result.scanRecord?.manufacturerSpecificData
            val brand = if (msd != null && msd.size() > 0) BleVendors.brandOf(msd.keyAt(0)) else null
            val prev = devices[addr]
            val d = Dev(nm, result.rssi, now, brand, BleVendors.knownSensorLabel(addr),
                        firstSeen = prev?.firstSeen ?: now)
            // RSSI netezit (EMA) pt afisaj — sa nu palpaie numarul
            d.show = if (prev != null) prev.show * 0.7 + result.rssi * 0.3 else result.rssi.toDouble()
            devices[addr] = d
            if (addr == targetAddr) updateHoming(result.rssi)
        }
        override fun onScanFailed(errorCode: Int) {
            runOnUiThread { tvStatus.text = "⚠ Scanare eșuată ($errorCode). Bluetooth pornit?" }
        }
    }

    private val refreshTick = object : Runnable {
        override fun run() {
            pruneOld()
            if (targetAddr == null) renderList()
            handler.postDelayed(this, 2500)   // refresh lent -> lista nu mai sare, poti citi
        }
    }

    private fun startScan() {
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = mgr?.adapter
        if (adapter == null || !adapter.isEnabled) {
            tvStatus.text = "⚠ Pornește Bluetooth-ul și reintră."
            return
        }
        scanner = adapter.bluetoothLeScanner ?: return
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0).build()
            scanner?.startScan(null, settings, scanCallback)
            tvStatus.text = "Scanez… plimbă-te încet; semnalul crește când te apropii."
            handler.post(refreshTick)
        } catch (e: SecurityException) {
            tvStatus.text = "⚠ Permisiune Bluetooth lipsă."
        }
    }

    private fun pruneOld() {
        val cut = System.currentTimeMillis() - 12_000
        devices.entries.removeAll { it.value.seen < cut && it.key != targetAddr }
    }

    private fun renderList() {
        // ORDINE STABILA: neignorate intai, senzorii sus, apoi in ordinea aparitiei (firstSeen).
        // NU sortam dupa RSSI — distanta nu e precisa, iar re-sortarea dupa semnal facea lista sa sara.
        order = devices.entries.sortedWith(
            compareBy<Map.Entry<String, Dev>> { isIgnored(it.key) }
                .thenByDescending { isSensor(it.key, it.value.name) }
                .thenBy { it.value.firstSeen }
        ).map { it.key }
        val sensorCount = order.count { isSensor(it, devices[it]!!.name) && !isIgnored(it) }
        val items = order.map { addr ->
            val d = devices[addr]!!
            val ign = isIgnored(addr)
            val tag = if (ign) "🔕 " else if (isSensor(addr, d.name)) "🛰 " else "📡 "
            // titlu: eticheta senzor cunoscut (+ nume/serie difuzata) > nume difuzat > brand > generic
            val title = when {
                d.known != null && d.name != "(fără nume)" -> "${d.known} · ${d.name}"
                d.known != null -> d.known!!
                d.name != "(fără nume)" -> d.name
                d.brand != null -> "${d.brand} (dispozitiv)"
                else -> "(necunoscut)"
            }
            val bits = mutableListOf("${d.show.toInt()} dBm", "~${distM(d.show)}")
            if (d.brand != null && d.known == null) bits.add(d.brand!!)
            bits.add(addr)
            "$tag$title\n     ${bits.joinToString(" · ")}"
        }
        listView.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                val addr = order.getOrNull(position)
                val ign = addr != null && isIgnored(addr)
                val isS = addr != null && isSensor(addr, devices[addr]?.name ?: "")
                v.setTextColor(when { ign -> 0xFF616161.toInt(); isS -> 0xFF80CBC4.toInt(); else -> 0xFFE0E0E0.toInt() })
                v.textSize = 13f
                return v
            }
        }
        tvStatus.text = "${devices.size} dispozitive (${sensorCount} 🛰 senzor). Apasă pe cel căutat."
    }

    /** Invata clasa MAC (OUI) a tintei curente -> toti senzorii cu acelasi prefix sunt recunoscuti. */
    private fun markTargetAsSensor() {
        val addr = targetAddr ?: return
        val o = oui(addr)
        if (sensorOuis.add(o)) {
            saveOuis()
            Toast.makeText(this, "Clasă senzor învățată: $o*", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Deja marcat ca senzor ($o*)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setTarget(addr: String) {
        targetAddr = addr
        val d = devices[addr]
        ema = (d?.rssi ?: -100).toDouble(); emaPrev = ema
        listView.visibility = View.GONE
        homing.visibility = View.VISIBLE
        val label = d?.known?.let { k -> if (d.name != "(fără nume)") "$k · ${d.name}" else k }
            ?: d?.name?.takeIf { it != "(fără nume)" } ?: d?.brand ?: addr
        tvName.text = "🎯 $label\n$addr"
        tvStatus.text = "Mergi încet în orice direcție și urmărește dacă te apropii."
        updateHoming(d?.rssi ?: -100)
    }

    private fun clearTarget() {
        targetAddr = null
        homing.visibility = View.GONE
        listView.visibility = View.VISIBLE
        renderList()
    }

    /** Actualizeaza afisajul homing + bipuri/vibratie in functie de RSSI (apropiere). */
    private fun updateHoming(rssi: Int) {
        emaPrev = ema
        ema = ema * 0.7 + rssi * 0.3          // netezire (BLE RSSI e zgomotos)
        val dist = distM(ema)
        // bara 0..100: -100 dBm -> 0, -40 dBm -> 100
        val pct = ((ema + 100) / 60.0 * 100).coerceIn(0.0, 100.0).toInt()
        val warmer = ema > emaPrev + 0.4
        val colder = ema < emaPrev - 0.4
        runOnUiThread {
            tvDist.text = dist
            tvDist.setTextColor(when {
                pct > 66 -> 0xFFFF5252.toInt()    // foarte aproape — roșu „fierbinte"
                pct > 33 -> 0xFFFFC107.toInt()    // mediu — galben
                else -> 0xFF4CAF50.toInt()        // departe — verde
            })
            tvTrend.text = when {
                warmer -> "🔥 te APROPII"
                colder -> "❄️ te depărtezi"
                else -> "… ține direcția"
            }
            tvTrend.setTextColor(if (warmer) 0xFFFF5252.toInt() else if (colder) 0xFF64B5F6.toInt() else 0xFF9E9E9E.toInt())
            bar.progress = pct
            tvRssi.text = "RSSI ${ema.toInt()} dBm (brut $rssi)"
        }
        beep(pct)
    }

    /** Bip + vibratie cu cadenta proportionala cu apropierea (mai des = mai aproape). */
    private fun beep(pct: Int) {
        val now = System.currentTimeMillis()
        val interval = (1200 - pct * 10).toLong().coerceIn(120, 1200)   // 1.2s departe -> 0.12s aproape
        if (now - lastBeep < interval) return
        lastBeep = now
        try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 80) } catch (_: Exception) {}
        if (pct > 50) {
            try {
                if (Build.VERSION.SDK_INT >= 26)
                    vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
                else @Suppress("DEPRECATION") vibrator?.vibrate(40)
            } catch (_: Exception) {}
        }
    }

    /** Estimare distanta din RSSI (orientativa): d = 10^((txPower - rssi)/(10*n)). */
    private fun distM(rssi: Double): String {
        val txPower = -59.0; val n = 2.5
        val d = 10.0.pow((txPower - rssi) / (10 * n))
        val dc = min(200.0, max(0.3, d))
        return if (dc < 10) "%.1f m".format(dc) else "${dc.toInt()} m"
    }

    override fun onPause() {
        super.onPause()
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        handler.removeCallbacks(refreshTick)
    }

    override fun onResume() {
        super.onResume()
        if (hasScanPerm()) startScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
        try { tone?.release() } catch (_: Exception) {}
    }
}
