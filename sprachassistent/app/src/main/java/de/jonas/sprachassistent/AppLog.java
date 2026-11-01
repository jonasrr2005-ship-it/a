package de.jonas.sprachassistent;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * Einfaches Protokoll im Arbeitsspeicher. Der Dienst schreibt hinein,
 * der Hauptbildschirm zeigt es an. Fehler werden besonders markiert.
 */
public final class AppLog {

    public interface Listener {
        void onLogChanged();
        void onStatusChanged(String status);
    }

    private static final String TAG = "Sprachassistent";
    private static final int MAX_LINES = 150;
    private static final LinkedList<String> LINES = new LinkedList<String>();
    private static final List<Listener> LISTENERS = new ArrayList<Listener>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.GERMANY);

    private static volatile String status = "Gestoppt";
    private static volatile String lastAnswer = "";
    private static volatile String lastError = "";
    private static volatile String perfInfo = "";

    private AppLog() {
    }

    public static void info(String msg) {
        add("", msg);
        Log.i(TAG, msg);
    }

    public static void error(String msg) {
        lastError = TIME.format(new Date()) + " " + msg;
        add("FEHLER: ", msg);
        Log.e(TAG, msg);
    }

    public static void error(String msg, Throwable t) {
        String detail = t == null ? "" : " (" + t.getClass().getSimpleName()
                + (t.getMessage() != null ? ": " + t.getMessage() : "") + ")";
        error(msg + detail);
        if (t != null) Log.e(TAG, msg, t);
    }

    private static void add(String prefix, String msg) {
        synchronized (LINES) {
            LINES.addFirst(TIME.format(new Date()) + "  " + prefix + msg);
            while (LINES.size() > MAX_LINES) LINES.removeLast();
        }
        notifyLog();
    }

    public static String text() {
        StringBuilder sb = new StringBuilder();
        synchronized (LINES) {
            for (String l : LINES) sb.append(l).append('\n');
        }
        return sb.toString();
    }

    public static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
        lastError = "";
        notifyLog();
    }

    public static void setStatus(String s) {
        status = s;
        final String st = s;
        MAIN.post(new Runnable() {
            public void run() {
                synchronized (LISTENERS) {
                    for (Listener l : new ArrayList<Listener>(LISTENERS)) l.onStatusChanged(st);
                }
            }
        });
    }

    public static String status() {
        return status;
    }

    public static void setLastAnswer(String a) {
        lastAnswer = a;
        notifyLog();
    }

    public static String lastAnswer() {
        return lastAnswer;
    }

    public static String lastError() {
        return lastError;
    }

    public static void setPerfInfo(String p) {
        perfInfo = p;
        setStatus(status);
    }

    public static String perfInfo() {
        return perfInfo;
    }

    public static void addListener(Listener l) {
        synchronized (LISTENERS) {
            LISTENERS.add(l);
        }
    }

    public static void removeListener(Listener l) {
        synchronized (LISTENERS) {
            LISTENERS.remove(l);
        }
    }

    private static void notifyLog() {
        MAIN.post(new Runnable() {
            public void run() {
                synchronized (LISTENERS) {
                    for (Listener l : new ArrayList<Listener>(LISTENERS)) l.onLogChanged();
                }
            }
        });
    }
}
