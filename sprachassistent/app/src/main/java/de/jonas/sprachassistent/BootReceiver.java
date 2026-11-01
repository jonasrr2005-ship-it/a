package de.jonas.sprachassistent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Startet den Dienst nach dem Einschalten des Handys und prueft regelmaessig, ob er noch laeuft. */
public class BootReceiver extends BroadcastReceiver {

    public static final String ACTION_WATCHDOG = "de.jonas.sprachassistent.WATCHDOG";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Prefs p = new Prefs(ctx);
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)) {
            if (p.autostart() && p.serviceWanted()) {
                ctx.startService(new Intent(ctx, AssistantService.class));
            }
        } else if (ACTION_WATCHDOG.equals(a)) {
            if (p.serviceWanted() && !AssistantService.isRunning()) {
                ctx.startService(new Intent(ctx, AssistantService.class));
            }
        }
    }
}
