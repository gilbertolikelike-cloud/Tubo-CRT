package com.crtoverlay;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Typeface;
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

public class MainActivity extends Activity {
    private static final int PAGES = 4;
    private static final int INK = 0xFF1E1208; // texto escuro em cima da madeira clara
    // prateleiras amarelas (fracao da altura do cenario) medidas nos gifs: {topo, base}
    private static final float[][] LAND_BANDS = {{0.156f, 0.246f}, {0.372f, 0.463f}, {0.589f, 0.680f}, {0.804f, 0.894f}};
    private static final float[][] PORT_BANDS = {{0.297f, 0.372f}, {0.477f, 0.553f}, {0.658f, 0.733f}, {0.837f, 0.912f}};

    private SharedPreferences sp;
    private float dens;
    private FrameLayout stage;
    private final FrameLayout[] boxes = new FrameLayout[4];
    private FrameLayout navBox;
    private TextView pageLabel;
    private Button toggle, musicBtn, barBtn;
    private ImageView bg, intro;
    private MediaPlayer mp;
    private boolean introDone = false;
    private int page = 0;

    // dim, curve, per, scan, mask, corner  (motion/hard/mtype/round nao sao mexidos pelos presets)
    private static final int[] LIGHT = {0, 20, 0, 35, 15, 10};   // valores do CRT LIGHT do Black
    private static final int[] STRONG = {0, 45, 0, 60, 30, 20};  // valores do CRT STRONG do Black

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("crt", MODE_PRIVATE);
        dens = getResources().getDisplayMetrics().density;

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);

        bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(bg, new FrameLayout.LayoutParams(-1, -1));

        stage = new FrameLayout(this);
        frame.addView(stage, new FrameLayout.LayoutParams(-1, -1));
        for (int i = 0; i < 4; i++) { boxes[i] = new FrameLayout(this); stage.addView(boxes[i]); }
        navBox = new FrameLayout(this);
        stage.addView(navBox);
        stage.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> layoutStage());

        intro = new ImageView(this);
        intro.setScaleType(ImageView.ScaleType.CENTER_CROP);
        intro.setBackgroundColor(Color.BLACK);
        frame.addView(intro, new FrameLayout.LayoutParams(-1, -1));
        setContentView(frame);

        loadBackground();
        showPage();
        if (b == null) playIntro(); else intro.setVisibility(View.GONE); // so abre a TV quando o app e aberto de novo
    }

    // ---------- posicao dos textos em cima das prateleiras ----------
    private boolean landscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void layoutStage() {
        int W = stage.getWidth(), H = stage.getHeight();
        if (W == 0 || H == 0) return;
        Drawable d = bg.getDrawable();
        int iw = d != null && d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : W;
        int ih = d != null && d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : H;
        float s = Math.max(W / (float) iw, H / (float) ih); // mesma conta do CENTER_CROP
        float dx = (W - iw * s) / 2f, dy = (H - ih * s) / 2f, w = iw * s, h = ih * s;
        boolean land = landscape();
        float[][] bands = land ? LAND_BANDS : PORT_BANDS;
        float x0 = land ? 0.195f : 0.035f, x1 = land ? 0.97f : 0.965f;
        for (int i = 0; i < 4; i++)
            place(boxes[i], dx + w * x0, dy + h * bands[i][0], w * (x1 - x0), h * (bands[i][1] - bands[i][0]));
        if (land) place(navBox, dx + w * 0.012f, dy + h * 0.16f, w * 0.165f, h * 0.68f);
        else place(navBox, dx + w * 0.08f, dy + h * 0.014f, w * 0.84f, h * 0.085f);
    }

    private void place(View v, float x, float y, float w, float h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.max(1, (int) w), Math.max(1, (int) h));
        lp.leftMargin = (int) x; lp.topMargin = (int) y;
        v.setLayoutParams(lp);
    }

    // ---------- paginas ----------
    private void showPage() {
        toggle = null; musicBtn = null; barBtn = null;
        for (int i = 0; i < 4; i++) {
            boxes[i].removeAllViews();
            View v = rowView(page, i);
            if (v != null) boxes[i].addView(v, new FrameLayout.LayoutParams(-1, -1));
        }
        buildNav();
        refresh();
    }

    private View rowView(int p, int slot) {
        switch (p * 4 + slot) {
            case 0: toggle = flat("Ligar CRT"); toggle.setOnClickListener(v -> onToggle()); return toggle;
            case 1: musicBtn = flat("Musica"); musicBtn.setOnClickListener(v -> toggleMusic()); return musicBtn;
            case 2: barBtn = flat("Barra de baixo");
                barBtn.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))); return barBtn;
            case 3: {
                LinearLayout r = new LinearLayout(this);
                Button l = flat("CRT leve"), s = flat("CRT forte");
                l.setOnClickListener(v -> preset(LIGHT));
                s.setOnClickListener(v -> preset(STRONG));
                r.addView(l, new LinearLayout.LayoutParams(0, -1, 1));
                r.addView(s, new LinearLayout.LayoutParams(0, -1, 1));
                return r;
            }
            case 4: return slider("Escurecer tela", "dim", 0, 90, CrtView.DEF[0], "%");
            case 5: return slider("Curva da TV (jogo: 20 / 45)", "curve", 0, 150, CrtView.DEF[1], "");
            case 6: return slider("Scanlines (forca)", "scan", 0, 80, CrtView.DEF[3], "%");
            case 7: return slider("Movimento das linhas", "motion", 0, 100, CrtView.DEF[6], "");
            case 8: return slider("Altura da linha (0 = auto)", "per", 0, 8, CrtView.DEF[2], "");
            case 9: return slider("Linha fina", "hard", 2, 16, CrtView.DEF[7], "");
            case 10: return slider("Mascara (forca)", "mask", 0, 50, CrtView.DEF[4], "%");
            case 11: return slider("Tipo de mascara (1 a 4)", "mtype", 1, 4, CrtView.DEF[8], "");
            case 12: return slider("Cantos arredondados", "round", 0, 15, CrtView.DEF[9], "");
            case 13: return slider("Sombra nos cantos", "corner", 0, 40, CrtView.DEF[5], "");
            default: return null;
        }
    }

    private void buildNav() {
        navBox.removeAllViews();
        boolean land = landscape();
        LinearLayout n = new LinearLayout(this);
        n.setOrientation(land ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        n.setGravity(Gravity.CENTER);
        Button prev = navBtn(land ? "\u25B2" : "<"), next = navBtn(land ? "\u25BC" : ">");
        prev.setOnClickListener(v -> { page = (page + PAGES - 1) % PAGES; showPage(); });
        next.setOnClickListener(v -> { page = (page + 1) % PAGES; showPage(); });
        pageLabel = new TextView(this);
        pageLabel.setTextColor(Color.WHITE);
        pageLabel.setTypeface(null, Typeface.BOLD);
        pageLabel.setTextSize(16);
        pageLabel.setGravity(Gravity.CENTER);
        pageLabel.setText((page + 1) + "/" + PAGES);
        if (land) {
            n.addView(prev, new LinearLayout.LayoutParams(-1, 0, 1));
            n.addView(pageLabel, new LinearLayout.LayoutParams(-1, -2));
            n.addView(next, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            n.addView(prev, new LinearLayout.LayoutParams(0, -1, 1));
            n.addView(pageLabel, new LinearLayout.LayoutParams(0, -1, 1));
            n.addView(next, new LinearLayout.LayoutParams(0, -1, 1));
        }
        navBox.addView(n, new FrameLayout.LayoutParams(-1, -1));
    }

    private Button navBtn(String t) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextColor(Color.WHITE);
        b.setTextSize(20);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x66000000);
        g.setStroke((int) (1.5f * dens), 0xAAFFFFFF);
        g.setCornerRadius(10 * dens);
        b.setBackground(g);
        return b;
    }

    private Button flat(String text) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextColor(INK);
        b.setTypeface(null, Typeface.BOLD);
        b.setTextSize(15);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x40FFFFFF);
        g.setStroke((int) (1.5f * dens), 0xAA1E1208);
        g.setCornerRadius(10 * dens);
        b.setBackground(g);
        return b;
    }

    private View slider(final String label, final String key, final int min, int max, int def, final String unit) {
        boolean land = landscape();
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (8 * dens);
        l.setPadding(pad, 0, pad, 0);
        int cur = sp.getInt(key, def);
        final TextView tv = new TextView(this);
        tv.setTextColor(INK);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextSize(14);
        tv.setText(label + ": " + cur + unit);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(cur - min);
        sb.setProgressTintList(ColorStateList.valueOf(INK));
        sb.setThumbTintList(ColorStateList.valueOf(INK));
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(0x661E1208));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                sp.edit().putInt(key, p + min).apply();
                tv.setText(label + ": " + (p + min) + unit);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        if (land) {
            l.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));
            l.addView(sb, new LinearLayout.LayoutParams(0, -2, 1f));
        } else {
            l.addView(tv, new LinearLayout.LayoutParams(-1, -2));
            l.addView(sb, new LinearLayout.LayoutParams(-1, -2));
        }
        return l;
    }

    private void preset(int[] v) {
        SharedPreferences.Editor e = sp.edit();
        for (int i = 1; i < 6; i++) e.putInt(CrtView.KEYS[i], v[i]); // nao mexe no "escurecer"
        e.apply();
        showPage();
    }

    // ---------- gifs ----------
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
        stage.post(this::layoutStage);
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
        showPage();
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

    // ---------- ligar / desligar ----------
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
            startForegroundService(new Intent(this, OverlayService.class));
        }
        stage.postDelayed(this::refresh, 400);
    }

    private void refresh() {
        if (toggle != null) toggle.setText(OverlayService.running ? "Desligar CRT" : "Ligar CRT");
        if (musicBtn != null) musicBtn.setText(sp.getBoolean("music_on", true) ? "Musica: ligada" : "Musica: desligada");
        if (barBtn != null) barBtn.setText(CrtAccessService.instance != null
                ? "Barra de baixo: coberta (religue o CRT)"
                : "Cobrir a barra de baixo (ativar)");
    }

    @Override protected void onResume() { super.onResume(); refresh(); startMusic(); }
}
