package com.crtoverlay;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private SharedPreferences sp;
    private Button toggle;
    private final Map<String, SeekBar> bars = new HashMap<>();
    private final Map<String, Integer> mins = new HashMap<>();

    // dim, curve, lines, scan, grille, vign
    private static final int[] LIGHT = {0, 20, 0, 35, 15, 10};   // valores do CRT LIGHT do Black
    private static final int[] STRONG = {0, 45, 0, 60, 30, 20};  // valores do CRT STRONG do Black

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("crt", MODE_PRIVATE);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        toggle = new Button(this);
        toggle.setOnClickListener(v -> onToggle());
        root.addView(toggle);

        TextView hint = new TextView(this);
        hint.setText("Ligue, depois abra o jogo. Os ajustes valem na hora.");
        root.addView(hint);

        addSlider(root, "Escurecer tela", "dim", 0, 90, CrtView.DEF[0], "%");
        addSlider(root, "Curva da TV (jogo: 20 leve / 45 forte)", "curve", 0, 150, CrtView.DEF[1], "");
        addSlider(root, "Altura da linha em pixels (0 = automatico)", "per", 0, 8, CrtView.DEF[2], "");
        addSlider(root, "Scanlines (forca)", "scan", 0, 80, CrtView.DEF[3], "%");
        addSlider(root, "Mascara de fosforo", "mask", 0, 50, CrtView.DEF[4], "%");
        addSlider(root, "Sombra nos cantos", "corner", 0, 40, CrtView.DEF[5], "");

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button l = new Button(this); l.setText("CRT leve");
        l.setOnClickListener(v -> preset(LIGHT));
        Button s = new Button(this); s.setText("CRT forte");
        s.setOnClickListener(v -> preset(STRONG));
        row.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(s, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(row);
    }

    private void addSlider(LinearLayout root, final String label, final String key,
                           final int min, int max, int def, final String unit) {
        int cur = sp.getInt(key, def);
        final TextView tv = new TextView(this);
        tv.setText(label + ": " + cur + unit);
        tv.setPadding(0, 24, 0, 0);
        root.addView(tv);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(cur - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                sp.edit().putInt(key, p + min).apply();
                tv.setText(label + ": " + (p + min) + unit);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(sb);
        bars.put(key, sb);
        mins.put(key, min);
    }

    private void preset(int[] v) {
        for (int i = 1; i < 6; i++) { // nao mexe no "escurecer"
            String k = CrtView.KEYS[i];
            bars.get(k).setProgress(v[i] - mins.get(k));
        }
    }

    private void onToggle() {
        if (OverlayService.running) {
            stopService(new Intent(this, OverlayService.class));
        } else {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Permita \"Exibir sobre outros apps\" e volte aqui", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                return;
            }
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            Intent i = new Intent(this, OverlayService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        }
        toggle.postDelayed(this::refresh, 400);
    }

    private void refresh() { toggle.setText(OverlayService.running ? "Desligar CRT" : "Ligar CRT"); }

    @Override protected void onResume() { super.onResume(); refresh(); }
}
