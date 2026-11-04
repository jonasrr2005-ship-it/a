package de.zugreise.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Ein Bahnhof / eine Haltestelle aus der Suche. */
data class Station(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val area: String?,
    /** null = unbekannt, true = hier halten Züge, false = nur Bus/Tram o. Ä. */
    val hasTrains: Boolean? = null,
) {
    val label: String
        get() = if (area != null && !name.contains(area, ignoreCase = true)) "$name ($area)" else name

    override fun toString() = when (hasTrains) {
        true -> "🚆 $label"
        false -> "🚌 $label"
        null -> label
    }
}

/** Eine Abfahrt an einem Bahnhof. */
data class Departure(
    val tripId: String,
    val name: String,
    val searchKeys: List<String>,
    val headsign: String?,
    val operator: String?,
    val mode: String?,
    val stop: Stop,
    val cancelled: Boolean,
)

/** Der komplette Laufweg eines Zuges. */
data class Trip(
    val tripId: String,
    val name: String,
    val headsign: String?,
    val operator: String?,
    val mode: String?,
    val stops: List<Stop>,
    val geometry: List<LatLon>,
)

class ApiException(message: String) : IOException(message)

/**
 * Fahrplandaten von Transitous (https://transitous.org) – freier, weltweiter Fahrplandienst
 * auf Basis von MOTIS, enthält u. a. die Daten der Deutschen Bahn, ÖBB, SBB und vieler
 * Verkehrsverbünde. Kein API-Schlüssel nötig.
 */
object TransitApi {

    private const val BASE = "https://api.transitous.org"
    private const val USER_AGENT = "Zugreise-Android/1.0 (+https://github.com/jonasrr2005-ship-it/a)"

    /** Neueste zuerst; ältere als Rückfall, falls der Server noch nicht aktualisiert ist. */
    private val VERSIONS = listOf("v6", "v5", "v4", "v3", "v2", "v1")

    @Volatile
    private var versionIndex = 0

    // ---------------- öffentliche Aufrufe ----------------

    suspend fun searchStations(text: String): List<Station> = withContext(Dispatchers.IO) {
        val body = get("/api/v1/geocode", mapOf("text" to text, "type" to "STOP", "language" to "de"))
        val a = JSONArray(body)
        List(a.length()) { a.getJSONObject(it) }
            .filter { it.str("id") != null }
            .map { o ->
                val area = o.optJSONArray("areas")?.let { areas ->
                    (0 until areas.length()).map { areas.getJSONObject(it) }
                        .firstOrNull { it.optBoolean("default") }?.str("name")
                }
                val modes = o.optJSONArray("modes")?.let { m -> List(m.length()) { m.optString(it) } }
                Station(
                    o.getString("id"), o.str("name") ?: "?", o.optDouble("lat"), o.optDouble("lon"), area,
                    hasTrains = modes?.let { m -> m.any { it in RAIL_MODES } },
                )
            }
            .distinctBy { it.toString() }
            // Bahnhöfe mit Zügen zuerst, die Reihenfolge der API sonst beibehalten
            .sortedBy { if (it.hasTrains == true) 0 else if (it.hasTrains == null) 1 else 2 }
    }

    val RAIL_MODES = setOf(
        "RAIL", "HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL", "REGIONAL_FAST_RAIL", "REGIONAL_RAIL", "SUBURBAN",
    )

    /**
     * Abfahrten ab [time]. Mit [windowHours] wird ein größerer Zeitraum durchsucht.
     * [trainsOnly] blendet Busse, Trams usw. aus und bezieht nahe Bahnsteige mit ein
     * (wichtig, wenn z. B. „Bahnhof“ als Bushaltestelle gefunden wurde).
     */
    suspend fun departures(stationId: String, time: Instant, windowHours: Int?, trainsOnly: Boolean = false): List<Departure> =
        withContext(Dispatchers.IO) {
            val params = mutableMapOf(
                "stopId" to stationId,
                "time" to time.truncatedTo(ChronoUnit.SECONDS).toString(),
                "n" to "40",
                "language" to "de",
            )
            if (windowHours != null) params["window"] = (windowHours * 3600).toString()
            if (trainsOnly) {
                params["mode"] = "RAIL"
                params["radius"] = "400"
            }
            val body = getVersioned("stoptimes", params)
            val a = JSONObject(body).optJSONArray("stopTimes") ?: JSONArray()
            List(a.length()) { a.getJSONObject(it) }.mapNotNull { o ->
                if (trainsOnly && o.str("mode")?.let { it !in RAIL_MODES } == true) return@mapNotNull null
                val tripId = o.str("tripId") ?: return@mapNotNull null
                val place = o.optJSONObject("place") ?: return@mapNotNull null
                Departure(
                    tripId = tripId,
                    name = trainName(o),
                    searchKeys = searchKeys(o),
                    headsign = o.str("headsign") ?: o.optJSONObject("tripTo")?.str("name"),
                    operator = o.str("agencyName"),
                    mode = o.str("mode"),
                    stop = parsePlace(place),
                    cancelled = o.optBoolean("cancelled") || o.optBoolean("tripCancelled"),
                )
            }
        }

