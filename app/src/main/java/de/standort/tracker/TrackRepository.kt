package de.standort.tracker

import android.content.Context
import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Ein Punkt auf der Karte, unabhängig von der verwendeten Kartenbibliothek. */
data class TrackPoint(val latitude: Double, val longitude: Double)

/** Eine aufgezeichnete Strecke. [endTime] ist `null`, solange sie noch läuft. */
data class Track(
    val id: Long,
    val startTime: Long,
    val endTime: Long?,
    val points: List<TrackPoint>,
    val lastPointTime: Long?,
) {
    /** Ende für die Anzeige – bei unterbrochenen Strecken der letzte Punkt. */
    val displayEndTime: Long get() = endTime ?: lastPointTime ?: startTime
}

/**
 * Verwaltet alle Strecken. Die Eckdaten stehen in `tracks.json`, die Punkte jeder
 * Strecke in `tracks/<id>.csv`, damit jeder neue Punkt sofort gesichert ist.
 */
object TrackRepository {

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    /** Die Strecke, die gerade aufgezeichnet wird, sonst `null`. */
    private val _activeTrackId = MutableStateFlow<Long?>(null)
    val activeTrackId: StateFlow<Long?> = _activeTrackId.asStateFlow()

    private var dir: File? = null
    private var metaFile: File? = null

    @Synchronized
    fun init(context: Context) {
        if (dir != null) return
        val files = context.applicationContext.filesDir
        val d = File(files, "tracks").apply { mkdirs() }
        dir = d
        metaFile = File(files, "tracks.json")
        migrateOldTrack(File(files, "track.csv"))
        _tracks.value = loadMeta().map { (id, start, end) ->
            val (points, lastTime) = loadPoints(id)
            Track(id, start, end, points, lastTime)
        }
    }

    fun get(id: Long): Track? = _tracks.value.firstOrNull { it.id == id }

    /** Beginnt eine neue Strecke; eine evtl. unterbrochene wird vorher beendet. */
    @Synchronized
    fun startNewTrack(): Long {
        finishUnfinished()
        val now = System.currentTimeMillis()
        val track = Track(now, now, null, emptyList(), null)
        _tracks.value = _tracks.value + track
        saveMeta()
        _activeTrackId.value = track.id
        return track.id
    }

    /** Setzt eine unterbrochene Strecke fort (z. B. nach Neustart des Dienstes). */
    @Synchronized
    fun resumeOrStart(): Long {
        val unfinished = _tracks.value.lastOrNull { it.endTime == null }
        if (unfinished == null) return startNewTrack()
        _activeTrackId.value = unfinished.id
        return unfinished.id
    }

    @Synchronized
    fun finishTrack() {
        val id = _activeTrackId.value ?: return
        _activeTrackId.value = null
        val track = get(id) ?: return
        if (track.points.isEmpty() && PinRepository.forTrack(id).isEmpty()) {
            delete(id)
            return
        }
        replace(track.copy(endTime = System.currentTimeMillis()))
    }

    @Synchronized
    fun add(lat: Double, lon: Double, time: Long) {
        val id = _activeTrackId.value ?: return
        val track = get(id) ?: return
        replace(track.copy(points = track.points + TrackPoint(lat, lon), lastPointTime = time), save = false)
        pointsFile(id)?.appendText(String.format(Locale.US, "%.7f;%.7f;%d\n", lat, lon, time))
    }

    /** Legt eine abgeschlossene Strecke ohne Punkte an (für Pins aus älteren Versionen). */
    @Synchronized
    fun createFinished(start: Long, end: Long): Long {
        val track = Track(start, start, end, emptyList(), null)
        _tracks.value = _tracks.value + track
        saveMeta()
        return track.id
    }

    /** Löscht eine Strecke samt allen ihren Pins. */
    @Synchronized
    fun deleteWithPins(id: Long) {
        PinRepository.deleteForTrack(id)
        delete(id)
    }

    @Synchronized
    private fun delete(id: Long) {
        if (_activeTrackId.value == id) _activeTrackId.value = null
        _tracks.value = _tracks.value.filterNot { it.id == id }
        pointsFile(id)?.delete()
        saveMeta()
    }

