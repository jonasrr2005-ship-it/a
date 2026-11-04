package de.zugreise.app

import org.json.JSONArray
import org.json.JSONObject

data class LatLon(val lat: Double, val lon: Double)

/** Ein Halt eines Zuges. Zeiten als ISO-8601-Strings (UTC), wie sie die API liefert. */
data class Stop(
    val name: String,
    val stopId: String?,
    val lat: Double,
    val lon: Double,
    val tz: String?,
    val scheduledArrival: String?,
    val arrival: String?,
    val scheduledDeparture: String?,
    val departure: String?,
    val track: String?,
    val cancelled: Boolean,
) {
    val position get() = LatLon(lat, lon)
}

/** Ein gefahrener Zugabschnitt: Einstieg = erster Halt, Ausstieg = letzter Halt. */
data class Leg(
    val id: String,
    val tripId: String,
    val name: String,
    val headsign: String?,
    val operator: String?,
    val mode: String?,
    val stops: List<Stop>,
    val geometry: List<LatLon>,
) {
    val from get() = stops.first()
    val to get() = stops.last()
    val departureTime get() = from.scheduledDeparture ?: from.departure
}

data class Journey(
    val id: String,
    val name: String,
    val legs: List<Leg>,
)

// ---------- JSON-Speicherung ----------

internal fun JSONObject.str(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

fun Stop.toJson() = JSONObject().apply {
    put("name", name)
    put("stopId", stopId)
    put("lat", lat)
    put("lon", lon)
    put("tz", tz)
    put("schedArr", scheduledArrival)
    put("arr", arrival)
    put("schedDep", scheduledDeparture)
    put("dep", departure)
    put("track", track)
    put("cancelled", cancelled)
}

fun stopFromJson(o: JSONObject) = Stop(
    name = o.str("name") ?: "?",
    stopId = o.str("stopId"),
    lat = o.optDouble("lat"),
    lon = o.optDouble("lon"),
    tz = o.str("tz"),
    scheduledArrival = o.str("schedArr"),
    arrival = o.str("arr"),
    scheduledDeparture = o.str("schedDep"),
    departure = o.str("dep"),
    track = o.str("track"),
    cancelled = o.optBoolean("cancelled"),
)

fun Leg.toJson() = JSONObject().apply {
    put("id", id)
    put("tripId", tripId)
    put("name", name)
    put("headsign", headsign)
    put("operator", operator)
    put("mode", mode)
    put("stops", JSONArray().apply { stops.forEach { put(it.toJson()) } })
    // Kompakt: [lat, lon, lat, lon, ...] mit 5 Nachkommastellen (~1 m)
    put("geometry", JSONArray().apply {
        geometry.forEach {
            put(Math.round(it.lat * 1e5) / 1e5)
            put(Math.round(it.lon * 1e5) / 1e5)
        }
    })
}

fun legFromJson(o: JSONObject): Leg {
    val stops = o.getJSONArray("stops").let { a -> List(a.length()) { stopFromJson(a.getJSONObject(it)) } }
    val g = o.optJSONArray("geometry") ?: JSONArray()
    val geometry = List(g.length() / 2) { LatLon(g.getDouble(2 * it), g.getDouble(2 * it + 1)) }
    return Leg(
        id = o.getString("id"),
        tripId = o.str("tripId") ?: "",
        name = o.str("name") ?: "Zug",
        headsign = o.str("headsign"),
        operator = o.str("operator"),
        mode = o.str("mode"),
        stops = stops,
        geometry = geometry,
    )
}

fun Journey.toJson() = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("legs", JSONArray().apply { legs.forEach { put(it.toJson()) } })
}

fun journeyFromJson(o: JSONObject): Journey {
    val legs = o.getJSONArray("legs").let { a -> List(a.length()) { legFromJson(a.getJSONObject(it)) } }
    return Journey(o.getString("id"), o.str("name") ?: "Reise", legs)
}
