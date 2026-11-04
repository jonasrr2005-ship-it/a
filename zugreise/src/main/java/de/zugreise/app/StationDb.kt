package de.zugreise.app

import android.content.Context
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Bahnhöfe Europas mit Koordinaten, fest in der App (offline).
 * Quelle: trainline-eu/stations (Open Database License, © Trainline und Mitwirkende).
 */
class StationDb(input: InputStream) {

    data class Entry(val name: String, val lat: Double, val lon: Double) {
        val position get() = LatLon(lat, lon)
    }

    private val all = ArrayList<Entry>(60_000)
    private val byKey = HashMap<String, MutableList<Entry>>(120_000)

    init {
        GZIPInputStream(input).bufferedReader().useLines { lines ->
            for (line in lines) {
                val parts = line.split('|')
                if (parts.size < 3) continue
                val e = Entry(parts[0], parts[1].toDoubleOrNull() ?: continue, parts[2].toDoubleOrNull() ?: continue)
                all += e
                for (key in keys(e.name)) byKey.getOrPut(key) { ArrayList(1) } += e
            }
        }
    }

    /** Schlüssel: voller Name und Name ohne „Hbf“/„Bf“ am Ende. */
    private fun keys(name: String): Set<String> {
        val k = StationNames.normalize(name)
        return setOf(k, k.removeSuffix("hbf").removeSuffix("bf")).filter { it.length >= 2 }.toSet()
    }

    /**
     * Sucht einen Bahnhof nach Namen. Bei mehreren gleichnamigen (z. B. „Neustadt“) wird der
     * genommen, der [near] am nächsten liegt (der vorherige Halt des Zuges).
     */
    fun find(name: String, near: LatLon? = null): Entry? {
        val candidates = keys(name).flatMap { byKey[it].orEmpty() }.distinct().ifEmpty {
            // Teilname, z. B. "Rahden" -> "Rahden (Kr Lübbecke)"
            val k = StationNames.normalize(name)
            if (k.length < 4) emptyList() else all.filter { StationNames.normalize(it.name).startsWith(k) }.take(20)
        }
        if (candidates.isEmpty()) return null
        if (near == null) return candidates.first()
        return candidates.minByOrNull { Geo.distance(it.position, near) }
    }

    companion object {
        @Volatile
        private var instance: StationDb? = null

        /** Lädt die Liste beim ersten Aufruf (dauert kurz, daher nicht im UI-Thread aufrufen). */
        fun get(context: Context): StationDb = instance ?: synchronized(this) {
            instance ?: context.applicationContext.assets.open("stations.txt.gz").use { StationDb(it) }
                .also { instance = it }
        }
    }
}
