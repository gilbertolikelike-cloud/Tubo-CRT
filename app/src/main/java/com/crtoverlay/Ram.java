package com.crtoverlay;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;

/** Limpeza de RAM: encerra os processos em segundo plano dos apps instalados (nunca o app da frente nem este). */
final class Ram {
    interface Callback {
        /** @param freedMb quanto a memoria livre subiu; @param availMb memoria livre agora */
        void done(long freedMb, long availMb);
    }

    private Ram() {}

    static void clean(final Context ctx, final Callback cb) {
        final ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        final long before = available(am);
        for (ApplicationInfo ai : ctx.getPackageManager().getInstalledApplications(0)) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 || ai.packageName.equals(ctx.getPackageName())) continue;
            am.killBackgroundProcesses(ai.packageName);
        }
        // a memoria so volta alguns instantes depois de matar os processos
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            long now = available(am);
            if (cb != null) cb.done(Math.max(0, (now - before) / (1024 * 1024)), now / (1024 * 1024));
        }, 900);
    }

    private static long available(ActivityManager am) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem;
    }
}
