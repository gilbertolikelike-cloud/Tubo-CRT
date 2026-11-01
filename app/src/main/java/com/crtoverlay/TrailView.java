package com.crtoverlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;
import java.util.ArrayDeque;
import java.util.Random;

/** Rastro da espada: véu azul, brilho branco e raios azuis, que somem depressa. Janela de tela cheia que não pega toque. */
final class TrailView extends View {
    private static final long LIFE_MS = 460;

    private static final class Pt {
        final float tipX, tipY, midX, midY;
        final long t;
        Pt(float tipX, float tipY, float midX, float midY, long t) {
            this.tipX = tipX; this.tipY = tipY; this.midX = midX; this.midY = midY; this.t = t;
        }
    }

    private final ArrayDeque<Pt> points = new ArrayDeque<>();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Random rnd = new Random();
    private final float d;

    TrailView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    /** Ponta da espada (tip) e um ponto mais perto do cabo (mid), em coordenadas da tela. */
    void add(float tipX, float tipY, float midX, float midY) {
        points.addLast(new Pt(tipX, tipY, midX, midY, SystemClock.uptimeMillis()));
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        while (!points.isEmpty() && now - points.peekFirst().t > LIFE_MS) points.removeFirst();
        if (points.size() >= 2) {
            Pt prev = null;
            for (Pt pt : points) {
                if (prev != null) {
                    float life = 1f - (now - pt.t) / (float) LIFE_MS; // 1 novo .. 0 sumindo
                    path.reset();
                    path.moveTo(prev.tipX, prev.tipY);
                    path.lineTo(pt.tipX, pt.tipY);
                    path.lineTo(pt.midX, pt.midY);
                    path.lineTo(prev.midX, prev.midY);
                    path.close();
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(Color.argb((int) (120 * life), 40, 120, 255)); // véu azul
                    c.drawPath(path, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(8 * d);
                    p.setColor(Color.argb((int) (80 * life), 60, 140, 255));  // brilho da borda, em camadas
                    c.drawLine(prev.tipX, prev.tipY, pt.tipX, pt.tipY, p);
                    p.setStrokeWidth(3.4f * d);
                    p.setColor(Color.argb((int) (200 * life), 150, 215, 255));
                    c.drawLine(prev.tipX, prev.tipY, pt.tipX, pt.tipY, p);
                    p.setStrokeWidth(1.4f * d);
                    p.setColor(Color.argb((int) (255 * life), 255, 255, 255));
                    c.drawLine(prev.tipX, prev.tipY, pt.tipX, pt.tipY, p);
                }
                prev = pt;
            }
            drawLightning(c);
        }
        if (!points.isEmpty()) postInvalidateOnAnimation();
    }

    /** Raios azuis em zigue-zague, que mudam a cada quadro. */
    private void drawLightning(Canvas c) {
        Pt[] arr = points.toArray(new Pt[0]);
        if (arr.length < 3) return;
        for (int bolt = 0; bolt < 3; bolt++) {
            Pt a = arr[rnd.nextInt(arr.length)], b = arr[rnd.nextInt(arr.length)];
            float x0 = a.tipX, y0 = a.tipY, x1 = b.midX, y1 = b.midY;
            float dx = x1 - x0, dy = y1 - y0, len = (float) Math.hypot(dx, dy);
            if (len < 8 * d) continue;
            float nx = -dy / len, ny = dx / len;
            path.reset();
            path.moveTo(x0, y0);
            for (int s = 1; s < 6; s++) {
                float t = s / 6f, jitter = (rnd.nextFloat() - 0.5f) * 14 * d;
                path.lineTo(x0 + dx * t + nx * jitter, y0 + dy * t + ny * jitter);
            }
            path.lineTo(x1, y1);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3.2f * d);
            p.setColor(Color.argb(90, 60, 140, 255));
            c.drawPath(path, p);
            p.setStrokeWidth(1.3f * d);
            p.setColor(Color.argb(235, 205, 235, 255));
            c.drawPath(path, p);
        }
    }
}
