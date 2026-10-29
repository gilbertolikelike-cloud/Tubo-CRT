package com.crtoverlay;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.SystemClock;

/** Temperatura da bateria (o melhor termômetro que um app comum consegue ler). */
final class Heat {
    private static long lastRead;
    private static float last = Float.NaN;

    private Heat() {}

    /** Em graus Celsius, ou NaN se o aparelho não informar. Guarda o valor por 5 segundos. */
    static float batteryTemp(Context ctx) {
        long now = SystemClock.uptimeMillis();
        if (Float.isNaN(last) || now - lastRead > 5000) {
            Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i != null) {
                int t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
                if (t != Integer.MIN_VALUE) last = t / 10f;
            }
            lastRead = now;
        }
        return last;
    }
}
