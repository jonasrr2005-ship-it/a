package de.zugreise.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import java.util.UUID

/** Speichert alle Reisen lokal auf dem Gerät (die Strecken funktionieren danach auch offline). */
class JourneyStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("zugreise", Context.MODE_PRIVATE)

    fun all(): List<Journey> {
        val raw = prefs.getString(KEY_JOURNEYS, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            List(a.length()) { journeyFromJson(a.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveAll(list: List<Journey>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        prefs.edit().putString(KEY_JOURNEYS, a.toString()).apply()
    }

    fun get(id: String?): Journey? = all().firstOrNull { it.id == id }

    /** Die gerade gewählte Reise; legt bei Bedarf eine erste leere Reise an. */
    fun current(): Journey {
        val list = all()
        list.firstOrNull { it.id == prefs.getString(KEY_CURRENT, null) }?.let { return it }
        list.firstOrNull()?.let { setCurrent(it.id); return it }
        return create("Meine Reise")
    }

    fun setCurrent(id: String) = prefs.edit().putString(KEY_CURRENT, id).apply()

    fun create(name: String): Journey {
        val j = Journey(UUID.randomUUID().toString(), name, emptyList())
        saveAll(all() + j)
        setCurrent(j.id)
        return j
    }

    fun update(journey: Journey) {
        val list = all()
        saveAll(
            if (list.any { it.id == journey.id }) list.map { if (it.id == journey.id) journey else it }
            else list + journey
        )
    }

    fun delete(id: String) {
        saveAll(all().filterNot { it.id == id })
        if (prefs.getString(KEY_CURRENT, null) == id) prefs.edit().remove(KEY_CURRENT).apply()
    }

    /** Fügt einen Abschnitt hinzu und sortiert alle Abschnitte nach Abfahrtszeit. */
    fun addLeg(journeyId: String, leg: Leg) {
        val j = get(journeyId) ?: return
        update(j.copy(legs = (j.legs + leg).sortedBy { Times.parse(it.departureTime) }))
    }

    fun removeLeg(journeyId: String, legId: String) {
        val j = get(journeyId) ?: return
        update(j.copy(legs = j.legs.filterNot { it.id == legId }))
    }

    private companion object {
        const val KEY_JOURNEYS = "journeys"
        const val KEY_CURRENT = "current"
    }
}
