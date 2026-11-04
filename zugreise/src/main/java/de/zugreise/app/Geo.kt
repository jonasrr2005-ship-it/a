package de.zugreise.app

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {

    /** Dekodiert eine "Encoded Polyline" (Google-Format) mit der angegebenen Genauigkeit. */
    fun decodePolyline(encoded: String, precision: Int = 6): List<LatLon> {
        val factor = 10.0.pow(precision)
        val out = ArrayList<LatLon>()
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            lat += nextValue(encoded, index).also { index = it.second }.first
            if (index >= encoded.length) break
            lon += nextValue(encoded, index).also { index = it.second }.first
            out += LatLon(lat / factor, lon / factor)
        }
        return out
    }

    private fun nextValue(s: String, start: Int): Pair<Long, Int> {
        var i = start
        var result = 0L
        var shift = 0
        var b: Int
        do {
            if (i >= s.length) break
            b = s[i++].code - 63
            result = result or ((b and 0x1f).toLong() shl shift)
            shift += 5
        } while (b >= 0x20)
        val value = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        return value to i
    }

    /** Entfernung in Metern (Haversine). */
    fun distance(a: LatLon, b: LatLon): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun length(points: List<LatLon>): Double =
        points.zipWithNext().sumOf { (a, b) -> distance(a, b) }

    /**
     * Schneidet aus der Geometrie des ganzen Zuglaufs das Stück zwischen Halt [fromIndex]
     * und Halt [toIndex] heraus. Die Halte werden der Reihe nach auf die Linie gelegt, damit
     * auch Strecken, die einen Ort zweimal passieren, richtig zugeordnet werden.
     * Passt die Geometrie nicht zu den Halten, wird eine Linie direkt durch die Halte gezogen.
     */
    fun cut(geometry: List<LatLon>, stops: List<LatLon>, fromIndex: Int, toIndex: Int): List<LatLon> {
        val straight = stops.subList(fromIndex, toIndex + 1)
        if (geometry.size < 2) return straight
        val indices = IntArray(toIndex + 1)
        var prev = 0
        for (s in 0..toIndex) {
            var best = prev
            var bestDist = Double.MAX_VALUE
            for (i in prev until geometry.size) {
                val d = distance(stops[s], geometry[i])
                if (d < bestDist) { bestDist = d; best = i }
            }
            // Mehr als 3 km neben der Linie -> Geometrie unbrauchbar
            if (bestDist > 3_000 && s >= fromIndex) return straight
            indices[s] = best
            prev = best
        }
        val a = indices[fromIndex]
        val b = indices[toIndex]
        if (b <= a) return straight
        return geometry.subList(a, b + 1)
    }
}
