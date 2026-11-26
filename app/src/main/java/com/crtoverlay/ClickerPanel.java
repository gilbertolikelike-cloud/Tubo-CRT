package com.crtoverlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextPaint;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import java.io.InputStream;
import java.util.Random;

/**
 * Terceiro painel (fica em cima do principal, ligado por 3 canos): Auto Clicker.
 * Por enquanto só a casca, com os 3 modos (alvo único, multi alvos e OCR) ainda sem função.
 */
final class ClickerPanel extends View {
    private static final float GAP = 28f;            // espaço dos canos, embaixo
    private static final float PW = 232f, PH = 104f; // tamanho do painel em dp (a tela é desenhada em dp)
    private static final String[][] MODES = {{"ALVO", "ÚNICO"}, {"MULTI", "ALVOS"}, {"OCR", "(texto)"}};
    private static final float[] MODE_X = {14f, 85f, 156f};

    private final Context ctx;
    private final WindowManager wm;
    private final SharedPreferences sp;
    private final float dens, d;
    private final int w, h;
    private final WindowManager.LayoutParams lp;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Matrix matrix = new Matrix();
    private Bitmap plateBmp, woodBmp, pipe;
    private BitmapShader plate, wood;
    private boolean showing, closing, animIn;
    private float finalY;
    private long animStart;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private int mode;
    private float downX, downY;

