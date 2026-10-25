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

/**
 * O Bob dançando que fica por cima de tudo. Toque nele: abre o painel com dois botões de girar
 * (volume e brilho), quatro botões (captura, apagar a tela, limpar RAM, modo desempenho) e o player
 * de música da pasta que você escolheu. Arraste o Bob pra qualquer lugar; ele olha pro lado do centro.
 *
 * Botão de girar: girar pra direita (sentido horário) diminui, pra esquerda aumenta.
 * O Bob é desenhado quadro a quadro (bob_sheet.png), a 8 quadros por segundo, pra pesar pouco.
 */
public class BobPanel extends View {
    /** O Bob do arquivo olha pra esquerda. */
    private static final boolean ASSET_FACES_LEFT = true;
    private static final int SHEET_COLS = 8, SHEET_ROWS = 5, FRAMES = 40, FRAME_MS = 120;

    private static final int M_NONE = 0, M_BOB = 1, M_VOL = 2, M_BRI = 3;
    private static final int M_BTN = 4;   // 4..7  = FOTO, APAGAR, RAM, MAX
    private static final int M_TRANSPORT = 8; // 8..10 = anterior, tocar/pausar, próxima
    private static final String[] BUTTON_LABEL = {"FOTO", "APAGAR", "RAM", "MAX"};
    private static final int[] BUTTON_RING = {0xFF2EAD5B, 0xFFD94040, 0xFF4F6BFF, 0xFFFFC107};
    private static final float RAINBOW_LEN = 120f;
    private static final long AUTO_CLOSE_MS = 10000;
    private static final float PANEL_W_DP = 232f, PANEL_H_DP = 300f;

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
            handler.postDelayed(this, FRAME_MS);
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
        handler.postDelayed(tick, FRAME_MS);
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
            Toast.makeText(ctx, "Escolha a pasta de músicas no app (botão do pilar)", Toast.LENGTH_LONG).show();
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
    private float knobX(int i) { return (i == 0 ? 62 : PANEL_W_DP - 62) * d; }
    private float knobY() { return 66 * d; }
    private float buttonX(int i) { return (40 + 53 * i) * d; }
    private float buttonY() { return 156 * d; }
    private float transportX(int i) { return (PANEL_W_DP / 2f + (i - 1) * 52) * d; }
    private float transportY() { return 240 * d; }

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

    private void drawBob(Canvas c) {
        float lx = bobX - winX, ly = bobY - winY;
        boolean onRightHalf = bobX + bobW / 2f > screenW / 2f;
        c.save();
        if (onRightHalf != ASSET_FACES_LEFT) c.scale(-1f, 1f, lx + bobW / 2f, ly + bobH / 2f); // olha pro lado do centro
        if (sheet != null) {
            int idx = (int) (((SystemClock.uptimeMillis() - t0) / FRAME_MS) % FRAMES);
            int left = (idx % SHEET_COLS) * frameW, top = (idx / SHEET_COLS) * frameH;
            src.set(left, top, left + frameW, top + frameH);
            dst.set(lx, ly, lx + bobW, ly + bobH);
            bobPaint.setAlpha(Math.round(255 * bobAlpha));
            c.drawBitmap(sheet, src, dst, bobPaint);
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

    /** Desenha o painel com o canto em (0, 0). */
    private void drawPanel(Canvas c) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF0B0B0B); // moldura
        r.set(0, 0, panW, panH);
        c.drawRoundRect(r, 16 * d, 16 * d, p);
        r.set(8 * d, 8 * d, panW - 8 * d, panH - 30 * d); // placa de metal
        p.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xFFCACACA, 0xFF8A8A8A, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 10 * d, 10 * d, p);
        p.setShader(null);

        drawKnob(c, knobX(0), knobY(), volume, "VOL", 0xFF4F6BFF);
        drawKnob(c, knobX(1), knobY(), brightness, "BRI", 0xFFFFA833);
        for (int i = 0; i < 4; i++) drawButton(c, buttonX(i), buttonY(), i);
        drawMusic(c);
        drawSignature(c);
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
        float maxW = panW - 36 * d;
        int fit = p.breakText(title, true, maxW, null);
        if (fit < title.length()) title = title.substring(0, Math.max(1, fit - 1)) + "\u2026";
        c.drawText(title, panW / 2f, 208 * d, p);

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
            } else {                         // anterior (0) e próxima (2)
                float s = i == 0 ? -1f : 1f;
                path.moveTo(cx + s * 7 * d, cy - 6 * d);
                path.lineTo(cx - s * 2 * d, cy);
                path.lineTo(cx + s * 7 * d, cy + 6 * d);
                path.close();
                c.drawPath(path, p);
                r.set(cx - s * 7 * d - 1.2f * d, cy - 6 * d, cx - s * 7 * d + 1.2f * d, cy + 6 * d);
                c.drawRect(r, p);
            }
        }
    }

    /** "By - Gilberto" em RGB, na faixa preta de baixo. */
    private void drawSignature(Canvas c) {
        float t = (SystemClock.uptimeMillis() - t0) / 1000f;
        rainbowMatrix.setTranslate((t * 70f * d) % (RAINBOW_LEN * d), 0);
        rainbow.setLocalMatrix(rainbowMatrix);
        p.setShader(rainbow);
        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(12 * d);
        p.setFakeBoldText(true);
        c.drawText("By - Gilberto", panW / 2f, panH - 10 * d, p);
        p.setShader(null);
        p.setFakeBoldText(false);
    }
}
