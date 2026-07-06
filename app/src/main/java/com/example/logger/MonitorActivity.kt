package com.example.logger

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.log10

/**
 * Ecran de supraveghere LIVE al inregistrarii: microfon curent (+ schimbare), nivel semnal (VU),
 * si daca se inregistreaza chiar acum (sau e pauza de program). Citeste doar LiveState (partajat cu
 * RecordingService). Consuma baterie DOAR cat e deschis — polling doar intre onResume/onPause.
 */
class MonitorActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var statusTv: TextView
    private lateinit var vu: VuView
    private lateinit var dbTv: TextView
    private lateinit var micTv: TextView
    private lateinit var statsTv: TextView
    private var silenceSince = 0L

    private val tick = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 60L) }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        title = "Monitor live"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }

        statusTv = TextView(this).apply {
            textSize = 22f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(14))
        }
        root.addView(statusTv)

        root.addView(label("Nivel semnal"))
        vu = VuView(this)
        root.addView(vu, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
        dbTv = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(16)) }
        root.addView(dbTv)

        root.addView(label("Microfon"))
        micTv = TextView(this).apply { textSize = 16f; setPadding(0, dp(2), 0, dp(6)) }
        root.addView(micTv)
        root.addView(Button(this).apply {
            text = "🎙  Schimbă microfonul"
            setOnClickListener { pickMic() }
        })

        statsTv = TextView(this).apply {
            textSize = 13f; setPadding(0, dp(18), 0, 0); setTextColor(0xFF666666.toInt())
        }
        root.addView(statsTv)

        val scroll = ScrollView(this); scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause()  { super.onPause();  ui.removeCallbacks(tick) }

    private fun refresh() {
        val active = LiveState.active
        val rec = LiveState.recordingNow
        when {
            !active -> {
                statusTv.text = "⚪  Nu înregistrează\n(serviciul e oprit)"
                statusTv.setTextColor(0xFF888888.toInt())
            }
            rec -> {
                statusTv.text = "🔴  ÎNREGISTREAZĂ ACUM"
                statusTv.setTextColor(0xFFE53935.toInt())
            }
            else -> {
                val hint = LiveState.statusHint.ifBlank { "în afara programului" }
                statusTv.text = "⏸  În pauză\n($hint)"
                statusTv.setTextColor(0xFFFB8C00.toInt())
            }
        }

        // nivel: valabil doar daca esantioanele-s recente (< 1s) si se inregistreaza
        val fresh = rec && (System.currentTimeMillis() - LiveState.lastAudioMs) < 1000L
        val lvl = if (fresh) LiveState.level else 0f
        vu.level = lvl; vu.invalidate()
        val db = if (lvl > 0f) 20.0 * log10(lvl.toDouble()) else -120.0
        dbTv.text = when {
            !rec -> "—"
            !fresh -> "aștept semnal…"
            lvl <= 0.0003f -> "≈ tăcere digitală (${fmtDb(db)})"
            else -> fmtDb(db)
        }
        dbTv.setTextColor(if (fresh && lvl > 0.5f) 0xFFE53935.toInt() else 0xFF444444.toInt())

        // avertisment tacere DIGITALA (microfon mut/mort) — nu "liniste" normala de noapte
        if (rec && fresh && lvl <= 0.0003f) {
            if (silenceSince == 0L) silenceSince = System.currentTimeMillis()
            if (System.currentTimeMillis() - silenceSince > 5000L)
                dbTv.text = "⚠ semnal ~0 de 5s — verifică microfonul (mut/acoperit?)"
        } else silenceSince = 0L

        micTv.text = "🎙  ${LiveState.micLabel}"

        val el = if (LiveState.sessionStartMs > 0) (System.currentTimeMillis() - LiveState.sessionStartMs) / 1000 else 0
        val free = try { Storage.humanSize(Storage.baseDir(this).usableSpace) } catch (_: Exception) { "?" }
        statsTv.text = "Mod: ${LiveState.mode}${if (LiveState.scheduled) " · program nocturn" else ""}\n" +
            "Segmente: ${LiveState.segmentCount} · ${LiveState.sampleRate / 1000} kHz\n" +
            "Sesiune: ${fmtDur(el)} · liber pe card: $free"
    }

    /** Lista microfoanelor de intrare -> alegere -> pref + comanda catre serviciu (segment rulat). */
    private fun pickMic() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { !it.isSink } else emptyList()
        val labels = ArrayList<String>(); val ids = ArrayList<Int>()
        labels.add("Automat (implicit sistem)"); ids.add(-1)
        for (d in devs) { labels.add(micLabel(d) + "  ·  id ${d.id}"); ids.add(d.id) }

        AlertDialog.Builder(this)
            .setTitle("Alege microfonul")
            .setItems(labels.toTypedArray()) { _, i ->
                getSharedPreferences("bioecho_prefs", MODE_PRIVATE).edit()
                    .putInt("mic_device_id", ids[i]).apply()
                if (LiveState.active) {
                    val it = Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_SET_MIC)
                    try { startService(it) } catch (_: Exception) {}
                    Toast.makeText(this, "Microfon schimbat — segmentul curent a fost reluat", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(this, "Preferință salvată (se aplică la următoarea înregistrare)", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Renunță", null)
            .show()
    }

    private fun micLabel(d: AudioDeviceInfo): String = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC   -> "Microfon intern"
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE    -> "Microfon USB-C"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Microfon jack 3.5mm"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Microfon Bluetooth"
        else -> d.productName?.toString() ?: "Necunoscut"
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 12f; setTextColor(0xFF999999.toInt()); setPadding(0, 0, 0, dp(2))
    }
    private fun fmtDb(db: Double) = if (db <= -119) "-∞ dB" else "%.0f dBFS".format(db)
    private fun fmtDur(sec: Long): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** VU orizontal cu scala dB (−60..0), peak-hold cu decadere, verde/galben/rosu. */
    class VuView(ctx: Context) : View(ctx) {
        @Volatile var level = 0f
        private var peak = 0f
        private val bg = Paint().apply { color = 0xFF1B1B1B.toInt() }
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pk = Paint().apply { color = Color.WHITE }

        private fun frac(l: Float): Float {
            if (l <= 0f) return 0f
            val db = 20.0 * log10(l.toDouble())      // ≤ 0
            return (((db + 60.0) / 60.0).coerceIn(0.0, 1.0)).toFloat()
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            c.drawRect(0f, 0f, w, h, bg)
            val f = frac(level)
            if (f > peak) peak = f else peak = (peak - 0.02f).coerceAtLeast(f)
            bar.color = when {
                f > 0.9f  -> 0xFFE53935.toInt()   // > -6 dB: aproape clip
                f > 0.75f -> 0xFFFFB300.toInt()   // -15..-6 dB
                else      -> 0xFF43A047.toInt()   // sub: verde
            }
            c.drawRect(0f, 0f, w * f, h, bar)
            if (peak > 0f) { val x = w * peak; c.drawRect(x - dpf(2), 0f, x, h, pk) }
        }
        private fun dpf(v: Int) = v * resources.displayMetrics.density
    }
}
