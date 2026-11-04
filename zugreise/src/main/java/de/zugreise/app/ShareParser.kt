package de.zugreise.app

import java.time.LocalDate
import java.time.LocalTime

/** Ein aus Text (z. B. DB Navigator „Teilen“ oder Bilderkennung) erkannter Zugabschnitt. */
data class ParsedLeg(
    val train: String?,
    val fromStation: String?,
    val departure: LocalTime?,
    val toStation: String?,
    val arrival: LocalTime?,
    /** Tage nach dem erkannten Reisedatum (bei Fahrten über Mitternacht). */
    val dayOffset: Int = 0,
) {
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
    private val TIME_LINE = Regex("""^(?:(ab|an|dep|arr)\.?\s+)?([01]?\d|2[0-3]):([0-5]\d)(?:\s*Uhr)?(?:\s*[-–]\s*(?:[01]?\d|2[0-3]):[0-5]\d)?\s*[,:|·]?\s*(.*)$""", RegexOption.IGNORE_CASE)
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

        data class Entry(val index: Int, val time: LocalTime, val station: String, val kind: String?)
        data class TrainAt(val index: Int, val name: String)

        val entries = mutableListOf<Entry>()
        val trains = mutableListOf<TrainAt>()
        val usedAsStation = mutableSetOf<Int>()

        for ((i, line) in lines.withIndex()) {
            if (i in usedAsStation) continue
            findTrain(line)?.let { name ->
                // Zeile wie "ICE 578 → Hamburg" ist eine Zuginfo, keine Uhrzeit-Zeile
                if (TIME_LINE.find(line) == null) {
                    trains += TrainAt(i, name)
                    return@let
                }
            }
            val m = TIME_LINE.matchEntire(line) ?: continue
            val kind = m.groupValues[1].lowercase().let { if (it == "dep") "ab" else if (it == "arr") "an" else it }.ifEmpty { null }
            val time = LocalTime.of(m.groupValues[2].toInt(), m.groupValues[3].toInt())
            var rest = cleanStation(m.groupValues[4])
            // Zug in derselben Zeile (z. B. "10:24 ICE 578 München Hbf")
            findTrain(m.groupValues[4])?.let { name ->
                if (trains.none { it.index == i }) trains += TrainAt(i, name)
                rest = cleanStation(m.groupValues[4].replace(TRAIN, ""))
            }
            // Zeitspanne ohne Bahnhof (z. B. "10:24 - 14:31") ist eine Zusammenfassung
            if (rest.isEmpty() && m.groupValues[0].contains(Regex("""\d\s*[-–]\s*\d"""))) continue
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
            if (rest.isNotEmpty()) entries += Entry(i, time, rest, kind)
        }

        // Züge an derselben Position wie eine Zeit sollen hinter dieser Zeit einsortiert werden
        val legs = mutableListOf<ParsedLeg>()
        if (trains.isEmpty()) {
            // Nur Zeiten + Bahnhöfe: paarweise Abfahrt/Ankunft
            entries.chunked(2).filter { it.size == 2 }.forEach { (a, b) ->
                legs += ParsedLeg(null, a.station, a.time, b.station, b.time)
            }
        } else {
            val firstTrain = trains.first().index
            val stopsBeforeFirst = entries.count { it.index < firstTrain }
            // Darstellung A: Abfahrt steht vor dem Zug, Ankunft danach (wie in der Reiseübersicht).
            // Darstellung B: Zug zuerst, danach Abfahrt und Ankunft.
            val trainFirst = stopsBeforeFirst == 0
            for ((t, train) in trains.withIndex()) {
                val prev = trains.getOrNull(t - 1)?.index ?: -1
                val next = trains.getOrNull(t + 1)?.index ?: Int.MAX_VALUE
                val before = entries.filter { it.index in (prev + 1) until train.index }
                val sameLine = entries.filter { it.index == train.index }
                val after = entries.filter { it.index > train.index && it.index < next }
                val dep: Entry?
                val arr: Entry?
                if (sameLine.isNotEmpty()) {
                    dep = sameLine.first()
                    arr = after.firstOrNull { it.kind != "ab" } ?: after.firstOrNull()
                } else if (trainFirst) {
                    dep = after.firstOrNull { it.kind == "ab" } ?: after.getOrNull(0)
                    arr = after.firstOrNull { it.kind == "an" } ?: after.getOrNull(1)
                } else {
                    dep = before.lastOrNull { it.kind == "ab" } ?: before.lastOrNull()
                    arr = after.firstOrNull { it.kind == "an" } ?: after.firstOrNull()
                }
                if (dep == null && arr == null) {
                    legs += ParsedLeg(train.name, null, null, null, null)
                } else {
                    legs += ParsedLeg(train.name, dep?.station, dep?.time, arr?.station, arr?.time)
                }
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
