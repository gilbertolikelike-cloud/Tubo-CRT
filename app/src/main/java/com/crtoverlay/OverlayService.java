package com.crtoverlay;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.util.DisplayMetrics;
import android.view.Gravity;
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
    private WindowManager.LayoutParams lp;
    private boolean viaAcc;
    private SidePanel panel;

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
        CrtAccessService acc = CrtAccessService.instance;
        viaAcc = acc != null; // com acessibilidade ligada, o overlay cobre ate a barra de botoes
        wm = (WindowManager) (viaAcc ? acc : this).getSystemService(WINDOW_SERVICE);
        view = new CrtView(this);
        applyPrefs();
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                viaAcc ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= 28)
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        if (viaAcc) { lp.gravity = Gravity.TOP | Gravity.START; fitRealSize(); }
        wm.addView(view, lp);
        sp.registerOnSharedPreferenceChangeListener(this);
        startPanel();
    }

    private void startPanel() {
        if (panel != null || !sp.getBoolean("panel_on", true)) return;
        panel = new SidePanel(this, wm, viaAcc);
        panel.show(sp.getInt("panel_y", 25));
    }

    private void stopPanel() {
        if (panel != null) { panel.hide(); panel = null; }
    }

    private void fitRealSize() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        lp.width = dm.widthPixels;
        lp.height = dm.heightPixels;
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (view != null && viaAcc && wm != null) {
            fitRealSize();
            try { wm.updateViewLayout(view, lp); } catch (Exception ignored) {}
        }
    }

    private void applyPrefs() {
        int[] v = new int[CrtView.KEYS.length];
        for (int i = 0; i < v.length; i++) v[i] = sp.getInt(CrtView.KEYS[i], CrtView.DEF[i]);
        view.set(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9]);
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        if ("panel_on".equals(key)) { if (sp.getBoolean("panel_on", true)) startPanel(); else stopPanel(); return; }
        if ("panel_y".equals(key)) { if (panel != null) panel.setPos(sp.getInt("panel_y", 25)); return; }
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
        stopPanel();
        if (view != null) { try { wm.removeView(view); } catch (Exception ignored) {} view = null; }
        super.onDestroy();
    }
}
