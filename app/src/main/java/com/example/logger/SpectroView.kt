package com.example.logger

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Cascada (waterfall) a spectrului — ca in BirdNET Live / Merlin: timpul curge spre stanga,
 * frecventa e pe verticala (0 jos .. FMAX sus), intensitatea e culoare.
 *
 * Firul audio cheama [push] cu esantioanele brute; FFT-ul se face acolo (nu pe UI). Rezultatul
 * (o coloana de [ROWS] intensitati) e trimis pe UI, care scrie coloana in bitmap si redeseneaza.
 * Bitmap-ul e un inel: coloana curenta inainteaza, la desenare se lipesc cele doua bucati — fara
 * sa mutam pixeli la fiecare cadru.
 *
 * Costul: un FFT de 1024 puncte la ~10 coloane/s. Neglijabil fata de BirdNET.
 */
class SpectroView(ctx: Context) : View(ctx) {

    companion object {
        const val N = 1024           // puncte FFT -> 512 benzi; la 48 kHz o banda = 46,9 Hz
        const val ROWS = 256         // benzile aratate: 0 .. 12 kHz (unde canta pasarile)
        const val COLS = 320         // istoric ~32 s la 10 coloane/s
        private const val DB_MIN = -80f
        private const val DB_MAX = -15f
    }

    private val bmp = Bitmap.createBitmap(COLS, ROWS, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.BLACK) }
    private var col = 0
    private val column = IntArray(ROWS)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src1 = Rect(); private val src2 = Rect(); private val dst1 = Rect(); private val dst2 = Rect()
    private val window = FloatArray(N) { 0.5f - 0.5f * cos(2.0 * Math.PI * it / (N - 1)).toFloat() }
    private val re = FloatArray(N); private val im = FloatArray(N)
    private val mags = FloatArray(ROWS)

    /** Din firul audio: ia ultimele [N] esantioane din buf (16-bit), face FFT, trimite coloana pe UI. */
    fun push(buf: ShortArray, n: Int) {
        if (n < N) return
        val off = n - N
        for (i in 0 until N) { re[i] = (buf[off + i] / 32768f) * window[i]; im[i] = 0f }
        fft(re, im)
        for (k in 0 until ROWS) {
            val m = sqrt(re[k] * re[k] + im[k] * im[k]) / (N / 4f)
            val db = 20f * (ln(m + 1e-9f) / ln(10f))
            mags[k] = ((db - DB_MIN) / (DB_MAX - DB_MIN)).coerceIn(0f, 1f)
        }
        val snapshot = mags.copyOf()
        post { writeColumn(snapshot) }
    }

    private fun writeColumn(v: FloatArray) {
        for (k in 0 until ROWS) column[ROWS - 1 - k] = heat(v[k])     // frecventa mare sus
        bmp.setPixels(column, 0, 1, col, 0, 1, ROWS)
        col = (col + 1) % COLS
        invalidate()
    }

    /** negru -> albastru -> verde -> galben -> alb, ca la spectrogramele clasice. */
    private fun heat(t: Float): Int {
        val r: Float; val g: Float; val b: Float
        when {
            t < 0.25f -> { val u = t / 0.25f;            r = 0f;        g = 0f;      b = u * 0.7f }
            t < 0.50f -> { val u = (t - 0.25f) / 0.25f;  r = 0f;        g = u;       b = 0.7f * (1 - u) }
            t < 0.75f -> { val u = (t - 0.50f) / 0.25f;  r = u;         g = 1f;      b = 0f }
            else      -> { val u = (t - 0.75f) / 0.25f;  r = 1f;        g = 1f;      b = u }
        }
        return Color.rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
    }

    override fun onDraw(c: Canvas) {
        // inel: [col..COLS) e partea veche (stanga), [0..col) e partea noua (dreapta)
        val w = width; val h = height
        val oldW = COLS - col
        val x1 = w * oldW / COLS
        src1.set(col, 0, COLS, ROWS); dst1.set(0, 0, x1, h)
        c.drawBitmap(bmp, src1, dst1, paint)
        if (col > 0) { src2.set(0, 0, col, ROWS); dst2.set(x1, 0, w, h); c.drawBitmap(bmp, src2, dst2, paint) }
    }

    /** FFT radix-2 in loc, iterativ. N e putere a lui 2. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {                       // bit-reversal
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t
                }
                i += len
            }
            len = len shl 1
        }
    }
}
