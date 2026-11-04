package de.zugreise.app

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Times {
    private val GERMAN = Locale.GERMANY
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", GERMAN)
    private val DATE = DateTimeFormatter.ofPattern("EE, dd.MM.yyyy", GERMAN)
    private val DATE_SHORT = DateTimeFormatter.ofPattern("dd.MM.", GERMAN)

    fun parse(s: String?): Instant? {
        if (s.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(s).toInstant()
        } catch (e: Exception) {
            try { Instant.parse(s) } catch (e2: Exception) { null }
        }
    }

    fun zone(tz: String?): ZoneId =
        try { if (tz.isNullOrBlank()) ZoneId.systemDefault() else ZoneId.of(tz) } catch (e: Exception) { ZoneId.systemDefault() }

    fun time(s: String?, tz: String?): String =
        parse(s)?.atZone(zone(tz))?.format(TIME) ?: "--:--"

    fun date(s: String?, tz: String?): String =
        parse(s)?.atZone(zone(tz))?.format(DATE) ?: ""

    fun shortDate(s: String?, tz: String?): String =
        parse(s)?.atZone(zone(tz))?.format(DATE_SHORT) ?: ""

    /** Verspätung in Minuten (null, wenn keine Echtzeit oder pünktlich). */
    fun delayMinutes(scheduled: String?, actual: String?): Long? {
        val a = parse(scheduled) ?: return null
        val b = parse(actual) ?: return null
        val m = Duration.between(a, b).toMinutes()
        return if (m == 0L) null else m
    }

    /** "10:24" oder "10:24 (+5)" bei Verspätung. */
    fun withDelay(scheduled: String?, actual: String?, tz: String?): String {
        val base = time(scheduled ?: actual, tz)
        val d = delayMinutes(scheduled, actual) ?: return base
        return if (d > 0) "$base (+$d)" else "$base ($d)"
    }

    fun duration(from: String?, to: String?): String {
        val a = parse(from) ?: return ""
        val b = parse(to) ?: return ""
        val min = Duration.between(a, b).toMinutes()
        return if (min >= 60) "%d h %02d min".format(min / 60, min % 60) else "$min min"
    }
}
