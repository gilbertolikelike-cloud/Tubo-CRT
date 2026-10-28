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
    private static final int M_BTN = 4;       // 4..7  = FOTO, APAGAR, RAM, MAX
    private static final int M_TRANSPORT = 8; // 8..10 = anterior, tocar/pausar, próxima
    private static final String[] BUTTON_LABEL = {"FOTO", "APAGAR", "RAM", "MAX"};
    private static final int[] BUTTON_RING = {0xFF2EAD5B, 0xFFD94040, 0xFF4F6BFF, 0xFFFFC107};
    private static final float RAINBOW_LEN = 120f;
    private static final long AUTO_CLOSE_MS = 10000;
    private static final float PANEL_W_DP = 232f, PANEL_H_DP = 304f, BORDER_DP = 5f;
    private static final float KNOB_R = 32f, BUTTON_R = 20f, TRANSPORT_R = 17f; // dp

    // cor do céu ao longo do dia
    private static final float[] SKY_HOURS = {0f, 5f, 6.5f, 8f, 16f, 17.5f, 19f, 21f, 24f};
    private static final int[] SKY_COLORS = {0xFF0B1030, 0xFF1B2350, 0xFFFF9966, 0xFF7EC8F8, 0xFF7EC8F8,
            0xFFFF8A5C, 0xFF2B2A5E, 0xFF0B1030, 0xFF0B1030};
    private static final float[] STAR_X = {0.10f, 0.22f, 0.35f, 0.50f, 0.63f, 0.80f, 0.92f};
    private static final float[] STAR_Y = {0.30f, 0.65f, 0.25f, 0.70f, 0.35f, 0.60f, 0.28f};

    /** Uma das folhas de quadros do Bob (descrita em bob_meta.json). */
    private static final class Variant {
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
    private final float d, panW, panH;      // densidade e tamanho do painel em px
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
    private final Shader rainbow, iridescent, knobFace, buttonFace, transportFace, sunGlow;
    private final Map<String, Variant> variants = new HashMap<>();
    private final Runnable autoClose = () -> setOpen(false, true);
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!added) return;
            ensureVariant();
            ensureSky();
            invalidate();
            handler.postDelayed(this, tickInterval());
        }
    };

    private Bitmap sheet, paperBitmap;
    private BitmapShader plate;
    private Variant variant, skyVariant;
    private Bitmap skySheet;
    private String skyKey = "";
    private boolean skyLoading;
    private int skyW, skyH;
    private String currentVariant = "";
    private boolean loading;
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
        d = ctx.getResources().getDisplayMetrics().density;
        panW = PANEL_W_DP * d;
        panH = PANEL_H_DP * d;
        slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        audio = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        music = MusicPlayer.get(ctx);
        blendPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD)); // soma o segundo quadro por cima do primeiro
        int[] colors = new int[7];
        for (int i = 0; i < colors.length; i++) colors[i] = Color.HSVToColor(new float[]{i * 60f % 360f, 1f, 1f});
        rainbow = new LinearGradient(0, 0, RAINBOW_LEN * d, 0, colors, null, Shader.TileMode.REPEAT);
        // furta-cor: ciano, violeta, rosa, dourado e verde-menta, deslizando como numa bolha de sabão
        iridescent = new LinearGradient(0, 0, 90 * d, 0, new int[]{0xFF00E5FF, 0xFF7C4DFF, 0xFFFF4DD2, 0xFFFFD54F, 0xFF69F0AE, 0xFF00E5FF},
                null, Shader.TileMode.REPEAT);
        // gradientes criados uma vez só, desenhados com o canvas deslocado (evita lixo a 60 quadros por segundo)
        knobFace = new RadialGradient(-9 * d, -11 * d, KNOB_R * 1.5f * d, 0xFFF6F6F6, 0xFF7C7C7C, Shader.TileMode.CLAMP);
        buttonFace = new RadialGradient(-6 * d, -6 * d, 30 * d, 0xFFF5F5F5, 0xFF9C9C9C, Shader.TileMode.CLAMP);
        transportFace = new RadialGradient(-5 * d, -5 * d, 24 * d, 0xFFF5F5F5, 0xFF9C9C9C, Shader.TileMode.CLAMP);
        sunGlow = new RadialGradient(0, 0, 14 * d, 0x99FFE27A, 0x00FFE27A, Shader.TileMode.CLAMP);
        loadMeta();
        loadPaper();
    }

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
     * Modo desempenho: Super Saiyajin. Ouvindo música: Bob na discoteca.
     * Senão, de noite na vida real: a espada; de dia: o Bob com sombra.
     */
    private String wantedVariant() {
        if (sp.getBoolean("perf_on", false)) return "saiyan";
        if (music.playing()) return "disco";
        return isNight() ? "sword" : "day";
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
        int f = Math.max(0, Math.min(100, sp.getInt("bob_fluid", 60)));
        return 160 - 144L * f / 100;
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
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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
        if (animator != null) animator.cancel();
        try { wm.removeView(this); } catch (RuntimeException ignored) { /* janela já removida */ }
        if (sheet != null) { sheet.recycle(); sheet = null; }
        if (skySheet != null) { skySheet.recycle(); skySheet = null; }
        if (paperBitmap != null) { paperBitmap.recycle(); paperBitmap = null; plate = null; }
    }

    /** Tamanho, transparência ou Bob mudaram. */
    public void refreshLook() {
        if (!added) return;
        setOpen(false, false);
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
        bobH = Math.min((40 + sp.getInt("bob_size", 40)) * d * scale, screenH * 0.6f); // 0% = 40 dp, 100% = 140 dp
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
        switch (i) {
            case 0: global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT); break;
            case 1: global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN); break;
            case 2:
                Sfx.ramClean(ctx);
                Toast.makeText(ctx, "Limpando a RAM…", Toast.LENGTH_SHORT).show();
                Ram.clean(ctx, (freed, avail) -> Toast.makeText(ctx,
                        "RAM livre: " + avail + " MB (+" + freed + " MB)", Toast.LENGTH_LONG).show());
                break;
            default:
                sp.edit().putBoolean("perf_on", !sp.getBoolean("perf_on", false)).apply(); // o Bob vira saiyajin no próximo quadro
                invalidate();
                break;
        }
    }

    private void onTransport(int i) {
        if (music.count() == 0) {
            Toast.makeText(ctx, "Escolha a pasta de músicas no app (botão ▦ do pilar)", Toast.LENGTH_LONG).show();
            return;
        }
        if (i == 0) music.previous(); else if (i == 1) music.toggle(); else music.next();
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
    private float knobX(int i) { return (i == 0 ? 56 : PANEL_W_DP - 56) * d; }
    private float knobY() { return 86 * d; }
    private float buttonX(int i) { return (40 + 53 * i) * d; }
    private float buttonY() { return 184 * d; }
    private float transportX(int i) { return (PANEL_W_DP / 2f + (i - 1) * 52) * d; }
    private float transportY() { return 266 * d; }

    private int hit(float x, float y) {
        float bl = bobX - winX, bt = bobY - winY;
        if (x >= bl && x <= bl + bobW && y >= bt && y <= bt + bobH) return M_BOB;
        if (anim < 0.5f) return M_NONE;
        float px = x - (panX - winX), py = y - (panY - winY);
        for (int i = 0; i < 2; i++)
            if (Math.hypot(px - knobX(i), py - knobY()) < 40 * d) return i == 0 ? M_VOL : M_BRI;
        for (int i = 0; i < 4; i++)
            if (Math.hypot(px - buttonX(i), py - buttonY()) < 26 * d) return M_BTN + i;
        for (int i = 0; i < 3; i++)
            if (Math.hypot(px - transportX(i), py - transportY()) < 23 * d) return M_TRANSPORT + i;
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
            case MotionEvent.ACTION_DOWN:
                mode = hit(x, y);
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                startBobX = bobX;
                startBobY = bobY;
                dragging = false;
                if (mode == M_VOL) lastAngle = angleOf(px, py, knobX(0), knobY());
                if (mode == M_BRI) lastAngle = angleOf(px, py, knobX(1), knobY());
                if (open) {
                    handler.removeCallbacks(autoClose);
                    handler.postDelayed(autoClose, AUTO_CLOSE_MS);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
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
                    }
                } else if (mode == M_VOL || mode == M_BRI) {
                    turnKnob(px, py);
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (mode == M_BOB) {
                    if (dragging) savePosition(); else setOpen(!open, true);
                } else if (mode >= M_TRANSPORT && hit(x, y) == mode) {
                    onTransport(mode - M_TRANSPORT);
                } else if (mode >= M_BTN && mode < M_TRANSPORT && hit(x, y) == mode) {
                    onButton(mode - M_BTN);
                }
                mode = M_NONE;
                dragging = false;
                return true;

            case MotionEvent.ACTION_CANCEL:
                mode = M_NONE;
                dragging = false;
                return true;

            default:
                return true;
        }
    }

    /** Girar pra direita (horário) diminui; pra esquerda aumenta. Uma volta de 270 graus cobre de 0 a 100%. */
    private void turnKnob(float px, float py) {
        boolean isVolume = mode == M_VOL;
        float a = angleOf(px, py, knobX(isVolume ? 0 : 1), knobY());
        float delta = a - lastAngle;
        while (delta > 180f) delta -= 360f;
        while (delta < -180f) delta += 360f;
        lastAngle = a;
        float v = Math.max(0f, Math.min(1f, (isVolume ? volume : brightness) - delta / 270f));
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
            if (sp.getInt("bob_fluid", 60) >= 50 && f > 0.04f) {
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
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF0B0B0B); // borda
        r.set(0, 0, panW, panH);
        c.drawRoundRect(r, 16 * d, 16 * d, p);

        r.set(b, b, panW - b, panH - b); // miolo
        if (plate != null && paperBitmap != null) {
            float sc = Math.max(r.width() / paperBitmap.getWidth(), r.height() / paperBitmap.getHeight());
            matrix.setScale(sc, sc);
            matrix.postTranslate(r.left + (r.width() - paperBitmap.getWidth() * sc) / 2f,
                    r.top + (r.height() - paperBitmap.getHeight() * sc) / 2f);
            plate.setLocalMatrix(matrix);
            p.setShader(plate);
            c.drawRoundRect(r, 11 * d, 11 * d, p);
            p.setShader(null);
            p.setColor(0x1F000000); // um pouco de sombra pro papel não ficar chapado
            c.drawRoundRect(r, 11 * d, 11 * d, p);
            p.setColor(0xFFFFFFFF); // volta o pincel pra opaco: gradiente desenhado depois herda a transparência da cor
        } else {
            p.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xFFCACACA, 0xFF8A8A8A, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, 11 * d, 11 * d, p);
            p.setShader(null);
        }

        drawSky(c);
        drawKnob(c, knobX(0), knobY(), volume, "VOL", 0xFF4F6BFF);
        drawKnob(c, knobX(1), knobY(), brightness, "BRI", 0xFFFFA833);
        drawClock(c);
        for (int i = 0; i < 4; i++) drawButton(c, buttonX(i), buttonY(), i);
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

        if (Weather.hasData(sp)) { // temperatura
            String temp = Math.round(Weather.temperature(sp)) + "\u00B0";
            p.setTextAlign(Paint.Align.LEFT);
            p.setTextSize(11 * d);
            p.setFakeBoldText(true);
            p.setColor(0x99000000);
            c.drawText(temp, left + 6 * d + 0.8f * d, top + 13 * d + 0.8f * d, p);
            p.setColor(0xFFFFFFFF);
            c.drawText(temp, left + 6 * d, top + 13 * d, p);
            p.setFakeBoldText(false);
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
        int h = now.get(Calendar.HOUR); // 0..11
        if (h == 0) h = 12;
        int m = now.get(Calendar.MINUTE);
        String time = h + ":" + (m < 10 ? "0" : "") + m;
        String period = now.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM";
        drawIridescent(c, time, panW / 2f, 130 * d, 15 * d);
        drawIridescent(c, period, panW / 2f, 142 * d, 9 * d);
    }

    /** Texto furta-cor: as cores deslizam, com contorno escuro pra dar pra ler em cima do papel. */
    private void drawIridescent(Canvas c, String text, float x, float y, float size) {
        float t = (SystemClock.uptimeMillis() - t0) / 1000f;
        iridescentMatrix.setTranslate(t * 38f * d, 0);
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

    private void drawKnob(Canvas c, float cx, float cy, float v, String label, int color) {
        float radius = KNOB_R * d, theta = 135f - 270f * v; // theta: graus no sentido horário, 0 = pra cima
        c.save();
        c.translate(cx, cy);
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF1B1B1B);
        c.drawCircle(0, 0, radius + 5 * d, p);

        r.set(-radius - 2.5f * d, -radius - 2.5f * d, radius + 2.5f * d, radius + 2.5f * d);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4 * d);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(0xFF3A3A3A);
        c.drawArc(r, 135f, 270f, false, p);               // trilho inteiro, passando por cima
        p.setColor(color);
        if (v > 0.001f) c.drawArc(r, 45f - 270f * v, 270f * v, false, p);

        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        p.setShader(knobFace);
        c.drawCircle(0, 0, radius - 4 * d, p);
        p.setShader(null);
        p.setColor(0x22000000);
        c.drawCircle(0, 0, radius * 0.55f, p);

        c.rotate(theta);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4 * d);
        p.setColor(color);
        c.drawLine(0, -radius * 0.30f, 0, -radius * 0.78f, p);
        c.restore();

        drawIridescent(c, label + " " + Math.round(v * 100) + "%", cx, cy + radius + 18 * d, 10.5f * d);
    }

    private void drawButton(Canvas c, float cx, float cy, int i) {
        boolean on = i == 3 && sp.getBoolean("perf_on", false);
        int ring = i == 3 && !on ? 0xFF555555 : BUTTON_RING[i];
        float rad = BUTTON_R * d;
        c.save();
        c.translate(cx, cy);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        p.setShader(buttonFace);
        c.drawCircle(0, 0, rad, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3 * d);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(ring);
        c.drawCircle(0, 0, rad, p);

        p.setColor(0xFF2A2A2A);
        p.setStrokeWidth(2.2f * d);
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
            default: // desempenho: raio
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
        }
        c.restore();

        drawIridescent(c, BUTTON_LABEL[i], cx, cy + rad + 11 * d, 8.5f * d);
    }

    /** Nome da música e botões de anterior, tocar/pausar e próxima. */
    private void drawMusic(Canvas c) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(10 * d);
        p.setFakeBoldText(true);
        String title = "\u266A " + music.title();
        float maxW = panW - 40 * d;
        int fit = p.breakText(title, true, maxW, null);
        if (fit < title.length()) title = title.substring(0, Math.max(1, fit - 1)) + "\u2026";
        p.setFakeBoldText(false);
        drawIridescent(c, title, panW / 2f, 238 * d, 10 * d);

        boolean playing = music.playing();
        for (int i = 0; i < 3; i++) {
            float rad = TRANSPORT_R * d;
            c.save();
            c.translate(transportX(i), transportY());
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            p.setShader(transportFace);
            c.drawCircle(0, 0, rad, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.5f * d);
            p.setColor(i == 1 && playing ? 0xFF2EAD5B : 0xFF555555);
            c.drawCircle(0, 0, rad, p);

            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF2A2A2A);
            path.reset();
            if (i == 1 && playing) { // pausar
                r.set(-6 * d, -6.5f * d, -2 * d, 6.5f * d);
                c.drawRect(r, p);
                r.set(2 * d, -6.5f * d, 6 * d, 6.5f * d);
                c.drawRect(r, p);
            } else if (i == 1) {     // tocar
                path.moveTo(-4 * d, -7 * d);
                path.lineTo(7 * d, 0);
                path.lineTo(-4 * d, 7 * d);
                path.close();
                c.drawPath(path, p);
            } else {
                // anterior |◀ (barra na esquerda, ponta pra esquerda) e próxima ▶| (espelhada)
                float s = i == 0 ? 1f : -1f;
                path.moveTo(s * 6 * d, -6 * d);
                path.lineTo(-s * 4 * d, 0);
                path.lineTo(s * 6 * d, 6 * d);
                path.close();
                c.drawPath(path, p);
                float bar = -s * 7 * d;
                r.set(bar - 1.2f * d, -6 * d, bar + 1.2f * d, 6 * d);
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
