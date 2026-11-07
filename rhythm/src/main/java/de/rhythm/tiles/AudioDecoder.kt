package de.rhythm.tiles

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Dekodiert eine Audiodatei (MP3, M4A, OGG, FLAC, WAV …) mit den Android-eigenen Codecs.
 * - [decodeMono]: Mono mit ~11 kHz – genug für die Beat-Erkennung
 * - [decodeStereo44k]: Stereo mit 44,1 kHz – das Eingabeformat der KI-Trennung
 */
object AudioDecoder {

    class Pcm(val samples: FloatArray, val sampleRate: Int)

    /** Verschachtelte Stereo-Samples (L, R, L, R …); gültig sind die ersten `frames * 2` Werte. */
    class StereoPcm(val data: ShortArray, val frames: Int)

    const val ANALYSIS_RATE = 11025
    const val MODEL_RATE = 44100
    private const val TIMEOUT_US = 10_000L

    fun decodeMono(context: Context, uri: Uri, onProgress: (Float) -> Unit): Pcm {
        val out = FloatList()
        var factor = 1
        var acc = 0f
        var accCount = 0
        val rate = decode(context, uri, onProgress, { r -> factor = maxOf(1, Math.round(r.toFloat() / ANALYSIS_RATE)) }) { l, r ->
            acc += (l + r) * 0.5f
            if (++accCount == factor) { out.add(acc / factor); acc = 0f; accCount = 0 }
        }
        return Pcm(out.toArray(), rate / factor)
    }

    /** Liefert Stereo mit 44,1 kHz als 16-Bit-Werte. */
    fun decodeStereo44k(context: Context, uri: Uri, maxSeconds: Int, onProgress: (Float) -> Unit): StereoPcm {
        val out = ShortList()
        // Lineare Interpolation, falls der Song nicht in 44,1 kHz vorliegt (meist 48 kHz)
        var step = 1.0
        var pos = 0.0
        var prevL = 0f
        var prevR = 0f
        var index = -1L
        val limit = maxSeconds.toLong() * MODEL_RATE * 2
        decode(context, uri, onProgress, { r -> step = r.toDouble() / MODEL_RATE }) { l, r ->
            index++
            if (index == 0L) { prevL = l; prevR = r; return@decode }
            // Ausgabe-Samples zwischen index-1 und index erzeugen
            while (pos <= index) {
                val frac = (pos - (index - 1)).toFloat()
                out.add(toShort(prevL + (l - prevL) * frac))
                out.add(toShort(prevR + (r - prevR) * frac))
                pos += step
                if (out.size > limit) throw IllegalArgumentException("Song ist länger als ${maxSeconds / 60} Minuten")
            }
            prevL = l; prevR = r
        }
        return StereoPcm(out.data, out.size / 2)
    }

    private fun toShort(v: Float): Short = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

    /**
     * Gemeinsame Dekodier-Schleife. Ruft [onFormat] mit der Abtastrate auf, sobald sie feststeht,
     * und danach [onSample] für jedes Stereo-Sample (Mono wird auf beide Kanäle verteilt).
     * Liefert die Abtastrate.
     */
    private inline fun decode(
        context: Context,
        uri: Uri,
        onProgress: (Float) -> Unit,
        onFormat: (Int) -> Unit,
        onSample: (Float, Float) -> Unit,
    ): Int {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        var trackIndex = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIndex = i
                inputFormat = format
                break
            }
        }
        if (trackIndex < 0 || inputFormat == null) {
            extractor.release()
            throw IllegalArgumentException("Keine Audiospur gefunden")
        }
        extractor.selectTrack(trackIndex)

        val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
            inputFormat.getLong(MediaFormat.KEY_DURATION)
        } else 0L

        val codec = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var floatPcm = false
        var formatKnown = false

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var lastProgress = -1

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            if (durationUs > 0) {
                                val p = (extractor.sampleTime * 100 / durationUs).toInt()
                                if (p != lastProgress) {
                                    lastProgress = p
                                    onProgress(p / 100f)
                                }
                            }
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIndex >= 0 -> {
                        if (!formatKnown) { formatKnown = true; onFormat(sampleRate) }
                        val buffer = codec.getOutputBuffer(outIndex)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.order(ByteOrder.nativeOrder())
                        val ch = maxOf(1, channels)
                        if (floatPcm) {
                            val fb = buffer.asFloatBuffer()
                            while (fb.remaining() >= ch) {
                                val l = fb.get()
                                val r = if (ch > 1) fb.get() else l
                                repeat(ch - minOf(ch, 2)) { fb.get() }
                                onSample(l, r)
                            }
                        } else {
                            val sb = buffer.asShortBuffer()
                            while (sb.remaining() >= ch) {
                                val l = sb.get() / 32768f
                                val r = if (ch > 1) sb.get() / 32768f else l
                                repeat(ch - minOf(ch, 2)) { sb.get() }
                                onSample(l, r)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        onProgress(1f)
        return sampleRate
    }

    class FloatList {
        private var data = FloatArray(1 shl 16)
        var size = 0
            private set
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }
        fun toArray(): FloatArray = data.copyOf(size)
    }

    private class ShortList {
        var data = ShortArray(1 shl 20)
            private set
        var size = 0
            private set
        fun add(v: Short) {
            if (size == data.size) data = data.copyOf(size + size / 2)
            data[size++] = v
        }
    }
}
