package de.jonas.sprachassistent;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.List;
import java.util.Map;

import edu.cmu.pocketsphinx.Config;
import edu.cmu.pocketsphinx.Decoder;
import edu.cmu.pocketsphinx.Hypothesis;

/**
 * Lauscht offline mit PocketSphinx auf das Aktivierungswort.
 * Nach einem Treffer kann direkt weiter aufgenommen werden (fuer Whisper),
 * oder das Mikrofon wird freigegeben (fuer die Google-Spracherkennung).
 */
public final class WakeWordEngine {

    public interface Callback {
        /** Aktivierungswort erkannt. Rueckgabe true = gleich weiter aufnehmen (Frage). */
        boolean onWakeWord();

        /** Aufgenommene Frage (nur wenn onWakeWord true geliefert hat). len==0: nichts gesagt. */
        void onQuestionAudio(short[] pcm, int len, int sampleRate);

        /** Schwerer Fehler, Lauschen beendet. */
        void onEngineError(String message, Throwable t);
    }

    public static final int SAMPLE_RATE = 16000;
    private static final int CHUNK = 1600; // 100 ms
    private static final String MODEL_VERSION = "1";

    private final Context ctx;
    private Decoder decoder;
    private String configKey = "";
    private volatile Thread thread;
    private Callback callback;

    private static boolean libLoaded;

    private static synchronized void loadLib() throws Exception {
        if (libLoaded) return;
        try {
            System.loadLibrary("pocketsphinx_jni");
            libLoaded = true;
        } catch (Throwable t) {
            throw new Exception("PocketSphinx-Bibliothek konnte nicht geladen werden: " + t, t);
        }
    }

    public WakeWordEngine(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** Laedt Modell und Woerterbuch. Dauert auf dem alten Handy einige Sekunden. Im Hintergrund aufrufen. */
    public synchronized void prepare(String phrase, String override, double threshold) throws Exception {
        String key = phrase + "|" + override + "|" + threshold;
        if (decoder != null && key.equals(configKey)) return;
        loadLib();
        long t0 = System.currentTimeMillis();
        File dir = new File(ctx.getFilesDir(), "ps");
        File hmm = new File(dir, "en-us-ptm");
        copyModelIfNeeded(hmm);

        Map<String, List<String>> prons = Phonetics.lookup(ctx, phrase, override);
        File dict = new File(dir, "keyword.dict");
        Writer w = new OutputStreamWriter(new FileOutputStream(dict), "UTF-8");
        StringBuilder used = new StringBuilder();
        for (Map.Entry<String, List<String>> e : prons.entrySet()) {
            int v = 0;
            for (String p : e.getValue()) {
                if (p.trim().length() == 0) continue;
                w.write(e.getKey() + (v == 0 ? "" : "(" + (v + 1) + ")") + " " + p + "\n");
                used.append(e.getKey()).append(" = ").append(p).append("\n");
                v++;
            }
            if (v == 0) {
                w.close();
                throw new Exception("Fuer \"" + e.getKey() + "\" konnte keine Aussprache bestimmt werden.");
            }
        }
        w.close();
        String keyphrase = Phonetics.joinWords(prons.keySet().toArray(new String[0]), " ");

        Config c = Decoder.defaultConfig();
        c.setString("-hmm", hmm.getAbsolutePath());
        c.setString("-dict", dict.getAbsolutePath());
        c.setString("-keyphrase", keyphrase);
        c.setFloat("-kws_threshold", threshold);
        c.setFloat("-samprate", SAMPLE_RATE);
        c.setString("-logfn", new File(dir, "pocketsphinx.log").getAbsolutePath());
        Decoder d = new Decoder(c);
        if (decoder != null) decoder.delete();
        decoder = d;
        configKey = key;
        AppLog.info("Aktivierungswort \"" + keyphrase + "\" geladen in "
                + (System.currentTimeMillis() - t0) + " ms. Aussprache:\n" + used.toString().trim());
    }

    private void copyModelIfNeeded(File hmm) throws Exception {
        File marker = new File(hmm, ".version");
        if (marker.exists()) return;
        hmm.mkdirs();
        AssetManager am = ctx.getAssets();
        for (String f : am.list("ps/en-us-ptm")) {
            InputStream in = am.open("ps/en-us-ptm/" + f);
            OutputStream out = new FileOutputStream(new File(hmm, f));
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
        }
        FileOutputStream m = new FileOutputStream(marker);
        m.write(MODEL_VERSION.getBytes());
        m.close();
        AppLog.info("Sprachmodell entpackt");
    }

    public boolean isRunning() {
        return thread != null;
    }

    public synchronized void start(Callback cb) {
        if (thread != null) return;
        if (decoder == null) {
            cb.onEngineError("Worterkennung nicht vorbereitet", null);
            return;
        }
        callback = cb;
        Thread t = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "WakeWord");
        thread = t;
        t.start();
    }

    private boolean active() {
        return thread == Thread.currentThread();
    }

