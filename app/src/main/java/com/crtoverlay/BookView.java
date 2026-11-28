package com.crtoverlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.MotionEvent;
import android.view.View;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Leitor de PDF em forma de livro: duas páginas, capa de couro, miolo com sombra, folhas que viram e zoom. */
final class BookView extends View {
    interface Listener {
        void onPageInfo(String info);
        void onTapCenter();
        void onError(String msg);
    }

    private final float dens;
    private final Handler main = new Handler(Looper.getMainLooper());
    // O PdfRenderer só aceita uma página por vez, então 1 thread; mas agora com FILA DE PRIORIDADE:
    // primeiro as páginas que aparecem na tela (versão rápida e baixa), depois a nítida, depois as vizinhas.
    private final java.util.concurrent.ThreadPoolExecutor exec = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L,
            java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.PriorityBlockingQueue<Runnable>());
    private static final java.util.concurrent.atomic.AtomicLong SEQ = new java.util.concurrent.atomic.AtomicLong();

    private static final class PTask implements Runnable, Comparable<PTask> {
        final int prio;
        final long seq = SEQ.getAndIncrement();
        final Runnable r;
        PTask(int prio, Runnable r) { this.prio = prio; this.r = r; }
        @Override public void run() { r.run(); }
        @Override public int compareTo(PTask o) { return prio != o.prio ? Integer.compare(prio, o.prio) : Long.compare(seq, o.seq); }
    }

    private void run(int prio, Runnable r) {
        try { exec.execute(new PTask(prio, r)); } catch (RuntimeException ignored) { /* fechado */ }
    }
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(80 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };
    private final HashSet<String> pending = new HashSet<>();
    private final HashMap<Integer, Integer> bestWidth = new HashMap<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint paperPaint = new Paint();
    private final RectF rl = new RectF(), rr = new RectF(), tmp = new RectF();
    private final ColorMatrixColorFilter paperFilter = new ColorMatrixColorFilter(new ColorMatrix(new float[]{
            1f, 0f, 0f, 0f, 0f, 0f, 0.95f, 0f, 0f, 0f, 0f, 0f, 0.82f, 0f, 0f, 0f, 0f, 0f, 1f, 0f}));
    private final ColorMatrixColorFilter nightFilter = new ColorMatrixColorFilter(new ColorMatrix(new float[]{
            -1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f}));
    private PdfRenderer renderer;
    private ParcelFileDescriptor pfd;
    private Listener listener;
    private volatile boolean opened;
    private int pageCount, left, theme, needW = 800;
    private float aspect = 0.707f, zoom = 1f, panX, panY;
    private boolean two;
    private float flipP = -1f;
    private int flipDir;
    private long flipStart;
    private float downX, downY, lastX, lastY;
    private boolean dragged, flipped;

    private android.view.ScaleGestureDetector pinch;

    BookView(Context c) {
        super(c);
        pinch = new android.view.ScaleGestureDetector(c, new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(android.view.ScaleGestureDetector d) {
                scaling = true;
                return true;
            }

            @Override public void onScaleEnd(android.view.ScaleGestureDetector d) {
                scaling = false;
                invalidate();                                                    // agora pede a página nítida
            }

            @Override public boolean onScale(android.view.ScaleGestureDetector d) {
                zoom = Math.max(1f, Math.min(5f, zoom * d.getScaleFactor()));    // pinça com os dedos
                if (zoom <= 1.01f) { zoom = 1f; panX = 0f; panY = 0f; }
                invalidate();
                return true;
            }
        });
        dens = c.getResources().getDisplayMetrics().density;
        two = c.getResources().getDisplayMetrics().widthPixels > c.getResources().getDisplayMetrics().heightPixels; // paisagem: duas páginas
    }

    void open(final ParcelFileDescriptor file, final int startLeft, Listener l) {
        listener = l;
        left = startLeft;
        run(-5, () -> {
            try {
                PdfRenderer r = new PdfRenderer(file);
                int n = r.getPageCount();
                PdfRenderer.Page p0 = r.openPage(0);
                float asp = p0.getWidth() / (float) p0.getHeight();
                p0.close();
                renderer = r;
                pfd = file;
                main.post(() -> {
                    pageCount = n;
                    aspect = asp;
                    left = fix(left);
                    opened = true;
                    notifyInfo();
                    prefetch();
                    invalidate();
                });
            } catch (Exception e) {
                main.post(() -> { if (listener != null) listener.onError("Não consegui abrir esse PDF"); });
            }
        });
    }

    void close() {
        run(1000, () -> {
            try {
                if (renderer != null) renderer.close();
                if (pfd != null) pfd.close();
            } catch (Exception ignored) {
                // já fechado
            }
        });
        exec.shutdown();
        cache.evictAll();
    }

    int currentLeft() { return left; }

    int pageCount() { return pageCount; }

    private int fix(int v) {
        if (two) {
            v = Math.max(-1, Math.min(v, pageCount - 1));
            if (v >= 0 && v % 2 == 0) v--; // as páginas da esquerda são as ímpares (a 1ª página fica sozinha, na direita)
            return v;
        }
        return Math.max(0, Math.min(v, pageCount - 1));
    }

    private void notifyInfo() {
        if (listener == null) return;
        int a = Math.max(1, left + 1), b = Math.min(pageCount, left + 2);
        listener.onPageInfo(two && b > a ? "Páginas " + a + "-" + b + " de " + pageCount : "Página " + a + " de " + pageCount);
    }

    void next() {
        if (!opened || flipP >= 0f || fix(left + (two ? 2 : 1)) == left) return;
        startFlip(1);
    }

    void prev() {
        if (!opened || flipP >= 0f || fix(left - (two ? 2 : 1)) == left) return;
        startFlip(-1);
    }

    private void startFlip(int dir) {
        flipDir = dir;
        flipStart = SystemClock.uptimeMillis();
        flipP = 0f;
        invalidate();
    }

    void goTo(int page1) {
        if (!opened) return;
        int p = Math.max(0, Math.min(page1 - 1, pageCount - 1));
        left = fix(two ? (p % 2 == 1 ? p : p - 1) : p);
        notifyInfo();
        prefetch();
        invalidate();
    }

    /** Botão da lupa: 1x -> 2x -> 3,5x -> volta. */
    void zoomCycle() {
        zoom = zoom < 1.3f ? 2f : (zoom < 2.6f ? 3.5f : 1f);
        if (zoom <= 1.01f) { panX = 0f; panY = 0f; }
        invalidate();
    }

    void zoomBy(float f) {
        zoom = Math.max(1f, Math.min(5f, zoom * f));
        if (zoom <= 1.01f) { zoom = 1f; panX = 0f; panY = 0f; }
        invalidate();
    }

    void toggleTwo() {
        two = !two;
        left = fix(Math.max(left, two ? -1 : 0));
        notifyInfo();
        invalidate();
    }

    void cycleTheme() {
        theme = (theme + 1) % 3; // normal, papel creme, noturno
        invalidate();
    }

    // ---------- páginas ----------
    // Larguras fixas (em vez de uma por zoom): na pinça o zoom muda a cada quadro e antes isso enchia o cache de
    // páginas quase iguais, expulsava as boas e a página ficava PRETA até a nova chegar.
    private static final int[] LEVELS = {500, 900, 1400, 1900, 2400};
    private boolean scaling;

    private int levelFor(float rectW) {
        int cap = (!two && zoom > 1.6f) ? 2400 : 1900;   // em duas páginas o limite é menor (cada página pesa ~20 MB)
        int pick = LEVELS[0];
        for (int l : LEVELS) {
            if (l > cap) break;
            pick = l;
            if (l >= rectW) break;
        }
        return pick;
    }

    /** Qualquer versão que já exista da página, a mais parecida com a pedida (maiores primeiro). */
    private Bitmap nearest(int page, int level) {
        Bitmap b = cache.get(page + "@" + level);
        if (b != null) return b;
        int idx = 0;
        for (int i = 0; i < LEVELS.length; i++) if (LEVELS[i] == level) idx = i;
        for (int dist = 1; dist < LEVELS.length; dist++) {
            if (idx + dist < LEVELS.length) { b = cache.get(page + "@" + LEVELS[idx + dist]); if (b != null) return b; }
            if (idx - dist >= 0) { b = cache.get(page + "@" + LEVELS[idx - dist]); if (b != null) return b; }
        }
        return null;
    }

    private Bitmap bitmapFor(int page, float rectW) {
        int need = levelFor(rectW);
        needW = need;
        Bitmap exact = cache.get(page + "@" + need);
        if (exact != null) return exact;
        Bitmap any = nearest(page, need);              // enquanto a nítida não chega, mostra a que já tinha
        if (any == null) request(page, 500);            // 1º uma versão rápida (aparece quase na hora)
        if (!scaling && need > 500) request(page, need); // durante a pinça não pede páginas pesadas; pede quando soltar
        return any;
    }

    private void request(final int page, final int width) {
        final String key = page + "@" + width;
        if (renderer == null && !opened) return;
        if (!pending.add(key)) return;
        final boolean visible = page >= left && page <= left + 1;
        run((visible ? 0 : 4) + (width <= 500 ? 0 : 1), () -> {
            Bitmap bm = null;
            try {
                PdfRenderer.Page pg = renderer.openPage(page);
                try {
                    int hPx = Math.round(width * (float) pg.getHeight() / pg.getWidth());
                    bm = Bitmap.createBitmap(width, hPx, Bitmap.Config.ARGB_8888);
                    bm.eraseColor(0xFFFFFFFF);
                    pg.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                } finally {
                    pg.close();
                }
            } catch (Exception | OutOfMemoryError e) {
                bm = null;
            }
            final Bitmap res = bm;
            main.post(() -> {
                pending.remove(key);
                if (res != null) {
                    cache.put(key, res);
                    Integer old = bestWidth.get(page);
                    if (old == null || old < width) bestWidth.put(page, width);
                    invalidate();
                }
            });
        });
    }

    private void prefetch() {
        int w = Math.max(needW, 900);   // sempre um dos tamanhos fixos
        for (int pg = left; pg <= left + 5; pg++) if (pg >= 0 && pg < pageCount) request(pg, 500);
        if (left - 1 >= 0) request(left - 1, 500);
        for (int pg = left; pg <= left + 3; pg++) if (pg >= 0 && pg < pageCount) request(pg, w);
        if (left - 1 >= 0) request(left - 1, w);
    }

    private int paperColor() { return theme == 0 ? 0xFFFFFFFF : theme == 1 ? 0xFFF2E4C4 : 0xFF111111; }

    private void drawPage(Canvas c, int page, RectF rect) {
        paperPaint.setColor(paperColor());
        c.drawRect(rect, paperPaint);
        if (page < 0 || page >= pageCount) return;
        Bitmap b = bitmapFor(page, Math.max(rect.width(), 40f));
        if (b == null) return;
        bmpPaint.setColorFilter(theme == 1 ? paperFilter : theme == 2 ? nightFilter : null);
        c.drawBitmap(b, null, rect, bmpPaint);
        bmpPaint.setColorFilter(null);
    }

    // ---------- desenho ----------
    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, 0, h, 0xFF2E1D11, 0xFF120A05, Shader.TileMode.CLAMP)); // mesa de madeira escura
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        if (!opened) {
            paint.setColor(0xFFEFE6CC);
            paint.setTextSize(22 * dens);
            paint.setTextAlign(Paint.Align.CENTER);
            c.drawText("Abrindo o livro…", w / 2f, h / 2f, paint);
            return;
        }
        if (flipP >= 0f) {
            flipP = (SystemClock.uptimeMillis() - flipStart) / 480f;
            if (flipP >= 1f) {
                left = fix(left + (two ? 2 : 1) * flipDir);
                flipP = -1f;
                notifyInfo();
                prefetch();
            } else {
                postInvalidateOnAnimation();
            }
        }
        float pad = 14 * dens;
        float pageH = two ? Math.min(h - 2 * pad, (w - 2 * pad) / 2f / aspect) : Math.min(h - 2 * pad, (w - 2 * pad) / aspect);
        pageH *= zoom;
        float pageW = pageH * aspect, bookW = two ? 2 * pageW : pageW;
        float mx = Math.max(0f, (bookW - w) / 2f + pad), my = Math.max(0f, (pageH - h) / 2f + pad);
        panX = Math.max(-mx, Math.min(mx, panX));
        panY = Math.max(-my, Math.min(my, panY));
        float cx = w / 2f + panX, top = (h - pageH) / 2f + panY;
        float bl = two ? cx - pageW : cx - pageW / 2f, br = bl + bookW;
        drawBody(c, bl, br, top, pageH);
        if (two) {
            rl.set(cx - pageW, top, cx, top + pageH);
            rr.set(cx, top, cx + pageW, top + pageH);
            if (flipP < 0f) {
                drawPage(c, left, rl);
                drawPage(c, left + 1, rr);
            } else {
                drawFlipTwo(c, pageW, top, pageH, cx);
            }
            paint.setShader(new LinearGradient(cx - 30 * dens, 0, cx, 0, 0x00000000, 0x80000000, Shader.TileMode.CLAMP)); // sombra do miolo
            c.drawRect(cx - 30 * dens, top, cx, top + pageH, paint);
            paint.setShader(new LinearGradient(cx, 0, cx + 30 * dens, 0, 0x80000000, 0x00000000, Shader.TileMode.CLAMP));
            c.drawRect(cx, top, cx + 30 * dens, top + pageH, paint);
            paint.setShader(null);
        } else {
            rl.set(bl, top, br, top + pageH);
            if (flipP < 0f) drawPage(c, left, rl);
            else drawFlipOne(c);
        }
    }

    /** Capa de couro, sombra no chão, e as bordas das folhas empilhadas. */
    private void drawBody(Canvas c, float l, float r, float top, float ph) {
        float cv = 9 * dens;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x88000000);
        tmp.set(l - cv + 6 * dens, top - cv + 8 * dens, r + cv + 6 * dens, top + ph + cv + 8 * dens);
        c.drawRoundRect(tmp, 8 * dens, 8 * dens, paint);
        paint.setColor(0xFF5A1E14);
        tmp.set(l - cv, top - cv, r + cv, top + ph + cv);
        c.drawRoundRect(tmp, 8 * dens, 8 * dens, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.6f * dens);
        paint.setColor(0xFFC9A24B);
        tmp.set(l - cv + 3 * dens, top - cv + 3 * dens, r + cv - 3 * dens, top + ph + cv - 3 * dens);
        c.drawRoundRect(tmp, 6 * dens, 6 * dens, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFE9DFC8);
        for (int i = 1; i <= 3; i++) { // folhas empilhadas dos dois lados
            tmp.set(l - i * 2f * dens, top + i * dens, l, top + ph - i * dens);
            c.drawRect(tmp, paint);
            tmp.set(r, top + i * dens, r + i * 2f * dens, top + ph - i * dens);
            c.drawRect(tmp, paint);
        }
    }

    private void shade(Canvas c, RectF r, float amount) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor((int) (Math.max(0f, Math.min(1f, amount)) * 150) << 24);
        c.drawRect(r, paint);
    }

    /** A folha vira ao redor do miolo: encolhe até ficar de pé e cresce do outro lado. */
    private void drawFlipTwo(Canvas c, float pw, float top, float ph, float cx) {
        float p = flipP, cos = Math.abs(1f - 2f * p), dark = 1f - cos;
        rl.set(cx - pw, top, cx, top + ph);
        rr.set(cx, top, cx + pw, top + ph);
        if (flipDir > 0) {
            drawPage(c, left, rl);
            drawPage(c, left + 3, rr);
            if (p < 0.5f) {
                tmp.set(cx, top, cx + pw * cos, top + ph);
                drawPage(c, left + 1, tmp);
            } else {
                tmp.set(cx - pw * cos, top, cx, top + ph);
                drawPage(c, left + 2, tmp);
            }
        } else {
            drawPage(c, left - 2, rl);
            drawPage(c, left + 1, rr);
            if (p < 0.5f) {
                tmp.set(cx - pw * cos, top, cx, top + ph);
                drawPage(c, left, tmp);
            } else {
                tmp.set(cx, top, cx + pw * cos, top + ph);
                drawPage(c, left - 1, tmp);
            }
        }
        shade(c, tmp, dark * 0.8f);
    }

    private void drawFlipOne(Canvas c) {
        float p = flipP, cos = 1f - 2f * p;
        drawPage(c, flipDir > 0 ? left + 1 : left - 1, rl);
        if (cos > 0f) {
            tmp.set(flipDir > 0 ? rl.left : rl.right - rl.width() * cos, rl.top, flipDir > 0 ? rl.left + rl.width() * cos : rl.right, rl.bottom);
            drawPage(c, left, tmp);
            shade(c, tmp, (1f - cos) * 0.8f);
        }
    }

    // ---------- toques ----------
    @Override public boolean onTouchEvent(MotionEvent e) {
        pinch.onTouchEvent(e);
        if (pinch.isInProgress() || e.getPointerCount() > 1) {   // dois dedos: só dá zoom (não vira a página nem abre a barra)
            dragged = true;
            flipped = true;
            lastX = e.getX();
            lastY = e.getY();
            return true;
        }
        if (e.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
            int idx = e.getActionIndex() == 0 ? 1 : 0;
            lastX = e.getX(idx);
            lastY = e.getY(idx);
            return true;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX();
                downY = lastY = e.getY();
                dragged = false;
                flipped = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - lastX, dy = e.getY() - lastY;
                lastX = e.getX();
                lastY = e.getY();
                if (Math.hypot(e.getX() - downX, e.getY() - downY) > 12 * dens) dragged = true;
                if (zoom > 1.01f) {
                    panX += dx;
                    panY += dy;
                    invalidate();
                } else if (!flipped && Math.abs(e.getX() - downX) > 90 * dens) { // arrastar vira a página
                    flipped = true;
                    if (e.getX() < downX) next(); else prev();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (!dragged && opened) {
                    float x = e.getX() / Math.max(1f, getWidth());
                    if (zoom > 1.01f || (x >= 0.33f && x <= 0.67f)) { if (listener != null) listener.onTapCenter(); }
                    else if (x < 0.33f) prev();
                    else next();
                }
                return true;
            default:
                return true;
        }
    }
}
