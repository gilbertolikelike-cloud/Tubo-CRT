package com.crtoverlay;

import android.content.Context;
import android.media.MediaPlayer;

/** Efeitos sonoros curtos. Hoje só um: o áudio do botão de RAM (res/raw/ram_clean.mp3, opcional). */
final class Sfx {
    private static MediaPlayer player;

    private Sfx() {}

    /** Toca o áudio da limpeza de RAM. Se apertar de novo, recomeça. Sem o arquivo, não faz nada. */
    static synchronized void ramClean(Context ctx) {
        int id = ctx.getResources().getIdentifier("ram_clean", "raw", ctx.getPackageName());
        if (id == 0) return;
        release();
        player = MediaPlayer.create(ctx.getApplicationContext(), id);
        if (player == null) return;
        player.setOnCompletionListener(mp -> release());
        player.start();
    }

    private static synchronized void release() {
        if (player != null) {
            try { player.release(); } catch (RuntimeException ignored) { /* já liberado */ }
            player = null;
        }
    }
}
