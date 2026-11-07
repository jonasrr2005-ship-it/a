package de.rhythm.tiles

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer

/**
 * KI-Trennung der Instrumente mit HT-Demucs (6 Spuren, Meta Research, MIT-Lizenz),
 * als ONNX-Export von StemSplit (https://github.com/StemSplit/demucs-onnx, MIT-Lizenz).
 *
 * Das Modell erwartet Stereo mit 44,1 kHz in festen Abschnitten von 7,8 s
 * (Eingang „mix“ [1, 2, 343980]) und liefert sechs getrennte Spuren
 * (Ausgang „stems“ [1, 6, 2, 343980]) in der Reihenfolge
 * Schlagzeug, Bass, Sonstiges, Gesang, Gitarre, Klavier.
 */
object StemSeparator {

    const val STEM_COUNT = 6
    private const val SEGMENT = 343_980
    private const val OVERLAP = SEGMENT / 4
    private const val STRIDE = SEGMENT - OVERLAP

    private const val MODEL_URL =
        "https://huggingface.co/StemSplitio/htdemucs-6s-onnx/resolve/main/htdemucs_6s_fp16weights.onnx"
    private const val MODEL_FILE = "htdemucs_6s_fp16weights.onnx"

    // ---------------------------------------------------------------- Modell-Datei

    fun modelFile(context: Context) = File(File(context.filesDir, "models").apply { mkdirs() }, MODEL_FILE)

    fun isModelInstalled(context: Context) = modelFile(context).let { it.exists() && it.length() > 10_000_000 }

    fun deleteModel(context: Context) {
        closeSession()
        modelFile(context).delete()
    }

