package com.crtoverlay;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Tela de ajustes: uma lista que rola e encaixa em cima das prateleiras do cenário.
 * No pilar da esquerda ficam dois botões: ▦ abre as opções de ligar/desligar e + escolhe os seus jogos.
 */
public class MainActivity extends Activity {
    private static final int REQ_VOICE_ZIP = 7711, REQ_VOICE_TREE = 7712, REQ_MUSIC_FOLDER = 7, REQ_AUDIO = 8, REQ_TV_FOLDER = 9;
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

    private final Map<String, Button> buttons = new HashMap<>();
    private volatile List<Game> games = Collections.emptyList();
    private SharedPreferences sp;
    private float dens;
    private FrameLayout stage;
    private SnapScrollView scroller;
    private LinearLayout list;
    private IconButton optionsButton, addButton, gamesButton;
    private ImageView bg, intro;
    private MediaPlayer appMusic;
    private TvSound appTv;
    private boolean introDone = false;
    private int bandPx, pitchPx, bgToken;

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
        scroller = new SnapScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(list, new FrameLayout.LayoutParams(-1, -2));
        scroller.setVisibility(View.INVISIBLE); // só aparece quando o cenário carregar, pra alinhar nas prateleiras
        stage.addView(scroller);

        optionsButton = new IconButton(this, IconButton.GRID);
        addButton = new IconButton(this, IconButton.PLUS);
        gamesButton = new IconButton(this, IconButton.PAD);
        optionsButton.setOnClickListener(v -> showOptions());
        addButton.setOnClickListener(v -> chooseGames());
        gamesButton.setOnClickListener(v -> showGames());
        stage.addView(optionsButton);
        stage.addView(addButton);
        stage.addView(gamesButton);
        stage.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> layoutStage());

        intro = new ImageView(this);
        intro.setScaleType(ImageView.ScaleType.CENTER_CROP);
        intro.setBackgroundColor(Color.BLACK);
        frame.addView(intro, new FrameLayout.LayoutParams(-1, -1));
        setContentView(frame);

        buildList();
        boolean fromPanel = getIntent() != null && getIntent().getBooleanExtra("force_stop_all", false);
        if (state == null && !fromPanel) {
            playIntro();
        } else { // tela girou, app recriado ou veio do botão PARAR do painel: sem abertura
            intro.setVisibility(View.GONE);
            introDone = true;
        }
        loadBackground();
        loadGames();
        handleLaunchIntent();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchIntent();
    }

    private void handleLaunchIntent() {
        Intent i = getIntent();
        if (i != null && i.getBooleanExtra("force_stop_all", false)) {
            i.removeExtra("force_stop_all");
            stage.post(this::confirmForceStopAll);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (getIntent().getBooleanExtra("ask_audio", false)) { // o painel pediu (toque no analisador)
            getIntent().removeExtra("ask_audio");
            askAudio();
        } else if (!sp.getBoolean("audio_asked", false)) {        // na primeira vez, já pergunta
            sp.edit().putBoolean("audio_asked", true).apply();
            askAudio();
        }
        refresh();
        if (introDone) startBackground();
        startMusic(); // só depois da abertura
        Brightness.forceManual(this, sp);
    }

    @Override protected void onPause() {
        super.onPause();
        if (appMusic != null && appMusic.isPlaying()) appMusic.pause(); // saiu do app = a música do app para
        if (appTv != null) appTv.setPlaying(false);
        Drawable background = bg.getDrawable(); // e o cenário animado também (menos calor)
        if (background instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) background).stop();
    }

    @Override protected void onDestroy() {
        if (appMusic != null) { appMusic.release(); appMusic = null; }
        if (appTv != null) { appTv.release(); appTv = null; }
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        loadBackground();
        buildList();
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

    // ---------- posição da lista e dos botões do pilar ----------
    /** Só mexe nas posições quando algo mudou de verdade; senão a tela entra num ciclo de refazer layout sem parar. */
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
        float pitch = (bands[3][0] - bands[0][0]) / 3f * sh;
        float band = (bands[0][1] - bands[0][0]) * sh;
        float x0 = land ? 0.195f : 0.035f, x1 = land ? 0.97f : 0.965f;

        place(scroller, Math.round(dx + sw * x0), Math.round(dy + sh * bands[0][0]),
                Math.round(sw * (x1 - x0)), Math.round(3 * pitch + band));
        bandPx = Math.max(1, Math.round(band));
        pitchPx = Math.max(bandPx, Math.round(pitch));
        scroller.setPitch(pitchPx);
        applyRowSizes();

        int size = Math.round(52 * dens);
        if (land) { // pilar: coluna branca à esquerda
            int cx = Math.round(dx + sw * 0.135f - size / 2f), y = Math.round(dy + sh * 0.14f);
            place(optionsButton, cx, y, size, size);
            place(addButton, cx, y + Math.round(size * 1.35f), size, size);
            place(gamesButton, cx, y + Math.round(size * 2.7f), size, size);
        } else {    // pilar: faixa de cima
            int x = Math.round(dx + sw * 0.12f), y = Math.round(dy + sh * 0.025f);
            place(optionsButton, x, y, size, size);
            place(addButton, x + Math.round(size * 1.4f), y, size, size);
            place(gamesButton, x + Math.round(size * 2.8f), y, size, size);
        }
    }

    private void place(View v, int x, int y, int w, int h) {
        ViewGroup.LayoutParams current = v.getLayoutParams();
        if (current instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams c = (FrameLayout.LayoutParams) current;
            if (c.leftMargin == x && c.topMargin == y && c.width == w && c.height == h) return;
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.max(1, w), Math.max(1, h));
        lp.leftMargin = x;
        lp.topMargin = y;
        v.setLayoutParams(lp);
    }

    private void applyRowSizes() {
        if (bandPx <= 0) return;
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) row.getLayoutParams();
            if (lp.height == bandPx && lp.bottomMargin == pitchPx - bandPx) continue;
            lp.height = bandPx;
            lp.bottomMargin = pitchPx - bandPx;
            row.setLayoutParams(lp);
        }
    }

    // ---------- lista ----------
    private void buildList() {
        int keep = scroller.getScrollY();
        list.removeAllViews();
        buttons.clear();
        addRow(actionButton("a_toggle"));
        addRow(presetRow());
        addRow(slider("Escurecer tela", "dim", 0, 90, CrtView.DEF[0], "%"));
        addRow(slider("Curva da TV (Black: 20 / 45)", "curve", 0, 150, CrtView.DEF[1], ""));
        addRow(slider("Scanlines (força)", "scan", 0, 80, CrtView.DEF[3], "%"));
        addRow(slider("Movimento das linhas (0 = parado)", "motion", 0, 100, CrtView.DEF[6], ""));
        addRow(slider("Altura da linha (0 = auto)", "per", 0, 8, CrtView.DEF[2], ""));
        addRow(slider("Linha fina", "hard", 2, 16, CrtView.DEF[7], ""));
        addRow(slider("Máscara de pixels (0 = limpa)", "mask", 0, 50, CrtView.DEF[4], "%"));
        addRow(slider("Tipo de máscara (1 a 4)", "mtype", 1, 4, CrtView.DEF[8], ""));
        addRow(slider("Cantos arredondados", "round", 0, 15, CrtView.DEF[9], ""));
        addRow(slider("Sombra nos cantos", "corner", 0, 40, CrtView.DEF[5], ""));
        addRow(slider("Tamanho do Bob", "bob_size", 0, 100, 40, "%"));
        addRow(slider("Transparência do Bob (100 = sólido)", "bob_alpha", 10, 100, 100, "%"));
        addRow(slider("Fluidez do Bob (0 = leve, 100 = suave)", "bob_fluid", 0, 100, 60, ""));
        addRow(slider("Chiado de TV na música (0 = desligado)", "tv_noise", 0, 100, 0, "%"));
        addRow(slider("Tamanho do painel (%)", "panel_scale", 50, 100, 80, "%"));
        addRow(slider("Tamanho da letra do versículo", "verse_size", 10, 26, 14, ""));
        addRow(slider("Escuridão do céu do painel (%)", "sky_dark", 0, 60, 22, "%"));
        addRow(slider("Volume dos efeitos (cliques e level up)", "sfx_volume", 0, 100, 35, "%"));
        addRow(slider("Tempo do versículo na tela (%)", "verse_time", 50, 300, 100, "%"));
        applyRowSizes();
        refresh();
        final int y = keep;
        scroller.post(() -> scroller.scrollTo(0, y));
    }

    private void addRow(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, bandPx > 0 ? bandPx : Math.round(60 * dens));
        if (bandPx > 0) lp.bottomMargin = pitchPx - bandPx;
        list.addView(v, lp);
    }

    private GradientDrawable box(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(Math.round(1.5f * dens), stroke);
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
        b.setOnClickListener(v -> toggleFilter());
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
        int pad = Math.round(8 * dens);
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
                if ("tv_noise".equals(key)) applyTvNoise();
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

    private String actionText(String key) {
        return OverlayService.running ? "Desligar CRT" : "Ligar CRT";
    }

    private void refresh() {
        for (Map.Entry<String, Button> e : buttons.entrySet()) e.getValue().setText(actionText(e.getKey()));
    }

    private void preset(int[] v) {
        SharedPreferences.Editor e = sp.edit();
        for (int i = 1; i < 6; i++) e.putInt(CrtView.KEYS[i], v[i]); // não mexe no "escurecer"
        e.apply();
        buildList();
    }

    // ---------- ▦ opções de ligar e desligar ----------
    private Switch switchRow(String label, boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        Switch s = new Switch(this);
        copperSwitch(s);
        s.setText(label);
        s.setTextSize(16);
        int pad = Math.round(8 * dens);
        s.setPadding(0, pad, 0, pad);
        s.setChecked(checked);
        s.setOnCheckedChangeListener(listener);
        return s;
    }

    private Button dialogButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setOnClickListener(l);
        copperButton(b);
        return b;
    }

    private android.graphics.drawable.Drawable assetDrawable(String name) {
        try (java.io.InputStream in = getAssets().open(name)) {
            return new android.graphics.drawable.BitmapDrawable(getResources(), android.graphics.BitmapFactory.decodeStream(in));
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    /** Troca o verde-água dos interruptores por cobre com um pingo escorrendo. */
    private void copperSwitch(Switch s) {
        android.graphics.drawable.Drawable thumb = assetDrawable("copper_thumb.png");
        android.graphics.drawable.Drawable on = assetDrawable("copper_track_on.png"), off = assetDrawable("copper_track_off.png");
        if (thumb == null || on == null || off == null) return;
        android.graphics.drawable.StateListDrawable track = new android.graphics.drawable.StateListDrawable();
        track.addState(new int[]{android.R.attr.state_checked}, on);
        track.addState(new int[]{}, off);
        s.setThumbDrawable(thumb);
        s.setTrackDrawable(track);
        s.setSwitchMinWidth(Math.round(54 * dens));
    }

    /** Botões das opções em cobre texturizado com pingos. */
    private void copperButton(Button b) {
        android.graphics.drawable.Drawable bg = assetDrawable("copper_btn.png");
        if (bg == null) return;
        b.setBackground(bg);
        b.setTextColor(0xFFFFF3E0);
        b.setShadowLayer(3f, 0f, 1.5f, 0xFF3A1A08);
        b.setPadding(Math.round(12 * dens), Math.round(10 * dens), Math.round(12 * dens), Math.round(18 * dens));
    }

    private void showOptions() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16 * dens);
        box.setPadding(pad, pad / 2, pad, pad / 2);

        box.addView(switchRow("CRT ligado", OverlayService.running, (b, on) -> {
            if (on == OverlayService.running) return;
            if (on) { if (!startFilter()) b.setChecked(false); }
            else stopService(new Intent(this, OverlayService.class));
        }));
        box.addView(switchRow("Música do app (só aqui dentro)", sp.getBoolean("music_on", true), (b, on) -> {
            sp.edit().putBoolean("music_on", on).apply();
            if (on) {
                startMusic();
            } else {
                if (appMusic != null && appMusic.isPlaying()) appMusic.pause();
                if (appTv != null) appTv.setPlaying(false);
            }
        }));
        box.addView(switchRow("Bob e painel por cima de tudo", sp.getBoolean("panel_on", true),
                (b, on) -> sp.edit().putBoolean("panel_on", on).apply()));
        box.addView(switchRow("Modo desempenho (tela e CPU acordadas)", sp.getBoolean("perf_on", false), (b, on) -> {
            SharedPreferences.Editor ed = sp.edit().putBoolean("perf_on", on);
            if (on) ed.putBoolean("cool_on", false); // desempenho e gelado não ficam ligados juntos
            ed.apply();
        }));
        box.addView(switchRow("Modo gelado (animações paradas, brilho baixo)", sp.getBoolean("cool_on", false), (b, on) -> {
            SharedPreferences.Editor ed = sp.edit().putBoolean("cool_on", on);
            if (on) ed.putBoolean("perf_on", false);
            ed.apply();
        }));
        box.addView(dialogButton("Biblioteca de livros (PDF)", v -> startActivity(new Intent(this, BookActivity.class))));
        box.addView(dialogButton("Dublagem da Bíblia (importar ZIP ou pasta)", v -> showVoiceDialog()));
        box.addView(dialogButton("Cores das falas dos versículos", v -> showSpeechColors()));
        box.addView(switchRow("Colorir os nomes (Elohim, Yahuah, Yahusha, profetas, discípulos)", sp.getBoolean("col_names", true), (b, on) -> sp.edit().putBoolean("col_names", on).apply()));
        box.addView(switchRow("Colorir as falas de outras pessoas", sp.getBoolean("col_other_on", true), (b, on) -> sp.edit().putBoolean("col_other_on", on).apply()));
        box.addView(dialogButton("Botões da gaveta (você escolhe)", v -> showDrawerDialog()));
        box.addView(dialogButton("TV de vídeo (playlists por pasta)", v -> showTvDialog()));
        box.addView(switchRow("Analisador de espectro (pede permissão de áudio)", sp.getBoolean("spectrum_on", true), (b, on) -> {
            sp.edit().putBoolean("spectrum_on", on).apply();
            if (on && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            else MusicPlayer.get(this).refreshVisualizer();
        }));
        box.addView(switchRow("Sons do painel (TV de tubo)", sp.getBoolean("ui_sounds", true), (b, on) -> sp.edit().putBoolean("ui_sounds", on).apply()));
        box.addView(switchRow("Ler um capítulo da Bíblia sozinho com a dublagem (também a cada 50 patos)", sp.getBoolean("bible_auto", true), (b, on) -> sp.edit().putBoolean("bible_auto", on).apply()));
        box.addView(slider("Intervalo base (30 = 30 min, depois 1 h, 1 h 30 e 2 h)", "bible_every", 5, 60, 30, " min"));
        box.addView(switchRow("Painel fecha sozinho depois de 10 s (desligado = fica aberto)", sp.getBoolean("panel_autoclose", false), (b, on) -> sp.edit().putBoolean("panel_autoclose", on).apply()));
        box.addView(switchRow("Espada solta depois de 2 min parado", sp.getBoolean("familiar_on", true), (b, on) -> sp.edit().putBoolean("familiar_on", on).apply()));
        box.addView(switchRow("Brilho nunca automático", sp.getBoolean("no_auto_bright", true), (b, on) -> {
            sp.edit().putBoolean("no_auto_bright", on).apply();
            Brightness.forceManual(this, sp);
        }));

        box.addView(dialogButton("Cobrir a barra de baixo: " + (CrtAccessService.instance != null ? "ativa" : "ativar"),
                v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        box.addView(dialogButton("Controle de brilho: " + (Settings.System.canWrite(this) ? "permitido" : "permitir"),
                v -> startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName())))));
        box.addView(dialogButton("Pasta de músicas: " + folderName(), v -> pickMusicFolder()));
        box.addView(dialogButton("Cidade do clima: " + sp.getString("wx_name", "não definida"), v -> askCity()));
        box.addView(dialogButton("Limpar a RAM agora", v -> cleanRam()));
        box.addView(dialogButton("Parar todos os apps (força parada)", v -> confirmForceStopAll()));
        box.addView(dialogButton("Esconder a notificação (abre as configurações)", v -> openNotificationSettings()));

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("Opções")
                .setView(sv)
                .setPositiveButton("Fechar", null)
                .setOnDismissListener(dlg -> refresh())
                .show();
    }

    /** Pede a permissão de áudio do analisador de espectro (ela só serve pra ler o espectro da música). */
    private void askAudio() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
    }

    private void applyTvNoise() {
        MusicPlayer.get(this).applyTvSound();
        if (appTv != null) appTv.apply();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != REQ_AUDIO) return;
        boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        Toast.makeText(this, ok ? "Analisador liberado" : "Sem a permissão o analisador não funciona", Toast.LENGTH_LONG).show();
        if (ok) MusicPlayer.get(this).refreshVisualizer();
    }

    private void cleanRam() {
        Sfx.ramClean(this);
        Toast.makeText(this, "Limpando a RAM…", Toast.LENGTH_SHORT).show();
        Ram.clean(this, (freed, avail) -> Toast.makeText(this,
                "RAM livre: " + avail + " MB (+" + freed + " MB)", Toast.LENGTH_LONG).show());
    }

    private void toggleFilter() {
        if (OverlayService.running) stopService(new Intent(this, OverlayService.class));
        else if (!startFilter()) return;
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

    // ---------- pasta de músicas ----------
    private String folderName() {
        String saved = sp.getString("music_tree", null);
        if (saved == null) return "nenhuma";
        try {
            String id = DocumentsContract.getTreeDocumentId(Uri.parse(saved));
            String name = id.substring(id.indexOf(':') + 1);
            return name.isEmpty() ? "memória interna" : name;
        } catch (RuntimeException e) {
            return "escolhida";
        }
    }

    /** Escolhe quais dos 6 botões ficam dentro da gaveta (os outros continuam no painel). */
    private void showDrawerDialog() {
        final String[] labels = {"FOTO", "APAGAR", "RAM", "MAX", "PARAR", "GELO"}; // na ordem dos botões do painel
        final boolean[] checked = new boolean[6];
        int mask = sp.getInt("drawer_mask", 0);
        for (int i = 0; i < 6; i++) checked[i] = (mask & (1 << i)) != 0;
        new android.app.AlertDialog.Builder(this)
                .setTitle("O que vai dentro da gaveta?")
                .setMultiChoiceItems(labels, checked, (dlg, which, on) -> checked[which] = on)
                .setPositiveButton("Salvar", (dlg, w) -> {
                    int m = 0;
                    for (int i = 0; i < 6; i++) if (checked[i]) m |= 1 << i;
                    sp.edit().putInt("drawer_mask", m).apply();
                    Toast.makeText(this, "Gaveta atualizada", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ---------- TV de vídeo: cada pasta é uma playlist ----------
    private void showTvDialog() {
        final java.util.List<String> folders = TvLibrary.folders(sp);
        java.util.List<String> items = new java.util.ArrayList<>();
        for (String f : folders) items.add("Pasta: " + TvLibrary.folderName(f));
        items.add("+ Adicionar pasta de vídeos");
        if (TvService.running) items.add("Fechar a TV");
        new android.app.AlertDialog.Builder(this)
                .setTitle("TV de vídeo: escolha a playlist")
                .setItems(items.toArray(new String[0]), (dlg, which) -> {
                    if (which < folders.size()) showTvVideos(folders.get(which));
                    else if (which == folders.size()) pickTvFolder();
                    else startService(new Intent(this, TvService.class).setAction(TvService.ACTION_STOP));
                })
                .setNegativeButton("Voltar", null)
                .show();
    }

    private void pickTvFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, REQ_TV_FOLDER);
    }

    private void showTvVideos(final String tree) {
        Toast.makeText(this, "Lendo a pasta…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final java.util.List<TvLibrary.Video> vids = TvLibrary.videos(this, tree);
            runOnUiThread(() -> {
                if (vids.isEmpty()) {
                    Toast.makeText(this, "Nenhum vídeo nessa pasta", Toast.LENGTH_LONG).show();
                    return;
                }
                String[] items = new String[vids.size() + 1];
                items[0] = "▶ Tocar a pasta toda (" + vids.size() + " vídeos)";
                for (int i = 0; i < vids.size(); i++) items[i + 1] = vids.get(i).name;
                new android.app.AlertDialog.Builder(this)
                        .setTitle(TvLibrary.folderName(tree))
                        .setItems(items, (dlg, which) -> startTv(tree, which == 0 ? 0 : which - 1))
                        .setNegativeButton("Voltar", (dlg, w) -> showTvDialog())
                        .setNeutralButton("Remover pasta", (dlg, w) -> {
                            TvLibrary.removeFolder(sp, tree);
                            showTvDialog();
                        })
                        .show();
            });
        }).start();
    }

    private void startTv(String tree, int index) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Falta liberar \"aparecer sobre outros apps\"", Toast.LENGTH_LONG).show();
            return;
        }
        startForegroundService(new Intent(this, TvService.class).setAction(TvService.ACTION_PLAY)
                .putExtra("folder", tree).putExtra("index", index));
    }

    private void pickMusicFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, REQ_MUSIC_FOLDER);
    }


    // ---------- dublagem da Bíblia: o app só reconhece os arquivos numerados, nunca mexe neles ----------
    private void showVoiceDialog() {
        final int n = VoiceLibrary.index(this, sp).size();
        new AlertDialog.Builder(this)
                .setTitle("Dublagem da Bíblia")
                .setMessage("Capítulos reconhecidos agora: " + n + " de 1189.\n\n"
                        + "O app reconhece pelo NÚMERO na frente do nome (0001_..., 0461_..., 1189_...). "
                        + "Os arquivos não são renomeados nem alterados.\n\n"
                        + "• Importar ZIP: copia os MP3 de dentro do ZIP para o app (pode escolher vários ZIPs, ocupa espaço).\n"
                        + "• Escolher pasta: usa a pasta onde os MP3 já estão, sem copiar nada.")
                .setPositiveButton("Importar ZIP", (dlg, w) -> {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(i, REQ_VOICE_ZIP);
                })
                .setNeutralButton("Escolher pasta", (dlg, w) -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_VOICE_TREE))
                .setNegativeButton("Apagar importados", (dlg, w) -> {
                    VoiceLibrary.clearImported(this);
                    sp.edit().remove("voice_tree").apply();
                    Toast.makeText(this, "Dublagem importada apagada (seus ZIPs e pastas originais não foram tocados).", Toast.LENGTH_LONG).show();
                }).show();
    }

    private void importVoiceZips(List<Uri> zips) {
        Toast.makeText(this, "Importando " + zips.size() + " ZIP(s)… pode demorar, deixe o app aberto.", Toast.LENGTH_LONG).show();
        new Thread(() -> {
            int total = 0;
            for (Uri z : zips) {
                int k = VoiceLibrary.importZip(this, z);
                if (k > 0) total += k;
            }
            final int done = total;
            runOnUiThread(() -> Toast.makeText(this, done > 0 ? "Pronto: " + done + " capítulos importados."
                    : "Não achei MP3 numerados dentro desse ZIP.", Toast.LENGTH_LONG).show());
        }).start();
    }

    private static final String[] SPEAKERS = {"Jesus (Yahusha)", "Deus (Elohim, Yahuah)", "Profetas", "Discípulos", "Outras pessoas (falas)"};
    private static final String[] SPEAKER_KEYS = {"col_jesus", "col_god", "col_prophet", "col_disciple", "col_other"};
    private static final int[] SPEAKER_DEF = {0xFFC00018, 0xFF0A3CA8, 0xFF9A6A00, 0xFF0B5D1E, 0xFF00796B};
    private static final String[] COLOR_NAMES = {"Vermelho", "Azul", "Dourado", "Verde escuro", "Roxo", "Laranja", "Preto", "Marrom"};
    private static final int[] COLOR_VALUES = {0xFFC00018, 0xFF0A3CA8, 0xFF9A6A00, 0xFF0B5D1E, 0xFF6A1B9A, 0xFFD35400, 0xFF111111, 0xFF6D4C41};

    private void showSpeechColors() {
        String[] items = new String[SPEAKERS.length + 1];
        for (int i = 0; i < SPEAKERS.length; i++) items[i] = SPEAKERS[i];
        items[SPEAKERS.length] = "Restaurar cores padrão";
        new AlertDialog.Builder(this).setTitle("Cor de quem fala").setItems(items, (dlg, which) -> {
            if (which == SPEAKERS.length) {
                SharedPreferences.Editor e = sp.edit();
                for (String k : SPEAKER_KEYS) e.remove(k);
                e.apply();
                return;
            }
            new AlertDialog.Builder(this).setTitle(SPEAKERS[which]).setItems(COLOR_NAMES, (d2, c) ->
                    sp.edit().putInt(SPEAKER_KEYS[which], COLOR_VALUES[c]).apply()).show();
        }).show();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQ_VOICE_ZIP && result == RESULT_OK && data != null) {
            List<Uri> zips = new ArrayList<>();
            if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) zips.add(data.getClipData().getItemAt(i).getUri());
            else if (data.getData() != null) zips.add(data.getData());
            if (!zips.isEmpty()) importVoiceZips(zips);
            return;
        }
        if (request == REQ_VOICE_TREE && result == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            try { getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (RuntimeException ignored) { /* vale até fechar o app */ }
            sp.edit().putString("voice_tree", tree.toString()).apply();
            Toast.makeText(this, "Pasta da dublagem guardada. Abra a Bíblia para ver quantos capítulos foram reconhecidos.", Toast.LENGTH_LONG).show();
            return;
        }
        if (request == REQ_TV_FOLDER && result == RESULT_OK && data != null && data.getData() != null) {
            Uri folder = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (RuntimeException ignored) {
                // algumas pastas não deixam guardar a permissão: vale só até fechar o app
            }
            TvLibrary.addFolder(sp, folder.toString());
            showTvDialog();
            return;
        }
        if (request != REQ_MUSIC_FOLDER || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException ignored) {
            // algumas pastas não deixam guardar a permissão: vale só até fechar o app
        }
        sp.edit().putString("music_tree", tree.toString()).apply();
        new Thread(() -> {
            MusicPlayer m = MusicPlayer.get(this);
            m.rescan();
            runOnUiThread(() -> Toast.makeText(this, m.count() + " músicas encontradas", Toast.LENGTH_LONG).show());
        }).start();
    }

    /** Só o nome da cidade vai pra internet, pra buscar a temperatura. O app não usa sua localização. */
    private void askCity() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Ex.: Recife");
        input.setText(sp.getString("wx_name", ""));
        new AlertDialog.Builder(this)
                .setTitle("Cidade do clima")
                .setView(input)
                .setPositiveButton("Buscar", (dlg, which) -> {
                    final String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    Toast.makeText(this, "Procurando…", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        String found = null;
                        try { found = Weather.findCity(this, name); } catch (Exception ignored) { /* sem internet */ }
                        final String result = found;
                        runOnUiThread(() -> Toast.makeText(this, result != null ? "Clima de " + result
                                : "Não achei essa cidade (precisa de internet)", Toast.LENGTH_LONG).show());
                        if (result != null) Weather.refreshIfStale(this);
                    }).start();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ---------- parar todos os apps ----------
    /** Apps que podem ser parados: instalados por você e abríveis, menos este, a sua lista de jogos, a tela inicial e o teclado. */
    private List<String> stoppableApps() {
        PackageManager pm = getPackageManager();
        Set<String> keep = new HashSet<>(sp.getStringSet("games_pkgs", Collections.<String>emptySet()));
        keep.add(getPackageName());
        ResolveInfo home = pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        if (home != null) keep.add(home.activityInfo.packageName);
        String ime = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ime != null && ime.indexOf('/') > 0) keep.add(ime.substring(0, ime.indexOf('/')));
        List<String> out = new ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_STOPPED)) != 0) continue;
            if (!keep.contains(ai.packageName) && pm.getLaunchIntentForPackage(ai.packageName) != null) out.add(ai.packageName);
        }
        Collections.sort(out);
        return out.size() > 40 ? new ArrayList<>(out.subList(0, 40)) : out; // até 40 por vez, pra não demorar demais
    }

    private void confirmForceStopAll() {
        final CrtAccessService service = CrtAccessService.instance;
        if (service == null) {
            Toast.makeText(this, "Ative o Tubo CRT em Acessibilidade primeiro (talvez precise desligar e ligar de novo)", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (service.isStopping()) {
            Toast.makeText(this, "Já está parando os apps…", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<String> targets = stoppableApps();
        if (targets.isEmpty()) {
            Toast.makeText(this, "Nenhum app aberto pra parar", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Parar todos os apps?")
                .setMessage("Vou fechar até " + targets.size() + " apps, uns " + Math.round(targets.size() * 2.5f)
                        + " segundos. Ficam de fora o Tubo CRT, a sua lista de jogos, a tela inicial e o teclado.\n\n"
                        + "As telas de Configurações vão piscar. Não mexa no celular até aparecer \"Pronto\".")
                .setPositiveButton("Parar", (dlg, which) -> service.forceStopAll(targets))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void openNotificationSettings() {
        OverlayService.ensureChannel(this); // o canal precisa existir pra aparecer nas configurações
        try {
            startActivity(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                    .putExtra(Settings.EXTRA_CHANNEL_ID, OverlayService.CHANNEL_ID));
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }
    }

    // ---------- meus jogos ----------
    /** O 🎮 do pilar: abre a lista dos apps que você escolheu no +. */
    private void showGames() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16 * dens);
        box.setPadding(pad, pad / 2, pad, pad / 2);
        final AlertDialog[] dialog = new AlertDialog[1];
        if (games.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Você ainda não escolheu nenhum jogo.\nToque no + do pilar pra escolher.");
            empty.setTextSize(16);
            box.addView(empty);
        }
        for (final Game g : games) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int icon = Math.round(44 * dens);
            ImageView iv = new ImageView(this);
            Drawable.ConstantState cs = g.icon.getConstantState();
            iv.setImageDrawable(cs != null ? cs.newDrawable() : g.icon);
            row.addView(iv, new LinearLayout.LayoutParams(icon, icon));
            TextView name = new TextView(this);
            name.setText(g.label);
            name.setTextSize(16);
            name.setSingleLine(true);
            name.setPadding(pad / 2, 0, pad / 2, 0);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            Button go = new Button(this);
            go.setText("JOGAR");
            go.setOnClickListener(v -> {
                if (dialog[0] != null) dialog[0].dismiss();
                play(g);
            });
            row.addView(go, new LinearLayout.LayoutParams(-2, -2));
            box.addView(row);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        dialog[0] = new AlertDialog.Builder(this)
                .setTitle("Meus jogos")
                .setView(sv)
                .setPositiveButton("Fechar", null)
                .setNeutralButton("+ Escolher", (dlg, which) -> chooseGames())
                .show();
    }

    private void loadGames() {
        final Set<String> chosen = new HashSet<>(sp.getStringSet("games_pkgs", Collections.<String>emptySet()));
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<Game> out = new ArrayList<>();
            for (String pkg : chosen) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    out.add(new Game(pkg, String.valueOf(pm.getApplicationLabel(ai)), pm.getApplicationIcon(ai)));
                } catch (PackageManager.NameNotFoundException ignored) {
                    // app foi desinstalado: some da lista
                }
            }
            Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
            runOnUiThread(() -> games = out);
        }).start();
    }

    /** O +: mostra todos os apps do celular e você marca os que quer na sua lista. */
    private void chooseGames() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            Map<String, String> byPackage = new TreeMap<>();
            for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (!pkg.equals(getPackageName())) byPackage.put(pkg, String.valueOf(ri.loadLabel(pm)));
            }
            final List<Map.Entry<String, String>> apps = new ArrayList<>(byPackage.entrySet());
            Collections.sort(apps, (a, b) -> a.getValue().compareToIgnoreCase(b.getValue()));
            Set<String> chosen = sp.getStringSet("games_pkgs", Collections.<String>emptySet());
            final CharSequence[] names = new CharSequence[apps.size()];
            final boolean[] checked = new boolean[apps.size()];
            for (int i = 0; i < apps.size(); i++) {
                names[i] = apps.get(i).getValue();
                checked[i] = chosen.contains(apps.get(i).getKey());
            }
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("Escolha os apps da sua lista")
                    .setMultiChoiceItems(names, checked, (dlg, which, on) -> checked[which] = on)
                    .setPositiveButton("Salvar", (dlg, which) -> {
                        Set<String> out = new HashSet<>();
                        for (int i = 0; i < apps.size(); i++) if (checked[i]) out.add(apps.get(i).getKey());
                        sp.edit().putStringSet("games_pkgs", out).apply();
                        loadGames();
                    })
                    .setNegativeButton("Cancelar", null)
                    .show());
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

    // ---------- cenário e abertura ----------
    private Drawable load(String name) {
        try {
            return ImageDecoder.decodeDrawable(ImageDecoder.createSource(getAssets(), name));
        } catch (Exception e) {
            return null; // arquivo ausente: o app funciona sem ele
        }
    }

    /** O cenário é grande: carrega fora da tela principal, pra não travar a abertura. */
    private void loadBackground() {
        final int token = ++bgToken;
        final String name = landscape() ? "bg_land.gif" : "bg_portrait.webp";
        new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            final Drawable d = load(name);
            runOnUiThread(() -> {
                if (token != bgToken || isDestroyed()) return;
                bg.setImageDrawable(d);
                scroller.setVisibility(View.VISIBLE);
                if (introDone) startBackground(); // durante a abertura ele fica parado, pra não pesar
                stage.post(this::layoutStage);
            });
        }).start();
    }

    private void startBackground() {
        Drawable d = bg.getDrawable();
        if (d instanceof AnimatedImageDrawable) {
            ((AnimatedImageDrawable) d).setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
            ((AnimatedImageDrawable) d).start();
        }
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
        startBackground();
        startMusic(); // a música começa quando a abertura termina
    }

    // ---------- música do app (opcional: sem o arquivo o app só fica sem som) ----------
    private void startMusic() {
        if (!introDone || !sp.getBoolean("music_on", true) || MusicPlayer.isPlayingNow()) return;
        if (appMusic == null) {
            int id = getResources().getIdentifier("intro_music", "raw", getPackageName());
            if (id == 0) return;
            appMusic = MediaPlayer.create(this, id);
            if (appMusic == null) return;
            appMusic.setLooping(true);
            appTv = new TvSound(this); // chiado de TV também na música do app
            appTv.attach(appMusic.getAudioSessionId());
        }
        if (!appMusic.isPlaying()) { appMusic.start(); if (appTv != null) appTv.setPlaying(true); }
    }

    // ---------- botão redondo do pilar ----------
    private static final class IconButton extends View {
        static final int GRID = 0, PLUS = 1, PAD = 2;
        private final int kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        IconButton(android.content.Context c, int kind) {
            super(c);
            this.kind = kind;
            setClickable(true);
        }

        @Override public void setPressed(boolean pressed) {
            super.setPressed(pressed);
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, rad = Math.min(cx, cy);
            p.setStyle(Paint.Style.FILL);
            p.setColor(isPressed() ? 0xEE3A3A3A : 0xDD141414);
            c.drawCircle(cx, cy, rad, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(rad * 0.06f);
            p.setColor(0x88FFFFFF);
            c.drawCircle(cx, cy, rad * 0.94f, p);
            p.setColor(0xFFEFEFEF);
            if (kind == GRID) {
                float s = rad * 0.28f, gap = rad * 0.12f;
                p.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 2; i++) {
                    for (int j = 0; j < 2; j++) {
                        float left = cx + (i == 0 ? -gap / 2f - s : gap / 2f);
                        float top = cy + (j == 0 ? -gap / 2f - s : gap / 2f);
                        r.set(left, top, left + s, top + s);
                        c.drawRoundRect(r, s * 0.2f, s * 0.2f, p);
                    }
                }
            } else if (kind == PLUS) {
                p.setStrokeWidth(rad * 0.14f);
                p.setStrokeCap(Paint.Cap.ROUND);
                c.drawLine(cx - rad * 0.4f, cy, cx + rad * 0.4f, cy, p);
                c.drawLine(cx, cy - rad * 0.4f, cx, cy + rad * 0.4f, p);
            } else { // controle de videogame
                p.setStyle(Paint.Style.FILL);
                r.set(cx - rad * 0.58f, cy - rad * 0.30f, cx + rad * 0.58f, cy + rad * 0.32f);
                c.drawRoundRect(r, rad * 0.30f, rad * 0.30f, p);
                p.setColor(isPressed() ? 0xEE3A3A3A : 0xDD141414);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeWidth(rad * 0.10f);
                float padX = cx - rad * 0.30f, padY = cy + rad * 0.01f;
                c.drawLine(padX - rad * 0.12f, padY, padX + rad * 0.12f, padY, p);
                c.drawLine(padX, padY - rad * 0.12f, padX, padY + rad * 0.12f, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx + rad * 0.24f, cy + rad * 0.05f, rad * 0.07f, p);
                c.drawCircle(cx + rad * 0.37f, cy - rad * 0.06f, rad * 0.07f, p);
            }
        }
    }
}
