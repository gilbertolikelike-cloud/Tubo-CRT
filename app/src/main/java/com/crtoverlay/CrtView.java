package com.crtoverlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * Filtro CRT por cima do jogo. Um overlay só consegue escurecer (preto com transparência),
 * então cada pixel vira alpha = 1 - fator.
 *
 * Valores do jogo Black (CRT LIGHT / STRONG): linhas 0,35 / 0,60, curva 0,02 / 0,045,
 * máscara 0,15 / 0,30, cantos 0,1 / 0,2.
 *
 * Conta de cada pixel:
 *  - curva de barril: p *= 1 + curva * p.yx², fora de [-1, 1] fica preto
 *  - scanline: perfil gaussiano 2^(-dureza * d²), d = distância até o centro da linha (CRT-Lottes)
 *  - máscara de fósforo, tipos 1 a 4 (CRT-Lottes), em luminância porque o overlay não tem cor por canal
 *  - sombra nos cantos: pow(16uv(1-u)(1-v), expoente)
 *  - cantos arredondados com borda suave (CRT-Geom)
 *  - o jogo devolve brilho (1 + (1/kept - 1) * 0,8); o overlay não pode clarear, então só limita em 1
 */
public class CrtView extends View {
    public static final String[] KEYS = {"dim", "curve", "per", "scan", "mask", "corner", "motion", "hard", "mtype", "round"};
    /** escurecer %, curva (milésimos), px por linha (0 = auto), scan %, máscara %, sombra, movimento, dureza, tipo, cantos */
    public static final int[] DEF = {0, 20, 0, 35, 15, 10, 25, 6, 2, 3};

    private static final float GUEST_H = 480f;               // altura da imagem do jogo original (Xbox)
    private static final float LN2 = 0.6931472f;
    private static final float[] LIT_LUM = {0.299f, 0.587f, 0.114f}; // peso de luminância de R, G, B
    private static final float CORNER_SMOOTH = 200f;

    private final long t0 = SystemClock.uptimeMillis();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint bandPaint = new Paint();
    private final Paint blackPaint = new Paint();

    private int dim, curve, per, scan, mask, corner, motion, hard, mtype, round;
    private Bitmap baked;
    private volatile int generation = 0;
    private int bandHeight = 0;

    public CrtView(Context c) {
        super(c);
        blackPaint.setColor(Color.BLACK);
    }

