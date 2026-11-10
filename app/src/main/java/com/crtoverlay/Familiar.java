package com.crtoverlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Process;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Espada solta (Sword Familiar): depois de uns minutos sem ninguém tocar na tela, a espada do Castlevania sai voando sozinha.
 * De vez em quando aparece um pato; a espada corre atrás, ataca com um arco de raios, e na hora um balão de conversa
 * mostra um versículo sorteado. Tocar na tela acaba com tudo. A janela é de tela cheia e não pega toque.
 */
final class Familiar {
    private final Context ctx;
    private final WindowManager wm;
    private final boolean viaAccessibility;
    private FamiliarView view;

    Familiar(Context ctx, WindowManager wm, boolean viaAccessibility) {
        this.ctx = ctx;
        this.wm = wm;
        this.viaAccessibility = viaAccessibility;
    }

    boolean isActive() { return view != null; }

    void start() {
        if (view != null) return;
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        FamiliarView v = new FamiliarView(ctx);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(dm.widthPixels, dm.heightPixels,
                viaAccessibility ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                        : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(v, lp);
            view = v;
        } catch (RuntimeException e) {
            v.release();
        }
    }

    void stop() {
        if (view == null) return;
        FamiliarView v = view;
        view = null;
        v.release();
        try { wm.removeView(v); } catch (RuntimeException ignored) { /* janela já removida */ }
    }

    // ---------------------------------------------------------------------------------------------
    private static final class Spark {
        float x, y, vx, vy, life;
        int color;
    }

    private static final class FamiliarView extends View {
        private static final int ROAM = 0, CHASE = 1, SLASH = 2, TALK = 3;

        private final float d;
        private final Random rnd = new Random();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final TextPaint verseText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint refText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF(), dst = new RectF();
        private final Rect src = new Rect();
        private final List<Spark> sparks = new ArrayList<>();
        private final Shader glow;
        private volatile SpriteSheet swordSheet, duckSheet;
        private volatile Verses verses;
        private volatile boolean released;

        private float w, h, t, talkSeconds = 8f;
        private long last;
        private int state = ROAM;
        // espada
        private float sx, sy, svx, svy, ang, tx, ty, nextWaypoint;
        // pato
        private boolean duckAlive;
        private float dx, dy, dvx, dvy, duckTurnAt, duckSpawnAt, duckChaseAt;
        // golpe e fala
        private float slashT, slashStart, talkUntil;
        private boolean popped;
        private StaticLayout verseLayout, refLayout;

