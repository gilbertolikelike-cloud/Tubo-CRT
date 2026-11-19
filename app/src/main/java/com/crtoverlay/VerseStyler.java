package com.crtoverlay;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cores das falas dos versículos: Jesus (vermelho), Deus (azul), grandes profetas (dourado)
 * e discípulos (verde escuro). A fala começa no travessão (—); quem fala vem do trecho antes dele.
 */
final class VerseStyler {
    static final int RED = 0xFFC00018, BLUE = 0xFF0A3CA8, GOLD = 0xFF9A6A00, GREEN = 0xFF0B5D1E;
    private static final int NONE = 0, GOD = 1, JESUS = 2, PROPHET = 3, DISCIPLE = 4;

    private static final Set<String> NEW_TESTAMENT = set("mateus", "marcos", "lucas", "joao", "atos", "romanos", "1 corintios",
            "2 corintios", "galatas", "efesios", "filipenses", "colossenses", "1 tessalonicenses", "2 tessalonicenses",
            "1 timoteo", "2 timoteo", "tito", "filemom", "hebreus", "tiago", "1 pedro", "2 pedro", "1 joao", "2 joao", "3 joao",
            "judas", "apocalipse");
    private static final Set<String> GOSPELS = set("mateus", "marcos", "lucas", "joao");
    private static final Set<String> LETTERS = set("romanos", "1 corintios", "2 corintios", "galatas", "efesios", "filipenses",
            "colossenses", "1 tessalonicenses", "2 tessalonicenses", "1 timoteo", "2 timoteo", "tito", "filemom", "hebreus",
            "tiago", "1 pedro", "2 pedro", "1 joao", "2 joao", "3 joao", "judas", "apocalipse");
    private static final Set<String> PROPHET_BOOKS = set("isaias", "jeremias", "lamentacoes", "ezequiel", "daniel", "oseias",
            "joel", "amos", "obadias", "jonas", "miqueias", "naum", "habacuque", "sofonias", "ageu", "zacarias", "malaquias");

    private static final Pattern GOD_P = names("Deus", "SENHOR");
    private static final Pattern JESUS_P = names("Jesus", "Cristo", "Messias");
    private static final Pattern PROPHET_P = names("Moisés", "Elias", "Eliseu", "Isaías", "Jeremias", "Ezequiel", "Daniel", "Samuel",
            "Natã", "Amós", "Oseias", "Joel", "Jonas", "Miqueias", "Naum", "Habacuque", "Sofonias", "Ageu", "Zacarias", "Malaquias", "Obadias");
    private static final Pattern DISCIPLE_P = names("Pedro", "João", "Tiago", "André", "Filipe", "Bartolomeu", "Tomé", "Mateus",
            "Tadeu", "Simão", "Paulo", "Barnabé", "discípulos", "apóstolos");

    private VerseStyler() {}

    private static Set<String> set(String... v) { return new HashSet<>(Arrays.asList(v)); }

    private static Pattern names(String... v) {
        return Pattern.compile("(?<!\\p{L})(" + String.join("|", v) + ")(?!\\p{L})");
    }

    /** Tira acento e põe tudo em minúscula: "1 Coríntios" vira "1 corintios". */
    private static String norm(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    private static boolean has(Set<String> books, String b) {
        for (String x : books) if (b.equals(x) || b.startsWith(x + " ")) return true;
        return false;
    }

    static boolean isNewTestament(String book) { return has(NEW_TESTAMENT, norm(book)); }

    /** Trechos coloridos do versículo: {início, fim, cor}. */
    static List<int[]> spans(String text, String book) {
        List<int[]> out = new ArrayList<>();
        String b = norm(book);
        int bookColor = has(PROPHET_BOOKS, b) ? GOLD : has(LETTERS, b) ? GREEN : 0; // livros dos profetas e cartas dos discípulos
        int dash = text.indexOf('\u2014');
        int narrationEnd = dash >= 0 ? dash : text.length();
        if (dash >= 0 && dash < text.length() - 1) {
            int speaker = dash == 0 ? NONE : detectSpeaker(text.substring(0, dash));
            int color = colorOf(speaker, b);
            if (color != 0) out.add(new int[]{dash, text.length(), color});
        }
        if (bookColor != 0 && narrationEnd > 0) out.add(new int[]{0, narrationEnd, bookColor});
        return out;
    }

    /** Quem fala: o primeiro nome conhecido no trecho que antecede o travessão. */
    private static int detectSpeaker(String intro) {
        int best = NONE, bestPos = Integer.MAX_VALUE;
        Object[][] sets = {{GOD_P, GOD}, {JESUS_P, JESUS}, {PROPHET_P, PROPHET}, {DISCIPLE_P, DISCIPLE}};
        for (Object[] s : sets) {
            Matcher m = ((Pattern) s[0]).matcher(intro);
            if (m.find() && m.start() < bestPos) {
                bestPos = m.start();
                best = (Integer) s[1];
            }
        }
        return best;
    }

    private static int colorOf(int speaker, String book) {
        switch (speaker) {
            case GOD: return BLUE;
            case JESUS: return RED;
            case PROPHET: return GOLD;
            case DISCIPLE: return GREEN;
            default:
                if (has(GOSPELS, book)) return RED;          // nos evangelhos, fala sem dono costuma ser de Jesus
                if (has(PROPHET_BOOKS, book)) return BLUE;   // nos profetas, é o recado de Deus
                if (has(LETTERS, book)) return GREEN;
                return 0;
        }
    }
}
