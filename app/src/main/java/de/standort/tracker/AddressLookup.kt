package de.standort.tracker

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Ermittelt die vollständige Adresse zu einem Punkt. */
object AddressLookup {

    private const val USER_AGENT = "StandortTracker/1.4 (Android)"

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

    /** Ersatz über OpenStreetMap, falls der Android-Geocoder nichts liefert. */
    private fun nominatimAddress(lat: Double, lon: Double): String? = runCatching {
        val url = String.format(
            Locale.US,
            "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%.7f&lon=%.7f&accept-language=de",
            lat, lon
        )
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).optString("display_name").ifBlank { null }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
