package de.jonas.sprachassistent;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;

/** Kleiner HTTPS-Helfer, der immer die moderne TLS-Verbindung nutzt. */
public final class Http {

    public static final class Response {
        public final int code;
        public final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private Http() {
    }

    public static Response post(Context ctx, String url, Map<String, String> headers, byte[] body,
                                int timeoutMs) throws Exception {
        HttpsURLConnection c;
        try {
            c = (HttpsURLConnection) new URL(url).openConnection();
            c.setSSLSocketFactory(Tls.factory(ctx));
        } catch (Exception e) {
            throw new Exception("Verbindung konnte nicht vorbereitet werden: " + e.getMessage(), e);
        }
        try {
            c.setConnectTimeout(20000);
            c.setReadTimeout(timeoutMs);
            c.setDoOutput(true);
            c.setUseCaches(false);
            c.setRequestMethod("POST");
            c.setFixedLengthStreamingMode(body.length);
            c.setRequestProperty("Connection", "close");
            for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            OutputStream os = c.getOutputStream();
            os.write(body);
            os.close();
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Response(code, is == null ? "" : readAll(is));
        } catch (UnknownHostException e) {
            throw new Exception("Keine Internetverbindung (Server nicht gefunden). Ist das WLAN an?", e);
        } catch (SocketTimeoutException e) {
            throw new Exception("Zeitueberschreitung - der Server hat nicht rechtzeitig geantwortet.", e);
        } catch (SSLException e) {
            throw new Exception("Sichere Verbindung (TLS) fehlgeschlagen: " + e.getMessage()
                    + " - Stimmen Datum und Uhrzeit am Handy?", e);
        } catch (IOException e) {
            throw new Exception("Netzwerkfehler: " + e, e);
        } finally {
            c.disconnect();
        }
    }

    static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toString("UTF-8");
    }
}
