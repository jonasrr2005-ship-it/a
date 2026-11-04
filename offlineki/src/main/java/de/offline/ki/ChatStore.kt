package de.offline.ki

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Message(
    val role: String,
    var text: String,
    /** Pfad zu einem erzeugten Bild (nur bei Bild-Antworten) */
    var image: String? = null,
    /** Englischer Prompt, mit dem das Bild erzeugt wurde */
    var imagePrompt: String? = null,
) {
    /** Was die Text-KI im Verlauf von dieser Nachricht sieht. */
    fun forModel(): String =
        if (imagePrompt != null) "$text (Du hast ein Bild gemalt: $imagePrompt)" else text
}

/** Speichert das aktuelle Gespräch lokal als JSON (nur auf dem Gerät). */
class ChatStore(context: Context) {
    private val file = File(context.filesDir, "gespraech.json")

    fun load(): MutableList<Message> = runCatching {
        val arr = JSONArray(file.readText())
        MutableList(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Message(
                o.getString("role"), o.getString("text"),
                o.optString("image").ifEmpty { null }, o.optString("imagePrompt").ifEmpty { null },
            )
        }
    }.getOrElse { mutableListOf() }

    fun save(messages: List<Message>) {
        val arr = JSONArray()
        messages.filter { it.text.isNotBlank() || it.image != null }.forEach {
            val o = JSONObject().put("role", it.role).put("text", it.text)
            it.image?.let { p -> o.put("image", p) }
            it.imagePrompt?.let { p -> o.put("imagePrompt", p) }
            arr.put(o)
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }
}
