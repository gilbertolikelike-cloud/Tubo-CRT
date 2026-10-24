package com.crtoverlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/** Brilho verde nas bordas, reflexo de vidro e tremida de brilho (estilo Vault66). So desenha, nao pega toque. */
public class GlowView extends View {
    private final Paint edge = new Paint(), glare = new Paint(), sheen = new Paint();
    private final long t0 = SystemClock.uptimeMillis();

    public GlowView(Context c) { super(c); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        RadialGradient rg = new RadialGradient(w / 2f, h / 2f, h * 0.75f,
                new int[]{0x00000000, 0x00000000, 0x555BB387}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(w / (float) h, 1f, w / 2f, h / 2f); // elipse do tamanho da tela
        rg.setLocalMatrix(m);
        edge.setShader(rg);
        glare.setShader(new LinearGradient(0, 0, w, h * 0.9f,
                new int[]{0x00FFFFFF, 0x0DFFFFFF, 0x18FFFFFF, 0x0DFFFFFF, 0x00FFFFFF},
                new float[]{0.1f, 0.35f, 0.5f, 0.65f, 0.9f}, Shader.TileMode.CLAMP));
        sheen.setShader(new LinearGradient(0, 0, 0, h * 0.35f, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas c) {
        float t = (SystemClock.uptimeMillis() - t0) / 1000f;
        float nz = (float) ((Math.sin(t * 13.1) + Math.sin(t * 7.7) * 0.6) * 0.3 + 0.5);
        edge.setAlpha((int) (255 * Math.max(0.6f, Math.min(1f, 0.8f + 0.2f * nz))));
        c.drawRect(0, 0, getWidth(), getHeight(), edge);
        c.drawRect(0, 0, getWidth(), getHeight(), glare);
        c.drawRect(0, 0, getWidth(), getHeight(), sheen);
        postInvalidateDelayed(50);
    }
}
