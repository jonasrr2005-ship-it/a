package de.jonas.sprachassistent;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Kuemmert sich um das Mikrofon des Bluetooth-Lautsprechers (Headset-Profil / SCO):
 * - schaltet die SCO-Tonverbindung ein (Mikrofon + Lautsprecher in Telefonqualitaet)
 * - erkennt Abbrueche und verbindet automatisch neu
 * - verbindet den zuletzt benutzten Lautsprecher neu, wenn er ganz getrennt wurde
 */
public final class BluetoothAudio {

    public interface Listener {
        /** SCO-Tonverbindung an/aus. */
        void onScoChanged(boolean connected);
    }

    private static final long RETRY_MS = 8000;

    private final Context ctx;
    private final AudioManager am;
    private final Handler handler = new Handler();
    private final Prefs prefs;
    private final Listener listener;
    private BluetoothHeadset headset;
    private boolean wanted;
    private boolean scoOn;
    private boolean paused;
    private int failedAttempts;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED.equals(a)) {
                int st = i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
                if (st == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                    if (!scoOn) AppLog.info("Bluetooth-Mikrofon verbunden (SCO)");
                    scoOn = true;
                    failedAttempts = 0;
                    listener.onScoChanged(true);
                } else if (st == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                    if (scoOn) AppLog.info("Bluetooth-Mikrofon getrennt");
                    boolean was = scoOn;
                    scoOn = false;
                    if (was) listener.onScoChanged(false);
                    scheduleRetry();
                }
            } else if (BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED.equals(a)) {
                int st = i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (st == BluetoothProfile.STATE_CONNECTED && d != null) {
                    AppLog.info("Bluetooth-Geraet verbunden: " + name(d));
                    prefs.setLastBtDevice(d.getAddress());
                    handler.postDelayed(new Runnable() {
                        public void run() {
                            startSco();
                        }
                    }, 1500);
                } else if (st == BluetoothProfile.STATE_DISCONNECTED && d != null) {
                    AppLog.error("Bluetooth-Geraet getrennt: " + name(d) + " - versuche neu zu verbinden");
                    scheduleRetry();
                }
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(a)) {
                int st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                if (st == BluetoothAdapter.STATE_OFF) {
                    AppLog.error("Bluetooth wurde ausgeschaltet");
                    scheduleRetry();
                } else if (st == BluetoothAdapter.STATE_ON) {
                    scheduleRetry();
                }
            }
        }
    };

    private final Runnable retry = new Runnable() {
        public void run() {
            ensureConnected();
        }
    };

    public BluetoothAudio(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        this.prefs = new Prefs(ctx);
        this.listener = listener;
    }

    public boolean isScoOn() {
        return scoOn;
    }

    public void start() {
        if (wanted) return;
        wanted = true;
        IntentFilter f = new IntentFilter();
        f.addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
        f.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        ctx.registerReceiver(receiver, f);
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) {
            AppLog.error("Dieses Handy hat kein Bluetooth");
            return;
        }
        ad.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                headset = (BluetoothHeadset) proxy;
                ensureConnected();
            }

            public void onServiceDisconnected(int profile) {
                headset = null;
            }
        }, BluetoothProfile.HEADSET);
    }

    public void stop() {
        if (!wanted) return;
        wanted = false;
        handler.removeCallbacks(retry);
        try {
            ctx.unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        stopSco();
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad != null && headset != null) ad.closeProfileProxy(BluetoothProfile.HEADSET, headset);
        headset = null;
    }

    /** SCO kurz abschalten, z. B. damit die Antwort in Musikqualitaet (A2DP) laeuft. */
    public void pause() {
        paused = true;
        handler.removeCallbacks(retry);
        stopSco();
    }

    public void resume() {
        paused = false;
        ensureConnected();
    }

    private void scheduleRetry() {
        if (!wanted || paused) return;
        handler.removeCallbacks(retry);
        handler.postDelayed(retry, RETRY_MS);
    }

    private void ensureConnected() {
        if (!wanted || paused) return;
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) return;
        if (!ad.isEnabled()) {
            AppLog.info("Bluetooth ist aus - schalte es ein");
            ad.enable();
            scheduleRetry();
            return;
        }
        if (headset == null) {
            scheduleRetry();
            return;
        }
        List<BluetoothDevice> devs = headset.getConnectedDevices();
        if (devs.isEmpty()) {
            reconnectLastDevice(ad);
            scheduleRetry();
            return;
        }
        prefs.setLastBtDevice(devs.get(0).getAddress());
        if (!scoOn) startSco();
    }

    private void reconnectLastDevice(BluetoothAdapter ad) {
        String addr = prefs.lastBtDevice();
        if (addr.length() == 0) {
            // noch nie verbunden: erstes gekoppeltes Geraet nehmen, das Headset kann
            for (BluetoothDevice d : ad.getBondedDevices()) {
                if (d.getBluetoothClass() != null
                        && d.getBluetoothClass().hasService(android.bluetooth.BluetoothClass.Service.AUDIO)) {
                    addr = d.getAddress();
                    break;
                }
            }
            if (addr.length() == 0) {
                if (failedAttempts++ % 8 == 0) {
                    AppLog.error("Kein Bluetooth-Lautsprecher gekoppelt/verbunden. Bitte in den Android-Bluetooth-Einstellungen koppeln. Bis dahin wird das Handy-Mikrofon benutzt.");
                }
                return;
            }
        }
        try {
            BluetoothDevice d = ad.getRemoteDevice(addr);
            // connect() ist auf Android 4.0 versteckt -> per Reflection aufrufen
            Method m = BluetoothHeadset.class.getMethod("connect", BluetoothDevice.class);
            Object ok = m.invoke(headset, d);
            if (failedAttempts++ % 8 == 0) {
                AppLog.info("Verbinde Bluetooth-Lautsprecher " + name(d) + " neu ... (" + ok + ")");
            }
        } catch (Exception e) {
            if (failedAttempts++ % 8 == 0) AppLog.error("Automatisches Neuverbinden nicht moeglich", e);
        }
    }

    private void startSco() {
        if (!wanted || paused || scoOn) return;
        if (!am.isBluetoothScoAvailableOffCall()) {
            AppLog.error("Dieses Handy erlaubt kein Bluetooth-Mikrofon ausserhalb von Telefonaten");
            return;
        }
        try {
            am.setMode(AudioManager.MODE_NORMAL);
            am.startBluetoothSco();
            am.setBluetoothScoOn(true);
        } catch (Exception e) {
            AppLog.error("Bluetooth-Mikrofon konnte nicht eingeschaltet werden", e);
        }
        scheduleRetry();
    }

    private void stopSco() {
        try {
            am.setBluetoothScoOn(false);
            am.stopBluetoothSco();
        } catch (Exception ignored) {
        }
        scoOn = false;
    }

    private static String name(BluetoothDevice d) {
        String n = d.getName();
        return n != null ? n : d.getAddress();
    }
}
