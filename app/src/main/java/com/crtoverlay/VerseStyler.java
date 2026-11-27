package com.crtoverlay;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cores do texto da Bíblia. Regra de ouro: só dá a cor de quem fala quando o dono da fala é CERTO
 * (nome logo no começo da frase, um verbo de fala, sem "que", sem outra oração no meio). Na dúvida, a fala fica
 * com a cor de "outras pessoas" (continua sendo fala) ou sem cor, nunca com a cor errada.
 * Cores: 0 Deus, 1 Jesus, 2 profetas, 3 discípulos, 4 outras pessoas.
 */
final class VerseStyler {
    static final int NONE = -1, GOD = 0, JESUS = 1, PROPHET = 2, DISCIPLE = 3, PRON = 9;
    private static final char DASH = '\u2014';
    private static final String VERBS = "disse|diz|dizia|falou|fala|respondeu|responde|perguntou|pergunta|ordenou|ordena|gritou|exclamou|declarou|replicou|prometeu|mandou|chamou|ensinou|orou|clamou|continuou|acrescentou|advertiu|explicou|prosseguiu|anunciou|profetizou|insistiu|repetiu|aconselhou|rezou|cantou";
    private static final String L = "\\p{L}";

    private static final Pattern LEAD = Pattern.compile("^\\s*(?:(?:Então|Aí|Mas|Porém|Depois|Nisso|Logo|Também|Ainda|Assim|E|Em seguida|Por isso|Nesse momento|Pois|Porque|Ora|Agora),?\\s+)+", Pattern.CASE_INSENSITIVE);
    private static final Pattern ART = Pattern.compile("^(?:o|a)\\s+", Pattern.CASE_INSENSITIVE);
    private static final String GODN = "Elohim|Yahuah|Yah|Todo-Poderoso";
    private static final String JESN = "Yahusha|Messias|Cristo";
    private static final String PRON_N = "Moisés|Elias|Eliseu|Isaías|Jeremias|Ezequiel|Daniel|Samuel|Natã|Amós|Oseias|Jonas|Miqueias|Naum|Habacuque|Ageu|Malaquias";
    private static final String DISN = "Pedro|André|Tomé|Natanael|Bartolomeu|Paulo|Barnabé|Estêvão|Timóteo";
    private static final Pattern P_GOD = Pattern.compile("^(?:" + GODN + ")(?![\\p{L}-])");
    private static final Pattern P_JES = Pattern.compile("^(?:" + JESN + ")(?![\\p{L}])");
    private static final Pattern P_PRO = Pattern.compile("^(?:" + PRON_N + ")(?![\\p{L}])");
    private static final Pattern P_DIS = Pattern.compile("^(?:" + DISN + ")(?![\\p{L}])");
    private static final Pattern ANY_NAME = Pattern.compile("(?<![\\p{L}-])(?:" + GODN + "|" + JESN + "|" + PRON_N + "|" + DISN + ")(?![\\p{L}-])");
    private static final Pattern PRONOUN = Pattern.compile("^(?:ele|ela)(?![\\p{L}])", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERB_START = Pattern.compile("^(?:" + VERBS + ")(?![\\p{L}])", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONT = Pattern.compile("(?:continuou|acrescentou|prosseguiu|disse ainda|disse também|ainda disse|também disse|continua|diz ainda|diz também)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SP = Pattern.compile("(?<![\\p{L}])(?:" + VERBS + "|dizendo)(?![\\p{L}])", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPV = Pattern.compile("(?<![\\p{L}])(?:" + VERBS + ")(?![\\p{L}])", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUE = Pattern.compile("(?<![\\p{L}])que(?![\\p{L}])", Pattern.CASE_INSENSITIVE);
    private static final Pattern E_WORD = Pattern.compile("(?<![\\p{L}])e(?![\\p{L}])\\s+(\\S+)");
    private static final Pattern FILLER = Pattern.compile("^(?:ent[aã]o|ainda|também)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OTHER_PROPER = Pattern.compile("(?<=[a-zà-ú,;] )([A-ZÀ-Ú][\\p{L}-]+)");
    private static final Pattern NARR = Pattern.compile("^(?:" + VERBS + ")(?![\\p{L}])(?:\\s+(?:ainda|também|então|de novo))?\\s+(?:o |a |ao |à )?(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SENT = Pattern.compile("(?<=[.!?])\\s+");

    private VerseStyler() { }

    /** @return lista de {início, fim, cor}. colors = {Deus, Jesus, profetas, discípulos, outras pessoas}. */
    static List<int[]> spans(Verses v, int idx, int[] colors, boolean names, boolean others) {
        List<int[]> out = new ArrayList<>();
        for (int[] s : segments(v, idx, 0)) {
            int color = s[2] >= 0 ? colors[s[2]] : (others ? colors[4] : 0);
            if (color != 0) out.add(new int[]{s[0], s[1], color});
        }
        for (int[] q : quotes(v, idx)) out.add(new int[]{q[0], q[1], colors[q[2]]});     // “...” que Elohim/Yahusha disseram
        if (names) {
            String t = v.text(idx);
            boolean nt = v.isNewTestament(v.bookOf(idx));
            Matcher m = ANY_NAME.matcher(t);
            while (m.find()) {
                int who = cat(m.group(), nt);
                if (who >= 0) out.add(new int[]{m.start(), m.end(), colors[who]});
            }
        }
        return out;
    }

    private static final Pattern QUOTE = Pattern.compile("\u201C([^\u201D]*)\u201D");
    private static final Pattern CONT_INTRO = Pattern.compile("^(?:E|Também)?\\s*(?:disse|diz|falou)(?:\\s+também)?\\s*:?\\s*$", Pattern.CASE_INSENSITIVE);

    /**
     * Citações entre aspas: se a frase antes diz "Elohim disse:" (ou Yahusha, Moisés...), o que está entre aspas
     * fica na cor de QUEM falou, mesmo que quem esteja contando seja outra pessoa. "E disse também:" continua o mesmo.
     */
    private static List<int[]> quotes(Verses v, int idx) {
        List<int[]> out = new ArrayList<>();
        String t = v.text(idx);
        if (t.indexOf('\u201C') < 0) return out;
        boolean nt = v.isNewTestament(v.bookOf(idx));
        Matcher m = QUOTE.matcher(t);
        int prevEnd = 0, lastWho = NONE;
        while (m.find()) {
            String before = t.substring(prevEnd, m.start()).trim();
            String[] sents = SENT.split(before);
            String clause = sents.length == 0 ? "" : sents[sents.length - 1].trim();
            int who = NONE;
            if (!clause.isEmpty() && clause.endsWith(":")) {
                if (CONT_INTRO.matcher(clause).find()) who = lastWho;
                else if (cleanTail(clause)) {
                    int k = subjSentence(clause, nt);
                    if (k != PRON) who = k;
                }
            }
            if (who >= 0) out.add(new int[]{m.start(), m.end(), who});
            lastWho = who;
            prevEnd = m.end();
        }
        return out;
    }

    private static int cat(String s, boolean nt) {
        if (P_GOD.matcher(s).find()) return GOD;
        if (P_JES.matcher(s).find()) return JESUS;
        if (!nt && P_PRO.matcher(s).find()) return PROPHET;
        if (nt && P_DIS.matcher(s).find()) return DISCIPLE;
        return NONE;
    }

    private static String strip(String s) {
        Matcher m = LEAD.matcher(s);
        if (m.find()) s = s.substring(m.end());
        m = ART.matcher(s);
        if (m.find()) s = s.substring(m.end());
        return s;
    }

    private static boolean otherProper(String text, boolean nt) {
        Matcher m = OTHER_PROPER.matcher(text);
        while (m.find()) if (cat(m.group(1), nt) == NONE) return true;
        return false;
    }

    /** Depois do nome: poucas palavras, um só verbo de fala, sem "que" e sem outra oração. */
    private static boolean cleanTail(String sent) {
        String s = sent.trim();
        if (s.endsWith(":")) s = s.substring(0, s.length() - 1).trim();
        if (QUE.matcher(s).find() || s.split("\\s+").length > 16 || !SP.matcher(s).find()) return false;
        String s2 = s.replaceAll(",\\s*dizendo$", "");
        if (s2.indexOf(',') >= 0) return false;
        Matcher m = E_WORD.matcher(s2);
        while (m.find()) {
            String w = m.group(1);
            if (!SPV.matcher(w).find() && !FILLER.matcher(w).find()) return false;
        }
        int n = 0;
        Matcher sv = SPV.matcher(s2);
        while (sv.find()) n++;
        return n <= 1;
    }

    private static int subjSentence(String s, boolean nt) {
        String x = strip(s.trim());
        int k = cat(x, nt);
        if (k != NONE) return k;
        if (PRONOUN.matcher(x).find() || VERB_START.matcher(x).find()) return PRON;
        return NONE;
    }

    private static int speakerPre(String pre, boolean nt, Verses v, int idx, int depth) {
        String[] sents = SENT.split(pre.trim());
        if (sents.length == 0) return NONE;
        String last = sents[sents.length - 1];
        if (!cleanTail(last)) return NONE;
        int k = subjSentence(last, nt);
        if (k != PRON) return k;
        for (int i = sents.length - 2; i >= 0; i--) {
            String s2 = strip(sents[i].trim());
            int kk = cat(s2, nt);
            if (kk != NONE) return otherProper(sents[i], nt) ? NONE : kk;
            if (!s2.isEmpty() && Character.isUpperCase(s2.charAt(0)) && !PRONOUN.matcher(s2).find()) return NONE;
        }
        // "Ele continuou:": só continua quem acabou de falar no versículo de antes
        if (!CONT.matcher(last).find() || idx < 1 || depth > 3) return NONE;
        if (v.bookOf(idx - 1) != v.bookOf(idx) || v.chapOf(idx - 1) != v.chapOf(idx)) return NONE;
        List<int[]> prev = segments(v, idx - 1, depth + 1);
        return prev.isEmpty() ? NONE : prev.get(0)[2];
    }

    private static int lookback(Verses v, int idx, boolean nt, int depth) {
        int b = v.bookOf(idx), c = v.chapOf(idx);
        for (int j = idx - 1; j >= Math.max(0, idx - 8); j--) {
            if (v.bookOf(j) != b || v.chapOf(j) != c) return NONE;
            String pt = v.text(j);
            int pd = pt.indexOf(DASH);
            if (pd < 0) return pt.trim().endsWith(":") ? speakerPre(pt, nt, v, j, depth + 1) : NONE;
            if (pt.indexOf(DASH, pd + 1) >= 0) return NONE;
            if (pd == 0) continue;
            String intro = pt.substring(0, pd).trim();
            return intro.endsWith(":") ? speakerPre(intro, nt, v, j, depth + 1) : NONE;
        }
        return NONE;
    }

    /** Falas do versículo: {início, fim, dono (-1 = fala de alguém que não dá para ter certeza)}. */
    private static List<int[]> segments(Verses v, int idx, int depth) {
        List<int[]> out = new ArrayList<>();
        String t = v.text(idx);
        List<Integer> pos = new ArrayList<>();
        for (int i = 0; i < t.length(); i++) if (t.charAt(i) == DASH) pos.add(i);
        int n = pos.size();
        if (n == 0) return out;
        boolean nt = v.isNewTestament(v.bookOf(idx));
        int[] st = new int[n + 1], en = new int[n + 1];
        st[0] = 0;
        en[0] = pos.get(0);
        for (int i = 1; i <= n; i++) {
            st[i] = pos.get(i - 1) + 1;
            en[i] = i < n ? pos.get(i) : t.length();
        }
        for (int k = 1; k <= n; k += 2) {
            String prev = t.substring(st[k - 1], en[k - 1]).trim();
            String nxt = k + 1 <= n ? t.substring(st[k + 1], en[k + 1]).trim() : "";
            int who = NONE;
            boolean speech;
            if (!prev.isEmpty()) {
                if (!prev.endsWith(":")) break;                        // narração sem dois pontos: não arrisca
                who = speakerPre(prev, nt, v, idx, depth);
                speech = true;
            } else {
                speech = k == 1;
            }
            Matcher nm = NARR.matcher(nxt);
            if (who == NONE && nm.find()) {
                who = cat(nm.group(1).replaceAll("[.,;:!?]+$", ""), nt);
            }
            if (!speech && !VERB_START.matcher(nxt).find()) break;
            if (who == NONE && k == 1 && prev.isEmpty() && n == 1) who = lookback(v, idx, nt, depth);
            out.add(new int[]{st[k] - 1, en[k], who});
        }
        return out;
    }
}
