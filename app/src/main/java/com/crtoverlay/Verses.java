package com.crtoverlay;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Versículos de assets/versiculos.txt (uma linha por versículo: "Livro cap:ver texto"). */
final class Verses {
    private static final Pattern LINE = Pattern.compile("^(.+?\\d+:\\d+)\\s+(.*)$");
    private final byte[] data;
    private final int[] starts;

    private Verses(byte[] data, int[] starts) {
        this.data = data;
        this.starts = starts;
    }

    /** Lê o arquivo todo (uns 5 MB). Devolve null se não existir. */
    static Verses load(Context ctx) {
        try (InputStream in = ctx.getAssets().open("versiculos.txt")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(5_000_000);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            byte[] bytes = out.toByteArray();
            int[] starts = new int[40000];
            int k = 0;
            starts[k++] = 0;
            for (int i = 0; i < bytes.length && k < starts.length; i++) {
                if (bytes[i] == '\n') starts[k++] = i + 1;
            }
            return new Verses(bytes, Arrays.copyOf(starts, k));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Um versículo sorteado: {texto, referência}. */
    String[] random(Random rnd) {
        for (int tries = 0; tries < 8; tries++) {
            int i = rnd.nextInt(starts.length);
            int end = i + 1 < starts.length ? starts[i + 1] : data.length;
            String line = new String(data, starts[i], Math.max(0, end - starts[i]), StandardCharsets.UTF_8).trim();
            if (line.length() < 10) continue;
            Matcher m = LINE.matcher(line);
            if (m.find()) return new String[]{m.group(2), m.group(1)};
            return new String[]{line, ""};
        }
        return new String[]{"O SENHOR é o meu pastor; nada me faltará.", "Salmos 23:1"};
    }
}