    suspend fun trip(tripId: String): Trip = withContext(Dispatchers.IO) {
        val body = getVersioned("trip", mapOf("tripId" to tripId, "language" to "de"))
        val itinerary = JSONObject(body)
        val legs = itinerary.optJSONArray("legs") ?: throw ApiException("Zuglauf nicht gefunden")
        val stops = ArrayList<Stop>()
        val geometry = ArrayList<LatLon>()
        var first: JSONObject? = null
        for (i in 0 until legs.length()) {
            val leg = legs.getJSONObject(i)
            if (leg.str("tripId") == null && leg.optJSONObject("legGeometry") == null) continue
            if (first == null) first = leg
            val places = buildList {
                leg.optJSONObject("from")?.let { add(parsePlace(it)) }
                leg.optJSONArray("intermediateStops")?.let { s ->
                    for (k in 0 until s.length()) add(parsePlace(s.getJSONObject(k)))
                }
                leg.optJSONObject("to")?.let { add(parsePlace(it)) }
            }
            for (p in places) {
                val last = stops.lastOrNull()
                if (last != null && last.name == p.name && Geo.distance(last.position, p.position) < 300) {
                    // gleicher Halt am Übergang zweier Teilstücke: zusammenführen
                    stops[stops.lastIndex] = last.copy(departure = p.departure, scheduledDeparture = p.scheduledDeparture)
                } else {
                    stops += p
                }
            }
            leg.optJSONObject("legGeometry")?.let { g ->
                val points = g.str("points")
                if (points != null) geometry += Geo.decodePolyline(points, g.optInt("precision", 6))
            }
        }
        val leg = first ?: throw ApiException("Zuglauf nicht gefunden")
        if (stops.size < 2) throw ApiException("Zuglauf enthält keine Halte")
        Trip(
            tripId = tripId,
            name = trainName(leg),
            headsign = leg.str("headsign") ?: stops.last().name,
            operator = leg.str("agencyName"),
            mode = leg.str("mode"),
            stops = stops,
            geometry = geometry,
        )
    }

    // ---------------- Hilfsfunktionen ----------------

    private fun parsePlace(o: JSONObject) = Stop(
        name = o.str("name") ?: "?",
        stopId = o.str("stopId"),
        lat = o.optDouble("lat"),
        lon = o.optDouble("lon"),
        tz = o.str("tz"),
        scheduledArrival = o.str("scheduledArrival"),
        arrival = o.str("arrival"),
        scheduledDeparture = o.str("scheduledDeparture"),
        departure = o.str("departure"),
        track = o.str("track") ?: o.str("scheduledTrack"),
        cancelled = o.optBoolean("cancelled"),
    )

    /** z. B. "ICE 578", "RE 4", "S 8". */
    fun trainName(o: JSONObject): String {
        o.str("displayName")?.let { return it }
        val route = o.str("routeShortName")
        val trip = o.str("tripShortName")
        return when {
            route != null && trip != null && !route.contains(trip) -> "$route $trip"
            route != null -> route
            trip != null -> trip
            else -> modeName(o.str("mode"))
        }
    }

    private fun searchKeys(o: JSONObject): List<String> {
        val route = o.str("routeShortName")
        val trip = o.str("tripShortName")
        return listOfNotNull(
            o.str("displayName"), route, trip, o.str("routeLongName"),
            if (route != null && trip != null) route + trip else null,
        ).map { normalize(it) }
    }

    fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9äöüß]"), "")

    /** Passt die Eingabe des Nutzers (z. B. "ICE578", "578", "re 4") zu dieser Abfahrt? */
    fun matches(departure: Departure, query: String): Boolean {
        val q = normalize(query)
        if (q.isEmpty()) return true
        return departure.searchKeys.any { key ->
            key == q || (q.length >= 3 && key.contains(q)) || (key.length >= 3 && q.endsWith(key) && q.any { it.isDigit() })
        }
    }

    fun modeName(mode: String?) = when (mode) {
        "HIGHSPEED_RAIL" -> "Hochgeschwindigkeitszug"
        "LONG_DISTANCE" -> "Fernzug"
        "NIGHT_RAIL" -> "Nachtzug"
        "REGIONAL_FAST_RAIL", "REGIONAL_RAIL", "RAIL" -> "Regionalzug"
        "SUBURBAN" -> "S-Bahn"
        "SUBWAY", "METRO" -> "U-Bahn"
        "TRAM" -> "Straßenbahn"
        "BUS" -> "Bus"
        "COACH" -> "Fernbus"
        "FERRY" -> "Fähre"
        "FUNICULAR", "AERIAL_LIFT" -> "Bergbahn"
        null -> "Zug"
        else -> mode
    }

    /** Ruft einen versionierten Endpunkt auf und weicht bei 404 auf ältere API-Versionen aus. */
    private fun getVersioned(endpoint: String, params: Map<String, String>): String {
        var lastError: ApiException? = null
        for (i in versionIndex until VERSIONS.size) {
            try {
                val result = get("/api/${VERSIONS[i]}/$endpoint", params)
                versionIndex = i
                return result
            } catch (e: NotFound) {
                lastError = ApiException(e.message ?: "Nicht gefunden")
                // Ist die Antwort eine echte Fehlermeldung der API (z. B. Zug unbekannt),
                // existiert der Endpunkt – dann nicht weiter probieren.
                if (e.isApiError) throw lastError
            }
        }
        throw lastError ?: ApiException("Fahrplandienst nicht erreichbar")
    }

    private class NotFound(message: String, val isApiError: Boolean) : IOException(message)

    private fun get(path: String, params: Map<String, String>): String {
        val query = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val conn = URL("$BASE$path?$query").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 45_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code in 200..299) return conn.inputStream.bufferedReader().use { it.readText() }
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val apiMessage = try { JSONObject(err).str("error") } catch (e: Exception) { null }
            when (code) {
                404 -> throw NotFound(apiMessage ?: "Nicht gefunden", apiMessage != null)
                429 -> throw ApiException("Zu viele Anfragen – bitte kurz warten.")
                else -> throw ApiException("Fehler vom Fahrplandienst ($code)" + (apiMessage?.let { ": $it" } ?: ""))
            }
        } finally {
            conn.disconnect()
        }
    }
}
