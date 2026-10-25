package com.crtoverlay;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tela de ajustes: o texto fica em cima das prateleiras do cenário (4 linhas por página).
 * Páginas: 1 principal, 2 imagem, 3 linhas e máscara, 4 cantos e Bob, 5 painel e desempenho, 6+ jogos.
 */
public class MainActivity extends Activity {
    private static final int FIXED_PAGES = 5;
    private static final int ROWS = 4;
    private static final int INK = 0xFF1E1208; // texto escuro em cima da madeira clara

    // prateleiras amarelas (fração da altura do cenário) medidas nos gifs: {topo, base}
    private static final float[][] LAND_BANDS = {{0.156f, 0.246f}, {0.372f, 0.463f}, {0.589f, 0.680f}, {0.804f, 0.894f}};
    private static final float[][] PORT_BANDS = {{0.297f, 0.372f}, {0.477f, 0.553f}, {0.658f, 0.733f}, {0.837f, 0.912f}};

    // escurecer, curva, linha, scan, máscara, sombra (os presets não mexem no resto)
    private static final int[] LIGHT = {0, 20, 0, 35, 15, 10};   // CRT LIGHT do Black
    private static final int[] STRONG = {0, 45, 0, 60, 30, 20};  // CRT STRONG do Black

    private static final class Game {
        final String pkg, label;
        final Drawable icon;
        Game(String pkg, String label, Drawable icon) { this.pkg = pkg; this.label = label; this.icon = icon; }
    }

    private final FrameLayout[] boxes = new FrameLayout[ROWS];
    private final Map<String, Button> buttons = new HashMap<>();
    private volatile List<Game> games = Collections.emptyList();
    private SharedPreferences sp;
    private float dens;
    private FrameLayout stage, navBox;
    private TextView pageLabel;
    private ImageView bg, intro;
    private MediaPlayer music;
    private boolean introDone = false;
    private int page = 0;

    // ---------- ciclo de vida ----------
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        sp = getSharedPreferences("crt", MODE_PRIVATE);
        dens = getResources().getDisplayMetrics().density;

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(bg, new FrameLayout.LayoutParams(-1, -1));

