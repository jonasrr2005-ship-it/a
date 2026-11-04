package de.zugreise.app

import java.time.LocalDate
import java.time.LocalTime

/** Ein aus Text (z. B. DB Navigator „Teilen“ oder Bilderkennung) erkannter Zugabschnitt. */
data class ParsedStop(val name: String, val arrival: LocalTime?, val departure: LocalTime?)

data class ParsedLeg(
    val train: String?,
    val fromStation: String?,
    val departure: LocalTime?,
    val toStation: String?,
    val arrival: LocalTime?,
    /** Tage nach dem erkannten Reisedatum (bei Fahrten über Mitternacht). */
    val dayOffset: Int = 0,
    /** Alle im Text gefundenen Halte (Einstieg, Zwischenhalte, Ausstieg). */
    val stops: List<ParsedStop> = emptyList(),
) {
    /** Stehen Zwischenhalte im Text? Dann kann der Abschnitt ohne Internet übernommen werden. */
    val hasIntermediateStops get() = stops.size >= 3

    fun label(): String {
        val dep = listOfNotNull(departure?.let { fmt(it) }, fromStation).joinToString(" ")
        val arr = listOfNotNull(arrival?.let { fmt(it) }, toStation).joinToString(" ")
        return listOfNotNull(train, "$dep → ${arr.ifEmpty { "?" }}").joinToString(" · ")
    }

    private fun fmt(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)
}

data class ParsedJourney(val date: LocalDate?, val legs: List<ParsedLeg>)

/**
 * Wertet frei formatierten Reisetext aus. Erkennt Uhrzeiten mit Bahnhof („10:24 München Hbf“,
 * auch auf zwei Zeilen verteilt oder mit „ab“/„an“), Zugnamen („ICE 578“, „RE 4“, „S8“) und
 * das Datum. Funktioniert mit dem Teilen-Text von Reise-Apps und mit Text aus Screenshots.
 */
object ShareParser {

