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
 * Mascaras (tipos 1-4) e perfil gaussiano da linha: adaptados do CRT-Lottes (dominio publico).
 * Cantos arredondados: adaptados do CRT-Geom.
 */
public class CrtView extends View {
    public static final String[] KEYS = {"dim", "curve", "per", "scan", "mask", "corner", "motion", "hard", "mtype", "round"};
    // dim %, curva (milesimos), px por linha (0 = auto), scan %, mascara %, cantos (centesimos)
    public static final int[] DEF = {0, 20, 0, 35, 15, 10, 25, 6, 2, 3};
    private static final float GUEST_H = 480f; // altura do jogo original (Xbox)
    private static final float[] MASK_W = {0.701f, 0.413f, 0.886f}; // quanto cada coluna escurece em luminancia

    private int dim, curve, per, scan, mask, corner, motion, hard, mtype, round;
    private final long t0 = SystemClock.uptimeMillis();
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint bandPaint = new Paint();
    private final Paint blackPaint = new Paint();
    private int bandH = 0;
    private Bitmap baked;
    private volatile int gen = 0;

    public CrtView(Context c) { super(c); blackPaint.setColor(Color.BLACK); }

    public void set(int dim, int curve, int per, int scan, int mask, int corner, int motion, int hard, int mtype, int round) {
        boolean rebake = baked == null || curve != this.curve || per != this.per || scan != this.scan
                || mask != this.mask || corner != this.corner || hard != this.hard || mtype != this.mtype || round != this.round;
        this.dim = dim; this.curve = curve; this.per = per;
        this.scan = scan; this.mask = mask; this.corner = corner; this.motion = motion; this.hard = hard; this.mtype = mtype; this.round = round;
        if (rebake) bake(); else invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        bandH = Math.max(1, h / 5);
        // faixa escura e suave (preto 25% no meio), que desce pela tela
        bandPaint.setShader(new LinearGradient(0, 0, 0, bandH,
                new int[]{0x00000000, 0x40000000, 0x00000000}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        bake();
    }

    private int perPx(int h) { return per > 0 ? per : Math.max(1, Math.round(h / GUEST_H)); }

    private static final float[] LIT_LUM = {0.299f, 0.587f, 0.114f}; // peso de luminancia de R, G, B

    private void bake() {
        final int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        final int g = ++gen;
        final float k = curve / 1000f, gap = scan / 100f, m = mask / 100f, ce = corner / 100f;
        final int perPx = perPx(h);
        final float ln = (float) Math.floor(h / (float) perPx);
        final int type = Math.max(1, Math.min(4, mtype));
        float kept = (1 - gap / 2) * (1 - 2 * m / 3);
        if (type == 1) kept *= (1 - m / 2);
        final float lookY = 1 + (1 / kept - 1) * 0.8f;
        final float[] gc = new float[3];
        for (int i = 0; i < 3; i++) gc[i] = 1 - m * (1 - LIT_LUM[i]);
        // perfil gaussiano da linha (CRT-Lottes): peso = 2^(-dureza * d^2), d = distancia ate o centro da linha
        final float hd = hard;
        final float pEdge = (float) Math.exp(-hd * 0.25f * 0.6931472f);
        final float pNorm = 1f / Math.max(0.0001f, 1f - pEdge);
        // cantos arredondados (CRT-Geom): raio em "alturas de tela", borda suave
        final float cs = round / 100f, aspect = w / (float) h, smooth = 200f;
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
                        float d = fr - 0.5f;
                        float p = (float) Math.exp(-hd * d * d * 0.6931472f);
                        float sc = 1f - gap * 0.86f * (1f - p) * pNorm; // 0.86: linha afrouxa em areas claras
                        int col; float rowDark = 1f;
                        switch (type) {
                            case 1: { int odd = (x % 6) < 3 ? 1 : 0; col = x % 3; if (((y + odd) % 2) == 0) rowDark = 1f - m; break; }
                            case 3: col = ((x + y * 3) % 6) / 2; break;
                            case 4: col = ((x + (y / 2) * 3) % 6) / 2; break;
                            default: col = x % 3; // 2 = aperture grille
                        }
                        float u = wx * 0.5f + 0.5f, v = wy * 0.5f + 0.5f;
                        float c = 16f * u * v * (1 - u) * (1 - v);
                        c = Math.max(0f, Math.min(1f, c));
                        float cor = ce > 0f ? (float) Math.pow(c, ce) : 1f;
                        float rnd = 1f;
                        if (cs > 0f) {
                            float uu = (x + 0.5f) / w, vv = (y + 0.5f) / h;
                            float ex = Math.min(uu, 1f - uu) * aspect, ey = Math.min(vv, 1f - vv);
                            float dx = cs - Math.min(ex, cs), dy = cs - Math.min(ey, cs);
                            float dist = (float) Math.sqrt(dx * dx + dy * dy);
                            rnd = Math.max(0f, Math.min(1f, (cs - dist) * smooth));
                        }
                        float f = Math.min(1f, sc * gc[col] * rowDark * cor * lookY) * rnd;
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
        final int w = getWidth(), h = getHeight();
        final float amt = motion / 100f;
        final float t = (SystemClock.uptimeMillis() - t0) / 1000f;
        if (baked != null) {
            // as linhas descem devagar: 1 a 7 linhas por segundo (o padrao se repete a cada linha, entao nao "pula")
            float off = 0f;
            int pp = perPx(h);
            if (motion > 0) off = (t * (1f + 6f * amt) * pp) % pp;
            canvas.drawBitmap(baked, 0, -off, bmpPaint);
            if (off > 0f) { // a tira que sobra embaixo fica preta, como a borda de uma TV
                canvas.drawRect(0, h - off - 1, w, h, blackPaint);
            }
        }
        if (motion > 0) {
            // faixa escura descendo a cada 7 segundos
            float y = ((t / 7f) % 1f) * (h + bandH) - bandH;
            bandPaint.setAlpha((int) (255 * amt));
            canvas.save();
            canvas.translate(0, y);
            canvas.drawRect(0, 0, w, bandH, bandPaint);
            canvas.restore();
            // tremida leve no brilho (ate 5% no maximo)
            float nz = (float) ((Math.sin(t * 91.3) + Math.sin(t * 47.1)) * 0.25 + 0.5);
            int fa = (int) (255 * 0.05f * amt * nz);
            if (fa > 0) canvas.drawColor(Color.argb(fa, 0, 0, 0));
        }
        if (dim > 0) canvas.drawColor(Color.argb(dim * 255 / 100, 0, 0, 0));
        if (motion > 0) postInvalidateDelayed(33); // ~30 quadros por segundo, pra poupar bateria
    }
}