    public void set(int dim, int curve, int per, int scan, int mask, int corner, int motion, int hard, int mtype, int round) {
        boolean rebake = baked == null || curve != this.curve || per != this.per || scan != this.scan
                || mask != this.mask || corner != this.corner || hard != this.hard
                || mtype != this.mtype || round != this.round;
        this.dim = dim; this.curve = curve; this.per = per; this.scan = scan; this.mask = mask;
        this.corner = corner; this.motion = motion; this.hard = hard; this.mtype = mtype; this.round = round;
        if (rebake) bake(); else invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        bandHeight = Math.max(1, h / 5);
        // faixa escura e suave que desce pela tela
        bandPaint.setShader(new LinearGradient(0, 0, 0, bandHeight,
                new int[]{0x00000000, 0x40000000, 0x00000000}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        bake();
    }

    @Override protected void onDetachedFromWindow() {
        generation++; // cancela a conta em andamento
        super.onDetachedFromWindow();
    }

    private int pixelsPerLine(int h) {
        return per > 0 ? per : Math.max(1, Math.round(h / GUEST_H));
    }

    private void bake() {
        final int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        final int gen = ++generation;
        final float k = curve / 1000f, gap = scan / 100f, m = mask / 100f, cornerExp = corner / 100f;
        final int perPx = pixelsPerLine(h);
        final float lines = (float) Math.floor(h / (float) perPx);
        final int type = Math.max(1, Math.min(4, mtype));
        float kept = (1 - gap / 2) * (1 - 2 * m / 3);
        if (type == 1) kept *= (1 - m / 2);
        final float lookY = 1 + (1 / kept - 1) * 0.8f;
        final float[] colFactor = new float[3];
        for (int i = 0; i < 3; i++) colFactor[i] = 1 - m * (1 - LIT_LUM[i]);
        final float hd = hard;
        final float pEdge = (float) Math.exp(-hd * 0.25f * LN2);
        final float pNorm = 1f / Math.max(0.0001f, 1f - pEdge);
        final float radius = round / 100f, aspect = w / (float) h;

        new Thread(() -> {
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                if (gen != generation) return;
                float cy = (y + 0.5f) / h * 2 - 1;
                for (int x = 0; x < w; x++) {
                    float cx = (x + 0.5f) / w * 2 - 1;
                    float wx = cx * (1 + k * cy * cy);
                    float wy = cy * (1 + k * cx * cx);
                    float edge = Math.max(Math.abs(wx), Math.abs(wy));
                    float a;
                    if (edge >= 1f + 2f / w) {
                        a = 1f;
                    } else {
                        float fr = (wy * 0.5f + 0.5f) * lines;
                        fr -= (float) Math.floor(fr);
                        float dd = fr - 0.5f;
                        float p = (float) Math.exp(-hd * dd * dd * LN2);
                        float line = 1f - gap * 0.86f * (1f - p) * pNorm; // 0,86: a linha afrouxa em áreas claras

                        int col; float rowDark = 1f;
                        switch (type) {
                            case 1: { int odd = (x % 6) < 3 ? 1 : 0; col = x % 3; if (((y + odd) % 2) == 0) rowDark = 1f - m; break; }
                            case 3: col = ((x + y * 3) % 6) / 2; break;
                            case 4: col = ((x + (y / 2) * 3) % 6) / 2; break;
                            default: col = x % 3; // 2 = grade Trinitron
                        }

                        float u = wx * 0.5f + 0.5f, v = wy * 0.5f + 0.5f;
                        float vignette = clamp01(16f * u * v * (1 - u) * (1 - v));
                        float shade = cornerExp > 0f ? (float) Math.pow(vignette, cornerExp) : 1f;

                        float rounded = 1f;
                        if (radius > 0f) {
                            float uu = (x + 0.5f) / w, vv = (y + 0.5f) / h;
                            float ex = Math.min(uu, 1f - uu) * aspect, ey = Math.min(vv, 1f - vv);
                            float dx = radius - Math.min(ex, radius), dy = radius - Math.min(ey, radius);
                            rounded = clamp01((radius - (float) Math.sqrt(dx * dx + dy * dy)) * CORNER_SMOOTH);
                        }

                        float f = Math.min(1f, line * colFactor[col] * rowDark * shade * lookY) * rounded;
                        a = 1f - f;
                        if (edge > 1f) a = Math.max(a, (edge - 1f) * w * 0.5f);
                    }
                    px[y * w + x] = ((int) (clamp01(a) * 255f)) << 24;
                }
            }
            final Bitmap result = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
            post(() -> {
                if (gen != generation) { result.recycle(); return; }
                Bitmap old = baked;
                baked = result;
                if (old != null) old.recycle();
                invalidate();
            });
        }).start();
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    @Override protected void onDraw(Canvas canvas) {
        final int w = getWidth(), h = getHeight();
        final float amt = motion / 100f;
        final float t = (SystemClock.uptimeMillis() - t0) / 1000f;

        if (baked != null) {
            // as linhas descem devagar: 1 a 7 linhas por segundo (o padrão se repete a cada linha, então não "pula")
            float off = 0f;
            int pp = pixelsPerLine(h);
            if (motion > 0) off = (t * (1f + 6f * amt) * pp) % pp;
            canvas.drawBitmap(baked, 0, -off, bitmapPaint);
            if (off > 0f) canvas.drawRect(0, h - off - 1, w, h, blackPaint); // a tira que sobra embaixo fica preta
        }
        if (motion > 0) {
            // faixa escura descendo a cada 7 segundos
            float y = ((t / 7f) % 1f) * (h + bandHeight) - bandHeight;
            bandPaint.setAlpha((int) (255 * amt));
            canvas.save();
            canvas.translate(0, y);
            canvas.drawRect(0, 0, w, bandHeight, bandPaint);
            canvas.restore();
            // tremida leve no brilho (até 5%)
            float noise = (float) ((Math.sin(t * 91.3) + Math.sin(t * 47.1)) * 0.25 + 0.5);
            int flicker = (int) (255 * 0.05f * amt * noise);
            if (flicker > 0) canvas.drawColor(Color.argb(flicker, 0, 0, 0));
        }
        if (dim > 0) canvas.drawColor(Color.argb(dim * 255 / 100, 0, 0, 0));
        if (motion > 0) postInvalidateDelayed(33); // ~30 quadros por segundo, pra poupar bateria
    }
}
