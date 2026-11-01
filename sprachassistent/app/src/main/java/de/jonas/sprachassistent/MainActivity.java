package de.jonas.sprachassistent;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;

/** Hauptbildschirm: Status, Start/Stopp, Testfeld, letzte Antwort, Fehlerprotokoll. */
public class MainActivity extends Activity implements AppLog.Listener {

    private TextView status, perf, answer, log, lastError;
    private Button startStop;
    private CheckBox testOnly;
    private EditText input;
    private Prefs prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);
        status = (TextView) findViewById(R.id.status);
        perf = (TextView) findViewById(R.id.perf);
        answer = (TextView) findViewById(R.id.answer);
        log = (TextView) findViewById(R.id.log);
        lastError = (TextView) findViewById(R.id.last_error);
        startStop = (Button) findViewById(R.id.start_stop);
        testOnly = (CheckBox) findViewById(R.id.test_only);
        input = (EditText) findViewById(R.id.input);

        startStop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (AssistantService.isRunning()) {
                    AssistantService.stop(MainActivity.this);
                } else {
                    if (prefs.claudeKey().length() == 0 && !prefs.wakeTestOnly()) {
                        AppLog.error("Kein Claude-API-Schluessel eingetragen (Einstellungen). Der Testmodus fuer das Aktivierungswort geht auch ohne.");
                    }
                    AssistantService.start(MainActivity.this);
                }
                handlerRefresh();
            }
        });
        findViewById(R.id.settings).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        findViewById(R.id.send).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String t = input.getText().toString().trim();
                if (t.length() == 0) return;
                input.setText("");
                ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                        .hideSoftInputFromWindow(input.getWindowToken(), 0);
                prefs.setServiceWanted(true);
                startService(new Intent(MainActivity.this, AssistantService.class)
                        .setAction(AssistantService.ACTION_ASK)
                        .putExtra(AssistantService.EXTRA_TEXT, t));
            }
        });
        findViewById(R.id.clear).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                AppLog.clear();
            }
        });
        testOnly.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton c, boolean checked) {
                if (checked != prefs.wakeTestOnly()) prefs.setWakeTestOnly(checked);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppLog.addListener(this);
        testOnly.setChecked(prefs.wakeTestOnly());
        handlerRefresh();
        onLogChanged();
    }

    @Override
    protected void onPause() {
        AppLog.removeListener(this);
        super.onPause();
    }

    private void handlerRefresh() {
        status.postDelayed(new Runnable() {
            public void run() {
                onStatusChanged(AppLog.status());
            }
        }, 300);
        onStatusChanged(AppLog.status());
    }

    @Override
    public void onLogChanged() {
        log.setText(AppLog.text());
        String a = AppLog.lastAnswer();
        answer.setText(a.length() == 0 ? "(noch keine)" : a);
        String e = AppLog.lastError();
        lastError.setVisibility(e.length() == 0 ? View.GONE : View.VISIBLE);
        lastError.setText("Letzter Fehler: " + e);
    }

    @Override
    public void onStatusChanged(String s) {
        boolean on = AssistantService.isRunning();
        status.setText(on ? s : "Gestoppt");
        perf.setText(on ? AppLog.perfInfo() : "");
        startStop.setText(on ? "Stopp" : "Start");
    }
}