    /** Beendet das Lauschen und wartet, bis das Mikrofon freigegeben ist. */
    public void stop() {
        Thread t = thread;
        thread = null;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(3000);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private AudioRecord openMic() {
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(min, SAMPLE_RATE * 2); // 1 Sekunde Puffer
        int[] sources = {MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};
        for (int src : sources) {
            AudioRecord r = new AudioRecord(src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
            if (r.getState() == AudioRecord.STATE_INITIALIZED) return r;
            r.release();
        }
        return null;
    }

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        AudioRecord rec = openMic();
        if (rec == null) {
            synchronized (this) {
                if (active()) thread = null;
            }
            callback.onEngineError("Mikrofon konnte nicht geoeffnet werden (wird es von einer anderen App benutzt?)", null);
            return;
        }
        short[] buf = new short[CHUNK];
        long procNanos = 0;
        long audioSamples = 0;
        long lastReport = System.currentTimeMillis();
        long uttStart = lastReport;
        int peak = 0;
        boolean continueRecording = false;
        try {
            rec.startRecording();
            if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("Aufnahme startet nicht");
            }
            decoder.startUtt();
            while (active()) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) {
                    throw new IllegalStateException("Mikrofon liefert keine Daten (Fehler " + n + ")");
                }
                for (int i = 0; i < n; i++) {
                    int a = Math.abs((int) buf[i]);
                    if (a > peak) peak = a;
                }
                long t = System.nanoTime();
                decoder.processRaw(buf, n, false, false);
                Hypothesis h = decoder.hyp();
                procNanos += System.nanoTime() - t;
                audioSamples += n;

                long now = System.currentTimeMillis();
                if (now - lastReport > 5000) {
                    // Anteil der Rechenzeit: unter 100 % = Handy kommt hinterher
                    double audioSec = audioSamples / (double) SAMPLE_RATE;
                    int load = (int) Math.round(procNanos / 1e9 / audioSec * 100);
                    int level = (int) Math.round(peak * 100.0 / 32767);
                    AppLog.setPerfInfo("Rechenlast Worterkennung: " + load + " %   Mikrofon-Pegel: " + level + " %");
                    procNanos = 0;
                    audioSamples = 0;
                    peak = 0;
                    lastReport = now;
                }

                if (h != null) {
                    decoder.endUtt();
                    AppLog.info("Aktivierungswort erkannt: \"" + h.getHypstr() + "\"");
                    continueRecording = callback.onWakeWord();
                    if (continueRecording) {
                        recordQuestion(rec);
                    }
                    break;
                }
                if (now - uttStart > 5 * 60 * 1000L) {
                    // gelegentlich neu beginnen, damit der Speicher nicht waechst
                    decoder.endUtt();
                    decoder.startUtt();
                    uttStart = now;
                }
            }
            if (!continueRecording) {
                try {
                    decoder.endUtt();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            try {
                decoder.endUtt();
            } catch (Throwable ignored) {
            }
            if (active()) callback.onEngineError("Fehler beim Lauschen: " + t.getMessage(), t);
        } finally {
            try {
                rec.stop();
            } catch (Throwable ignored) {
            }
            rec.release();
            synchronized (this) {
                if (active()) thread = null;
            }
        }
    }

    /** Nimmt die Frage auf, bis eine Pause kommt (max. 15 Sekunden). */
    private void recordQuestion(AudioRecord rec) {
        final int maxSamples = SAMPLE_RATE * 15;
        short[] pcm = new short[maxSamples];
        int len = 0;
        short[] buf = new short[CHUNK];
        // Grundrauschen = leisester Abschnitt am Anfang (robust gegen den Piepton)
        long minEnergy = Long.MAX_VALUE;
        boolean speechStarted = false;
        int silentChunks = 0;
        int chunks = 0;
        while (active() && len + CHUNK <= maxSamples) {
            int n = rec.read(buf, 0, CHUNK);
            if (n <= 0) break;
            System.arraycopy(buf, 0, pcm, len, n);
            len += n;
            chunks++;
            long energy = 0;
            for (int i = 0; i < n; i++) energy += Math.abs((int) buf[i]);
            energy /= n;
            if (chunks <= 10 && energy < minEnergy) minEnergy = energy;
            if (chunks < 2) continue;
            long base = Math.max(150, minEnergy);
            boolean loud = energy > base * 3;
            if (loud) {
                speechStarted = true;
                silentChunks = 0;
            } else {
                silentChunks++;
            }
            if (!speechStarted && chunks > 60) break;      // 6 s nichts gesagt
            if (speechStarted && silentChunks >= 12) break; // 1,2 s Pause = fertig
        }
        callback.onQuestionAudio(pcm, speechStarted ? len : 0, SAMPLE_RATE);
    }

    public void release() {
        stop();
        synchronized (this) {
            if (decoder != null) {
                decoder.delete();
                decoder = null;
            }
            configKey = "";
        }
    }
}
