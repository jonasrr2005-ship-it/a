package de.offline.ki.bild

/** Brücke zu stable-diffusion.cpp (src/main/cpp/imagegen.cpp). */
object ImageNative {
    init {
        System.loadLibrary("imagegen")
    }

    fun interface Progress {
        fun onProgress(step: Int, steps: Int)
    }

    /** @return null bei Erfolg, sonst Fehlermeldung */
    external fun load(path: String, nThreads: Int): String?
    external fun modelVersion(): String

    /** @return ARGB-Pixel oder null bei Fehler/Abbruch. sampler < 0 = Standard des Modells. */
    external fun generate(
        prompt: String,
        negative: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        sampler: Int,
        seed: Long,
        progress: Progress?,
    ): IntArray?

    external fun cancel()
    external fun unload()

    // Werte aus enum sample_method_t (stable-diffusion.h)
    const val SAMPLER_DEFAULT = -1
    const val SAMPLER_EULER_A = 1
    const val SAMPLER_DPMPP2M = 5
    const val SAMPLER_LCM = 9
}
