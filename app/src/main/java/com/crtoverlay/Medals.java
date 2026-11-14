package com.crtoverlay;

import android.content.SharedPreferences;
import android.util.Base64;
import java.util.Random;

/** Uma medalha por versículo: guarda quais já foram desbloqueados (um bit por versículo, salvo em texto). */
final class Medals {
    private static final String KEY = "medals_bits";
    private final SharedPreferences sp;
    private final int total;
    private final long[] bits;
    private int count;

    Medals(SharedPreferences sp, int total) {
        this.sp = sp;
        this.total = total;
        bits = new long[(total + 63) / 64];
        String saved = sp.getString(KEY, "");
        if (!saved.isEmpty()) {
            try {
                byte[] raw = Base64.decode(saved, Base64.NO_WRAP);
                for (int i = 0; i < bits.length && i * 8 + 7 < raw.length; i++) {
                    long v = 0;
                    for (int b = 7; b >= 0; b--) v = (v << 8) | (raw[i * 8 + b] & 0xFF);
                    bits[i] = v;
                }
            } catch (IllegalArgumentException ignored) {
                // texto salvo estragado: começa do zero
            }
        }
        for (long b : bits) count += Long.bitCount(b);
    }

    int count() { return count; }

    int total() { return total; }

    boolean has(int i) { return i >= 0 && i < total && (bits[i >> 6] & (1L << (i & 63))) != 0; }

    /** Desbloqueia a medalha do versículo i. Devolve true se era nova. */
    boolean unlock(int i) {
        if (i < 0 || i >= total || has(i)) return false;
        bits[i >> 6] |= 1L << (i & 63);
        count++;
        save();
        return true;
    }

    /** Quantas medalhas já existem entre os versículos [from, from + n). */
    int countRange(int from, int n) {
        int c = 0;
        for (int i = from; i < from + n && i < total; i++) if (has(i)) c++;
        return c;
    }

    /** Um versículo que ainda não deu medalha (-1 se já tem todas). */
    int randomLocked(Random rnd) {
        if (count >= total || total == 0) return -1;
        int start = rnd.nextInt(total);
        for (int n = 0; n < total; n++) {
            int k = (start + n) % total;
            if (!has(k)) return k;
        }
        return -1;
    }

    private void save() {
        byte[] raw = new byte[bits.length * 8];
        for (int i = 0; i < bits.length; i++) {
            long v = bits[i];
            for (int b = 0; b < 8; b++) { raw[i * 8 + b] = (byte) (v & 0xFF); v >>= 8; }
        }
        sp.edit().putString(KEY, Base64.encodeToString(raw, Base64.NO_WRAP)).apply();
    }
}
