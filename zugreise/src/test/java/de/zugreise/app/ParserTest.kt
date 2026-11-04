package de.zugreise.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ParserTest {

    private val today = LocalDate.of(2026, 11, 4)

    private fun check(text: String, vararg expected: String): ParsedJourney {
        val r = ShareParser.parse(text.trimIndent(), today)
        assertEquals(expected.toList(), r.legs.map { it.label() })
        return r
    }

    @Test
    fun departureBeforeTrain() {
        val r = check(
            """
            Mi., 05.11.2026
            München Hbf → Stade
            10:24 München Hbf
            ICE 578
            14:31 Hamburg Hbf
            14:45 Hamburg Hbf
            RE 5
            15:40 Stade
            """,
            "ICE 578 · 10:24 München Hbf → 14:31 Hamburg Hbf",
            "RE 5 · 14:45 Hamburg Hbf → 15:40 Stade",
        )
        assertEquals(LocalDate.of(2026, 11, 5), r.date)
    }

    @Test
    fun ocrWithSeparateLines() {
        val r = check(
            """
            Mittwoch, 5. Nov.
            10:24
            München Hbf
            Gl. 18
            ICE 578
            Richtung Hamburg-Altona
            14:31
            Hamburg Hbf
            Gl. 7
            Umstieg 14 Min.
            14:45
            Hamburg Hbf
            RE5
            15:40
            Stade
            """,
            "ICE 578 · 10:24 München Hbf → 14:31 Hamburg Hbf",
            "RE 5 · 14:45 Hamburg Hbf → 15:40 Stade",
        )
        assertEquals(LocalDate.of(2026, 11, 5), r.date)
    }

    @Test
    fun abAnAfterTrain() {
        check(
            """
            Meine Reise am 05.11.26
            ICE 578 nach Hamburg-Altona
            ab 10:24 München Hbf, Gl. 18
            an 14:31 Hamburg Hbf, Gl. 7
            RE 5
            ab 14:45 Hamburg Hbf
            an 15:40 Stade
            https://int.bahn.de/de/buchung/start?vbid=abc
            """,
            "ICE 578 · 10:24 München Hbf → 14:31 Hamburg Hbf",
            "RE 5 · 14:45 Hamburg Hbf → 15:40 Stade",
        )
    }

    @Test
    fun onlyTimesAndStations() {
        check(
            """
            08:00 Rheda-Wiedenbrück
            08:20 Hamm(Westf)Hbf
            """,
            "08:00 Rheda-Wiedenbrück → 08:20 Hamm(Westf)Hbf",
        )
    }

    @Test
    fun nightTrainMovesNextLegToNextDay() {
        val r = ShareParser.parse(
            """
            22:10 Wien Hbf
            NJ 490
            05:30 München Hbf
            06:01 München Hbf
            S 8
            06:40 München Flughafen Terminal
            """.trimIndent(), today,
        )
        assertEquals(listOf(0, 1), r.legs.map { it.dayOffset })
        assertEquals(LocalTime.of(6, 1), r.legs[1].departure)
    }

    @Test
    fun linkOnlyGivesNothing() {
        assertTrue(ShareParser.parse("Schau mal: https://int.bahn.de/x?vbid=1", today).legs.isEmpty())
    }

    @Test
    fun stationNamesMatch() {
        assertTrue(LegResolver.sameStation("Hamburg Hauptbahnhof", "Hamburg Hbf"))
        assertTrue(LegResolver.sameStation("Hamm(Westf)Hbf", "Hamm (Westf) Hbf"))
        assertTrue(!LegResolver.sameStation("Hamburg Hbf", "Hamburg-Altona"))
    }

    @Test
    fun intermediateStopsAreRecognizedAndBuiltOffline() {
        val r = ShareParser.parse(
            """
            05.11.2026
            10:24 München Hbf
            ICE 578
            10:54 10:56 Augsburg Hbf
            12:01 - 12:03 Würzburg Hbf
            14:31 Hamburg Hbf
            14:45 Hamburg Hbf
            RE 5
            15:10 Buxtehude
            15:40 Stade
            """.trimIndent(), today,
        )
        assertEquals(2, r.legs.size)
        val ice = r.legs[0]
        assertTrue(ice.hasIntermediateStops)
        assertEquals(listOf("München Hbf", "Augsburg Hbf", "Würzburg Hbf", "Hamburg Hbf"), ice.stops.map { it.name })
        assertEquals(LocalTime.of(10, 54), ice.stops[1].arrival)
        assertEquals(LocalTime.of(10, 56), ice.stops[1].departure)

        val db = java.io.File("src/main/assets/stations.txt.gz").inputStream().use { StationDb(it) }
        val leg = LegResolver.fromText(ice, r.date!!, db, java.time.ZoneId.of("Europe/Berlin"))!!
        assertEquals(4, leg.stops.size)
        assertEquals(48.14, leg.stops[0].lat, 0.01)
        assertEquals(53.55, leg.stops[3].lat, 0.01)
        assertEquals("2026-11-05T09:24:00Z", leg.stops[0].scheduledDeparture)
        assertEquals("2026-11-05T13:31:00Z", leg.stops[3].scheduledArrival)
    }

    @Test
    fun stationListFindsSmallStations() {
        val db = java.io.File("src/main/assets/stations.txt.gz").inputStream().use { StationDb(it) }
        assertTrue(db.find("Rahden") != null)
        assertTrue(db.find("Rheda-Wiedenbrück") != null)
        assertTrue(db.find("Hamm(Westf)Hbf") != null)
        assertTrue(db.find("Hamburg Hauptbahnhof") != null)
    }

    @Test
    fun probeDateUsesSameWeekdaySoon() {
        val today = LocalDate.of(2026, 11, 4) // Mittwoch
        val target = LocalDate.of(2026, 12, 28) // Montag
        val probe = LegResolver.probeDate(target, today)!!
        assertEquals(LocalDate.of(2026, 11, 9), probe)
        assertEquals(target.dayOfWeek, probe.dayOfWeek)
        assertEquals(null, LegResolver.probeDate(today.plusDays(1), today))
    }

    @Test
    fun shiftMovesAllTimesAndAddsNote() {
        val s = Stop("A", null, 0.0, 0.0, "Europe/Berlin", null, null, "2026-11-09T09:24:00Z", "2026-11-09T09:24:00Z", null, false)
        val e = Stop("B", null, 0.0, 1.0, "Europe/Berlin", "2026-11-09T10:00:00Z", "2026-11-09T10:00:00Z", null, null, null, false)
        val leg = Leg("1", "t", "RB 71", null, null, null, listOf(s, e), emptyList())
        val moved = LegResolver.shift(leg, 49)
        // 28.12. ist Winterzeit wie im November -> gleiche UTC-Uhrzeit
        assertEquals("2026-12-28T09:24:00Z", moved.from.scheduledDeparture)
        assertEquals("2026-12-28T10:00:00Z", moved.to.scheduledArrival)
        assertTrue(moved.note!!.contains("noch nicht veröffentlicht"))
    }
}
