package com.crtoverlay;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int GREEN = 0xFF5BB387, BRIGHT = 0xFF9BF0BE, BG = 0xFF03100A;
    // dim, curve, per, scan, mask, corner  (os presets nao mexem no resto)
    private static final int[] LIGHT = {0, 20, 0, 35, 15, 10};   // valores do CRT LIGHT do Black
    private static final int[] STRONG = {0, 45, 0, 60, 30, 20};  // valores do CRT STRONG do Black

    private static class Item {
        final String name, desc, key, unit; final int min, max, def, step, kind; // kind 0 = valor com - e +, 1 = botao
        Item(String name, String desc, String key, int min, int max, int def, int step, String unit, int kind) {
            this.name = name; this.desc = desc; this.key = key; this.min = min; this.max = max;
            this.def = def; this.step = step; this.unit = unit; this.kind = kind;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private final List<View> rows = new ArrayList<>();
    private final List<TextView> vals = new ArrayList<>();
    private SharedPreferences sp;
    private float dens;
    private CrtScreen screen;
    private CrtView uiCrt;
    private ImageView intro;
    private TextView infoTitle, infoDesc, mainBtn;
    private MediaPlayer mp;
    private boolean introDone = false;
    private int sel = 0;
    private final SharedPreferences.OnSharedPreferenceChangeListener prefL = (p, k) -> applyPreview();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("crt", MODE_PRIVATE);
        dens = getResources().getDisplayMetrics().density;
        defineItems();

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        screen = new CrtScreen(this);
        frame.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        uiCrt = new CrtView(this);
        frame.addView(uiCrt, new FrameLayout.LayoutParams(-1, -1));
        frame.addView(new GlowView(this), new FrameLayout.LayoutParams(-1, -1));
        intro = new ImageView(this);
        intro.setScaleType(ImageView.ScaleType.CENTER_CROP);
        intro.setBackgroundColor(Color.BLACK);
        frame.addView(intro, new FrameLayout.LayoutParams(-1, -1));
        setContentView(frame);

        buildUi();
        applyPreview();
        sp.registerOnSharedPreferenceChangeListener(prefL);
        if (b == null) playIntro(); else intro.setVisibility(View.GONE); // a TV so abre quando o app e aberto de novo
    }

    // ---------- lista de ajustes ----------
    private void add(String name, String desc, String key, int min, int max, int def, int step, String unit, int kind) {
        items.add(new Item(name, desc, key, min, max, def, step, unit, kind));
    }

    private void defineItems() {
        add("MÚSICA", "Música de fundo desta tela. Ela para sozinha quando você sai do app pra jogar.", "a_music", 0, 0, 0, 0, "", 1);
        add("PAINEL LATERAL", "Aba na borda direita da tela, com volume, brilho, captura de tela e apagar a tela. Aparece com o CRT ligado.", "a_panel", 0, 0, 0, 0, "", 1);
        add("BARRA DE BAIXO", "Ative o Tubo CRT em Acessibilidade pro filtro cobrir também a barra de botões. Depois desligue e ligue o CRT. Também libera os botões de captura e de apagar a tela do painel.", "a_bar", 0, 0, 0, 0, "", 1);
        add("PERMISSÃO DE BRILHO", "O controle de brilho do painel lateral precisa da permissão \"Modificar configurações do sistema\".", "a_perm", 0, 0, 0, 0, "", 1);
        add("PRESET LEVE", "Valores do filtro CRT LIGHT do jogo Black.", "a_leve", 0, 0, 0, 0, "", 1);
        add("PRESET FORTE", "Valores do filtro CRT STRONG do jogo Black.", "a_forte", 0, 0, 0, 0, "", 1);
        add("ESCURECER TELA", "Escurece a tela inteira. 0 é normal.", "dim", 0, 90, CrtView.DEF[0], 5, "%", 0);
        add("CURVA DA TV", "Curva do vidro: cantos pretos e arredondados por cima do jogo. No Black, 20 é o leve e 45 o forte. Nesta tela o olho de peixe é de verdade.", "curve", 0, 150, CrtView.DEF[1], 5, "", 0);
        add("ALTURA DA LINHA", "Altura de cada linha, em pixels da tela. 0 deixa automático.", "per", 0, 8, CrtView.DEF[2], 1, "", 0);
        add("SCANLINES", "Força das linhas escuras entre as linhas da imagem.", "scan", 0, 80, CrtView.DEF[3], 5, "%", 0);
        add("MOVIMENTO", "As linhas descem devagar, com uma faixa escura e uma tremida leve no brilho. 0 deixa parado e poupa bateria.", "motion", 0, 100, CrtView.DEF[6], 5, "", 0);
        add("LINHA FINA", "Quanto maior, mais fina e marcada fica cada linha.", "hard", 2, 16, CrtView.DEF[7], 1, "", 0);
        add("MÁSCARA", "Força da máscara de fósforo, as listras do tubo.", "mask", 0, 50, CrtView.DEF[4], 5, "%", 0);
        add("TIPO DE MÁSCARA", "1 TV comum, 2 grade Trinitron, 3 VGA esticada, 4 VGA.", "mtype", 1, 4, CrtView.DEF[8], 1, "", 0);
        add("CANTOS ARREDONDADOS", "Tamanho dos cantos arredondados da tela.", "round", 0, 15, CrtView.DEF[9], 1, "", 0);
        add("SOMBRA NOS CANTOS", "Escurece as bordas, como num tubo de verdade.", "corner", 0, 40, CrtView.DEF[5], 2, "", 0);
        add("ALTURA DO PAINEL", "Posição da aba do painel lateral na borda, de cima (0) até embaixo (100).", "panel_y", 0, 100, 25, 5, "%", 0);
    }

    private boolean landscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    // ---------- tela ----------
    private TextView mono(String t, float size, int color) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setTextColor(color);
        tv.setTextSize(size);
        tv.setShadowLayer(6 * dens, 0, 0, 0xAA5BB387); // brilho de fosforo
        return tv;
    }

    private GradientDrawable box(int fill, int stroke, float radiusDp, boolean oval) {
        GradientDrawable g = new GradientDrawable();
        if (oval) g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        g.setStroke((int) (1.5f * dens), stroke);
        g.setCornerRadius(radiusDp * dens);
        return g;
    }

    private void buildUi() {
        boolean land = landscape();
        screen.removeAllViews();
        rows.clear(); vals.clear();
        int pad = (int) (14 * dens);

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(BG);
        outer.setPadding(pad * 2, pad, pad * 2, pad); // folga pros cantos arredondados
        screen.addView(outer, new FrameLayout.LayoutParams(-1, -1));

        TextView head = mono("BEM-VINDO AO TUBO CRT", land ? 22 : 19, BRIGHT);
        head.setGravity(Gravity.CENTER);
        outer.addView(head, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        outer.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));

        // lista de ajustes
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < items.size(); i++) list.addView(buildRow(i), new LinearLayout.LayoutParams(-1, (int) (54 * dens)));
        sv.addView(list);

        // coluna de informacao: Bob, nome e descricao
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView bob = new ImageView(this);
        bob.setScaleType(ImageView.ScaleType.FIT_CENTER);
        bob.setBackground(box(0xFF000000, 0x885BB387, 10, false));
        int bp = (int) (6 * dens);
        bob.setPadding(bp, bp, bp, bp);
        Drawable bd = load("bob.gif");
        bob.setImageDrawable(bd);
        if (bd instanceof AnimatedImageDrawable) {
            ((AnimatedImageDrawable) bd).setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
            ((AnimatedImageDrawable) bd).start();
        }
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, 0, 1f);
        blp.setMargins(0, pad, 0, pad / 2);
        info.addView(bob, blp);
        infoTitle = mono("", land ? 24 : 20, BRIGHT);
        infoTitle.setGravity(Gravity.CENTER);
        info.addView(infoTitle, new LinearLayout.LayoutParams(-1, -2));
        infoDesc = new TextView(this);
        infoDesc.setTypeface(Typeface.SERIF);
        infoDesc.setTextColor(GREEN);
        infoDesc.setTextSize(land ? 15 : 14);
        infoDesc.setGravity(Gravity.CENTER);
        infoDesc.setPadding(0, pad / 2, 0, pad / 2);
        info.addView(infoDesc, new LinearLayout.LayoutParams(-1, -2));

        mainBtn = mono("", 18, BRIGHT);
        mainBtn.setGravity(Gravity.CENTER);
        mainBtn.setBackground(box(0x335BB387, 0xFF5BB387, 24, false));
        mainBtn.setOnClickListener(v -> onToggle());
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, (int) (48 * dens));
        mlp.setMargins(0, pad / 2, 0, 0);

        if (land) {
            info.addView(mainBtn, mlp);
            LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, -1, 1f);
            a.setMargins(0, pad, pad, 0);
            body.addView(sv, a);
            body.addView(info, new LinearLayout.LayoutParams(0, -1, 1.1f));
        } else {
            body.addView(info, new LinearLayout.LayoutParams(-1, 0, 1f));
            body.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
            outer.addView(mainBtn, mlp);
        }
        select(sel);
        refreshActions();
        refreshMain();
    }

    private View buildRow(final int idx) {
        final Item it = items.get(idx);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pd = (int) (8 * dens);
        row.setPadding(pd, 0, pd, 0);
        row.setOnClickListener(v -> select(idx));
        TextView name = mono(it.name, 13, GREEN);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        int s40 = (int) (40 * dens), m = (int) (4 * dens);
        TextView val = mono("", 14, BRIGHT);
        val.setGravity(Gravity.CENTER);
        val.setMinWidth((int) (64 * dens));
        val.setBackground(box(0x00000000, 0xAA5BB387, 20, false));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-2, s40);
        vlp.setMargins(m, 0, m, 0);
        if (it.kind == 0) {
            TextView minus = mono("−", 22, GREEN), plus = mono("+", 22, GREEN);
            minus.setGravity(Gravity.CENTER); plus.setGravity(Gravity.CENTER);
            minus.setBackground(box(0x1A5BB387, 0xAA5BB387, 20, true));
            plus.setBackground(box(0x1A5BB387, 0xAA5BB387, 20, true));
            val.setText(sp.getInt(it.key, it.def) + it.unit);
            bindRepeat(minus, idx, -1);
            bindRepeat(plus, idx, 1);
            row.addView(minus, new LinearLayout.LayoutParams(s40, s40));
            row.addView(val, vlp);
            row.addView(plus, new LinearLayout.LayoutParams(s40, s40));
        } else {
            val.setOnClickListener(v -> { select(idx); doAction(it.key); });
            row.addView(val, vlp);
        }
        rows.add(row);
        vals.add(val);
        return row;
    }

    private void bindRepeat(View btn, final int idx, final int dir) {
        final Handler hd = new Handler(Looper.getMainLooper());
        final Runnable[] rep = new Runnable[1];
        rep[0] = () -> { step(idx, dir); hd.postDelayed(rep[0], 110); };
        btn.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) { step(idx, dir); hd.postDelayed(rep[0], 380); }
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) hd.removeCallbacks(rep[0]);
            return true;
        });
    }

    private void step(int idx, int dir) {
        Item it = items.get(idx);
        int cur = sp.getInt(it.key, it.def);
        int nv = Math.max(it.min, Math.min(it.max, cur + dir * it.step));
        sp.edit().putInt(it.key, nv).apply();
        vals.get(idx).setText(nv + it.unit);
        select(idx);
    }

    private void select(int idx) {
        sel = idx;
        for (int i = 0; i < rows.size(); i++) rows.get(i).setBackground(i == idx ? box(0x335BB387, 0x665BB387, 8, false) : null);
        infoTitle.setText(items.get(idx).name);
        infoDesc.setText(items.get(idx).desc);
    }

    private String actionText(String key) {
        switch (key) {
            case "a_music": return sp.getBoolean("music_on", true) ? "LIGADA" : "DESLIGADA";
            case "a_panel": return sp.getBoolean("panel_on", true) ? "LIGADO" : "DESLIGADO";
            case "a_bar": return CrtAccessService.instance != null ? "ATIVA" : "ATIVAR";
            case "a_perm": return Settings.System.canWrite(this) ? "OK" : "PERMITIR";
            default: return "APLICAR";
        }
    }

    private void doAction(String key) {
        switch (key) {
            case "a_music": toggleMusic(); break;
            case "a_panel": sp.edit().putBoolean("panel_on", !sp.getBoolean("panel_on", true)).apply(); break;
            case "a_bar": startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); break;
            case "a_perm":
                startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName())));
                break;
            case "a_leve": preset(LIGHT); return;
            case "a_forte": preset(STRONG); return;
            default: break;
        }
        refreshActions();
    }

    private void refreshActions() {
        for (int i = 0; i < items.size(); i++)
            if (items.get(i).kind == 1) vals.get(i).setText(actionText(items.get(i).key));
    }

    private void refreshMain() {
        mainBtn.setText(OverlayService.running ? "> DESLIGAR CRT" : "> LIGAR CRT");
    }

    private void preset(int[] v) {
        SharedPreferences.Editor e = sp.edit();
        for (int i = 1; i < 6; i++) e.putInt(CrtView.KEYS[i], v[i]); // nao mexe no "escurecer"
        e.apply();
        buildUi();
    }

    /** O filtro desta tela mostra ao vivo o que voce esta ajustando (a curva aqui entorta de verdade). */
    private void applyPreview() {
        int[] v = new int[CrtView.KEYS.length];
        for (int i = 0; i < v.length; i++) v[i] = sp.getInt(CrtView.KEYS[i], CrtView.DEF[i]);
        int curve = Math.max(v[1], 35);
        uiCrt.set(0, curve, v[2], v[3], v[4], v[5], 30, v[7], v[8], v[9]);
        screen.setCurve(curve / 1000f);
    }

    // ---------- gifs ----------
    private Drawable load(String name) {
        try { return ImageDecoder.decodeDrawable(ImageDecoder.createSource(getAssets(), name)); }
        catch (Exception e) { return null; }
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
        buildUi();
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
    }

    @Override protected void onPause() {
        super.onPause();
        if (mp != null && mp.isPlaying()) mp.pause(); // sai do app = musica para
    }

    @Override protected void onDestroy() {
        sp.unregisterOnSharedPreferenceChangeListener(prefL);
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
        screen.postDelayed(this::refreshMain, 400);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshActions();
        refreshMain();
        startMusic();
    }
}
