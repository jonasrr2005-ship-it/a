package de.jonas.sprachassistent;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Optionale Spracherkennung ueber OpenAI Whisper (braucht eigenen OpenAI-Schluessel).
 * Wird nur benutzt, wenn in den Einstellungen ausgewaehlt. Claude selbst kann
 * keine Tonaufnahmen annehmen, deshalb ist dafuer ein extra Dienst noetig.
 */
public final class WhisperClient {

    private WhisperClient() {
    }

    public static String transcribe(Context ctx, String apiKey, short[] pcm, int len, int sampleRate) throws Exception {
        if (apiKey.length() == 0) {
            throw new Exception("Kein OpenAI-Schluessel eingetragen (noetig fuer Whisper-Spracherkennung).");
        }
        byte[] wav = toWav(pcm, len, sampleRate);
        String boundary = "----sprachassistent" + System.currentTimeMillis();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        field(b, boundary, "model", "whisper-1");
        field(b, boundary, "language", "de");
        field(b, boundary, "response_format", "json");
        b.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"frage.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes("UTF-8"));
        b.write(wav);
        b.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));

        Map<String, String> h = new HashMap<String, String>();
        h.put("Authorization", "Bearer " + apiKey);
        h.put("Content-Type", "multipart/form-data; boundary=" + boundary);
        Http.Response r = Http.post(ctx, "https://api.openai.com/v1/audio/transcriptions", h, b.toByteArray(), 60000);
        if (r.code != 200) {
            String msg = r.body;
            try {
                msg = new JSONObject(r.body).getJSONObject("error").optString("message");
            } catch (Exception ignored) {
            }
            if (r.code == 401) throw new Exception("OpenAI-Schluessel ungueltig (401).");
            throw new Exception("Whisper-Fehler " + r.code + ": " + msg);
        }
        return new JSONObject(r.body).optString("text").trim();
    }

    private static void field(ByteArrayOutputStream b, String boundary, String name, String value) throws Exception {
        b.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes("UTF-8"));
    }

    static byte[] toWav(short[] pcm, int len, int rate) {
        int dataLen = len * 2;
        byte[] out = new byte[44 + dataLen];
        writeStr(out, 0, "RIFF");
        writeInt(out, 4, 36 + dataLen);
        writeStr(out, 8, "WAVE");
        writeStr(out, 12, "fmt ");
        writeInt(out, 16, 16);
        writeShort(out, 20, 1);
        writeShort(out, 22, 1);
        writeInt(out, 24, rate);
        writeInt(out, 28, rate * 2);
        writeShort(out, 32, 2);
        writeShort(out, 34, 16);
        writeStr(out, 36, "data");
        writeInt(out, 40, dataLen);
        for (int i = 0; i < len; i++) {
            out[44 + 2 * i] = (byte) (pcm[i] & 0xff);
            out[45 + 2 * i] = (byte) ((pcm[i] >> 8) & 0xff);
        }
        return out;
    }

    private static void writeStr(byte[] b, int off, String s) {
        for (int i = 0; i < 4; i++) b[off + i] = (byte) s.charAt(i);
    }

    private static void writeInt(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static void writeShort(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
    }
}
