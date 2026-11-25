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
        FamiliarView v = new FamiliarView(ctx, host);
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
    /** A espada do painel (o Bob de noite): informa onde ela está e some do painel enquanto ela luta. */
    interface Host {
        boolean swordAvailable();

        /** {centro x, centro y, altura} da espada do painel, em pixels. */
        float[] swordState();

        void setSwordHidden(boolean hidden);
    }

    private Host host;

    void setHost(Host h) { host = h; }

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
        private float strikeSpeed, strikeLimit, hitStop; // peso: a estocada arranca devagar e ganha velocidade
        private boolean bankActive, bankedOnce;
        private int bankAt = 4; // depois de 3 a 5 erros seguidos, vem o tiro de ricochete
        private boolean willMiss, popped;
        private int missStreak;
        // pato
        private boolean duckAlive;
        private float dx, dy, dvx, dvy, duckTurnAt, duckSpawnAt, duckChaseAt;
        // fala
        private float talkUntil;
        private StaticLayout verseLayout, haloLayout, refLayout, refHaloLayout, labelLayout, labelHaloLayout;
        private final TextPaint labelText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint labelHalo = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        // Alucard (raro)
        private boolean alucardOn;
        private float alucardX, alucardStart, alucardUntil, alucardNextAt;
        // versículo: o 1º sai logo; depois espera 5, 7 e 10 min (e de 10 em 10). Tocar na tela recomeça tudo.
        private int verseCount;
        private final Context appCtx;
        private int level = 1;
        private float levelUpUntil;
        private float nextVerseAt;
        // batalha da noite: a espada azul de raio contra a espada que mata os patos
        private static final int DUEL = 6;
        private volatile SpriteSheet nightSheet;
        private boolean duelOn;
        private final Host host;
        private float dockX, dockY, duelH, hpMax0 = 100f, hpMax1 = 120f, duelAge, appearAt;
        private final float[] hp = new float[2], kvx = new float[2], kvy = new float[2];
        private int attacker, comboLeft, loser;
        private boolean lastHit, greenDead, blueDead;
        private int duelPhase, duelRound;
        private float duelT, duelCooldown, nx, ny, nang, nAngVel, nAlpha, cxc, cyc, clashX, clashY;
        private final ArrayList<float[]> trail2 = new ArrayList<>();
        private float nvxK, nvyK; // recuo da espada azul depois do choque

        FamiliarView(Context c, Host hostRef) {
            super(c);
            host = hostRef;
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
            labelText.setColor(0xFF5A1E00);
            labelText.setTypeface(sansBold);
            labelHalo.setColor(0xFFFFFFFF);
            labelHalo.setTypeface(sansBold);
            labelHalo.setStyle(Paint.Style.STROKE);
            labelHalo.setStrokeJoin(Paint.Join.ROUND);
            duckSpawnAt = 5f + rnd.nextFloat() * 4f;
            alucardNextAt = Float.MAX_VALUE; // o Alucard só vai aparecer como boss, no jogo
            final Context app = c.getApplicationContext();
            new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                SpriteSheet sword = SpriteSheet.load(app, "familiar", 1);
                SpriteSheet trailS = SpriteSheet.load(app, "sword_trail", 1);
                SpriteSheet duck = SpriteSheet.load(app, "duck_target", 1);
                SpriteSheet alucard = null; // não carrega: ele só vai aparecer como boss
                SpriteSheet night = SpriteSheet.load(app, "sword", 1);
                Verses v = Verses.shared(app);
                if (released) {
                    for (SpriteSheet s : new SpriteSheet[]{sword, trailS, duck, alucard, night}) if (s != null) s.recycle();
                    return;
                }
                trailSheet = trailS;
                duckSheet = duck;
                alucardSheet = alucard;
                nightSheet = night;
                verses = v;
                swordSheet = sword;
            }).start();
        }

        void release() {
            if (duelOn && host != null) host.setSwordHidden(false); // a espada do painel volta pro lugar dela
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
        private float duckH() { return 56 * d; } // pequeno: o boss é que vai ser grande

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
            if (hitStop > 0f) { // impacto: congela um instante
                hitStop -= dt;
                updateSparks(dt);
                return;
            }
            updateAlucard();
            switch (state) {
                case ROAM:
                    roam(dt);
                    if (!duckAlive && t >= duckSpawnAt && duckSheet != null) {
                        if (nightSheet != null && host != null && host.swordAvailable() && isNight() && t >= duelCooldown && rnd.nextFloat() < 0.10f) startDuel();
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
            ang += wrap(want - ang) * Math.min(1f, 4.5f * dt); // gira devagarinho: tem peso
        }

        private void roam(float dt) {
            // estacionada: só balança de leve. Ela só sai do lugar quando aparece um pato.
            svx = 0f;
            svy = 0f;
            ang += wrap((float) Math.sin(t * 1.8f) * 5f - ang) * Math.min(1f, 3f * dt);
        }

        /** Anda em linha reta até o ponto (sem curvas): arranca, e freia perto do destino. */
        private void moveStraight(float targetX, float targetY, float speed, float dt) {
            float vx = targetX - sx, vy = targetY - sy, dist = (float) Math.hypot(vx, vy);
            if (dist < 1f) { svx = 0f; svy = 0f; return; }
            float sp2 = Math.min(speed, dist * 6f + 40 * d);
            float step = Math.min(dist, sp2 * dt);
            sx += vx / dist * step;
            sy += vy / dist * step;
            svx = vx / dist * sp2;
            svy = vy / dist * sp2;
            ang += wrap(tipAngle(vx, vy) - ang) * Math.min(1f, 5f * dt);
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
            moveStraight(dx + vx / dist * standoff, dy + vy / dist * standoff, 300 * d, dt);
            if (Math.hypot(dx + vx / dist * standoff - sx, dy + vy / dist * standoff - sy) < 45 * d) {
                // WIND-UP: trava a mira no pato, zera a velocidade e começa a girar no lugar
                float ux = dx - sx, uy = dy - sy, len = Math.max(1f, (float) Math.hypot(ux, uy));
                ux /= len;
                uy /= len;
                bankActive = false;
                boolean far = Math.hypot(dx - sx, dy - sy) > 0.55 * Math.hypot(w, h);
                if (!willMiss && (missStreak >= bankAt || (far && rnd.nextFloat() < 0.35f))) { // tiro de ricochete
                    float[] aim = bankAim();
                    if (aim != null) {
                        ux = aim[0];
                        uy = aim[1];
                        bankActive = true;
                        bankedOnce = false;
                    }
                }
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

        /** Mira num ponto "espelhado" do pato: a parede devolve a espada exatamente na direção dele. */
        private float[] bankAim() {
            float m = 24 * d;
            int start = rnd.nextInt(4);
            for (int k = 0; k < 4; k++) {
                int wall = (start + k) % 4; // 0 esquerda, 1 direita, 2 cima, 3 baixo
                float mx, my;
                switch (wall) {
                    case 0: mx = 2 * m - dx; my = dy; break;
                    case 1: mx = 2 * (w - m) - dx; my = dy; break;
                    case 2: mx = dx; my = 2 * m - dy; break;
                    default: mx = dx; my = 2 * (h - m) - dy; break;
                }
                float ux = mx - sx, uy = my - sy, len = (float) Math.hypot(ux, uy);
                if (len < 1f) continue;
                ux /= len;
                uy /= len;
                float tHit = wall == 0 ? (m - sx) / ux : wall == 1 ? ((w - m) - sx) / ux : wall == 2 ? (m - sy) / uy : ((h - m) - sy) / uy;
                if (tHit <= 0f || Float.isNaN(tHit) || Float.isInfinite(tHit)) continue;
                float hx = sx + ux * tHit, hy = sy + uy * tHit; // onde a reta bate na parede
                boolean inside = wall < 2 ? hy > m && hy < h - m : hx > m && hx < w - m;
                if (inside) return new float[]{ux, uy};
            }
            return null;
        }

        private void windup(float dt) {
            windT += dt;
            angVel = Math.min(1500f, angVel + 2600f * dt); // acelera o giro
            ang += angVel * dt;
            if (windT > WINDUP_S - 0.12f) { // micro-recuo: a lâmina anda um pouco pra trás antes de sair
                sx -= dirX * 110 * d * dt;
                sy -= dirY * 110 * d * dt;
            }
            if (windT >= WINDUP_S) {
                ang = tipAngle(dirX, dirY); // estocada: a ponta aponta exatamente pro alvo
                strikeSpeed = STRIKE_SPEED_DP * d;
                strikeLimit = bankActive ? 1900 * d : STRIKE_DIST_DP * d;
                svx = dirX * strikeSpeed;
                svy = dirY * strikeSpeed;
                traveled = 0f;
                state = STRIKE;
            }
        }

        private void strike(float dt) {
            float ramp = Math.min(1f, 0.5f + traveled / (140 * d) * 0.5f); // arranca com peso e ganha velocidade
            float step = strikeSpeed * ramp * dt;
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
                if (bankActive && !bankedOnce) { // bateu na parede no tiro de ricochete: pega velocidade
                    bankedOnce = true;
                    strikeSpeed *= 1.7f;
                }
                svx = dirX * strikeSpeed;
                svy = dirY * strikeSpeed;
                ang = tipAngle(dirX, dirY);
                spawnSparks(sx, sy, 8);
            }
            if (bankedOnce && duckAlive && !popped) { // depois do ricochete ela mira de novo no pato
                float hx = dx - sx, hy = dy - sy, hl = Math.max(1f, (float) Math.hypot(hx, hy));
                dirX += (hx / hl - dirX) * Math.min(1f, 6f * dt);
                dirY += (hy / hl - dirY) * Math.min(1f, 6f * dt);
                float dl = Math.max(0.001f, (float) Math.hypot(dirX, dirY));
                dirX /= dl;
                dirY /= dl;
                ang = tipAngle(dirX, dirY);
                svx = dirX * strikeSpeed;
                svy = dirY * strikeSpeed;
            }
            if (!willMiss && !popped && duckAlive && Math.hypot(dx - sx, dy - sy) < 46 * d) { // acertou
                popped = true;
                duckAlive = false;
                missStreak = 0;
                bankActive = false;
                bankAt = 3 + rnd.nextInt(3); // o próximo tiro de ricochete vem entre 3 e 5 erros
                hitStop = 0.07f;            // a espada pesada "trava" um instante no impacto
                spawnSparks(dx, dy, 20);
                if (GameState.killsTotal(sp) % 50 == 49) BibleAudio.get(appCtx).triggerNext(); // a cada 50 patos: um capítulo com dublagem
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
            if (traveled >= strikeLimit) {
                state = RECOVER;
                recoverT = 0f;
            }
        }

        private void recover(float dt) {
            recoverT += dt;
            float k = (float) Math.pow(0.05, dt); // a espada pesada demora um pouco pra parar
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
                labelLayout = null;
                labelHaloLayout = null;
                refLayout = null;
                refHaloLayout = null;
                nextWaypoint = t;
            }
        }

        private void chooseVerse() {
            Verses v = verses;
            String[] verse;
            int idx = -1;
            if (v != null) {
                idx = Math.max(0, sp.getInt("verse_seq", 0)) % v.size();   // em ordem, do Gênesis ao Apocalipse: sem sorteio
                sp.edit().putInt("verse_seq", (idx + 1) % v.size()).apply();
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
            String text = verse[0], ref = verse[1].isEmpty() ? "" : "\u2014 " + verse[1] + "";
            String book = verse[1].isEmpty() ? "" : verse[1].substring(0, Math.max(0, verse[1].lastIndexOf(' ')));
            boolean nt = v != null && idx >= 0 && v.isNewTestament(v.bookOf(idx));
            android.text.SpannableString styled = new android.text.SpannableString(text); // fala de Jesus, de Deus, dos profetas e dos discípulos em cores
            for (int[] sp2 : (v != null && idx >= 0 ? VerseStyler.spans(v, idx, new int[]{sp.getInt("col_god", 0xFF0A3CA8), sp.getInt("col_jesus", 0xFFC00018),
                    sp.getInt("col_prophet", 0xFF9A6A00), sp.getInt("col_disciple", 0xFF0B5D1E), sp.getInt("col_other", 0xFF00796B)},
                    sp.getBoolean("col_names", true), sp.getBoolean("col_other_on", true)) : new java.util.ArrayList<int[]>())) {
                styled.setSpan(new android.text.style.ForegroundColorSpan(sp2[2]), sp2[0], sp2[1], android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            verseLayout = layout(styled, verseText, width);
            haloLayout = layout(text, verseHalo, width); // o contorno branco usa o texto puro (mesma forma)
            String label = book.isEmpty() ? "" : (nt ? "NOVO TESTAMENTO" : "VELHO TESTAMENTO");
            labelText.setTextSize(size * 0.74f);
            labelHalo.setTextSize(size * 0.74f);
            labelHalo.setStrokeWidth(Math.max(2f, size * 0.16f));
            labelLayout = label.isEmpty() ? null : layout(label, labelText, width);
            labelHaloLayout = label.isEmpty() ? null : layout(label, labelHalo, width);
            refLayout = ref.isEmpty() ? null : layout(ref, refText, width);
            refHaloLayout = ref.isEmpty() ? null : layout(ref, refHalo, width);
            float scale = Math.max(0.5f, Math.min(3f, sp.getInt("verse_time", 100) / 100f)); // slider em ▦
            talkSeconds = Math.max(20f, Math.min(90f, 15f + text.length() * 0.17f)) * scale; // dá tempo de ler com calma
        }

        private StaticLayout layout(CharSequence text, TextPaint paint, int width) {
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

        // ---------- batalha da noite, no estilo Vagabond ----------
        // tensão e jogo de pernas (passos curtos em linha reta), fintas, defesas, desvios, contra-ataques,
        // câmera lenta nos cortes e um golpe final em que as duas se cruzam e só uma fica de pé.
        private static final int D_INTRO = 0, D_FOOT = 1, D_ATTACK = 2, D_RESOLVE = 3, D_STANDOFF = 4, D_CROSS = 5, D_DEATH = 6, D_VICTORY = 7, D_HOME = 8;
        private static final int R_TAKE = 0, R_PARRY = 1, R_DODGE = 2, R_COUNTER = 3;
        private final float[] px = new float[2], py = new float[2], pa = new float[2], pvx = new float[2], pvy = new float[2];
        private final float[] stam = new float[2], stagger = new float[2];
        private final float[] footFromX = new float[2], footFromY = new float[2], footToX = new float[2], footToY = new float[2];
        private int atkType, react, atkLeft, winner, resolveKind;
        private boolean atkCrit, counterNext, feintBack, crossCut;
        private float footT, footDur = 0.9f, holdT, holdDur = 0.5f, circleLeft, tension, cine, slowUntil, slowScale = 1f;
        private float dashDx, dashDy, dashDist, dashTrav, dashSpeed, reactT, reactSide = 1f, reactFromX, reactFromY, footAngle, crossT, standoffT;
        private final ArrayList<float[]> slashes = new ArrayList<>(); // x, y, ângulo (rad), comprimento, início

        private boolean isNight() {
            int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            return hour >= 19 || hour < 6; // de noite, depois das 19h
        }

        /** Ângulo pra ponta de uma espada desenhada pra CIMA apontar na direção (ux, uy). */
        private static float tipUpAngle(float ux, float uy) {
            return (float) Math.toDegrees(Math.atan2(ux, -uy));
        }

        private static float clampF(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

        private void startDuel() {
            float[] st = host.swordState(); // a espada do painel de verdade: sai de lá e vai lutar
            dockX = st[0];
            dockY = st[1];
            duelH = st[2];
            host.setSwordHidden(true);
            duelOn = true;
            state = DUEL;
            duelAge = 0f;
            greenDead = false;
            blueDead = false;
            hp[0] = hpMax0 = 100f + level * 3f;
            hp[1] = hpMax1 = 120f;
            stam[0] = stam[1] = 100f;
            stagger[0] = stagger[1] = 0f;
            px[0] = sx; py[0] = sy; pa[0] = ang;
            px[1] = dockX; py[1] = dockY; pa[1] = 0f;
            pvx[0] = pvy[0] = pvx[1] = pvy[1] = 0f;
            kvx[0] = kvy[0] = kvx[1] = kvy[1] = 0f;
            nx = px[1]; ny = py[1]; nang = pa[1];
            spawnSparks(nx, ny, 14);
            float m = 230 * d;
            cxc = m + rnd.nextFloat() * Math.max(1f, w - 2 * m);
            cyc = m + rnd.nextFloat() * Math.max(1f, h - 2 * m);
            tension = 0f;
            slowUntil = 0f;
            slowScale = 1f;
            slashes.clear();
            counterNext = false;
            attacker = rnd.nextInt(2);
            duelPhase = D_INTRO;
            duelT = 0f;
            holdDur = 0.5f;
            startFoot(270 * d, 0.9f); // vão pra posição da luta, uma de cada lado
        }

        private void endDuel() {
            duelOn = false;
            state = ROAM;
            nextWaypoint = t;
            duelCooldown = t + 120f;
            duckSpawnAt = t + 4f + rnd.nextFloat() * 4f;
            trail2.clear();
            greenDead = false;
            blueDead = false;
        }

        /** Um passo do jogo de pernas: cada espada anda em linha reta até o seu lado do ponto da luta. */
        private void startFoot(float dist, float dur) {
            footAngle = duelPhase == D_INTRO ? (float) (rnd.nextDouble() * Math.PI * 2) : footAngle + (rnd.nextFloat() - 0.5f) * 1.1f;
            float m = 50 * d;
            float ux = (float) Math.cos(footAngle), uy = (float) Math.sin(footAngle);
            footToX[0] = clampF(cxc + ux * dist, m, w - m);
            footToY[0] = clampF(cyc + uy * dist, m, h - m);
            footToX[1] = clampF(cxc - ux * dist, m, w - m);
            footToY[1] = clampF(cyc - uy * dist, m, h - m);
            for (int i = 0; i < 2; i++) { footFromX[i] = px[i]; footFromY[i] = py[i]; }
            footT = 0f;
            footDur = dur;
            holdT = 0f;
        }

        private float moveFoot(float dt) {
            footT += dt;
            float k = Math.min(1f, footT / Math.max(0.01f, footDur));
            float e = k < 0.5f ? 2f * k * k : 1f - 2f * (1f - k) * (1f - k); // arranca e freia: peso, mas em linha reta
            for (int i = 0; i < 2; i++) {
                float ox = px[i], oy = py[i];
                px[i] = footFromX[i] + (footToX[i] - footFromX[i]) * e;
                py[i] = footFromY[i] + (footToY[i] - footFromY[i]) * e;
                pvx[i] = dt > 0f ? (px[i] - ox) / dt : 0f;
                pvy[i] = dt > 0f ? (py[i] - oy) / dt : 0f;
            }
            return k;
        }

        /** As duas se apontam; a ponta treme mais quanto maior a tensão. */
        private void faceEachOther(boolean tremble) {
            float ux = px[1] - px[0], uy = py[1] - py[0], len = Math.max(1f, (float) Math.hypot(ux, uy));
            ux /= len;
            uy /= len;
            float tr = tremble ? (float) Math.sin(t * 37f) * (1.2f + 2.6f * tension) : 0f;
            pa[0] = tipAngle(ux, uy) + tr;
            pa[1] = tipUpAngle(-ux, -uy) - tr;
        }

        private void clampDuel() {
            float m = 30 * d;
            for (int i = 0; i < 2; i++) {
                px[i] = clampF(px[i], m, w - m);
                py[i] = clampF(py[i], m, h - m);
            }
        }

        private void duel(float dtReal) {
            float ts = t < slowUntil ? slowScale : 1f; // câmera lenta nos cortes
            float dt = dtReal * ts;
            duelT += dt;
            duelAge += dtReal;
            cine += ((duelPhase >= D_HOME ? 0f : 1f) - cine) * Math.min(1f, 3f * dtReal);
            for (int i = 0; i < 2; i++) {
                stagger[i] = Math.max(0f, stagger[i] - dt);
                stam[i] = Math.min(100f, stam[i] + 9f * dt);
            }
            switch (duelPhase) {
                case D_INTRO:
                case D_FOOT: duelFoot(dt); break;
                case D_ATTACK: duelAttack(dt); break;
                case D_RESOLVE: duelResolve(dt); break;
                case D_STANDOFF: duelStandoff(dt); break;
                case D_CROSS: duelCross(dt); break;
                case D_DEATH: duelDeath(dt); break;
                case D_VICTORY: duelVictory(dt); break;
                default: duelHome(dt); break;
            }
            sx = px[0]; sy = py[0]; ang = pa[0];
            nx = px[1]; ny = py[1]; nang = pa[1];
            svx = pvx[0];
            svy = pvy[0];
            for (int i = slashes.size() - 1; i >= 0; i--) if (t - slashes.get(i)[4] > 0.5f) slashes.remove(i);
        }

        private void duelFoot(float dt) {
            float k = moveFoot(dt);
            faceEachOther(true);
            if (duelPhase == D_FOOT) {
                circleLeft -= dt;
                tension = Math.min(1f, tension + 0.3f * dt);
            }
            if (k < 1f) return;
            pvx[0] = pvy[0] = pvx[1] = pvy[1] = 0f;
            holdT += dt;
            if (holdT < holdDur) return;
            if (duelPhase == D_INTRO) { duelPhase = D_FOOT; circleLeft = 1.0f + rnd.nextFloat() * 1.8f; }
            if (circleLeft > 0f) {
                startFoot((170 + rnd.nextFloat() * 70) * d, 0.2f);
                holdDur = 0.15f + rnd.nextFloat() * 0.3f;
            } else {
                decideNext();
            }
        }

        private void backToFoot() {
            duelPhase = D_FOOT;
            circleLeft = 0.8f + rnd.nextFloat() * 1.6f;
            startFoot((190 + rnd.nextFloat() * 60) * d, 0.2f);
            holdDur = 0.2f;
            duelT = 0f;
        }

        private void decideNext() {
            float low = Math.min(hp[0] / hpMax0, hp[1] / hpMax1);
            if (low < 0.3f && rnd.nextFloat() < 0.55f) { startStandoff(); return; } // o golpe final
            if (counterNext) counterNext = false;
            else attacker = stam[0] * (0.6f + rnd.nextFloat()) >= stam[1] * (0.6f + rnd.nextFloat()) ? 0 : 1;
            launchAttack();
        }

        private void launchAttack() {
            float r0 = rnd.nextFloat();
            atkType = r0 < 0.5f ? 0 : r0 < 0.75f ? 1 : 2; // estocada, finta ou golpe duplo
            atkLeft = atkType == 2 ? 2 : 1;
            feintBack = false;
            atkCrit = false;
            chooseReaction();
            startDash();
        }

        private void chooseReaction() {
            int def = 1 - attacker;
            float r1 = rnd.nextFloat() + (stam[def] < 30f ? 0.25f : 0f); // cansado reage pior
            if (atkType == 1) react = r1 < 0.7f ? (rnd.nextBoolean() ? R_DODGE : R_PARRY) : R_TAKE; // na finta ele "morde" 70% das vezes
            else react = r1 < 0.32f ? R_PARRY : r1 < 0.58f ? R_DODGE : r1 < 0.72f ? R_COUNTER : R_TAKE;
            reactSide = rnd.nextBoolean() ? 1f : -1f;
        }

        private void startDash() {
            int def = 1 - attacker;
            float ux = px[def] - px[attacker], uy = py[def] - py[attacker], len = Math.max(1f, (float) Math.hypot(ux, uy));
            dashDx = ux / len;
            dashDy = uy / len;
            dashDist = atkType == 1 && !feintBack ? len * 0.5f : Math.max(60 * d, len - 44 * d); // a finta só vai até a metade
            dashDist = Math.max(40 * d, dashDist);
            dashSpeed = 1000 * d;
            dashTrav = 0f;
            reactT = 0f;
            reactFromX = px[def];
            reactFromY = py[def];
            if (attacker == 0) { pa[0] = tipAngle(dashDx, dashDy); pa[1] = tipUpAngle(-dashDx, -dashDy); }
            else { pa[1] = tipUpAngle(dashDx, dashDy); pa[0] = tipAngle(-dashDx, -dashDy); }
            duelPhase = D_ATTACK;
            duelT = 0f;
        }

        private void duelAttack(float dt) {
            int def = 1 - attacker;
            if (duelT < 0.22f) { // um instante de silêncio: a lâmina recua um pouquinho antes de sair
                px[attacker] -= dashDx * 80 * d * dt;
                py[attacker] -= dashDy * 80 * d * dt;
                pvx[0] = pvy[0] = pvx[1] = pvy[1] = 0f;
                return;
            }
            float tt = duelT - 0.22f;
            float step = Math.min(dashDist - dashTrav, dashSpeed * Math.min(1f, 0.35f + tt * 5f) * dt); // arranca com peso
            px[attacker] += dashDx * step;
            py[attacker] += dashDy * step;
            dashTrav += step;
            pvx[attacker] = dt > 0f ? dashDx * step / dt : 0f;
            pvy[attacker] = dt > 0f ? dashDy * step / dt : 0f;
            reactT += dt; // reação de quem defende
            float ox = px[def], oy = py[def];
            float e = Math.min(1f, reactT / (react == R_PARRY ? 0.15f : 0.22f));
            if (react == R_DODGE || react == R_COUNTER) { // sai do caminho, de lado
                px[def] = reactFromX - dashDy * reactSide * 150 * d * e;
                py[def] = reactFromY + dashDx * reactSide * 150 * d * e;
            } else if (react == R_PARRY) { // dá um passo à frente e levanta a lâmina
                px[def] = reactFromX - dashDx * 36 * d * e;
                py[def] = reactFromY - dashDy * 36 * d * e;
            }
            pvx[def] = dt > 0f ? (px[def] - ox) / dt : 0f;
            pvy[def] = dt > 0f ? (py[def] - oy) / dt : 0f;
            if (dashTrav >= dashDist - 0.5f) endDash();
        }

        private void endDash() {
            int def = 1 - attacker;
            pvx[0] = pvy[0] = pvx[1] = pvy[1] = 0f;
            if (atkType == 1 && !feintBack) { // finta
                feintBack = true;
                if (react == R_TAKE) { // não caiu na finta: cada um volta pro seu lugar
                    stam[attacker] -= 6f;
                    backToFoot();
                } else {               // caiu: ficou aberto, e a estocada de verdade vem certeira
                    stagger[def] = 0.7f;
                    atkType = 0;
                    react = R_TAKE;
                    atkCrit = rnd.nextFloat() < 0.4f;
                    startDash();
                }
                return;
            }
            float hx = px[def], hy = py[def];
            if (react == R_DODGE || react == R_COUNTER) { // errou: passou direto
                spawnSparks(px[attacker], py[attacker], 6);
                stagger[attacker] = 0.55f;
                stam[attacker] -= 12f;
                resolveKind = 3;
                if (react == R_COUNTER) { // quem desviou contra-ataca na hora
                    attacker = def;
                    counterNext = true;
                    atkType = 0;
                    atkLeft = 1;
                    react = rnd.nextFloat() < 0.3f ? R_PARRY : R_TAKE;
                    atkCrit = rnd.nextFloat() < 0.25f;
                    reactSide = rnd.nextBoolean() ? 1f : -1f;
                }
            } else if (react == R_PARRY && rnd.nextFloat() > 0.18f) { // defesa: choque de lâminas
                float mx = (px[0] + px[1]) / 2f, my = (py[0] + py[1]) / 2f;
                spawnSparks(mx, my, 28);
                clashX = mx;
                clashY = my;
                kvx[attacker] = -dashDx * 330 * d;
                kvy[attacker] = -dashDy * 330 * d;
                kvx[def] = dashDx * 210 * d;
                kvy[def] = dashDy * 210 * d;
                stam[0] -= 12f;
                stam[1] -= 12f;
                resolveKind = 2;
            } else { // acertou
                boolean crit = atkCrit || rnd.nextFloat() < 0.22f;
                hp[def] -= (9f + rnd.nextFloat() * 13f + (attacker == 0 ? level * 0.3f : 4f)) * (crit ? 1.8f : 1f);
                spawnSparks(hx, hy, crit ? 40 : 24);
                slashes.add(new float[]{hx, hy, (float) Math.atan2(dashDy, dashDx) + (rnd.nextFloat() - 0.5f) * 0.5f, (crit ? 280f : 180f) * d, t});
                hitStop = crit ? 0.14f : 0.07f;
                if (crit) { // corte fundo: câmera lenta e respingo de tinta
                    slowUntil = t + 0.6f;
                    slowScale = 0.22f;
                    spawnInk(hx, hy, 18);
                }
                float knock = (crit ? 500f : 340f) * d;
                kvx[def] = dashDx * knock;
                kvy[def] = dashDy * knock;
                kvx[attacker] = -dashDx * 90 * d;
                kvy[attacker] = -dashDy * 90 * d;
                stagger[def] = crit ? 0.8f : 0.35f;
                resolveKind = 1;
            }
            duelPhase = D_RESOLVE;
            duelT = 0f;
        }

        private void duelResolve(float dt) {
            float k = (float) Math.pow(0.03, dt);
            for (int i = 0; i < 2; i++) {
                kvx[i] *= k;
                kvy[i] *= k;
                px[i] += kvx[i] * dt;
                py[i] += kvy[i] * dt;
                pvx[i] = kvx[i];
                pvy[i] = kvy[i];
            }
            clampDuel();
            faceEachOther(false);
            if (duelT < (resolveKind == 1 ? 0.6f : 0.4f)) return;
            if (hp[0] <= 0f || hp[1] <= 0f) { loser = hp[0] <= 0f ? 0 : 1; startDeath(); return; }
            if (counterNext) { counterNext = false; feintBack = false; startDash(); return; } // contra-ataque
            atkLeft--;
            if (atkLeft > 0 && resolveKind != 2) { chooseReaction(); startDash(); return; } // segundo golpe do golpe duplo
            backToFoot();
        }

        private void startStandoff() {
            duelPhase = D_STANDOFF;
            duelT = 0f;
            standoffT = 0f;
            startFoot(310 * d, 0.55f); // se afastam, uma de cada lado
        }

        private void duelStandoff(float dt) {
            float k = moveFoot(dt);
            faceEachOther(true);
            tension = Math.min(1f, tension + 0.9f * dt);
            if (k < 1f) return;
            pvx[0] = pvy[0] = pvx[1] = pvy[1] = 0f;
            standoffT += dt;
            if (standoffT >= 1.3f) startCross(); // silêncio de tensão, depois o golpe único
        }

        private void startCross() {
            float g = Math.max(0.05f, hp[0] / hpMax0), bb = Math.max(0.05f, hp[1] / hpMax1);
            loser = rnd.nextFloat() < g / (g + bb) ? 1 : 0; // a mais forte tem mais chance
            crossT = 0f;
            crossCut = false;
            for (int i = 0; i < 2; i++) { footFromX[i] = px[i]; footFromY[i] = py[i]; }
            footToX[0] = px[1]; footToY[0] = py[1];
            footToX[1] = px[0]; footToY[1] = py[0];
            float ux = px[1] - px[0], uy = py[1] - py[0], len = Math.max(1f, (float) Math.hypot(ux, uy));
            dashDx = ux / len;
            dashDy = uy / len;
            pa[0] = tipAngle(dashDx, dashDy);
            pa[1] = tipUpAngle(-dashDx, -dashDy);
            slowUntil = t + 0.9f;
            slowScale = 0.2f;
            duelPhase = D_CROSS;
            duelT = 0f;
        }

        private void duelCross(float dt) {
            crossT += dt;
            float e = Math.min(1f, crossT / 0.32f);
            for (int i = 0; i < 2; i++) { // as duas se cruzam em linha reta e trocam de lado
                float ox = px[i], oy = py[i];
                px[i] = footFromX[i] + (footToX[i] - footFromX[i]) * e;
                py[i] = footFromY[i] + (footToY[i] - footFromY[i]) * e;
                pvx[i] = dt > 0f ? (px[i] - ox) / dt : 0f;
                pvy[i] = dt > 0f ? (py[i] - oy) / dt : 0f;
            }
            if (e >= 0.5f && !crossCut) { // o corte: uma linha branca cruza a tela
                crossCut = true;
                float mx = (footFromX[0] + footFromX[1]) / 2f, my = (footFromY[0] + footFromY[1]) / 2f;
                slashes.add(new float[]{mx, my, (float) Math.atan2(dashDy, dashDx) + 0.9f, 900f * d, t});
                spawnSparks(mx, my, 40);
                hitStop = 0.1f;
            }
            if (e >= 1f) {
                pvx[0] = pvy[0] = pvx[1] = pvy[1] = 0f;
                if (crossT >= 0.32f + 0.9f) { hp[loser] = 0f; startDeath(); } // as duas paradas, de costas; então uma cai
            }
        }

        private void startDeath() {
            float lx = px[loser], ly = py[loser];
            spawnSparks(lx, ly, 70);
            spawnInk(lx, ly, 40);
            hitStop = 0.12f;
            if (loser == 0) greenDead = true; else blueDead = true;
            winner = 1 - loser;
            clashX = lx;
            clashY = ly;
            slashes.add(new float[]{lx, ly, (float) Math.atan2(dashDy, dashDx), 320 * d, t});
            duelPhase = D_DEATH;
            duelT = 0f;
        }

        private void duelDeath(float dt) {
            pvx[0] = pvy[0] = pvx[1] = pvy[1] = 0f;
            if (duelT >= 1.2f) { duelPhase = D_VICTORY; duelT = 0f; }
        }

        private void duelVictory(float dt) { // quem venceu respira e faz só um giro lento
            pa[winner] += 200f * dt * (winner == 0 ? 1f : -1f);
            if (duelT >= 1.6f) { duelPhase = D_HOME; duelT = 0f; }
        }

        private void duelHome(float dt) {
            if (loser == 1) { // a espada do painel morreu: some uns segundos e renasce no lugar dela
                if (duelT >= 2.5f) {
                    host.setSwordHidden(false);
                    spawnSparks(dockX, dockY, 24);
                    endDuel();
                }
                return;
            }
            float dist = (float) Math.hypot(dockX - px[1], dockY - py[1]); // a do painel venceu: volta em linha reta
            float step = Math.min(dist, 700 * d * dt);
            if (dist > 1f) { px[1] += (dockX - px[1]) / dist * step; py[1] += (dockY - py[1]) / dist * step; }
            pa[1] += wrap(0f - pa[1]) * Math.min(1f, 6f * dt);
            pvx[1] = pvy[1] = 0f;
            if (greenDead && duelT > 1.0f) { // a espada dos patos renasce em outro ponto da tela
                px[0] = 80 * d + rnd.nextFloat() * Math.max(1f, w - 160 * d);
                py[0] = 80 * d + rnd.nextFloat() * Math.max(1f, h - 160 * d);
                pa[0] = 0f;
                greenDead = false;
                appearAt = t;
                spawnSparks(px[0], py[0], 16);
            }
            if (dist < 6 * d || duelT > 2.4f) {
                host.setSwordHidden(false); // reaparece no painel
                endDuel();
            }
        }

        private void spawnInk(float x, float y, int n) {
            for (int i = 0; i < n; i++) {
                Spark s = new Spark();
                double a = rnd.nextDouble() * Math.PI * 2;
                float speed = (60 + rnd.nextFloat() * 260) * d;
                s.x = x;
                s.y = y;
                s.vx = (float) Math.cos(a) * speed;
                s.vy = (float) Math.sin(a) * speed;
                s.life = 1.0f;
                s.color = 0xFF111111;
                sparks.add(s);
            }
        }

        private void drawNightSword(Canvas c) {
            SpriteSheet s = nightSheet;
            if (!duelOn || s == null || blueDead) return;
            s.frame(src, (int) (t * 1000f / s.ms) % s.frames);
            float hh = duelH > 0f ? duelH : swordLen(), ww = hh * s.aspect;
            float shake = stagger[1] > 0f ? (float) Math.sin(t * 60f) * 3 * d : 0f; // abalada: treme
            c.save();
            c.translate(nx + shake, ny);
            c.rotate(nang);
            dst.set(-ww / 2f, -hh / 2f, ww / 2f, hh / 2f);
            bmp.setAlpha(255);
            c.drawBitmap(s.bitmap, src, dst, bmp);
            c.restore();
        }

        /** Rastro azul da espada de raio, nas investidas. */
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

        /** Anel de choque (defesa) e anéis da morte. */
        private void drawClash(Canvas c) {
            if (!duelOn) return;
            if (duelPhase == D_RESOLVE && resolveKind == 2 && duelT <= 0.4f) {
                float life = 1f - duelT / 0.4f;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(4 * d * life + 1f);
                p.setColor(Color.argb((int) (230 * life), 235, 245, 255));
                c.drawCircle(clashX, clashY, 20 * d + duelT * 420 * d, p);
            } else if (duelPhase == D_DEATH) {
                for (int i = 0; i < 2; i++) {
                    float life = Math.max(0f, 1f - (duelT - i * 0.18f) / 0.9f);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(5 * d * life + 1f);
                    p.setColor(Color.argb((int) (230 * life), 255, 240, 200));
                    c.drawCircle(clashX, clashY, 24 * d + Math.max(0f, duelT - i * 0.18f) * 520 * d, p);
                }
            }
        }

        /** Tela escurecida e vinheta que fecha no centro: quanto maior a tensão, mais escuro nas bordas. */
        private void drawDuelFx(Canvas c) {
            if (cine <= 0.01f) return;
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb((int) (70 * cine), 0, 0, 0));
            c.drawRect(0, 0, w, h, p);
            float a = (0.35f + 0.65f * tension) * cine;
            p.setShader(new RadialGradient(w / 2f, h / 2f, Math.max(w, h) * 0.7f, 0x00000000, Color.argb((int) (170 * a), 0, 0, 0), Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
        }

        /** Riscos de corte, brancos com contorno preto, como num mangá. */
        private void drawSlashes(Canvas c) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            for (float[] s : slashes) {
                float life = 1f - (t - s[4]) / 0.5f;
                if (life <= 0f) continue;
                float hx = (float) Math.cos(s[2]) * s[3] / 2f, hy = (float) Math.sin(s[2]) * s[3] / 2f;
                int al = (int) (255 * Math.min(1f, life * 1.6f));
                p.setStrokeWidth(9 * d * life + 2f);
                p.setColor(Color.argb(al, 0, 0, 0));
                c.drawLine(s[0] - hx, s[1] - hy, s[0] + hx, s[1] + hy, p);
                p.setStrokeWidth(3.6f * d * life + 1f);
                p.setColor(Color.argb(al, 255, 255, 255));
                c.drawLine(s[0] - hx, s[1] - hy, s[0] + hx, s[1] + hy, p);
            }
        }

        /** Faixas pretas de cinema em cima e embaixo durante a luta. */
        private void drawCinema(Canvas c) {
            if (cine <= 0.01f) return;
            float bar = h * 0.075f * cine;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF000000);
            c.drawRect(0, 0, w, bar, p);
            c.drawRect(0, h - bar, w, h, p);
        }

        private void drawDuelBars(Canvas c) {
            if (!duelOn || cine < 0.5f || duelPhase == D_STANDOFF || duelPhase == D_CROSS || duelPhase >= D_DEATH) return;
            drawBar(c, sx, sy - 76 * d, hp[0] / hpMax0, 0xFF7CFF8A, greenDead);
            drawBar(c, nx, ny - (duelH > 0f ? duelH / 2f : 56 * d) - 18 * d, hp[1] / hpMax1, 0xFF6FB6FF, blueDead);
        }

        private void drawBar(Canvas c, float x, float y, float frac, int color, boolean dead) {
            if (dead) return;
            frac = Math.max(0f, Math.min(1f, frac));
            float bw = 46 * d, bh = 4 * d;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xCC000000);
            r.set(x - bw / 2f - 1.5f * d, y - 1.5f * d, x + bw / 2f + 1.5f * d, y + bh + 1.5f * d);
            c.drawRoundRect(r, 3 * d, 3 * d, p);
            p.setColor(0x55FFFFFF);
            r.set(x - bw / 2f, y, x + bw / 2f, y + bh);
            c.drawRoundRect(r, 2 * d, 2 * d, p);
            p.setColor(color);
            r.set(x - bw / 2f, y, x - bw / 2f + bw * frac, y + bh);
            c.drawRoundRect(r, 2 * d, 2 * d, p);
        }

        /** Guarda onde a espada passou, pro rastro verde (só quando ela está rápida). */
        private void updateTrail() {
            if (Math.hypot(svx, svy) > 90 * d) trail.add(new float[]{sx, sy + bobNow(), t, ang});
            while (!trail.isEmpty() && t - trail.get(0)[2] > 0.4f) trail.remove(0);
            if (duelOn && Math.hypot(pvx[1], pvy[1]) > 150 * d) trail2.add(new float[]{nx, ny, t});
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
                if (!duelOn && cine > 0f) cine = Math.max(0f, cine - dt * 2f); // as faixas do cinema saem devagar
                drawDuelFx(c);
                drawAlucard(c);
                drawDuck(c);
                drawTrail(c);
                drawBlueTrail(c);
                drawSparks(c);
                drawNightSword(c);
                drawSword(c);
                drawLevelUp(c);
                drawClash(c);
                drawDuelBars(c);
                drawSlashes(c);
                drawCinema(c);
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
            if (greenDead) return;
            float appear = Math.min(1f, (t - appearAt) / 0.6f); // nasce pequena e o brilho some em 0,6 s
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
                float vol = Math.max(0f, Math.min(1f, sp.getInt("sfx_volume", 35) / 100f)); // não dá susto
                m.setVolume(vol, vol);
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
            float labelH = labelLayout != null ? labelLayout.getHeight() + gap : 0f;
            float bw = verseLayout.getWidth() + 2 * padX, bh = verseLayout.getHeight() + refH + labelH + 2 * padY;
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
            if (labelLayout != null) { // "NOVO TESTAMENTO" ou "VELHO TESTAMENTO" em cima do versículo
                labelHaloLayout.draw(c);
                labelLayout.draw(c);
                c.translate(0, labelLayout.getHeight() + gap);
            }
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
