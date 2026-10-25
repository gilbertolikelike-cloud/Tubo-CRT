package com.crtoverlay;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

/** Mantém o brilho automático (adaptável) desligado, pra a tela não escurecer sozinha. */
final class Brightness {
    private Brightness() {}

    static void forceManual(Context ctx, SharedPreferences sp) {
        if (!sp.getBoolean("no_auto_bright", true) || !Settings.System.canWrite(ctx)) return;
        try {
            ContentResolver cr = ctx.getContentResolver();
            int manual = Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
            if (Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, manual) != manual)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, manual);
        } catch (RuntimeException ignored) {
            // o sistema recusou: sem permissão ou aparelho sem esse ajuste
        }
    }
}
