package com.crtoverlay;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
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
    private Button toggle, musicBtn;
    private TextView barStatus;
    private ImageView bg, intro;
    private MediaPlayer mp;
    private boolean introDone = false;
    private final Map<String, SeekBar> bars = new HashMap<>();
    private final Map<String, Integer> mins = new HashMap<>();

    // dim, curve, per, scan, mask, corner  (motion nao e mexido pelos presets)
    private static final int[] LIGHT = {0, 20, 0, 35, 15, 10};   // valores do CRT LIGHT do Black
    private static final int[] STRONG = {0, 45, 0, 60, 30, 20};  // valores do CRT STRONG do Black

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("crt", MODE_PRIVATE);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);

        bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(bg, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        GradientDrawable panel = new GradientDrawable(); // painel escuro transparente pra dar pra ler em cima do cenario
        panel.setColor(0xCC000000);
        panel.setCornerRadius(16 * d);
        root.setBackground(panel);
        ScrollView sv = new ScrollView(this);
        sv.setPadding(pad, pad, pad, pad);
        sv.setClipToPadding(false);
        sv.addView(root);
        frame.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        intro = new ImageView(this);
        intro.setScaleType(ImageView.ScaleType.CENTER_CROP);
        intro.setBackgroundColor(Color.BLACK);
        frame.addView(intro, new FrameLayout.LayoutParams(-1, -1));
        setContentView(frame);

        toggle = new Button(this);
        toggle.setOnClickListener(v -> onToggle());
        root.addView(toggle);

        musicBtn = new Button(this);
        musicBtn.setOnClickListener(v -> toggleMusic());
        root.addView(musicBtn);

        root.addView(label("Ligue, depois abra o jogo. Os ajustes valem na hora.", 0));

        Button bar = new Button(this);
        bar.setText("Cobrir tambem a barra de botoes (opcional)");
        bar.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(bar);
        barStatus = label("", 0);
        root.addView(barStatus);

        addSlider(root, "Escurecer tela", "dim", 0, 90, CrtView.DEF[0], "%");
        addSlider(root, "Curva da TV (jogo: 20 leve / 45 forte)", "curve", 0, 150, CrtView.DEF[1], "");
        addSlider(root, "Altura da linha em pixels (0 = automatico)", "per", 0, 8, CrtView.DEF[2], "");
        addSlider(root, "Scanlines (forca)", "scan", 0, 80, CrtView.DEF[3], "%");
        addSlider(root, "Movimento das linhas (0 = parado)", "motion", 0, 100, CrtView.DEF[6], "");
        addSlider(root, "Linha fina (maior = linha mais fina)", "hard", 2, 16, CrtView.DEF[7], "");
        addSlider(root, "Mascara de fosforo (forca)", "mask", 0, 50, CrtView.DEF[4], "%");
        addSlider(root, "Tipo de mascara (1 TV, 2 grade, 3 VGA esticada, 4 VGA)", "mtype", 1, 4, CrtView.DEF[8], "");
        addSlider(root, "Cantos arredondados", "round", 0, 15, CrtView.DEF[9], "");
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

        loadBackground();
        if (b == null) playIntro(); else intro.setVisibility(View.GONE); // so abre a TV quando o app e aberto de novo
    }

    // ---------- gifs ----------
    private boolean landscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private Drawable load(String name) {
        try { return ImageDecoder.decodeDrawable(ImageDecoder.createSource(getAssets(), name)); }
        catch (Exception e) { return null; }
    }

    private void loadBackground() {
        Drawable d = load(landscape() ? "bg_land.gif" : "bg_portrait.webp");
        bg.setImageDrawable(d);
        if (d instanceof AnimatedImageDrawable) {
            ((AnimatedImageDrawable) d).setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
            ((AnimatedImageDrawable) d).start();
        }
    }

    private void playIntro() {
        Drawable d = load(landscape() ? "intro_land.gif" : "intro_portrait.gif");
        if (!(d instanceof AnimatedImageDrawable)) { intro.setVisibility(View.GONE); introDone = true; return; }
        AnimatedImageDrawable a = (AnimatedImageDrawable) d;
        a.setRepeatCount(0); // toca uma vez so
        a.registerAnimationCallback(new Animatable2.AnimationCallback() {
            @Override public void onAnimationEnd(Drawable dr) { endIntro(); }
        });
        intro.setImageDrawable(a);
        intro.setOnClickListener(v -> endIntro()); // toque pula
        a.start();
        intro.postDelayed(this::endIntro, 6000);   // garantia
    }

    private void endIntro() {
        if (introDone) return;
        introDone = true;
        intro.animate().alpha(0f).setDuration(400).withEndAction(() -> intro.setVisibility(View.GONE)).start();
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        loadBackground();
    }

    // ---------- musica ----------
    private void startMusic() {
        if (!sp.getBoolean("music_on", true)) return;
        if (mp == null) {
            mp = MediaPlayer.create(this, R.raw.intro_music);
            if (mp == null) return;
            mp.setLooping(true);
        }
        if (!mp.isPlaying()) mp.start();
    }

    private void toggleMusic() {
        boolean on = !sp.getBoolean("music_on", true);
        sp.edit().putBoolean("music_on", on).apply();
        if (on) startMusic(); else if (mp != null && mp.isPlaying()) mp.pause();
        refresh();
    }

    @Override protected void onPause() {
        super.onPause();
        if (mp != null && mp.isPlaying()) mp.pause(); // sai do app = musica para
    }

    @Override protected void onDestroy() {
        if (mp != null) { mp.release(); mp = null; }
        super.onDestroy();
    }

    // ---------- ajustes ----------
    private TextView label(String text, int topPad) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setPadding(0, topPad, 0, 0);
        return tv;
    }

    private void addSlider(LinearLayout root, final String label, final String key,
                           final int min, int max, int def, final String unit) {
        int cur = sp.getInt(key, def);
        final TextView tv = label(label + ": " + cur + unit, 24);
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
            startForegroundService(i);
        }
        toggle.postDelayed(this::refresh, 400);
    }

    private void refresh() {
        toggle.setText(OverlayService.running ? "Desligar CRT" : "Ligar CRT");
        musicBtn.setText(sp.getBoolean("music_on", true) ? "Musica: ligada" : "Musica: desligada");
        barStatus.setText(CrtAccessService.instance != null
                ? "Barra de baixo: sera coberta (desligue e ligue o CRT pra valer)"
                : "Barra de baixo: nao coberta. Ative \"Tubo CRT\" em Acessibilidade.");
    }

    @Override protected void onResume() { super.onResume(); refresh(); startMusic(); }
}
