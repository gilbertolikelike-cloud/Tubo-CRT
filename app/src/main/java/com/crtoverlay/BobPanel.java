package com.crtoverlay;

import android.accessibilityservice.AccessibilityService;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.Toast;
import java.io.IOException;
import java.io.InputStream;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Random;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * O Bob que fica por cima de tudo. Toque nele: abre o painel de pergaminho com o céu (sol, lua, nuvens e chuva
 * seguem a hora e o clima), a temperatura, dois botões de girar (volume e brilho), a hora em 12h, quatro botões
 * (captura, apagar a tela, limpar RAM, modo desempenho) e o player de música da sua pasta.
 *
 * O Bob muda sozinho: de dia é o Bob com sombra, de noite ele dança na discoteca e com o modo desempenho ligado
 * vira Super Saiyajin. Arraste ele pra qualquer lugar: ele olha pro lado do centro da tela e fica de cabeça pra
 * baixo quando está lá em cima.
 *
 * Botão de girar: girar pra direita (sentido horário) diminui, pra esquerda aumenta.
 */
public class BobPanel extends View {
    /** Os desenhos do Bob olham pra esquerda. */
    private static final boolean ASSET_FACES_LEFT = true;

    private static final int M_NONE = 0, M_BOB = 1, M_VOL = 2, M_BRI = 3;
    private static final int M_BTN = 4;       // 4..9  = FOTO, APAGAR, RAM, MAX, PARAR, GELO
    private static final int M_TRANSPORT = 10; // 10..12 = anterior, tocar/pausar, próxima
    private static final String[] BUTTON_LABEL = {"FOTO", "APAGAR", "RAM", "MAX", "PARAR", "GELO"};
    private static final int[] BUTTON_RING = {0xFF2EAD5B, 0xFFD94040, 0xFF4F6BFF, 0xFFFFC107, 0xFFFF7043, 0xFF4FC3F7};
    private static final int M_SPECTRUM = 13, M_DRAWER = 20, M_TVLIST = 21, M_DRAWER_CLOSE = 22;
    /** Posição dos 6 botões (dp), na ordem FOTO, APAGAR, RAM, MAX, PARAR, GELO. Coluna da direita (de cima pra baixo): APAGAR, FOTO, MAX. Coluna do meio: PARAR, GELO, RAM. */
    private static final float[] BTN_X = {204, 204, 160, 204, 160, 160}, BTN_Y = {142, 92, 192, 192, 92, 142};
    private static final float RAINBOW_LEN = 120f;
    private static final long AUTO_CLOSE_MS = 10000;
    private static final float PANEL_W_DP = 232f, PANEL_H_DP = 360f, BORDER_DP = 6f;
    private static final int BUTTONS = 6;
    private static final float KNOB_R = 32f, BUTTON_R = 15f, TRANSPORT_R = 17f; // dp

    // cor do céu ao longo do dia
    private static final float[] SKY_HOURS = {0f, 5f, 6.5f, 8f, 16f, 17.5f, 19f, 21f, 24f};
    private static final int[] SKY_COLORS = {0xFF0B1030, 0xFF1B2350, 0xFFFF9966, 0xFF7EC8F8, 0xFF7EC8F8,
            0xFFFF8A5C, 0xFF2B2A5E, 0xFF0B1030, 0xFF0B1030};
    private static final float[] STAR_X = {0.10f, 0.22f, 0.35f, 0.50f, 0.63f, 0.80f, 0.92f};
    private static final float[] STAR_Y = {0.30f, 0.65f, 0.25f, 0.70f, 0.35f, 0.60f, 0.28f};

    /** Uma das folhas de quadros do Bob (descrita em bob_meta.json). */
    private static final class Variant {
        boolean blend = true;
        String file;
        int cols, rows, frames;
        float ms, aspect, scale;
    }

