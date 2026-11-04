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
}
