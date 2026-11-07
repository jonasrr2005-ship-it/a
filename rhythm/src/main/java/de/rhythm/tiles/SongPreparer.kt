package de.rhythm.tiles

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Bereitet einen Song vor: ggf. KI-Modell laden, Instrumente trennen, Beats erkennen,
 * Kacheln erzeugen. Läuft unabhängig von der Activity weiter (z. B. beim Drehen).
 */
object SongPreparer {

    /** Längere Songs würden für die KI-Trennung zu viel Speicher brauchen. */
    private const val MAX_AI_SECONDS = 12 * 60

    class Request(
        val uri: Uri,
        val title: String,
        val songKey: String,
        val instrument: Instrument,
        val difficulty: Difficulty,
        /** Im Spiel nur das abgetrennte Instrument abspielen. */
        val isolate: Boolean,
    )

    sealed class Status {
        class Working(val text: String, val progress: Float?) : Status()
        class Ready(val request: Request, val audioFile: File?) : Status()
        class Failed(val message: String) : Status()
        class TooFewNotes(val request: Request) : Status()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status

    val busy get() = job?.isActive == true

    /** Ist alles Nötige schon vorhanden, sodass das Spiel sofort starten kann? */
    fun readyFile(context: Context, r: Request): Pair<Boolean, File?> {
        val chart = ChartStore.load(context, r.songKey, r.instrument, r.difficulty) != null
        if (!r.isolate || !r.instrument.needsAi) return chart to null
        val wav = StemStore.file(context, r.songKey, r.instrument)
        return (chart && wav.exists()) to wav
    }

    fun start(context: Context, r: Request) {
        if (busy) return
        val app = context.applicationContext
        job = scope.launch {
            try {
                if (r.instrument.needsAi) prepareWithAi(app, r) else prepareRhythm(app, r)
                val notes = ChartStore.load(app, r.songKey, r.instrument, r.difficulty).orEmpty()
                _status.value = when {
                    notes.size < 5 -> Status.TooFewNotes(r)
                    r.isolate && r.instrument.needsAi -> Status.Ready(r, StemStore.file(app, r.songKey, r.instrument))
                    else -> Status.Ready(r, null)
                }
            } catch (e: CancellationException) {
                _status.value = null
            } catch (e: OutOfMemoryError) {
                _status.value = Status.Failed("Nicht genug Arbeitsspeicher für diesen Song.")
            } catch (e: Exception) {
                _status.value = Status.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _status.value = null
    }

    /** Status wurde von der Activity verarbeitet. */
    fun consume() {
        if (!busy) _status.value = null
    }

    private fun progress(text: String, p: Float?) {
        _status.value = Status.Working(text, p)
    }

    private suspend fun prepareRhythm(context: Context, r: Request) {
        val text = "Analysiere Rhythmus von „${r.title}“ …"
        progress(text, 0f)
        val pcm = AudioDecoder.decodeMono(context, r.uri) { progress(text, it * 0.9f) }
        kotlin.coroutines.coroutineContext.ensureActive()
        val onsets = BeatAnalyzer.detectOnsets(pcm)
        saveCharts(context, r.songKey, Instrument.ALL, onsets)
    }

    private suspend fun prepareWithAi(context: Context, r: Request) {
        val ctx = kotlin.coroutines.coroutineContext
        val checkCancel = { ctx.ensureActive() }

        if (!StemSeparator.isModelInstalled(context)) {
            progress("Lade KI-Modell herunter …", 0f)
            StemSeparator.downloadModel(context, checkCancel) { loaded, total ->
                val mb = loaded / 1_000_000
                if (total > 0) {
                    progress("Lade KI-Modell herunter … $mb von ${total / 1_000_000} MB", loaded.toFloat() / total)
                } else {
                    progress("Lade KI-Modell herunter … $mb MB", null)
                }
            }
        }

        val decodeText = "Lese „${r.title}“ ein …"
        progress(decodeText, 0f)
        val pcm = AudioDecoder.decodeStereo44k(context, r.uri, MAX_AI_SECONDS) { progress(decodeText, it) }
        checkCancel()

        // Alle Instrumente werden in einem Durchgang getrennt, daher gleich für alle die Kacheln
        // erzeugen. Als Audiodatei gespeichert wird nur das gewählte Instrument.
        val instruments = Instrument.entries.filter { it.needsAi }
        val analysis = instruments.associateWith { StemSeparator.AnalysisSink() }
        val wavFile = StemStore.file(context, r.songKey, r.instrument)
        val wav = if (r.isolate) StemSeparator.WavSink(File(wavFile.path + ".part")) else null
        val sinks = arrayOfNulls<StemSeparator.StemSink>(StemSeparator.STEM_COUNT)
        for ((inst, sink) in analysis) {
            sinks[inst.stem] = if (inst == r.instrument && wav != null) StemSeparator.TeeSink(sink, wav) else sink
        }

        progress("KI wird gestartet …", null)
        try {
            StemSeparator.separate(context, pcm, sinks, checkCancel) { chunk, chunks ->
                progress(
                    "KI trennt die Instrumente … Abschnitt ${minOf(chunk + 1, chunks)} von $chunks\n" +
                        "Das dauert einige Minuten – lass die App dabei geöffnet.",
                    chunk.toFloat() / chunks,
                )
            }
            wav?.close()
            if (wav != null) {
                File(wavFile.path + ".part").renameTo(wavFile)
                StemStore.cleanup(context)
            }
        } finally {
            File(wavFile.path + ".part").let { if (it.exists()) { runCatching { wav?.close() }; it.delete() } }
        }

        progress("Erkenne die Noten …", null)
        for ((inst, sink) in analysis) {
            checkCancel()
            saveCharts(context, r.songKey, inst, BeatAnalyzer.detectOnsets(sink.toPcm()))
        }
    }

    private fun saveCharts(context: Context, songKey: String, instrument: Instrument, onsets: List<BeatAnalyzer.Onset>) {
        val seed = (songKey + instrument.name).hashCode().toLong()
        for (d in Difficulty.entries) {
            ChartStore.save(context, songKey, instrument, d, ChartGenerator.generate(onsets, d, instrument.melodic, seed))
        }
    }
}
