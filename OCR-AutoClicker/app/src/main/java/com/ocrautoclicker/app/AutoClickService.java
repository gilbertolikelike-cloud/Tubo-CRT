package com.ocrautoclicker.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

/** Lightweight foreground service for ordinary Auto Click mode; it never captures the screen. */
public class AutoClickService extends Service {
    private static final String CHANNEL_ID = "auto_click";
    private static final int NOTIFICATION_ID = 73;
    private AutoClickOverlayManager clickOverlay;

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Auto Click", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Auto Click ativo")
            .setContentText("OCR desligado para economizar recursos.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        if (clickOverlay == null) {
            DisplayMetrics metrics = new DisplayMetrics();
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            wm.getDefaultDisplay().getRealMetrics(metrics);
            clickOverlay = new AutoClickOverlayManager(
                this, metrics.widthPixels, metrics.heightPixels);
            clickOverlay.show();
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        if (clickOverlay != null) {
            clickOverlay.destroy();
            clickOverlay = null;
        }
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