        stage = new FrameLayout(this);
        frame.addView(stage, new FrameLayout.LayoutParams(-1, -1));
        for (int i = 0; i < ROWS; i++) {
            boxes[i] = new FrameLayout(this);
            stage.addView(boxes[i]);
        }
        navBox = new FrameLayout(this);
        stage.addView(navBox);
        stage.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> layoutStage());

        intro = new ImageView(this);
        intro.setScaleType(ImageView.ScaleType.CENTER_CROP);
        intro.setBackgroundColor(Color.BLACK);
        frame.addView(intro, new FrameLayout.LayoutParams(-1, -1));
        setContentView(frame);

        loadBackground();
        showPage();
        if (state == null) {
            playIntro();
        } else { // tela girou ou o app foi recriado: sem abertura
            intro.setVisibility(View.GONE);
            introDone = true;
        }
    }

    @Override protected void onStart() {
        super.onStart();
        scanGames();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
        startMusic(); // só depois da abertura
    }

    @Override protected void onPause() {
        super.onPause();
        if (music != null && music.isPlaying()) music.pause(); // saiu do app = a música para
    }

    @Override protected void onDestroy() {
        if (music != null) { music.release(); music = null; }
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        loadBackground();
        showPage();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private boolean landscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    // ---------- posição dos textos em cima das prateleiras ----------
    private void layoutStage() {
        int w = stage.getWidth(), h = stage.getHeight();
        if (w == 0 || h == 0) return;
        Drawable d = bg.getDrawable();
        int iw = d != null && d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : w;
        int ih = d != null && d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : h;
        float s = Math.max(w / (float) iw, h / (float) ih); // mesma conta do CENTER_CROP
        float dx = (w - iw * s) / 2f, dy = (h - ih * s) / 2f, sw = iw * s, sh = ih * s;
        boolean land = landscape();
        float[][] bands = land ? LAND_BANDS : PORT_BANDS;
        float x0 = land ? 0.195f : 0.035f, x1 = land ? 0.97f : 0.965f;
        for (int i = 0; i < ROWS; i++)
            place(boxes[i], dx + sw * x0, dy + sh * bands[i][0], sw * (x1 - x0), sh * (bands[i][1] - bands[i][0]));
        if (land) place(navBox, dx + sw * 0.012f, dy + sh * 0.16f, sw * 0.165f, sh * 0.68f);
        else place(navBox, dx + sw * 0.08f, dy + sh * 0.014f, sw * 0.84f, sh * 0.085f);
    }

    private void place(View v, float x, float y, float w, float h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.max(1, (int) w), Math.max(1, (int) h));
        lp.leftMargin = (int) x;
        lp.topMargin = (int) y;
        v.setLayoutParams(lp);
    }

    // ---------- páginas ----------
    private int pageCount() {
        return FIXED_PAGES + (games.size() + ROWS - 1) / ROWS;
    }

    private void showPage() {
        if (page >= pageCount()) page = 0;
        buttons.clear();
        for (int i = 0; i < ROWS; i++) {
            boxes[i].removeAllViews();
            View v = rowView(page, i);
            if (v != null) boxes[i].addView(v, new FrameLayout.LayoutParams(-1, -1));
        }
        buildNav();
        refresh();
    }

    private void changePage(int delta) {
        int n = pageCount();
        page = (page + delta + n) % n;
        showPage();
    }

    private View rowView(int p, int slot) {
        if (p >= FIXED_PAGES) {
            int i = (p - FIXED_PAGES) * ROWS + slot;
            return i < games.size() ? gameRow(games.get(i)) : null;
        }
        switch (p * ROWS + slot) {
            case 0: return actionButton("a_toggle");
            case 1: return actionButton("a_music");
            case 2: return actionButton("a_bar");
            case 3: return presetRow();
            case 4: return slider("Escurecer tela", "dim", 0, 90, CrtView.DEF[0], "%");
            case 5: return slider("Curva da TV (Black: 20 / 45)", "curve", 0, 150, CrtView.DEF[1], "");
            case 6: return slider("Scanlines (força)", "scan", 0, 80, CrtView.DEF[3], "%");
            case 7: return slider("Movimento das linhas", "motion", 0, 100, CrtView.DEF[6], "");
            case 8: return slider("Altura da linha (0 = auto)", "per", 0, 8, CrtView.DEF[2], "");
            case 9: return slider("Linha fina", "hard", 2, 16, CrtView.DEF[7], "");
            case 10: return slider("Máscara (força)", "mask", 0, 50, CrtView.DEF[4], "%");
            case 11: return slider("Tipo de máscara (1 a 4)", "mtype", 1, 4, CrtView.DEF[8], "");
            case 12: return slider("Cantos arredondados", "round", 0, 15, CrtView.DEF[9], "");
            case 13: return slider("Sombra nos cantos", "corner", 0, 40, CrtView.DEF[5], "");
            case 14: return slider("Tamanho do Bob", "bob_size", 0, 100, 40, "%");
            case 15: return slider("Transparência do Bob (100 = sólido)", "bob_alpha", 10, 100, 100, "%");
            case 16: return actionButton("a_panel");
            case 17: return actionButton("a_perm");
            case 18: return actionButton("a_perf");
            case 19: return actionButton("a_ram");
            default: return null;
        }
    }

    // ---------- navegação entre páginas ----------
    private void buildNav() {
        navBox.removeAllViews();
        boolean land = landscape();
        LinearLayout n = new LinearLayout(this);
        n.setOrientation(land ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        n.setGravity(Gravity.CENTER);
        Button prev = navButton(land ? "\u25B2" : "<"), next = navButton(land ? "\u25BC" : ">");
        prev.setOnClickListener(v -> changePage(-1));
        next.setOnClickListener(v -> changePage(1));
        pageLabel = new TextView(this);
        pageLabel.setTextColor(Color.WHITE);
        pageLabel.setTypeface(null, Typeface.BOLD);
        pageLabel.setTextSize(16);
        pageLabel.setGravity(Gravity.CENTER);
        pageLabel.setText((page + 1) + "/" + pageCount());
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

    private Button navButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(20);
        b.setBackground(box(0x66000000, 0xAAFFFFFF));
        return b;
    }

    // ---------- peças de tela ----------
    private GradientDrawable box(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke((int) (1.5f * dens), stroke);
        g.setCornerRadius(10 * dens);
        return g;
    }

    private Button flat(String text) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextColor(INK);
        b.setTypeface(null, Typeface.BOLD);
        b.setTextSize(15);
        b.setBackground(box(0x40FFFFFF, 0xAA1E1208));
        return b;
    }

    private Button actionButton(final String key) {
        Button b = flat(actionText(key));
        b.setOnClickListener(v -> doAction(key));
        buttons.put(key, b);
        return b;
    }

    private View presetRow() {
        LinearLayout row = new LinearLayout(this);
        Button light = flat("CRT leve"), strong = flat("CRT forte");
        light.setOnClickListener(v -> preset(LIGHT));
        strong.setOnClickListener(v -> preset(STRONG));
        row.addView(light, new LinearLayout.LayoutParams(0, -1, 1));
        row.addView(strong, new LinearLayout.LayoutParams(0, -1, 1));
        return row;
    }

    private View slider(final String label, final String key, final int min, int max, int def, final String unit) {
        boolean land = landscape();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (8 * dens);
        row.setPadding(pad, 0, pad, 0);

        int cur = sp.getInt(key, def);
        final TextView text = new TextView(this);
        text.setTextColor(INK);
        text.setTypeface(null, Typeface.BOLD);
        text.setTextSize(14);
        text.setText(label + ": " + cur + unit);

        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(cur - min);
        bar.setProgressTintList(ColorStateList.valueOf(INK));
        bar.setThumbTintList(ColorStateList.valueOf(INK));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0x661E1208));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                sp.edit().putInt(key, progress + min).apply();
                text.setText(label + ": " + (progress + min) + unit);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        if (land) {
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(bar, new LinearLayout.LayoutParams(0, -2, 1f));
        } else {
            row.addView(text, new LinearLayout.LayoutParams(-1, -2));
            row.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        }
        return row;
    }

    private View gameRow(final Game g) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (8 * dens), icon = (int) (40 * dens);
        row.setPadding(pad, pad / 2, pad, pad / 2);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(g.icon);
        row.addView(iv, new LinearLayout.LayoutParams(icon, icon));
        TextView name = new TextView(this);
        name.setText(g.label);
        name.setTextColor(INK);
        name.setTypeface(null, Typeface.BOLD);
        name.setTextSize(15);
        name.setSingleLine(true);
        name.setPadding(pad, 0, pad, 0);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        Button play = flat("JOGAR");
        play.setOnClickListener(v -> play(g));
        row.addView(play, new LinearLayout.LayoutParams(-2, -1));
        return row;
    }

    // ---------- ações ----------
    private String actionText(String key) {
        switch (key) {
            case "a_toggle": return OverlayService.running ? "Desligar CRT" : "Ligar CRT";
            case "a_music": return sp.getBoolean("music_on", true) ? "Música: ligada" : "Música: desligada";
            case "a_bar": return CrtAccessService.instance != null
                    ? "Barra de baixo: coberta" : "Cobrir a barra de baixo (ativar)";
            case "a_panel": return sp.getBoolean("panel_on", true) ? "Painel do Bob: ligado" : "Painel do Bob: desligado";
            case "a_perm": return Settings.System.canWrite(this)
                    ? "Brilho do painel: permitido" : "Permitir controle de brilho";
            case "a_perf": return sp.getBoolean("perf_on", false) ? "Modo desempenho: ligado" : "Modo desempenho: desligado";
            case "a_ram": return "Limpar a RAM agora";
            default: return "";
        }
    }

    private void refresh() {
        for (Map.Entry<String, Button> e : buttons.entrySet()) e.getValue().setText(actionText(e.getKey()));
    }

    private void doAction(String key) {
        switch (key) {
            case "a_toggle": toggleFilter(); return;
            case "a_music": toggleMusic(); break;
            case "a_bar": startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return;
            case "a_panel": sp.edit().putBoolean("panel_on", !sp.getBoolean("panel_on", true)).apply(); break;
            case "a_perm":
                startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName())));
                return;
            case "a_perf": sp.edit().putBoolean("perf_on", !sp.getBoolean("perf_on", false)).apply(); break;
            case "a_ram": cleanRam(); return;
            default: break;
        }
        refresh();
    }

    private void preset(int[] v) {
        SharedPreferences.Editor e = sp.edit();
        for (int i = 1; i < 6; i++) e.putInt(CrtView.KEYS[i], v[i]); // não mexe no "escurecer"
        e.apply();
        showPage();
    }

    private void cleanRam() {
        Toast.makeText(this, "Limpando a RAM…", Toast.LENGTH_SHORT).show();
        Ram.clean(this, (freed, avail) -> Toast.makeText(this,
                "RAM livre: " + avail + " MB (+" + freed + " MB)", Toast.LENGTH_LONG).show());
    }

    private void toggleFilter() {
        if (OverlayService.running) {
            stopService(new Intent(this, OverlayService.class));
        } else if (!startFilter()) {
            return;
        }
        stage.postDelayed(this::refresh, 400);
    }

    /** Liga o filtro. Devolve falso se ainda falta permissão. */
    private boolean startFilter() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Permita \"Exibir sobre outros apps\" e volte aqui", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            return false;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        startForegroundService(new Intent(this, OverlayService.class));
        if (CrtAccessService.instance == null)
            Toast.makeText(this, "Dica: ative o Tubo CRT em Acessibilidade pra cobrir a barra de baixo", Toast.LENGTH_LONG).show();
        return true;
    }

    // ---------- jogos ----------
    private void scanGames() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            final List<Game> found = new ArrayList<>();
            final List<Boolean> isGame = new ArrayList<>();
            for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
                ApplicationInfo ai = ri.activityInfo.applicationInfo;
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 || ai.packageName.equals(getPackageName())) continue;
                found.add(new Game(ai.packageName, String.valueOf(ri.loadLabel(pm)), ri.loadIcon(pm)));
                isGame.add(ai.category == ApplicationInfo.CATEGORY_GAME);
            }
            // jogos primeiro, depois ordem alfabética
            final List<Integer> order = new ArrayList<>();
            for (int i = 0; i < found.size(); i++) order.add(i);
            Collections.sort(order, (a, b) -> {
                boolean ga = isGame.get(a), gb = isGame.get(b);
                if (ga != gb) return ga ? -1 : 1;
                return found.get(a).label.compareToIgnoreCase(found.get(b).label);
            });
            final List<Game> sorted = new ArrayList<>();
            for (int i : order) sorted.add(found.get(i));
            runOnUiThread(() -> {
                games = sorted;
                showPage();
            });
        }).start();
    }

    /** Liga o CRT, limpa a RAM (se o modo desempenho estiver ligado) e abre o jogo. */
    private void play(Game g) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(g.pkg);
        if (launch == null) {
            Toast.makeText(this, "Não consegui abrir " + g.label, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!OverlayService.running && !startFilter()) return;
        if (sp.getBoolean("perf_on", false)) Ram.clean(this, null);
        startActivity(launch);
    }

    // ---------- gifs ----------
    private Drawable load(String name) {
        try {
            return ImageDecoder.decodeDrawable(ImageDecoder.createSource(getAssets(), name));
        } catch (Exception e) {
            return null; // arquivo ausente: o app funciona sem ele
        }
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
        if (!(d instanceof AnimatedImageDrawable)) {
            intro.setVisibility(View.GONE);
            introDone = true;
            return;
        }
        AnimatedImageDrawable a = (AnimatedImageDrawable) d;
        a.setRepeatCount(0); // toca uma vez só
        a.registerAnimationCallback(new Animatable2.AnimationCallback() {
            @Override public void onAnimationEnd(Drawable dr) { endIntro(); }
        });
        intro.setImageDrawable(a);
        intro.setOnClickListener(v -> endIntro()); // um toque pula
        a.start();
        intro.postDelayed(this::endIntro, 6000);   // garantia
    }

    private void endIntro() {
        if (introDone) return;
        introDone = true;
        intro.animate().alpha(0f).setDuration(400).withEndAction(() -> intro.setVisibility(View.GONE)).start();
        startMusic(); // a música começa quando a abertura termina
    }

    // ---------- música (opcional: sem o arquivo o app só fica sem som) ----------
    private void startMusic() {
        if (!introDone || !sp.getBoolean("music_on", true)) return;
        if (music == null) {
            int id = getResources().getIdentifier("intro_music", "raw", getPackageName());
            if (id == 0) return;
            music = MediaPlayer.create(this, id);
            if (music == null) return;
            music.setLooping(true);
        }
        if (!music.isPlaying()) music.start();
    }

    private void toggleMusic() {
        boolean on = !sp.getBoolean("music_on", true);
        sp.edit().putBoolean("music_on", on).apply();
        if (on) startMusic(); else if (music != null && music.isPlaying()) music.pause();
    }
}
