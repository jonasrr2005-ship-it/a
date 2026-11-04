package de.standort.tracker

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Eine Firma bzw. ein Geschäft in der Nähe eines Pins. */
data class Company(val name: String, val category: String, val distanceMeters: Int)

/**
 * Ein gesetzter Pin. [address] und [companies] sind `null`, solange sie noch nicht
 * ermittelt werden konnten (z. B. ohne Internet); eine leere Firmenliste bedeutet
 * dagegen „keine Firmen in der Nähe“.
 */
data class Pin(
    val id: Long,
    val time: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val address: String?,
    val companies: List<Company>?,
) {
    val isComplete: Boolean get() = address != null && companies != null
}

/** Speichert die Pins dauerhaft in `pins.json`, bis der Nutzer sie löscht. */
object PinRepository {

    private val _pins = MutableStateFlow<List<Pin>>(emptyList())
    val pins: StateFlow<List<Pin>> = _pins.asStateFlow()

    private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "pins.json")
        file = f
        if (f.exists()) {
            _pins.value = runCatching { parse(f.readText()) }.getOrDefault(emptyList())
        }
    }

    fun get(id: Long): Pin? = _pins.value.firstOrNull { it.id == id }

    @Synchronized
    fun add(pin: Pin) = save(_pins.value + pin)

    @Synchronized
    fun update(pin: Pin) = save(_pins.value.map { if (it.id == pin.id) pin else it })

    @Synchronized
    fun delete(id: Long) = save(_pins.value.filterNot { it.id == id })

    @Synchronized
    fun deleteAll() = save(emptyList())

    private fun save(pins: List<Pin>) {
        _pins.value = pins
        file?.writeText(toJson(pins).toString())
    }

    private fun toJson(pins: List<Pin>) = JSONArray().apply {
        for (pin in pins) {
            put(JSONObject().apply {
                put("id", pin.id)
                put("time", pin.time)
                put("lat", pin.latitude)
                put("lon", pin.longitude)
                pin.accuracyMeters?.let { put("accuracy", it.toDouble()) }
                pin.address?.let { put("address", it) }
                pin.companies?.let { companies ->
                    put("companies", JSONArray().apply {
                        for (c in companies) {
                            put(JSONObject().apply {
                                put("name", c.name)
                                put("category", c.category)
                                put("distance", c.distanceMeters)
                            })
                        }
                    })
                }
            })
        }
    }

    private fun parse(text: String): List<Pin> {
        val array = JSONArray(text)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Pin(
                id = o.getLong("id"),
                time = o.getLong("time"),
                latitude = o.getDouble("lat"),
                longitude = o.getDouble("lon"),
                accuracyMeters = if (o.has("accuracy")) o.getDouble("accuracy").toFloat() else null,
                address = if (o.has("address")) o.getString("address") else null,
                companies = o.optJSONArray("companies")?.let { list ->
                    (0 until list.length()).map { j ->
                        val c = list.getJSONObject(j)
                        Company(c.getString("name"), c.getString("category"), c.getInt("distance"))
                    }
                },
            )
        }
    }
}
