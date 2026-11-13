package com.crtoverlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.json.JSONObject;

/**
 * A TV Philco flutuante: o vídeo toca dentro do vidro, com controles, lista de reprodução (por pasta)
 * e redimensionamento pelas quatro quinas. Tocar no vídeo mostra ou esconde os controles.
 */
final class TvView extends FrameLayout {
    interface Callback { void onClose(); }

    private static final int MODE_NONE = 0, MODE_MOVE = 1, MODE_RESIZE = 2, MODE_SEEK = 3, MODE_LIST = 4;

    private final Context ctx;
    private final WindowManager wm;
    private final SharedPreferences sp;
    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final float dens;
    private final int screenW, screenH;
    private final WindowManager.LayoutParams lp;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF(), dst = new RectF();
    private final FrameLayout glass;
    private final TextureView tex;

    private Bitmap frame;
    private float sl = 0.12f, st = 0.08f, sr = 0.88f, sb = 0.68f, aspect = 1.02f; // vidro, em fração da moldura

    // vídeo
    private Surface surface;
    private MediaPlayer mp;
    private boolean prepared, pendingStart, shuffle, seeking, released;
    private int errors, videoW, videoH, fitFor;
    private float seekFrac;
    private List<TvLibrary.Video> vids = new ArrayList<>();
    private int index;
    private String folder;

    // controles
    private boolean controlsOn, listOn, foldersTab;
    private int fillMode; // 0 = preencher a TV toda (corta as pontas), 1 = esticar, 2 = ajustar (faixas pretas)
    private float gl, gt, gr, gb, u, listScroll, listScrollStart;
    private final RectF rClose = new RectF(), rList = new RectF(), rShuffle = new RectF(), rPrev = new RectF(),
            rPlay = new RectF(), rNext = new RectF(), rSeek = new RectF(), rSeekHit = new RectF(),
            rListArea = new RectF(), rTabV = new RectF(), rTabF = new RectF(), rAspect = new RectF();

    // gestos
    private int mode, corner, startX, startY, startW, startH;
    private float downX, downY, rawDownX, rawDownY;
    private boolean movedFar;

