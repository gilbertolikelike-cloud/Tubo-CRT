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
            if (k > 0 && starts[k - 1] >= bytes.length) k--; // não conta a linha vazia depois do último "\n"
            return new Verses(bytes, Arrays.copyOf(starts, k));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    int size() { return starts.length; }

    /** O versículo número i: {texto, referência}. */
    String[] get(int i) {
        int end = i + 1 < starts.length ? starts[i + 1] : data.length;
        String line = new String(data, starts[i], Math.max(0, end - starts[i]), StandardCharsets.UTF_8).trim();
        Matcher m = LINE.matcher(line);
        if (m.find()) return new String[]{m.group(2), m.group(1)};
        return new String[]{line, ""};
    }

    /** Livros da Bíblia na ordem do arquivo: nome, primeiro versículo e quantos versículos tem. */
    static final class Books {
        String[] names;
        int[] start, count;
    }

    Books books() {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> st = new java.util.ArrayList<>(), ct = new java.util.ArrayList<>();
        String last = null;
        for (int i = 0; i < starts.length; i++) {
            String ref = get(i)[1];
            if (ref.isEmpty()) continue;
            int sp = ref.lastIndexOf(' ');
            String book = sp > 0 ? ref.substring(0, sp) : ref;
            if (!book.equals(last)) {
                names.add(book);
                st.add(i);
                ct.add(1);
                last = book;
            } else {
                ct.set(ct.size() - 1, ct.get(ct.size() - 1) + 1);
            }
        }
        Books b = new Books();
        b.names = names.toArray(new String[0]);
        b.start = new int[st.size()];
        b.count = new int[ct.size()];
        for (int i = 0; i < b.start.length; i++) { b.start[i] = st.get(i); b.count[i] = ct.get(i); }
        return b;
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
