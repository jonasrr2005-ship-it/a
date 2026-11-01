package de.jonas.sprachassistent;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.HashMap;
import java.util.Locale;

/** Sprachausgabe auf Deutsch mit der im Handy eingebauten TextToSpeech. */
public final class Speaker implements TextToSpeech.OnInitListener {

    private final Handler handler = new Handler();
    private TextToSpeech tts;
    private boolean ready;
    private Runnable pendingDone;
    private int counter;

    public Speaker(Context ctx) {
        tts = new TextToSpeech(ctx.getApplicationContext(), this);
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            AppLog.error("Sprachausgabe (TextToSpeech) konnte nicht gestartet werden. Ist eine Sprachausgabe installiert?");
            return;
        }
        int r = tts.setLanguage(Locale.GERMANY);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            r = tts.setLanguage(Locale.GERMAN);
        }
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            AppLog.error("Keine deutsche Stimme installiert. Einstellungen > Sprache & Eingabe > Text-in-Sprache: Deutsch installieren.");
        } else {
            AppLog.info("Sprachausgabe bereit (Deutsch)");
        }
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            public void onStart(String id) {
            }

            public void onDone(String id) {
                finished(id);
            }

            public void onError(String id) {
                AppLog.error("Fehler bei der Sprachausgabe");
                finished(id);
            }
        });
        ready = true;
    }

    private void finished(final String id) {
        handler.post(new Runnable() {
            public void run() {
                if (!("u" + counter).equals(id)) return;
                Runnable d = pendingDone;
                pendingDone = null;
                if (d != null) d.run();
            }
        });
    }

    /** Spricht den Text. onDone wird danach im Haupt-Thread aufgerufen (auch bei Fehlern). */
    public void speak(String text, boolean viaCallStream, final Runnable onDone) {
        if (!ready) {
            AppLog.error("Sprachausgabe noch nicht bereit");
            if (onDone != null) handler.post(onDone);
            return;
        }
        counter++;
        pendingDone = onDone;
        String id = "u" + counter;
        HashMap<String, String> p = new HashMap<String, String>();
        p.put(TextToSpeech.Engine.KEY_PARAM_STREAM,
                String.valueOf(viaCallStream ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC));
        // in Saetze teilen - alte Sprachausgaben moegen keine langen Texte
        String[] parts = text.split("(?<=[.!?])\\s+");
        for (int i = 0; i < parts.length; i++) {
            if (i == parts.length - 1) p.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id);
            int r = tts.speak(parts[i], i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, p);
            if (r != TextToSpeech.SUCCESS) {
                AppLog.error("Sprachausgabe hat den Text abgelehnt");
                pendingDone = null;
                if (onDone != null) handler.post(onDone);
                return;
            }
        }
        // Sicherheitsnetz, falls die Sprachausgabe nie "fertig" meldet
        final int myCounter = counter;
        handler.postDelayed(new Runnable() {
            public void run() {
                if (counter == myCounter && pendingDone != null) {
                    Runnable d = pendingDone;
                    pendingDone = null;
                    d.run();
                }
            }
        }, 20000 + text.length() * 150L);
    }

    public void stopSpeaking() {
        pendingDone = null;
        if (tts != null) tts.stop();
    }

    public static void beep(boolean viaCallStream) {
        try {
            final ToneGenerator tg = new ToneGenerator(
                    viaCallStream ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC, 80);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 200);
            new Handler().postDelayed(new Runnable() {
                public void run() {
                    tg.release();
                }
            }, 400);
        } catch (Exception e) {
            AppLog.error("Piepton nicht moeglich", e);
        }
    }

    public void shutdown() {
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
        ready = false;
    }
}