    private final Context ctx;
    private final WindowManager wm;
    private final boolean viaAccessibility;
    private final SharedPreferences sp;
    private final AudioManager audio;
    private final MusicPlayer music;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float dens;                // densidade da tela
    private float d, panW, panH;              // escala do painel (densidade x tamanho escolhido) e tamanho em px
    private final UiSounds sounds;
    private float tickAccum;
    private boolean drawerOpen; // a gaveta com os 6 botões
    private Runnable activityListener;
    private BitmapShader woodShader;
    private Bitmap woodBitmap;
    private final int slop;
    private final long t0 = SystemClock.uptimeMillis();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bobPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint blendPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF r = new RectF();
    private final RectF dst = new RectF();
    private final Rect src = new Rect();
    private final Path path = new Path();
    private final Matrix matrix = new Matrix();
    private final Matrix rainbowMatrix = new Matrix();
    private final Matrix iridescentMatrix = new Matrix();
    private Shader rainbow, iridescent, sunGlow, stripA, stripB, stripC;
    private final Paint uiPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Map<String, Variant> variants = new HashMap<>();
    private static final String[] DANCERS = {"disco", "shaggy", "papyrus", "duck"};
    private final Random rnd = new Random();
    private final float[] specLevel = new float[MusicPlayer.BANDS], specPeak = new float[MusicPlayer.BANDS];
    private String dancer = "disco";
    private boolean wasPlaying;
    private TrailView trail;
    private final Runnable dropTrailRunnable = this::dropTrail;
    private final Runnable autoClose = () -> setOpen(false, true);
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!added || paused) return;
            boolean nowPlaying = music.playing();
            if (nowPlaying && !wasPlaying) pickDancer(); // música começou: sorteia quem dança
            wasPlaying = nowPlaying;
            ensureVariant();
            ensureSky();
            invalidate();
            handler.postDelayed(this, tickInterval());
        }
    };

    private Bitmap sheet, paperBitmap, capBig, capSmall, capTransport, dialRing;
    private BitmapShader plate;
    private Variant variant, skyVariant;
    private Bitmap skySheet;
    private String skyKey = "";
    private boolean skyLoading;
    private int skyW, skyH;
    private String currentVariant = "";
    private boolean loading, paused;
    private int frameW, frameH, skyShaderColor;
    private Shader skyShader;
    private WindowManager.LayoutParams lp;
    private ValueAnimator animator;
    private boolean open, added, askedPermission, dragging;
    private float anim;                           // 0 fechado .. 1 aberto
    private float bobW, bobH, bobAlpha = 1f, bobX, bobY;   // Bob na tela (px)
    private float panX, panY, winX, winY;                  // painel e janela na tela (px)
    private int screenW, screenH, fx, fy;         // fx, fy: posição do Bob, em milésimos do espaço livre da tela
    private int mode = M_NONE;
    private float downRawX, downRawY, startBobX, startBobY, lastAngle, volume, brightness;

    public BobPanel(Context ctx, WindowManager wm, boolean viaAccessibility, SharedPreferences sp) {
        super(ctx);
        this.ctx = ctx;
        this.wm = wm;
        this.viaAccessibility = viaAccessibility;
        this.sp = sp;
        dens = ctx.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        audio = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        music = MusicPlayer.get(ctx);
        sounds = UiSounds.get(ctx);
        blendPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD)); // soma o segundo quadro por cima do primeiro
        buildAssets();
        loadMeta();
        loadPaper();
    }

    /** (Re)cria tudo que depende do tamanho do painel: escala, gradientes e peças com textura. */
    private void buildAssets() {
        d = dens * Math.max(0.5f, Math.min(1f, sp.getInt("panel_scale", 80) / 100f));
        panW = PANEL_W_DP * d;
        panH = PANEL_H_DP * d;
        for (Bitmap b : new Bitmap[]{capBig, capSmall, capTransport, dialRing, woodBitmap}) if (b != null) b.recycle();
        int[] colors = new int[7];
        for (int i = 0; i < colors.length; i++) colors[i] = Color.HSVToColor(new float[]{i * 60f % 360f, 1f, 1f});
        rainbow = new LinearGradient(0, 0, RAINBOW_LEN * d, 0, colors, null, Shader.TileMode.REPEAT);
        // furta-cor: ciano, violeta, rosa, dourado e verde-menta, deslizando como numa bolha de sabão
        iridescent = new LinearGradient(0, 0, 90 * d, 0, new int[]{0xFF00E5FF, 0xFF7C4DFF, 0xFFFF4DD2, 0xFFFFD54F, 0xFF69F0AE, 0xFF00E5FF},
                null, Shader.TileMode.REPEAT);
        // faixas pretas embutidas: colunas dos botões (50..206), analisador (212..254) e player (260..324)
        stripA = new LinearGradient(0, 50 * d, 0, 226 * d, 0xFF000000, 0xFF1C1C1C, Shader.TileMode.CLAMP);
        stripC = new LinearGradient(0, 232 * d, 0, 272 * d, 0xFF000000, 0xFF1C1C1C, Shader.TileMode.CLAMP);
        stripB = new LinearGradient(0, 278 * d, 0, 342 * d, 0xFF000000, 0xFF1C1C1C, Shader.TileMode.CLAMP);
        // peças com textura, desenhadas uma vez só: tampas de metal serrilhado, aro preto com marcas e a madeira da moldura
        capBig = makeCap(Math.round(42 * d));
        capSmall = makeCap(Math.round(24 * d));
        capTransport = makeCap(Math.round(26 * d));
        dialRing = makeDial(Math.round(68 * d));
        woodBitmap = makeWood(Math.max(32, Math.round(90 * d)));
        woodShader = new BitmapShader(woodBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        sunGlow = new RadialGradient(0, 0, 14 * d, 0x99FFE27A, 0x00FFE27A, Shader.TileMode.CLAMP);
    }

    /** Madeira escura de TV antiga: fundo laranja-amarronzado com veios na vertical. */
    private Bitmap makeWood(int px) {
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
        q.setShader(new LinearGradient(0, 0, px, 0, new int[]{0xFF8B4513, 0xFFB5651D, 0xFF8B4513, 0xFFA0551A, 0xFF8B4513}, null, Shader.TileMode.REPEAT));
        cv.drawRect(0, 0, px, px, q);
        q.setShader(null);
        Random grain = new Random(5);
        for (int i = 0; i < px; i++) {
            float x = grain.nextFloat() * px;
            q.setColor(grain.nextBoolean() ? Color.argb(40 + grain.nextInt(40), 40, 20, 5) : Color.argb(30 + grain.nextInt(30), 235, 165, 85));
            q.setStrokeWidth(0.6f + grain.nextFloat() * 1.4f);
            cv.drawLine(x, 0, x + grain.nextFloat() * 4 - 2, px, q);
        }
        return bmp;
    }

    public void setActivityListener(Runnable listener) { activityListener = listener; }

    private void notifyActivity() { if (activityListener != null) activityListener.run(); }

    // ---------- arquivos ----------
    private void loadMeta() {
        try (InputStream in = ctx.getAssets().open("bob_meta.json")) {
            byte[] buf = new byte[in.available()];
            int n = in.read(buf);
            JSONObject root = new JSONObject(new String(buf, 0, Math.max(0, n), "UTF-8"));
            java.util.Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject o = root.getJSONObject(key);
                Variant v = new Variant();
                v.file = o.getString("file");
                v.cols = o.getInt("cols");
                v.rows = o.getInt("rows");
                v.frames = o.getInt("frames");
                v.ms = (float) o.getDouble("ms");
                v.aspect = (float) o.getDouble("aspect");
                v.scale = (float) o.getDouble("scale");
                v.blend = o.optBoolean("blend", true);
                variants.put(key, v);
            }
        } catch (IOException | JSONException | RuntimeException ignored) {
            // sem os arquivos do Bob, desenha um Bob simples
        }
    }

    private void loadPaper() {
        try (InputStream in = ctx.getAssets().open("parchment.jpg")) {
            paperBitmap = BitmapFactory.decodeStream(in);
            if (paperBitmap != null) plate = new BitmapShader(paperBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        } catch (IOException | RuntimeException ignored) {
            paperBitmap = null; // sem o pergaminho, usa metal cinza
        }
    }

    private Bitmap decodeSheet(String file) {
        try (InputStream in = ctx.getAssets().open(file)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            return BitmapFactory.decodeStream(in, null, o);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ---------- qual Bob mostrar ----------
    private static boolean isNight() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h < 6 || h >= 18;
    }

    /**
     * Modo desempenho: Super Saiyajin. Ouvindo música: um dançarino sorteado (Bob da discoteca, Shaggy e Carl, Papyrus ou o pato).
     * Senão, de noite na vida real: a espada; de dia: o Bob com sombra.
     */
    private String wantedVariant() {
        if (sp.getBoolean("perf_on", false)) return "saiyan";
        if (music.playing()) return dancer;
        return isNight() ? "sword" : "day";
    }

    /** Sorteia o próximo dançarino, sem repetir o que está na tela. */
    private void pickDancer() {
        String next;
        do { next = DANCERS[rnd.nextInt(DANCERS.length)]; } while (next.equals(dancer));
        dancer = next;
    }

    private void ensureVariant() {
        final String want = wantedVariant();
        if (want.equals(currentVariant) || loading) return;
        final Variant v = variants.get(want);
        if (v == null) { currentVariant = want; return; }
        loading = true;
        new Thread(() -> {
            final Bitmap b = decodeSheet(v.file);
            handler.post(() -> {
                loading = false;
                if (!added) { if (b != null) b.recycle(); return; }
                Bitmap old = sheet;
                sheet = b;
                variant = v;
                currentVariant = want;
                if (b != null) { frameW = b.getWidth() / v.cols; frameH = b.getHeight() / v.rows; }
                if (old != null) old.recycle();
                refreshLook(); // a proporção muda de um Bob pro outro
            });
        }).start();
    }

    /** Carrega o gif do céu (dia ou noite) quando o painel abre e libera a memória quando fecha. */
    private void ensureSky() {
        if (!open && anim <= 0f) {
            if (skySheet != null) { skySheet.recycle(); skySheet = null; skyVariant = null; skyKey = ""; }
            return;
        }
        final String want = isNight() ? "sky_night" : "sky_day";
        if (want.equals(skyKey) || skyLoading) return;
        final Variant v = variants.get(want);
        if (v == null) { skyKey = want; return; }
        skyLoading = true;
        new Thread(() -> {
            final Bitmap b = decodeSheet(v.file);
            handler.post(() -> {
                skyLoading = false;
                if (!added || (!open && anim <= 0f)) { if (b != null) b.recycle(); return; }
                Bitmap old = skySheet;
                skySheet = b;
                skyVariant = v;
                skyKey = want;
                if (b != null) { skyW = b.getWidth() / v.cols; skyH = b.getHeight() / v.rows; }
                if (old != null) old.recycle();
            });
        }).start();
    }

    /** Quanto menor o intervalo, mais suave (e mais pesado). 0% = 160 ms (6 fps), 100% = 16 ms (60 fps). */
    private long tickInterval() {
        if (sp.getBoolean("cool_on", false)) return 250; // modo gelado: 4 quadros por segundo
        int f = Math.max(0, Math.min(100, sp.getInt("bob_fluid", 60)));
        long interval = 160 - 144L * f / 100;
        if (open && music.playing()) interval = Math.min(interval, 45); // o analisador precisa de quadros mais rápidos
        return interval;
    }

    // ---------- ciclo de vida ----------
    public void show() {
        if (added) return;
        refreshScreen();
        fx = sp.getInt("panel_fx", 1000);
        fy = sp.getInt("panel_fy", 250);
        ensureVariant(); // já começa a carregar o Bob certo (dia, noite ou saiyajin)
        computeLook();
        placeFromFractions();
        lp = new WindowManager.LayoutParams(Math.round(bobW), Math.round(bobH),
                viaAccessibility ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                        : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        winX = bobX;
        winY = bobY;
        lp.x = Math.round(winX);
        lp.y = Math.round(winY);
        try {
            wm.addView(this, lp);
            added = true;
        } catch (RuntimeException e) {
            return;
        }
        Weather.refreshIfStale(ctx);
        handler.post(tick);
    }

    public void hide() {
        added = false;
        handler.removeCallbacksAndMessages(null);
        dropTrail();
        if (animator != null) animator.cancel();
        try { wm.removeView(this); } catch (RuntimeException ignored) { /* janela já removida */ }
        if (sheet != null) { sheet.recycle(); sheet = null; }
        if (skySheet != null) { skySheet.recycle(); skySheet = null; }
        for (Bitmap b : new Bitmap[]{capBig, capSmall, capTransport, dialRing}) if (b != null) b.recycle();
        capBig = capSmall = capTransport = dialRing = null;
        if (paperBitmap != null) { paperBitmap.recycle(); paperBitmap = null; plate = null; }
    }

    // ---------- rastro da espada ----------
    /** Enquanto você arrasta a espada, uma janela de tela cheia (que não pega toque) desenha o rastro azul. */
    private void trailPoint() {
        if (!"sword".equals(currentVariant)) return;
        if (trail == null) {
            trail = new TrailView(ctx);
            WindowManager.LayoutParams tp = new WindowManager.LayoutParams(screenW, screenH,
                    viaAccessibility ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                            : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            tp.gravity = Gravity.TOP | Gravity.START;
            try {
                wm.addView(trail, tp);
            } catch (RuntimeException e) {
                trail = null;
                return;
            }
        }
        handler.removeCallbacks(dropTrailRunnable);
        boolean upsideDown = bobY + bobH / 2f < screenH * 0.35f;
        float cx = bobX + bobW / 2f;
        trail.add(cx, upsideDown ? bobY : bobY + bobH, cx, upsideDown ? bobY + bobH * 0.55f : bobY + bobH * 0.45f);
    }

    private void finishTrail() {
        if (trail != null) handler.postDelayed(dropTrailRunnable, 700); // deixa o rastro sumir sozinho
    }

    private void dropTrail() {
        handler.removeCallbacks(dropTrailRunnable);
        if (trail != null) {
            try { wm.removeView(trail); } catch (RuntimeException ignored) { /* janela já removida */ }
            trail = null;
        }
    }

    /** Tela apagada: o Bob para de desenhar até ela acender de novo. */
    public void setPaused(boolean paused) {
        this.paused = paused;
        if (!paused && added) {
            handler.removeCallbacks(tick);
            handler.post(tick);
        }
    }

    /** Tamanho, transparência ou Bob mudaram. */
    public void refreshLook() {
        if (!added) return;
        setOpen(false, false);
        buildAssets();
        computeLook();
        placeFromFractions();
        applyWindow(false);
        invalidate();
    }

    /** A tela girou. */
    public void onScreenChanged() {
        if (!added) return;
        setOpen(false, false);
        refreshScreen();
        computeLook();
        placeFromFractions();
        applyWindow(false);
        invalidate();
    }

    private void refreshScreen() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
    }

    private void computeLook() {
        float scale = variant != null ? variant.scale : 1f;
        bobH = Math.min((40 + sp.getInt("bob_size", 40)) * dens * scale, screenH * 0.6f); // 0% = 40 dp, 100% = 140 dp
        bobW = bobH * (variant != null ? variant.aspect : 1f);
        bobAlpha = Math.max(0.1f, Math.min(1f, sp.getInt("bob_alpha", 100) / 100f));
    }

    private void placeFromFractions() {
        bobX = fx / 1000f * Math.max(0f, screenW - bobW);
        bobY = fy / 1000f * Math.max(0f, screenH - bobH);
    }

    private void savePosition() {
        fx = Math.round(1000f * bobX / Math.max(1f, screenW - bobW));
        fy = Math.round(1000f * bobY / Math.max(1f, screenH - bobH));
        sp.edit().putInt("panel_fx", fx).putInt("panel_fy", fy).apply();
    }

    // ---------- janela e posição do painel ----------
    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(v, Math.max(lo, hi))); }

    /** Põe o painel ao lado do Bob, no lado com mais espaço; se não couber, embaixo ou em cima. */
    private void layoutPanel() {
        float gap = 4 * d;
        boolean toRight = bobX + bobW / 2f < screenW / 2f;
        float x = toRight ? bobX + bobW + gap : bobX - panW - gap;
        float y = bobY + bobH / 2f - panH / 2f;
        if (x < 0 || x + panW > screenW) {
            x = bobX + bobW / 2f - panW / 2f;
            y = bobY + bobH + gap + panH <= screenH ? bobY + bobH + gap : bobY - gap - panH;
        }
        panX = clamp(x, 0, screenW - panW);
        panY = clamp(y, 0, screenH - panH);
    }

    /** Janela do tamanho do Bob (fechado) ou do Bob + painel (aberto). */
    private void applyWindow(boolean forOpen) {
        if (lp == null) return;
        float w, h;
        if (!forOpen) {
            winX = bobX;
            winY = bobY;
            w = bobW;
            h = bobH;
        } else {
            layoutPanel();
            winX = Math.min(bobX, panX);
            winY = Math.min(bobY, panY);
            w = Math.max(bobX + bobW, panX + panW) - winX;
            h = Math.max(bobY + bobH, panY + panH) - winY;
        }
        lp.width = Math.round(w);
        lp.height = Math.round(h);
        lp.x = Math.round(winX);
        lp.y = Math.round(winY);
        if (added) {
            try { wm.updateViewLayout(this, lp); } catch (RuntimeException ignored) { /* janela removida */ }
        }
    }

    // ---------- abrir e fechar com animação ----------
    private void setOpen(boolean want, boolean animated) {
        if (!added) return;
        if (animator != null) animator.cancel();
        handler.removeCallbacks(autoClose);
        if (want) {
            open = true;
            askedPermission = false;
            volume = currentVolume();
            brightness = currentBrightness();
            new Thread(music::rescan).start(); // reconhece músicas novas na pasta
            Weather.refreshIfStale(ctx);
            applyWindow(true);
            ensureSky();
            animateTo(1f, true);
            handler.postDelayed(autoClose, AUTO_CLOSE_MS);
        } else {
            open = false;
            if (animated && anim > 0f) {
                animateTo(0f, false);
            } else {
                anim = 0f;
                applyWindow(false);
                invalidate();
            }
        }
    }

    private void animateTo(float target, final boolean opening) {
        animator = ValueAnimator.ofFloat(anim, target);
        animator.setDuration(opening ? 280 : 200);
        animator.setInterpolator(opening ? new DecelerateInterpolator(1.6f) : new AccelerateInterpolator());
        animator.addUpdateListener(a -> {
            anim = (Float) a.getAnimatedValue();
            invalidate();
        });
        if (!opening) {
            animator.addListener(new AnimatorListenerAdapter() {
                private boolean cancelled;
                @Override public void onAnimationCancel(Animator a) { cancelled = true; }
                @Override public void onAnimationEnd(Animator a) {
                    if (cancelled || open) return; // foi reaberto no meio do caminho
                    anim = 0f;
                    applyWindow(false);
                    invalidate();
                }
            });
        }
        animator.start();
    }

    // ---------- volume, brilho e ações ----------
    private float currentVolume() {
        int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max;
    }

    private float currentBrightness() {
        try {
            return Settings.System.getInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128) / 255f;
        } catch (RuntimeException e) {
            return 0.5f;
        }
    }

    private void applyVolume(float v) {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(v * max), 0);
    }

    private void applyBrightness(float v) {
        if (!Settings.System.canWrite(ctx)) {
            if (!askedPermission) {
                askedPermission = true;
                Toast.makeText(ctx, "Permita \"Modificar configurações do sistema\" pro Tubo CRT", Toast.LENGTH_LONG).show();
                try {
                    ctx.startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.getPackageName()))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException ignored) { /* sem tela de permissão neste aparelho */ }
            }
            return;
        }
        try {
            Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, Math.max(1, Math.round(v * 255)));
        } catch (RuntimeException ignored) { /* o sistema recusou */ }
    }

    private void onButton(int i) {
        sounds.beep();
        switch (i) {
            case 0: global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT); break;
            case 1: global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN); break;
            case 2:
                Sfx.ramClean(ctx);
                Toast.makeText(ctx, "Limpando a RAM…", Toast.LENGTH_SHORT).show();
                Ram.clean(ctx, (freed, avail) -> Toast.makeText(ctx,
                        "RAM livre: " + avail + " MB (+" + freed + " MB)", Toast.LENGTH_LONG).show());
                break;
            case 3: { // o Bob vira saiyajin no próximo quadro; desempenho e gelado não ficam ligados juntos
                boolean perfOn = !sp.getBoolean("perf_on", false);
                SharedPreferences.Editor perfEditor = sp.edit().putBoolean("perf_on", perfOn);
                if (perfOn) perfEditor.putBoolean("cool_on", false);
                perfEditor.apply();
                invalidate();
                break;
            }
            case 4: // PARAR: abre o app, que pede confirmação e fecha os outros apps
                setOpen(false, true);
                try {
                    ctx.startActivity(new Intent(ctx, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra("force_stop_all", true));
                } catch (RuntimeException e) {
                    Toast.makeText(ctx, "Abra o Tubo CRT e use ▦ → Parar todos os apps", Toast.LENGTH_LONG).show();
                }
                break;
            default: { // GELO: liga e desliga o modo gelado
                boolean coolOn = !sp.getBoolean("cool_on", false);
                SharedPreferences.Editor coolEditor = sp.edit().putBoolean("cool_on", coolOn);
                if (coolOn) coolEditor.putBoolean("perf_on", false);
                coolEditor.apply();
                invalidate();
                break;
            }
        }
    }

    private void onTransport(int i) {
        sounds.beep();
        if (music.count() == 0) {
            Toast.makeText(ctx, "Escolha a pasta de músicas no app (botão ▦ do pilar)", Toast.LENGTH_LONG).show();
            return;
        }
        if (i == 0) music.previous(); else if (i == 1) music.toggle(); else music.next();
        pickDancer(); // cada toque num botão do player sorteia um dançarino novo
        invalidate();
    }

    /** Captura e bloqueio de tela só existem via Acessibilidade. Fecha o painel antes, pra ele não sair na foto. */
    private void global(final int action) {
        setOpen(false, true);
        handler.postDelayed(() -> {
            CrtAccessService s = CrtAccessService.instance;
            if (s == null) Toast.makeText(ctx, "Ative o Tubo CRT em Acessibilidade pra usar este botão", Toast.LENGTH_LONG).show();
            else s.performGlobalAction(action);
        }, 500);
    }

    // ---------- geometria das peças (dentro do painel, em px) ----------
    private float knobX(int i) { return 48 * d; }
    private float knobY(int i) { return (i == 0 ? 82 : 172) * d; }
    private float buttonX(int i) { return BTN_X[i] * d; }
    private float buttonY(int i) { return BTN_Y[i] * d; }
    private float transportX(int i) { return (PANEL_W_DP / 2f + (i - 1) * 52) * d; }
    private float transportY() { return 321 * d; }

    private int hit(float x, float y) {
        float bl = bobX - winX, bt = bobY - winY;
        if (x >= bl && x <= bl + bobW && y >= bt && y <= bt + bobH) return M_BOB;
        if (anim < 0.5f) return M_NONE;
        float px = x - (panX - winX), py = y - (panY - winY);
        for (int i = 0; i < 2; i++) if (Math.hypot(px - knobX(i), py - knobY(i)) < 38 * d) return i == 0 ? M_VOL : M_BRI;
        if (drawerOpen) {
            for (int i = 0; i < BUTTONS; i++) if (Math.hypot(px - buttonX(i), py - buttonY(i)) < 20 * d) return M_BTN + i;
            if (Math.hypot(px - 182 * d, py - 62 * d) < 16 * d) return M_DRAWER_CLOSE;
        } else {
            if (Math.hypot(px - 182 * d, py - 102 * d) < 26 * d) return M_DRAWER;
            if (Math.hypot(px - 182 * d, py - 180 * d) < 26 * d) return M_TVLIST;
        }
        for (int i = 0; i < 3; i++) if (Math.hypot(px - transportX(i), py - transportY()) < 22 * d) return M_TRANSPORT + i;
        if (music.analyzerState() != 0 && px >= 12 * d && px <= panW - 12 * d && py >= 232 * d && py <= 272 * d) return M_SPECTRUM;
        return M_NONE;
    }

    private static float angleOf(float x, float y, float cx, float cy) {
        return (float) Math.toDegrees(Math.atan2(x - cx, -(y - cy))); // graus, sentido horário, 0 = pra cima
    }

    // ---------- toque ----------
    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        float px = x - (panX - winX), py = y - (panY - winY);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_OUTSIDE: // alguém tocou na tela fora do Bob: o aparelho não está parado
                notifyActivity();
                return false;
            case MotionEvent.ACTION_DOWN:
                notifyActivity();
                mode = hit(x, y);
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                startBobX = bobX;
                startBobY = bobY;
                dragging = false;
                tickAccum = 0f;
                if (mode == M_VOL || mode == M_BRI) {
                    int k = mode == M_VOL ? 0 : 1;
                    lastAngle = angleOf(px, py, knobX(k), knobY(k));
                    sounds.pop(); // o "bum" da TV de tubo ao pegar o botão
                }
                return mode != M_NONE;
            case MotionEvent.ACTION_MOVE:
                notifyActivity();
                if (mode == M_BOB) {
                    float dx = e.getRawX() - downRawX, dy = e.getRawY() - downRawY;
                    if (!dragging && Math.hypot(dx, dy) > slop) {
                        dragging = true;
                        if (open || anim > 0f) setOpen(false, false);
                    }
                    if (dragging) {
                        bobX = clamp(startBobX + dx, 0f, screenW - bobW);
                        bobY = clamp(startBobY + dy, 0f, screenH - bobH);
                        applyWindow(false);
                        invalidate();
                        trailPoint();
                    }
                } else if (mode == M_VOL || mode == M_BRI) {
                    turnKnob(px, py);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (mode == M_BOB) {
                    if (dragging) { savePosition(); finishTrail(); } else setOpen(!open, true);
                } else if (mode >= M_TRANSPORT && mode < M_SPECTRUM && hit(x, y) == mode) {
                    onTransport(mode - M_TRANSPORT);
                } else if (mode >= M_BTN && mode < M_TRANSPORT && hit(x, y) == mode) {
                    onButton(mode - M_BTN);
                } else if (mode == M_DRAWER && hit(x, y) == M_DRAWER) {
                    sounds.beep();
                    drawerOpen = true;
                } else if (mode == M_DRAWER_CLOSE && hit(x, y) == M_DRAWER_CLOSE) {
                    sounds.beep();
                    drawerOpen = false;
                } else if (mode == M_TVLIST && hit(x, y) == M_TVLIST) {
                    sounds.beep();
                    openTvList();
                } else if (mode == M_SPECTRUM && hit(x, y) == M_SPECTRUM) {
                    onSpectrumTap();
                }
                mode = M_NONE;
                dragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                finishTrail();
                mode = M_NONE;
                dragging = false;
                return true;
            default:
                return true;
        }
    }

    /** TV LISTA: abre a TV já na lista de playlists (pastas), sem precisar abrir o app. */
    private void openTvList() {
        if (TvLibrary.folders(sp).isEmpty()) {
            Toast.makeText(ctx, "Adicione uma pasta de vídeos em ▦ → TV de vídeo", Toast.LENGTH_LONG).show();
            return;
        }
        setOpen(false, true);
        try {
            ctx.startForegroundService(new Intent(ctx, TvService.class).setAction(TvService.ACTION_PLAY).putExtra("list", true));
        } catch (RuntimeException e) {
            Toast.makeText(ctx, "Não consegui abrir a TV agora", Toast.LENGTH_SHORT).show();
        }
    }

    /** Toque no analisador quando ele não está funcionando: pede a permissão de áudio ou liga de novo. */
    private void onSpectrumTap() {
        sounds.beep();
        if (music.analyzerState() == 1) {
            setOpen(false, true);
            try {
                ctx.startActivity(new Intent(ctx, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("ask_audio", true));
            } catch (RuntimeException ignored) { /* sem permissão pra abrir o app agora */ }
        } else if (music.analyzerState() == 2) {
            sp.edit().putBoolean("spectrum_on", true).apply();
            music.refreshVisualizer();
        }
    }

    /** Girar pra direita (horário) diminui; pra esquerda aumenta. Uma volta de 270 graus cobre de 0 a 100%. */
    private void turnKnob(float px, float py) {
        boolean isVolume = mode == M_VOL;
        int k = isVolume ? 0 : 1;
        float a = angleOf(px, py, knobX(k), knobY(k));
        float delta = a - lastAngle;
        while (delta > 180f) delta -= 360f;
        while (delta < -180f) delta += 360f;
        lastAngle = a;
        float v = Math.max(0f, Math.min(1f, (isVolume ? volume : brightness) - delta / 270f));
        tickAccum += Math.abs(delta) / 270f;
        if (tickAccum >= 0.045f) {
            tickAccum = 0f;
            sounds.tick(); // "tec" de botão de girar a cada passo
        }
        if (isVolume) { volume = v; applyVolume(v); } else { brightness = v; applyBrightness(v); }
        invalidate();
    }

    // ---------- desenho ----------
    @Override protected void onDraw(Canvas c) {
        if (anim > 0.001f) {
            float pl = panX - winX, pt = panY - winY;
            float pivotX = clamp(bobX + bobW / 2f - panX, 0f, panW);
            float pivotY = clamp(bobY + bobH / 2f - panY, 0f, panH);
            int save = c.saveLayerAlpha(pl, pt, pl + panW, pt + panH, (int) (255 * Math.min(1f, anim)));
            c.translate(pl, pt);
            c.scale(0.75f + 0.25f * anim, 0.92f + 0.08f * anim, pivotX, pivotY);
            drawPanel(c);
            c.restoreToCount(save);
        }
        drawBob(c);
    }

    private void frameRect(int idx) {
        int left = (idx % variant.cols) * frameW, top = (idx / variant.cols) * frameH;
        src.set(left, top, left + frameW, top + frameH);
    }

    private void drawBob(Canvas c) {
        float lx = bobX - winX, ly = bobY - winY, cx = lx + bobW / 2f, cy = ly + bobH / 2f;
        boolean onRightHalf = bobX + bobW / 2f > screenW / 2f;
        boolean onTop = bobY + bobH / 2f < screenH * 0.35f; // lá em cima ele fica de cabeça pra baixo
        c.save();
        if (onRightHalf != ASSET_FACES_LEFT) c.scale(-1f, 1f, cx, cy); // olha pro lado do centro
        if (onTop) c.scale(1f, -1f, cx, cy);
        if (sheet != null && variant != null) {
            float pos = (SystemClock.uptimeMillis() - t0) / variant.ms;
            int i0 = (int) pos;
            float f = pos - i0;
            int i1 = (i0 + 1) % variant.frames;
            i0 %= variant.frames;
            dst.set(lx, ly, lx + bobW, ly + bobH);
            if (variant.blend && sp.getInt("bob_fluid", 60) >= 50 && !sp.getBoolean("cool_on", false) && f > 0.04f) {
                // mistura o quadro atual com o próximo: o movimento fica macio
                int layer = c.saveLayerAlpha(dst, Math.round(255 * bobAlpha));
                frameRect(i0);
                bobPaint.setAlpha(Math.round(255 * (1f - f)));
                c.drawBitmap(sheet, src, dst, bobPaint);
                frameRect(i1);
                blendPaint.setAlpha(Math.round(255 * f));
                c.drawBitmap(sheet, src, dst, blendPaint);
                c.restoreToCount(layer);
            } else {
                frameRect(i0);
                bobPaint.setAlpha(Math.round(255 * bobAlpha));
                c.drawBitmap(sheet, src, dst, bobPaint);
            }
        } else if (!loading) {
            drawFallbackBob(c, lx, ly);
        }
        c.restore();
    }

    private void drawFallbackBob(Canvas c, float lx, float ly) {
        int a = Math.round(255 * bobAlpha);
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(a, 245, 224, 74));
        r.set(lx, ly, lx + bobW, ly + bobH);
        c.drawRoundRect(r, 8 * d, 8 * d, p);
        p.setColor(Color.argb(a, 255, 255, 255));
        c.drawCircle(lx + bobW * 0.32f, ly + bobH * 0.35f, bobW * 0.12f, p);
        c.drawCircle(lx + bobW * 0.64f, ly + bobH * 0.35f, bobW * 0.12f, p);
        p.setColor(Color.argb(a, 30, 30, 30));
        c.drawCircle(lx + bobW * 0.30f, ly + bobH * 0.35f, bobW * 0.05f, p);
        c.drawCircle(lx + bobW * 0.62f, ly + bobH * 0.35f, bobW * 0.05f, p);
    }

    /** Desenha o painel com o canto em (0, 0): borda preta fina e o miolo de pergaminho. */
    private void drawPanel(Canvas c) {
        float b = BORDER_DP * d;
        // moldura de madeira, com linha escura por fora e fio dourado por dentro
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF8B4A1E);
        p.setShader(woodShader);
        r.set(0, 0, panW, panH);
        c.drawRoundRect(r, 14 * d, 14 * d, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.2f * d);
        p.setColor(0xFF1B0F06);
        r.set(0.6f * d, 0.6f * d, panW - 0.6f * d, panH - 0.6f * d);
        c.drawRoundRect(r, 13.4f * d, 13.4f * d, p);
        // miolo de pergaminho
        p.setStyle(Paint.Style.FILL);
        r.set(b, b, panW - b, panH - b);
        if (plate != null && paperBitmap != null) {
            float sc = Math.max(r.width() / paperBitmap.getWidth(), r.height() / paperBitmap.getHeight());
            matrix.setScale(sc, sc);
            matrix.postTranslate(r.left + (r.width() - paperBitmap.getWidth() * sc) / 2f, r.top + (r.height() - paperBitmap.getHeight() * sc) / 2f);
            plate.setLocalMatrix(matrix);
            p.setColor(0xFFFFFFFF);
            p.setFilterBitmap(true); // sem isso a textura aparece quadriculada
            p.setShader(plate);
            c.drawRoundRect(r, 10 * d, 10 * d, p);
            p.setShader(null);
            p.setColor(0x1F000000);
            c.drawRoundRect(r, 10 * d, 10 * d, p);
        } else {
            p.setColor(0xFF3A2A1A);
            c.drawRoundRect(r, 10 * d, 10 * d, p);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.4f * d);
        p.setColor(0xFFD9A441);
        r.set(b - 0.7f * d, b - 0.7f * d, panW - b + 0.7f * d, panH - b + 0.7f * d);
        c.drawRoundRect(r, 10.7f * d, 10.7f * d, p);

        drawSky(c);
        drawStripRect(c, stripA, 139, 50, 226, 226);   // faixa dos botões (gaveta)
        drawStripRect(c, stripC, 12, 232, 220, 272);   // analisador de espectro
        drawStripRect(c, stripB, 12, 278, 220, 342);   // player
        drawSpectrum(c);
        drawKnob(c, 0, volume, "VOL", 0xFF4F6BFF);
        drawKnob(c, 1, brightness, "BRI", 0xFFFFA833);
        drawClock(c);
        if (drawerOpen) { // gaveta aberta: os 6 botões
            for (int i = 0; i < BUTTONS; i++) drawButton(c, i);
            drawDrawerHandle(c);
        } else {          // gaveta fechada: só a gaveta e a lista da TV
            drawBigButton(c, 182 * d, 102 * d, 0xFFE8A33D, "GAVETA", 0);
            drawBigButton(c, 182 * d, 180 * d, 0xFF35C9E0, "TV LISTA", 1);
        }
        drawMusic(c);
        drawSignature(c);
    }

    // ---------- céu: sol de dia, lua de noite, nuvens e chuva conforme o clima ----------
    private static int lerpColor(int a, int b, float t) {
        return Color.argb(255,
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private static int skyColor(float hour) {
        for (int i = 0; i < SKY_HOURS.length - 1; i++) {
            if (hour <= SKY_HOURS[i + 1]) {
                float t = (hour - SKY_HOURS[i]) / (SKY_HOURS[i + 1] - SKY_HOURS[i]);
                return lerpColor(SKY_COLORS[i], SKY_COLORS[i + 1], Math.max(0f, Math.min(1f, t)));
            }
        }
        return SKY_COLORS[SKY_COLORS.length - 1];
    }

    /** 0 = dia claro, 1 = noite fechada (pras estrelas). */
    private static float nightAmount(float hour) {
        if (hour < 5f || hour >= 21f) return 1f;
        if (hour >= 19f) return (hour - 19f) / 2f;
        if (hour < 7f) return (7f - hour) / 2f;
        return 0f;
    }

    private void drawSky(Canvas c) {
        float b = BORDER_DP * d, left = b, top = b, right = panW - b, bottom = 41 * d;
        Calendar now = Calendar.getInstance();
        float hour = now.get(Calendar.HOUR_OF_DAY) + now.get(Calendar.MINUTE) / 60f;
        float t = (SystemClock.uptimeMillis() - t0) / 1000f;
        int sky = skyColor(hour);
        int kind = Weather.kind(sp);
        if (kind >= Weather.RAIN) sky = lerpColor(sky, 0xFF4A5260, 0.55f); // céu fechado quando chove

        c.save();
        r.set(left, top, right, bottom + 12 * d); // topo arredondado como o miolo; a base reta vem do clipRect
        path.reset();
        path.addRoundRect(r, 11 * d, 11 * d, Path.Direction.CW);
        c.clipPath(path);
        c.clipRect(left, top, right, bottom);

        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        float night = nightAmount(hour);
        if (skySheet != null && skyVariant != null) {
            // gif do céu preenchendo a faixa (corta o que sobra, sem esticar); o céu parado anda bem devagar de lado
            int idx = skyVariant.frames > 1 ? (int) (((SystemClock.uptimeMillis() - t0) / skyVariant.ms) % skyVariant.frames) : 0;
            int sl = (idx % skyVariant.cols) * skyW, st = (idx / skyVariant.cols) * skyH;
            float bandW = right - left, bandH = bottom - top;
            float sc = Math.max(bandW / skyW, bandH / skyH);
            float cropW = Math.min(skyW, bandW / sc), cropH = Math.min(skyH, bandH / sc);
            float pan = skyVariant.frames == 1 ? (float) Math.sin(t * 0.25f) * 0.5f : 0f;
            int cl = Math.round(sl + (skyW - cropW) / 2f * (1f + pan));
            int ct = Math.round(st + (skyH - cropH) / 2f);
            src.set(cl, ct, Math.round(cl + cropW), Math.round(ct + cropH));
            dst.set(left, top, right, bottom);
            bobPaint.setAlpha(255);
            c.drawBitmap(skySheet, src, dst, bobPaint);
            if (night < 0.5f) { // capa escura sobre o céu do dia (ajuste em ▦; padrão 22%)
                p.setColor(Math.round(Math.max(0, Math.min(60, sp.getInt("sky_dark", 22))) * 2.55f) << 24);
                c.drawRect(left, top, right, bottom, p);
                p.setColor(0xFFFFFFFF);
            }
            if (kind >= Weather.RAIN) { // chuva escurece o céu
                p.setColor(0x80303642);
                c.drawRect(left, top, right, bottom, p);
            }
        } else {
            if (skyShader == null || skyShaderColor != sky) {
                skyShaderColor = sky;
                skyShader = new LinearGradient(0, top, 0, bottom, lerpColor(sky, 0xFF000000, 0.35f), sky, Shader.TileMode.CLAMP);
            }
            p.setShader(skyShader);
            c.drawRect(left, top, right, bottom, p);
            p.setShader(null);
        }

        if (skySheet == null && night > 0.01f && kind < Weather.RAIN) { // estrelas (só enquanto o gif não carrega)
            p.setColor(Color.argb(Math.round(220 * night), 255, 255, 255));
            for (int i = 0; i < STAR_X.length; i++)
                c.drawCircle(left + (right - left) * STAR_X[i], top + (bottom - top) * STAR_Y[i], 0.9f * d, p);
        }

        boolean day = hour >= 6f && hour < 18f;
        float arc = day ? (hour - 6f) / 12f : ((hour - 18f + 24f) % 24f) / 12f; // 0 = nascendo, 1 = se pondo
        float x = left + 12 * d + arc * (right - left - 24 * d);
        float y = bottom - 7 * d - (float) Math.sin(Math.PI * arc) * (bottom - top - 18 * d);

        if (day) {
            if (skySheet == null) { // com o gif carregado, o sol é o do gif
            c.save();
            c.translate(x, y);
            p.setColor(0xFFFFFFFF);
            p.setShader(sunGlow);
            c.drawCircle(0, 0, 14 * d, p);
            p.setShader(null);
            p.setColor(0xFFFFD84A);
            c.drawCircle(0, 0, 6.5f * d, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.4f * d);
            p.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 0; i < 8; i++) { // raios girando devagar
                double a = Math.toRadians(t * 16f + i * 45);
                c.drawLine((float) Math.cos(a) * 9 * d, (float) Math.sin(a) * 9 * d,
                        (float) Math.cos(a) * 12 * d, (float) Math.sin(a) * 12 * d, p);
            }
            p.setStyle(Paint.Style.FILL);
            c.restore();
            }
        } else {
            p.setColor(0xFFF4F1DE);
            c.drawCircle(x, y, 6.5f * d, p);
            p.setColor(sky);                     // "morde" a lua pra virar crescente
            c.drawCircle(x + 3.2f * d, y - 2f * d, 5.6f * d, p);
        }

        drawWeather(c, kind, night > 0.5f, left, top, right, bottom, t);

        p.setStyle(Paint.Style.FILL);
        p.setColor(0x33000000);                  // linha do horizonte
        c.drawRect(left, bottom - 2.5f * d, right, bottom, p);

        float battery = Heat.batteryTemp(ctx);   // temperatura do celular (bateria), no canto do céu
        if (!Float.isNaN(battery)) {
            String label = "CEL " + Math.round(battery) + "\u00B0C";
            p.setTextSize(8.5f * d);
            p.setFakeBoldText(true);
            float half = p.measureText(label) / 2f;
            p.setFakeBoldText(false);
            drawIridescent(c, label, left + 5 * d + half, top + 11 * d, 8.5f * d);
        }

        c.restore();
    }

    private void drawCloud(Canvas c, float cx, float cy, float s) {
        c.drawCircle(cx, cy, s * 0.6f, p);
        c.drawCircle(cx + s * 0.7f, cy + s * 0.1f, s * 0.5f, p);
        c.drawCircle(cx - s * 0.7f, cy + s * 0.15f, s * 0.45f, p);
        r.set(cx - s * 1.1f, cy + s * 0.1f, cx + s * 1.2f, cy + s * 0.65f);
        c.drawRoundRect(r, s * 0.3f, s * 0.3f, p);
    }

    private void drawWeather(Canvas c, int kind, boolean night, float left, float top, float right, float bottom, float t) {
        if (kind == Weather.CLEAR) return;
        int cloud = kind >= Weather.RAIN ? 0xFF58606C : night ? 0xFF8A93B0 : 0xFFE8EEF5;
        p.setStyle(Paint.Style.FILL);
        p.setColor(cloud);
        float span = right - left + 60 * d;
        for (int i = 0; i < 3; i++) { // nuvens passando devagar
            float cx = left + ((t * (5 + 3 * i) * d + i * 75 * d) % span) - 30 * d;
            drawCloud(c, cx, top + (9 + (i * 7) % 11) * d, (9 + 2 * i) * d);
        }
        if (kind >= Weather.RAIN) { // chuva
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.1f * d);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(0xCCA8D8FF);
            float h = bottom - top;
            for (int i = 0; i < 16; i++) {
                float x = left + (right - left) * ((i * 0.137f) % 1f);
                float y = top + ((t * 70 * d + i * 11 * d) % h);
                c.drawLine(x, y, x - 2 * d, y + 5 * d, p);
            }
            p.setStyle(Paint.Style.FILL);
        }
        if (kind == Weather.STORM && ((int) (t * 2)) % 7 == 0 && (t * 2) % 1f < 0.25f) { // relâmpago
            p.setColor(0x66FFFFFF);
            c.drawRect(left, top, right, bottom, p);
        }
    }

    // ---------- hora em 12h, entre os dois botões de girar ----------
    private void drawClock(Canvas c) {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR);
        if (h == 0) h = 12;
        int m = now.get(Calendar.MINUTE);
        String time = h + ":" + (m < 10 ? "0" : "") + m;
        String period = now.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM";
        float cx = 111 * d;
        if (Weather.hasData(sp)) drawIridescent(c, Math.round(Weather.temperature(sp)) + "\u00B0C", cx, 108 * d, 12 * d);
        drawIridescent(c, time, cx, 128 * d, 15 * d);
        drawIridescent(c, period, cx, 142 * d, 9.5f * d);
    }

    /** Texto furta-cor: as cores deslizam, com contorno escuro pra dar pra ler em cima do papel. */
    private void drawIridescent(Canvas c, String text, float x, float y, float size) {
        float t = (SystemClock.uptimeMillis() - t0) / 1000f;
        iridescentMatrix.setTranslate((t * 38f * d) % (90f * d), 0); // volta ao zero: sem isso a cor some depois de horas
        iridescent.setLocalMatrix(iridescentMatrix);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(size);
        p.setFakeBoldText(true);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(size * 0.24f);
        p.setColor(0xFF1B1B1B);
        c.drawText(text, x, y, p);
        p.setStyle(Paint.Style.FILL);
        p.setShader(iridescent);
        c.drawText(text, x, y, p);
        p.setShader(null);
        p.setFakeBoldText(false);
    }

    // ---------- peças com textura ----------
    /** Tampa de botão: metal escuro escovado, serrilhado na borda e um disco no meio. */
    private Bitmap makeCap(int px) {
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
        float rad = px / 2f;
        q.setShader(new SweepGradient(rad, rad, new int[]{0xFF2A2A2A, 0xFF707070, 0xFF1E1E1E, 0xFF7C7C7C, 0xFF2A2A2A}, null));
        cv.drawCircle(rad, rad, rad, q); // brilho em "leque", como metal escovado
        q.setShader(null);
        q.setStyle(Paint.Style.STROKE);
        q.setStrokeWidth(Math.max(1f, px * 0.016f));
        for (int i = 0; i < 44; i++) { // serrilhado
            cv.save();
            cv.rotate(i * 360f / 44f, rad, rad);
            q.setColor(i % 2 == 0 ? 0x66FFFFFF : 0x88000000);
            cv.drawLine(rad, rad * 0.04f, rad, rad * 0.22f, q);
            cv.restore();
        }
        q.setStyle(Paint.Style.FILL);
        q.setShader(new RadialGradient(rad * 0.78f, rad * 0.68f, rad * 0.75f, 0xFF7E7E7E, 0xFF202020, Shader.TileMode.CLAMP));
        cv.drawCircle(rad, rad, rad * 0.64f, q); // disco do meio
        q.setShader(null);
        q.setStyle(Paint.Style.STROKE);
        q.setStrokeWidth(Math.max(1f, px * 0.02f));
        q.setColor(0x88000000);
        cv.drawCircle(rad, rad, rad * 0.64f, q);
        q.setColor(0x44FFFFFF);
        cv.drawCircle(rad, rad, rad - q.getStrokeWidth(), q);
        return bmp;
    }

    /** Aro preto do dial, com as marcas de 0 a 100 em volta (igual o seletor de canais de TV antiga). */
    private Bitmap makeDial(int px) {
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
        float rad = px / 2f;
        q.setColor(0xFF111111);
        cv.drawCircle(rad, rad, rad, q);
        q.setStyle(Paint.Style.STROKE);
        q.setStrokeWidth(px * 0.02f);
        q.setColor(0xFF3C3C3C);
        cv.drawCircle(rad, rad, rad - q.getStrokeWidth() / 2f, q);
        q.setStrokeCap(Paint.Cap.ROUND);
        q.setColor(0xFFE8DCC0);
        for (int k = 0; k <= 20; k++) { // 21 marcas em 270 graus; as de 5 em 5 são maiores
            boolean major = k % 5 == 0;
            q.setStrokeWidth(px * (major ? 0.028f : 0.018f));
            cv.save();
            cv.rotate(-135f + 13.5f * k, rad, rad);
            cv.drawLine(rad, rad - rad * 0.93f, rad, rad - rad * (major ? 0.74f : 0.82f), q);
            cv.restore();
        }
        return bmp;
    }

    /** Analisador de espectro: 31 barras de LED verde com o pico em vermelho, mexendo com a música. */
    private void drawSpectrum(Canvas c) {
        music.snapshot(specLevel, specPeak);
        float left = 18 * d, width = panW - 36 * d, top = 236 * d, bottom = 268 * d;
        float slot = width / MusicPlayer.BANDS, barW = slot * 0.68f;
        int segs = 12;
        float segH = (bottom - top) / segs, gap = segH * 0.3f;
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < MusicPlayer.BANDS; i++) {
            float x = left + i * slot + (slot - barW) / 2f;
            p.setColor(0x1A2EAD5B); // fundo apagado da barra
            r.set(x, top, x + barW, bottom);
            c.drawRect(r, p);
            int lit = Math.round(specLevel[i] * segs);
            for (int seg = 0; seg < lit; seg++) {
                float y1 = bottom - seg * segH;
                p.setColor(seg >= segs - 2 ? 0xFFE6E24A : 0xFF2EAD5B);
                r.set(x, y1 - segH + gap, x + barW, y1);
                c.drawRect(r, p);
            }
            int cap = Math.min(segs - 1, Math.round(specPeak[i] * segs));
            if (cap > lit) {
                float y1 = bottom - cap * segH;
                p.setColor(0xFFD33A3A);
                r.set(x, y1 - segH + gap, x + barW, y1);
                c.drawRect(r, p);
            }
        }
        int state = music.analyzerState();
        if (state != 0) drawIridescent(c, state == 1 ? "analisador: libere o áudio em ▦" : "analisador desligado (▦)", panW / 2f, 254 * d, 8f * d);
    }

    /** Faixa preta embutida (fundo dos botões e do player). */
    private void drawStripRect(Canvas c, Shader shader, float l, float t, float rt, float bt) {
        r.set(l * d, t * d, rt * d, bt * d);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        p.setShader(shader);
        c.drawRoundRect(r, 9 * d, 9 * d, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.2f * d);
        p.setColor(0x33FFFFFF);
        c.drawRoundRect(r, 9 * d, 9 * d, p);
    }

    private void drawKnob(Canvas c, int k, float v, String label, int color) {
        float cx = knobX(k), cy = knobY(k);
        float ringR = 34 * d, arcR = 36.5f * d, capR = 21 * d, theta = 135f - 270f * v;
        c.save();
        c.translate(cx, cy);
        dst.set(-ringR, -ringR, ringR, ringR);
        c.drawBitmap(dialRing, null, dst, uiPaint); // aro preto com as marcas
        r.set(-arcR, -arcR, arcR, arcR);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3.2f * d);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(0x66000000);
        c.drawArc(r, 135f, 270f, false, p);
        p.setColor(color);
        if (v > 0.001f) c.drawArc(r, 45f - 270f * v, 270f * v, false, p);
        c.rotate(theta); // a tampa serrilhada gira junto com o ponteiro
        dst.set(-capR, -capR, capR, capR);
        c.drawBitmap(capBig, null, dst, uiPaint);
        p.setStrokeWidth(3.2f * d);
        p.setColor(color);
        c.drawLine(0, -capR * 0.20f, 0, -capR * 0.90f, p);
        c.restore();
        // nome embaixo do botão
        drawIridescent(c, label + " " + Math.round(v * 100) + "%", cx, cy + 37 * d + 12 * d, 11 * d);
    }

    /** Botão grande da faixa (gaveta e TV LISTA). */
    private void drawBigButton(Canvas c, float cx, float cy, int ring, String label, int kind) {
        float ringR = 21 * d, capR = 17 * d;
        c.save();
        c.translate(cx, cy);
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF050505);
        c.drawCircle(0, 0, ringR, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.8f * d);
        p.setColor(ring);
        c.drawCircle(0, 0, ringR - 1.4f * d, p);
        dst.set(-capR, -capR, capR, capR);
        c.drawBitmap(capBig, null, dst, uiPaint);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(2.6f * d);
        p.setColor(0xFFEFE6CC);
        if (kind == 0) { // gaveta: caixa com puxador
            r.set(-9 * d, -7 * d, 9 * d, 7 * d);
            c.drawRoundRect(r, 2 * d, 2 * d, p);
            c.drawLine(-9 * d, -1 * d, 9 * d, -1 * d, p);
            c.drawLine(-3 * d, 3.5f * d, 3 * d, 3.5f * d, p);
        } else {         // TV: tela com antenas
            r.set(-9 * d, -5 * d, 9 * d, 8 * d);
            c.drawRoundRect(r, 3 * d, 3 * d, p);
            c.drawLine(0, -5 * d, -5 * d, -10 * d, p);
            c.drawLine(0, -5 * d, 5 * d, -10 * d, p);
        }
        c.restore();
        drawIridescent(c, label, cx, cy + ringR + 12 * d, 9.5f * d);
    }

    /** Setinha no alto da faixa, pra fechar a gaveta. */
    private void drawDrawerHandle(Canvas c) {
        float cx = 182 * d, cy = 62 * d;
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF050505);
        c.drawCircle(cx, cy, 12 * d, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f * d);
        p.setColor(0xFFE8A33D);
        c.drawCircle(cx, cy, 11 * d, p);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(2.4f * d);
        p.setColor(0xFFEFE6CC);
        c.drawLine(cx - 5 * d, cy + 2 * d, cx, cy - 3 * d, p);
        c.drawLine(cx, cy - 3 * d, cx + 5 * d, cy + 2 * d, p);
    }

    private void drawButton(Canvas c, int i) {
        float cx = buttonX(i), cy = buttonY(i);
        boolean on = (i == 3 && sp.getBoolean("perf_on", false)) || (i == 5 && sp.getBoolean("cool_on", false));
        int ring = (i == 3 || i == 5) && !on ? 0xFF6A6A6A : BUTTON_RING[i];
        int ink = on ? ring : 0xFFEFE6CC;
        float ringR = BUTTON_R * d, capR = 12 * d;
        c.save();
        c.translate(cx, cy);
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF050505);
        c.drawCircle(0, 0, ringR, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.4f * d);
        p.setColor(ring);
        c.drawCircle(0, 0, ringR - 1.2f * d, p);
        dst.set(-capR, -capR, capR, capR);
        c.drawBitmap(capSmall, null, dst, uiPaint);
        c.scale(0.7f, 0.7f); // o desenho dos ícones foi feito pra um botão maior
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(2.6f * d);
        p.setColor(ink);
        switch (i) {
            case 0: // captura: moldura com ponto
                r.set(-9.5f * d, -7 * d, 9.5f * d, 7 * d);
                c.drawRoundRect(r, 3 * d, 3 * d, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(0, 0, 2.8f * d, p);
                break;
            case 1: // apagar a tela: símbolo de ligar
                r.set(-8 * d, -8 * d, 8 * d, 8 * d);
                c.drawArc(r, -55f, 290f, false, p);
                c.drawLine(0, -10.5f * d, 0, -2 * d, p);
                break;
            case 2: // RAM: pastilha com pinos
                r.set(-7.5f * d, -5 * d, 7.5f * d, 5 * d);
                c.drawRoundRect(r, 2 * d, 2 * d, p);
                for (int k = -1; k <= 1; k++) {
                    c.drawLine(k * 5 * d, -5 * d, k * 5 * d, -8.5f * d, p);
                    c.drawLine(k * 5 * d, 5 * d, k * 5 * d, 8.5f * d, p);
                }
                break;
            case 3: // desempenho: raio
                path.reset();
                path.moveTo(3 * d, -10.5f * d);
                path.lineTo(-6 * d, 2 * d);
                path.lineTo(-0.5f * d, 2 * d);
                path.lineTo(-3 * d, 10.5f * d);
                path.lineTo(6 * d, -2 * d);
                path.lineTo(0.5f * d, -2 * d);
                path.close();
                p.setStyle(Paint.Style.FILL);
                c.drawPath(path, p);
                break;
            case 4: // parar todos os apps: quadrado com um X
                r.set(-7 * d, -7 * d, 7 * d, 7 * d);
                c.drawRoundRect(r, 2 * d, 2 * d, p);
                c.drawLine(-3.5f * d, -3.5f * d, 3.5f * d, 3.5f * d, p);
                c.drawLine(3.5f * d, -3.5f * d, -3.5f * d, 3.5f * d, p);
                break;
            default: // modo gelado: floco de neve
                for (int k = 0; k < 3; k++) {
                    c.save();
                    c.rotate(k * 60f);
                    c.drawLine(0, -8 * d, 0, 8 * d, p);
                    c.drawLine(-2.5f * d, -6 * d, 0, -4 * d, p);
                    c.drawLine(2.5f * d, -6 * d, 0, -4 * d, p);
                    c.drawLine(-2.5f * d, 6 * d, 0, 4 * d, p);
                    c.drawLine(2.5f * d, 6 * d, 0, 4 * d, p);
                    c.restore();
                }
                break;
        }
        c.restore();
        // nome embaixo do botão, maior
        drawIridescent(c, BUTTON_LABEL[i], cx, cy + ringR + 11 * d, 8.5f * d);
    }

    /** Nome da música e botões de anterior, tocar/pausar e próxima. */
    private void drawMusic(Canvas c) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(10 * d);
        p.setFakeBoldText(true);
        String title = "\u266A " + music.title();
        float maxW = panW - 44 * d;
        int fit = p.breakText(title, true, maxW, null);
        if (fit < title.length()) title = title.substring(0, Math.max(1, fit - 1)) + "\u2026";
        p.setFakeBoldText(false);
        drawIridescent(c, title, panW / 2f, 296 * d, 10.5f * d);

        boolean playing = music.playing();
        for (int i = 0; i < 3; i++) {
            float ringR = TRANSPORT_R * d, capR = 13 * d;
            c.save();
            c.translate(transportX(i), transportY());
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF050505);
            c.drawCircle(0, 0, ringR, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.4f * d);
            p.setColor(i == 1 && playing ? 0xFF2EAD5B : 0xFF6A6A6A);
            c.drawCircle(0, 0, ringR - 1.2f * d, p);
            dst.set(-capR, -capR, capR, capR);
            c.drawBitmap(capTransport, null, dst, uiPaint);

            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFEFE6CC);
            path.reset();
            if (i == 1 && playing) { // pausar
                r.set(-5 * d, -5.5f * d, -1.5f * d, 5.5f * d);
                c.drawRect(r, p);
                r.set(1.5f * d, -5.5f * d, 5 * d, 5.5f * d);
                c.drawRect(r, p);
            } else if (i == 1) {     // tocar
                path.moveTo(-3.5f * d, -6 * d);
                path.lineTo(6 * d, 0);
                path.lineTo(-3.5f * d, 6 * d);
                path.close();
                c.drawPath(path, p);
            } else {
                // anterior |◀ (barra na esquerda, ponta pra esquerda) e próxima ▶| (espelhada)
                float sgn = i == 0 ? 1f : -1f;
                path.moveTo(sgn * 5 * d, -5 * d);
                path.lineTo(-sgn * 3.5f * d, 0);
                path.lineTo(sgn * 5 * d, 5 * d);
                path.close();
                c.drawPath(path, p);
                float bar = -sgn * 6 * d;
                r.set(bar - 1.1f * d, -5 * d, bar + 1.1f * d, 5 * d);
                c.drawRect(r, p);
            }
            c.restore();
        }
    }

    /** "By - Gilberto" em RGB, com contorno preto pra aparecer em cima do papel. */
    private void drawSignature(Canvas c) {
        float t = (SystemClock.uptimeMillis() - t0) / 1000f;
        rainbowMatrix.setTranslate((t * 70f * d) % (RAINBOW_LEN * d), 0);
        rainbow.setLocalMatrix(rainbowMatrix);
        float x = panW / 2f, y = panH - 9 * d;
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(12 * d);
        p.setFakeBoldText(true);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3 * d);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(0xFF0B0B0B);
        c.drawText("By - Gilberto", x, y, p);
        p.setStyle(Paint.Style.FILL);
        p.setShader(rainbow);
        c.drawText("By - Gilberto", x, y, p);
        p.setShader(null);
        p.setFakeBoldText(false);
    }
}
