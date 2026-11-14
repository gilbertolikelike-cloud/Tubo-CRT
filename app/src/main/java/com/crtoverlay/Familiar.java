package com.crtoverlay;

import android.content.Context;
import android.content.SharedPreferences;
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
import android.media.MediaPlayer;
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
 * De vez em quando aparece um pato; a espada corre atrás, gira no lugar, recua um pouco e dispara em linha reta
 * (a estocada do jogo, com o rastro verde). Às vezes erra. Quando acerta, um balão de conversa mostra um versículo sorteado.
 * O Alucard aparece raramente. Tocar na tela acaba com tudo.
 * A janela é de tela cheia e não pega toque.
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
        // máquina de estados parecida com a do jogo: IDLE, SCAN (perseguir), WIND-UP, STRIKE, RECOVERY (+ fala)
        private static final int ROAM = 0, CHASE = 1, WINDUP = 2, STRIKE = 3, RECOVER = 4, TALK = 5;
        private static final float SWORD_DP = 112f;        // comprimento da espada na tela
        private static final float WINDUP_S = 0.55f;       // giro no lugar antes da estocada (nível baixo)
        private static final float STRIKE_SPEED_DP = 520f; // velocidade da estocada
        private static final float STRIKE_DIST_DP = 520f;  // até onde a estocada vai (bate na borda e volta)

        private final float d;
        private final SharedPreferences sp;
        private final Random rnd = new Random();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG);
        // texto do balão: letra preta com contorno branco por baixo ("destaque"), fonte sem serifa
        private final TextPaint verseText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint verseHalo = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint refText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint refHalo = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF(), dst = new RectF();
        private final Rect src = new Rect();
        private final List<Spark> sparks = new ArrayList<>();
        private final ArrayList<float[]> trail = new ArrayList<>(); // x, y, tempo, ângulo: rastro verde da espada
        private final float[] bladeX = new float[128], bladeY = new float[128];
        private volatile SpriteSheet swordSheet, trailSheet, duckSheet, alucardSheet;
        private volatile Verses verses;
        private volatile boolean released;

        private float w, h, t, talkSeconds = 12f;
        private long last;
        private int state = ROAM;
        // espada
        private float sx, sy, svx, svy, ang, tx, ty, nextWaypoint, chaseResumeAt;
        private float windT, angVel, dirX, dirY, traveled, recoverT;
        private boolean willMiss, popped;
        private int missStreak;
        // pato
        private boolean duckAlive;
        private float dx, dy, dvx, dvy, duckTurnAt, duckSpawnAt, duckChaseAt;
        // fala
        private float talkUntil;
        private StaticLayout verseLayout, haloLayout, refLayout, refHaloLayout;
        // Alucard (raro)
        private boolean alucardOn;
        private float alucardX, alucardStart, alucardUntil, alucardNextAt;
        // versículo: o 1º sai logo; depois espera 5, 7 e 10 min (e de 10 em 10). Tocar na tela recomeça tudo.
        private int verseCount;
        private final Context appCtx;
        private volatile Medals medals;
        private int level = 1;
        private float levelUpUntil;
        private float nextVerseAt;
        // batalha da noite: a espada azul de raio contra a espada que mata os patos
        private static final int DUEL = 6;
        private volatile SpriteSheet nightSheet;
        private boolean duelOn;
        private int duelPhase, duelRound;
        private float duelT, duelCooldown, nx, ny, nang, nAngVel, nAlpha, cxc, cyc, clashX, clashY;
        private final ArrayList<float[]> trail2 = new ArrayList<>();
        private float nvxK, nvyK; // recuo da espada azul depois do choque

        FamiliarView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            sp = c.getSharedPreferences("crt", Context.MODE_PRIVATE);
            appCtx = c.getApplicationContext();
            level = GameState.level(sp);
            Typeface sans = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            Typeface sansBold = Typeface.create("sans-serif", Typeface.BOLD);
            verseText.setColor(0xFF000000);
            verseText.setTypeface(sans);
            verseHalo.setColor(0xFFFFFFFF);
            verseHalo.setTypeface(sans);
            verseHalo.setStyle(Paint.Style.STROKE);
            verseHalo.setStrokeJoin(Paint.Join.ROUND);
            refText.setColor(0xFF0B2A5A);
            refText.setTypeface(sansBold);
            refHalo.setColor(0xFFFFFFFF);
            refHalo.setTypeface(sansBold);
            refHalo.setStyle(Paint.Style.STROKE);
            refHalo.setStrokeJoin(Paint.Join.ROUND);
            duckSpawnAt = 5f + rnd.nextFloat() * 4f;
            alucardNextAt = 300f + rnd.nextFloat() * 120f; // só depois de uns 5 minutos
            final Context app = c.getApplicationContext();
            new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                SpriteSheet sword = SpriteSheet.load(app, "familiar", 1);
                SpriteSheet trailS = SpriteSheet.load(app, "sword_trail", 1);
                SpriteSheet duck = SpriteSheet.load(app, "duck_target", 2);
                SpriteSheet alucard = SpriteSheet.load(app, "alucard", 1);
                SpriteSheet night = SpriteSheet.load(app, "sword", 2);
                Verses v = Verses.load(app);
                if (released) {
                    for (SpriteSheet s : new SpriteSheet[]{sword, trailS, duck, alucard, night}) if (s != null) s.recycle();
                    return;
                }
                trailSheet = trailS;
                duckSheet = duck;
                alucardSheet = alucard;
                nightSheet = night;
                verses = v;
                medals = v != null ? new Medals(sp, v.size()) : null;
                swordSheet = sword;
            }).start();
        }

        void release() {
            released = true;
            SpriteSheet[] all = {swordSheet, trailSheet, duckSheet, alucardSheet, nightSheet};
            swordSheet = null;
            trailSheet = null;
            duckSheet = null;
            alucardSheet = null;
            nightSheet = null;
            verses = null;
            for (SpriteSheet s : all) if (s != null) s.recycle();
        }

        @Override protected void onSizeChanged(int nw, int nh, int ow, int oh) {
            w = nw;
            h = nh;
            sx = 80 * d + rnd.nextFloat() * Math.max(1f, w - 160 * d); // aparece em qualquer lugar da tela
            sy = 80 * d + rnd.nextFloat() * Math.max(1f, h - 160 * d);
            tx = sx;
            ty = sy;
            spawnSparks(sx, sy, 16);
        }

        // ---------- lógica ----------
        private float duckH() { return 84 * d; }

        private float swordLen() { return SWORD_DP * d; }

        private float alucardH() { return 100 * d; }

        private float alucardFeetY() { return h - 64 * d; }

        private static float wrap(float a) {
            while (a > 180f) a -= 360f;
            while (a < -180f) a += 360f;
            return a;
        }

        /** Ângulo pra ponta da espada (desenhada pra baixo) apontar na direção (ux, uy). */
        private static float tipAngle(float ux, float uy) {
            return (float) Math.toDegrees(Math.atan2(-ux, uy));
        }

        private void update(float dt) {
            updateAlucard();
            switch (state) {
                case ROAM:
                    roam(dt);
                    if (!duckAlive && t >= duckSpawnAt && duckSheet != null) {
                        if (nightSheet != null && isNight() && t >= duelCooldown && rnd.nextFloat() < 0.10f) startDuel();
                        else spawnDuck();
                    }
                    if (duckAlive && t >= duckChaseAt && t >= chaseResumeAt) { state = CHASE; rollMiss(); }
                    break;
                case CHASE: chase(dt); break;
                case WINDUP: windup(dt); break;
                case STRIKE: strike(dt); break;
                case RECOVER: recover(dt); break;
                case DUEL: duel(dt); break;
                default: talk(dt); break;
            }
            moveDuck(dt);
            updateSparks(dt);
            updateTrail();
        }

        /** O Alucard é raro: entra, fica parado uns segundos e sai. Enquanto ele está aí, a espada voa em volta dele. */
        private void updateAlucard() {
            if (alucardSheet == null) return;
            if (!alucardOn && t >= alucardNextAt) {
                alucardOn = true;
                alucardX = w * (0.12f + rnd.nextFloat() * 0.76f);
                alucardStart = t;
                alucardUntil = t + 10f + rnd.nextFloat() * 7f;
            } else if (alucardOn && t >= alucardUntil) {
                alucardOn = false;
                alucardNextAt = t + 240f + rnd.nextFloat() * 240f;
            }
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
            float want = now > 60 * d ? tipAngle(svx, svy) : (float) Math.sin(t * 2f) * 8f;
            ang += wrap(want - ang) * Math.min(1f, 7f * dt);
        }

        private void roam(float dt) {
            if (t >= nextWaypoint) {
                float m = 60 * d;
                if (alucardOn) { // orbita o Alucard, como no jogo
                    tx = alucardX + (rnd.nextFloat() - 0.5f) * 260 * d;
                    ty = alucardFeetY() - alucardH() * 1.05f + (rnd.nextFloat() - 0.5f) * 130 * d;
                    tx = Math.max(m, Math.min(w - m, tx));
                    ty = Math.max(m, Math.min(h - m, ty));
                } else {
                    tx = m + rnd.nextFloat() * (w - 2 * m);
                    ty = m + rnd.nextFloat() * (h - 2 * m);
                }
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
            duckChaseAt = t + 1.4f;
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
            dx += dvx * dt;
            dy += dvy * dt;
            float m = 40 * d;
            if (dx < m) { dx = m; dvx = Math.abs(dvx); }
            if (dx > w - m) { dx = w - m; dvx = -Math.abs(dvx); }
            if (dy < m * 1.6f) { dy = m * 1.6f; dvy = Math.abs(dvy); }
            if (dy > h - m * 1.6f) { dy = h - m * 1.6f; dvy = -Math.abs(dvy); }
        }

        /** Sorteia se a próxima estocada erra (4 em 10). Depois de errar 4 vezes seguidas, a próxima acerta. Mesmo sem sorteio de erro, o pato pode sair da frente. */
        private void rollMiss() {
            willMiss = missStreak < 4 && rnd.nextFloat() < 0.40f;
        }

        /** SCAN: chega perto do pato (sem encostar) e começa a preparar a estocada. */
        private void chase(float dt) {
            if (!duckAlive) { state = ROAM; return; }
            float vx = sx - dx, vy = sy - dy, dist = (float) Math.hypot(vx, vy);
            if (dist < 1f) { vx = 1f; vy = 0f; dist = 1f; }
            float standoff = 230 * d;
            steer(dx + vx / dist * standoff, dy + vy / dist * standoff, 300 * d, 1100 * d, dt);
            if (Math.hypot(dx + vx / dist * standoff - sx, dy + vy / dist * standoff - sy) < 45 * d) {
                // WIND-UP: trava a mira no pato, zera a velocidade e começa a girar no lugar
                float ux = dx - sx, uy = dy - sy, len = Math.max(1f, (float) Math.hypot(ux, uy));
                ux /= len;
                uy /= len;
                if (willMiss) { // erra de propósito: gira a mira de 22 a 35 graus pra um dos lados
                    double off = Math.toRadians((18 + rnd.nextFloat() * 24) * (rnd.nextBoolean() ? 1 : -1));
                    float c0 = (float) Math.cos(off), s0 = (float) Math.sin(off);
                    float nx = ux * c0 - uy * s0, ny = ux * s0 + uy * c0;
                    ux = nx;
                    uy = ny;
                }
                dirX = ux;
                dirY = uy;
                svx = 0f;
                svy = 0f;
                windT = 0f;
                angVel = 0f;
                popped = false;
                state = WINDUP;
            }
        }

        private void windup(float dt) {
            windT += dt;
            angVel = Math.min(1500f, angVel + 2600f * dt); // acelera o giro
            ang += angVel * dt;
            if (windT > WINDUP_S - 0.12f) { // micro-recuo: a lâmina anda um pouco pra trás antes de sair
                sx -= dirX * 70 * d * dt;
                sy -= dirY * 70 * d * dt;
            }
            if (windT >= WINDUP_S) {
                ang = tipAngle(dirX, dirY); // estocada: a ponta aponta exatamente pro alvo
                svx = dirX * STRIKE_SPEED_DP * d;
                svy = dirY * STRIKE_SPEED_DP * d;
                traveled = 0f;
                state = STRIKE;
            }
        }

        private void strike(float dt) {
            float step = STRIKE_SPEED_DP * d * dt;
            sx += dirX * step;
            sy += dirY * step;
            traveled += step;
            // ricochete: bate na borda da tela e volta espelhada, em vez de atravessar
            float m = 24 * d;
            boolean bounced = false;
            if (sx < m) { sx = m; dirX = Math.abs(dirX); bounced = true; }
            else if (sx > w - m) { sx = w - m; dirX = -Math.abs(dirX); bounced = true; }
            if (sy < m) { sy = m; dirY = Math.abs(dirY); bounced = true; }
            else if (sy > h - m) { sy = h - m; dirY = -Math.abs(dirY); bounced = true; }
            if (bounced) {
                svx = dirX * STRIKE_SPEED_DP * d;
                svy = dirY * STRIKE_SPEED_DP * d;
                ang = tipAngle(dirX, dirY);
                spawnSparks(sx, sy, 8);
            }
            if (!willMiss && !popped && duckAlive && Math.hypot(dx - sx, dy - sy) < 52 * d) { // acertou
                popped = true;
                duckAlive = false;
                missStreak = 0;
                spawnSparks(dx, dy, 20);
                if (GameState.addKill(sp, 60)) { // pato morto: soma kills e EXP; pode subir de nível
                    level = GameState.level(sp);
                    levelUpUntil = t + 2.5f;
                    playLevelUp();
                    spawnSparks(sx, sy, 30);
                }
                if (t >= nextVerseAt) { // ainda não deu o tempo do próximo versículo? então só mata o pato
                    chooseVerse();
                    float[] waitMin = {5f, 7f, 10f, 10f};
                    nextVerseAt = t + waitMin[Math.min(verseCount, 3)] * 60f;
                    verseCount++;
                }
            }
            if (traveled >= STRIKE_DIST_DP * d) {
                state = RECOVER;
                recoverT = 0f;
            }
        }

        private void recover(float dt) {
            recoverT += dt;
            float k = (float) Math.pow(0.02, dt); // freia rápido
            svx *= k;
            svy *= k;
            sx += svx * dt;
            sy += svy * dt;
            ang += wrap(0f - ang) * Math.min(1f, 5f * dt);
            if (recoverT >= 0.5f) {
                if (popped && verseLayout != null) {
                    state = TALK;
                    talkUntil = t + talkSeconds;
                    duckSpawnAt = talkUntil + 10f + rnd.nextFloat() * 12f;
                } else if (popped) {
                    state = ROAM;
                    nextWaypoint = t;
                    duckSpawnAt = t + 10f + rnd.nextFloat() * 12f;
                } else { // errou: respira e tenta de novo
                    missStreak++;
                    state = ROAM;
                    chaseResumeAt = t + 0.8f;
                    duckChaseAt = t;
                    nextWaypoint = t;
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
                haloLayout = null;
                refLayout = null;
                refHaloLayout = null;
                nextWaypoint = t;
            }
        }

        private void chooseVerse() {
            Verses v = verses;
            Medals md = medals;
            boolean fresh = false;
            String[] verse;
            if (v != null) { // sorteia entre os versículos que ainda não deram medalha
                int idx = md != null ? md.randomLocked(rnd) : -1;
                if (idx < 0) idx = rnd.nextInt(v.size());
                else if (md.unlock(idx)) fresh = true;
                verse = v.get(idx);
            } else {
                verse = new String[]{"O SENHOR é o meu pastor; nada me faltará.", "Salmos 23:1"};
            }
            float size = Math.max(10, Math.min(26, sp.getInt("verse_size", 14))) * d; // tamanho da letra: slider em ▦
            verseText.setTextSize(size);
            verseHalo.setTextSize(size);
            verseHalo.setStrokeWidth(Math.max(2f, size * 0.22f));
            refText.setTextSize(size * 0.88f);
            refHalo.setTextSize(size * 0.88f);
            refHalo.setStrokeWidth(Math.max(2f, size * 0.2f));
            int width = (int) Math.min(w * 0.76f, 300 * d);
            String text = verse[0], ref = verse[1].isEmpty() ? "" : "\u2014 " + verse[1] + (fresh ? "  (nova medalha!)" : "");
            verseLayout = layout(text, verseText, width);
            haloLayout = layout(text, verseHalo, width);
            refLayout = ref.isEmpty() ? null : layout(ref, refText, width);
            refHaloLayout = ref.isEmpty() ? null : layout(ref, refHalo, width);
            talkSeconds = Math.max(12f, Math.min(45f, 8f + text.length() * 0.085f)); // fica bastante tempo na tela
        }

        private StaticLayout layout(String text, TextPaint paint, int width) {
            return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).build();
        }

        private void spawnSparks(float x, float y, int count) {
            int[] colors = {0xFFFFFFFF, 0xFFB8FFC4, 0xFF7CFF8A, 0xFF8FC8FF};
            for (int i = 0; i < count; i++) {
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

        private float bobNow() {
            return state == ROAM || state == TALK ? (float) Math.sin(t * 3f) * 5 * d : 0f;
        }

        // ---------- batalha da noite ----------
        private boolean isNight() {
            int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            return hour >= 19 || hour < 6; // de noite, depois das 19h
        }

        /** Ângulo pra ponta de uma espada desenhada pra CIMA apontar na direção (ux, uy). */
        private static float tipUpAngle(float ux, float uy) {
            return (float) Math.toDegrees(Math.atan2(ux, -uy));
        }

        private void startDuel() {
            duelOn = true;
            state = DUEL;
            duelRound = 0;
            nAlpha = 0f;
            beginRound();
            nx = cxc + 150 * d;
            ny = cyc;
            spawnSparks(nx, ny, 16);
        }

        /** Escolhe o ponto da luta: cada espada vai pra um lado dele e gira no lugar. */
        private void beginRound() {
            duelPhase = 0;
            duelT = 0f;
            float m = 190 * d;
            cxc = m + rnd.nextFloat() * Math.max(1f, w - 2 * m);
            cyc = m + rnd.nextFloat() * Math.max(1f, h - 2 * m);
            nAngVel = 0f;
            svx = 0f;
            svy = 0f;
        }

        private void duel(float dt) {
            duelT += dt;
            float side = 150 * d;
            if (duelPhase == 3) { // acabou: a espada azul some
                nAlpha -= 1.6f * dt;
                if (nAlpha <= 0f) {
                    duelOn = false;
                    state = ROAM;
                    nextWaypoint = t;
                    duelCooldown = t + 120f;
                    duckSpawnAt = t + 4f + rnd.nextFloat() * 4f;
                    trail2.clear();
                }
                return;
            }
            nAlpha = Math.min(1f, nAlpha + 2f * dt);
            if (duelPhase == 0) { // cara a cara, girando
                float k = Math.min(1f, 5f * dt);
                sx += ((cxc - side) - sx) * k;
                sy += (cyc - sy) * k;
                nx += ((cxc + side) - nx) * k;
                ny += (cyc - ny) * k;
                nAngVel = Math.min(1400f, nAngVel + 2200f * dt);
                ang += nAngVel * dt;
                nang -= nAngVel * dt;
                if (duelT >= 1.0f) {
                    float ux = nx - sx, uy = ny - sy, len = Math.max(1f, (float) Math.hypot(ux, uy));
                    dirX = ux / len;
                    dirY = uy / len;
                    ang = tipAngle(dirX, dirY);
                    nang = tipUpAngle(-dirX, -dirY);
                    svx = dirX * 640 * d;
                    svy = dirY * 640 * d;
                    duelPhase = 1;
                    duelT = 0f;
                }
            } else if (duelPhase == 1) { // as duas disparam uma na outra
                float step = 640 * d * dt;
                sx += dirX * step;
                sy += dirY * step;
                nx -= dirX * step;
                ny -= dirY * step;
                if (Math.hypot(nx - sx, ny - sy) < 50 * d || duelT > 0.9f) {
                    clashX = (sx + nx) / 2f;
                    clashY = (sy + ny) / 2f;
                    spawnSparks(clashX, clashY, 26); // o choque
                    svx = -dirX * 300 * d;
                    svy = -dirY * 300 * d;
                    nvxK = dirX * 300 * d;
                    nvyK = dirY * 300 * d;
                    duelPhase = 2;
                    duelT = 0f;
                }
            } else { // recuo depois do choque
                float k = (float) Math.pow(0.03, dt);
                svx *= k;
                svy *= k;
                nvxK *= k;
                nvyK *= k;
                sx += svx * dt;
                sy += svy * dt;
                nx += nvxK * dt;
                ny += nvyK * dt;
                ang += wrap(0f - ang) * Math.min(1f, 6f * dt);
                nang += wrap(0f - nang) * Math.min(1f, 6f * dt);
                if (duelT >= 0.55f) {
                    duelRound++;
                    if (duelRound >= 4) { duelPhase = 3; duelT = 0f; }
                    else beginRound();
                }
            }
        }

        private void drawNightSword(Canvas c) {
            SpriteSheet s = nightSheet;
            if (!duelOn || s == null || nAlpha <= 0f) return;
            s.frame(src, (int) (t * 1000f / s.ms) % s.frames);
            float hh = swordLen(), ww = hh * s.aspect;
            c.save();
            c.translate(nx, ny);
            c.rotate(nang);
            dst.set(-ww / 2f, -hh / 2f, ww / 2f, hh / 2f);
            bmp.setAlpha((int) (255 * Math.min(1f, nAlpha)));
            c.drawBitmap(s.bitmap, src, dst, bmp);
            bmp.setAlpha(255);
            c.restore();
        }

        /** Rastro azul da espada de raio, na investida. */
        private void drawBlueTrail(Canvas c) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            float[] prev = null;
            for (float[] pt : trail2) {
                if (prev != null) {
                    float life = Math.max(0f, 1f - (t - pt[2]) / 0.4f);
                    p.setStrokeWidth(16 * d);
                    p.setColor(Color.argb((int) (55 * life), 60, 130, 255));
                    c.drawLine(prev[0], prev[1], pt[0], pt[1], p);
                    p.setStrokeWidth(7 * d);
                    p.setColor(Color.argb((int) (150 * life), 120, 190, 255));
                    c.drawLine(prev[0], prev[1], pt[0], pt[1], p);
                    p.setStrokeWidth(2.2f * d);
                    p.setColor(Color.argb((int) (230 * life), 235, 245, 255));
                    c.drawLine(prev[0], prev[1], pt[0], pt[1], p);
                }
                prev = pt;
            }
        }

        /** Anel de choque quando as duas espadas se batem. */
        private void drawClash(Canvas c) {
            if (!duelOn || duelPhase != 2 || duelT > 0.4f) return;
            float life = 1f - duelT / 0.4f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4 * d * life + 1f);
            p.setColor(Color.argb((int) (230 * life), 235, 245, 255));
            c.drawCircle(clashX, clashY, 20 * d + duelT * 420 * d, p);
            if (duelT < 0.12f) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.argb((int) (140 * (1f - duelT / 0.12f)), 255, 255, 255));
                c.drawCircle(clashX, clashY, 60 * d, p);
            }
        }

        /** Guarda onde a espada passou, pro rastro verde (só quando ela está rápida). */
        private void updateTrail() {
            if (Math.hypot(svx, svy) > 90 * d) trail.add(new float[]{sx, sy + bobNow(), t, ang});
            while (!trail.isEmpty() && t - trail.get(0)[2] > 0.4f) trail.remove(0);
            if (duelOn && duelPhase == 1) trail2.add(new float[]{nx, ny, t});
            while (!trail2.isEmpty() && t - trail2.get(0)[2] > 0.4f) trail2.remove(0);
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
                drawAlucard(c);
                drawDuck(c);
                drawTrail(c);
                drawBlueTrail(c);
                drawSparks(c);
                drawNightSword(c);
                drawSword(c);
                drawLevelUp(c);
                drawClash(c);
                drawBubble(c);
            }
            postInvalidateOnAnimation(); // acompanha a tela: 50 a 60 quadros por segundo
        }

        private void drawAlucard(Canvas c) {
            SpriteSheet s = alucardSheet;
            if (!alucardOn || s == null) return;
            float fade = Math.max(0f, Math.min(1f, Math.min((t - alucardStart) / 0.6f, (alucardUntil - t) / 0.6f)));
            s.frame(src, (int) (t * 1000f / s.ms) % s.frames);
            float hh = alucardH(), ww = hh * s.aspect, feet = alucardFeetY();
            dst.set(alucardX - ww / 2f, feet - hh, alucardX + ww / 2f, feet);
            c.save();
            if (alucardX > w / 2f) c.scale(-1f, 1f, alucardX, feet); // olha pro meio da tela
            bmp.setAlpha((int) (255 * fade));
            c.drawBitmap(s.bitmap, src, dst, bmp);
            c.restore();
            bmp.setAlpha(255);
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

        /** Rastro verde: faixa que afina até sumir, 3 cópias fantasma da espada e, na estocada, o rastro do arquivo "sprite ataque espada". */
        private void drawTrail(Canvas c) {
            int n = Math.min(trail.size(), 128);
            float off = 0.19f * swordLen(); // o rastro sai do meio da LÂMINA (a parte azul), não do cabo
            for (int i = 0; i < n; i++) {
                float[] e = trail.get(i);
                double rad = Math.toRadians(e[3]);
                bladeX[i] = e[0] - (float) Math.sin(rad) * off;
                bladeY[i] = e[1] + (float) Math.cos(rad) * off;
            }
            if (n >= 2) {
                for (int layer = 0; layer < 3; layer++) { // 3 camadas de brilho, da mais larga e fraca à mais fina e forte
                    float maxW = (layer == 0 ? 17f : layer == 1 ? 9f : 3.2f) * d;
                    int col = layer == 0 ? Color.argb(55, 60, 255, 110) : layer == 1 ? Color.argb(130, 124, 255, 138) : Color.argb(220, 239, 255, 234);
                    path.reset();
                    for (int i = 0; i < n; i++) { // lado esquerdo, da ponta do rastro até a lâmina
                        int nxt = Math.min(n - 1, i + 1), prv = Math.max(0, i - 1);
                        float vx = bladeX[nxt] - bladeX[prv], vy = bladeY[nxt] - bladeY[prv], len = Math.max(0.001f, (float) Math.hypot(vx, vy));
                        float half = maxW * Math.max(0f, 1f - (t - trail.get(i)[2]) / 0.4f) * 0.5f;
                        float px = bladeX[i] - vy / len * half, py = bladeY[i] + vx / len * half;
                        if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
                    }
                    for (int i = n - 1; i >= 0; i--) { // lado direito, de volta
                        int nxt = Math.min(n - 1, i + 1), prv = Math.max(0, i - 1);
                        float vx = bladeX[nxt] - bladeX[prv], vy = bladeY[nxt] - bladeY[prv], len = Math.max(0.001f, (float) Math.hypot(vx, vy));
                        float half = maxW * Math.max(0f, 1f - (t - trail.get(i)[2]) / 0.4f) * 0.5f;
                        path.lineTo(bladeX[i] + vy / len * half, bladeY[i] - vx / len * half);
                    }
                    path.close();
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(col);
                    c.drawPath(path, p);
                }
                // pós-imagem: cópias translúcidas da espada nas posições de 0,05 s, 0,10 s e 0,15 s atrás
                float[] ages = {0.05f, 0.10f, 0.15f};
                float[] alphas = {0.42f, 0.24f, 0.11f};
                for (int g = 0; g < ages.length; g++) {
                    float[] best = null;
                    float bestErr = 0.03f;
                    for (float[] e : trail) {
                        float err = Math.abs((t - e[2]) - ages[g]);
                        if (err < bestErr) { bestErr = err; best = e; }
                    }
                    if (best != null) drawSwordAt(c, best[0], best[1], best[3], alphas[g], 1f);
                }
            }
            SpriteSheet s = trailSheet;
            if (state == STRIKE && s != null) { // o rastro do ataque: a ponta (linha branca) fica na espada
                s.frame(src, Math.min(s.frames - 1, level >= 90 ? 3 : level >= 80 ? 2 : level >= 50 ? 1 : 0)); // o tom do rastro sobe com o nível
                float len = 150 * d, hh = len / s.aspect;
                float fade = 0.85f * Math.max(0f, 1f - traveled / (STRIKE_DIST_DP * d) * 0.6f);
                c.save();
                c.translate(sx + dirX * 0.19f * swordLen(), sy + dirY * 0.19f * swordLen()); // na lâmina
                c.rotate((float) Math.toDegrees(Math.atan2(dirY, dirX)));
                dst.set(-len + 14 * d, -hh / 2f, 14 * d, hh / 2f);
                bmp.setAlpha((int) (255 * fade));
                c.drawBitmap(s.bitmap, src, dst, bmp);
                bmp.setAlpha(255);
                c.restore();
            }
        }

        /** A espada de pixel art do jogo (modelo do nível 1 a 49). Sem brilho em volta. */
        private void drawSword(Canvas c) {
            float appear = Math.min(1f, t / 0.6f); // nasce pequena e o brilho some em 0,6 s
            drawSwordAt(c, sx, sy + bobNow(), ang, appear, 0.4f + 0.6f * appear);
        }

        private void drawSwordAt(Canvas c, float x, float y, float angle, float alpha, float scale) {
            SpriteSheet s = swordSheet;
            if (s == null) return;
            s.frame(src, level >= 50 && s.frames > 1 ? 1 : 0); // nível 50 em diante: modelo com as duas lâminas laterais
            float hh = swordLen() / s.sh, ww = hh * s.aspect;
            c.save();
            c.translate(x, y);
            c.rotate(angle);
            c.scale(scale, scale);
            dst.set(-s.ax * ww, -s.ay * hh, (1f - s.ax) * ww, (1f - s.ay) * hh);
            bmp.setAlpha((int) (255 * Math.max(0f, Math.min(1f, alpha))));
            c.drawBitmap(s.bitmap, src, dst, bmp);
            bmp.setAlpha(255);
            c.restore();
        }

        /** "LEVEL UP!" sobe e some em cima da espada. */
        private void drawLevelUp(Canvas c) {
            if (t >= levelUpUntil) return;
            float life = (levelUpUntil - t) / 2.5f; // 1 -> 0
            int alpha = (int) (255 * Math.min(1f, life * 2f));
            float y = sy - 70 * d - (1f - life) * 40 * d;
            String text = "LEVEL UP!  LV " + level;
            p.setShader(null);
            p.setTextAlign(Paint.Align.CENTER);
            p.setFakeBoldText(true);
            p.setTextSize(17 * d);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4 * d);
            p.setColor(Color.argb(alpha, 0, 0, 0));
            c.drawText(text, sx, y, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(alpha, 255, 214, 90));
            c.drawText(text, sx, y, p);
            p.setTextAlign(Paint.Align.LEFT);
            p.setFakeBoldText(false);
        }

        private void playLevelUp() {
            try {
                MediaPlayer m = MediaPlayer.create(appCtx, R.raw.level_up);
                if (m == null) return;
                m.setOnCompletionListener(MediaPlayer::release);
                m.start();
            } catch (RuntimeException ignored) {
                // sem som de level up: o resto continua
            }
        }

        private void drawSparks(Canvas c) {
            p.setStyle(Paint.Style.FILL);
            for (Spark s : sparks) {
                float life = Math.min(1f, s.life / 0.7f);
                p.setColor(Color.argb((int) (255 * life), Color.red(s.color), Color.green(s.color), Color.blue(s.color)));
                c.drawCircle(s.x, s.y, 3 * d * (0.5f + life), p);
            }
        }

        /** Balão de conversa azul, com letra preta e contorno branco. A pontinha aponta pra espada. */
        private void drawBubble(Canvas c) {
            if (state != TALK || verseLayout == null) return;
            float padX = 14 * d, padY = 10 * d, gap = 4 * d;
            float refH = refLayout != null ? refLayout.getHeight() + gap : 0f;
            float bw = verseLayout.getWidth() + 2 * padX, bh = verseLayout.getHeight() + refH + 2 * padY;
            float swordHalf = swordLen() / 2f;
            float bx = Math.max(8 * d, Math.min(sx - bw / 2f, w - bw - 8 * d));
            float by = sy - swordHalf - bh - 18 * d;
            boolean below = false;
            if (by < 8 * d) { by = sy + swordHalf + 18 * d; below = true; }
            if (below && by + bh > h - 8 * d) by = Math.max(8 * d, h - bh - 8 * d);
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
            p.setColor(0xFFA9D6FF);
            c.drawRoundRect(r, radius, radius, p);
            c.drawPath(path, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.2f * d);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(0xFF1F4E8C);
            c.drawRoundRect(r, radius, radius, p);
            c.drawPath(path, p);
            p.setStyle(Paint.Style.FILL); // apaga o risco entre o balão e a pontinha
            p.setColor(0xFFA9D6FF);
            c.drawRect(tailX - 8 * d, baseY - (below ? 0f : 2.4f * d), tailX + 8 * d, baseY + (below ? 2.4f * d : 0f), p);
            c.save();
            c.translate(bx + padX, by + padY);
            haloLayout.draw(c);   // contorno branco primeiro
            verseLayout.draw(c);  // letra preta por cima
            if (refLayout != null) {
                c.translate(0, verseLayout.getHeight() + gap);
                refHaloLayout.draw(c);
                refLayout.draw(c);
            }
            c.restore();
        }
    }
}
