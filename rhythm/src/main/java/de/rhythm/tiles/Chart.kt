package de.rhythm.tiles

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

enum class Difficulty(
    val label: String,
    /** Mindestabstand zwischen zwei Kacheln in Sekunden. */
    val minGap: Double,
    /** Anteil der schwächsten Onsets, die verworfen werden. */
    val dropWeakest: Double,
    /** Zeit, die eine Kachel vom oberen Rand bis zur Trefferlinie braucht. */
    val travelTime: Double,
    val chords: Boolean,
) {
    EASY("Leicht", 0.42, 0.40, 1.7, false),
    NORMAL("Normal", 0.27, 0.22, 1.35, false),
    HARD("Schwer", 0.16, 0.08, 1.05, true),
}

/**
 * Wonach die Kacheln erzeugt werden. [stem] ist die Spur der KI-Trennung
 * (Reihenfolge von HT-Demucs 6s), -1 = ganzer Song ohne Trennung.
 */
enum class Instrument(val label: String, val stem: Int, val melodic: Boolean) {
    ALL("Rhythmus (alles)", -1, false),
    PIANO("Klavier", 5, true),
    VOCALS("Gesang", 3, true),
    GUITAR("Gitarre", 4, true),
    BASS("Bass", 1, true),
    DRUMS("Schlagzeug", 0, false);

    val needsAi get() = stem >= 0
}

class Note(val time: Double, val lane: Int, val duration: Double) {
    val end get() = time + duration
    val isHold get() = duration > 0.0

    // Zustand während des Spiels
    var judged = false
    var missed = false
    var holdingPointer = -1
    var holdFinished = false
    var hitAt = 0.0
}

object ChartGenerator {

    const val LANES = 4

    fun generate(onsets: List<BeatAnalyzer.Onset>, difficulty: Difficulty, melodic: Boolean, seed: Long): List<Note> {
        if (onsets.isEmpty()) return emptyList()

        // Schwache Onsets verwerfen
        val strengths = onsets.map { it.strength }.sorted()
        val minStrength = strengths[(strengths.size * difficulty.dropWeakest).toInt().coerceAtMost(strengths.size - 1)]
        val candidates = onsets.filter { it.strength >= minStrength && it.time > 0.8 }

        // Die stärksten zuerst übernehmen, solange der Mindestabstand eingehalten wird
        val chosen = ArrayList<BeatAnalyzer.Onset>()
        for (o in candidates.sortedByDescending { it.strength }) {
            var idx = chosen.binarySearchBy(o.time) { it.time }
            if (idx < 0) idx = -idx - 1
            val before = chosen.getOrNull(idx - 1)
            val after = chosen.getOrNull(idx)
            if (before != null && o.time - before.time < difficulty.minGap) continue
            if (after != null && after.time - o.time < difficulty.minGap) continue
            chosen.add(idx, o)
        }
        if (chosen.isEmpty()) return emptyList()

        val chordThreshold = chosen.map { it.strength }.sorted()
            .let { it[(it.size * 0.92).toInt().coerceAtMost(it.size - 1)] }
        val medianStrength = chosen.map { it.strength }.sorted()[chosen.size / 2]

        val rnd = Random(seed)
        val notes = ArrayList<Note>()
        var lastLane = -1
        var lastPitch = 0f
        for ((i, o) in chosen.withIndex()) {
            val prevGap = if (i > 0) o.time - chosen[i - 1].time else 9.0
            val nextGap = if (i + 1 < chosen.size) chosen[i + 1].time - o.time else 9.0

            val lane = if (melodic && lastLane >= 0 && o.pitch > 0f && lastPitch > 0f) {
                melodicLane(lastLane, o.pitch - lastPitch, prevGap, rnd)
            } else {
                var l = rnd.nextInt(LANES)
                if (l == lastLane) l = (l + 1 + rnd.nextInt(LANES - 1)) % LANES
                l
            }
            if (o.pitch > 0f) lastPitch = o.pitch

            // Lange Pause bis zur nächsten Kachel → manchmal eine Halte-Kachel
            val duration = if (nextGap >= 0.85 && o.strength >= medianStrength && rnd.nextFloat() < 0.5f) {
                minOf(nextGap - 0.3, 2.0)
            } else 0.0
            notes.add(Note(o.time, lane, duration))

            if (difficulty.chords && duration == 0.0 && o.strength >= chordThreshold &&
                prevGap >= 0.3 && nextGap >= 0.3
            ) {
                var second = rnd.nextInt(LANES)
                if (second == lane) second = (second + 1 + rnd.nextInt(LANES - 1)) % LANES
                notes.add(Note(o.time, second, 0.0))
            }
            lastLane = lane
        }
        return notes
    }

