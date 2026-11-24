package com.crtoverlay;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cores das falas. Regra de segurança: só pinta quando tem certeza de quem fala. Quem fala é o 1º nome do trecho
 * que vem antes do travessão (ex.: "Então Yahusha disse: —"); se o sujeito for outra pessoa (Pilatos), um "ele"
 * ou um trecho com mais de um travessão, NÃO pinta. É melhor um versículo sem cor do que com a cor errada.
 */
final class VerseStyler {
    static final int NONE = 0, GOD = 1, JESUS = 2, PROPHET = 3, DISCIPLE = 4;
    private static final char DASH = '\u2014';

    private static final Pattern LEAD = Pattern.compile("^\\s*(?:(?:Então|Aí|Mas|Porém|Depois|Nisso|Logo|Também|Ainda|Assim|E|Em seguida)[,]?\\s+)+", Pattern.CASE_INSENSITIVE);
    private static final Pattern ART = Pattern.compile("^(?:o|a)\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern GOD_P = Pattern.compile("^(Elohim|Yahuah|Yah|Deus|Todo-Poderoso)(?![\\p{L}-])");
    private static final Pattern JESUS_P = Pattern.compile("^(Yahusha|Messias|Cristo)(?![\\p{L}])");
    private static final Pattern PROPHET_P = Pattern.compile("^(Moisés|Elias|Eliseu|Isaías|Jeremias|Ezequiel|Daniel|Samuel|Natã|Amós|Oseias|Joel|Jonas|Miqueias|Naum|Habacuque|Sofonias|Ageu|Zacarias|Malaquias|Obadias)(?![\\p{L}])");
    private static final Pattern DISCIPLE_P = Pattern.compile("^(Pedro|André|Filipe|Tomé|Natanael|Paulo|Estêvão|Barnabé)(?![\\p{L}])");

    private VerseStyler() { }

    static List<int[]> spans(Verses v, int idx, int god, int jesus, int prophet, int disciple) {
        List<int[]> out = new ArrayList<>();
        String t = v.text(idx);
        int d = t.indexOf(DASH);
        if (d < 0 || t.indexOf(DASH, d + 1) >= 0) return out;
        int who = d == 0 ? lookBack(v, idx) : (t.substring(0, d).trim().endsWith(":") ? subject(t.substring(0, d), v.isNewTestament(v.bookOf(idx))) : NONE);
        // quando o dono do trecho é o versículo anterior, a regra de testamento usa o livro atual
        int color = who == GOD ? god : who == JESUS ? jesus : who == PROPHET ? prophet : who == DISCIPLE ? disciple : 0;
        if (d == 0 && who != NONE) who = fix(who, v, idx);
        if (color != 0) out.add(new int[]{d, t.length(), color});
        return out;
    }

    private static int fix(int who, Verses v, int idx) { return who; }

    private static int lookBack(Verses v, int idx) {
        int b = v.bookOf(idx), c = v.chapOf(idx);
        boolean nt = v.isNewTestament(b);
        for (int j = idx - 1; j >= Math.max(0, idx - 8); j--) {
            if (v.bookOf(j) != b || v.chapOf(j) != c) return NONE;
            String t = v.text(j);
            int d = t.indexOf(DASH);
            if (d < 0) return t.trim().endsWith(":") ? subject(t, nt) : NONE;
            if (t.indexOf(DASH, d + 1) >= 0) return NONE;
            if (d == 0) continue;
            return t.substring(0, d).trim().endsWith(":") ? subject(t.substring(0, d), nt) : NONE;
        }
        return NONE;
    }

    /** Sujeito da última frase do trecho. Só aceita se o nome for a primeira palavra (depois de "Então", "E", "o"...). */
    private static int subject(String intro, boolean nt) {
        String s = intro.trim();
        int cut = Math.max(Math.max(s.lastIndexOf(". "), s.lastIndexOf("! ")), s.lastIndexOf("? "));
        if (cut >= 0) s = s.substring(cut + 2);
        Matcher m = LEAD.matcher(s);
        if (m.find()) s = s.substring(m.end());
        m = ART.matcher(s);
        if (m.find()) s = s.substring(m.end());
        if (GOD_P.matcher(s).find()) return GOD;
        if (JESUS_P.matcher(s).find()) return JESUS;
        if (!nt && PROPHET_P.matcher(s).find()) return PROPHET;
        if (nt && DISCIPLE_P.matcher(s).find()) return DISCIPLE;
        return NONE;
    }
}
