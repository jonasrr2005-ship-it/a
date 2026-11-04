package de.offline.ki

import android.graphics.Bitmap
import de.offline.ki.bild.ImageNative
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** Bildgenerierung auf einem eigenen Hintergrund-Thread. */
class ImageEngine {
    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(null, r, "bild-engine", 64L * 1024 * 1024)
    }.asCoroutineDispatcher()

    @Volatile var loadedModel: String? = null
        private set

    /** Einstellungen, passend zum Modelltyp (am Dateinamen erkannt). */
    data class Preset(val steps: Int, val cfg: Float, val sampler: Int, val size: Int, val negative: String)

    fun presetFor(file: File): Preset {
        val n = file.name.lowercase()
        val xl = "xl" in n
        val size = if (xl && "turbo" !in n) 768 else 512
        return when {
            "turbo" in n -> Preset(4, 1.0f, ImageNative.SAMPLER_EULER_A, size, "")
            "lcm" in n -> Preset(6, 1.5f, ImageNative.SAMPLER_LCM, size, "")
            "lightning" in n || "hyper" in n || "dmd" in n -> Preset(6, 1.5f, ImageNative.SAMPLER_EULER_A, size, "")
            else -> Preset(20, 7.0f, ImageNative.SAMPLER_DPMPP2M, size, NEGATIVE)
        }
    }

    suspend fun load(file: File): String? = withContext(dispatcher) {
        if (loadedModel == file.absolutePath) return@withContext null
        val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
        val err = ImageNative.load(file.absolutePath, threads)
        loadedModel = if (err == null) file.absolutePath else null
        err
    }

    suspend fun unload() = withContext(dispatcher) {
        ImageNative.unload()
        loadedModel = null
    }

    /** @return fertiges Bild oder null (Fehler/Abbruch) */
    suspend fun generate(prompt: String, preset: Preset, onProgress: (Int, Int) -> Unit): Bitmap? =
        withContext(dispatcher) {
            val px = ImageNative.generate(
                prompt, preset.negative, preset.size, preset.size,
                preset.steps, preset.cfg, preset.sampler, System.nanoTime() and 0x7fffffff,
            ) { step, steps -> onProgress(step, steps) } ?: return@withContext null
            if (px.size != preset.size * preset.size) return@withContext null
            Bitmap.createBitmap(px, preset.size, preset.size, Bitmap.Config.ARGB_8888)
        }

    fun cancel() = ImageNative.cancel()

    companion object {
        private const val NEGATIVE =
            "blurry, low quality, worst quality, deformed, disfigured, bad anatomy, extra fingers, " +
                "watermark, text, signature, jpeg artifacts"

        /** Erkennt Bildwünsche wie „Mal mir eine Katze“ oder „Erstell ein Bild von …“. */
        private val IMAGE_REQUEST = Regex(
            "(?i)^\\s*(/bild\\b|male?\\s+(mir|uns)\\b|male\\s+(ein|eine|einen|das|die|den)\\b|zeichne\\b)" +
                "|\\b(male|zeichne|erstell\\w*|generier\\w*|mach\\w*|zeig\\w*|kreier\\w*)\\b.{0,40}\\b" +
                "(bild|bildchen|foto|zeichnung|gemälde|illustration|logo|wallpaper|hintergrundbild)\\b" +
                "|\\b(bild|foto|zeichnung)\\b.{0,60}\\b(malen|zeichnen|erstellen|generieren)\\b"
        )

        fun isImageRequest(text: String) = IMAGE_REQUEST.containsMatchIn(text)

        fun stripCommand(text: String) = text.replace(Regex("(?i)^\\s*/bild\\s*"), "").trim()
    }
}
