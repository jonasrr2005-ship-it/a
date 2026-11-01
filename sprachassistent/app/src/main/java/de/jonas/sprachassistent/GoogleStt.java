package de.jonas.sprachassistent;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

/** Spracherkennung der Frage mit der im Handy eingebauten Google-Spracheingabe (braucht Internet). */
public final class GoogleStt {

    public interface Result {
        void onText(String text);

        void onFailed(String message);
    }

    private final Context ctx;
    private SpeechRecognizer sr;

    public GoogleStt(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** Muss im Haupt-Thread aufgerufen werden. */
    public void listen(final Result result) {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            result.onFailed("Google-Spracherkennung ist auf diesem Handy nicht vorhanden. "
                    + "In den Einstellungen auf Whisper umstellen.");
            return;
        }
        cancel();
        sr = SpeechRecognizer.createSpeechRecognizer(ctx);
        sr.setRecognitionListener(new RecognitionListener() {
            boolean done;

            public void onReadyForSpeech(Bundle params) {
                AppLog.setStatus("Hoere zu ... (Google)");
            }

            public void onBeginningOfSpeech() {
            }

            public void onRmsChanged(float rmsdB) {
            }

            public void onBufferReceived(byte[] buffer) {
            }

            public void onEndOfSpeech() {
                AppLog.setStatus("Erkenne Sprache ...");
            }

            public void onError(int error) {
                if (done) return;
                done = true;
                destroy();
                result.onFailed(explain(error));
            }

            public void onResults(Bundle results) {
                if (done) return;
                done = true;
                ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                destroy();
                if (list == null || list.isEmpty() || list.get(0).trim().length() == 0) {
                    result.onFailed("Nichts verstanden");
                } else {
                    result.onText(list.get(0).trim());
                }
            }

            public void onPartialResults(Bundle partialResults) {
            }

            public void onEvent(int eventType, Bundle params) {
            }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE");
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.getPackageName());
        sr.startListening(i);
    }

    public void cancel() {
        if (sr != null) {
            try {
                sr.cancel();
            } catch (Exception ignored) {
            }
            destroy();
        }
    }

    private void destroy() {
        if (sr != null) {
            try {
                sr.destroy();
            } catch (Exception ignored) {
            }
            sr = null;
        }
    }

    private static String explain(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "Google-Spracherkennung: Netzwerkfehler (" + error + ")";
            case SpeechRecognizer.ERROR_AUDIO:
                return "Google-Spracherkennung: Problem mit dem Mikrofon";
            case SpeechRecognizer.ERROR_SERVER:
                return "Google-Spracherkennung: Server-Fehler - evtl. funktioniert der alte Google-Dienst nicht mehr. Dann in den Einstellungen Whisper waehlen.";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "Nichts gehoert (Zeit abgelaufen)";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "Nichts verstanden";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "Google-Spracherkennung ist beschaeftigt";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Google-Spracherkennung: fehlende Berechtigung";
            default:
                return "Google-Spracherkennung: Fehler " + error;
        }
    }
}
