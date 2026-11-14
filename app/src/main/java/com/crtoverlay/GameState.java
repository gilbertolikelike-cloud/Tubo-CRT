package com.crtoverlay;

import android.content.SharedPreferences;

/** Placar do jogo da espada: kills (da rodada e total), nível e experiência (regra do Sword Familiar). */
final class GameState {
    private GameState() {}

    static int level(SharedPreferences sp) { return Math.max(1, Math.min(99, sp.getInt("g_lvl", 1))); }

    static int exp(SharedPreferences sp) { return Math.max(0, Math.min(99, sp.getInt("g_exp", 0))); }

    static int killsRun(SharedPreferences sp) { return Math.max(0, sp.getInt("g_kills_run", 0)); }

    static int killsTotal(SharedPreferences sp) { return Math.max(0, sp.getInt("g_kills_total", 0)); }

    /**
     * Registra um pato morto. EXP ganha = floor(log2((2 * expBase) / nível)), no mínimo 1; cada nível pede 100 pontos.
     * Devolve true se a espada subiu de nível.
     */
    static boolean addKill(SharedPreferences sp, int expBase) {
        int lvl = level(sp), exp = exp(sp);
        int gain = Math.max(1, (int) Math.floor(Math.log(Math.max(1.0, 2.0 * expBase / lvl)) / Math.log(2.0)));
        exp += gain;
        boolean up = false;
        while (exp >= 100 && lvl < 99) {
            exp -= 100;
            lvl++;
            up = true;
        }
        if (lvl >= 99) exp = Math.min(exp, 99);
        sp.edit().putInt("g_lvl", lvl).putInt("g_exp", exp)
                .putInt("g_kills_run", killsRun(sp) + 1).putInt("g_kills_total", killsTotal(sp) + 1).apply();
        return up;
    }

    /** Zera os kills da rodada (game over ou renascer, quando existirem). */
    static void resetRun(SharedPreferences sp) { sp.edit().putInt("g_kills_run", 0).apply(); }
}