    private final Runnable hideControls = () -> { if (!listOn) { controlsOn = false; invalidate(); } };
    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (released) return;
            if (controlsOn) invalidate();
            handler.postDelayed(this, 500);
        }
    };

    TvView(Context ctx, WindowManager wm, SharedPreferences sp, Callback callback) {
        super(ctx);
        this.ctx = ctx;
        this.wm = wm;
        this.sp = sp;
        this.callback = callback;
        dens = ctx.getResources().getDisplayMetrics().density;
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        setWillNotDraw(false);
        fillMode = sp.getInt("tv_fill", 0);
        tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        loadFrame();

        glass = new FrameLayout(ctx);
        glass.setBackgroundColor(0xFF000000);
        tex = new TextureView(ctx);
        glass.addView(tex, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        addView(glass);
        tex.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                surface = new Surface(st);
                if (pendingStart) { pendingStart = false; play(index); }
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                releasePlayer();
                if (surface != null) { surface.release(); surface = null; }
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });

        int w = Math.min(sp.getInt("tv_w", Math.min(Math.round(screenW * 0.5f), Math.round(380 * dens))), screenW);
        int h = Math.round(w / aspect);
        lp = new WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Math.max(0, Math.min(sp.getInt("tv_x", screenW - w - Math.round(16 * dens)), screenW - w));
        lp.y = Math.max(0, Math.min(sp.getInt("tv_y", Math.round(80 * dens)), screenH - h));
        handler.postDelayed(progressTick, 500);
    }

    WindowManager.LayoutParams layoutParams() { return lp; }

    private void loadFrame() {
        try (InputStream in = ctx.getAssets().open("bob_meta.json")) {
            byte[] buf = new byte[in.available()];
            int n = in.read(buf);
            JSONObject o = new JSONObject(new String(buf, 0, Math.max(0, n), "UTF-8")).getJSONObject("tv");
            sl = (float) o.getDouble("sl");
            st = (float) o.getDouble("st");
            sr = (float) o.getDouble("sr");
            sb = (float) o.getDouble("sb");
            aspect = (float) o.getDouble("aspect");
            try (InputStream img = ctx.getAssets().open(o.getString("file"))) {
                frame = BitmapFactory.decodeStream(img);
            }
        } catch (IOException | org.json.JSONException | RuntimeException ignored) {
            frame = null; // sem a moldura, só aparece o vídeo
        }
    }

    /** Abre a TV já na lista de playlists (pastas), pra escolher uma. */
    void openList() {
        listOn = true;
        foldersTab = true;
        controlsOn = true;
        listScroll = 0f;
        handler.removeCallbacks(hideControls);
        invalidate();
    }

    // ---------- playlist e vídeo ----------
    /** Abre uma pasta como playlist e começa a tocar a partir do vídeo "start" (0 = o primeiro). */
    void open(String tree, int start) {
        if (tree == null) return;
        folder = tree;
        MusicPlayer music = MusicPlayer.get(ctx);
        if (music.playing()) music.toggle(); // a música do app para enquanto a TV toca
        new Thread(() -> {
            final List<TvLibrary.Video> found = TvLibrary.videos(ctx, tree);
            handler.post(() -> {
                if (released) return;
                if (found.isEmpty()) {
                    Toast.makeText(ctx, "Nenhum vídeo nessa pasta", Toast.LENGTH_LONG).show();
                    return;
                }
                vids = found;
                errors = 0;
                play(Math.max(0, Math.min(start, vids.size() - 1)));
            });
        }).start();
    }

    private void play(int i) {
        if (vids.isEmpty()) return;
        index = ((i % vids.size()) + vids.size()) % vids.size();
        releasePlayer();
        if (surface == null) { pendingStart = true; return; }
        try {
            mp = new MediaPlayer();
            mp.setSurface(surface);
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
            mp.setDataSource(ctx, vids.get(index).uri);
            mp.setOnPreparedListener(m -> {
                prepared = true;
                errors = 0;
                m.start();
                fit(m.getVideoWidth(), m.getVideoHeight());
                showControls();
            });
            mp.setOnVideoSizeChangedListener((m, vw, vh) -> fit(vw, vh));
            mp.setOnCompletionListener(m -> next(true));
            mp.setOnErrorListener((m, what, extra) -> {
                errors++;
                if (errors < vids.size()) next(true);
                return true;
            });
            mp.prepareAsync();
        } catch (IOException | RuntimeException e) {
            errors++;
            if (errors < vids.size()) next(true);
        }
        invalidate();
    }

    private void next(boolean auto) {
        if (vids.isEmpty()) return;
        play(shuffle ? rnd.nextInt(vids.size()) : index + 1);
    }

    private void previous() {
        try {
            if (mp != null && prepared && mp.getCurrentPosition() > 3000) { mp.seekTo(0); return; }
        } catch (IllegalStateException ignored) { /* player ainda carregando */ }
        play(index - 1);
    }

    private void togglePause() {
        if (mp == null || !prepared) return;
        try {
            if (mp.isPlaying()) mp.pause(); else mp.start();
        } catch (IllegalStateException ignored) { /* player ainda carregando */ }
        invalidate();
    }

    private void releasePlayer() {
        prepared = false;
        if (mp != null) {
            try { mp.release(); } catch (RuntimeException ignored) { /* já liberado */ }
            mp = null;
        }
    }

    void release() {
        released = true;
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        if (surface != null) { surface.release(); surface = null; }
        if (frame != null) { frame.recycle(); frame = null; }
    }

    /** Encaixa o vídeo inteiro no vidro, com faixas pretas se a proporção for diferente. */
    private void fit(int vw, int vh) {
        videoW = vw;
        videoH = vh;
        fitFor = lp.width;
        if (vw <= 0 || vh <= 0) return;
        float gw = (sr - sl) * lp.width, gh = (sb - st) * lp.height;
        int tw, th;
        if (fillMode == 1) {
            tw = Math.round(gw);
            th = Math.round(gh);
        } else {
            float sc = fillMode == 0 ? Math.max(gw / vw, gh / vh) : Math.min(gw / vw, gh / vh);
            tw = Math.round(vw * sc);
            th = Math.round(vh * sc);
        }
        tex.setLayoutParams(new FrameLayout.LayoutParams(tw, th, Gravity.CENTER)); // o vidro corta o que passar
    }

    // ---------- medidas ----------
    @Override protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
        setMeasuredDimension(w, h);
        glass.measure(MeasureSpec.makeMeasureSpec(Math.round((sr - sl) * w), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.round((sb - st) * h), MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int rr, int b) {
        int w = rr - l, h = b - t;
        glass.layout(Math.round(sl * w), Math.round(st * h), Math.round(sr * w), Math.round(sb * h));
        if (fitFor != lp.width) post(() -> fit(videoW, videoH)); // a TV mudou de tamanho: re-encaixa o vídeo
    }

    // ---------- desenho ----------
    @Override protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        int w = getWidth(), h = getHeight();
        if (frame != null) {
            dst.set(0, 0, w, h);
            c.drawBitmap(frame, null, dst, bmp);
        }
        layoutRects(w, h);
        if (controlsOn) drawCorners(c, w, h);
        if (listOn) drawList(c);
        if (controlsOn || listOn) drawBars(c);
    }

    private void layoutRects(int w, int h) {
        gl = sl * w;
        gt = st * h;
        gr = sr * w;
        gb = sb * h;
        u = dens * Math.max(0.7f, Math.min(1.4f, (gr - gl) / (300 * dens)));
        float cy = gb - 24 * u, cx = (gl + gr) / 2f, bs = 22 * u;
        rClose.set(gr - 32 * u, gt + 3 * u, gr - 4 * u, gt + 31 * u);
        rList.set(gr - 64 * u, gt + 3 * u, gr - 36 * u, gt + 31 * u);
        rPlay.set(cx - bs, cy - bs, cx + bs, cy + bs);
        rPrev.set(cx - 56 * u - bs, cy - bs, cx - 56 * u + bs, cy + bs);
        rNext.set(cx + 56 * u - bs, cy - bs, cx + 56 * u + bs, cy + bs);
        rShuffle.set(gl + 6 * u, cy - bs, gl + 6 * u + 2 * bs, cy + bs);
        rAspect.set(gr - 6 * u - 2 * bs, cy - bs, gr - 6 * u, cy + bs);
        rSeek.set(gl + 44 * u, gb - 62 * u, gr - 44 * u, gb - 50 * u);
        rSeekHit.set(rSeek.left - 10 * u, rSeek.top - 12 * u, rSeek.right + 10 * u, rSeek.bottom + 12 * u);
        rListArea.set(gl, gt + 34 * u, gr, gb - 78 * u);
        rTabV.set(gl, gt + 34 * u, (gl + gr) / 2f, gt + 34 * u + 28 * u);
        rTabF.set((gl + gr) / 2f, gt + 34 * u, gr, gt + 34 * u + 28 * u);
    }

    private void drawCorners(Canvas c, int w, int h) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(3 * dens);
        p.setColor(0xE6FFFFFF);
        float m = 7 * dens, a = 16 * dens;
        c.drawLine(m, m + a, m, m, p); c.drawLine(m, m, m + a, m, p);
        c.drawLine(w - m - a, m, w - m, m, p); c.drawLine(w - m, m, w - m, m + a, p);
        c.drawLine(m, h - m - a, m, h - m, p); c.drawLine(m, h - m, m + a, h - m, p);
        c.drawLine(w - m - a, h - m, w - m, h - m, p); c.drawLine(w - m, h - m, w - m, h - m - a, p);
    }

    private void drawBars(Canvas c) {
        c.save();
        c.clipRect(gl, gt, gr, gb);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xA0000000);
        c.drawRect(gl, gt, gr, gt + 34 * u, p);
        c.drawRect(gl, gb - 78 * u, gr, gb, p);
        // título
        tp.setColor(0xFFFFFFFF);
        tp.setTextSize(12 * u);
        String title = vids.isEmpty() ? "Carregando…" : vids.get(index).name;
        float maxW = rList.left - gl - 14 * u;
        c.drawText(TextUtils.ellipsize(title, tp, maxW, TextUtils.TruncateAt.END).toString(), gl + 8 * u, gt + 22 * u, tp);
        // lista e fechar
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(2.4f * u);
        p.setColor(listOn ? 0xFF7CFF8A : 0xFFFFFFFF);
        for (int i = -1; i <= 1; i++) c.drawLine(rList.centerX() - 8 * u, rList.centerY() + i * 5 * u, rList.centerX() + 8 * u, rList.centerY() + i * 5 * u, p);
        p.setColor(0xFFFFFFFF);
        c.drawLine(rClose.centerX() - 7 * u, rClose.centerY() - 7 * u, rClose.centerX() + 7 * u, rClose.centerY() + 7 * u, p);
        c.drawLine(rClose.centerX() + 7 * u, rClose.centerY() - 7 * u, rClose.centerX() - 7 * u, rClose.centerY() + 7 * u, p);
        // barra de progresso
        long pos = 0, dur = 0;
        try {
            if (mp != null && prepared) { pos = mp.getCurrentPosition(); dur = mp.getDuration(); }
        } catch (IllegalStateException ignored) { /* player ainda carregando */ }
        float frac = seeking ? seekFrac : (dur > 0 ? Math.min(1f, pos / (float) dur) : 0f);
        float cy = rSeek.centerY();
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x66FFFFFF);
        r.set(rSeek.left, cy - 2.5f * u, rSeek.right, cy + 2.5f * u);
        c.drawRoundRect(r, 3 * u, 3 * u, p);
        p.setColor(0xFF7CFF8A);
        r.set(rSeek.left, cy - 2.5f * u, rSeek.left + rSeek.width() * frac, cy + 2.5f * u);
        c.drawRoundRect(r, 3 * u, 3 * u, p);
        c.drawCircle(rSeek.left + rSeek.width() * frac, cy, 6 * u, p);
        tp.setTextSize(10.5f * u);
        tp.setColor(0xFFFFFFFF);
        tp.setTextAlign(Paint.Align.RIGHT);
        c.drawText(fmt(seeking ? (long) (seekFrac * dur) : pos), rSeek.left - 6 * u, cy + 4 * u, tp);
        tp.setTextAlign(Paint.Align.LEFT);
        c.drawText(fmt(dur), rSeek.right + 6 * u, cy + 4 * u, tp);
        // botões
        p.setColor(0xFFFFFFFF);
        boolean playing = false;
        try { playing = mp != null && prepared && mp.isPlaying(); } catch (IllegalStateException ignored) { /* carregando */ }
        if (playing) { // pausar
            r.set(rPlay.centerX() - 8 * u, rPlay.centerY() - 10 * u, rPlay.centerX() - 2.5f * u, rPlay.centerY() + 10 * u);
            c.drawRect(r, p);
            r.set(rPlay.centerX() + 2.5f * u, rPlay.centerY() - 10 * u, rPlay.centerX() + 8 * u, rPlay.centerY() + 10 * u);
            c.drawRect(r, p);
        } else {
            triangle(c, rPlay.centerX() + 2 * u, rPlay.centerY(), 11 * u, true);
        }
        triangle(c, rPrev.centerX() + 3 * u, rPrev.centerY(), 8 * u, false);
        c.drawRect(rPrev.centerX() - 9 * u, rPrev.centerY() - 8 * u, rPrev.centerX() - 6 * u, rPrev.centerY() + 8 * u, p);
        triangle(c, rNext.centerX() - 3 * u, rNext.centerY(), 8 * u, true);
        c.drawRect(rNext.centerX() + 6 * u, rNext.centerY() - 8 * u, rNext.centerX() + 9 * u, rNext.centerY() + 8 * u, p);
        // embaralhar: duas setas cruzadas
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.2f * u);
        p.setColor(shuffle ? 0xFF7CFF8A : 0xAAFFFFFF);
        float sx = rShuffle.centerX(), sy = rShuffle.centerY();
        c.drawLine(sx - 9 * u, sy + 6 * u, sx + 9 * u, sy - 6 * u, p);
        c.drawLine(sx - 9 * u, sy - 6 * u, sx + 9 * u, sy + 6 * u, p);
        c.drawLine(sx + 9 * u, sy - 6 * u, sx + 4 * u, sy - 6 * u, p);
        c.drawLine(sx + 9 * u, sy + 6 * u, sx + 4 * u, sy + 6 * u, p);
        // tela cheia: quatro cantinhos
        p.setColor(0xFFFFFFFF);
        float ax = rAspect.centerX(), ay = rAspect.centerY(), q = 8 * u, k = 4.5f * u;
        c.drawLine(ax - q, ay - q + k, ax - q, ay - q, p); c.drawLine(ax - q, ay - q, ax - q + k, ay - q, p);
        c.drawLine(ax + q - k, ay - q, ax + q, ay - q, p); c.drawLine(ax + q, ay - q, ax + q, ay - q + k, p);
        c.drawLine(ax - q, ay + q - k, ax - q, ay + q, p); c.drawLine(ax - q, ay + q, ax - q + k, ay + q, p);
        c.drawLine(ax + q - k, ay + q, ax + q, ay + q, p); c.drawLine(ax + q, ay + q, ax + q, ay + q - k, p);
        c.restore();
    }

    private void triangle(Canvas c, float cx, float cy, float s, boolean right) {
        p.setStyle(Paint.Style.FILL);
        path.reset();
        float k = right ? 1f : -1f;
        path.moveTo(cx - k * s * 0.6f, cy - s);
        path.lineTo(cx + k * s * 0.9f, cy);
        path.lineTo(cx - k * s * 0.6f, cy + s);
        path.close();
        c.drawPath(path, p);
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000), h = s / 3600, m = (s % 3600) / 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s % 60) : String.format("%d:%02d", m, s % 60);
    }

    private List<String> foldersNow() { return TvLibrary.folders(sp); }

    private int itemCount() { return foldersTab ? foldersNow().size() : vids.size(); }

    private float rowH() { return 30 * u; }

    private float maxScroll() { return Math.max(0f, itemCount() * rowH() - (rListArea.height() - 28 * u)); }

    private void drawList(Canvas c) {
        c.save();
        c.clipRect(rListArea);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xE8101018);
        c.drawRect(rListArea, p);
        // abas
        tp.setTextSize(12 * u);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setColor(!foldersTab ? 0xFF7CFF8A : 0xFFB0B0B0);
        c.drawText("Vídeos (" + vids.size() + ")", rTabV.centerX(), rTabV.centerY() + 4 * u, tp);
        tp.setColor(foldersTab ? 0xFF7CFF8A : 0xFFB0B0B0);
        c.drawText("Pastas (" + foldersNow().size() + ")", rTabF.centerX(), rTabF.centerY() + 4 * u, tp);
        p.setColor(0xFF7CFF8A);
        RectF tab = foldersTab ? rTabF : rTabV;
        c.drawRect(tab.left + 10 * u, tab.bottom - 2 * u, tab.right - 10 * u, tab.bottom, p);
        // linhas
        float top = rTabV.bottom;
        c.clipRect(rListArea.left, top, rListArea.right, rListArea.bottom);
        tp.setTextAlign(Paint.Align.LEFT);
        tp.setTextSize(13 * u);
        List<String> folders = foldersNow();
        int n = itemCount();
        for (int i = 0; i < n; i++) {
            float y = top + i * rowH() - listScroll;
            if (y + rowH() < top || y > rListArea.bottom) continue;
            boolean current = foldersTab ? folders.get(i).equals(folder) : i == index;
            if (current) {
                p.setColor(0x337CFF8A);
                c.drawRect(rListArea.left, y, rListArea.right, y + rowH(), p);
            }
            tp.setColor(current ? 0xFF7CFF8A : 0xFFFFFFFF);
            String name = foldersTab ? TvLibrary.folderName(folders.get(i)) : vids.get(i).name;
            c.drawText(TextUtils.ellipsize((foldersTab ? "" : (i + 1) + ". ") + name, tp, rListArea.width() - 16 * u, TextUtils.TruncateAt.END).toString(),
                    rListArea.left + 8 * u, y + rowH() * 0.66f, tp);
        }
        c.restore();
    }

    // ---------- toques ----------
    @Override public boolean onInterceptTouchEvent(MotionEvent e) { return true; }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                layoutRects(getWidth(), getHeight());
                rawDownX = e.getRawX();
                rawDownY = e.getRawY();
                downX = x;
                downY = y;
                movedFar = false;
                startX = lp.x;
                startY = lp.y;
                startW = lp.width;
                startH = lp.height;
                corner = cornerAt(x, y);
                if (corner >= 0) mode = MODE_RESIZE;
                else if (controlsOn && rSeekHit.contains(x, y)) { mode = MODE_SEEK; seeking = true; seekTo(x); }
                else if (listOn && rTabV.contains(x, y)) { foldersTab = false; listScroll = 0f; mode = MODE_NONE; invalidate(); }
                else if (listOn && rTabF.contains(x, y)) { foldersTab = true; listScroll = 0f; mode = MODE_NONE; invalidate(); }
                else if (listOn && rListArea.contains(x, y) && y > rTabV.bottom) { mode = MODE_LIST; listScrollStart = listScroll; }
                else mode = MODE_MOVE;
                handler.removeCallbacks(hideControls);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dxr = e.getRawX() - rawDownX, dyr = e.getRawY() - rawDownY;
                if (Math.hypot(dxr, dyr) > 12 * dens) movedFar = true;
                if (mode == MODE_RESIZE) resize(dxr);
                else if (mode == MODE_SEEK) { seekTo(x); invalidate(); }
                else if (mode == MODE_LIST) { listScroll = Math.max(0f, Math.min(maxScroll(), listScrollStart - (y - downY))); invalidate(); }
                else if (mode == MODE_MOVE && movedFar) {
                    lp.x = Math.max(0, Math.min(screenW - lp.width, Math.round(startX + dxr)));
                    lp.y = Math.max(0, Math.min(screenH - lp.height, Math.round(startY + dyr)));
                    updateWindow();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (mode == MODE_MOVE && !movedFar) tap(x, y);
                else if (mode == MODE_LIST && !movedFar) tapList(x, y);
                else if (mode == MODE_SEEK) commitSeek();
                if (mode == MODE_RESIZE || (mode == MODE_MOVE && movedFar)) savePlacement();
                mode = MODE_NONE;
                seeking = false;
                if (controlsOn && !listOn) handler.postDelayed(hideControls, 4000);
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                mode = MODE_NONE;
                seeking = false;
                return true;
            default:
                return true;
        }
    }

    /** Qual quina da TV foi tocada: 0 = cima-esquerda, 1 = cima-direita, 2 = baixo-esquerda, 3 = baixo-direita; -1 = nenhuma. */
    private int cornerAt(float x, float y) {
        float z = 26 * dens;
        boolean left = x < z, right = x > getWidth() - z, top = y < z, bottom = y > getHeight() - z;
        if (left && top) return 0;
        if (right && top) return 1;
        if (left && bottom) return 2;
        if (right && bottom) return 3;
        return -1;
    }

    private void resize(float dxr) {
        boolean left = corner == 0 || corner == 2, top = corner == 0 || corner == 1;
        float nw = Math.max(170 * dens, Math.min(screenW, startW + (left ? -dxr : dxr)));
        float nh = nw / aspect;
        lp.width = Math.round(nw);
        lp.height = Math.round(nh);
        lp.x = Math.max(0, Math.round(left ? startX + startW - nw : startX));
        lp.y = Math.max(0, Math.round(top ? startY + startH - nh : startY));
        updateWindow();
    }

    private void updateWindow() {
        try { wm.updateViewLayout(this, lp); } catch (RuntimeException ignored) { /* janela já saiu */ }
    }

    private void savePlacement() {
        sp.edit().putInt("tv_x", lp.x).putInt("tv_y", lp.y).putInt("tv_w", lp.width).apply();
    }

    private void showControls() {
        controlsOn = true;
        handler.removeCallbacks(hideControls);
        if (!listOn) handler.postDelayed(hideControls, 4000);
        invalidate();
    }

    private void tap(float x, float y) {
        if (!controlsOn) { showControls(); return; }
        if (rClose.contains(x, y)) { callback.onClose(); return; }
        if (rList.contains(x, y)) { listOn = !listOn; foldersTab = false; listScroll = 0f; invalidate(); return; }
        if (rShuffle.contains(x, y)) { shuffle = !shuffle; invalidate(); return; }
        if (rAspect.contains(x, y)) { // preencher, esticar ou ajustar
            fillMode = (fillMode + 1) % 3;
            sp.edit().putInt("tv_fill", fillMode).apply();
            fit(videoW, videoH);
            Toast.makeText(ctx, new String[]{"Preencher a TV", "Esticar", "Ajustar (com faixas pretas)"}[fillMode], Toast.LENGTH_SHORT).show();
            invalidate();
            return;
        }
        if (rPrev.contains(x, y)) { previous(); return; }
        if (rPlay.contains(x, y)) { togglePause(); return; }
        if (rNext.contains(x, y)) { next(false); return; }
        if (!listOn) { controlsOn = false; invalidate(); } // tocou no vídeo: esconde
    }

    private void tapList(float x, float y) {
        float row = (y - rTabV.bottom + listScroll) / rowH();
        int i = (int) Math.floor(row);
        if (i < 0 || i >= itemCount()) return;
        if (foldersTab) {
            String tree = foldersNow().get(i);
            foldersTab = false;
            listScroll = 0f;
            listOn = false; // escolheu a playlist: fecha a lista e já toca
            open(tree, 0);
        } else {
            listOn = false;
            play(i);
        }
    }

    private void seekTo(float x) {
        seekFrac = Math.max(0f, Math.min(1f, (x - rSeek.left) / Math.max(1f, rSeek.width())));
        invalidate();
    }

    private void commitSeek() {
        try {
            if (mp != null && prepared) mp.seekTo((int) (seekFrac * mp.getDuration()));
        } catch (IllegalStateException ignored) { /* player ainda carregando */ }
    }
}
