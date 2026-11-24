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
import android.net.Uri;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Map;
import java.util.Random;

/**
 * Painel 2: a Mini Bíblia (edição do Gilberto). Capa de couro -> livros -> capítulos -> leitura.
 * Toca a dublagem por capítulo (arquivos numerados que o Gilberto importa), acompanha o texto, pausa, pula e marca o que foi lido.
 * Medalha da Bíblia completa só aparece na capa; as medalhas OURO e RGB dos patos continuam na capa também.
 */
final class BiblePanel extends View {
    private static final float GAP = 28f;
    private static final float PW = 232f, PH = 360f;
    private static final float TL = 14, TT = 48, TR = 218, TB = 350, ROW = 30;
    private static final float RT = 296;                      // fim da área de texto no leitor
    private static final int COVER = 0, BOOKS = 1, CHAPS = 2, READER = 3;
    // ações dos botões
    private static final int A_CLOSE = 1, A_BACK = 2, A_BIG = 3, A_CONT = 4, A_BOOKS = 5, A_PREV = 6, A_PLAY = 7, A_STOP = 8,
            A_NEXT = 9, A_FDN = 10, A_FUP = 11, A_FOLLOW = 12, A_SEEK = 13;

    private final Context ctx;
    private final WindowManager wm;
    private final boolean viaAccessibility;
    private final SharedPreferences sp;
    private final float dens, d;
    private final int w, h;
    private final WindowManager.LayoutParams lp;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bodyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Matrix matrix = new Matrix();
    private Bitmap plateBmp, woodBmp, pipe;
    private BitmapShader plate, wood;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean showing, closing;
    private float finalX;
    Runnable onResize;

    private volatile Verses verses;
    private final BibleAudio audio;
    private int screen = COVER, selBook, selChap;
    private float listScroll, textScroll, startScroll;
    private StaticLayout layout;
    private int[] vStart = new int[0], vEnd = new int[0];
    private int hiVerse = -1;
    private ArrayList<int[]> rows = new ArrayList<>();       // {tipo(0 cab,1 livro), índice}

    private boolean follow = true;
    private long followPausedUntil;
    private int pendingBook = -1, pendingChap = -1;

    private int mode, hitAction;
    private float downX, downY;
    private boolean moved;

