package de.rhythm.tiles

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Dekodiert eine Audiodatei (MP3, M4A, OGG, FLAC, WAV …) mit den Android-eigenen
 * Codecs zu Mono-PCM mit reduzierter Abtastrate – genug für die Beat-Erkennung.
 */
object AudioDecoder {

    class Pcm(val samples: FloatArray, val sampleRate: Int)

    private const val TARGET_RATE = 11025
    private const val TIMEOUT_US = 10_000L

    fun decodeMono(context: Context, uri: Uri, onProgress: (Float) -> Unit): Pcm {
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

        val out = FloatList()
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var floatPcm = false
        var factor = 0
        var acc = 0f
        var accCount = 0

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
                        if (factor == 0) factor = maxOf(1, Math.round(sampleRate.toFloat() / TARGET_RATE))
                        val buffer = codec.getOutputBuffer(outIndex)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.order(ByteOrder.nativeOrder())
                        val ch = maxOf(1, channels)
                        if (floatPcm) {
                            val fb = buffer.asFloatBuffer()
                            while (fb.remaining() >= ch) {
                                var s = 0f
                                repeat(ch) { s += fb.get() }
                                acc += s / ch
                                if (++accCount == factor) { out.add(acc / factor); acc = 0f; accCount = 0 }
                            }
                        } else {
                            val sb = buffer.asShortBuffer()
                            while (sb.remaining() >= ch) {
                                var s = 0f
                                repeat(ch) { s += sb.get() / 32768f }
                                acc += s / ch
                                if (++accCount == factor) { out.add(acc / factor); acc = 0f; accCount = 0 }
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
        return Pcm(out.toArray(), sampleRate / maxOf(1, factor))
    }

    private class FloatList {
        private var data = FloatArray(1 shl 16)
        private var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }
        fun toArray(): FloatArray = data.copyOf(size)
    }
}
