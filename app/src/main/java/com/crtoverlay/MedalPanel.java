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
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import java.io.InputStream;
import java.util.Random;

/**
 * Segundo painel: medalhas. Mostra o placar (kills, nível), as medalhas de ouro e RGB dos patos pet
 * e, por livro da Bíblia, quantos versículos já deram medalha. Arrasta pelo título e rola a lista com o dedo.
 */
final class MedalPanel extends View {
    private static final float PW = 232f, PH = 360f;          // tamanho em dp (a tela é desenhada em dp)
    private static final float LIST_L = 14, LIST_T = 196, LIST_R = 218, LIST_B = 350, ROW = 30;

    // livros: calculados uma vez por execução do app
    private static String[] bookNames;
    private static int[] bookStart, bookCount;
    private static int totalVerses;

    private final Context ctx;
    private final WindowManager wm;
    private final boolean viaAccessibility;
    private final SharedPreferences sp;
    private final float dens, d;
    private final int w, h;
    private final WindowManager.LayoutParams lp;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Matrix matrix = new Matrix();
    private Bitmap plateBmp, woodBmp;
    private BitmapShader plate, wood;

    private int[] unlocked;
    private int unlockedTotal;
    private boolean showing;
    private float scroll;
    private int mode;
    private float downX, downY, startScroll, rawX, rawY;
    private int startX, startY;
    private boolean moved;

