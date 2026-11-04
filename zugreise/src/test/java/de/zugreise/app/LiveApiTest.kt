package de.zugreise.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

/**
 * Fragt den echten Fahrplandienst ab. Läuft nur mit Umgebungsvariable LIVE_API=1
 * (im CI als eigener Schritt), damit normale Builds nicht vom Internet abhängen.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LiveApiTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    @Before
    fun onlyWhenEnabled() = assumeTrue(System.getenv("LIVE_API") == "1")

    @Test
    fun stationSearch() = runBlocking {
        for (q in listOf("Rahden", "Rahden Bahnhof", "Rheda-Wiedenbrück", "München Hbf")) {
            val found = TransitApi.searchStations(q, trainsOnly = true)
            println("SUCHE '$q': " + found.take(6).joinToString(" | ") { "$it [${it.id}]" })
        }
    }

    @Test
    fun rahdenDepartures() = runBlocking {
        val found = TransitApi.searchStations("Rahden", trainsOnly = true)
        for (st in found.take(3)) {
            val trains = TransitApi.departures(st.id, Instant.now(), 24, trainsOnly = true)
            val all = TransitApi.departures(st.id, Instant.now(), null, trainsOnly = false)
            println("RAHDEN $st: Züge(24h)=${trains.size} alle=${all.size} " +
                trains.take(3).joinToString { "${it.name}→${it.headsign}" } + " / " +
                all.take(3).joinToString { "${it.name}(${it.mode})→${it.headsign}" })
        }
    }

    @Test
    fun fullChain() = runBlocking {
        val st = TransitApi.searchStations("Rheda-Wiedenbrück", trainsOnly = true).first()
        val deps = TransitApi.departures(st.id, Instant.now(), null, trainsOnly = true)
        println("ABFAHRTEN $st: " + deps.take(5).joinToString { "${Times.time(it.stop.scheduledDeparture, it.stop.tz)} ${it.name}→${it.headsign}" })
        assertTrue("keine Abfahrten", deps.isNotEmpty())
        val dep = deps.first { !it.cancelled }
        val trip = TransitApi.trip(dep.tripId)
        val board = LegResolver.boardingIndex(trip.stops, dep.stop)
        println("ZUG ${trip.name} Halte=${trip.stops.size} Geometrie=${trip.geometry.size} Einstieg=$board " +
            trip.stops.take(4).joinToString { "${it.name} ${Times.time(it.scheduledDeparture, it.tz)}" })
        assertTrue(trip.stops.size >= 2)

        // Wie ein Import: Bahnhof + Uhrzeit + Zug + Ausstieg zwei Halte später
        val exit = trip.stops[minOf(board + 2, trip.stops.lastIndex)]
        val depTime = Times.parse(dep.stop.scheduledDeparture)!!.atZone(berlin)
        val parsed = ParsedLeg(dep.name, "Rheda-Wiedenbrück", depTime.toLocalTime(), exit.name, null)
        val match = LegResolver.resolve(parsed, depTime.toLocalDate(), berlin)
        val leg = LegResolver.buildLeg(match.trip, match.departure, match.board, match.alight!!)
        println("IMPORT ${leg.name}: ${leg.from.name} → ${leg.to.name}, ${leg.stops.size} Halte, " +
            "${leg.geometry.size} Punkte, ${(Geo.length(leg.geometry) / 1000).toInt()} km")
        assertTrue(leg.to.name == exit.name)
    }

    @Test
    fun timetableDateRange() = runBlocking {
        val st = TransitApi.searchStations("Rahden", trainsOnly = true).first()
        val today = java.time.LocalDate.now(berlin)
        val days = listOf(today.plusDays(7), today.plusDays(21), java.time.LocalDate.of(2026, 12, 10),
            java.time.LocalDate.of(2026, 12, 12), java.time.LocalDate.of(2026, 12, 14),
            java.time.LocalDate.of(2026, 12, 28), java.time.LocalDate.of(2027, 1, 15))
        for (d in days) {
            val start = d.atTime(8, 0).atZone(berlin).toInstant()
            val deps = TransitApi.departures(st.id, start, null, trainsOnly = true)
                .filter { Times.parse(it.stop.scheduledDeparture)?.atZone(berlin)?.toLocalDate() == d }
            println("DATUM $d $st: ${deps.size} Züge an diesem Tag " + deps.take(2).joinToString { "${Times.time(it.stop.scheduledDeparture, it.stop.tz)} ${it.name}" })
        }
        // Wie in der App: Zieltag ohne Daten -> gleicher Wochentag in den nächsten Tagen
        val target = java.time.LocalDate.of(2026, 12, 28)
        val probe = LegResolver.probeDate(target, today)
        println("ERSATZTAG für $target: $probe")
    }
}
