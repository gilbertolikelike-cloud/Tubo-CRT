package com.crtoverlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.PowerManager;
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
    private MediaPlayer player;
    private boolean prepared;
    private int index = -1;

    private MusicPlayer(Context ctx) {
        this.ctx = ctx;
        this.sp = ctx.getSharedPreferences("crt", Context.MODE_PRIVATE);
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
            if (player.isPlaying()) player.pause(); else player.start();
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
            mp.setOnPreparedListener(m -> { prepared = true; m.start(); });
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
        if (player != null) {
            try { player.release(); } catch (RuntimeException ignored) { /* já liberado */ }
            player = null;
        }
    }
}