    BiblePanel(Context ctx, WindowManager wm, boolean viaAccessibility, SharedPreferences sp) {
        super(ctx);
        this.ctx = ctx;
        this.wm = wm;
        this.viaAccessibility = viaAccessibility;
        this.sp = sp;
        this.audio = BibleAudio.get(ctx);
        dens = ctx.getResources().getDisplayMetrics().density;
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(dm);
        float base = dens * Math.max(0.5f, Math.min(1f, sp.getInt("panel_scale", 80) / 100f)) * 0.9f;
        float big = sp.getBoolean("bible_big", false) ? 1.7f : 1f;
        d = Math.min(base * big, Math.min(0.5f * dm.widthPixels / (PW + GAP), 0.96f * dm.heightPixels / PH));
        w = Math.round((PW + GAP) * d);
        h = Math.round(PH * d);
        tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        bodyPaint.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        woodBmp = makeWood();
        wood = new BitmapShader(woodBmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        try (InputStream in = ctx.getAssets().open("parchment.jpg")) {
            plateBmp = BitmapFactory.decodeStream(in);
            if (plateBmp != null) plate = new BitmapShader(plateBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        } catch (java.io.IOException | RuntimeException ignored) {
            plate = null;
        }
        try (InputStream in = ctx.getAssets().open("pipe.png")) {
            Bitmap src = BitmapFactory.decodeStream(in);
            if (src != null) {
                Matrix rot = new Matrix();
                rot.postRotate(90);
                pipe = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), rot, true);
            }
        } catch (java.io.IOException | RuntimeException ignored) {
            pipe = null;
        }
        lp = new WindowManager.LayoutParams(w, h,
                viaAccessibility ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
    }

    // ---------- ciclo de vida ----------
    void toggle(float panelX, float panelY, float panelW, float panelH) {
        if (!showing) show(panelX, panelY); else if (!closing) close();
    }

    void show(float panelX, float panelY) {
        if (showing) return;
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(dm);
        finalX = Math.max(0f, panelX + 6 * d - w);
        lp.x = Math.round(finalX);
        lp.y = Math.max(0, Math.min(dm.heightPixels - h, Math.round(panelY)));
        setPivotX(w);
        setPivotY(h / 2f);
        setTranslationX(w - 28 * d);
        setAlpha(0f);
        try {
            wm.addView(this, lp);
            showing = true;
            closing = false;
        } catch (RuntimeException e) {
            return;
        }
        setLayerType(LAYER_TYPE_HARDWARE, null);   // desliza pela GPU: sem engasgar
        animate().translationX(0f).alpha(1f).setDuration(300).setInterpolator(new DecelerateInterpolator(2f))
                .withEndAction(() -> setLayerType(LAYER_TYPE_NONE, null)).start();
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            Verses v = Verses.shared(app);
            audio.reload();
            post(() -> {
                verses = v;
                if (v != null) {
                    selBook = Math.min(v.bookCount() - 1, sp.getInt("bible_book", 0));
                    selChap = Math.min(v.chapterCount(selBook) - 1, sp.getInt("bible_chap", 0));
                    buildRows();
                    if (pendingBook >= 0) { int pb = pendingBook, pc = pendingChap; pendingBook = -1; openChapter(pb, pc); }
                }
                audio.listener = audioListener;
                invalidate();
            });
        }).start();
    }

    void close() {
        if (!showing || closing) return;
        closing = true;
        audio.listener = null;
        setLayerType(LAYER_TYPE_HARDWARE, null);
        animate().translationX(w - 28 * d).alpha(0f).setDuration(240).setInterpolator(new DecelerateInterpolator(1.5f))
                .withEndAction(() -> {
                    showing = false;
                    closing = false;
                    try { wm.removeView(BiblePanel.this); } catch (RuntimeException ignored) { /* já saiu */ }
                }).start();
    }

    void closeNow() {
        handler.removeCallbacksAndMessages(null);
        animate().cancel();
        audio.listener = null;
        if (!showing) return;
        showing = false;
        closing = false;
        try { wm.removeView(this); } catch (RuntimeException ignored) { /* já saiu */ }
    }

    // ---------- dados ----------
    private void buildRows() {
        rows.clear();
        Verses v = verses;
        if (v == null) return;
        boolean ntDone = false;
        rows.add(new int[]{0, 0});
        for (int b = 0; b < v.bookCount(); b++) {
            if (!ntDone && v.isNewTestament(b)) { rows.add(new int[]{0, 1}); ntDone = true; }
            rows.add(new int[]{1, b});
        }
    }

    private boolean isRead(int g) { return audio.isRead(g); }

    private boolean complete() { return audio.readCount() >= 1189; }

    boolean isShowing() { return showing && !closing; }

    /** A cada 30 min o app abre a Bíblia direto no capítulo da vez. */
    void openAt(int b, int c) {
        if (verses == null) { pendingBook = b; pendingChap = c; return; }
        openChapter(b, c);
    }

    private final BibleAudio.Listener audioListener = new BibleAudio.Listener() {
        @Override public void onState() { post(() -> invalidate()); startTick(); }
        @Override public void onChapter(int pb, int pc) {
            post(() -> { if (screen == READER && selBook == pb && selChap == pc) openChapter(audio.book, audio.chap); });
        }
    };

    private int readInBook(int b) {
        Verses v = verses;
        int n = 0;
        for (int c = 0; v != null && c < v.chapterCount(b); c++) if (isRead(v.globalChapter(b, c))) n++;
        return n;
    }

    private void openChapter(int b, int c) {
        Verses v = verses;
        if (v == null) return;
        selBook = b;
        selChap = c;
        screen = READER;
        sp.edit().putInt("bible_book", b).putInt("bible_chap", c).apply();
        buildText();
        textScroll = 0;
        hiVerse = -1;
        invalidate();
    }

    private static int lift(int c) { // cores das falas ficam mais claras no fundo escuro do leitor
        return Color.rgb(Color.red(c) + (255 - Color.red(c)) * 45 / 100, Color.green(c) + (255 - Color.green(c)) * 45 / 100,
                Color.blue(c) + (255 - Color.blue(c)) * 45 / 100);
    }

    private void buildText() {
        Verses v = verses;
        if (v == null) return;
        int first = v.chapterFirst(selBook, selChap), len = v.chapterLength(selBook, selChap);
        int god = lift(sp.getInt("col_god", 0xFF0A3CA8)), jesus = lift(sp.getInt("col_jesus", 0xFFC00018)),
                prophet = lift(sp.getInt("col_prophet", 0xFF9A6A00)), disc = lift(sp.getInt("col_disciple", 0xFF0B5D1E));
        boolean colors = sp.getBoolean("bible_colors", true);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        vStart = new int[len];
        vEnd = new int[len];
        for (int i = 0; i < len; i++) {
            vStart[i] = sb.length();
            String num = v.verseOf(first + i) + " ";
            sb.append(num);
            sb.setSpan(new ForegroundColorSpan(0xFFFFC94D), vStart[i], sb.length(), 0);
            sb.setSpan(new RelativeSizeSpan(0.72f), vStart[i], sb.length(), 0);
            int base = sb.length();
            sb.append(v.text(first + i));
            if (colors) for (int[] s : VerseStyler.spans(v, first + i, god, jesus, prophet, disc))
                sb.setSpan(new ForegroundColorSpan(s[2]), base + s[0], base + s[1], 0);
            vEnd[i] = sb.length();
            sb.append('\n');
        }
        float fs = Math.max(8f, Math.min(30f, sp.getInt("bible_font", 13)));
        bodyPaint.setTextSize(fs);
        bodyPaint.setColor(0xFFF4ECD8);
        layout = StaticLayout.Builder.obtain(sb, 0, sb.length(), bodyPaint, Math.round(TR - TL - 14))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.1f).build();
    }

    private float maxText() { return layout == null ? 0f : Math.max(0f, layout.getHeight() + 8 - (RT - TT)); }

    // ---------- áudio (quem toca é o BibleAudio; o painel só mostra e comanda) ----------
    private void playChapter(int b, int c) {
        openChapter(b, c);
        audio.listener = audioListener;
        audio.play(b, c, true);
    }

    private void startTick() { handler.removeCallbacks(tickRun); handler.post(tickRun); }

    private final Runnable tickRun = this::tick;

    private void tick() {
        handler.removeCallbacks(tickRun);
        if (!showing || !audio.isPlaying()) return;
        if (audio.book == selBook && audio.chap == selChap && layout != null && vStart.length > 0) {
            float frac = audio.position() / (float) audio.duration();
            long total = 0;
            for (int i = 0; i < vStart.length; i++) total += vEnd[i] - vStart[i];
            float target = frac * total;
            long acc = 0;
            int k = 0;
            for (int i = 0; i < vStart.length; i++) { acc += vEnd[i] - vStart[i]; k = i; if (acc >= target) break; }
            hiVerse = k;
            if (follow && SystemClockNow() > followPausedUntil) {
                float want = layout.getLineTop(layout.getLineForOffset(vStart[k])) - 24f;
                want = Math.max(0f, Math.min(maxText(), want));
                textScroll += (want - textScroll) * 0.10f;
            }
        }
        invalidate();
        handler.postDelayed(tickRun, 33);
    }

    private static long SystemClockNow() { return android.os.SystemClock.uptimeMillis(); }

    private static String clock(int ms) {
        int s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
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


    // ---------- desenho ----------
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


        // topo: título, voltar, tamanho grande, fechar
        Verses v = verses;
        String title = screen == COVER ? "BÍBLIA" : screen == BOOKS ? "LIVROS" : (v != null ? v.bookName(selBook) : "");
        if (screen == READER && v != null) title += " " + (selChap + 1);
        float tx = screen == COVER ? PW / 2f : 118f;
        fitText(c, title, tx, 31, 17, screen == COVER ? 150f : 110f, 0xFFFFD27A, Paint.Align.CENTER);
        if (screen != COVER) roundBtn(c, 24, 24, "\u2190");
        roundBtn(c, 176, 24, sp.getBoolean("bible_big", false) ? "\u2198" : "\u2922");
        roundBtn(c, 208, 24, "\u2715");

        if (v == null) {
            text(c, "carregando…", PW / 2f, 160, 12, 0xFFB0B0B0, Paint.Align.CENTER, false);
        } else if (screen == COVER) {
            drawCover(c, v);
        } else if (screen == BOOKS) {
            drawBooks(c, v);
        } else if (screen == CHAPS) {
            drawChapters(c, v);
        } else {
            drawReader(c, v);
        }
        drawPipes(c);
        c.restore();
    }

    private void roundBtn(Canvas c, float cx, float cy, String glyph) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF050505);
        c.drawCircle(cx, cy, 11, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.6f);
        p.setColor(0xFFE8A33D);
        c.drawCircle(cx, cy, 10.2f, p);
        text(c, glyph, cx, cy + 5, 13, 0xFFEFE6CC, Paint.Align.CENTER, false);
    }

    private void fitText(Canvas c, String s, float x, float y, float size, float maxW, int color, Paint.Align al) {
        tp.setTextSize(size);
        tp.setFakeBoldText(true);
        float wd = tp.measureText(s);
        if (wd > maxW) size = size * maxW / wd;
        tp.setFakeBoldText(false);
        text(c, s, x, y, size, color, al, true);
    }

    private void button(Canvas c, float l, float t, float rt, float b, String label, int color, float size) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, t, 0, b, 0xFF2A2A2A, 0xFF050505, Shader.TileMode.CLAMP));
        r.set(l, t, rt, b);
        c.drawRoundRect(r, 8, 8, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.4f);
        p.setColor(color);
        c.drawRoundRect(r, 8, 8, p);
        text(c, label, (l + rt) / 2f, (t + b) / 2f + size * 0.36f, size, color, Paint.Align.CENTER, true);
    }

    private void drawCover(Canvas c, Verses v) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, TT - 2, PW, TB, 0xFF6E2A1A, 0xFF3A130B, Shader.TileMode.CLAMP));
        r.set(TL, TT - 2, TR, TB);
        c.drawRoundRect(r, 10, 10, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.6f);
        p.setColor(0xFFD9A441);
        r.set(TL + 5, TT + 3, TR - 5, TB - 5);
        c.drawRoundRect(r, 7, 7, p);
        // medalhas: ouro e RGB dos patos (sempre aqui) e a da Bíblia completa (só quando completa)
        drawPetMedal(c, 52, 86, sp.getBoolean("medal_gold", false), false);
        drawPetMedal(c, 180, 86, sp.getBoolean("medal_rgb", false), true);
        if (complete()) {
            p.setStyle(Paint.Style.FILL);
            p.setShader(new RadialGradient(110, 80, 34, 0xFFFFF3B0, 0xFFC8901A, Shader.TileMode.CLAMP));
            c.drawCircle(116, 86, 25, p);
            p.setShader(null);
            text(c, "\u271D", 116, 96, 26, 0xFF3A130B, Paint.Align.CENTER, false);
        }
        text(c, "OURO", 52, 122, 9, 0xFFEFE6CC, Paint.Align.CENTER, true);
        text(c, "RGB", 180, 122, 9, 0xFFEFE6CC, Paint.Align.CENTER, true);
        if (complete()) text(c, "COMPLETA", 116, 122, 9, 0xFFFFD27A, Paint.Align.CENTER, true);
        text(c, "BÍBLIA", PW / 2f, 160, 30, 0xFFFFD27A, Paint.Align.CENTER, true);
        text(c, "Edição por Gilberto", PW / 2f, 180, 13.5f, 0xFFFFE9B0, Paint.Align.CENTER, true);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.2f);
        p.setColor(0xFFD9A441);
        c.drawLine(50, 190, 182, 190, p);
        text(c, "texto-base NTLH · nomes ajustados", PW / 2f, 204, 8.6f, 0xFFE6D3A8, Paint.Align.CENTER, false);
        text(c, "Capítulos lidos/ouvidos: " + audio.readCount() + " / 1189", PW / 2f, 228, 10, 0xFFFFFFFF, Paint.Align.CENTER, true);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x55FFFFFF);
        r.set(30, 233, 202, 237);
        c.drawRoundRect(r, 2, 2, p);
        p.setColor(0xFF7CFF8A);
        r.set(30, 233, 30 + 172 * audio.readCount() / 1189f, 237);
        c.drawRoundRect(r, 2, 2, p);
        int n = audio.fileCount();
        text(c, n > 0 ? "Dublagem: " + n + " capítulos reconhecidos" : "Dublagem: nenhuma (importe em ▦ Opções)", PW / 2f, 254, 9.2f,
                n > 0 ? 0xFF7CFF8A : 0xFFFFB066, Paint.Align.CENTER, true);
        button(c, 28, 268, 204, 304, "CONTINUAR  " + shortRef(v), 0xFFFFD27A, 12.5f);
        button(c, 28, 310, 204, 342, "LIVROS", 0xFF9FE8FF, 13);
    }

    private String shortRef(Verses v) { return ""; }

    private void drawBooks(Canvas c, Verses v) {
        drawStrip(c, TL - 2, TT - 2, TR + 2, TB + 2);
        c.save();
        c.clipRect(TL, TT, TR, TB);
        for (int i = 0; i < rows.size(); i++) {
            float y = TT + i * ROW - listScroll;
            if (y + ROW < TT || y > TB) continue;
            int[] row = rows.get(i);
            if (row[0] == 0) {
                text(c, row[1] == 0 ? "VELHO TESTAMENTO" : "NOVO TESTAMENTO", PW / 2f, y + 19, 11, 0xFFFFD27A, Paint.Align.CENTER, true);
                continue;
            }
            int b = row[1], rd = readInBook(b), tot = v.chapterCount(b);
            boolean done = rd >= tot;
            fitText(c, v.bookName(b), TL + 8, y + 14, 11, 130f, done ? 0xFFFFD27A : 0xFFFFFFFF, Paint.Align.LEFT);
            text(c, rd + "/" + tot, TR - 8, y + 14, 10, done ? 0xFFFFD27A : 0xFFB0B0B0, Paint.Align.RIGHT, false);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x44FFFFFF);
            r.set(TL + 8, y + 19, TR - 8, y + 22);
            c.drawRoundRect(r, 1.5f, 1.5f, p);
            p.setColor(done ? 0xFFFFD27A : 0xFF7CFF8A);
            r.set(TL + 8, y + 19, TL + 8 + (TR - TL - 16) * (rd / (float) Math.max(1, tot)), y + 22);
            c.drawRoundRect(r, 1.5f, 1.5f, p);
        }
        c.restore();
    }

    private static final float CELL = 31f, CGAP = 3f;

    private void drawChapters(Canvas c, Verses v) {
        drawStrip(c, TL - 2, TT - 2, TR + 2, TB + 2);
        c.save();
        c.clipRect(TL, TT, TR, TB);
        int n = v.chapterCount(selBook);
        for (int i = 0; i < n; i++) {
            float x = TL + 3 + (i % 6) * (CELL + CGAP), y = TT + 4 + (i / 6) * (CELL + CGAP) - listScroll;
            if (y + CELL < TT || y > TB) continue;
            boolean rd = isRead(v.globalChapter(selBook, i));
            p.setStyle(Paint.Style.FILL);
            p.setColor(rd ? 0xFF1F5A2A : 0xFF2B2B2B);
            r.set(x, y, x + CELL, y + CELL);
            c.drawRoundRect(r, 6, 6, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.2f);
            p.setColor(rd ? 0xFF7CFF8A : 0x66FFFFFF);
            c.drawRoundRect(r, 6, 6, p);
            text(c, String.valueOf(i + 1), x + CELL / 2f, y + CELL / 2f + 4.5f, 12, 0xFFFFFFFF, Paint.Align.CENTER, true);
        }
        c.restore();
    }

    private void drawReader(Canvas c, Verses v) {
        drawStrip(c, TL - 2, TT - 2, TR + 2, RT + 2);
        c.save();
        c.clipRect(TL, TT, TR, RT);
        if (layout != null) {
            c.translate(TL + 7, TT + 4 - textScroll);
            if (hiVerse >= 0 && hiVerse < vStart.length && audio.book == selBook && audio.chap == selChap) {
                int l0 = layout.getLineForOffset(vStart[hiVerse]), l1 = layout.getLineForOffset(Math.max(vStart[hiVerse], vEnd[hiVerse] - 1));
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x44FFD27A);
                r.set(-5, layout.getLineTop(l0) - 1, TR - TL - 9, layout.getLineBottom(l1) + 1);
                c.drawRoundRect(r, 4, 4, p);
            }
            layout.draw(c);
        }
        c.restore();
        // barra de tempo (toque pra pular) e estado do áudio
        boolean here = audio.book == selBook && audio.chap == selChap;
        float frac = 0f;
        String info = here ? audio.status : "";
        if (audio.isPrepared()) {
            int dur = audio.duration(), pos = audio.position();
            frac = pos / (float) dur;
            info = (audio.isPlaying() ? "tocando " : "pausado ") + (here ? "" : v.bookName(audio.book) + " " + (audio.chap + 1) + " · ") + clock(pos) + " / " + clock(dur);
        } else if (info.isEmpty()) {
            info = audio.hasAudio(v.globalChapter(selBook, selChap)) ? "toque ▶ para ouvir" : "sem áudio deste capítulo";
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x55FFFFFF);
        r.set(TL, RT + 6, TR, RT + 10);
        c.drawRoundRect(r, 2, 2, p);
        p.setColor(0xFFFFD27A);
        r.set(TL, RT + 6, TL + (TR - TL) * frac, RT + 10);
        c.drawRoundRect(r, 2, 2, p);
        text(c, info, PW / 2f, RT + 21, 9.5f, 0xFFEFE6CC, Paint.Align.CENTER, true);
        // botões: anterior, tocar/pausar, parar, próximo, letra menor, letra maior, seguir texto
        String[] lab = {"\u25C0", audio.isPlaying() ? "\u275A\u275A" : "\u25B6", "\u25A0", "\u25B6\u25B6", "A\u2212", "A+", follow ? "\u2316" : "\u2013"};
        int[] col = {0xFF9FE8FF, 0xFF7CFF8A, 0xFFFF8A8A, 0xFF9FE8FF, 0xFFFFD27A, 0xFFFFD27A, follow ? 0xFF7CFF8A : 0xFF888888};
        for (int i = 0; i < 7; i++) {
            float x = TL + i * 29.2f;
            button(c, x, 322, x + 26, 350, lab[i], col[i], i == 3 ? 9 : 12);
        }
    }

    /** Os dois canos de cobre que ligam este painel ao principal. */
    private void drawPipes(Canvas c) {
        if (pipe == null) return;
        p.setFilterBitmap(true);
        for (float y : new float[]{118f, 252f}) {
            r.set(PW - 8, y - 8, PW + GAP, y + 8);
            c.drawBitmap(pipe, null, r, p);
        }
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
    private int hit(float x, float y) {
        if (Math.hypot(x - 208, y - 24) < 15) return A_CLOSE;
        if (Math.hypot(x - 176, y - 24) < 15) return A_BIG;
        if (screen != COVER && Math.hypot(x - 24, y - 24) < 15) return A_BACK;
        if (screen == COVER) {
            if (y >= 268 && y <= 304 && x >= 28 && x <= 204) return A_CONT;
            if (y >= 310 && y <= 342 && x >= 28 && x <= 204) return A_BOOKS;
        } else if (screen == READER) {
            if (y >= 322 && y <= 350 && x >= TL && x < TL + 7 * 29.2f) {
                int i = (int) ((x - TL) / 29.2f);
                int[] acts = {A_PREV, A_PLAY, A_STOP, A_NEXT, A_FDN, A_FUP, A_FOLLOW};
                return acts[Math.min(6, i)];
            }
            if (y >= RT + 2 && y <= RT + 14 && x >= TL && x <= TR) return A_SEEK;
        }
        return 0;
    }

    private float maxList() {
        Verses v = verses;
        if (v == null) return 0;
        float total = screen == BOOKS ? rows.size() * ROW : 8 + ((v.chapterCount(selBook) + 5) / 6) * (CELL + CGAP);
        return Math.max(0f, total - (TB - TT));
    }

    private void step(int dir) {
        Verses v = verses;
        if (v == null) return;
        int b = selBook, c = selChap + dir;
        if (c < 0) { b--; if (b < 0) return; c = v.chapterCount(b) - 1; }
        else if (c >= v.chapterCount(b)) { b++; if (b >= v.bookCount()) return; c = 0; }
        if (audio.isActive()) playChapter(b, c); else openChapter(b, c);
    }

    private void act(int a, float x) {
        Verses v = verses;
        switch (a) {
            case A_CLOSE: close(); break;
            case A_BACK:
                if (screen == READER) { screen = CHAPS; listScroll = 0; }
                else if (screen == CHAPS) { screen = BOOKS; listScroll = 0; }
                else screen = COVER;
                invalidate();
                break;
            case A_BIG:
                sp.edit().putBoolean("bible_big", !sp.getBoolean("bible_big", false)).apply();
                if (onResize != null) onResize.run();
                break;
            case A_CONT: if (v != null) openChapter(selBook, selChap); break;
            case A_BOOKS: screen = BOOKS; listScroll = 0; invalidate(); break;
            case A_PREV: step(-1); break;
            case A_NEXT: step(1); break;
            case A_PLAY: if (!audio.togglePause()) playChapter(selBook, selChap); startTick(); invalidate(); break;
            case A_STOP: audio.stop(); audio.status = ""; invalidate(); break;
            case A_FDN: sp.edit().putInt("bible_font", Math.max(8, sp.getInt("bible_font", 13) - 1)).apply(); buildText(); invalidate(); break;
            case A_FUP: sp.edit().putInt("bible_font", Math.min(30, sp.getInt("bible_font", 13) + 1)).apply(); buildText(); invalidate(); break;
            case A_FOLLOW: follow = !follow; invalidate(); break;
            case A_SEEK:
                audio.seekFraction((x - TL) / (TR - TL));
                invalidate();
                break;
            default: break;
        }
    }

    private void tapContent(float x, float y) {
        Verses v = verses;
        if (v == null || y < TT || y > TB) return;
        if (screen == BOOKS) {
            int i = (int) ((y - TT + listScroll) / ROW);
            if (i >= 0 && i < rows.size() && rows.get(i)[0] == 1) { selBook = rows.get(i)[1]; screen = CHAPS; listScroll = 0; invalidate(); }
        } else if (screen == CHAPS) {
            int col = (int) ((x - TL - 3) / (CELL + CGAP)), row = (int) ((y - TT - 4 + listScroll) / (CELL + CGAP));
            int i = row * 6 + col;
            if (col >= 0 && col < 6 && i >= 0 && i < v.chapterCount(selBook)) openChapter(selBook, i);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX() / d, y = e.getY() / d;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                startScroll = screen == READER ? textScroll : listScroll;
                moved = false;
                hitAction = hit(x, y);
                mode = hitAction != 0 ? 1 : (x >= TL && x <= TR && y >= TT && y <= (screen == READER ? RT : TB) && screen != COVER ? 2 : 0);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(x - downX, y - downY) > 5) moved = true;
                if (mode == 2 && moved) {
                    if (screen == READER) {
                        textScroll = Math.max(0f, Math.min(maxText(), startScroll - (y - downY)));
                        followPausedUntil = SystemClockNow() + 3500;      // mexeu no texto: o "seguir" espera um pouco
                        if (!audio.isPlaying()) invalidate();
                    } else {
                        listScroll = Math.max(0f, Math.min(maxList(), startScroll - (y - downY)));
                    }
                    invalidate();
                } else if (mode == 1 && hitAction == A_SEEK) {
                    act(A_SEEK, x);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!moved) {
                    if (mode == 1 && hit(x, y) == hitAction) act(hitAction, x);
                    else if (mode == 2) tapContent(x, y);
                }
                if (screen == READER && textScroll >= maxText() - 2 && layout != null && !closing) {
                    Verses v = verses;
                    if (v != null && maxText() > 0) { audio.markRead(v.globalChapter(selBook, selChap)); invalidate(); }
                }
                mode = 0;
                return true;
            default:
                return true;
        }
    }
}