    /** Lädt das Modell herunter (einmalig, über 100 MB). [onProgress] erhält geladene und gesamte Bytes. */
    fun downloadModel(context: Context, checkCancel: () -> Unit, onProgress: (Long, Long) -> Unit) {
        val target = modelFile(context)
        val part = File(target.path + ".part")
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode != 200) throw IllegalStateException("Download fehlgeschlagen (HTTP ${conn.responseCode})")
            val total = conn.contentLengthLong
            var loaded = 0L
            var lastReport = 0L
            conn.inputStream.use { input ->
                FileOutputStream(part).use { output ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        checkCancel()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        loaded += n
                        if (loaded - lastReport > 512 * 1024) {
                            lastReport = loaded
                            onProgress(loaded, total)
                        }
                    }
                }
            }
            if (total > 0 && loaded != total) throw IllegalStateException("Download unvollständig")
            if (!part.renameTo(target)) throw IllegalStateException("Modell konnte nicht gespeichert werden")
        } finally {
            conn.disconnect()
            part.delete()
        }
    }

    // ---------------------------------------------------------------- Inferenz

    private var session: OrtSession? = null

    @Synchronized
    private fun session(context: Context): OrtSession {
        session?.let { return it }
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        }
        return OrtEnvironment.getEnvironment().createSession(modelFile(context).path, options).also { session = it }
    }

    @Synchronized
    private fun closeSession() {
        session?.close()
        session = null
    }

    /** Empfängt die fertig berechneten Samples einer Spur der Reihe nach. */
    fun interface StemSink {
        fun write(left: Float, right: Float)
    }

    /**
     * Trennt [pcm] in sechs Spuren. Die Abschnitte überlappen sich um ein Viertel und werden
     * mit Dreiecksfenstern überblendet (wie in Demucs selbst). Fertige Samples gehen sofort an
     * [sinks] (Index = Spur), damit nie der ganze Song je Spur im Speicher liegen muss.
     */
    fun separate(
        context: Context,
        pcm: AudioDecoder.StereoPcm,
        sinks: Array<StemSink?>,
        checkCancel: () -> Unit,
        onProgress: (chunk: Int, chunks: Int) -> Unit,
    ) {
        val env = OrtEnvironment.getEnvironment()
        val sess = session(context)
        val total = pcm.frames
        val chunks = maxOf(1, (total + STRIDE - 1) / STRIDE)

        val window = FloatArray(SEGMENT) { 1f }
        for (i in 0 until OVERLAP) {
            val fade = i / (OVERLAP - 1).toFloat()
            window[i] = fade
            window[SEGMENT - 1 - i] = fade
        }

        // Noch nicht fertige Überlappung aus dem vorigen Abschnitt, je Spur und Kanal
        val carry = Array(STEM_COUNT) { Array(2) { FloatArray(OVERLAP) } }
        val carryWeight = FloatArray(OVERLAP)
        var carryLen = 0

        val input = FloatArray(2 * SEGMENT)
        for (c in 0 until chunks) {
            checkCancel()
            onProgress(c, chunks)
            val start = c * STRIDE
            val len = minOf(SEGMENT, total - start)
            val last = c == chunks - 1
            input.fill(0f)
            for (i in 0 until len) {
                input[i] = pcm.data[(start + i) * 2] / 32768f
                input[SEGMENT + i] = pcm.data[(start + i) * 2 + 1] / 32768f
            }

            val stems: FloatBuffer = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 2, SEGMENT.toLong())).use { tensor ->
                sess.run(mapOf("mix" to tensor)).use { result ->
                    (result.get(0) as OnnxTensor).floatBuffer
                }
            }

            // Bis hierher ist alles fertig; dahinter überlappt der nächste Abschnitt
            val finalUpTo = if (last) len else minOf(STRIDE, len)
            for (i in 0 until len) {
                var weight = window[i]
                if (i < carryLen) weight += carryWeight[i]
                if (i >= finalUpTo) carryWeight[i - finalUpTo] = window[i]
                for (s in 0 until STEM_COUNT) {
                    val sink = sinks[s] ?: continue
                    var l = stems.get((s * 2) * SEGMENT + i) * window[i]
                    var r = stems.get((s * 2 + 1) * SEGMENT + i) * window[i]
                    if (i < carryLen) {
                        l += carry[s][0][i]
                        r += carry[s][1][i]
                    }
                    if (i < finalUpTo) {
                        val w = maxOf(weight, 1e-8f)
                        sink.write(l / w, r / w)
                    } else {
                        carry[s][0][i - finalUpTo] = l
                        carry[s][1][i - finalUpTo] = r
                    }
                }
            }
            carryLen = len - finalUpTo
        }
        onProgress(chunks, chunks)
    }

    // ---------------------------------------------------------------- Hilfsklassen

    /** Mischt eine Spur auf Mono herunter und reduziert sie auf ~11 kHz für die Beat-Erkennung. */
    class AnalysisSink : StemSink {
        private val out = AudioDecoder.FloatList()
        private val factor = AudioDecoder.MODEL_RATE / AudioDecoder.ANALYSIS_RATE
        private var acc = 0f
        private var count = 0
        override fun write(left: Float, right: Float) {
            acc += (left + right) * 0.5f
            if (++count == factor) { out.add(acc / factor); acc = 0f; count = 0 }
        }
        fun toPcm() = AudioDecoder.Pcm(out.toArray(), AudioDecoder.MODEL_RATE / factor)
    }

    /** Schreibt eine Spur als 16-Bit-Stereo-WAV mit 44,1 kHz. */
    class WavSink(private val file: File) : StemSink, AutoCloseable {
        private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
        private var frames = 0L

        init {
            out.write(ByteArray(44)) // Platz für den Kopf, wird in close() ausgefüllt
        }

        override fun write(left: Float, right: Float) {
            writeSample(left)
            writeSample(right)
            frames++
        }

        private fun writeSample(v: Float) {
            val s = (v.coerceIn(-1f, 1f) * 32767f).toInt()
            out.write(s and 0xFF)
            out.write((s shr 8) and 0xFF)
        }

        override fun close() {
            out.close()
            val dataBytes = frames * 4
            RandomAccessFile(file, "rw").use { raf ->
                val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                h.put("RIFF".toByteArray()); h.putInt((36 + dataBytes).toInt()); h.put("WAVE".toByteArray())
                h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(2)
                h.putInt(AudioDecoder.MODEL_RATE); h.putInt(AudioDecoder.MODEL_RATE * 4); h.putShort(4); h.putShort(16)
                h.put("data".toByteArray()); h.putInt(dataBytes.toInt())
                raf.seek(0)
                raf.write(h.array())
            }
        }
    }

    /** Sendet jedes Sample an mehrere Empfänger (z. B. Analyse und WAV-Datei). */
    class TeeSink(private vararg val sinks: StemSink) : StemSink {
        override fun write(left: Float, right: Float) = sinks.forEach { it.write(left, right) }
    }
}
