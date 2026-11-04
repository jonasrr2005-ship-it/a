package de.offline.ki

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Handler
import android.os.Looper
import java.util.Locale

/**
 * Sprachausgabe (TextToSpeech) und Spracheingabe (SpeechRecognizer).
 * Beides nutzt die Sprachdienste des Handys im Offline-Modus. Für Deutsch müssen
 * einmalig die Offline-Sprachdaten installiert sein (Samsung: Einstellungen →
 * Allgemeine Verwaltung → Sprache → Text-zu-Sprache / Spracherkennung).
 */
class Voice(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var spokenUpTo = 0
    private var utteranceCounter = 0

    /** Wird aufgerufen, wenn die komplette Antwort fertig vorgelesen wurde. */
    var onFinishedSpeaking: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.GERMANY
                ttsReady = true
            }
        }.apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onError(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == FINAL) main.post { onFinishedSpeaking?.invoke() }
                }
            })
        }
    }

    // ---------- Sprachausgabe, satzweise schon während die KI noch schreibt ----------

    fun beginAnswer() {
        spokenUpTo = 0
    }

    /** Liest alle neu fertig gewordenen Sätze vor. */
    fun feed(text: String) {
        if (!ttsReady) return
        val end = lastSentenceEnd(text)
        if (end > spokenUpTo) {
            say(text.substring(spokenUpTo, end), "s${utteranceCounter++}")
            spokenUpTo = end
        }
    }

    /** Antwort ist fertig: Rest vorlesen. */
    fun finish(text: String) {
        if (!ttsReady) { onFinishedSpeaking?.invoke(); return }
        val rest = if (spokenUpTo < text.length) text.substring(spokenUpTo) else ""
        spokenUpTo = text.length
        say(rest, FINAL)
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    private fun say(raw: String, id: String) {
        val clean = raw
            .replace(Regex("[*_#`>~]"), "")
            .replace(Regex("[\\p{So}\\p{Cn}\\x{1F000}-\\x{1FFFF}]"), "")
            .trim()
        // Auch leere Endstücke als "FINAL" senden, damit onDone sicher kommt.
        tts?.speak(clean.ifEmpty { " " }, TextToSpeech.QUEUE_ADD, null, id)
    }

    private fun lastSentenceEnd(t: String): Int {
        var i = t.length - 2
        while (i >= spokenUpTo) {
            val c = t[i]
            if ((c == '.' || c == '!' || c == '?' || c == '\n') && t[i + 1].isWhitespace()) return i + 1
            i--
        }
        return spokenUpTo
    }

    // ---------- Spracheingabe ----------

    private var recognizer: SpeechRecognizer? = null

    val canListen: Boolean get() =
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context) ||
            SpeechRecognizer.isRecognitionAvailable(context)

    fun listen(onPartial: (String) -> Unit, onResult: (String?) -> Unit, onError: (String) -> Unit) {
        recognizer?.destroy()
        val r = if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }
            override fun onResults(results: Bundle?) {
                onResult(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
            }
            override fun onError(error: Int) {
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onResult(null)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> onError("Mikrofon-Berechtigung fehlt")
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        onError("Deutsche Offline-Spracherkennung ist nicht installiert")
                    else -> onError("Spracherkennung fehlgeschlagen (Code $error)")
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        r.startListening(intent)
    }

    fun stopListening() {
        recognizer?.cancel()
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
    }

    companion object {
        private const val FINAL = "final"
    }
}
