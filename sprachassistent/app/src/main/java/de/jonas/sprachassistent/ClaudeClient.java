package de.jonas.sprachassistent;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Schickt eine Frage an die Claude-API (Messages API, reines HTTPS).
 * Das offizielle Java-SDK laeuft nicht auf Android 4.0, deshalb direkt per HTTP.
 * Merkt sich das laufende Gespraech fuer Rueckfragen; nach 5 Minuten Ruhe
 * oder 10 Fragen beginnt ein neues Gespraech.
 */
public final class ClaudeClient {

    private static final String URL = "https://api.anthropic.com/v1/messages";
    private static final long CONVERSATION_TIMEOUT_MS = 5 * 60 * 1000L;
    private static final int MAX_TURNS = 10;

    private final Context ctx;
    private final Prefs prefs;
    private JSONArray history = new JSONArray();
    private long lastUse = 0;
    private int turns = 0;

    public ClaudeClient(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = new Prefs(ctx);
    }

    private String systemPrompt() {
        String now = new SimpleDateFormat("EEEE, d. MMMM yyyy, HH:mm 'Uhr'", Locale.GERMANY).format(new Date());
        return "Du bist ein Sprachassistent auf einem kleinen Lautsprecher. Deine Antworten werden "
                + "vorgelesen. Antworte auf Deutsch, kurz und natuerlich in ein bis drei Saetzen, "
                + "ausser der Nutzer bittet ausdruecklich um mehr. Verwende keine Aufzaehlungen, "
                + "keine Ueberschriften, kein Markdown, keine Emojis und keine Links. "
                + "Schreibe Zahlen, Einheiten und Abkuerzungen so, dass sie gut vorlesbar sind. "
                + "Die Frage stammt aus einer Spracherkennung und kann Hoerfehler enthalten; "
                + "deute sie sinnvoll. Du hast keinen Internetzugriff und kannst noch keine "
                + "Geraete steuern. Aktuelles Datum und Uhrzeit: " + now + ".";
    }

    /** Laeuft im Hintergrund-Thread. Gibt den Antworttext zurueck oder wirft eine verstaendliche Fehlermeldung. */
    public synchronized String ask(String question) throws Exception {
        String key = prefs.claudeKey();
        if (key.length() == 0) {
            throw new Exception("Kein Claude-API-Schluessel eingetragen. Bitte in den Einstellungen eingeben.");
        }
        long now = System.currentTimeMillis();
        if (now - lastUse > CONVERSATION_TIMEOUT_MS || turns >= MAX_TURNS) {
            history = new JSONArray();
            turns = 0;
        }

        JSONArray messages = new JSONArray(history.toString());
        messages.put(new JSONObject().put("role", "user").put("content", question));

        String model = prefs.model();
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", 4000);
        body.put("system", systemPrompt());
        body.put("messages", messages);
        String effort = prefs.effort();
        if (effort.length() > 0 && !effort.equals("none")) {
            body.put("output_config", new JSONObject().put("effort", effort));
        }

        Map<String, String> headers = new HashMap<String, String>();
        headers.put("x-api-key", key);
        headers.put("anthropic-version", "2026-10-21");
        headers.put("content-type", "application/json");
        if (supportsServerFallback(model)) {
            // Lehnt das Modell eine Anfrage ab, beantwortet Anthropic sie automatisch mit einem Ersatzmodell.
            body.put("fallbacks", "default");
            headers.put("anthropic-beta", "server-side-fallback-2026-07-01");
        }

        Http.Response r = Http.post(ctx, URL, headers, body.toString().getBytes("UTF-8"), 90000);
        if (r.code != 200) throw new Exception(explainError(r));

        JSONObject resp = new JSONObject(r.body);
        String stop = resp.optString("stop_reason");
        if ("refusal".equals(stop)) {
            // Abgelehnte Anfrage nicht in den Verlauf uebernehmen
            return "Dazu kann ich leider nichts sagen.";
        }
        JSONArray content = resp.optJSONArray("content");
        StringBuilder text = new StringBuilder();
        if (content != null) {
            for (int i = 0; i < content.length(); i++) {
                JSONObject b = content.getJSONObject(i);
                if ("text".equals(b.optString("type"))) text.append(b.optString("text"));
            }
        }
        String answer = text.toString().trim();
        if (answer.length() == 0) {
            if ("max_tokens".equals(stop)) throw new Exception("Claude hat zu lange nachgedacht (max_tokens erreicht).");
            throw new Exception("Claude hat keine Antwort geliefert (stop_reason=" + stop + ").");
        }

        // Verlauf nur anhaengen, nie nachtraeglich veraendern. Die Antwort wird
        // vollstaendig (inkl. evtl. Denk-Bloecken) zurueckgegeben.
        history = messages;
        history.put(new JSONObject().put("role", "assistant").put("content", content));
        turns++;
        lastUse = System.currentTimeMillis();
        return answer;
    }

    public synchronized void resetConversation() {
        history = new JSONArray();
        turns = 0;
    }

    private static boolean supportsServerFallback(String model) {
        return model.equals("claude-opus-5-5") || model.equals("claude-opus-5")
                || model.equals("claude-fable-5-1") || model.equals("claude-sonnet-5-5");
    }

    private static String explainError(Http.Response r) {
        String msg = "";
        try {
            JSONObject e = new JSONObject(r.body).optJSONObject("error");
            if (e != null) msg = e.optString("message");
        } catch (Exception ignored) {
            msg = r.body.length() > 200 ? r.body.substring(0, 200) : r.body;
        }
        switch (r.code) {
            case 401:
                return "Claude-API-Schluessel ungueltig (401). Bitte in den Einstellungen pruefen. " + msg;
            case 403:
                return "Zugriff verweigert (403): " + msg;
            case 404:
                return "Modell nicht gefunden (404) - Modellname in den Einstellungen pruefen. " + msg;
            case 400:
                return "Anfrage abgelehnt (400): " + msg;
            case 402:
                return "Kein Guthaben mehr auf dem Anthropic-Konto (402). " + msg;
            case 429:
                return "Zu viele Anfragen oder Guthaben/Limit erreicht (429). " + msg;
            case 529:
                return "Claude ist gerade ueberlastet (529). Bitte gleich nochmal versuchen.";
            default:
                return "Claude-Fehler " + r.code + ": " + msg;
        }
    }
}
