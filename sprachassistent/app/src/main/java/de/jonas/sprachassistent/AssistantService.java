package de.jonas.sprachassistent;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * Der Dauer-Dienst: laeuft im Vordergrund (mit Symbol in der Statusleiste),
 * lauscht auf das Aktivierungswort, nimmt die Frage auf, fragt Claude und
 * liest die Antwort vor.
 */
public class AssistantService extends Service
        implements WakeWordEngine.Callback, BluetoothAudio.Listener,
        SharedPreferences.OnSharedPreferenceChangeListener {

    public static final String ACTION_ASK = "de.jonas.sprachassistent.ASK";
    public static final String EXTRA_TEXT = "text";
    public static final String ACTION_STOP = "de.jonas.sprachassistent.STOP";
    private static final int NOTIF_ID = 1;

    private static volatile boolean running;

    private final Handler handler = new Handler();
    private Prefs prefs;
    private WakeWordEngine engine;
    private BluetoothAudio bt;
    private Speaker speaker;
    private GoogleStt googleStt;
    private ClaudeClient claude;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private boolean engineReady;
    private boolean preparing;
    private boolean busy;          // gerade Frage/Antwort in Arbeit
    private int wakeCount;
    private int engineErrors;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        prefs = new Prefs(this);
        startForeground(NOTIF_ID, buildNotification("Startet ..."));

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sprachassistent");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Sprachassistent");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }

        speaker = new Speaker(this);
        googleStt = new GoogleStt(this);
        claude = new ClaudeClient(this);
        engine = new WakeWordEngine(this);
        bt = new BluetoothAudio(this, this);
        if (prefs.useBluetooth()) bt.start();
        prefs.raw().registerOnSharedPreferenceChangeListener(this);
        scheduleWatchdog(this);
        AppLog.info("Dienst gestartet");
        prepareEngine();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            prefs.setServiceWanted(false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_ASK.equals(intent.getAction())) {
            String text = intent.getStringExtra(EXTRA_TEXT);
            if (text != null && text.trim().length() > 0) askFromText(text.trim());
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        prefs.raw().unregisterOnSharedPreferenceChangeListener(this);
        handler.removeCallbacksAndMessages(null);
        googleStt.cancel();
        engine.release();
        bt.stop();
        speaker.shutdown();
        if (wakeLock != null) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        AppLog.setStatus("Gestoppt");
        AppLog.setPerfInfo("");
        AppLog.info("Dienst beendet");
        super.onDestroy();
    }

    // ---------------------------------------------------------------- Ablauf

    private void prepareEngine() {
        if (preparing) return;
        preparing = true;
        engineReady = false;
        engine.stop();
        setStatus("Lade Worterkennung ...");
        final String word = prefs.wakeWord();
        final String pron = prefs.pronunciation();
        final double thr = prefs.threshold();
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                Throwable ex = null;
                try {
                    engine.prepare(word, pron, thr);
                } catch (Throwable t) {
                    err = "Worterkennung konnte nicht geladen werden: " + t.getMessage();
                    ex = t;
                }
                final String fErr = err;
                final Throwable fEx = ex;
                handler.post(new Runnable() {
                    public void run() {
                        preparing = false;
                        if (fErr != null) {
                            AppLog.error(fErr, fEx);
                            setStatus("Fehler - siehe Protokoll");
                            return;
                        }
                        engineReady = true;
                        startListening();
                    }
                });
            }
        }, "Prepare").start();
    }

    private void startListening() {
        if (!running || !engineReady || busy) return;
        engine.stop();
        engine.start(this);
        String mic = bt.isScoOn() ? "Bluetooth-Mikrofon" : "Handy-Mikrofon";
        setStatus("Lausche auf \"" + prefs.wakeWord() + "\" (" + mic + ")"
                + (prefs.wakeTestOnly() ? " - TESTMODUS" : ""));
    }

    private boolean callStream() {
        return bt.isScoOn();
    }

    /** Audio-Thread! */
    @Override
    public boolean onWakeWord() {
        final boolean testOnly = prefs.wakeTestOnly();
        final boolean whisper = !testOnly && Prefs.STT_WHISPER.equals(prefs.sttMode());
        handler.post(new Runnable() {
            public void run() {
                handleWake(testOnly, whisper);
            }
        });
        return whisper;
    }

    private void handleWake(boolean testOnly, boolean whisper) {
        wakeCount++;
        engineErrors = 0;
        if (prefs.beep()) Speaker.beep(callStream());
        if (testOnly) {
            AppLog.info("TEST: Aktivierungswort erkannt (" + wakeCount + ". Mal)");
            setStatus("Erkannt! (" + wakeCount + ". Mal)");
            handler.postDelayed(new Runnable() {
                public void run() {
                    startListening();
                }
            }, 800);
            return;
        }
        busy = true;
        if (whisper) {
            setStatus("Hoere zu ... (stell deine Frage)");
            return; // Aufnahme laeuft im Audio-Thread weiter
        }
        engine.stop(); // Mikrofon fuer Google freigeben
        setStatus("Hoere zu ... (stell deine Frage)");
        handler.postDelayed(new Runnable() {
            public void run() {
                googleStt.listen(new GoogleStt.Result() {
                    public void onText(String text) {
                        onQuestion(text);
                    }

                    public void onFailed(String message) {
                        AppLog.error(message);
                        finishTurn();
                    }
                });
            }
        }, 350);
    }

    /** Audio-Thread! */
    @Override
    public void onQuestionAudio(final short[] pcm, final int len, final int rate) {
        if (len == 0) {
            handler.post(new Runnable() {
                public void run() {
                    AppLog.info("Keine Frage gehoert");
                    finishTurn();
                }
            });
            return;
        }
        setStatus("Erkenne Sprache (Whisper) ...");
        new Thread(new Runnable() {
            public void run() {
                try {
                    final String text = WhisperClient.transcribe(AssistantService.this, prefs.openAiKey(), pcm, len, rate);
                    handler.post(new Runnable() {
                        public void run() {
                            if (text.length() == 0) {
                                AppLog.info("Nichts verstanden");
                                finishTurn();
                            } else {
                                onQuestion(text);
                            }
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        public void run() {
                            fail(e.getMessage());
                        }
                    });
                }
            }
        }, "Whisper").start();
    }

    private void onQuestion(String text) {
        AppLog.info("Frage: " + text);
        askClaude(text);
    }

    private void askFromText(String text) {
        if (busy) {
            AppLog.error("Bin noch beschaeftigt - bitte warten");
            return;
        }
        busy = true;
        googleStt.cancel();
        engine.stop();
        AppLog.info("Getippte Frage: " + text);
        askClaude(text);
    }

    private void askClaude(final String question) {
        setStatus("Frage Claude ...");
        new Thread(new Runnable() {
            public void run() {
                try {
                    final long t0 = System.currentTimeMillis();
                    final String answer = claude.ask(question);
                    handler.post(new Runnable() {
                        public void run() {
                            AppLog.info("Antwort nach " + (System.currentTimeMillis() - t0) / 100 / 10.0 + " s");
                            AppLog.setLastAnswer(answer);
                            speakThenListen(answer);
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        public void run() {
                            fail(e.getMessage());
                        }
                    });
                }
            }
        }, "Claude").start();
    }

    private void fail(String message) {
        AppLog.error(message);
        AppLog.setLastAnswer("(Fehler) " + message);
        speakThenListen("Es gab einen Fehler. Details stehen auf dem Bildschirm.");
    }

    private void speakThenListen(final String text) {
        setStatus("Spreche ...");
        if (prefs.answerViaA2dp() && bt.isScoOn()) {
            // fuer bessere Tonqualitaet kurz vom Telefon-Profil auf Musik-Profil wechseln
            bt.pause();
            handler.postDelayed(new Runnable() {
                public void run() {
                    speaker.speak(text, false, new Runnable() {
                        public void run() {
                            bt.resume();
                            handler.postDelayed(new Runnable() {
                                public void run() {
                                    finishTurn();
                                }
                            }, 1500);
                        }
                    });
                }
            }, 1200);
        } else {
            speaker.speak(text, callStream(), new Runnable() {
                public void run() {
                    finishTurn();
                }
            });
        }
    }

    private void finishTurn() {
        busy = false;
        startListening();
    }

    // ---------------------------------------------------------------- Ereignisse

    @Override
    public void onEngineError(final String message, final Throwable t) {
        handler.post(new Runnable() {
            public void run() {
                engineErrors++;
                AppLog.error(message, t);
                setStatus("Fehler beim Lauschen - neuer Versuch in 5 s");
                handler.postDelayed(new Runnable() {
                    public void run() {
                        startListening();
                    }
                }, engineErrors > 5 ? 30000 : 5000);
            }
        });
    }

    @Override
    public void onScoChanged(boolean connected) {
        // Mikrofon neu oeffnen, damit es ueber den richtigen Weg (Bluetooth/Handy) laeuft
        if (!busy && engineReady && engine.isRunning()) {
            handler.postDelayed(new Runnable() {
                public void run() {
                    if (!busy) startListening();
                }
            }, 500);
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if ("wake_word".equals(key) || "wake_threshold".equals(key) || "wake_pron".equals(key)) {
            prepareEngine();
        } else if ("use_bluetooth".equals(key)) {
            if (prefs.useBluetooth()) bt.start();
            else bt.stop();
        } else if ("wake_test_only".equals(key) && !busy) {
            startListening();
        }
    }

    // ---------------------------------------------------------------- Anzeige

    private void setStatus(String s) {
        AppLog.setStatus(s);
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification(s));
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Sprachassistent")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .getNotification();
    }

    /** Wecker alle 10 Minuten: startet den Dienst neu, falls Android ihn beendet hat. */
    public static void scheduleWatchdog(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(ctx, BootReceiver.class).setAction(BootReceiver.ACTION_WATCHDOG);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES, pi);
    }

    public static void start(Context ctx) {
        new Prefs(ctx).setServiceWanted(true);
        ctx.startService(new Intent(ctx, AssistantService.class));
    }

    public static void stop(Context ctx) {
        new Prefs(ctx).setServiceWanted(false);
        ctx.stopService(new Intent(ctx, AssistantService.class));
    }
}