    ClickerPanel(Context ctx, WindowManager wm, boolean viaAccessibility, SharedPreferences sp) {
        super(ctx);
        this.ctx = ctx;
        this.wm = wm;
        this.sp = sp;
        dens = ctx.getResources().getDisplayMetrics().density;
        d = dens * Math.max(0.5f, Math.min(1f, sp.getInt("panel_scale", 80) / 100f)) * 0.78f; // menor que o principal
        w = Math.round(PW * d);
        h = Math.round((PH + GAP) * d);
        tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        woodBmp = makeWood();
        wood = new BitmapShader(woodBmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        try (InputStream in = ctx.getAssets().open("parchment.jpg")) {
            plateBmp = BitmapFactory.decodeStream(in);
            if (plateBmp != null) plate = new BitmapShader(plateBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        } catch (java.io.IOException | RuntimeException ignored) {
            plate = null;
        }
        try (InputStream in = ctx.getAssets().open("pipe.png")) {
            pipe = BitmapFactory.decodeStream(in); // cano de cobre em pé
        } catch (java.io.IOException | RuntimeException ignored) {
            pipe = null;
        }
        lp = new WindowManager.LayoutParams(w, h,
                viaAccessibility ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.windowAnimations = R.style.NoWindowAnim;
        lp.gravity = Gravity.TOP | Gravity.START;
    }

    void toggle(float panelX, float panelY, float panelW) {
        if (!showing) show(panelX, panelY, panelW); else if (!closing) close();
    }

    /** Aparece em cima do painel principal, ligado por 3 canos, subindo de trás dele. */
    void show(float panelX, float panelY, float panelW) {
        if (showing) return;
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(dm);
        lp.x = Math.max(0, Math.min(dm.widthPixels - w, Math.round(panelX + (panelW - w) / 2f))); // centralizado no principal
        finalY = Math.max(0f, panelY + 6 * d - h);
        lp.y = Math.round(finalY);
        setTranslationY(h - 28 * d);
        setAlpha(0f);
        try {
            wm.addView(this, lp);
            showing = true;
            closing = false;
        } catch (RuntimeException e) {
            return;
        }
        slide(true);
    }

    void close() {
        if (!showing || closing) return;
        closing = true;
        slide(false);
    }

    void closeNow() {
        handler.removeCallbacksAndMessages(null);
        animate().cancel();
        if (!showing) return;
        showing = false;
        closing = false;
        try { wm.removeView(this); } catch (RuntimeException ignored) { /* já saiu */ }
    }

    private void slide(boolean in) {
        animIn = in;
        animate().cancel();
        setLayerType(LAYER_TYPE_HARDWARE, null);   // pela GPU: sem engasgar
        android.view.ViewPropertyAnimator a = animate().setDuration(in ? 300 : 240)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2f));
        if (in) {
            a.translationY(0f).alpha(1f).withEndAction(() -> setLayerType(LAYER_TYPE_NONE, null)).start();
        } else {
            a.translationY(h - 28 * d).alpha(0f).withEndAction(() -> {
                showing = false;
                closing = false;
                try { wm.removeView(ClickerPanel.this); } catch (RuntimeException ignored) { /* já saiu */ }
            }).start();
        }
    }

    private Bitmap makeWood() {
        int px = Math.max(32, Math.round(90 * d));
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

    // ---------- desenho (em dp) ----------
    @Override protected void onDraw(Canvas c) {
        c.save();
        c.scale(d, d);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF8B4A1E);
        matrix.setScale(1f / d, 1f / d);
        wood.setLocalMatrix(matrix);
        p.setShader(wood);
        r.set(0, 0, PW, PH);
        c.drawRoundRect(r, 14, 14, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.2f);
        p.setColor(0xFF1B0F06);
        r.set(0.6f, 0.6f, PW - 0.6f, PH - 0.6f);
        c.drawRoundRect(r, 13.4f, 13.4f, p);
        p.setStyle(Paint.Style.FILL);
        r.set(6, 6, PW - 6, PH - 6);
        if (plate != null && plateBmp != null) {
            float sc = Math.max(r.width() / plateBmp.getWidth(), r.height() / plateBmp.getHeight());
            matrix.setScale(sc, sc);
            matrix.postTranslate(r.left + (r.width() - plateBmp.getWidth() * sc) / 2f, r.top + (r.height() - plateBmp.getHeight() * sc) / 2f);
            plate.setLocalMatrix(matrix);
            p.setColor(0xFFFFFFFF);
            p.setFilterBitmap(true);
            p.setShader(plate);
            c.drawRoundRect(r, 10, 10, p);
            p.setShader(null);
            p.setColor(0x33000000);
            c.drawRoundRect(r, 10, 10, p);
        } else {
            p.setColor(0xFF2A2218);
            c.drawRoundRect(r, 10, 10, p);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.4f);
        p.setColor(0xFFD9A441);
        r.set(5.3f, 5.3f, PW - 5.3f, PH - 5.3f);
        c.drawRoundRect(r, 10.7f, 10.7f, p);

        text(c, "AUTO CLICKER", PW / 2f, 26, 15, 0xFF7CFF8A, Paint.Align.CENTER, true);
        text(c, "em construção", PW / 2f, 40, 9.5f, 0xFFEFE6CC, Paint.Align.CENTER, false);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF050505);
        c.drawCircle(208, 20, 10, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f);
        p.setColor(0xFFE8A33D);
        c.drawCircle(208, 20, 9.3f, p);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(1.9f);
        p.setColor(0xFFEFE6CC);
        c.drawLine(204.5f, 16.5f, 211.5f, 23.5f, p);
        c.drawLine(211.5f, 16.5f, 204.5f, 23.5f, p);

        for (int i = 0; i < 3; i++) { // os 3 modos (ainda sem função)
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, 50, 0, 94, 0xFF000000, 0xFF1C1C1C, Shader.TileMode.CLAMP));
            r.set(MODE_X[i], 50, MODE_X[i] + 62, 94);
            c.drawRoundRect(r, 9, 9, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.2f);
            p.setColor(0x55E8A33D);
            c.drawRoundRect(r, 9, 9, p);
            text(c, MODES[i][0], MODE_X[i] + 31, 68, 10.5f, 0xFFFFFFFF, Paint.Align.CENTER, true);
            text(c, MODES[i][1], MODE_X[i] + 31, 82, 10, 0xFFB0B0B0, Paint.Align.CENTER, false);
        }
        c.restore();
        c.save();
        c.scale(d, d);
        if (pipe != null) { // 3 canos de cobre descendo até o painel principal
            p.setFilterBitmap(true);
            for (float x : new float[]{44f, 116f, 188f}) {
                r.set(x - 7, PH - 8, x + 7, PH + GAP);
                c.drawBitmap(pipe, null, r, p);
            }
        }
        c.restore();
    }

    private void text(Canvas c, String s, float x, float y, float size, int color, Paint.Align align, boolean outline) {
        tp.setTextSize(size);
        tp.setTextAlign(align);
        tp.setFakeBoldText(outline);
        if (outline) {
            tp.setStyle(Paint.Style.STROKE);
            tp.setStrokeWidth(Math.max(1.6f, size * 0.18f));
            tp.setColor(0xFF000000);
            c.drawText(s, x, y, tp);
        }
        tp.setStyle(Paint.Style.FILL);
        tp.setColor(color);
        c.drawText(s, x, y, tp);
        tp.setFakeBoldText(false);
    }

    // ---------- toques ----------
    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX() / d, y = e.getY() / d;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                mode = Math.hypot(x - 208, y - 20) < 16 ? 1 : 2;
                return true;
            case MotionEvent.ACTION_UP:
                if (mode == 1 && Math.hypot(x - 208, y - 20) < 16) {
                    close();
                } else if (mode == 2 && Math.hypot(x - downX, y - downY) < 10 && y >= 50 && y <= 94) {
                    for (int i = 0; i < 3; i++) {
                        if (x >= MODE_X[i] && x <= MODE_X[i] + 62) {
                            Toast.makeText(ctx, "Em breve: modo " + MODES[i][0] + " " + MODES[i][1], Toast.LENGTH_SHORT).show();
                        }
                    }
                }
                mode = 0;
                return true;
            default:
                return true;
        }
    }
}
