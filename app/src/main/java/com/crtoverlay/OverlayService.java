package com.crtoverlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Toast;

/** Mantém o filtro CRT (e o painel do Bob) por cima de tudo enquanto estiver ligado. */
public class OverlayService extends Service implements SharedPreferences.OnSharedPreferenceChangeListener {
    /** Verdadeiro enquanto o filtro está ligado. */
    public static volatile boolean running = false;
    static volatile OverlayService current;

    private static final String ACTION_STOP = "com.crtoverlay.STOP";
    /** Canal mínimo: sem som, sem ícone na barra de cima. Dá pra desativar sem desligar o filtro. */
    static final String CHANNEL_ID = "crt_quiet";
    private static final int NOTIFICATION_ID = 1;

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private CrtView view;
    private BobPanel panel;
    private SharedPreferences sp;
    private PowerManager.WakeLock wakeLock;
    private boolean viaAccessibility;
    private ContentObserver brightnessGuard;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        sp = getSharedPreferences("crt", Context.MODE_PRIVATE);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification());
        if (view == null && !addOverlay()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        current = this;
        startBrightnessGuard();
        return START_STICKY;
    }

    // ---------- janela do filtro ----------
    private boolean addOverlay() {
        CrtAccessService acc = CrtAccessService.instance;
        // com a Acessibilidade ligada a janela fica acima da barra de botões; sem ela, o Android não deixa
        viaAccessibility = acc != null;
        wm = (WindowManager) (viaAccessibility ? acc : this).getSystemService(WINDOW_SERVICE);

        view = new CrtView(this);
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                viaAccessibility ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                        : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        if (viaAccessibility) {
            lp.gravity = Gravity.TOP | Gravity.START;
            fitRealSize();
        }
        applyPrefs();
        try {
            wm.addView(view, lp);
        } catch (RuntimeException e) { // permissão de sobrepor apps foi tirada
            Toast.makeText(this, "Sem permissão de exibir sobre outros apps", Toast.LENGTH_LONG).show();
            view = null;
            return false;
        }
        sp.registerOnSharedPreferenceChangeListener(this);
        startPanel();
        applyPerformance(false);
        return true;
    }

    private void removeOverlay() {
        stopPanel();
        if (view != null) {
            try { wm.removeView(view); } catch (RuntimeException ignored) { /* a janela já pode ter sumido */ }
            view = null;
        }
    }

    /** Refaz tudo (chamado quando a Acessibilidade liga ou desliga). */
    void reloadOverlay() {
        if (view == null) return;
        removeOverlay();
        if (!addOverlay()) stopSelf();
    }

    private void fitRealSize() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        lp.width = dm.widthPixels;
        lp.height = dm.heightPixels;
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (view != null && viaAccessibility) {
            fitRealSize();
            try { wm.updateViewLayout(view, lp); } catch (RuntimeException ignored) { /* janela removida */ }
        }
        if (panel != null) panel.onScreenChanged();
    }

    // ---------- ajustes ----------
    private void applyPrefs() {
        int[] v = new int[CrtView.KEYS.length];
        for (int i = 0; i < v.length; i++) v[i] = sp.getInt(CrtView.KEYS[i], CrtView.DEF[i]);
        view.set(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9]);
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (view == null || key == null) return;
        switch (key) {
            case "panel_on": if (sp.getBoolean("panel_on", true)) startPanel(); else stopPanel(); break;
            case "bob_size": case "bob_alpha": if (panel != null) panel.refreshLook(); break;
            case "perf_on": applyPerformance(true); break;
            case "panel_fx": case "panel_fy": case "music_tree": case "games_pkgs": case "music_on": case "bob_fluid": break;
            case "no_auto_bright": Brightness.forceManual(this, sp); break;
            default: applyPrefs(); break;
        }
    }

    /** Modo desempenho: tela e processador acordados e RAM limpa ao ligar. */
    private void applyPerformance(boolean announce) {
        boolean on = sp.getBoolean("perf_on", false);
        if (on && wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TuboCRT:desempenho");
            wakeLock.acquire(8 * 60 * 60 * 1000L);
            if (announce) {
                Toast.makeText(this, "Modo desempenho ligado. Limpando a RAM…", Toast.LENGTH_SHORT).show();
                Ram.clean(this, (freed, avail) -> Toast.makeText(this,
                        "RAM livre: " + avail + " MB (+" + freed + " MB)", Toast.LENGTH_LONG).show());
            }
        } else if (!on && wakeLock != null) {
            wakeLock.release();
            wakeLock = null;
            if (announce) Toast.makeText(this, "Modo desempenho desligado", Toast.LENGTH_SHORT).show();
        }
        if (on) lp.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        else lp.flags &= ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        try { wm.updateViewLayout(view, lp); } catch (RuntimeException ignored) { /* janela removida */ }
        applyPrefs();
    }

    // ---------- brilho automático ----------
    private void startBrightnessGuard() {
        if (brightnessGuard != null) return;
        Brightness.forceManual(this, sp);
        brightnessGuard = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) { Brightness.forceManual(OverlayService.this, sp); }
        };
        getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), false, brightnessGuard);
    }

    // ---------- painel do Bob ----------
    private void startPanel() {
        if (panel != null || !sp.getBoolean("panel_on", true)) return;
        panel = new BobPanel(this, wm, viaAccessibility, sp);
        panel.show();
    }

    private void stopPanel() {
        if (panel != null) {
            panel.hide();
            panel = null;
        }
    }

    // ---------- notificação ----------
    static void ensureChannel(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.deleteNotificationChannel("crt"); // canal antigo, que era mais chamativo
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Tubo CRT (pode desativar)", NotificationManager.IMPORTANCE_MIN);
        channel.setDescription("Aviso fixo que o Android exige enquanto o filtro está ligado. Dá pra desativar sem desligar o filtro.");
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        ensureChannel(this);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name) + " ligado")
                .setContentText("Toque para ajustar ou desligar")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .addAction(new Notification.Action.Builder(null, "Desligar", stop).build())
                .build();
    }

    @Override public void onDestroy() {
        running = false;
        current = null;
        sp.unregisterOnSharedPreferenceChangeListener(this);
        if (wakeLock != null) { wakeLock.release(); wakeLock = null; }
        if (brightnessGuard != null) { getContentResolver().unregisterContentObserver(brightnessGuard); brightnessGuard = null; }
        MusicPlayer.get(this).stop();
        removeOverlay();
        super.onDestroy();
    }
}
