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

/**
 * O Bob dançando que fica por cima de tudo. Toque nele: abre o painel com o céu (sol e lua seguem a hora),
 * dois botões de girar (volume e brilho), a hora em 12h, quatro botões (captura, apagar a tela, limpar RAM,
 * modo desempenho) e o player de música da pasta que você escolheu.
 * Arraste o Bob pra qualquer lugar; ele olha pro lado do centro da tela.
 *
 * Botão de girar: girar pra direita (sentido horário) diminui, pra esquerda aumenta.
 * O Bob é desenhado quadro a quadro (bob_sheet.png). A opção "Fluidez do Bob" troca leveza por suavidade.
 */
public class BobPanel extends View {
    /** O Bob do arquivo olha pra esquerda. */
    private static final boolean ASSET_FACES_LEFT = true;
    private static final int SHEET_COLS = 8, SHEET_ROWS = 8, FRAMES = 64, FRAME_MS = 76;

    private static final int M_NONE = 0, M_BOB = 1, M_VOL = 2, M_BRI = 3;
    private static final int M_BTN = 4;       // 4..7  = FOTO, APAGAR, RAM, MAX
    private static final int M_TRANSPORT = 8; // 8..10 = anterior, tocar/pausar, próxima
    private static final String[] BUTTON_LABEL = {"FOTO", "APAGAR", "RAM", "MAX"};
    private static final int[] BUTTON_RING = {0xFF2EAD5B, 0xFFD94040, 0xFF4F6BFF, 0xFFFFC107};
    private static final float RAINBOW_LEN = 120f;
    private static final long AUTO_CLOSE_MS = 10000;
    private static final float PANEL_W_DP = 232f, PANEL_H_DP = 304f, BORDER_DP = 5f;

