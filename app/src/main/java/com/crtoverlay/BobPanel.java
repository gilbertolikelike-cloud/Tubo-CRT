package com.crtoverlay;

import android.accessibilityservice.AccessibilityService;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
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

/**
 * O Bob dançando que fica por cima de tudo. Toque nele: abre o painel com dois botões de girar
 * (volume e brilho) e quatro botões (captura, apagar a tela, limpar RAM, modo desempenho).
 * Arraste o Bob pra qualquer lugar da tela; ele sempre olha pro lado do centro da tela.
 *
 * Botão de girar: girar pra direita (sentido horário) diminui, pra esquerda aumenta.
 */
public class BobPanel extends View {
    /** O Bob do arquivo bob.webp olha pra esquerda. */
    private static final boolean ASSET_FACES_LEFT = true;

    private static final int M_NONE = 0, M_BOB = 1, M_VOL = 2, M_BRI = 3, M_BTN = 4; // M_BTN + número do botão
    private static final int BUTTONS = 4;
    private static final String[] BUTTON_LABEL = {"FOTO", "APAGAR", "RAM", "MAX"};
    private static final int[] BUTTON_RING = {0xFF2EAD5B, 0xFFD94040, 0xFF4F6BFF, 0xFFFFC107};
    private static final float RAINBOW_LEN = 120f;
    private static final long AUTO_CLOSE_MS = 8000;

    private final Context ctx;
    private final WindowManager wm;
    private final boolean viaAccessibility;
    private final SharedPreferences sp;
    private final AudioManager audio;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoClose = () -> setOpen(false, true);
    private final float d, panW, panH;   // densidade e tamanho do painel em px
    private final int slop;
    private final long t0 = SystemClock.uptimeMillis();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private final Matrix rainbowMatrix = new Matrix();
    private final Shader rainbow;

    private AnimatedImageDrawable bob;
    private WindowManager.LayoutParams lp;
    private ValueAnimator animator;
    private boolean open, added, askedPermission, dragging;
    private float anim;                         // 0 fechado .. 1 aberto
    private float bobW, bobH, bobAlpha = 1f, bobX, bobY, winX, winY;
    private int screenW, screenH, fx, fy;       // fx, fy: posição do Bob em milésimos do espaço livre da tela
    private int mode = M_NONE;
    private float downRawX, downRawY, startBobX, startBobY, lastAngle, volume, brightness;

    public BobPanel(Context ctx, WindowManager wm, boolean viaAccessibility, SharedPreferences sp) {
        super(ctx);
        this.ctx = ctx;
        this.wm = wm;
        this.viaAccessibility = viaAccessibility;
        this.sp = sp;
        d = ctx.getResources().getDisplayMetrics().density;
        panW = 232 * d;
        panH = 250 * d;
        slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        audio = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int[] colors = new int[7];
        for (int i = 0; i < colors.length; i++) colors[i] = Color.HSVToColor(new float[]{i * 60f % 360f, 1f, 1f});
        rainbow = new LinearGradient(0, 0, RAINBOW_LEN * d, 0, colors, null, Shader.TileMode.REPEAT);
        loadBob();
    }