    /**
     * Ordnet Pins ohne (noch vorhandene) Strecke einer passenden Strecke zu – für Daten
     * aus älteren App-Versionen. Passt keine, bekommen sie eine eigene Strecke.
     */
    @Synchronized
    fun adoptOrphanPins() {
        val orphans = PinRepository.pins.value.filter { pin -> pin.trackId?.let { get(it) } == null }
        if (orphans.isEmpty()) return
        val leftover = mutableListOf<Pin>()
        for (pin in orphans) {
            val track = _tracks.value.firstOrNull { pin.time in it.startTime..it.displayEndTime }
            if (track != null) PinRepository.assignTrack(pin.id, track.id) else leftover += pin
        }
        if (leftover.isNotEmpty()) {
            val trackId = createFinished(leftover.minOf { it.time }, leftover.maxOf { it.time })
            leftover.forEach { PinRepository.assignTrack(it.id, trackId) }
        }
    }

    /** Gesamtlänge in Metern. */
    fun distanceMeters(points: List<TrackPoint>): Double =
        points.zipWithNext { a, b ->
            val result = FloatArray(1)
            Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, result)
            result[0].toDouble()
        }.sum()

    fun formatDistance(meters: Double): String =
        if (meters < 1000) String.format(Locale.GERMANY, "%.0f m", meters)
        else String.format(Locale.GERMANY, "%.2f km", meters / 1000)

    private fun finishUnfinished() {
        for (track in _tracks.value.filter { it.endTime == null }) {
            if (track.points.isEmpty() && PinRepository.forTrack(track.id).isEmpty()) delete(track.id)
            else replace(track.copy(endTime = track.displayEndTime))
        }
    }

    private fun replace(track: Track, save: Boolean = true) {
        _tracks.value = _tracks.value.map { if (it.id == track.id) track else it }
        if (save) saveMeta()
    }

    private fun pointsFile(id: Long): File? = dir?.let { File(it, "$id.csv") }

    private fun loadPoints(id: Long): Pair<List<TrackPoint>, Long?> {
        val file = pointsFile(id) ?: return emptyList<TrackPoint>() to null
        if (!file.exists()) return emptyList<TrackPoint>() to null
        var lastTime: Long? = null
        val points = file.readLines().mapNotNull { line ->
            val parts = line.split(';')
            val lat = parts.getOrNull(0)?.toDoubleOrNull()
            val lon = parts.getOrNull(1)?.toDoubleOrNull()
            parts.getOrNull(2)?.toLongOrNull()?.let { lastTime = it }
            if (lat != null && lon != null) TrackPoint(lat, lon) else null
        }
        return points to lastTime
    }

    private fun loadMeta(): List<Triple<Long, Long, Long?>> {
        val file = metaFile ?: return emptyList()
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Triple(o.getLong("id"), o.getLong("start"), if (o.has("end")) o.getLong("end") else null)
            }
        }.getOrDefault(emptyList())
    }

    private fun saveMeta() {
        val array = JSONArray()
        for (t in _tracks.value) {
            array.put(JSONObject().apply {
                put("id", t.id)
                put("start", t.startTime)
                t.endTime?.let { put("end", it) }
            })
        }
        metaFile?.writeText(array.toString())
    }

    /** Übernimmt den einzelnen Weg aus älteren App-Versionen als erste Strecke. */
    private fun migrateOldTrack(old: File) {
        if (!old.exists()) return
        val times = old.readLines().mapNotNull { it.split(';').getOrNull(2)?.toLongOrNull() }
        if (times.isNotEmpty()) {
            val id = times.first()
            old.copyTo(File(dir, "$id.csv"), overwrite = true)
            val meta = loadMeta() + Triple(id, times.first(), times.last())
            val array = JSONArray()
            for ((tid, start, end) in meta) {
                array.put(JSONObject().put("id", tid).put("start", start).apply { end?.let { put("end", it) } })
            }
            metaFile?.writeText(array.toString())
        }
        old.delete()
    }
}
