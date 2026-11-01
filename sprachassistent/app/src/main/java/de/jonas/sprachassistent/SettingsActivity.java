package de.jonas.sprachassistent;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;

/** Einstellungsbildschirm (einfaches Android-Standardmenue, sehr sparsam). */
public class SettingsActivity extends PreferenceActivity
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        addPreferencesFromResource(R.xml.settings);
        updateAll(getPreferenceScreen());
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onResume() {
        super.onResume();
        getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onPause() {
        getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        Preference p = findPreference(key);
        if (p != null) updateSummary(p);
    }

    private void updateAll(PreferenceGroup g) {
        for (int i = 0; i < g.getPreferenceCount(); i++) {
            Preference p = g.getPreference(i);
            if (p instanceof PreferenceGroup) updateAll((PreferenceGroup) p);
            else updateSummary(p);
        }
    }

    private void updateSummary(Preference p) {
        if (p instanceof ListPreference) {
            CharSequence e = ((ListPreference) p).getEntry();
            if (e != null) p.setSummary(e);
        } else if (p instanceof EditTextPreference) {
            String v = ((EditTextPreference) p).getText();
            String key = p.getKey();
            if (v == null || v.trim().length() == 0) {
                if ("wake_pron".equals(key)) p.setSummary("leer = automatisch (empfohlen)");
                else if ("claude_model".equals(key)) p.setSummary("leer = claude-opus-5-5");
                else p.setSummary("(nicht eingetragen)");
            } else if (key.endsWith("_key")) {
                String t = v.trim();
                p.setSummary(t.length() > 10 ? t.substring(0, 7) + "..." + t.substring(t.length() - 4) : "gesetzt");
            } else {
                p.setSummary(v);
            }
        }
    }
}