    MedalPanel(Context ctx, WindowManager wm, boolean viaAccessibility, SharedPreferences sp) {
        super(ctx);
        this.ctx = ctx;
        this.wm = wm;
        this.viaAccessibility = viaAccessibility;
        this.sp = sp;
        dens = ctx.getResources().getDisplayMetrics().density;
        d = dens * Math.max(0.5f, Math.min(1f, sp.getInt("panel_scale", 80) / 100f));
        w = Math.round(PW * d);
        h = Math.round(PH * d);
        tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        woodBmp = makeWood();
        wood = new BitmapShader(woodBmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        try (InputStream in = ctx.getAssets().open("parchment.jpg")) {
            plateBmp = BitmapFactory.decodeStream(in);
            if (plateBmp != null) plate = new BitmapShader(plateBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        } catch (java.io.IOException | RuntimeException ignored) {
            plate = null;
        }
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(dm);
        lp = new WindowManager.LayoutParams(w, h,
                viaAccessibility ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Math.round(12 * dens);
        lp.y = Math.max(0, (dm.heightPixels - h) / 2);
    }

    void toggle() {
        if (showing) close(); else show();
    }

    void show() {
        if (showing) return;
        try {
            wm.addView(this, lp);
            showing = true;
        } catch (RuntimeException e) {
            return;
        }
        scroll = 0f;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            if (bookNames == null) {
                Verses v = Verses.load(app);
                if (v != null) {
                    Verses.Books b = v.books();
                    bookStart = b.start;
                    bookCount = b.count;
                    totalVerses = v.size();
                    bookNames = b.names;
                }
            }
            if (bookNames == null) return;
            Medals m = new Medals(sp, totalVerses);
            final int[] un = new int[bookNames.length];
            for (int i = 0; i < un.length; i++) un[i] = m.countRange(bookStart[i], bookCount[i]);
            final int all = m.count();
            post(() -> {
                unlocked = un;
                unlockedTotal = all;
                invalidate();
            });
        }).start();
    }

    void close() {
        if (!showing) return;
        showing = false;
        try { wm.removeView(this); } catch (RuntimeException ignored) { /* já saiu */ }
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

    private float maxScroll() {
        int n = bookNames == null ? 0 : bookNames.length;
        return Math.max(0f, n * ROW - (LIST_B - LIST_T));
    }

    // ---------- desenho (tudo em dp: o canvas é escalado por d) ----------
    @Override protected void onDraw(Canvas c) {
        c.save();
        c.scale(d, d);
        // moldura de madeira, linha escura por fora, miolo barroco e fio dourado
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

        // título e fechar
        text(c, "MEDALHAS", PW / 2f, 31, 17, 0xFFFFD27A, Paint.Align.CENTER, true);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF050505);
        c.drawCircle(208, 24, 11, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.6f);
        p.setColor(0xFFE8A33D);
        c.drawCircle(208, 24, 10.2f, p);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(2f);
        p.setColor(0xFFEFE6CC);
        c.drawLine(204, 20, 212, 28, p);
        c.drawLine(212, 20, 204, 28, p);

        // placar
        drawStrip(c, 14, 46, 218, 108);
        int lvl = GameState.level(sp), exp = GameState.exp(sp);
        int shown = unlocked != null ? unlockedTotal : 0, tot = totalVerses > 0 ? totalVerses : 30310;
        text(c, "Versículos: " + shown + " / " + tot, 22, 64, 11.5f, 0xFFFFFFFF, Paint.Align.LEFT, true);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x55FFFFFF);
        r.set(22, 69, 210, 73);
        c.drawRoundRect(r, 2, 2, p);
        p.setColor(0xFF7CFF8A);
        r.set(22, 69, 22 + 188 * Math.min(1f, shown / (float) tot), 73);
        c.drawRoundRect(r, 2, 2, p);
        text(c, "Kills: " + GameState.killsRun(sp) + " (rodada)  |  " + GameState.killsTotal(sp) + " (total)", 22, 88, 10.5f, 0xFFEFE6CC, Paint.Align.LEFT, false);
        text(c, "Espada nível " + lvl + "  ·  EXP " + exp + " / 100", 22, 101, 10.5f, 0xFFEFE6CC, Paint.Align.LEFT, false);

        // medalhas dos patos pet
        drawPetMedal(c, 66, 138, sp.getBoolean("medal_gold", false), false);
        drawPetMedal(c, 166, 138, sp.getBoolean("medal_rgb", false), true);
        text(c, "OURO", 66, 172, 10, 0xFFEFE6CC, Paint.Align.CENTER, true);
        text(c, "RGB", 166, 172, 10, 0xFFEFE6CC, Paint.Align.CENTER, true);
        text(c, "LIVROS", PW / 2f, 190, 11, 0xFFFFD27A, Paint.Align.CENTER, true);

        // lista de livros
        drawStrip(c, LIST_L - 2, LIST_T - 2, LIST_R + 2, LIST_B + 2);
        c.save();
        c.clipRect(LIST_L, LIST_T, LIST_R, LIST_B);
        if (bookNames == null || unlocked == null) {
            text(c, "carregando…", PW / 2f, LIST_T + 30, 11, 0xFFB0B0B0, Paint.Align.CENTER, false);
        } else {
            for (int i = 0; i < bookNames.length; i++) {
                float y = LIST_T + i * ROW - scroll;
                if (y + ROW < LIST_T || y > LIST_B) continue;
                boolean done = unlocked[i] >= bookCount[i];
                text(c, bookNames[i], LIST_L + 8, y + 14, 11, done ? 0xFFFFD27A : 0xFFFFFFFF, Paint.Align.LEFT, done);
                text(c, unlocked[i] + "/" + bookCount[i], LIST_R - 8, y + 14, 10, done ? 0xFFFFD27A : 0xFFB0B0B0, Paint.Align.RIGHT, false);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x44FFFFFF);
                r.set(LIST_L + 8, y + 19, LIST_R - 8, y + 22);
                c.drawRoundRect(r, 1.5f, 1.5f, p);
                p.setColor(done ? 0xFFFFD27A : 0xFF7CFF8A);
                r.set(LIST_L + 8, y + 19, LIST_L + 8 + (LIST_R - LIST_L - 16) * (unlocked[i] / (float) Math.max(1, bookCount[i])), y + 22);
                c.drawRoundRect(r, 1.5f, 1.5f, p);
            }
        }
        c.restore();
        c.restore();
    }

    private void drawStrip(Canvas c, float l, float t, float rt, float b) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, t, 0, b, 0xFF000000, 0xFF1C1C1C, Shader.TileMode.CLAMP));
        r.set(l, t, rt, b);
        c.drawRoundRect(r, 9, 9, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.2f);
        p.setColor(0x33FFFFFF);
        c.drawRoundRect(r, 9, 9, p);
    }

    /** Medalha redonda: apagada até o pato pet ser achado; dourada ou com todas as cores quando desbloqueada. */
    private void drawPetMedal(Canvas c, float cx, float cy, boolean has, boolean rainbow) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF050505);
        c.drawCircle(cx, cy, 24, p);
        if (has) {
            if (rainbow) {
                int[] colors = new int[7];
                for (int i = 0; i < colors.length; i++) colors[i] = Color.HSVToColor(new float[]{i * 60f % 360f, 0.9f, 1f});
                p.setShader(new SweepGradient(cx, cy, colors, null));
            } else {
                p.setShader(new RadialGradient(cx - 6, cy - 7, 30, 0xFFFFF3B0, 0xFFC8901A, Shader.TileMode.CLAMP));
            }
            c.drawCircle(cx, cy, 21, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(cx, cy, 21, p);
            text(c, "\u2605", cx, cy + 6, 18, 0xFFFFFFFF, Paint.Align.CENTER, true);
        } else {
            p.setColor(0xFF2A2A2A);
            c.drawCircle(cx, cy, 21, p);
            text(c, "?", cx, cy + 7, 20, 0xFF666666, Paint.Align.CENTER, true);
        }
    }

    /** Texto com contorno preto, pra ler bem em cima de qualquer fundo. */
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
                rawX = e.getRawX();
                rawY = e.getRawY();
                startX = lp.x;
                startY = lp.y;
                startScroll = scroll;
                moved = false;
                if (Math.hypot(x - 208, y - 24) < 16) mode = 1;
                else if (y < 44) mode = 2;
                else if (x >= LIST_L && x <= LIST_R && y >= LIST_T && y <= LIST_B) mode = 3;
                else mode = 0;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dxr = e.getRawX() - rawX, dyr = e.getRawY() - rawY;
                if (Math.hypot(dxr, dyr) > 10 * dens) moved = true;
                if (mode == 2 && moved) {
                    DisplayMetrics dm = new DisplayMetrics();
                    wm.getDefaultDisplay().getMetrics(dm);
                    lp.x = Math.max(0, Math.min(dm.widthPixels - w, Math.round(startX + dxr)));
                    lp.y = Math.max(0, Math.min(dm.heightPixels - h, Math.round(startY + dyr)));
                    try { wm.updateViewLayout(this, lp); } catch (RuntimeException ignored) { /* janela já saiu */ }
                } else if (mode == 3) {
                    scroll = Math.max(0f, Math.min(maxScroll(), startScroll - (y - downY)));
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (mode == 1 && Math.hypot(x - 208, y - 24) < 16) close();
                mode = 0;
                return true;
            default:
                return true;
        }
    }
}
