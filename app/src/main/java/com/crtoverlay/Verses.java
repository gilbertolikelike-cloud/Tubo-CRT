package com.crtoverlay;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê assets/versiculos.txt SEM alterar o arquivo. Formato do Gilberto:
 * primeiro versículo do capítulo: "Livro, capítulo N, versículo 1. texto"; os outros: "Versículo M. texto".
 */
final class Verses {
    private static Verses shared;

    static synchronized Verses shared(Context ctx) {
        if (shared == null) shared = load(ctx.getApplicationContext());
        return shared;
    }

    private static final Pattern HEAD = Pattern.compile("^(.+?),\\s*cap[íi]tulo\\s+(\\d+),\\s*vers[íi]culo\\s+(\\d+)\\.\\s*");
    private static final Pattern NEXT = Pattern.compile("^Vers[íi]culo\\s+(\\d+)\\.\\s*");

    private final byte[] data;
    private final int[] tStart, tEnd;
    private final short[] bookOf, chapOf, verseOf;
    private final String[] bookNames;
    private final int[][] chFirst;
    private final int[] chBase;
    private final int totalChapters;

    private Verses(byte[] data, int[] ts, int[] te, short[] bo, short[] co, short[] vo, String[] names, int[][] chFirst) {
        this.data = data; tStart = ts; tEnd = te; bookOf = bo; chapOf = co; verseOf = vo; bookNames = names; this.chFirst = chFirst;
        chBase = new int[names.length];
        int sum = 0;
        for (int b = 0; b < names.length; b++) { chBase[b] = sum; sum += chFirst[b].length; }
        totalChapters = sum;
    }

    private static Verses load(Context ctx) {
        try (InputStream in = ctx.getAssets().open("versiculos.txt")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(5_000_000);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            byte[] data = bo.toByteArray();
            int cap = 40000, count = 0;
            int[] ts = new int[cap], te = new int[cap];
            short[] bk = new short[cap], ch = new short[cap], vs = new short[cap];
            LinkedHashMap<String, Integer> books = new LinkedHashMap<>();
            ArrayList<ArrayList<Integer>> starts = new ArrayList<>();
            int curBook = -1, curChap = -1, curVerse = 0;
            int pos = 0;
            while (pos < data.length) {
                int e = pos;
                while (e < data.length && data[e] != '\n') e++;
                int end = e;
                if (end > pos && data[end - 1] == '\r') end--;
                if (end > pos) {
                    String line = new String(data, pos, end - pos, StandardCharsets.UTF_8);
                    Matcher mh = HEAD.matcher(line);
                    int pref = -1;
                    if (mh.find()) {
                        String name = mh.group(1).trim();
                        Integer bi = books.get(name);
                        if (bi == null) { bi = books.size(); books.put(name, bi); starts.add(new ArrayList<>()); }
                        int chap = Integer.parseInt(mh.group(2));
                        if (bi != curBook || chap != curChap) starts.get(bi).add(count);
                        curBook = bi; curChap = chap; curVerse = Integer.parseInt(mh.group(3));
                        pref = mh.end();
                    } else {
                        Matcher mn = NEXT.matcher(line);
                        if (curBook >= 0 && mn.find()) { curVerse = Integer.parseInt(mn.group(1)); pref = mn.end(); }
                    }
                    if (pref >= 0 && count < cap) {
                        ts[count] = pos + line.substring(0, pref).getBytes(StandardCharsets.UTF_8).length;
                        te[count] = end;
                        bk[count] = (short) curBook; ch[count] = (short) curChap; vs[count] = (short) curVerse;
                        count++;
                    } else if (count > 0) {
                        te[count - 1] = end; // linha solta: continua o versículo anterior
                    }
                }
                pos = e + 1;
            }
            int[][] chFirst = new int[starts.size()][];
            for (int b = 0; b < chFirst.length; b++) {
                chFirst[b] = new int[starts.get(b).size()];
                for (int i = 0; i < chFirst[b].length; i++) chFirst[b][i] = starts.get(b).get(i);
            }
            return new Verses(data, java.util.Arrays.copyOf(ts, count), java.util.Arrays.copyOf(te, count),
                    java.util.Arrays.copyOf(bk, count), java.util.Arrays.copyOf(ch, count), java.util.Arrays.copyOf(vs, count),
                    books.keySet().toArray(new String[0]), chFirst);
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    int size() { return tStart.length; }
    String text(int i) { return new String(data, tStart[i], tEnd[i] - tStart[i], StandardCharsets.UTF_8).trim(); }
    String ref(int i) { return bookNames[bookOf[i]] + " " + chapOf[i] + ":" + verseOf[i]; }
    String[] get(int i) { return new String[]{text(i), ref(i)}; }
    String[] random(Random r) { return get(r.nextInt(size())); }
    int bookOf(int i) { return bookOf[i]; }
    int chapOf(int i) { return chapOf[i]; }
    int verseOf(int i) { return verseOf[i]; }
    int bookCount() { return bookNames.length; }
    String bookName(int b) { return bookNames[b]; }
    int chapterCount(int b) { return chFirst[b].length; }
    int chapterFirst(int b, int c) { return chFirst[b][c]; }
    int chapterLength(int b, int c) {
        int end = c + 1 < chFirst[b].length ? chFirst[b][c + 1] : (b + 1 < bookNames.length ? chFirst[b + 1][0] : size());
        return end - chFirst[b][c];
    }
    int totalChapters() { return totalChapters; }
    /** Mateus é o 1º livro do Novo Testamento. */
    boolean isNewTestament(int b) {
        for (int i = 0; i < bookNames.length; i++) if (bookNames[i].equalsIgnoreCase("Mateus")) return b >= i;
        return b >= 39;
    }
    /** Número do capítulo na Bíblia inteira (Gn 1 = 1 ... Ap 22 = 1189): o número que vem na frente dos MP3. */
    int globalChapter(int b, int c) { return chBase[b] + c + 1; }
    int[] locate(int g) {
        for (int b = 0; b < bookNames.length; b++) if (g <= chBase[b] + chFirst[b].length) return new int[]{b, g - chBase[b] - 1};
        return new int[]{bookNames.length - 1, chFirst[bookNames.length - 1].length - 1};
    }
}
