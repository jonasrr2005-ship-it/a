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

    fun normalizeStation(s: String) = StationNames.normalize(s)

    fun sameStation(a: String, b: String) = StationNames.same(a, b)

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
        val stations = TransitApi.searchStations(from, trainsOnly = true).take(4)
        if (stations.isEmpty()) throw NotResolved("Bahnhof „$from“ nicht gefunden")

        var best: Pair<Departure, Long>? = null
        for (station in stations) {
            // Erst nur Züge (inkl. naher Bahnsteige), dann alles – manche Bahnhöfe sind in der
            // Suche nur als Bushaltestelle eingetragen.
            val deps = try {
                TransitApi.departures(station.id, wanted.minus(Duration.ofMinutes(20)), 2, trainsOnly = true)
                    .ifEmpty { TransitApi.departures(station.id, wanted.minus(Duration.ofMinutes(20)), 2) }
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

    /**
     * Baut den Abschnitt nur aus dem geteilten Text, ohne Internet: Halte und Zeiten aus dem
     * Text, Koordinaten aus der eingebauten Bahnhofsliste. Die Linie verläuft von Halt zu Halt.
     * Gibt null zurück, wenn ein Halt nicht in der Liste steht.
     */
    fun fromText(p: ParsedLeg, date: LocalDate, db: StationDb, zone: ZoneId = ZoneId.systemDefault()): Leg? {
        if (p.stops.size < 2) return null
        var day = date.plusDays(p.dayOffset.toLong())
        var lastTime: java.time.LocalTime? = null
        var near: LatLon? = null
        val stops = ArrayList<Stop>()
        for (ps in p.stops) {
            val found = db.find(ps.name, near) ?: return null
            // Plausibel? Nächster Halt mehr als 1000 km entfernt -> wohl falscher Bahnhof
            if (near != null && Geo.distance(near, found.position) > 1_000_000) return null
            near = found.position
            fun iso(t: java.time.LocalTime?): String? {
                if (t == null) return null
                if (lastTime != null && t.isBefore(lastTime!!.minusHours(3))) day = day.plusDays(1)
                lastTime = t
                return LocalDateTime.of(day, t).atZone(zone).toInstant().toString()
            }
            val arr = iso(ps.arrival)
            val dep = iso(ps.departure)
            stops += Stop(
                name = ps.name, stopId = null, lat = found.lat, lon = found.lon, tz = zone.id,
                scheduledArrival = arr, arrival = arr, scheduledDeparture = dep, departure = dep,
                track = null, cancelled = false,
            )
        }
        return Leg(
            id = UUID.randomUUID().toString(),
            tripId = "",
            name = p.train ?: "Zug",
            headsign = stops.last().name,
            operator = null,
            mode = null,
            stops = stops,
            geometry = stops.map { it.position },
        )
    }
}
