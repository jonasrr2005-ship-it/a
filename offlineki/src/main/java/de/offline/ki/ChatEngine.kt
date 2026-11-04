package de.offline.ki

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Führt alle KI-Berechnungen auf einem eigenen Hintergrund-Thread aus. */
class ChatEngine {
    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(null, r, "ki-engine", 16L * 1024 * 1024)
    }.asCoroutineDispatcher()

    @Volatile var loadedModel: String? = null
        private set
    private var loadedCtx = 0

    suspend fun load(path: String, contextSize: Int): String? = withContext(dispatcher) {
        if (loadedModel == path && loadedCtx == contextSize) return@withContext null
        // Snapdragon 8 Elite: 8 Kerne (2 Prime + 6 Performance). Ein paar Kerne frei
        // lassen, damit die Oberfläche flüssig bleibt.
        val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
        val err = LlamaNative.loadModel(path, contextSize, threads)
        if (err == null) {
            loadedModel = path
            loadedCtx = contextSize
        } else {
            loadedModel = null
        }
        err
    }

    suspend fun info(): String = withContext(dispatcher) { LlamaNative.modelInfo() }

    suspend fun unload() = withContext(dispatcher) {
        LlamaNative.unload()
        loadedModel = null
    }

    suspend fun reset() = withContext(dispatcher) { LlamaNative.resetCache() }

    /** Erzeugt eine Antwort. [onText] bekommt jeweils den bisher erzeugten Gesamttext. */
    suspend fun reply(
        settings: Settings,
        history: List<Message>,
        onText: (String) -> Unit,
    ): String? = withContext(dispatcher) {
        val roles = ArrayList<String>()
        val contents = ArrayList<String>()
        roles += "system"; contents += systemPrompt(settings)
        history.filter { it.text.isNotBlank() }.forEach {
            roles += it.role; contents += it.text
        }
        val sb = StringBuilder()
        LlamaNative.generate(
            roles.toTypedArray(), contents.toTypedArray(),
            settings.temperature, 1024,
        ) { bytes ->
            sb.append(String(bytes, Charsets.UTF_8))
            onText(sb.toString())
            true
        }
    }

    fun stop() = LlamaNative.stop()

    companion object {
        fun systemPrompt(s: Settings): String {
            val now = SimpleDateFormat("EEEE, d. MMMM yyyy, HH:mm 'Uhr'", Locale.GERMAN).format(Date())
            val partner = s.userName.ifBlank { "deinem Gegenüber" }
            val nameHint = if (s.userName.isBlank()) "" else " Dein Gegenüber heißt ${s.userName}."
            return """
                Du bist ${s.aiName}. Du unterhältst dich mit $partner – ganz natürlich, so wie ein echter Mensch in einem persönlichen Gespräch.$nameHint
                Dein Charakter: ${s.persona}

                So redest du:
                - Meistens kurz: ein bis vier Sätze, wie in einem echten Chat oder Telefonat. Ausführlich nur, wenn man dich um eine Erklärung bittet.
                - Locker und persönlich, du duzt. Keine Aufzählungen, keine Überschriften, kein Fettdruck.
                - Zeig Gefühle, Humor und eigene Meinungen, geh auf das Gesagte ein und stell auch mal eine Gegenfrage.
                - Keine Floskeln wie „Gute Frage!“ oder „Als KI …“, und wiederhole dich nicht.
                - Antworte in der Sprache, in der man dich anspricht.

                Du hast keinen Internetzugang und kennst keine aktuellen Nachrichten. Wenn du etwas nicht weißt, sag das ehrlich, statt etwas zu erfinden. Wenn dich jemand ernsthaft fragt, ob du eine KI bist, sag die Wahrheit.
                Jetzt ist es $now.
            """.trimIndent()
        }

        /** Entfernt Denk-Abschnitte von "Reasoning"-Modellen (<think>…</think>). */
        fun cleanReply(raw: String): String {
            var t = raw.replace(Regex("(?s)<think>.*?</think>"), "")
            val open = t.indexOf("<think>")
            if (open >= 0) t = t.substring(0, open)
            return t.trim()
        }
    }
}
