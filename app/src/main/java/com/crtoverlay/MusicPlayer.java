package com.crtoverlay;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.audiofx.Visualizer;
import android.net.Uri;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Toca as músicas da pasta que você escolheu (todas as subpastas até 3 níveis).
 * Chame {@link #rescan()} de novo pra reconhecer arquivos que você colocou depois.
 */
final class MusicPlayer {
    /** Número de barras do analisador (de 20 Hz a 20 kHz, de terço em terço de oitava). */
    static final int BANDS = 31;

    private static MusicPlayer instance;

    static synchronized MusicPlayer get(Context ctx) {
        if (instance == null) instance = new MusicPlayer(ctx.getApplicationContext());
        return instance;
    }

    static synchronized boolean isPlayingNow() {
        return instance != null && instance.playing();
    }

    private static final class Track {
        final String name;
        final Uri uri;
        Track(String name, Uri uri) { this.name = name; this.uri = uri; }
    }

    private final Context ctx;
    private final SharedPreferences sp;
    private final List<Track> tracks = new ArrayList<>();
    private final TvSound tv;
    private final float[] level = new float[BANDS], peak = new float[BANDS];
    private Visualizer visualizer;
    private long levelStamp;
    private MediaPlayer player;
    private boolean prepared;
    private int index = -1;

    private MusicPlayer(Context ctx) {
        this.ctx = ctx;
        this.sp = ctx.getSharedPreferences("crt", Context.MODE_PRIVATE);
        this.tv = new TvSound(ctx);
    }

    // ---------- lista de músicas ----------
    /** Lê a pasta de novo. Pode demorar um pouco: chame fora da tela principal (thread). */
    void rescan() {
        List<Track> found = new ArrayList<>();
        String saved = sp.getString("music_tree", null);
        if (saved != null) {
            Uri tree = Uri.parse(saved);
            try {
                collect(tree, DocumentsContract.getTreeDocumentId(tree), found, 0);
            } catch (RuntimeException ignored) {
                // permissão da pasta foi perdida: lista fica vazia
            }
        }
        Collections.sort(found, (a, b) -> a.name.compareToIgnoreCase(b.name));
        synchronized (this) {
            Uri current = index >= 0 && index < tracks.size() ? tracks.get(index).uri : null;
            tracks.clear();
            tracks.addAll(found);
            index = -1;
            for (int i = 0; current != null && i < tracks.size(); i++)
                if (tracks.get(i).uri.equals(current)) index = i;
        }
    }

    private void collect(Uri tree, String parentId, List<Track> out, int depth) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] cols = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE};
        try (Cursor c = ctx.getContentResolver().query(children, cols, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                if (name == null) continue;
                if (Document.MIME_TYPE_DIR.equals(mime)) {
                    if (depth < 3) collect(tree, id, out, depth + 1);
                } else if (isAudio(name, mime)) {
                    out.add(new Track(withoutExtension(name), DocumentsContract.buildDocumentUriUsingTree(tree, id)));
                }
            }
        } catch (RuntimeException ignored) {
            // pasta ilegível: ignora
        }
    }

    private static boolean isAudio(String name, String mime) {
        if (mime != null && mime.startsWith("audio/")) return true;
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".ogg") || n.endsWith(".wav")
                || n.endsWith(".flac") || n.endsWith(".aac") || n.endsWith(".opus");
    }

    private static String withoutExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Aplica na hora a intensidade nova do chiado de TV. */
    void applyTvSound() { tv.apply(); }

    // ---------- analisador de espectro ----------
    /** 0 = funcionando, 1 = falta a permissão de áudio, 2 = desligado nos ajustes. */
    int analyzerState() {
        if (!sp.getBoolean("spectrum_on", true)) return 2;
        return ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ? 0 : 1;
    }

    /** Liga o analisador na música que está tocando (depois de liberar a permissão, por exemplo). */
    synchronized void refreshVisualizer() {
        if (player != null && prepared) attachVisualizer(player.getAudioSessionId());
        else releaseVisualizer();
    }

    private void attachVisualizer(int session) {
        releaseVisualizer();
        if (analyzerState() != 0) return;
        try {
            Visualizer v = new Visualizer(session);
            v.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);
            v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(Visualizer vis, byte[] waveform, int samplingRate) {}
                @Override public void onFftDataCapture(Visualizer vis, byte[] fft, int samplingRate) { onFft(fft, samplingRate); }
            }, Visualizer.getMaxCaptureRate(), false, true);
            v.setEnabled(true);
            visualizer = v;
        } catch (RuntimeException e) {
            visualizer = null; // sem permissão ou aparelho sem suporte
        }
    }

    private void releaseVisualizer() {
        if (visualizer != null) {
            try { visualizer.setEnabled(false); visualizer.release(); } catch (RuntimeException ignored) { /* já liberado */ }
            visualizer = null;
        }
    }

    /** Converte o espectro (FFT) em 31 barras de volume, de 0 a 1. */
    private void onFft(byte[] fft, int samplingRateMilliHz) {
        int n = fft.length;
        float hzPerBin = samplingRateMilliHz / 1000f / n;
        float[] next = new float[BANDS];
        for (int band = 0; band < BANDS; band++) {
            float center = (float) (20.0 * Math.pow(10.0, band / 10.0));
            int k0 = Math.max(1, (int) Math.floor(center / 1.1225f / hzPerBin));
            int k1 = Math.min(n / 2 - 1, (int) Math.ceil(center * 1.1225f / hzPerBin));
            if (k1 < k0) k1 = k0;
            float mag = 0f;
            for (int k = k0; k <= k1 && 2 * k + 1 < n; k++) mag = Math.max(mag, (float) Math.hypot(fft[2 * k], fft[2 * k + 1]));
            float db = 20f * (float) Math.log10(mag + 1f);
            next[band] = Math.max(0f, Math.min(1f, db / 40f + band * 0.006f)); // um pouco mais forte nos agudos, que chegam fracos
        }
        synchronized (level) {
            for (int i = 0; i < BANDS; i++) {
                level[i] = next[i] > level[i] ? next[i] : level[i] * 0.78f + next[i] * 0.22f; // sobe rápido, desce devagar
                peak[i] = next[i] > peak[i] ? next[i] : Math.max(0f, peak[i] - 0.015f);
            }
            levelStamp = SystemClock.uptimeMillis();
        }
    }

    /** Copia as barras atuais e os picos. Sem dados novos (música parada), elas descem sozinhas. */
    void snapshot(float[] outLevel, float[] outPeak) {
        synchronized (level) {
            long age = SystemClock.uptimeMillis() - levelStamp;
            float fade = age > 150 ? (float) Math.pow(0.5, (age - 150) / 180.0) : 1f;
            for (int i = 0; i < BANDS; i++) {
                outLevel[i] = level[i] * fade;
                outPeak[i] = peak[i] * fade;
            }
        }
    }

    synchronized int count() { return tracks.size(); }

    synchronized boolean playing() { return player != null && prepared && player.isPlaying(); }

    /** Texto pra mostrar no painel. */
    synchronized String title() {
        if (tracks.isEmpty()) return "sem músicas (escolha a pasta no app)";
        if (index < 0) return tracks.size() + " músicas";
        return tracks.get(index).name;
    }

    // ---------- controle ----------
    synchronized void toggle() {
        if (tracks.isEmpty()) return;
        if (player != null && prepared) {
            if (player.isPlaying()) { player.pause(); tv.setPlaying(false); } else { player.start(); tv.setPlaying(true); }
        } else if (player == null) {
            play(index >= 0 ? index : 0);
        }
    }

    synchronized void next() {
        if (!tracks.isEmpty()) play((index + 1) % tracks.size());
    }

    synchronized void previous() {
        if (!tracks.isEmpty()) play((index - 1 + tracks.size()) % tracks.size());
    }

    synchronized void stop() {
        release();
    }

    private void play(int i) {
        release();
        index = i;
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK);
            mp.setDataSource(ctx, tracks.get(i).uri);
            mp.setOnPreparedListener(m -> {
                prepared = true;
                tv.attach(m.getAudioSessionId());      // chiado de TV (se estiver ligado nos ajustes)
                attachVisualizer(m.getAudioSessionId()); // analisador de espectro (se tiver permissão)
                m.start();
                tv.setPlaying(true);
            });
            mp.setOnCompletionListener(m -> next());
            mp.setOnErrorListener((m, what, extra) -> { release(); return true; });
            player = mp;
            mp.prepareAsync();
        } catch (IOException | RuntimeException e) {
            release();
        }
    }

    private void release() {
        prepared = false;
        tv.release();
        releaseVisualizer();
        if (player != null) {
            try { player.release(); } catch (RuntimeException ignored) { /* já liberado */ }
            player = null;
        }
    }
}
