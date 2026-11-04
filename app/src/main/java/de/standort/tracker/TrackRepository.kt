package de.standort.tracker

import android.content.Context
import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale

/** Ein Punkt auf der Karte, unabhängig von der verwendeten Kartenbibliothek. */
data class TrackPoint(val latitude: Double, val longitude: Double)

/**
 * Hält den aufgezeichneten Weg im Speicher und sichert jeden Punkt in einer CSV-Datei,
 * damit der Strich nach einem Neustart der App erhalten bleibt.
 */
object TrackRepository {

    private val _points = MutableStateFlow<List<TrackPoint>>(emptyList())
    val points: StateFlow<List<TrackPoint>> = _points.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "track.csv")
        file = f
        if (f.exists()) {
            _points.value = f.readLines().mapNotNull { line ->
                val parts = line.split(';')
                val lat = parts.getOrNull(0)?.toDoubleOrNull()
                val lon = parts.getOrNull(1)?.toDoubleOrNull()
                if (lat != null && lon != null) TrackPoint(lat, lon) else null
            }
        }
    }

    fun setRecording(recording: Boolean) {
        _isRecording.value = recording
    }

    @Synchronized
    fun add(lat: Double, lon: Double, time: Long) {
        _points.value = _points.value + TrackPoint(lat, lon)
        file?.appendText(String.format(Locale.US, "%.7f;%.7f;%d\n", lat, lon, time))
    }

    @Synchronized
    fun clear() {
        _points.value = emptyList()
        file?.delete()
    }

    /** Gesamtlänge des Weges in Metern. */
    fun distanceMeters(points: List<TrackPoint>): Double =
        points.zipWithNext { a, b ->
            val result = FloatArray(1)
            Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, result)
            result[0].toDouble()
        }.sum()

    fun formatDistance(meters: Double): String =
        if (meters < 1000) String.format(Locale.GERMANY, "%.0f m", meters)
        else String.format(Locale.GERMANY, "%.2f km", meters / 1000)
}
