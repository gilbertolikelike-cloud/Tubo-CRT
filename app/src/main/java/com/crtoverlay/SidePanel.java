package com.crtoverlay;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

/** Aba na borda direita da tela. Toque na aba abre o painel: volume, brilho, captura de tela e apagar a tela. */
public class SidePanel extends View {
    private final Context ctx;
    private final WindowManager wm;
    private final boolean acc;
    private final AudioManager am;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final float d, tabW, tabH, panW, panH;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Runnable autoClose = this::closePanel;
    private WindowManager.LayoutParams lp;
    private boolean open = false, added = false, askedPerm = false;
    private int screenH, yPct = 25, hit = 0; // hit: 1 volume, 2 brilho, 3 aba, 4 captura, 5 apagar
    private float volF, briF;

    public SidePanel(Context ctx, WindowManager wm, boolean acc) {
        super(ctx);
        this.ctx = ctx; this.wm = wm; this.acc = acc;
        d = ctx.getResources().getDisplayMetrics().density;
        tabW = 26 * d; tabH = 76 * d; panW = 168 * d; panH = 236 * d;
        am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        screenH = dm.heightPixels;
    }

    public void show(int yPercent) {
        if (added) return;
        yPct = yPercent;
        lp = new WindowManager.LayoutParams((int) tabW, (int) tabH,
                acc ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        place();
        wm.addView(this, lp);
        added = true;
    }

    public void hide() {
        h.removeCallbacksAndMessages(null);
        if (added) { try { wm.removeView(this); } catch (Exception ignored) {} added = false; }
    }

    public void setPos(int percent) { yPct = percent; if (added) { place(); wm.updateViewLayout(this, lp); } }

    private void place() {
        float base = (screenH - tabH) * yPct / 100f;
        float y = open ? base - (panH - tabH) / 2f : base;
        lp.y = (int) Math.max(0, Math.min(screenH - lp.height, y));
    }

    private void openPanel() {
        open = true; askedPerm = false;
        volF = curVol(); briF = curBri();
        lp.width = (int) (tabW + panW); lp.height = (int) panH;
        place(); wm.updateViewLayout(this, lp); invalidate(); rearm();
    }

    private void closePanel() {
        if (!open || !added) return;
        open = false;
        lp.width = (int) tabW; lp.height = (int) tabH;
        place(); wm.updateViewLayout(this, lp); invalidate();
        h.removeCallbacks(autoClose);
    }

    private void rearm() { h.removeCallbacks(autoClose); h.postDelayed(autoClose, 7000); }

    private float curVol() {
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max;
    }

    private float curBri() {
        try { return Settings.System.getInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128) / 255f; }
        catch (Exception e) { return 0.5f; }
    }

