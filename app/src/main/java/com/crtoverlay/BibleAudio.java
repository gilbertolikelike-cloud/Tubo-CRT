package com.crtoverlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Dublagem da Bíblia. Vive no app (não no painel): fechar o painel NÃO para o áudio.
 * Só para quando o Gilberto aperta ■, ou quando acaba (no modo contínuo segue em ordem Gn..Ap).
 * Também guarda quais capítulos já foram lidos/ouvidos.
 */
final class BibleAudio {
    interface Listener {
        void onState();
        /** O áudio passou sozinho para o capítulo seguinte. */
        void onChapter(int prevBook, int prevChap);
    }

    private static BibleAudio inst;

    static synchronized BibleAudio get(Context c) {
        if (inst == null) inst = new BibleAudio(c.getApplicationContext());
        return inst;
    }

    private final Context app;
    private final SharedPreferences sp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Map<Integer, Uri> files = new HashMap<>();
    private final byte[] bits = new byte[(1189 + 7) / 8 + 1];
    private int readCount;
    private MediaPlayer mp;
    private boolean prepared, playing, starting, continuous;
    int book = -1, chap = -1;
    String status = "";
    Listener listener;

    private BibleAudio(Context app) {
        this.app = app;
        sp = app.getSharedPreferences("crt", Context.MODE_PRIVATE);
        try {
            byte[] b = Base64.decode(sp.getString("bible_read", ""), Base64.NO_WRAP);
            System.arraycopy(b, 0, bits, 0, Math.min(b.length, bits.length));
        } catch (RuntimeException ignored) { /* ainda não ouviu nada */ }
        int n = 0;
        for (int g = 1; g <= 1189; g++) if (isRead(g)) n++;
        readCount = n;
    }

    // ---------- progresso ----------
    boolean isRead(int g) { return g >= 1 && g <= 1189 && (bits[g >> 3] & (1 << (g & 7))) != 0; }
    int readCount() { return readCount; }

    void markRead(int g) {
        if (g < 1 || g > 1189 || isRead(g)) return;
        bits[g >> 3] |= (byte) (1 << (g & 7));
        readCount++;
        sp.edit().putString("bible_read", Base64.encodeToString(bits, Base64.NO_WRAP)).apply();
    }

    // ---------- arquivos ----------
    /** Pode demorar (lê a pasta): chamar fora da thread principal. */
    void reload() { files = VoiceLibrary.index(app, sp); }
    int fileCount() { return files.size(); }
    boolean hasAudio(int g) { return files.get(g) != null; }

    // ---------- estado ----------
    boolean isPlaying() { return playing; }
    boolean isActive() { return playing || starting || (mp != null && prepared); }
    boolean isPrepared() { return mp != null && prepared; }
    int position() { try { return mp != null && prepared ? mp.getCurrentPosition() : 0; } catch (RuntimeException e) { return 0; } }
    int duration() { try { return mp != null && prepared ? Math.max(1, mp.getDuration()) : 1; } catch (RuntimeException e) { return 1; } }

    private void changed() { Listener l = listener; if (l != null) l.onState(); }

    void play(int b, int c, boolean cont) {
        stop();
        book = b;
        chap = c;
        continuous = cont;
        starting = true;
        status = "carregando áudio…";
        changed();
        new Thread(() -> {
            Verses v = Verses.shared(app);
            if (files.isEmpty()) reload();
            main.post(() -> begin(v, b, c));
        }).start();
    }

    private void begin(Verses v, int b, int c) {
        if (!starting || book != b || chap != c || v == null) return;
        int g = v.globalChapter(b, c);
        Uri u = files.get(g);
        if (u == null) {
            status = "sem áudio do capítulo " + g;
            starting = false;
            changed();
            return;
        }
        try {
            MusicPlayer m = MusicPlayer.get(app);
            if (m.playing()) m.toggle();           // a música do app espera a leitura
        } catch (RuntimeException ignored) { /* sem música */ }
        final MediaPlayer m = new MediaPlayer();
        mp = m;
        try {
            m.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            m.setWakeMode(app, android.os.PowerManager.PARTIAL_WAKE_LOCK);   // não para com a tela apagada
            m.setDataSource(app, u);
            m.setOnPreparedListener(x -> {
                if (mp != x) return;
                prepared = true;
                starting = false;
                x.start();
                playing = true;
                status = "";
                changed();
            });
            m.setOnCompletionListener(x -> { if (mp == x) finish(v, g); });
            m.setOnErrorListener((x, what, extra) -> {
                status = "não consegui tocar este áudio";
                stop();
                changed();
                return true;
            });
            m.prepareAsync();
        } catch (java.io.IOException | RuntimeException e) {
            status = "não consegui abrir o áudio";
            stop();
            changed();
        }
    }

    private void finish(Verses v, int g) {
        markRead(g);
        sp.edit().putInt("bible_seq", g >= 1189 ? 1 : g + 1).apply();      // o próximo capítulo da fila automática
        int pb = book, pc = chap, nb = book, nc = chap + 1;
        if (nc >= v.chapterCount(nb)) { nb++; nc = 0; }
        if (!continuous || nb >= v.bookCount()) {
            stop();
            changed();
            return;
        }
        play(nb, nc, true);                                                  // segue em ordem: Gn ... Ap
        Listener l = listener;
        if (l != null) l.onChapter(pb, pc);
    }

    void stop() {
        playing = false;
        prepared = false;
        starting = false;
        MediaPlayer m = mp;
        mp = null;
        if (m != null) {
            try { m.stop(); } catch (RuntimeException ignored) { /* ainda não estava pronto */ }
            m.release();
        }
    }

    /** Pausa/retoma. Devolve false se não há áudio carregado (aí é para começar um capítulo). */
    boolean togglePause() {
        MediaPlayer m = mp;
        if (m == null || !prepared) return false;
        try {
            if (m.isPlaying()) { m.pause(); playing = false; } else { m.start(); playing = true; }
        } catch (RuntimeException ignored) { /* player saiu */ }
        changed();
        return true;
    }

    void seekFraction(float f) {
        MediaPlayer m = mp;
        if (m == null || !prepared) return;
        try { m.seekTo((int) (m.getDuration() * Math.max(0f, Math.min(1f, f)))); } catch (RuntimeException ignored) { /* sem player */ }
    }

    /**
     * A cada 30 minutos: o próximo capítulo da fila (Gn..Ap, em ordem, sem sorteio) com a dublagem.
     * Devolve {livro, capítulo} ou null se não pôde começar.
     */
    int[] playNextScheduled() {
        Verses v = Verses.shared(app);
        if (v == null || isActive()) return null;
        int g = Math.max(1, Math.min(1189, sp.getInt("bible_seq", 1)));
        int[] loc = v.locate(g);
        play(loc[0], loc[1], false);
        return loc;
    }
}
