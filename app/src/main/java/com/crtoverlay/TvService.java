package com.crtoverlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.view.WindowManager;

/** Mantém a TV de vídeo viva por cima de outros apps (serviço em primeiro plano, notificação mínima). */
public class TvService extends Service {
    static final String ACTION_PLAY = "com.crtoverlay.TV_PLAY", ACTION_STOP = "com.crtoverlay.TV_STOP";
    private static final String CHANNEL = "tv_quiet";
    private static final int NOTIFICATION_ID = 77;
    static volatile boolean running;

    private WindowManager wm;
    private TvView view;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification());
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (view == null) {
            SharedPreferences sp = getSharedPreferences("crt", MODE_PRIVATE);
            view = new TvView(this, wm, sp, () -> stopSelf());
            try {
                wm.addView(view, view.layoutParams());
            } catch (RuntimeException e) { // sem permissão de aparecer por cima
                view.release();
                view = null;
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        if (intent.getBooleanExtra("list", false)) view.openList(); // TV LISTA do painel
        String folder = intent.getStringExtra("folder");
        if (folder != null) view.open(folder, intent.getIntExtra("index", 0));
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        if (view != null) {
            try { wm.removeView(view); } catch (RuntimeException ignored) { /* já saiu */ }
            view.release();
            view = null;
        }
        super.onDestroy();
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "TV de vídeo (pode desativar)", NotificationManager.IMPORTANCE_MIN);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, TvService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("TV de vídeo ligada")
                .setContentText("Toque em Fechar pra desligar")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .addAction(new Notification.Action.Builder(null, "Fechar", stop).build())
                .build();
    }
}