        FamiliarView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            verseText.setColor(0xFF2B1608);
            verseText.setTextSize(12.5f * d);
            verseText.setTypeface(Typeface.SERIF);
            refText.setColor(0xFF8A4B14);
            refText.setTextSize(11f * d);
            refText.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
            glow = new RadialGradient(0, 0, 70 * d, 0x663A8BFF, 0x003A8BFF, Shader.TileMode.CLAMP);
            duckSpawnAt = 5f + rnd.nextFloat() * 4f;
            final Context app = c.getApplicationContext();
            new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                SpriteSheet sword = SpriteSheet.load(app, "familiar", 1);
                SpriteSheet duck = SpriteSheet.load(app, "duck_target", 2);
                Verses v = Verses.load(app);
                if (released) {
                    if (sword != null) sword.recycle();
                    if (duck != null) duck.recycle();
                    return;
                }
                duckSheet = duck;
                verses = v;
                swordSheet = sword;
            }).start();
        }

        void release() {
            released = true;
            SpriteSheet a = swordSheet, b = duckSheet;
            swordSheet = null;
            duckSheet = null;
            verses = null;
            if (a != null) a.recycle();
            if (b != null) b.recycle();
        }

        @Override protected void onSizeChanged(int nw, int nh, int ow, int oh) {
            w = nw;
            h = nh;
            sx = w + 60 * d; // a espada entra voando pela direita
            sy = h * 0.3f;
            tx = w * 0.7f;
            ty = h * 0.4f;
        }

        // ---------- lógica ----------
        private float duckH() { return 84 * d; }

        private static float wrap(float a) {
            while (a > 180f) a -= 360f;
            while (a < -180f) a += 360f;
            return a;
        }

        private void update(float dt) {
            switch (state) {
                case ROAM:
                    roam(dt);
                    if (!duckAlive && t >= duckSpawnAt && duckSheet != null) spawnDuck();
                    if (duckAlive && t >= duckChaseAt) state = CHASE;
                    break;
                case CHASE: chase(dt); break;
                case SLASH: slash(dt); break;
                default: talk(dt); break;
            }
            moveDuck(dt);
            updateSparks(dt);
        }

        /** Vai em direção a um ponto, acelerando e freando suave. A ponta da espada aponta pra onde ela vai. */
        private void steer(float targetX, float targetY, float maxSpeed, float accel, float dt) {
            float vx = targetX - sx, vy = targetY - sy, dist = (float) Math.hypot(vx, vy);
            float speed = maxSpeed * Math.min(1f, dist / (90 * d));
            float wantX = dist > 0.01f ? vx / dist * speed : 0f, wantY = dist > 0.01f ? vy / dist * speed : 0f;
            float ax = wantX - svx, ay = wantY - svy, len = (float) Math.hypot(ax, ay), lim = accel * dt;
            if (len > lim) { ax *= lim / len; ay *= lim / len; }
            svx += ax;
            svy += ay;
            sx += svx * dt;
            sy += svy * dt;
            float now = (float) Math.hypot(svx, svy);
            float want = now > 60 * d ? (float) Math.toDegrees(Math.atan2(svx, -svy)) : (float) Math.sin(t * 2f) * 8f;
            ang += wrap(want - ang) * Math.min(1f, 7f * dt);
        }

        private void roam(float dt) {
            if (t >= nextWaypoint) {
                float m = 60 * d;
                tx = m + rnd.nextFloat() * (w - 2 * m);
                ty = m + rnd.nextFloat() * (h - 2 * m);
                nextWaypoint = t + 1.4f + rnd.nextFloat() * 1.8f;
            }
            steer(tx, ty, 230 * d, 700 * d, dt);
        }

        private void spawnDuck() {
            float m = 50 * d;
            for (int i = 0; i < 12; i++) {
                dx = m + rnd.nextFloat() * (w - 2 * m);
                dy = m * 1.6f + rnd.nextFloat() * (h - 3.2f * m);
                if (Math.hypot(dx - sx, dy - sy) > 170 * d) break;
            }
            duckAlive = true;
            duckChaseAt = t + 0.9f;
            duckTurnAt = t;
            dvx = 0f;
            dvy = 0f;
        }

        private void moveDuck(float dt) {
            if (!duckAlive) return;
            if (t >= duckTurnAt) { // passeia sem rumo
                double a = rnd.nextDouble() * Math.PI * 2;
                float speed = (35 + rnd.nextFloat() * 40) * d;
                dvx = (float) Math.cos(a) * speed;
                dvy = (float) Math.sin(a) * speed * 0.5f;
                duckTurnAt = t + 0.8f + rnd.nextFloat() * 1.4f;
            }
            if (state != SLASH) {
                dx += dvx * dt;
                dy += dvy * dt;
            }
            float m = 40 * d;
            if (dx < m) { dx = m; dvx = Math.abs(dvx); }
            if (dx > w - m) { dx = w - m; dvx = -Math.abs(dvx); }
            if (dy < m * 1.6f) { dy = m * 1.6f; dvy = Math.abs(dvy); }
            if (dy > h - m * 1.6f) { dy = h - m * 1.6f; dvy = -Math.abs(dvy); }
        }

        private void chase(float dt) {
            if (!duckAlive) { state = ROAM; return; }
            float aimY = dy - duckH() * 0.1f;
            steer(dx, aimY, 640 * d, 2200 * d, dt);
            if (Math.hypot(dx - sx, aimY - sy) < 70 * d) {
                state = SLASH;
                slashT = 0f;
                slashStart = rnd.nextFloat() * 360f;
                popped = false;
                svx = 0f;
                svy = 0f;
            }
        }

        private void slash(float dt) {
            slashT += dt;
            float k = Math.min(1f, 10f * dt);
            sx += (dx - sx) * k;
            sy += (dy - duckH() * 0.1f - sy) * k;
            ang += 1100f * dt; // gira atacando
            if (!popped && slashT >= 0.30f) {
                popped = true;
                duckAlive = false;
                spawnSparks(dx, dy);
                chooseVerse();
            }
            if (slashT >= 0.80f) {
                ang = wrap(ang);
                if (verseLayout != null) {
                    state = TALK;
                    talkUntil = t + talkSeconds;
                    duckSpawnAt = talkUntil + 10f + rnd.nextFloat() * 12f;
                } else {
                    state = ROAM;
                    nextWaypoint = t;
                    duckSpawnAt = t + 10f + rnd.nextFloat() * 12f;
                }
            }
        }

        private void talk(float dt) {
            sx += (float) Math.sin(t * 1.3f) * 14 * d * dt;
            sy += (float) Math.cos(t * 1.1f) * 12 * d * dt;
            ang += wrap((float) Math.sin(t * 1.7f) * 6f - ang) * Math.min(1f, 4f * dt);
            if (t >= talkUntil) {
                state = ROAM;
                verseLayout = null;
                refLayout = null;
                nextWaypoint = t;
            }
        }

        private void chooseVerse() {
            Verses v = verses;
            String[] verse = v != null ? v.random(rnd) : new String[]{"O SENHOR é o meu pastor; nada me faltará.", "Salmos 23:1"};
            int width = (int) Math.min(w * 0.72f, 270 * d);
            verseLayout = StaticLayout.Builder.obtain(verse[0], 0, verse[0].length(), verseText, width)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).build();
            String ref = verse[1].isEmpty() ? "" : "\u2014 " + verse[1];
            refLayout = ref.isEmpty() ? null : StaticLayout.Builder.obtain(ref, 0, ref.length(), refText, width)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).build();
            talkSeconds = Math.max(7f, Math.min(20f, 4f + verse[0].length() * 0.045f));
        }

        private void spawnSparks(float x, float y) {
            int[] colors = {0xFFFFFFFF, 0xFF8FD0FF, 0xFFFFE27A};
            for (int i = 0; i < 20; i++) {
                Spark s = new Spark();
                double a = rnd.nextDouble() * Math.PI * 2;
                float speed = (80 + rnd.nextFloat() * 220) * d;
                s.x = x;
                s.y = y;
                s.vx = (float) Math.cos(a) * speed;
                s.vy = (float) Math.sin(a) * speed;
                s.life = 0.7f;
                s.color = colors[rnd.nextInt(colors.length)];
                sparks.add(s);
            }
        }

        private void updateSparks(float dt) {
            for (Iterator<Spark> it = sparks.iterator(); it.hasNext(); ) {
                Spark s = it.next();
                s.x += s.vx * dt;
                s.y += s.vy * dt;
                s.vx *= 0.93f;
                s.vy *= 0.93f;
                s.life -= dt;
                if (s.life <= 0f) it.remove();
            }
        }

        // ---------- desenho ----------
        @Override protected void onDraw(Canvas c) {
            if (released || w <= 0) return;
            long now = SystemClock.uptimeMillis();
            float dt = last == 0 ? 0.033f : Math.min(0.06f, (now - last) / 1000f);
            last = now;
            t += dt;
            if (swordSheet != null) { // só começa quando a espada terminou de carregar
                update(dt);
                drawDuck(c);
                drawSlash(c);
                drawSparks(c);
                drawSword(c);
                drawBubble(c);
            }
            postInvalidateDelayed(33); // ~30 quadros por segundo
        }

        private void drawDuck(Canvas c) {
            SpriteSheet s = duckSheet;
            if (!duckAlive || s == null) return;
            s.frame(src, (int) (t * 1000f / s.ms) % s.frames);
            float hh = duckH(), ww = hh * s.aspect;
            dst.set(dx - ww / 2f, dy - hh / 2f, dx + ww / 2f, dy + hh / 2f);
            c.save();
            if (dvx < 0f) c.scale(-1f, 1f, dx, dy); // o pato do arquivo olha pra direita
            bmp.setAlpha(255);
            c.drawBitmap(s.bitmap, src, dst, bmp);
            c.restore();
        }

        private void drawSword(Canvas c) {
            SpriteSheet s = swordSheet;
            if (s == null) return;
            s.frame(src, (int) (t * 1000f / s.ms) % s.frames);
            float hh = 112 * d, ww = hh * s.aspect;
            float bob = state == ROAM || state == TALK ? (float) Math.sin(t * 3f) * 5 * d : 0f;
            c.save();
            c.translate(sx, sy + bob);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            p.setShader(glow);
            c.drawCircle(0, 0, 70 * d, p);
            p.setShader(null);
            c.rotate(ang);
            dst.set(-ww / 2f, -hh / 2f, ww / 2f, hh / 2f);
            bmp.setAlpha(255);
            c.drawBitmap(s.bitmap, src, dst, bmp);
            c.restore();
        }

        /** O golpe do jogo (um arco largo), só que de raios azuis. */
        private void drawSlash(Canvas c) {
            if (state != SLASH) return;
            float grow = Math.min(1f, slashT / 0.30f);
            float fade = slashT < 0.30f ? 1f : Math.max(0f, 1f - (slashT - 0.30f) / 0.5f);
            float radius = 95 * d, sweep = 200f * grow;
            r.set(dx - radius, dy - radius, dx + radius, dy + radius);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(22 * d);
            p.setColor(Color.argb((int) (70 * fade), 40, 120, 255));
            c.drawArc(r, slashStart, sweep, false, p);
            p.setStrokeWidth(10 * d);
            p.setColor(Color.argb((int) (170 * fade), 120, 200, 255));
            c.drawArc(r, slashStart, sweep, false, p);
            p.setStrokeWidth(3 * d);
            p.setColor(Color.argb((int) (255 * fade), 255, 255, 255));
            c.drawArc(r, slashStart, sweep, false, p);
            for (int i = 0; i < 7; i++) { // raios saindo do arco
                double a = Math.toRadians(slashStart + sweep * rnd.nextFloat());
                float k = 1.35f + rnd.nextFloat() * 0.5f;
                bolt(c, dx + (float) Math.cos(a) * radius, dy + (float) Math.sin(a) * radius,
                        dx + (float) Math.cos(a) * radius * k, dy + (float) Math.sin(a) * radius * k, fade);
            }
            for (int i = 0; i < 2; i++) { // raios atravessando o alvo
                double a = Math.toRadians(slashStart + sweep * rnd.nextFloat());
                bolt(c, dx + (float) Math.cos(a) * radius, dy + (float) Math.sin(a) * radius,
                        dx - (float) Math.cos(a) * radius * 0.6f, dy - (float) Math.sin(a) * radius * 0.6f, fade);
            }
            if (slashT > 0.26f && slashT < 0.40f) { // clarão do impacto
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.argb(110, 255, 255, 255));
                c.drawCircle(dx, dy, 70 * d, p);
            }
        }

        private void bolt(Canvas c, float x0, float y0, float x1, float y1, float fade) {
            float ddx = x1 - x0, ddy = y1 - y0, len = (float) Math.hypot(ddx, ddy);
            if (len < 6 * d) return;
            float nx = -ddy / len, ny = ddx / len;
            path.reset();
            path.moveTo(x0, y0);
            for (int s = 1; s < 6; s++) {
                float u = s / 6f, jitter = (rnd.nextFloat() - 0.5f) * 16 * d;
                path.lineTo(x0 + ddx * u + nx * jitter, y0 + ddy * u + ny * jitter);
            }
            path.lineTo(x1, y1);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3.4f * d);
            p.setColor(Color.argb((int) (95 * fade), 60, 140, 255));
            c.drawPath(path, p);
            p.setStrokeWidth(1.4f * d);
            p.setColor(Color.argb((int) (240 * fade), 205, 235, 255));
            c.drawPath(path, p);
        }

        private void drawSparks(Canvas c) {
            p.setStyle(Paint.Style.FILL);
            for (Spark s : sparks) {
                float life = Math.min(1f, s.life / 0.7f);
                p.setColor(Color.argb((int) (255 * life), Color.red(s.color), Color.green(s.color), Color.blue(s.color)));
                c.drawCircle(s.x, s.y, 3 * d * (0.5f + life), p);
            }
        }

        /** Balão de conversa da espada, com o versículo. A pontinha aponta pra ela. */
        private void drawBubble(Canvas c) {
            if (state != TALK || verseLayout == null) return;
            float padX = 14 * d, padY = 10 * d, gap = 4 * d;
            float refH = refLayout != null ? refLayout.getHeight() + gap : 0f;
            float bw = verseLayout.getWidth() + 2 * padX, bh = verseLayout.getHeight() + refH + 2 * padY;
            float swordHalf = 56 * d;
            float bx = Math.max(8 * d, Math.min(sx - bw / 2f, w - bw - 8 * d));
            float by = sy - swordHalf - bh - 18 * d;
            boolean below = false;
            if (by < 8 * d) { by = sy + swordHalf + 18 * d; below = true; }
            float tailX = Math.max(bx + 22 * d, Math.min(sx, bx + bw - 22 * d));
            float baseY = below ? by : by + bh, tipY = below ? sy + swordHalf * 0.5f : sy - swordHalf * 0.5f;
            float radius = Math.min(bh / 2f, 22 * d);
            r.set(bx, by, bx + bw, by + bh);
            path.reset();
            path.moveTo(tailX - 9 * d, baseY);
            path.lineTo(sx, tipY);
            path.lineTo(tailX + 9 * d, baseY);
            path.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xF7FFF1CF);
            c.drawRoundRect(r, radius, radius, p);
            c.drawPath(path, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f * d);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(0xFF4A2A10);
            c.drawRoundRect(r, radius, radius, p);
            c.drawPath(path, p);
            p.setStyle(Paint.Style.FILL); // apaga o risco entre o balão e a pontinha
            p.setColor(0xF7FFF1CF);
            c.drawRect(tailX - 8 * d, baseY - (below ? 0f : 2.2f * d), tailX + 8 * d, baseY + (below ? 2.2f * d : 0f), p);
            c.save();
            c.translate(bx + padX, by + padY);
            verseLayout.draw(c);
            if (refLayout != null) {
                c.translate(0, verseLayout.getHeight() + gap);
                refLayout.draw(c);
            }
            c.restore();
        }
    }
}