    private val TIME = Regex("""(?<![\d:])([01]?\d|2[0-3])[:.]([0-5]\d)(?![\d:])(?:\s*Uhr)?""")
    private val TIME_LINE = Regex("""^(?:(ab|an|dep|arr)\.?\s+)?([01]?\d|2[0-3]):([0-5]\d)(?:\s*Uhr)?(?:\s*[-–/]?\s*(?:ab\s+)?([01]?\d|2[0-3]):([0-5]\d)(?:\s*Uhr)?)?\s*[,:|·]?\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val AB_AN_PREFIX = Regex("""^(ab|an)\s+""", RegexOption.IGNORE_CASE)

    private val KNOWN_PRODUCTS = listOf(
        "ICE", "ICD", "ECE", "EC", "IC", "EN", "NJ", "RJX", "RJ", "TGV", "FLX", "WB", "IRE", "RE", "RB",
        "MEX", "FEX", "ALX", "ERB", "NWB", "BRB", "HEX", "ME", "EB", "TLX", "IR", "SE", "RS", "ÖBB", "D",
        "S", "U", "STR", "Tram", "Bus", "BUS", "R", "L", "TER", "EST", "AVE", "FR", "ES", "IRN", "SBB",
    )
    private val TRAIN = Regex(
        """(?<![\p{L}\d])(${KNOWN_PRODUCTS.joinToString("|") { Regex.escape(it) }})\s?(\d{1,5}[A-Z]?)(?![\p{L}\d])"""
    )
    private val GENERIC_TRAIN_LINE = Regex("""^([A-ZÄÖÜ]{1,4})\s?(\d{1,5})$""")

    private val DATE_NUMERIC = Regex("""(?<!\d)(\d{1,2})\.\s?(\d{1,2})\.(\s?(\d{4}|\d{2})(?!\d))?""")
    private val DATE_WORDS = Regex(
        """(?<!\d)(\d{1,2})\.?\s*(Jan|Feb|Mär|Mar|Apr|Mai|May|Jun|Jul|Aug|Sep|Okt|Oct|Nov|Dez|Dec)[a-zä]*\.?\s*(\d{4})?""",
        RegexOption.IGNORE_CASE,
    )
    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mär" to 3, "mar" to 3, "apr" to 4, "mai" to 5, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "okt" to 10, "oct" to 10, "nov" to 11, "dez" to 12, "dec" to 12,
    )

    /** Zeilen, die sicher kein Bahnhof sind. */
    private val NOT_STATION = Regex(
        """^(gl\.?|gleis|bstg|steig|richtung|nach |umstieg|umsteige|min\b|dauer|fahrtdauer|verbindung|reise|ticket|""" +
            """preis|klasse|sitzplatz|reserv|auslastung|hinfahrt|rückfahrt|zug fällt|fällt aus|verspät|heute|morgen|""" +
            """meine|hier|details|fahrplan|db |deutsche bahn|navigator|bahn\.de|http|www\.|ab |an |fußweg|""" +
            """mo\b|di\b|mi\b|do\b|fr\b|sa\b|so\b|montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag|""" +
            """\+\d|\(\+|ca\.|planmäßig|aktuell|halt|zwischenhalt|bordrestaurant|fahrradmitnahme|wlan|teilen|""" +
            """sparpreis|flexpreis|bahncard|erw|person|€)""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String, today: LocalDate = LocalDate.now()): ParsedJourney {
        val lines = text.lines()
            .map { it.replace(' ', ' ').replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() && !it.contains("http", ignoreCase = true) }

        val date = findDate(lines, today)

        /** [time] = Ankunft bzw. einzige Zeit, [time2] = Abfahrt bei Zwischenhalten ("10:54 10:56 Augsburg"). */
        data class Entry(val index: Int, val time: LocalTime, val time2: LocalTime?, val station: String, val kind: String?) {
            val dep get() = time2 ?: time
            fun toStop() = ParsedStop(station, time, dep)
        }
        data class TrainAt(val index: Int, val name: String)

        val entries = mutableListOf<Entry>()
        val trains = mutableListOf<TrainAt>()
        val usedAsStation = mutableSetOf<Int>()

        for ((i, line) in lines.withIndex()) {
            if (i in usedAsStation) continue
            val timeMatch = TIME_LINE.matchEntire(line)
            if (timeMatch == null) {
                findTrain(line)?.let { trains += TrainAt(i, it) }
                continue
            }
            val m = timeMatch
            val kind = m.groupValues[1].lowercase().let { if (it == "dep") "ab" else if (it == "arr") "an" else it }.ifEmpty { null }
            val time = LocalTime.of(m.groupValues[2].toInt(), m.groupValues[3].toInt())
            val time2 = m.groupValues[4].takeIf { it.isNotEmpty() }?.let { LocalTime.of(it.toInt(), m.groupValues[5].toInt()) }
            val restRaw = m.groupValues[6]
            var rest = cleanStation(restRaw)
            // Zug in derselben Zeile (z. B. "10:24 ICE 578 München Hbf")
            findTrain(restRaw)?.let { name ->
                trains += TrainAt(i, name)
                rest = cleanStation(restRaw.replace(TRAIN, ""))
            }
            // Zeitspanne ohne Bahnhof (z. B. "10:24 - 14:31") ist eine Zusammenfassung
            if (rest.isEmpty() && time2 != null) continue
            if (rest.isEmpty() || !looksLikeStation(rest)) {
                // Bahnhof steht in einer der nächsten Zeilen
                rest = ""
                for (k in i + 1..minOf(i + 3, lines.lastIndex)) {
                    val candidate = lines[k]
                    if (TIME_LINE.matchEntire(candidate) != null || findTrain(candidate) != null) break
                    val cleaned = cleanStation(candidate)
                    if (looksLikeStation(cleaned)) {
                        rest = cleaned
                        usedAsStation += k
                        break
                    }
                }
            }
            if (rest.isNotEmpty()) entries += Entry(i, time, time2, rest, kind)
        }

        fun legOf(train: String?, segment: List<Entry>): ParsedLeg? {
            if (segment.isEmpty()) return if (train != null) ParsedLeg(train, null, null, null, null) else null
            val first = segment.first()
            val last = segment.last().takeIf { segment.size >= 2 }
            return ParsedLeg(
                train = train,
                fromStation = first.station,
                departure = first.dep,
                toStation = last?.station,
                arrival = last?.time,
                stops = if (segment.size >= 2)
                    segment.mapIndexed { k, e ->
                        when (k) {
                            0 -> ParsedStop(e.station, null, e.dep)
                            segment.lastIndex -> ParsedStop(e.station, e.time, null)
                            else -> e.toStop()
                        }
                    }
                else emptyList(),
            )
        }

        val legs = mutableListOf<ParsedLeg>()
        if (trains.isEmpty()) {
            // Keine Zugnamen: Abschnitte dort trennen, wo derselbe Bahnhof zweimal hintereinander
            // steht (Ankunft + Abfahrt beim Umstieg).
            var current = mutableListOf<Entry>()
            for (e in entries) {
                val prev = current.lastOrNull()
                if (prev != null && StationNames.same(prev.station, e.station)) {
                    legOf(null, current)?.let { legs += it }
                    current = mutableListOf()
                }
                current += e
            }
            legOf(null, current)?.let { if (it.toStation != null) legs += it }
        } else {
            val stopsBeforeFirst = entries.count { it.index < trains.first().index }
            // Darstellung A: Abfahrt steht vor dem Zug, Zwischenhalte und Ankunft danach.
            // Darstellung B: Zug zuerst, danach Abfahrt, Zwischenhalte und Ankunft.
            val trainFirst = stopsBeforeFirst == 0
            for ((t, train) in trains.withIndex()) {
                val prev = trains.getOrNull(t - 1)?.index ?: -1
                val next = trains.getOrNull(t + 1)
                val nextIndex = next?.index ?: Int.MAX_VALUE
                val before = entries.filter { it.index in (prev + 1) until train.index }
                val sameLine = entries.filter { it.index == train.index }
                var after = entries.filter { it.index > train.index && it.index < nextIndex }
                val segment: List<Entry> = when {
                    sameLine.isNotEmpty() -> sameLine + after
                    trainFirst -> {
                        val a = after.indexOfFirst { it.kind == "ab" }.takeIf { it >= 0 } ?: 0
                        val b = after.indexOfLast { it.kind == "an" }.takeIf { it >= 0 } ?: after.lastIndex
                        if (after.isEmpty() || b < a) after else after.subList(a, b + 1)
                    }
                    else -> {
                        // Der letzte Eintrag vor dem nächsten Zug ist dessen Abfahrt
                        val nextHasOwnDeparture = next != null && entries.none { it.index == next.index }
                        if (nextHasOwnDeparture && after.size >= 2) after = after.dropLast(1)
                        val dep = before.lastOrNull { it.kind == "ab" } ?: before.lastOrNull()
                        listOfNotNull(dep) + after
                    }
                }
                legOf(train.name, segment)?.let { legs += it }
            }
        }
        return ParsedJourney(date, withDayOffsets(legs))
    }

    /** Fahrten über Mitternacht: spätere Abschnitte mit früherer Uhrzeit liegen am Folgetag. */
    private fun withDayOffsets(legs: List<ParsedLeg>): List<ParsedLeg> {
        var offset = 0
        var last: LocalTime? = null
        return legs.map { leg ->
            val dep = leg.departure
            val prev = last
            if (dep != null && prev != null && dep.isBefore(prev.minusHours(3)) && prev.hour >= 3) offset++
            val result = leg.copy(dayOffset = offset)
            // Ankunft nach Mitternacht: folgende Abschnitte am nächsten Tag
            if (dep != null && leg.arrival != null && leg.arrival.isBefore(dep)) offset++
            last = leg.arrival ?: dep ?: last
            result
        }
    }

    fun findTrain(line: String): String? {
        val trimmed = line.trim()
        if (Regex("""^(gl|gleis|bstg|steig)\b""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) return null
        TRAIN.find(trimmed)?.let { return "${it.groupValues[1]} ${it.groupValues[2]}" }
        GENERIC_TRAIN_LINE.matchEntire(trimmed)?.let { return "${it.groupValues[1]} ${it.groupValues[2]}" }
        return null
    }

    private fun cleanStation(s: String): String = s
        .replace(AB_AN_PREFIX, "")
        .replace(Regex("""\s*[,(]?\s*(Gl\.?|Gleis|Bstg\.?|Steig)\s*\S+\)?.*$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s*[+]\s*\d+.*$"""), "")
        .trim(' ', ',', '-', '–', '·', '|', ':', '→')

    private fun looksLikeStation(s: String): Boolean {
        if (s.length < 3) return false
        if (!s.any { it.isLetter() }) return false
        if (NOT_STATION.containsMatchIn(s)) return false
        if (s.contains('→') || s.contains("->")) return false
        if (TIME.containsMatchIn(s)) return false
        if (DATE_NUMERIC.containsMatchIn(s)) return false
        return true
    }

    private fun findDate(lines: List<String>, today: LocalDate): LocalDate? {
        for (line in lines) {
            DATE_NUMERIC.find(line)?.let { m ->
                val day = m.groupValues[1].toInt()
                val month = m.groupValues[2].toInt()
                val yearText = m.groupValues[4]
                val year = when {
                    yearText.length == 4 -> yearText.toInt()
                    yearText.length == 2 -> 2000 + yearText.toInt()
                    else -> today.year
                }
                runCatching { LocalDate.of(year, month, day) }.getOrNull()?.let {
                    return if (yearText.isEmpty() && it.isBefore(today.minusMonths(6))) it.plusYears(1) else it
                }
            }
            DATE_WORDS.find(line)?.let { m ->
                val month = MONTHS[m.groupValues[2].lowercase().take(3)] ?: return@let
                val year = m.groupValues[3].toIntOrNull() ?: today.year
                runCatching { LocalDate.of(year, month, m.groupValues[1].toInt()) }.getOrNull()?.let { return it }
            }
        }
        return null
    }
}
