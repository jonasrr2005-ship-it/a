package de.offline.ki

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Message(val role: String, var text: String)

/** Speichert das aktuelle Gespräch lokal als JSON (nur auf dem Gerät). */
class ChatStore(context: Context) {
    private val file = File(context.filesDir, "gespraech.json")

    fun load(): MutableList<Message> = runCatching {
        val arr = JSONArray(file.readText())
        MutableList(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Message(o.getString("role"), o.getString("text"))
        }
    }.getOrElse { mutableListOf() }

    fun save(messages: List<Message>) {
        val arr = JSONArray()
        messages.filter { it.text.isNotBlank() }.forEach {
            arr.put(JSONObject().put("role", it.role).put("text", it.text))
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }
}