    private void setBri(float f) {
        if (!Settings.System.canWrite(ctx)) {
            if (!askedPerm) {
                askedPerm = true;
                Toast.makeText(ctx, "Permita \"Modificar configuracoes do sistema\" pro Tubo CRT", Toast.LENGTH_LONG).show();
                try {
                    ctx.startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.getPackageName()))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) {}
            }
            return;
        }
        try {
            Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, Math.max(1, (int) (f * 255)));
        } catch (Exception ignored) {}
    }

    private void setVol(float f) {
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(f * max), 0);
    }

    private void global(final int action) {
        closePanel(); // fecha antes, pra aba nao sair na foto
        h.postDelayed(() -> {
            CrtAccessService s = CrtAccessService.instance;
            if (s == null) Toast.makeText(ctx, "Ative o Tubo CRT em Acessibilidade pra usar este botao", Toast.LENGTH_LONG).show();
            else s.performGlobalAction(action);
        }, 450);
    }

    // ---------- geometria (dentro da view aberta) ----------
    private float trackX(int i) { return tabW + (i == 0 ? 36 : 84) * d; }
    private float trackTop() { return 40 * d; }
    private float trackBot() { return panH - 54 * d; }
    private float btnX() { return tabW + 130 * d; }
    private float btnY(int i) { return panH * (i == 0 ? 0.30f : 0.68f); }

    private int hitTest(float x, float y) {
        if (x < tabW) return 3;
        for (int i = 0; i < 2; i++)
            if (Math.abs(x - trackX(i)) < 24 * d && y > trackTop() - 18 * d && y < trackBot() + 18 * d) return i + 1;
        for (int i = 0; i < 2; i++)
            if (Math.hypot(x - btnX(), y - btnY(i)) < 30 * d) return 4 + i;
        return 0;
    }

    private void drag(int which, float y) {
        float f = 1f - Math.max(0f, Math.min(1f, (y - trackTop()) / (trackBot() - trackTop())));
        if (which == 1) { volF = f; setVol(f); } else { briF = f; setBri(f); }
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                hit = open ? hitTest(x, y) : 3;
                if (open) rearm();
                if (hit == 1 || hit == 2) drag(hit, y);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (hit == 1 || hit == 2) { drag(hit, y); rearm(); }
                return true;
            case MotionEvent.ACTION_UP:
                if (hit == 3) { if (open) closePanel(); else openPanel(); }
                else if (hit == 4 && hitTest(x, y) == 4) global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT);
                else if (hit == 5 && hitTest(x, y) == 5) global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
                hit = 0;
                return true;
            default:
                return true;
        }
    }

    // ---------- desenho ----------
    @Override protected void onDraw(Canvas c) {
        float tabTop = open ? (panH - tabH) / 2f : 0f;
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(0xFF0B0B0B);
        r.set(0, tabTop, tabW + 14 * d, tabTop + tabH);
        c.drawRoundRect(r, 12 * d, 12 * d, p);
        p.setColor(0xFF707070);
        for (int i = 0; i < 3; i++) {
            float yy = tabTop + tabH / 2f + (i - 1) * 9 * d;
            r.set(7 * d, yy - 1.2f * d, tabW - 6 * d, yy + 1.2f * d);
            c.drawRoundRect(r, d, d, p);
        }
        if (!open) return;
        p.setColor(0xFF0B0B0B);
        r.set(tabW, 0, tabW + panW, panH);
        c.drawRoundRect(r, 14 * d, 14 * d, p);
        r.set(tabW + 8 * d, 8 * d, tabW + panW - 8 * d, panH - 8 * d);
        p.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xFFC8C8C8, 0xFF8C8C8C, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 8 * d, 8 * d, p);
        p.setShader(null);
        drawSlider(c, 0, volF, 0xFF4F6BFF, "VOL");
        drawSlider(c, 1, briF, 0xFFFFA833, "BRI");
        drawBtn(c, 0, 0xFF2EAD5B);
        drawBtn(c, 1, 0xFFD94040);
    }

    private void drawSlider(Canvas c, int i, float f, int color, String label) {
        float x = trackX(i), top = trackTop(), bot = trackBot(), ky = bot - f * (bot - top);
        p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeWidth(5 * d);
        p.setColor(0xFF262626); c.drawLine(x, top, x, bot, p);
        p.setColor(color); c.drawLine(x, ky, x, bot, p);
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(x - 3 * d, ky - 3 * d, 16 * d, 0xFFFFFFFF, 0xFF9A9A9A, Shader.TileMode.CLAMP));
        c.drawCircle(x, ky, 13 * d, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.5f * d); p.setColor(color);
        c.drawCircle(x, ky, 13 * d, p);
        p.setStyle(Paint.Style.FILL); p.setColor(0xFF303030); p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(11 * d); p.setFakeBoldText(true);
        c.drawText(label, x, panH - 24 * d, p);
        p.setFakeBoldText(false);
    }

    private void drawBtn(Canvas c, int i, int ring) {
        float x = btnX(), y = btnY(i);
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(x - 6 * d, y - 6 * d, 30 * d, 0xFFF5F5F5, 0xFF9C9C9C, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, 24 * d, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3 * d); p.setColor(ring);
        c.drawCircle(x, y, 24 * d, p);
        p.setColor(0xFF303030); p.setStrokeWidth(2.4f * d); p.setStrokeCap(Paint.Cap.ROUND);
        if (i == 0) { // captura de tela: moldura com ponto
            r.set(x - 11 * d, y - 9 * d, x + 11 * d, y + 9 * d);
            c.drawRoundRect(r, 3 * d, 3 * d, p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(x, y, 3.2f * d, p);
        } else {      // apagar a tela: simbolo de ligar
            r.set(x - 10 * d, y - 10 * d, x + 10 * d, y + 10 * d);
            c.drawArc(r, -55, 290, false, p);
            c.drawLine(x, y - 13 * d, x, y - 2 * d, p);
        }
    }
}