    // cor do céu ao longo do dia
    private static final float[] SKY_HOURS = {0f, 5f, 6.5f, 8f, 16f, 17.5f, 19f, 21f, 24f};
    private static final int[] SKY_COLORS = {0xFF0B1030, 0xFF1B2350, 0xFFFF9966, 0xFF7EC8F8, 0xFF7EC8F8,
            0xFFFF8A5C, 0xFF2B2A5E, 0xFF0B1030, 0xFF0B1030};
    private static final float[] STAR_X = {0.10f, 0.22f, 0.35f, 0.50f, 0.63f, 0.80f, 0.92f};
    private static final float[] STAR_Y = {0.30f, 0.65f, 0.25f, 0.70f, 0.35f, 0.60f, 0.28f};

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
    private final Matrix rainbowMatrix = new Matrix();
    private final Shader rainbow;
    private final Runnable autoClose = () -> setOpen(false, true);
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!added) return;
            invalidate();
            handler.postDelayed(this, tickInterval());
        }
    };

    private Bitmap sheet;
    private int frameW, frameH;
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
        loadSheet();
    }

    private void loadSheet() {
        try (InputStream in = ctx.getAssets().open("bob_sheet.png")) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            sheet = BitmapFactory.decodeStream(in, null, o);
        } catch (IOException | RuntimeException e) {
            sheet = null; // sem o arquivo, desenha um Bob simples
        }
        if (sheet != null) {
            frameW = sheet.getWidth() / SHEET_COLS;
            frameH = sheet.getHeight() / SHEET_ROWS;
        }
    }

    /** Quanto menor o intervalo, mais suave (e mais pesado). 0% = 160 ms, 100% = 40 ms. */
    private long tickInterval() {
        int f = Math.max(0, Math.min(100, sp.getInt("bob_fluid", 60)));
        return 160 - 120L * f / 100;
    }

    // ---------- ciclo de vida ----------
    public void show() {
        if (added) return;
        refreshScreen();
        fx = sp.getInt("panel_fx", 1000);
        fy = sp.getInt("panel_fy", 250);
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
        handler.postDelayed(tick, tickInterval());
    }

    public void hide() {
        added = false;
        handler.removeCallbacksAndMessages(null);
        if (animator != null) animator.cancel();
        try { wm.removeView(this); } catch (RuntimeException ignored) { /* janela já removida */ }
        if (sheet != null) { sheet.recycle(); sheet = null; }
    }

    /** Tamanho ou transparência do Bob mudaram nos ajustes. */
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
        bobH = (40 + sp.getInt("bob_size", 40)) * d; // 0% = 40 dp, 100% = 140 dp
        bobW = bobH * (sheet != null ? frameW / (float) frameH : 1f);
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
            applyWindow(true);
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
                Toast.makeText(ctx, "Limpando a RAM…", Toast.LENGTH_SHORT).show();
                Ram.clean(ctx, (freed, avail) -> Toast.makeText(ctx,
                        "RAM livre: " + avail + " MB (+" + freed + " MB)", Toast.LENGTH_LONG).show());
                break;
            default:
                sp.edit().putBoolean("perf_on", !sp.getBoolean("perf_on", false)).apply();
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
        int left = (idx % SHEET_COLS) * frameW, top = (idx / SHEET_COLS) * frameH;
        src.set(left, top, left + frameW, top + frameH);
    }

    private void drawBob(Canvas c) {
        float lx = bobX - winX, ly = bobY - winY;
        boolean onRightHalf = bobX + bobW / 2f > screenW / 2f;
        c.save();
        if (onRightHalf != ASSET_FACES_LEFT) c.scale(-1f, 1f, lx + bobW / 2f, ly + bobH / 2f); // olha pro lado do centro
        if (sheet != null) {
            float pos = (SystemClock.uptimeMillis() - t0) / (float) FRAME_MS;
            int i0 = (int) pos;
            float f = pos - i0;
            int i1 = (i0 + 1) % FRAMES;
            i0 %= FRAMES;
            dst.set(lx, ly, lx + bobW, ly + bobH);
            if (sp.getInt("bob_fluid", 60) >= 50 && f > 0.04f) {
                // mistura o quadro atual com o próximo: o movimento fica mais macio
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
        } else {
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

    /** Desenha o painel com o canto em (0, 0). Só a borda é preta; o resto é a placa de metal. */
    private void drawPanel(Canvas c) {
        float b = BORDER_DP * d;
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF0B0B0B); // borda
        r.set(0, 0, panW, panH);
        c.drawRoundRect(r, 16 * d, 16 * d, p);
        r.set(b, b, panW - b, panH - b); // placa de metal
        p.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xFFCACACA, 0xFF8A8A8A, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 11 * d, 11 * d, p);
        p.setShader(null);

        drawSky(c);
        drawKnob(c, knobX(0), knobY(), volume, "VOL", 0xFF4F6BFF);
        drawKnob(c, knobX(1), knobY(), brightness, "BRI", 0xFFFFA833);
        drawClock(c);
        for (int i = 0; i < 4; i++) drawButton(c, buttonX(i), buttonY(), i);
        drawMusic(c);
        drawSignature(c);
    }

    // ---------- céu: sol de dia, lua de noite, andando conforme a hora ----------
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
        int sky = skyColor(hour);

        c.save();
        r.set(left, top, right, bottom + 12 * d); // topo arredondado como a placa; a base reta vem do clipRect
        path.reset();
        path.addRoundRect(r, 11 * d, 11 * d, Path.Direction.CW);
        c.clipPath(path);
        c.clipRect(left, top, right, bottom);

        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, top, 0, bottom, lerpColor(sky, 0xFF000000, 0.35f), sky, Shader.TileMode.CLAMP));
        c.drawRect(left, top, right, bottom, p);
        p.setShader(null);

        float night = nightAmount(hour);
        if (night > 0.01f) { // estrelas
            p.setColor(Color.argb(Math.round(220 * night), 255, 255, 255));
            for (int i = 0; i < STAR_X.length; i++)
                c.drawCircle(left + (right - left) * STAR_X[i], top + (bottom - top) * STAR_Y[i], 0.9f * d, p);
        }

        boolean day = hour >= 6f && hour < 18f;
        float t = day ? (hour - 6f) / 12f : ((hour - 18f + 24f) % 24f) / 12f; // 0 = nascendo, 1 = se pondo
        float x = left + 12 * d + t * (right - left - 24 * d);
        float y = bottom - 7 * d - (float) Math.sin(Math.PI * t) * (bottom - top - 18 * d);

        if (day) {
            p.setShader(new RadialGradient(x, y, 14 * d, 0x99FFE27A, 0x00FFE27A, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, 14 * d, p);
            p.setShader(null);
            p.setColor(0xFFFFD84A);
            c.drawCircle(x, y, 6.5f * d, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.4f * d);
            p.setStrokeCap(Paint.Cap.ROUND);
            float spin = (SystemClock.uptimeMillis() - t0) / 60f; // raios girando devagar
            for (int i = 0; i < 8; i++) {
                double a = Math.toRadians(spin + i * 45);
                c.drawLine(x + (float) Math.cos(a) * 9 * d, y + (float) Math.sin(a) * 9 * d,
                        x + (float) Math.cos(a) * 12 * d, y + (float) Math.sin(a) * 12 * d, p);
            }
            p.setStyle(Paint.Style.FILL);
        } else {
            p.setColor(0xFFF4F1DE);
            c.drawCircle(x, y, 6.5f * d, p);
            p.setColor(sky);                     // "morde" a lua pra virar crescente
            c.drawCircle(x + 3.2f * d, y - 2f * d, 5.6f * d, p);
        }
        p.setColor(0x33000000);                  // linha do horizonte
        c.drawRect(left, bottom - 2.5f * d, right, bottom, p);
        c.restore();
    }

    // ---------- hora em 12h, entre os dois botões de girar ----------
    private void drawClock(Canvas c) {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR); // 0..11
        if (h == 0) h = 12;
        int m = now.get(Calendar.MINUTE);
        String time = h + ":" + (m < 10 ? "0" : "") + m;
        String period = now.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM";
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF1E1E1E);
        p.setTextAlign(Paint.Align.CENTER);
        p.setFakeBoldText(true);
        p.setTextSize(15 * d);
        c.drawText(time, panW / 2f, 130 * d, p);
        p.setTextSize(9 * d);
        c.drawText(period, panW / 2f, 142 * d, p);
        p.setFakeBoldText(false);
    }

    private void drawKnob(Canvas c, float cx, float cy, float v, String label, int color) {
        float radius = 32 * d, theta = 135f - 270f * v; // theta: graus no sentido horário, 0 = pra cima
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF1B1B1B);
        c.drawCircle(cx, cy, radius + 5 * d, p);

        r.set(cx - radius - 2.5f * d, cy - radius - 2.5f * d, cx + radius + 2.5f * d, cy + radius + 2.5f * d);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4 * d);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(0xFF3A3A3A);
        c.drawArc(r, 135f, 270f, false, p);               // trilho inteiro, passando por cima
        p.setColor(color);
        if (v > 0.001f) c.drawArc(r, 45f - 270f * v, 270f * v, false, p);

        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(cx - 9 * d, cy - 11 * d, radius * 1.5f, 0xFFF6F6F6, 0xFF7C7C7C, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, radius - 4 * d, p);
        p.setShader(null);
        p.setColor(0x22000000);
        c.drawCircle(cx, cy, radius * 0.55f, p);

        c.save();
        c.rotate(theta, cx, cy);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4 * d);
        p.setColor(color);
        c.drawLine(cx, cy - radius * 0.30f, cx, cy - radius * 0.78f, p);
        c.restore();

        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF2A2A2A);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(10.5f * d);
        p.setFakeBoldText(true);
        c.drawText(label + " " + Math.round(v * 100) + "%", cx, cy + radius + 18 * d, p);
        p.setFakeBoldText(false);
    }

    private void drawButton(Canvas c, float cx, float cy, int i) {
        boolean on = i == 3 && sp.getBoolean("perf_on", false);
        int ring = i == 3 && !on ? 0xFF555555 : BUTTON_RING[i];
        float rad = 20 * d;
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(cx - 6 * d, cy - 6 * d, 30 * d, 0xFFF5F5F5, 0xFF9C9C9C, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, rad, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3 * d);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(ring);
        c.drawCircle(cx, cy, rad, p);

        p.setColor(0xFF2A2A2A);
        p.setStrokeWidth(2.2f * d);
        switch (i) {
            case 0: // captura: moldura com ponto
                r.set(cx - 9.5f * d, cy - 7 * d, cx + 9.5f * d, cy + 7 * d);
                c.drawRoundRect(r, 3 * d, 3 * d, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, 2.8f * d, p);
                break;
            case 1: // apagar a tela: símbolo de ligar
                r.set(cx - 8 * d, cy - 8 * d, cx + 8 * d, cy + 8 * d);
                c.drawArc(r, -55f, 290f, false, p);
                c.drawLine(cx, cy - 10.5f * d, cx, cy - 2 * d, p);
                break;
            case 2: // RAM: pastilha com pinos
                r.set(cx - 7.5f * d, cy - 5 * d, cx + 7.5f * d, cy + 5 * d);
                c.drawRoundRect(r, 2 * d, 2 * d, p);
                for (int k = -1; k <= 1; k++) {
                    c.drawLine(cx + k * 5 * d, cy - 5 * d, cx + k * 5 * d, cy - 8.5f * d, p);
                    c.drawLine(cx + k * 5 * d, cy + 5 * d, cx + k * 5 * d, cy + 8.5f * d, p);
                }
                break;
            default: // desempenho: raio
                path.reset();
                path.moveTo(cx + 3 * d, cy - 10.5f * d);
                path.lineTo(cx - 6 * d, cy + 2 * d);
                path.lineTo(cx - 0.5f * d, cy + 2 * d);
                path.lineTo(cx - 3 * d, cy + 10.5f * d);
                path.lineTo(cx + 6 * d, cy - 2 * d);
                path.lineTo(cx + 0.5f * d, cy - 2 * d);
                path.close();
                p.setStyle(Paint.Style.FILL);
                c.drawPath(path, p);
                break;
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF2A2A2A);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(8.5f * d);
        p.setFakeBoldText(true);
        c.drawText(BUTTON_LABEL[i], cx, cy + rad + 11 * d, p);
        p.setFakeBoldText(false);
    }

    /** Nome da música e botões de anterior, tocar/pausar e próxima. */
    private void drawMusic(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF2A2A2A);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(10 * d);
        String title = "\u266A " + music.title();
        float maxW = panW - 40 * d;
        int fit = p.breakText(title, true, maxW, null);
        if (fit < title.length()) title = title.substring(0, Math.max(1, fit - 1)) + "\u2026";
        c.drawText(title, panW / 2f, 238 * d, p);

        for (int i = 0; i < 3; i++) {
            float cx = transportX(i), cy = transportY(), rad = 17 * d;
            p.setStyle(Paint.Style.FILL);
            p.setShader(new RadialGradient(cx - 5 * d, cy - 5 * d, 24 * d, 0xFFF5F5F5, 0xFF9C9C9C, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, rad, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.5f * d);
            p.setColor(i == 1 && music.playing() ? 0xFF2EAD5B : 0xFF555555);
            c.drawCircle(cx, cy, rad, p);

            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF2A2A2A);
            path.reset();
            if (i == 1 && music.playing()) { // pausar
                r.set(cx - 6 * d, cy - 6.5f * d, cx - 2 * d, cy + 6.5f * d);
                c.drawRect(r, p);
                r.set(cx + 2 * d, cy - 6.5f * d, cx + 6 * d, cy + 6.5f * d);
                c.drawRect(r, p);
            } else if (i == 1) {             // tocar
                path.moveTo(cx - 4 * d, cy - 7 * d);
                path.lineTo(cx + 7 * d, cy);
                path.lineTo(cx - 4 * d, cy + 7 * d);
                path.close();
                c.drawPath(path, p);
            } else {
                // anterior |◀ (barra na esquerda, ponta pra esquerda) e próxima ▶| (espelhada)
                float s = i == 0 ? 1f : -1f;
                path.moveTo(cx + s * 6 * d, cy - 6 * d);
                path.lineTo(cx - s * 4 * d, cy);
                path.lineTo(cx + s * 6 * d, cy + 6 * d);
                path.close();
                c.drawPath(path, p);
                float bar = cx - s * 7 * d;
                r.set(bar - 1.2f * d, cy - 6 * d, bar + 1.2f * d, cy + 6 * d);
                c.drawRect(r, p);
            }
        }
    }

    /** "By - Gilberto" em RGB, com contorno preto pra aparecer em cima do metal. */
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
