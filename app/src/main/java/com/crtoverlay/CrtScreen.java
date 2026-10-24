package com.crtoverlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.view.MotionEvent;
import android.widget.FrameLayout;

/**
 * Olho de peixe de verdade, mas SO pro que esta dentro deste layout (a tela do proprio app).
 * Desenha os filhos numa imagem e redesenha ela entortada (malha). Os toques passam pela conta inversa.
 * Mesma formula do shader: p' = p * (1 + k * p.yx^2)
 */
public class CrtScreen extends FrameLayout {
    private static final int MW = 36, MH = 20;
    private static final float RES = 0.6f; // resolucao interna: menor = mais leve (e mais "tubo")
    private final float[] verts = new float[(MW + 1) * (MH + 1) * 2];
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Bitmap off;
    private Canvas offCanvas;
    private float k = 0.045f;

    public CrtScreen(Context c) { super(c); }

    public void setCurve(float k) { this.k = k; buildMesh(); invalidate(); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w > 0 && h > 0) {
            if (off != null) off.recycle();
            off = Bitmap.createBitmap(Math.max(1, (int) (w * RES)), Math.max(1, (int) (h * RES)), Bitmap.Config.ARGB_8888);
            offCanvas = new Canvas(off);
        }
        buildMesh();
    }

    private void buildMesh() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        int n = 0;
        for (int j = 0; j <= MH; j++) {
            for (int i = 0; i <= MW; i++) {
                float sx = i / (float) MW * 2 - 1, sy = j / (float) MH * 2 - 1, px = sx, py = sy;
                for (int it = 0; it < 10; it++) { px = sx / (1 + k * py * py); py = sy / (1 + k * px * px); }
                verts[n++] = (px * 0.5f + 0.5f) * w;
                verts[n++] = (py * 0.5f + 0.5f) * h;
            }
        }
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        if (off == null || k <= 0f) { super.dispatchDraw(canvas); return; }
        offCanvas.drawColor(0, PorterDuff.Mode.CLEAR);
        offCanvas.save();
        offCanvas.scale(RES, RES);
        super.dispatchDraw(offCanvas);
        offCanvas.restore();
        canvas.drawBitmapMesh(off, MW, MH, verts, 0, null, 0, paint);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        int w = getWidth(), h = getHeight();
        if (k <= 0f || w == 0 || h == 0) return super.dispatchTouchEvent(ev);
        float px = ev.getX() / w * 2 - 1, py = ev.getY() / h * 2 - 1;
        float sx = px * (1 + k * py * py), sy = py * (1 + k * px * px);
        MotionEvent e = MotionEvent.obtain(ev);
        e.setLocation((sx * 0.5f + 0.5f) * w, (sy * 0.5f + 0.5f) * h);
        boolean r = super.dispatchTouchEvent(e);
        e.recycle();
        return r;
    }
}
