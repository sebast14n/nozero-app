package com.example.logger

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile

/**
 * Upload DIRECT de pe cardul SD al unui senzor (Song Meter). Fluxul:
 *  1. alegi folderul cardului (SAF);
 *  2. app-ul scaneaza fisierele .wav → SERIA + PERIOADA din numele fisierelor, LOCATIA din metadatele
 *     GUANO din interiorul WAV-ului (Loc Position);
 *  3. VERIFICI (serie, perioada, locatie) + alegi PROIECTUL;
 *  4. urci — dar intai verifica hash-urile pe server, ca sa NU re-urce ce e deja urcat (cardul poate
 *     contine date deja incarcate).
 * Datele intra in contul tau (spatiu personal), etichetate cu proiectul; le trimiti spre analiza
 * ulterior din platforma. NU se sterge nimic de pe card.
 */
class CardUploadActivity : AppCompatActivity() {

    private lateinit var uploadManager: UploadManager
    private lateinit var tvStatus: TextView
    private lateinit var box: LinearLayout
    private var project: String? = null
    private var busy = false

    private data class CardFile(val uri: Uri, val name: String, val serial: String?,
                                val recordedAt: String?, val size: Long)

    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) { tvStatus.text = "Anulat. Apasă „Alege card” ca să reîncerci."; return@registerForActivityResult }
        scanCard(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uploadManager = UploadManager(this)
        val root = ScrollView(this).apply { setBackgroundColor(0xFF121212.toInt()) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 24)
        }
        col.addView(TextView(this).apply {
            text = "💾 Upload de pe card SD"; textSize = 20f; setTextColor(0xFFFFFFFF.toInt()); setPadding(0, 0, 0, 8)
        })
        tvStatus = TextView(this).apply { setTextColor(0xFF90CAF9.toInt()); textSize = 13f; setPadding(0, 0, 0, 12) }
        col.addView(tvStatus)
        col.addView(Button(this).apply {
            text = "📂 Alege cardul (folderul)"
            setOnClickListener { if (!busy) pickTree.launch(null) }
        })
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 16, 0, 0) }
        col.addView(box)
        root.addView(col)
        setContentView(root)

        if (uploadManager.jwtToken.isNullOrBlank()) {
            tvStatus.text = "⚠ Neautentificat — scanează QR pe pagina principală."
        } else {
            tvStatus.text = "Bagă cardul în telefon și apasă „Alege cardul”."
        }
    }

    // ── 1. Scanare card ──
    private fun scanCard(treeUri: Uri) {
        busy = true; box.removeAllViews()
        tvStatus.text = "Scanez cardul…"
        Thread {
            val root = DocumentFile.fromTreeUri(this, treeUri)
            val wavs = ArrayList<CardFile>()
            collectWavs(root, wavs, 0)
            // serie + timestamp din numele fisierelor: <SERIE>_<YYYYMMDD>_<HHMMSS>.wav
            val serials = LinkedHashSet<String>()
            var dMin: String? = null; var dMax: String? = null
            var total = 0L
            for (f in wavs) {
                f.serial?.let { serials.add(it) }
                val d = f.name.substringAfter("_", "").take(8)
                if (d.length == 8) { if (dMin == null || d < dMin!!) dMin = d; if (dMax == null || d > dMax!!) dMax = d }
                total += f.size
            }
            // locatia din GUANO: primul + ultimul fisier (detectam daca variaza)
            val locFirst = wavs.firstOrNull()?.let { readGuanoLoc(it.uri, it.size) }
            val locLast = if (wavs.size > 1) wavs.last().let { readGuanoLoc(it.uri, it.size) } else locFirst
            val varies = locFirst != null && locLast != null && locFirst != locLast

            runOnUiThread {
                busy = false
                if (wavs.isEmpty()) { tvStatus.text = "Niciun fișier .wav în folderul ales. Alege folderul cardului sau „Data”."; return@runOnUiThread }
                showSummary(wavs, serials.toList(), dMin, dMax, locFirst, varies, total)
            }
        }.start()
    }

    private fun collectWavs(dir: DocumentFile?, out: ArrayList<CardFile>, depth: Int) {
        if (dir == null || depth > 4 || out.size > 20000) return
        for (f in dir.listFiles()) {
            if (f.isDirectory) collectWavs(f, out, depth + 1)
            else {
                val nm = f.name ?: continue
                if (!nm.endsWith(".wav", true)) continue
                val parts = nm.removeSuffix(".wav").removeSuffix(".WAV").split("_")
                val serial = if (parts.size >= 3) parts[0] else null
                val rec = if (parts.size >= 3 && parts[1].length == 8 && parts[2].length >= 6) {
                    val d = parts[1]; val t = parts[2]
                    "${d.substring(0,4)}-${d.substring(4,6)}-${d.substring(6,8)}T${t.substring(0,2)}:${t.substring(2,4)}:${t.substring(4,6)}"
                } else null
                out.add(CardFile(f.uri, nm, serial, rec, f.length()))
            }
        }
    }

    /** Loc Position din chunk-ul GUANO (cap sau coada fisierului). null daca lipseste sau 0,0. */
    private fun readGuanoLoc(uri: Uri, size: Long): Pair<Double, Double>? {
        fun scan(bytes: ByteArray, n: Int): Pair<Double, Double>? {
            val txt = String(bytes, 0, n, Charsets.ISO_8859_1)
            val m = Regex("Loc Position:\\s*([-\\d.]+)\\s+([-\\d.]+)").find(txt) ?: return null
            val la = m.groupValues[1].toDoubleOrNull() ?: return null
            val lo = m.groupValues[2].toDoubleOrNull() ?: return null
            return if (la == 0.0 && lo == 0.0) null else Pair(la, lo)
        }
        return try {
            val cap = 262144
            contentResolver.openInputStream(uri)?.use { ins ->
                val head = ByteArray(cap); var off = 0
                while (off < cap) { val r = ins.read(head, off, cap - off); if (r < 0) break; off += r }
                scan(head, off)
            }?.let { return it }
            if (size > cap) contentResolver.openInputStream(uri)?.use { ins ->
                var toSkip = size - cap; while (toSkip > 0) { val s = ins.skip(toSkip); if (s <= 0) break; toSkip -= s }
                val tail = ByteArray(cap); var off = 0
                while (off < cap) { val r = ins.read(tail, off, cap - off); if (r < 0) break; off += r }
                scan(tail, off)
            } else null
        } catch (_: Exception) { null }
    }

    // ── 2. Rezumat verificare ──
    private fun showSummary(files: List<CardFile>, serials: List<String>, dMin: String?, dMax: String?,
                            loc: Pair<Double, Double>?, varies: Boolean, total: Long) {
        box.removeAllViews()
        tvStatus.text = "✓ Card scanat. Verifică datele înainte de upload."
        fun fmtDate(d: String?) = if (d != null && d.length == 8) "${d.substring(6,8)}.${d.substring(4,6)}.${d.substring(0,4)}" else "?"
        fun line(label: String, value: String, warn: Boolean = false) = TextView(this).apply {
            text = "$label  $value"; textSize = 14f
            setTextColor(if (warn) 0xFFFFB74D.toInt() else 0xFFE0E0E0.toInt()); setPadding(0, 6, 0, 6)
        }
        box.addView(line("🎙 Senzor:", if (serials.isEmpty()) "(necunoscut)" else serials.joinToString(", ")))
        box.addView(line("📅 Perioada:", "${fmtDate(dMin)} → ${fmtDate(dMax)}"))
        box.addView(line("📁 Fișiere:", "${files.size} · ${Storage.humanSize(total)}"))
        if (loc != null) {
            box.addView(line("📍 Locație:", "%.5f, %.5f".format(loc.first, loc.second)))
            if (varies) box.addView(line("⚠", "Locația diferă în timpul cardului — verifică (config veche?).", true))
        } else {
            box.addView(line("📍 Locație:", "nu e în metadate (0,0 sau lipsă)", true))
        }

        val btnProj = Button(this).apply {
            text = "📂 Proiect: (alege)"; setPadding(0, 12, 0, 0)
            setOnClickListener { chooseProject { slug, name -> project = slug; text = "📂 Proiect: $name" } }
        }
        box.addView(btnProj)

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        val prog = TextView(this).apply { setTextColor(0xFFB0BEC5.toInt()); textSize = 12f; visibility = View.GONE; setPadding(0, 8, 0, 0) }
        box.addView(bar); box.addView(prog)

        box.addView(Button(this).apply {
            text = "⬆ Urcă în proiect"
            setOnClickListener {
                if (busy) return@setOnClickListener
                if (project == null) { toast("Alege întâi proiectul"); return@setOnClickListener }
                doUpload(files, loc, bar, prog)
            }
        })
    }

    // ── 3. Alegere proiect (reia selectorul din #3) ──
    private fun chooseProject(onPick: (String, String) -> Unit) {
        tvStatus.text = "Încarc lista proiectelor…"
        Thread {
            val orgs = uploadManager.fetchOrgs()
            runOnUiThread {
                tvStatus.text = "✓ Card scanat. Verifică datele înainte de upload."
                if (orgs.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("Nu pot încărca proiectele")
                        .setMessage(uploadManager.lastFetchError ?: "Motiv necunoscut.")
                        .setPositiveButton("OK", null).show()
                    return@runOnUiThread
                }
                val prefs = getSharedPreferences("bioecho_prefs", MODE_PRIVATE)
                val last = prefs.getString("upload_project", null)
                val names = orgs.map { it.name }.toTypedArray()
                var sel = orgs.indexOfFirst { it.slug == last }.let { if (it >= 0) it else 0 }
                AlertDialog.Builder(this).setTitle("În ce proiect urci?")
                    .setSingleChoiceItems(names, sel) { _, w -> sel = w }
                    .setPositiveButton("Alege") { _, _ ->
                        val o = orgs[sel]; prefs.edit().putString("upload_project", o.slug).apply(); onPick(o.slug, o.name)
                    }
                    .setNegativeButton("Anulează", null).show()
            }
        }.start()
    }

    // ── 4. Upload cu dedup ──
    private fun doUpload(files: List<CardFile>, loc: Pair<Double, Double>?, bar: ProgressBar, prog: TextView) {
        busy = true
        runOnUiThread {
            bar.visibility = View.VISIBLE; prog.visibility = View.VISIBLE
            bar.isIndeterminate = false; bar.max = 100; bar.progress = 0
            prog.text = "Verific ce e deja urcat… 0/${files.size}"
        }
        Thread {
            // 4a. hash local pentru fiecare fisier -> care exista deja pe server (nu le re-urcam).
            //     Calcularea MD5 citeste fiecare fisier integral de pe cardul SD => poate dura;
            //     aratam progres pe fisier ca sa nu para inghetat.
            val hashByFile = HashMap<String, String>()
            files.forEachIndexed { i, f ->
                runOnUiThread {
                    prog.text = "Verific ${i + 1}/${files.size} · ${f.name}"
                    bar.progress = (i * 100 / files.size.coerceAtLeast(1))
                }
                try { hashByFile[f.name] = uploadManager.md5 { contentResolver.openInputStream(f.uri)!! } } catch (_: Exception) {}
            }
            runOnUiThread { prog.text = "Verific pe server…"; bar.progress = 100 }
            val existing = uploadManager.existingHashes(hashByFile.values.toList())
            val toUpload = files.filter { hashByFile[it.name]?.let { h -> h !in existing } ?: true }
            val already = files.size - toUpload.size

            var ok = 0; var skip = 0; var fail = 0
            toUpload.forEachIndexed { idx, f ->
                runOnUiThread { prog.text = "Urc ${idx + 1}/${toUpload.size} · ${f.name}  (deja urcate: $already)" }
                val st = uploadManager.uploadStream(
                    f.name, { contentResolver.openInputStream(f.uri)!! }, project, f.recordedAt,
                    loc?.first, loc?.second
                ) { written ->
                    runOnUiThread {
                        val pct = if (f.size > 0) (written * 100 / f.size).toInt() else 0
                        bar.progress = pct
                    }
                }
                when (st) {
                    UploadManager.OneStatus.SUCCESS -> ok++
                    UploadManager.OneStatus.SKIPPED -> skip++
                    UploadManager.OneStatus.FAILED -> fail++
                }
            }
            runOnUiThread {
                busy = false; bar.visibility = View.GONE
                prog.text = "✓ Gata: $ok urcate · ${already + skip} deja existau" + if (fail > 0) " · $fail eșuate" else ""
                toast("✓ $ok urcate · ${already + skip} deja existau")
            }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
