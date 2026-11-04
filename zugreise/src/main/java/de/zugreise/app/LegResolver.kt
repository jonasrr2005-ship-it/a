package de.zugreise.app

import java.io.IOException
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs

/** Macht aus Angaben (Bahnhof, Zeit, Zug, Ausstieg) einen gespeicherten Zugabschnitt. */
object LegResolver {

    class NotResolved(message: String) : IOException(message)

    /** Ergebnis: Zuglauf + Einstieg; [alight] ist null, wenn der Ausstieg nicht eindeutig ist. */
    data class Match(val trip: Trip, val departure: Departure, val board: Int, val alight: Int?)

    /** Findet den Einstiegshalt im Laufweg (Halt-ID + Abfahrtszeit, ersatzweise Name oder Lage). */
    fun boardingIndex(stops: List<Stop>, at: Stop): Int {
        val idx = stops.indices
        val sameTime = { s: Stop ->
            at.scheduledDeparture != null && Times.parse(s.scheduledDeparture) == Times.parse(at.scheduledDeparture)
        }
        return idx.firstOrNull { stops[it].stopId == at.stopId && sameTime(stops[it]) }
            ?: idx.firstOrNull { sameTime(stops[it]) && Geo.distance(stops[it].position, at.position) < 2000 }
            ?: idx.firstOrNull { stops[it].stopId == at.stopId }
            ?: idx.firstOrNull { stops[it].name == at.name }
            ?: idx.minByOrNull { Geo.distance(stops[it].position, at.position) }
            ?: 0
    }

    fun buildLeg(trip: Trip, dep: Departure, from: Int, to: Int) = Leg(
        id = UUID.randomUUID().toString(),
        tripId = trip.tripId,
        name = dep.name.ifBlank { trip.name },
        headsign = dep.headsign ?: trip.headsign,
        operator = dep.operator ?: trip.operator,
        mode = dep.mode ?: trip.mode,
        stops = trip.stops.subList(from, to + 1).toList(),
        geometry = Geo.cut(trip.geometry, trip.stops.map { it.position }, from, to),
    )

    /** Vereinheitlicht Bahnhofsnamen für den Vergleich ("Hamburg Hauptbahnhof" == "Hamburg Hbf"). */
    fun normalizeStation(s: String): String = s.lowercase()
        .replace("hauptbahnhof", "hbf")
        .replace("bahnhof", "bf")
        .replace(Regex("""\(.*?\)"""), "")
        .replace(Regex("[^a-z0-9äöüß]"), "")

    fun sameStation(a: String, b: String): Boolean {
        val x = normalizeStation(a)
        val y = normalizeStation(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || (minOf(x.length, y.length) >= 4 && (x.startsWith(y) || y.startsWith(x)))
    }

    private fun hhmm(s: String?, tz: String?) = Times.time(s, tz)

    /** Sucht den Ausstieg nach Name und/oder Ankunftszeit. */
    fun alightIndex(stops: List<Stop>, board: Int, name: String?, arrival: java.time.LocalTime?): Int? {
        val after = (board + 1..stops.lastIndex)
        val time = arrival?.let { "%02d:%02d".format(it.hour, it.minute) }
        val byTime = { i: Int -> time != null && hhmm(stops[i].scheduledArrival ?: stops[i].arrival, stops[i].tz) == time }
        if (name != null) {
            after.firstOrNull { sameStation(stops[it].name, name) && byTime(it) }?.let { return it }
            after.firstOrNull { sameStation(stops[it].name, name) }?.let { return it }
            // Teilname, z. B. "Stade" in "Stade, Bahnhof"
            val n = normalizeStation(name)
            if (n.length >= 4) after.firstOrNull { normalizeStation(stops[it].name).contains(n) }?.let { return it }
        }
        if (time != null) after.firstOrNull { byTime(it) }?.let { return it }
        return null
    }

    /**
     * Sucht den passenden Zug zu einem erkannten Abschnitt: Bahnhof suchen, Abfahrten zur Zeit
     * laden, Zug über Namen und/oder Abfahrtszeit auswählen, Laufweg laden.
     */
    suspend fun resolve(p: ParsedLeg, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Match {
        val from = p.fromStation ?: throw NotResolved("Einstiegsbahnhof nicht erkannt")
        val depTime = p.departure ?: throw NotResolved("Abfahrtszeit nicht erkannt")
        val wanted = LocalDateTime.of(date.plusDays(p.dayOffset.toLong()), depTime).atZone(zone).toInstant()
        val stations = TransitApi.searchStations(from).take(4)
        if (stations.isEmpty()) throw NotResolved("Bahnhof „$from“ nicht gefunden")

        var best: Pair<Departure, Long>? = null
        for (station in stations) {
            val deps = try {
                TransitApi.departures(station.id, wanted.minus(Duration.ofMinutes(20)), windowHours = 2)
            } catch (e: IOException) {
                continue
            }
            for (d in deps) {
                if (d.cancelled && p.train == null) continue
                val trainOk = p.train == null || TransitApi.matches(d, p.train)
                if (!trainOk) continue
                val t = Times.parse(d.stop.scheduledDeparture ?: d.stop.departure) ?: continue
                val diff = abs(Duration.between(wanted, t).toMinutes())
                // Ohne Zugnamen muss die Zeit genau passen, mit Zugnamen genügt die Nähe
                if (p.train == null && diff > 1) continue
                if (diff > 90) continue
                if (best == null || diff < best.second) best = d to diff
            }
            if (best != null && best.second <= 1) break
        }
        val dep = best?.first ?: throw NotResolved(
            "Kein " + (p.train ?: "Zug") + " um %02d:%02d ab %s gefunden".format(depTime.hour, depTime.minute, from)
        )
        val trip = TransitApi.trip(dep.tripId)
        val board = boardingIndex(trip.stops, dep.stop)
        if (board >= trip.stops.lastIndex) throw NotResolved("${dep.name} endet in $from")
        val alight = alightIndex(trip.stops, board, p.toStation, p.arrival)
        return Match(trip, dep, board, alight)
    }
}
