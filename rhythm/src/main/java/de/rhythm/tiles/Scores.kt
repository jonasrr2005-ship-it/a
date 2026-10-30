package de.rhythm.tiles

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Bestenliste pro Song/Schwierigkeit und Liste der zuletzt gespielten Songs. */
object Scores {

    private const val PREFS = "scores"
    private const val RECENT = "recent"
    private const val MAX_RECENT = 15

    class RecentSong(val uri: Uri, val title: String)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun best(context: Context, songKey: String, difficulty: Difficulty): Long =
        prefs(context).getLong("$songKey|${difficulty.name}", 0L)

    /** Speichert den Punktestand, falls er ein neuer Rekord ist. Liefert den bisherigen Rekord. */
    fun submit(context: Context, songKey: String, difficulty: Difficulty, score: Long): Long {
        val old = best(context, songKey, difficulty)
        if (score > old) prefs(context).edit().putLong("$songKey|${difficulty.name}", score).apply()
        return old
    }

    fun recent(context: Context): List<RecentSong> {
        val json = prefs(context).getString(RECENT, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                RecentSong(Uri.parse(o.getString("uri")), o.getString("title"))
            }
        }.getOrDefault(emptyList())
    }

    fun addRecent(context: Context, uri: Uri, title: String) {
        val list = listOf(RecentSong(uri, title)) + recent(context).filter { it.uri != uri }
        save(context, list.take(MAX_RECENT))
    }

    fun removeRecent(context: Context, uri: Uri) {
        save(context, recent(context).filter { it.uri != uri })
    }

    private fun save(context: Context, list: List<RecentSong>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("uri", it.uri.toString()).put("title", it.title)) }
        prefs(context).edit().putString(RECENT, arr.toString()).apply()
    }
}
