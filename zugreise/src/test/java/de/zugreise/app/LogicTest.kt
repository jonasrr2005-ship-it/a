package de.zugreise.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {

    @Test
    fun decodesGooglePolylineExample() {
        val p = Geo.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", precision = 5)
        assertEquals(3, p.size)
        assertEquals(38.5, p[0].lat, 1e-6)
        assertEquals(-120.2, p[0].lon, 1e-6)
        assertEquals(40.7, p[1].lat, 1e-6)
        assertEquals(-120.95, p[1].lon, 1e-6)
        assertEquals(43.252, p[2].lat, 1e-6)
        assertEquals(-126.453, p[2].lon, 1e-6)
    }

    @Test
    fun cutsGeometryBetweenStops() {
        // Linie von 0,0 nach 0,10 in 0.1°-Schritten, Halte bei 0, 3, 6, 10
        val line = (0..100).map { LatLon(0.0, it / 10.0) }
        val stops = listOf(LatLon(0.0, 0.0), LatLon(0.001, 3.0), LatLon(0.0, 6.0), LatLon(0.0, 10.0))
        val part = Geo.cut(line, stops, 1, 2)
        assertEquals(3.0, part.first().lon, 1e-9)
        assertEquals(6.0, part.last().lon, 1e-9)
        assertEquals(31, part.size)
    }

    @Test
    fun cutFallsBackToStraightLineWhenGeometryDoesNotFit() {
        val line = listOf(LatLon(50.0, 0.0), LatLon(50.0, 1.0))
        val stops = listOf(LatLon(0.0, 0.0), LatLon(0.0, 1.0))
        assertEquals(stops, Geo.cut(line, stops, 0, 1))
    }

    private fun dep(vararg keys: String) = Departure(
        tripId = "t", name = keys.first(), searchKeys = keys.map { TransitApi.normalize(it) },
        headsign = null, operator = null, mode = null,
        stop = Stop("A", null, 0.0, 0.0, null, null, null, null, null, null, false), cancelled = false,
    )

    @Test
    fun matchesTrainNumbers() {
        val ice = dep("ICE 578", "ICE", "578", "ICE578")
        assertTrue(TransitApi.matches(ice, "ICE 578"))
        assertTrue(TransitApi.matches(ice, "ice578"))
        assertTrue(TransitApi.matches(ice, "578"))
        assertFalse(TransitApi.matches(ice, "ICE 579"))
        val re = dep("RE 4", "RE 4")
        assertTrue(TransitApi.matches(re, "re4"))
        assertFalse(TransitApi.matches(re, "RE 14"))
        assertTrue(TransitApi.matches(re, ""))
    }

    @Test
    fun parsesTimes() {
        assertEquals(Times.parse("2026-11-05T09:24:00Z"), Times.parse("2026-11-05T10:24:00+01:00"))
        assertEquals("10:24", Times.time("2026-11-05T09:24:00Z", "Europe/Berlin"))
        assertEquals("10:24 (+5)", Times.withDelay("2026-11-05T09:24:00Z", "2026-11-05T09:29:00Z", "Europe/Berlin"))
        assertEquals("4 h 07 min", Times.duration("2026-11-05T09:24:00Z", "2026-11-05T13:31:00Z"))
    }
}
