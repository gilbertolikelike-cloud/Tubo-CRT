package com.crtoverlay;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.WindowManager;

public class OverlayService extends Service implements SharedPreferences.OnSharedPreferenceChangeListener {
    public static volatile boolean running = false;
    static final String ACTION_STOP = "com.crtoverlay.STOP";

    private WindowManager wm;
    private CrtView view;
    private SharedPreferences sp;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        startForeground(1, buildNotification());
        if (view == null) addOverlay();
        running = true;
        return START_STICKY;
    }

    private void addOverlay() {
        sp = getSharedPreferences("crt", Context.MODE_PRIVATE);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        view = new CrtView(this);
        applyPrefs();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= 28)
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        wm.addView(view, lp);
        sp.registerOnSharedPreferenceChangeListener(this);
    }

    private void applyPrefs() {
        int[] v = new int[6];
        for (int i = 0; i < 6; i++) v[i] = sp.getInt(CrtView.KEYS[i], CrtView.DEF[i]);
        view.set(v[0], v[1], v[2], v[3], v[4], v[5]);
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        if (view != null) applyPrefs();
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("crt", "Tubo CRT", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "crt") : new Notification.Builder(this);
        nb.setContentTitle("Tubo CRT ligado").setContentText("Toque para ajustar ou desligar")
                .setSmallIcon(android.R.drawable.ic_menu_view).setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Desligar", stop).build());
        return nb.build();
    }

    @Override public void onDestroy() {
        running = false;
        if (sp != null) sp.unregisterOnSharedPreferenceChangeListener(this);
        if (view != null) { try { wm.removeView(view); } catch (Exception ignored) {} view = null; }
        super.onDestroy();
    }
}
