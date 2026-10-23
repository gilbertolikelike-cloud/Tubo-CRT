package com.crtoverlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;

/**
 * Reproduz o filtro CRT do Black-Android (native/vulkan.cpp, shader pscrt) como camada preta com alpha.
 * Valores originais  (LEVE / FORTE):
 *   gap (scanline)  0.35 / 0.60     curva 0.02 / 0.045
 *   mascara         0.15 / 0.30     cantos (expoente) 0.1 / 0.2
 * Conta do shader:
 *   cc *= 1 + curva * cc.yx^2 ; fora de [-1,1] = preto
 *   scan = 1 - gap * cos(pi*frac(v*linhas))^2      (cada linha tem N pixels de tela)
 *   mascara por coluna (x%3), cantos = pow(16uv(1-u)(1-v), expoente)
 *   brilho devolvido = 1 + (1/kept - 1) * 0.8, kept = (1-gap/2)(1-2*mascara/3)
 * Overlay so escurece: alpha = 1 - min(1, fator).
 */
public class CrtView extends View {
    public static final String[] KEYS = {"dim", "curve", "per", "scan", "mask", "corner"};
    // dim %, curva (milesimos), px por linha (0 = auto), scan %, mascara %, cantos (centesimos)
    public static final int[] DEF = {0, 20, 0, 35, 15, 10};
    private static final float GUEST_H = 480f; // altura do jogo original (Xbox)
    private static final float[] MASK_W = {0.701f, 0.413f, 0.886f}; // quanto cada coluna escurece em luminancia

    private int dim, curve, per, scan, mask, corner;
    private Bitmap baked;
    private volatile int gen = 0;

    public CrtView(Context c) { super(c); }

    public void set(int dim, int curve, int per, int scan, int mask, int corner) {
        boolean rebake = baked == null || curve != this.curve || per != this.per || scan != this.scan
                || mask != this.mask || corner != this.corner;
        this.dim = dim; this.curve = curve; this.per = per;
        this.scan = scan; this.mask = mask; this.corner = corner;
        if (rebake) bake(); else invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { bake(); }

    private void bake() {
        final int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        final int g = ++gen;
        final float k = curve / 1000f, gap = scan / 100f, m = mask / 100f, ce = corner / 100f;
        final int perPx = per > 0 ? per : Math.max(1, Math.round(h / GUEST_H));
        final float ln = (float) Math.floor(h / (float) perPx);
        final float kept = (1 - gap / 2) * (1 - 2 * m / 3);
        final float lookY = 1 + (1 / kept - 1) * 0.8f;
        final float[] gc = {1 - m * MASK_W[0], 1 - m * MASK_W[1], 1 - m * MASK_W[2]};
        new Thread(() -> {
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                if (g != gen) return;
                float cy = (y + 0.5f) / h * 2 - 1;
                for (int x = 0; x < w; x++) {
                    float cx = (x + 0.5f) / w * 2 - 1;
                    float wx = cx * (1 + k * cy * cy);
                    float wy = cy * (1 + k * cx * cx);
                    float e = Math.max(Math.abs(wx), Math.abs(wy));
                    float a;
                    if (e >= 1f + 2f / w) {
                        a = 1f;
                    } else {
                        float fr = (wy * 0.5f + 0.5f) * ln;
                        fr -= (float) Math.floor(fr);
                        float cs = (float) Math.cos(Math.PI * fr);
                        float s = 1f - gap * cs * cs * 0.86f; // linha afrouxa em areas claras (luma media ~0.35)
                        float u = wx * 0.5f + 0.5f, v = wy * 0.5f + 0.5f;
                        float c = 16f * u * v * (1 - u) * (1 - v);
                        c = Math.max(0f, Math.min(1f, c));
                        float cor = ce > 0f ? (float) Math.pow(c, ce) : 1f;
                        float f = Math.min(1f, s * gc[x % 3] * cor * lookY);
                        a = 1f - f;
                        if (e > 1f) a = Math.max(a, (e - 1f) * w * 0.5f);
                    }
                    a = Math.max(0f, Math.min(1f, a));
                    px[y * w + x] = ((int) (a * 255f)) << 24;
                }
            }
            final Bitmap b = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
            post(() -> { if (g == gen) { baked = b; invalidate(); } });
        }).start();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (baked != null) canvas.drawBitmap(baked, 0, 0, null);
        if (dim > 0) canvas.drawColor(Color.argb(dim * 255 / 100, 0, 0, 0));
    }
}
