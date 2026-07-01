package com.example.logger

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Test comparativ de microfoane. Alegi dispozitivul fizic + sursa (AudioSource), inregistrezi 20s,
 * iar app-ul CONFIRMA cu getRoutedDevice() ce microfon a inregistrat EFECTIV (nu ce presupune).
 * Fisiere in /BioEcho/mic_test/ cu nume descriptiv. Repeti pt fiecare mic in aceleasi conditii.
 */
class MicTestActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var inputs: List<AudioDeviceInfo> = emptyList()
    private var selectedDevice: AudioDeviceInfo? = null
    private var selectedSource = MediaRecorder.AudioSource.UNPROCESSED

    @Volatile private var recording = false
    private var recThread: Thread? = null

    private lateinit var deviceGroup: RadioGroup
    private lateinit var noteEdit: EditText
    private lateinit var routedTv: TextView
    private lateinit var statusTv: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var recBtn: Button
    private lateinit var filesTv: TextView

    private val sources = listOf(
        Triple("Unprocessed", MediaRecorder.AudioSource.UNPROCESSED,
            "Brut, fara AGC/filtru — ideal bioacustic. DAR uneori doar pt micul intern (poate ignora mic extern)."),
        Triple("Mic", MediaRecorder.AudioSource.MIC,
            "Micul principal, procesare minima — de obicei merge cu USB/extern."),
        Triple("Default", MediaRecorder.AudioSource.DEFAULT, "Android alege singur."),
        Triple("Voice recognition", MediaRecorder.AudioSource.VOICE_RECOGNITION,
            "Tunat pt voce (reduce zgomotul de fond) — NU pt pasari."),
        Triple("Camcorder", MediaRecorder.AudioSource.CAMCORDER, "Micul de camera video.")
    )

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(28, 36, 28, 28); setBackgroundColor(0xFF121212.toInt())
        }
        val scroll = ScrollView(this).apply { addView(root) }

        root.addView(h1("🎙 Test microfoane"))
        root.addView(hint("Conecteaza un microfon, alege-l mai jos + sursa, apasa Inregistreaza 20s. " +
            "App-ul CONFIRMA ce microfon a inregistrat efectiv. Repeta pt fiecare mic in ACELEASI conditii " +
            "(aceeasi pozitie/distanta fata de sursa). Pt zgomot propriu: 20s de liniste totala. Apoi urca fisierele."))

        root.addView(h2("1. Microfon (dispozitiv fizic)"))
        root.addView(Button(this).apply { text = "🔄 Reimprospateaza lista"; setOnClickListener { refreshDevices() } })
        deviceGroup = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(deviceGroup)

        root.addView(h2("2. Sursa (AudioSource)"))
        val sourceGroup = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        sources.forEachIndexed { i, (name, src, expl) ->
            sourceGroup.addView(RadioButton(this).apply {
                text = name; id = 1000 + i; isChecked = (src == selectedSource); setTextColor(0xFFE0E0E0.toInt())
            })
            sourceGroup.addView(hint("     $expl"))
        }
        sourceGroup.setOnCheckedChangeListener { _, id ->
            val i = id - 1000; if (i in sources.indices) selectedSource = sources[i].second
        }
        root.addView(sourceGroup)

        root.addView(h2("3. Nota (ce microfon fizic e — ex. „Rode-ME-C”)"))
        noteEdit = EditText(this).apply { setText("mic"); setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF888888.toInt()) }
        root.addView(noteEdit)

        recBtn = Button(this).apply { text = "🔴 Inregistreaza 20s"; setOnClickListener { startTest() } }
        root.addView(recBtn)
        levelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30))
        }
        root.addView(levelBar)
        routedTv = small(""); root.addView(routedTv)
        statusTv = small(""); root.addView(statusTv)

        root.addView(h2("Teste salvate (/BioEcho/mic_test/)"))
        filesTv = small(""); root.addView(filesTv)

        root.addView(Button(this).apply { text = "← Inapoi"; setOnClickListener { finish() } })
        setContentView(scroll)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 21)
        refreshDevices(); refreshFiles()
    }

    private fun refreshDevices() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        deviceGroup.removeAllViews()
        deviceGroup.addView(RadioButton(this).apply { text = "Auto (Android alege)"; id = 500; isChecked = true; setTextColor(0xFFE0E0E0.toInt()) })
        selectedDevice = null
        inputs.forEachIndexed { i, d ->
            deviceGroup.addView(RadioButton(this).apply { text = deviceLabel(d); id = 501 + i; setTextColor(0xFFE0E0E0.toInt()) })
        }
        deviceGroup.setOnCheckedChangeListener { _, id -> selectedDevice = if (id == 500) null else inputs.getOrNull(id - 501) }
        statusTv.text = "${inputs.size} intrari gasite. Daca ai conectat un mic USB si nu apare, apasa Reimprospateaza."
    }

    private fun deviceLabel(d: AudioDeviceInfo): String {
        val t = typeName(d.type)
        val rates = if (d.sampleRates.isNotEmpty()) d.sampleRates.joinToString("/") { "${it / 1000}k" } else "orice"
        return "$t · ${d.productName} · id ${d.id} · $rates"
    }
    private fun typeName(t: Int) = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "INTERN"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB-C"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "jack-3.5mm"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        else -> "tip$t"
    }
    private fun typeShort(t: Int) = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "INTERN"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USBC"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "JACK"
        else -> "t$t"
    }

    private fun startTest() {
        if (recording) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            statusTv.text = "⚠ fara permisiune microfon"; return
        }
        val sr = 48000
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { statusTv.text = "⚠ microfon indisponibil"; return }
        val ar = try {
            AudioRecord(selectedSource, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        } catch (e: Exception) { statusTv.text = "⚠ ${e.message?.take(50)}"; return }
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            try { ar.release() } catch (_: Exception) {}
            statusTv.text = "⚠ sursa asta nu se deschide cu micul ales — incearca sursa „Mic”"; return
        }
        val prefOk = selectedDevice?.let { ar.setPreferredDevice(it) }
        try { ar.startRecording() } catch (e: Exception) { ar.release(); statusTv.text = "⚠ start esuat"; return }
        // ADEVARUL: ce microfon inregistreaza efectiv
        val routed = ar.routedDevice
        val routedLbl = routed?.let { deviceLabel(it) } ?: "necunoscut"
        val match = routed != null && selectedDevice != null && routed.id == selectedDevice!!.id
        routedTv.text = "🎯 RUTARE CONFIRMATA: $routedLbl" +
            (if (selectedDevice != null)
                "\n   ai cerut: ${deviceLabel(selectedDevice!!)} · setPreferred=$prefOk" +
                (if (match) " ✓ potrivire" else " ⚠ NU se potriveste — Android a ignorat selectia!")
            else "\n   (Auto — nu ai fortat un dispozitiv)")
        val srcName = sources.firstOrNull { it.second == selectedSource }?.first ?: "src"
        val note = noteEdit.text.toString().ifBlank { "mic" }.replace(Regex("[^A-Za-z0-9_-]"), "-")
        val rlbl = routed?.type?.let { typeShort(it) } ?: "auto"
        val ts = System.currentTimeMillis()
        val dir = File(Storage.baseDir(this), "mic_test").apply { mkdirs() }
        val out = File(dir, "mictest_${note}_${rlbl}_${srcName}_$ts.wav")
        recording = true; recBtn.isEnabled = false
        recThread = Thread { recordWav(ar, out, sr, 20) }.also { it.start() }
    }

    private fun recordWav(ar: AudioRecord, file: File, sr: Int, seconds: Int) {
        val raf = try { RandomAccessFile(file, "rw") } catch (e: Exception) { finishRec("⚠ nu pot scrie fisierul"); return }
        try {
            writeHeader(raf, sr)
            val target = sr.toLong() * seconds
            var written = 0L
            val buf = ShortArray(2048)
            val bytes = ByteArray(buf.size * 2)
            while (recording && written < target) {
                val n = ar.read(buf, 0, buf.size)
                if (n <= 0) continue
                var bi = 0; var sum = 0.0
                for (i in 0 until n) {
                    val v = buf[i].toInt(); sum += (v * v).toDouble()
                    bytes[bi++] = (v and 0xff).toByte(); bytes[bi++] = ((v shr 8) and 0xff).toByte()
                }
                raf.write(bytes, 0, n * 2); written += n
                val rms = sqrt(sum / n); val dbfs = if (rms > 0) 20 * log10(rms / 32768.0) else -90.0
                val pct = (((dbfs + 60) / 60) * 100).coerceIn(0.0, 100.0).toInt()
                val remain = ((target - written) / sr).toInt()
                handler.post { levelBar.progress = pct; statusTv.text = "🔴 inregistrez… ${remain}s ramase · nivel ${dbfs.toInt()} dBFS" }
            }
            val dataLen = written * 2
            raf.seek(4); writeIntLE(raf, (36 + dataLen).toInt())
            raf.seek(40); writeIntLE(raf, dataLen.toInt())
        } catch (_: Exception) {
        } finally {
            try { raf.close() } catch (_: Exception) {}
            try { ar.stop() } catch (_: Exception) {}
            try { ar.release() } catch (_: Exception) {}
            finishRec("✓ salvat: ${file.name}")
        }
    }

    private fun finishRec(msg: String) {
        recording = false
        handler.post { recBtn.isEnabled = true; levelBar.progress = 0; statusTv.text = msg; refreshFiles() }
    }

    private fun refreshFiles() {
        val dir = File(Storage.baseDir(this), "mic_test")
        val fs = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        filesTv.text = if (fs.isEmpty()) "(niciun test inca)" else fs.joinToString("\n") { "• ${it.name}  (${it.length() / 1024} KB)" }
    }

    private fun writeHeader(raf: RandomAccessFile, sr: Int) {
        val ch = 1; val bps = 2; val byteRate = sr * ch * bps
        raf.write("RIFF".toByteArray()); writeIntLE(raf, 36)
        raf.write("WAVE".toByteArray()); raf.write("fmt ".toByteArray())
        writeIntLE(raf, 16); writeShortLE(raf, 1); writeShortLE(raf, ch)
        writeIntLE(raf, sr); writeIntLE(raf, byteRate); writeShortLE(raf, ch * bps); writeShortLE(raf, 16)
        raf.write("data".toByteArray()); writeIntLE(raf, 0)
    }
    private fun writeIntLE(raf: RandomAccessFile, v: Int) = raf.write(byteArrayOf(
        (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(), ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte()))
    private fun writeShortLE(raf: RandomAccessFile, v: Int) = raf.write(byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte()))

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun h1(s: String) = TextView(this).apply { text = s; textSize = 20f; setTextColor(0xFFFFFFFF.toInt()); setPadding(0, 0, 0, 6) }
    private fun h2(s: String) = TextView(this).apply { text = s; textSize = 16f; setTextColor(0xFF80CBC4.toInt()); setPadding(0, 22, 0, 8) }
    private fun hint(s: String) = TextView(this).apply { text = s; textSize = 12f; setTextColor(0xFF9E9E9E.toInt()); setPadding(0, 0, 0, 6) }
    private fun small(s: String) = TextView(this).apply { text = s; textSize = 13f; setTextColor(0xFFE0E0E0.toInt()); setPadding(0, 4, 0, 4) }

    override fun onPause() { super.onPause(); recording = false }
}
