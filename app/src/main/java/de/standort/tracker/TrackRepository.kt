package de.standort.tracker

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.osmdroid.util.GeoPoint
import java.io.File
import java.util.Locale

/**
 * Hält den aufgezeichneten Weg im Speicher und sichert jeden Punkt in einer CSV-Datei,
 * damit der Strich nach einem Neustart der App erhalten bleibt.
 */
object TrackRepository {

    private val _points = MutableStateFlow<List<GeoPoint>>(emptyList())
    val points: StateFlow<List<GeoPoint>> = _points.asStateFlow()

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
                if (lat != null && lon != null) GeoPoint(lat, lon) else null
            }
        }
    }

    fun setRecording(recording: Boolean) {
        _isRecording.value = recording
    }

    @Synchronized
    fun add(lat: Double, lon: Double, time: Long) {
        _points.value = _points.value + GeoPoint(lat, lon)
        file?.appendText(String.format(Locale.US, "%.7f;%.7f;%d\n", lat, lon, time))
    }

    @Synchronized
    fun clear() {
        _points.value = emptyList()
        file?.delete()
    }

    /** Gesamtlänge des Weges in Metern. */
    fun distanceMeters(points: List<GeoPoint>): Double =
        points.zipWithNext { a, b -> a.distanceToAsDouble(b) }.sum()

    fun formatDistance(meters: Double): String =
        if (meters < 1000) String.format(Locale.GERMANY, "%.0f m", meters)
        else String.format(Locale.GERMANY, "%.2f km", meters / 1000)
}
