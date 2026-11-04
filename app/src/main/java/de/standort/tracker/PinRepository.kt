package de.standort.tracker

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Ein gesetzter Pin (eine Lieferung). [address] ist `null`, solange die Adresse noch
 * nicht ermittelt werden konnte (z. B. ohne Internet).
 */
data class Pin(
    val id: Long,
    val time: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val address: String?,
    /** Was abgeliefert wurde. */
    val delivery: String,
    /** Die Strecke, zu der der Pin gehört. */
    val trackId: Long?,
)

/**
 * Speichert die Pins dauerhaft in `pins.json`. Pins gehören immer zu einer Strecke
 * und werden nur zusammen mit ihr gelöscht.
 */
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

    fun forTrack(trackId: Long): List<Pin> = _pins.value.filter { it.trackId == trackId }

    @Synchronized
    fun add(pin: Pin) = save(_pins.value + pin)

    @Synchronized
    fun setAddress(id: Long, address: String) = modify(id) { it.copy(address = address) }

    @Synchronized
    fun setDelivery(id: Long, delivery: String) = modify(id) { it.copy(delivery = delivery) }

    @Synchronized
    fun assignTrack(id: Long, trackId: Long) = modify(id) { it.copy(trackId = trackId) }

    @Synchronized
    fun deleteForTrack(trackId: Long) = save(_pins.value.filterNot { it.trackId == trackId })

    private fun modify(id: Long, change: (Pin) -> Pin) =
        save(_pins.value.map { if (it.id == id) change(it) else it })

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
                put("delivery", pin.delivery)
                pin.trackId?.let { put("track", it) }
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
                delivery = o.optString("delivery"),
                trackId = if (o.has("track")) o.getLong("track") else null,
            )
        }
    }
}