    private void loadBob() {
        try {
            Drawable dr = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ctx.getAssets(), "bob.webp"));
            if (dr instanceof AnimatedImageDrawable) {
                bob = (AnimatedImageDrawable) dr;
                bob.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                bob.setCallback(this);
            }
        } catch (Exception ignored) {
            // sem o arquivo, desenha um Bob simples no lugar
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
        if (bob != null) bob.start();
    }

    public void hide() {
        handler.removeCallbacksAndMessages(null);
        if (animator != null) animator.cancel();
        if (bob != null) bob.stop();
        if (added) {
            try { wm.removeView(this); } catch (RuntimeException ignored) { /* janela já removida */ }
            added = false;
        }
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
        float aspect = bob != null && bob.getIntrinsicHeight() > 0 ? bob.getIntrinsicWidth() / (float) bob.getIntrinsicHeight() : 1f;
        bobW = bobH * aspect;
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

    // ---------- janela ----------
    private boolean panelOnLeft() { return bobX + bobW / 2f > screenW / 2f; }

    /** Janela do tamanho do Bob (fechado) ou do Bob + painel (aberto). */
    private void applyWindow(boolean forOpen) {
        if (lp == null) return;
        if (!forOpen) {
            winX = bobX;
            winY = bobY;
            lp.width = Math.round(bobW);
            lp.height = Math.round(bobH);
        } else {
            float w = bobW + panW, h = Math.max(panH, bobH);
            float x = panelOnLeft() ? bobX - panW : bobX;
            float y = bobY + bobH / 2f - h / 2f;
            winX = Math.max(0f, Math.min(x, Math.max(0f, screenW - w)));
            winY = Math.max(0f, Math.min(y, Math.max(0f, screenH - h)));
            lp.width = Math.round(w);
            lp.height = Math.round(h);
        }
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

    /** Captura e bloqueio de tela só existem via Acessibilidade. Fecha o painel antes, pra ele não sair na foto. */
    private void global(final int action) {
        setOpen(false, true);
        handler.postDelayed(() -> {
            CrtAccessService s = CrtAccessService.instance;
            if (s == null) Toast.makeText(ctx, "Ative o Tubo CRT em Acessibilidade pra usar este botão", Toast.LENGTH_LONG).show();
            else s.performGlobalAction(action);
        }, 500);
    }

    // ---------- toque ----------
    private float panelLeft() {
        float l = panelOnLeft() ? bobX - winX - panW : bobX - winX + bobW;
        return Math.max(0f, Math.min(l, Math.max(0f, lp.width - panW)));
    }

    private float knobX(int i) { return panelLeft() + (i == 0 ? 64 : 232 - 64) * d; }
    private float knobY() { return 78 * d; }
    private float buttonX(int i) { return panelLeft() + (38 + 54 * i) * d; }
    private float buttonY() { return 176 * d; }

    private int hit(float x, float y) {
        float bl = bobX - winX, bt = bobY - winY;
        if (x >= bl && x <= bl + bobW && y >= bt && y <= bt + bobH) return M_BOB;
        if (anim < 0.5f) return M_NONE;
        for (int i = 0; i < 2; i++)
            if (Math.hypot(x - knobX(i), y - knobY()) < 44 * d) return i == 0 ? M_VOL : M_BRI;
        for (int i = 0; i < BUTTONS; i++)
            if (Math.hypot(x - buttonX(i), y - buttonY()) < 27 * d) return M_BTN + i;
        return M_NONE;
    }

    private static float angleOf(float x, float y, float cx, float cy) {
        return (float) Math.toDegrees(Math.atan2(x - cx, -(y - cy))); // graus, sentido horário, 0 = pra cima
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mode = hit(x, y);
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                startBobX = bobX;
                startBobY = bobY;
                dragging = false;
                if (mode == M_VOL) lastAngle = angleOf(x, y, knobX(0), knobY());
                if (mode == M_BRI) lastAngle = angleOf(x, y, knobX(1), knobY());
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
                        bobX = Math.max(0f, Math.min(startBobX + dx, screenW - bobW));
                        bobY = Math.max(0f, Math.min(startBobY + dy, screenH - bobH));
                        applyWindow(false);
                        invalidate();
                    }
                } else if (mode == M_VOL || mode == M_BRI) {
                    turnKnob(x, y);
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (mode == M_BOB) {
                    if (dragging) savePosition(); else setOpen(!open, true);
                } else if (mode >= M_BTN && hit(x, y) == mode) {
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
    private void turnKnob(float x, float y) {
        boolean isVolume = mode == M_VOL;
        float a = angleOf(x, y, knobX(isVolume ? 0 : 1), knobY());
        float delta = a - lastAngle;
        while (delta > 180f) delta -= 360f;
        while (delta < -180f) delta += 360f;
        lastAngle = a;
        float v = Math.max(0f, Math.min(1f, (isVolume ? volume : brightness) - delta / 270f));
        if (isVolume) { volume = v; applyVolume(v); } else { brightness = v; applyBrightness(v); }
        invalidate();
    }

    // ---------- desenho ----------
    @Override protected boolean verifyDrawable(Drawable who) { return who == bob || super.verifyDrawable(who); }

    @Override public void invalidateDrawable(Drawable who) { invalidate(); } // o Bob pode estar espelhado: redesenha tudo

    @Override protected void onDraw(Canvas c) {
        if (anim > 0.001f) {
            float pl = panelLeft();
            float pivotX = panelOnLeft() ? pl + panW : pl;
            int save = c.saveLayerAlpha(pl, 0, pl + panW, panH, (int) (255 * Math.min(1f, anim)));
            c.scale(0.75f + 0.25f * anim, 0.92f + 0.08f * anim, pivotX, panH / 2f);
            drawPanel(c, pl);
            c.restoreToCount(save);
        }
        drawBob(c);
    }

    private void drawBob(Canvas c) {
        float lx = bobX - winX, ly = bobY - winY;
        c.save();
        if (panelOnLeft() != ASSET_FACES_LEFT) c.scale(-1f, 1f, lx + bobW / 2f, ly + bobH / 2f); // olha pro lado do centro
        if (bob != null) {
            bob.setBounds(Math.round(lx), Math.round(ly), Math.round(lx + bobW), Math.round(ly + bobH));
            bob.setAlpha(Math.round(255 * bobAlpha));
            bob.draw(c);
        } else {
            drawFallbackBob(c, lx, ly);
        }
        c.restore();
    }

    private void drawFallbackBob(Canvas c, float lx, float ly) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(Math.round(255 * bobAlpha), 245, 224, 74));
        r.set(lx, ly, lx + bobW, ly + bobH);
        c.drawRoundRect(r, 8 * d, 8 * d, p);
        p.setColor(Color.argb(Math.round(255 * bobAlpha), 255, 255, 255));
        c.drawCircle(lx + bobW * 0.32f, ly + bobH * 0.35f, bobW * 0.12f, p);
        c.drawCircle(lx + bobW * 0.64f, ly + bobH * 0.35f, bobW * 0.12f, p);
        p.setColor(Color.argb(Math.round(255 * bobAlpha), 30, 30, 30));
        c.drawCircle(lx + bobW * 0.30f, ly + bobH * 0.35f, bobW * 0.05f, p);
        c.drawCircle(lx + bobW * 0.62f, ly + bobH * 0.35f, bobW * 0.05f, p);
    }

    private void drawPanel(Canvas c, float pl) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF0B0B0B); // moldura
        r.set(pl, 0, pl + panW, panH);
        c.drawRoundRect(r, 16 * d, 16 * d, p);
        r.set(pl + 8 * d, 8 * d, pl + panW - 8 * d, panH - 32 * d); // placa de metal
        p.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xFFCACACA, 0xFF8A8A8A, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 10 * d, 10 * d, p);
        p.setShader(null);

        drawKnob(c, knobX(0), knobY(), volume, "VOL", 0xFF4F6BFF);
        drawKnob(c, knobX(1), knobY(), brightness, "BRI", 0xFFFFA833);
        for (int i = 0; i < BUTTONS; i++) drawButton(c, buttonX(i), buttonY(), i);
        drawSignature(c, pl);
    }

    private void drawKnob(Canvas c, float cx, float cy, float v, String label, int color) {
        float radius = 36 * d, theta = 135f - 270f * v; // theta: graus no sentido horário, 0 = pra cima
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
        p.setShader(new RadialGradient(cx - 10 * d, cy - 12 * d, radius * 1.5f, 0xFFF6F6F6, 0xFF7C7C7C, Shader.TileMode.CLAMP));
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
        c.drawText(label + " " + Math.round(v * 100) + "%", cx, cy + radius + 20 * d, p);
        p.setFakeBoldText(false);
    }

    private void drawButton(Canvas c, float cx, float cy, int i) {
        boolean on = i == 3 && sp.getBoolean("perf_on", false);
        int ring = i == 3 && !on ? 0xFF555555 : BUTTON_RING[i];
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(cx - 6 * d, cy - 6 * d, 30 * d, 0xFFF5F5F5, 0xFF9C9C9C, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, 21 * d, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3 * d);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(ring);
        c.drawCircle(cx, cy, 21 * d, p);

        p.setColor(0xFF2A2A2A);
        p.setStrokeWidth(2.2f * d);
        switch (i) {
            case 0: // captura: moldura com ponto
                r.set(cx - 10 * d, cy - 7.5f * d, cx + 10 * d, cy + 7.5f * d);
                c.drawRoundRect(r, 3 * d, 3 * d, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, 3f * d, p);
                break;
            case 1: // apagar a tela: símbolo de ligar
                r.set(cx - 8.5f * d, cy - 8.5f * d, cx + 8.5f * d, cy + 8.5f * d);
                c.drawArc(r, -55f, 290f, false, p);
                c.drawLine(cx, cy - 11 * d, cx, cy - 2 * d, p);
                break;
            case 2: // RAM: pastilha com pinos
                r.set(cx - 8 * d, cy - 5.5f * d, cx + 8 * d, cy + 5.5f * d);
                c.drawRoundRect(r, 2 * d, 2 * d, p);
                for (int k = -1; k <= 1; k++) {
                    c.drawLine(cx + k * 5 * d, cy - 5.5f * d, cx + k * 5 * d, cy - 9 * d, p);
                    c.drawLine(cx + k * 5 * d, cy + 5.5f * d, cx + k * 5 * d, cy + 9 * d, p);
                }
                break;
            default: // desempenho: raio
                path.reset();
                path.moveTo(cx + 3 * d, cy - 11 * d);
                path.lineTo(cx - 6 * d, cy + 2 * d);
                path.lineTo(cx - 0.5f * d, cy + 2 * d);
                path.lineTo(cx - 3 * d, cy + 11 * d);
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
        c.drawText(BUTTON_LABEL[i], cx, cy + 21 * d + 11 * d, p);
        p.setFakeBoldText(false);
    }

    /** "By - Gilberto" em RGB, na faixa preta de baixo. */
    private void drawSignature(Canvas c, float pl) {
        float t = (SystemClock.uptimeMillis() - t0) / 1000f;
        rainbowMatrix.setTranslate((t * 70f * d) % (RAINBOW_LEN * d), 0);
        rainbow.setLocalMatrix(rainbowMatrix);
        p.setShader(rainbow);
        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(12 * d);
        p.setFakeBoldText(true);
        c.drawText("By - Gilberto", pl + panW / 2f, panH - 11 * d, p);
        p.setShader(null);
        p.setFakeBoldText(false);
    }
}
