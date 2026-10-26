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
    private static final int REQ_MUSIC_FOLDER = 7;
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
        if (state == null) {
            playIntro();
        } else { // tela girou ou o app foi recriado: sem abertura
            intro.setVisibility(View.GONE);
            introDone = true;
        }
        loadBackground();
        loadGames();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
        startMusic(); // só depois da abertura
        Brightness.forceManual(this, sp);
    }

    @Override protected void onPause() {
        super.onPause();
        if (appMusic != null && appMusic.isPlaying()) appMusic.pause(); // saiu do app = a música do app para
    }

    @Override protected void onDestroy() {
        if (appMusic != null) { appMusic.release(); appMusic = null; }
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
        return b;
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
            if (on) startMusic(); else if (appMusic != null && appMusic.isPlaying()) appMusic.pause();
        }));
        box.addView(switchRow("Bob e painel por cima de tudo", sp.getBoolean("panel_on", true),
                (b, on) -> sp.edit().putBoolean("panel_on", on).apply()));
        box.addView(switchRow("Modo desempenho (tela e CPU acordadas)", sp.getBoolean("perf_on", false),
                (b, on) -> sp.edit().putBoolean("perf_on", on).apply()));
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

    private void cleanRam() {
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

    private void pickMusicFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, REQ_MUSIC_FOLDER);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
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
        }
        if (!appMusic.isPlaying()) appMusic.start();
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
