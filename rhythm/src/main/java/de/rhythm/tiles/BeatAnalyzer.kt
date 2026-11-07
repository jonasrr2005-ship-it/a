package de.rhythm.tiles

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Findet Einsätze (Onsets) in der Musik über den „Spectral Flux“: Für jedes kurze
 * Zeitfenster wird das Frequenzspektrum berechnet; wo die Energie in vielen
 * Frequenzen plötzlich ansteigt (Schlagzeug, Anschlag, Gesangseinsatz), liegt ein Onset.
 */
object BeatAnalyzer {

    class Onset(val time: Double, val strength: Float)

    private const val FFT_SIZE = 512
    private const val HOP = 128

    fun detectOnsets(pcm: AudioDecoder.Pcm): List<Onset> {
        val x = pcm.samples
        val rate = pcm.sampleRate.toDouble()
        val frames = if (x.size < FFT_SIZE) 0 else (x.size - FFT_SIZE) / HOP + 1
        if (frames < 3) return emptyList()

        val window = FloatArray(FFT_SIZE) { (0.5 - 0.5 * cos(2 * PI * it / (FFT_SIZE - 1))).toFloat() }
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        val bins = FFT_SIZE / 2
        // Bässe (~20–250 Hz), Mitten (~250–1400 Hz), Höhen (~1,4–5,5 kHz) getrennt auswerten,
        // damit eine Kickdrum nicht von breitbandigen Hi-Hats übertönt wird
        val bands = arrayOf(1 to 12, 12 to 64, 64 to bins)
        var prev = FloatArray(bins)
        var cur = FloatArray(bins)
        val bandFlux = Array(bands.size) { FloatArray(frames) }

        for (f in 0 until frames) {
            val off = f * HOP
            for (i in 0 until FFT_SIZE) {
                re[i] = x[off + i] * window[i]
                im[i] = 0f
            }
            fft(re, im)
            for (b in 1 until bins) {
                cur[b] = ln(1f + 100f * sqrt(re[b] * re[b] + im[b] * im[b]))
            }
            if (f > 0) {
                for ((k, band) in bands.withIndex()) {
                    var sum = 0f
                    for (b in band.first until band.second) {
                        val d = cur[b] - prev[b]
                        if (d > 0) sum += d
                    }
                    bandFlux[k][f] = sum
                }
            }
            val t = prev; prev = cur; cur = t
        }

        // Jedes Band auf sein 98. Perzentil normalisieren (laute und leise Songs gleich behandeln)
        val flux = FloatArray(frames)
        for (bf in bandFlux) {
            val sorted = bf.copyOf().also { it.sort() }
            val norm = maxOf(1e-6f, sorted[(sorted.size * 0.98).toInt().coerceAtMost(sorted.size - 1)])
            for (i in flux.indices) flux[i] += (bf[i] / norm).coerceAtMost(1.5f) / bandFlux.size
        }

        // Adaptive Schwelle: gleitender Mittelwert über ±0,2 s
        val half = (0.2 * rate / HOP).toInt().coerceAtLeast(2)
        val prefix = DoubleArray(frames + 1)
        for (i in 0 until frames) prefix[i + 1] = prefix[i] + flux[i]

        val peakRadius = 3
        val onsets = ArrayList<Onset>()
        for (i in 1 until frames - 1) {
            val v = flux[i]
            val lo = maxOf(0, i - half)
            val hi = minOf(frames, i + half + 1)
            val mean = (prefix[hi] - prefix[lo]) / (hi - lo)
            if (v < mean * 1.3 + 0.06) continue
            var isPeak = true
            for (j in maxOf(0, i - peakRadius)..minOf(frames - 1, i + peakRadius)) {
                if (flux[j] > v || (flux[j] == v && j < i)) { isPeak = false; break }
            }
            if (!isPeak) continue
            val time = (i * HOP + FFT_SIZE / 2) / rate
            onsets.add(Onset(time, (v - mean.toFloat()).coerceAtLeast(0f)))
        }
        return onsets
    }

    /** Iterative Radix-2-FFT (in place). */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
