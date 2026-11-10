package com.crtoverlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * Sons do painel, gerados no próprio app (nenhum arquivo de áudio):
 *  - tick: o "tec" de botão de girar de TV antiga, a cada passo do volume e do brilho;
 *  - pop: o "bum" da TV de tubo (desmagnetização) quando você pega o botão de girar;
 *  - beep: o "bip-bop" dos botões.
 * Dá pra desligar em ▦ ("Sons do painel").
 */
final class UiSounds {
    private static final int RATE = 22050;
    private static UiSounds instance;

    static synchronized UiSounds get(Context ctx) {
        if (instance == null) instance = new UiSounds(ctx.getApplicationContext());
        return instance;
    }

    private final SharedPreferences sp;
    private final SoundPool pool;
    private int tickId, popId, beepId;
    private long lastTick;

    private UiSounds(Context ctx) {
        sp = ctx.getSharedPreferences("crt", Context.MODE_PRIVATE);
        pool = new SoundPool.Builder().setMaxStreams(4)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .build();
        File dir = ctx.getCacheDir();
        try {
            tickId = pool.load(write(new File(dir, "sfx_tick.wav"), makeTick()).getPath(), 1);
            popId = pool.load(write(new File(dir, "sfx_pop.wav"), makePop()).getPath(), 1);
            beepId = pool.load(write(new File(dir, "sfx_beep.wav"), makeBeep()).getPath(), 1);
        } catch (IOException | RuntimeException ignored) {
            // sem cache de arquivos: o painel só fica sem som
        }
    }

    void tick() {
        long now = SystemClock.uptimeMillis();
        if (now - lastTick < 40) return;
        lastTick = now;
        play(tickId, 0.8f);
    }

    void pop() { play(popId, 1f); }

    void beep() { play(beepId, 0.9f); }

    private void play(int id, float volume) {
        if (id == 0 || !sp.getBoolean("ui_sounds", true)) return;
        pool.play(id, volume, volume, 1, 0, 1f);
    }

    // ---------- geração dos sons ----------
    private static short[] makeTick() {
        short[] s = new short[(int) (RATE * 0.05)];
        Random rnd = new Random(1);
        for (int i = 0; i < s.length; i++) {
            double t = i / (double) RATE;
            double v = (i < RATE * 0.003 ? (rnd.nextDouble() * 2 - 1) * 0.8 : 0)
                    + Math.sin(2 * Math.PI * 1700 * t) * Math.exp(-t * 90) * 0.6
                    + Math.sin(2 * Math.PI * 220 * t) * Math.exp(-t * 60) * 0.5;
            s[i] = clip(v * 0.8);
        }
        return s;
    }

    private static short[] makePop() {
        short[] s = new short[(int) (RATE * 0.38)];
        Random rnd = new Random(2);
        for (int i = 0; i < s.length; i++) {
            double t = i / (double) RATE;
            double wobble = Math.sin(2 * Math.PI * 7 * t) * 6;
            double v = Math.sin(2 * Math.PI * (58 + wobble) * t) * Math.exp(-t * 9) * 0.9
                    + (rnd.nextDouble() * 2 - 1) * Math.exp(-t * 35) * 0.45
                    + (t < 0.02 ? Math.sin(2 * Math.PI * 4000 * t) * 0.35 : 0);
            s[i] = clip(v * 0.85);
        }
        return s;
    }

    private static short[] makeBeep() {
        int a = (int) (RATE * 0.05), b = (int) (RATE * 0.08);
        short[] s = new short[a + b];
        for (int i = 0; i < s.length; i++) {
            boolean first = i < a;
            double t = (first ? i : i - a) / (double) RATE, len = (first ? a : b) / (double) RATE;
            double env = Math.min(1.0, t / 0.004) * Math.min(1.0, (len - t) / 0.012);
            s[i] = clip(Math.sin(2 * Math.PI * (first ? 880 : 1320) * t) * env * 0.55);
        }
        return s;
    }

    private static short clip(double v) {
        return (short) (Math.max(-1.0, Math.min(1.0, v)) * 30000);
    }

    private static File write(File f, short[] pcm) throws IOException {
        int bytes = pcm.length * 2;
        byte[] h = new byte[44];
        put(h, 0, "RIFF"); le(h, 4, 36 + bytes); put(h, 8, "WAVE"); put(h, 12, "fmt "); le(h, 16, 16);
        h[20] = 1; h[22] = 1; le(h, 24, RATE); le(h, 28, RATE * 2); h[32] = 2; h[34] = 16;
        put(h, 36, "data"); le(h, 40, bytes);
        byte[] body = new byte[bytes];
        for (int i = 0; i < pcm.length; i++) { body[2 * i] = (byte) pcm[i]; body[2 * i + 1] = (byte) (pcm[i] >> 8); }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(h);
            out.write(body);
        }
        return f;
    }

    private static void put(byte[] b, int at, String s) { for (int i = 0; i < s.length(); i++) b[at + i] = (byte) s.charAt(i); }

    private static void le(byte[] b, int at, int v) { for (int i = 0; i < 4; i++) b[at + i] = (byte) (v >> (8 * i)); }
}
