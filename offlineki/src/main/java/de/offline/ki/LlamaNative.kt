package de.offline.ki

/** Brücke zum nativen llama.cpp-Kern (src/main/cpp/llm.cpp). Alle Aufrufe außer [stop]
 *  müssen auf demselben Hintergrund-Thread laufen (siehe [ChatEngine]). */
object LlamaNative {
    init {
        System.loadLibrary("offlineki")
        nativeInit()
    }

    fun interface TokenCallback {
        /** Neues Textstück (UTF-8). Rückgabe false = Generierung abbrechen. */
        fun onToken(bytes: ByteArray): Boolean
    }

    private external fun nativeInit()

    /** @return null bei Erfolg, sonst Fehlermeldung */
    external fun loadModel(path: String, nCtx: Int, nThreads: Int): String?
    external fun modelInfo(): String
    external fun stop()
    external fun resetCache()

    /** @return null bei Erfolg (oder Abbruch), sonst Fehlermeldung */
    external fun generate(
        roles: Array<String>,
        contents: Array<String>,
        temperature: Float,
        maxTokens: Int,
        callback: TokenCallback,
    ): String?

    external fun unload()
}