    /**
     * Folgt der Melodie wie auf einer Klaviatur: höherer Ton → weiter rechts,
     * tieferer Ton → weiter links, großer Sprung → zwei Spuren weit.
     */
    private fun melodicLane(last: Int, semitones: Float, gap: Double, rnd: Random): Int {
        val dir = when {
            semitones > 0.5f -> 1
            semitones < -0.5f -> -1
            // Gleicher Ton: dieselbe Spur, außer bei sehr schnellen Wiederholungen
            gap >= 0.3 -> 0
            else -> if (rnd.nextBoolean()) 1 else -1
        }
        if (dir == 0) return last
        val steps = if (kotlin.math.abs(semitones) > 4.5f) 2 else 1
        var lane = last + dir * steps
        if (lane !in 0 until LANES) {
            // Am Rand angekommen: so weit wie möglich in die Richtung, sonst zur anderen Seite ausweichen
            lane = lane.coerceIn(0, LANES - 1)
            if (lane == last) lane = last - dir
        }
        return lane
    }
}

/** Speichert erzeugte Kachelfolgen, damit ein Song nur einmal analysiert werden muss. */
object ChartStore {

    private const val VERSION = 2

    fun songKey(context: Context, uri: Uri): String {
        val size = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
            }
        }.getOrNull() ?: 0L
        val digest = MessageDigest.getInstance("SHA-1").digest("$uri|$size".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(20)
    }

    private fun file(context: Context, songKey: String, instrument: Instrument, difficulty: Difficulty) =
        File(File(context.filesDir, "charts").apply { mkdirs() }, "$songKey-${instrument.name}-${difficulty.name}-v$VERSION.txt")

    fun load(context: Context, songKey: String, instrument: Instrument, difficulty: Difficulty): List<Note>? {
        val f = file(context, songKey, instrument, difficulty)
        if (!f.exists()) return null
        return runCatching {
            f.readLines().filter { it.isNotBlank() }.map { line ->
                val (t, l, d) = line.split(';')
                Note(t.toDouble(), l.toInt(), d.toDouble())
            }
        }.getOrNull()
    }

    fun save(context: Context, songKey: String, instrument: Instrument, difficulty: Difficulty, notes: List<Note>) {
        file(context, songKey, instrument, difficulty).writeText(
            notes.joinToString("\n") { "${it.time};${it.lane};${it.duration}" }
        )
    }
}

/** Von der KI abgetrennte Instrumentenspuren (WAV), damit man im Spiel nur das Instrument hören kann. */
object StemStore {

    private const val KEEP = 5

    fun file(context: Context, songKey: String, instrument: Instrument): File =
        File(File(context.filesDir, "stems").apply { mkdirs() }, "$songKey-${instrument.name}.wav")

    /** Nur die zuletzt erzeugten Spuren behalten – jede belegt ~10 MB pro Minute Musik. */
    fun cleanup(context: Context) {
        val files = File(context.filesDir, "stems").listFiles() ?: return
        files.sortedByDescending { it.lastModified() }.drop(KEEP).forEach { it.delete() }
    }
}

fun displayName(context: Context, uri: Uri): String {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "Unbekannter Song"
    return name.substringBeforeLast('.', name)
}
