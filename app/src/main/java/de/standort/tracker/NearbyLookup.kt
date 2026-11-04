package de.standort.tracker

import android.content.Context
import android.location.Geocoder
import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Ermittelt Adresse und Firmen rund um einen Punkt. Die Firmen kommen aus den
 * OpenStreetMap-Daten (Overpass-API) – kostenlos und ohne API-Schlüssel.
 */
object NearbyLookup {

    /** Nur Firmen, die wirklich direkt am Standort sind. */
    const val RADIUS_METERS = 50

    private const val USER_AGENT = "StandortTracker/1.2 (Android)"

    /** Liefert die Adresse oder `null`, wenn sie nicht ermittelt werden konnte. */
    suspend fun address(context: Context, lat: Double, lon: Double): String? =
        withContext(Dispatchers.IO) {
            geocoderAddress(context, lat, lon) ?: nominatimAddress(lat, lon)
        }

    @Suppress("DEPRECATION")
    private fun geocoderAddress(context: Context, lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) return null
        return runCatching {
            Geocoder(context, Locale.GERMANY).getFromLocation(lat, lon, 1)
                ?.firstOrNull()
                ?.getAddressLine(0)
        }.getOrNull()
    }

    private fun nominatimAddress(lat: Double, lon: Double): String? = runCatching {
        val url = String.format(
            Locale.US,
            "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%.7f&lon=%.7f&accept-language=de",
            lat, lon
        )
        JSONObject(httpGet(url)).optString("display_name").ifBlank { null }
    }.getOrNull()

    /**
     * Liefert die Firmen im Umkreis von [RADIUS_METERS], nach Entfernung sortiert.
     * Eine leere Liste heißt „keine Firmen“, `null` heißt „Suche fehlgeschlagen“.
     */
    suspend fun companies(lat: Double, lon: Double): List<Company>? = withContext(Dispatchers.IO) {
        runCatching {
            val around = String.format(Locale.US, "around:%d,%.7f,%.7f", RADIUS_METERS, lat, lon)
            val query = """
                [out:json][timeout:20];
                (
                  nwr($around)["name"]["shop"];
                  nwr($around)["name"]["office"];
                  nwr($around)["name"]["craft"];
                  nwr($around)["name"]["company"];
                  nwr($around)["name"]["industrial"];
                  nwr($around)["name"]["healthcare"];
                  nwr($around)["name"]["tourism"~"^(hotel|motel|hostel|guest_house|apartment)$"];
                  nwr($around)["name"]["amenity"~"^($AMENITIES)$"];
                );
                out center tags;
            """.trimIndent()
            val response = httpPost(
                "https://overpass-api.de/api/interpreter",
                "data=" + URLEncoder.encode(query, "UTF-8")
            )
            parseCompanies(JSONObject(response), lat, lon)
        }.getOrNull()
    }

    private fun parseCompanies(json: JSONObject, lat: Double, lon: Double): List<Company> {
        val elements = json.optJSONArray("elements") ?: return emptyList()
        val result = mutableListOf<Company>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: continue
            val name = tags.optString("name")
            if (name.isBlank()) continue
            val center = e.optJSONObject("center")
            val eLat = center?.optDouble("lat") ?: e.optDouble("lat")
            val eLon = center?.optDouble("lon") ?: e.optDouble("lon")
            val distance = FloatArray(1)
            if (!eLat.isNaN() && !eLon.isNaN()) {
                Location.distanceBetween(lat, lon, eLat, eLon, distance)
            }
            result += Company(name, category(tags), distance[0].toInt())
        }
        return result
            .sortedBy { it.distanceMeters }
            .distinctBy { it.name.lowercase(Locale.GERMANY) }
    }

    private fun category(tags: JSONObject): String {
        for ((key, label) in CATEGORY_LABELS) {
            val value = tags.optString(key)
            if (value.isNotBlank()) {
                return if (value == "yes") label else "$label: ${value.replace('_', ' ')}"
            }
        }
        return "Firma"
    }

    private fun httpGet(url: String): String = request(url, null)

    private fun httpPost(url: String, body: String): String = request(url, body)

    private fun request(url: String, body: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 25_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private val CATEGORY_LABELS = listOf(
        "shop" to "Geschäft",
        "office" to "Büro",
        "craft" to "Handwerk",
        "company" to "Firma",
        "industrial" to "Industrie",
        "healthcare" to "Gesundheit",
        "tourism" to "Unterkunft",
        "amenity" to "Einrichtung",
    )

    private const val AMENITIES =
        "restaurant|cafe|fast_food|bar|pub|biergarten|ice_cream|food_court|bank|bureau_de_change|" +
            "pharmacy|doctors|dentist|clinic|veterinary|fuel|car_wash|car_rental|bicycle_rental|" +
            "cinema|theatre|nightclub|casino|driving_school|post_office|marketplace|coworking_space"
}
