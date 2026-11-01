package de.jonas.sprachassistent;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/** Zugriff auf die Einstellungen (werden nur auf dem Handy gespeichert). */
public final class Prefs {

    public static final String STT_GOOGLE = "google";
    public static final String STT_WHISPER = "whisper";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = PreferenceManager.getDefaultSharedPreferences(ctx.getApplicationContext());
    }

    public SharedPreferences raw() {
        return sp;
    }

    public String claudeKey() {
        return sp.getString("claude_key", "").trim();
    }

    public String model() {
        String m = sp.getString("claude_model", "").trim();
        return m.length() == 0 ? "claude-opus-5-5" : m;
    }

    public String effort() {
        return sp.getString("claude_effort", "low");
    }

    public String wakeWord() {
        return sp.getString("wake_word", "computer").trim().toLowerCase(java.util.Locale.GERMANY);
    }

    public String pronunciation() {
        return sp.getString("wake_pron", "").trim().toUpperCase(java.util.Locale.US);
    }

    public double threshold() {
        try {
            return Double.parseDouble(sp.getString("wake_threshold", "1e-20"));
        } catch (NumberFormatException e) {
            return 1e-20;
        }
    }

    public String sttMode() {
        return sp.getString("stt_mode", STT_GOOGLE);
    }

    public String openAiKey() {
        return sp.getString("openai_key", "").trim();
    }

    public boolean useBluetooth() {
        return sp.getBoolean("use_bluetooth", true);
    }

    public boolean answerViaA2dp() {
        return sp.getBoolean("answer_a2dp", false);
    }

    public boolean beep() {
        return sp.getBoolean("beep", true);
    }

    public boolean wakeTestOnly() {
        return sp.getBoolean("wake_test_only", true);
    }

    public void setWakeTestOnly(boolean b) {
        sp.edit().putBoolean("wake_test_only", b).commit();
    }

    public boolean autostart() {
        return sp.getBoolean("autostart", true);
    }

    public boolean serviceWanted() {
        return sp.getBoolean("service_wanted", false);
    }

    public void setServiceWanted(boolean b) {
        sp.edit().putBoolean("service_wanted", b).commit();
    }

    public String lastBtDevice() {
        return sp.getString("last_bt_device", "");
    }

    public void setLastBtDevice(String addr) {
        sp.edit().putString("last_bt_device", addr).commit();
    }
}
