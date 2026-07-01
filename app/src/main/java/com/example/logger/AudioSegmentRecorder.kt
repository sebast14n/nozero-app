package com.example.logger

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Captura unui segment audio LOSSLESS din microfon via AudioRecord (32-bit FLOAT cu fallback la
 * 16-bit, sursa UNPROCESSED). Float-ul pastreaza sunetele slabe de noapte fara cuantizare/clipping.
 *
 * - WAV (implicit): scriere directa a PCM/float + header WAV — 100% fiabil, identic cu Song Meter.
 * - FLAC (experimental): PCM -> encoder MediaCodec "audio/flac" -> fisier .flac (header din CSD).
 *   Daca encoderul FLAC nu poate fi pornit pe dispozitiv, cade automat pe WAV (nu se pierde nimic).
 *
 * O instanta = un segment. start() porneste captura intr-un thread; stop() finalizeaza fisierul.
 */
class AudioSegmentRecorder(
    private val sampleRate: Int = 48000,
    private val channels: Int = 1,
    private val preferFlac: Boolean = false,
    private val preferFloat: Boolean = false,   // 32-bit float OPT-IN (default 16-bit; podeaua e analogica, nu cuantizare)
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var ar: AudioRecord? = null
    private var bytesPerSample = 2          // 2 = PCM 16-bit, 4 = float 32-bit
    private var isFloat = false             // true cand inregistram pe 32-bit float (WAV)
    var outFile: File? = null; private set

    /** Porneste captura. Intoarce fisierul efectiv scris (.flac sau .wav), sau null la esec. */
    fun start(baseNoExt: File): File? {
        val chMask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val unp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
        // WAV: preferam 32-bit FLOAT — gama dinamica mare, sunetele slabe de noapte nu mai cad in
        // podeaua de cuantizare a 16-bit (si fara clipping pe sunetele tari). FLAC (experimental)
        // ramane pe 16-bit (encoderul cere int). Ordine fallback:
        // float/UNPROCESSED -> float/MIC -> 16bit/UNPROCESSED -> 16bit/MIC.
        var rec: AudioRecord? = null
        if (preferFloat && !preferFlac && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            rec = makeRecord(AudioFormat.ENCODING_PCM_FLOAT, unp, chMask)
                ?: makeRecord(AudioFormat.ENCODING_PCM_FLOAT, MediaRecorder.AudioSource.MIC, chMask)
            if (rec != null) { isFloat = true; bytesPerSample = 4 }
        }
        if (rec == null) {
            rec = makeRecord(AudioFormat.ENCODING_PCM_16BIT, unp, chMask)
                ?: makeRecord(AudioFormat.ENCODING_PCM_16BIT, MediaRecorder.AudioSource.MIC, chMask)
            isFloat = false; bytesPerSample = 2
        }
        ar = rec
        val a = ar ?: return null   // makeRecord intoarce doar instante INITIALIZED

        // pregateste encoderul FLAC daca e cerut; altfel WAV
        val codec = if (preferFlac) tryCreateFlac() else null
        val readChunk = 8192
        running = true
        try { a.startRecording() } catch (e: Exception) { stop(); return null }

        if (codec != null) {
            outFile = File(baseNoExt.parentFile, baseNoExt.name + ".flac")
            thread = Thread { runFlac(a, codec, outFile!!, readChunk) }.also { it.start() }
        } else {
            outFile = File(baseNoExt.parentFile, baseNoExt.name + ".wav")
            thread = Thread { runWav(a, outFile!!, readChunk) }.also { it.start() }
        }
        return outFile
    }

    fun stop() {
        running = false
        try { thread?.join(3000) } catch (_: Exception) {}
        thread = null
        try { ar?.stop() } catch (_: Exception) {}
        try { ar?.release() } catch (_: Exception) {}
        ar = null
    }

    /** Creeaza un AudioRecord cu encoding+sursa date; intoarce-l doar daca s-a INITIALIZAT, altfel null. */
    private fun makeRecord(enc: Int, src: Int, chMask: Int): AudioRecord? {
        return try {
            val mb = AudioRecord.getMinBufferSize(sampleRate, chMask, enc)
            if (mb <= 0) return null
            val r = AudioRecord(src, sampleRate, chMask, enc, mb * 4)
            if (r.state == AudioRecord.STATE_INITIALIZED) r
            else { try { r.release() } catch (_: Exception) {}; null }
        } catch (e: Exception) { null }
    }

    // ── WAV ──
    private fun runWav(a: AudioRecord, file: File, readChunk: Int) {
        val raf = try { RandomAccessFile(file, "rw") } catch (e: Exception) { return }
        try {
            writeWavHeader(raf, 0)         // placeholder, patch la final
            var dataLen = 0L
            if (isFloat) {
                // citim float-uri si le scriem ca IEEE float32 little-endian (corect pe orice device)
                val fbuf = FloatArray(readChunk / 4)
                val bytes = ByteArray(readChunk)
                while (running) {
                    val n = a.read(fbuf, 0, fbuf.size, AudioRecord.READ_BLOCKING)
                    if (n > 0) {
                        var bi = 0
                        for (i in 0 until n) {
                            val v = java.lang.Float.floatToIntBits(fbuf[i])
                            bytes[bi++] = (v and 0xff).toByte()
                            bytes[bi++] = ((v ushr 8) and 0xff).toByte()
                            bytes[bi++] = ((v ushr 16) and 0xff).toByte()
                            bytes[bi++] = ((v ushr 24) and 0xff).toByte()
                        }
                        raf.write(bytes, 0, n * 4); dataLen += n * 4L
                    }
                }
            } else {
                val buf = ByteArray(readChunk)
                while (running) {
                    val n = a.read(buf, 0, buf.size)
                    if (n > 0) { raf.write(buf, 0, n); dataLen += n }
                }
            }
            // patch dimensiuni
            raf.seek(4);  writeIntLE(raf, (36 + dataLen).toInt())
            raf.seek(40); writeIntLE(raf, dataLen.toInt())
        } catch (_: Exception) {
        } finally { try { raf.close() } catch (_: Exception) {} }
    }

    private fun writeWavHeader(raf: RandomAccessFile, dataLen: Int) {
        val fmtCode = if (isFloat) 3 else 1        // 3 = WAVE_FORMAT_IEEE_FLOAT, 1 = PCM
        val bits = bytesPerSample * 8
        val byteRate = sampleRate * channels * bytesPerSample
        raf.write("RIFF".toByteArray()); writeIntLE(raf, 36 + dataLen)
        raf.write("WAVE".toByteArray()); raf.write("fmt ".toByteArray())
        writeIntLE(raf, 16); writeShortLE(raf, fmtCode); writeShortLE(raf, channels)
        writeIntLE(raf, sampleRate); writeIntLE(raf, byteRate)
        writeShortLE(raf, channels * bytesPerSample); writeShortLE(raf, bits)
        raf.write("data".toByteArray()); writeIntLE(raf, dataLen)
    }

    private fun writeIntLE(raf: RandomAccessFile, v: Int) {
        raf.write(byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
            ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte()))
    }
    private fun writeShortLE(raf: RandomAccessFile, v: Int) {
        raf.write(byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte()))
    }

    // ── FLAC ──
    private fun tryCreateFlac(): MediaCodec? {
        return try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, sampleRate, channels)
            fmt.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
            fmt.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            c
        } catch (e: Exception) { null }
    }

    private fun runFlac(a: AudioRecord, codec: MediaCodec, file: File, readChunk: Int) {
        val fos = try { FileOutputStream(file) } catch (e: Exception) { return }
        val info = MediaCodec.BufferInfo()
        var headerWritten = false
        var totalSamples = 0L
        var eosSent = false
        val pcm = ByteArray(readChunk)
        try {
            var sawEos = false
            while (!sawEos) {
                if (running) {
                    val n = a.read(pcm, 0, pcm.size)
                    if (n > 0) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val ib = codec.getInputBuffer(inIdx)
                            ib?.clear()
                            val take = if (ib != null) minOf(n, ib.remaining()) else n
                            ib?.put(pcm, 0, take)
                            val ptsUs = totalSamples * 1_000_000L / sampleRate
                            codec.queueInputBuffer(inIdx, 0, take, ptsUs, 0)
                            totalSamples += take / (2L * channels)
                        }
                    }
                } else if (!eosSent) {
                    // semnaleaza EOS o SINGURA data, apoi doar dreneaza pana la EOS pe iesire
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        codec.queueInputBuffer(inIdx, 0, 0,
                            totalSamples * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosSent = true
                    }
                }
                // dreneaza iesirea
                while (true) {
                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    if (outIdx < 0) break
                    val ob = codec.getOutputBuffer(outIdx)
                    if (ob != null && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        ob.position(info.offset); ob.get(bytes)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            if (!headerWritten) { fos.write(flacHeader(bytes)); headerWritten = true }
                        } else {
                            fos.write(bytes)
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { sawEos = true; break }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { fos.flush(); fos.close() } catch (_: Exception) {}
        }
    }

    /** Construieste header-ul de stream FLAC din CSD. Defensiv: daca CSD incepe deja cu "fLaC",
     *  e gata; altfel adauga marcajul + antetul metadata-block STREAMINFO (34 octeti). */
    private fun flacHeader(csd: ByteArray): ByteArray {
        val marker = byteArrayOf(0x66.toByte(), 0x4C.toByte(), 0x61.toByte(), 0x43.toByte()) // "fLaC"
        if (csd.size >= 4 && csd[0] == marker[0] && csd[1] == marker[1] &&
            csd[2] == marker[2] && csd[3] == marker[3]) return csd
        // STREAMINFO: ultim bloc metadata (0x80) | tip 0; lungime 34 = 0x000022
        val blockHdr = byteArrayOf(0x80.toByte(), 0x00.toByte(), 0x00.toByte(), 0x22.toByte())
        return marker + blockHdr + csd
    }
}
