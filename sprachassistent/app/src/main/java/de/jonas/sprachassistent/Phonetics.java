package de.jonas.sprachassistent;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bestimmt die Aussprache des Aktivierungsworts in der Lautschrift des
 * (englischen) PocketSphinx-Modells. Zuerst wird im englischen Woerterbuch
 * gesucht (z. B. "computer", "jarvis"); sonst wird das Wort mit einfachen
 * deutschen Ausspracheregeln umgewandelt.
 */
public final class Phonetics {

    private Phonetics() {
    }

    /** Ergebnis: Wort -> Aussprachen (mehrere Varianten moeglich). */
    public static Map<String, List<String>> lookup(Context ctx, String phrase, String override) throws Exception {
        String[] words = cleanWords(phrase);
        Map<String, List<String>> out = new LinkedHashMap<String, List<String>>();
        if (words.length == 0) throw new Exception("Kein Aktivierungswort eingetragen.");

        if (override.length() > 0) {
            // Experten-Eingabe: eine Aussprache fuer die ganze Phrase, als ein "Wort"
            String joined = joinWords(words, "_");
            List<String> l = new ArrayList<String>();
            l.add(override.replaceAll("[^A-Z ]", " ").replaceAll(" +", " ").trim());
            out.put(joined, l);
            return out;
        }

        for (String w : words) out.put(w, new ArrayList<String>());
        // englisches Woerterbuch durchsuchen (nur ASCII-Woerter koennen darin stehen)
        BufferedReader r = new BufferedReader(new InputStreamReader(ctx.getAssets().open("ps/cmudict-en-us.dict"), "UTF-8"), 16384);
        try {
            String line;
            while ((line = r.readLine()) != null) {
                int sp = line.indexOf(' ');
                if (sp <= 0) continue;
                String w = line.substring(0, sp);
                int paren = w.indexOf('(');
                if (paren > 0) w = w.substring(0, paren);
                List<String> l = out.get(w);
                if (l != null) l.add(line.substring(sp + 1).trim());
            }
        } finally {
            r.close();
        }
        for (Map.Entry<String, List<String>> e : out.entrySet()) {
            String german = germanToPhones(e.getKey());
            if (e.getValue().isEmpty()) {
                e.getValue().add(german);
            } else if (!e.getValue().contains(german)) {
                // deutsche Aussprache als zusaetzliche Variante
                e.getValue().add(german);
            }
        }
        return out;
    }

    public static String[] cleanWords(String phrase) {
        String p = phrase.toLowerCase(Locale.GERMANY).replaceAll("[^a-zäöüß' ]", " ").trim();
        if (p.length() == 0) return new String[0];
        return p.split(" +");
    }

    public static String joinWords(String[] words, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(w);
        }
        return sb.toString();
    }

    private static boolean isVowel(char c) {
        return "aeiouyäöü".indexOf(c) >= 0;
    }

    /** Sehr einfache deutsche Ausspracheregeln -> englische Modell-Laute (ARPAbet). */
    public static String germanToPhones(String word) {
        String w = word.replace("'", "");
        List<String> ph = new ArrayList<String>();
        int n = w.length();
        int i = 0;
        while (i < n) {
            char c = w.charAt(i);
            String rest = w.substring(i);
            char prev = i > 0 ? w.charAt(i - 1) : ' ';
            boolean atStart = i == 0;
            boolean atEnd = i == n - 1;

            if (rest.startsWith("tsch")) { ph.add("CH"); i += 4; continue; }
            if (rest.startsWith("sch")) { ph.add("SH"); i += 3; continue; }
            if (atStart && (rest.startsWith("sp") || rest.startsWith("st"))) {
                ph.add("SH"); ph.add(rest.charAt(1) == 'p' ? "P" : "T"); i += 2; continue;
            }
            if (rest.startsWith("chs")) { ph.add("K"); ph.add("S"); i += 3; continue; }
            if (rest.startsWith("ch")) {
                if (atStart) ph.add("K");
                else if (prev == 'a' || prev == 'o' || prev == 'u') ph.add("HH");
                else ph.add("SH");
                i += 2; continue;
            }
            if (rest.startsWith("ig") && i + 2 == n) { ph.add("IH"); ph.add("SH"); i += 2; continue; }
            if (rest.startsWith("ck")) { ph.add("K"); i += 2; continue; }
            if (rest.startsWith("qu")) { ph.add("K"); ph.add("V"); i += 2; continue; }
            if (rest.startsWith("ph")) { ph.add("F"); i += 2; continue; }
            if (rest.startsWith("th")) { ph.add("T"); i += 2; continue; }
            if (rest.startsWith("tz")) { ph.add("T"); ph.add("S"); i += 2; continue; }
            if (rest.startsWith("ng")) { ph.add("NG"); i += 2; continue; }
            if (rest.startsWith("nk")) { ph.add("NG"); ph.add("K"); i += 2; continue; }
            if (rest.startsWith("ei") || rest.startsWith("ai") || rest.startsWith("ey") || rest.startsWith("ay")) {
                ph.add("AY"); i += 2; continue;
            }
            if (rest.startsWith("eu") || rest.startsWith("äu")) { ph.add("OY"); i += 2; continue; }
            if (rest.startsWith("au")) { ph.add("AW"); i += 2; continue; }
            if (rest.startsWith("ie")) { ph.add("IY"); i += 2; continue; }
            if (rest.startsWith("er") && i + 2 == n && i > 0) { ph.add("ER"); i += 2; continue; }
            if (rest.startsWith("aa")) { ph.add("AA"); i += 2; continue; }
            if (rest.startsWith("ee")) { ph.add("EY"); i += 2; continue; }
            if (rest.startsWith("oo")) { ph.add("OW"); i += 2; continue; }
            if (isVowel(c) && i + 1 < n && w.charAt(i + 1) == 'h') {
                // Dehnungs-h: langer Vokal, h stumm
                ph.add(longVowel(c)); i += 2; continue;
            }
            if (isVowel(c)) {
                if (c == 'e' && atEnd && i > 0) ph.add("AH");
                else ph.add(shortVowel(c));
                i++; continue;
            }
            // doppelte Konsonanten nur einmal
            if (i + 1 < n && w.charAt(i + 1) == c) { i++; continue; }
            switch (c) {
                case 'b': ph.add(atEnd ? "P" : "B"); break;
                case 'd': ph.add(atEnd ? "T" : "D"); break;
                case 'g': ph.add(atEnd ? "K" : "G"); break;
                case 'c': ph.add(i + 1 < n && "eiy".indexOf(w.charAt(i + 1)) >= 0 ? "T S" : "K"); break;
                case 'f': ph.add("F"); break;
                case 'h': if (i + 1 < n && isVowel(w.charAt(i + 1))) ph.add("HH"); break;
                case 'j': ph.add("Y"); break;
                case 'k': ph.add("K"); break;
                case 'l': ph.add("L"); break;
                case 'm': ph.add("M"); break;
                case 'n': ph.add("N"); break;
                case 'p': ph.add("P"); break;
                case 'r': ph.add("R"); break;
                case 's': ph.add((atStart || isVowel(prev)) && i + 1 < n && isVowel(w.charAt(i + 1)) ? "Z" : "S"); break;
                case 'ß': ph.add("S"); break;
                case 't': ph.add("T"); break;
                case 'v': ph.add("F"); break;
                case 'w': ph.add("V"); break;
                case 'x': ph.add("K"); ph.add("S"); break;
                case 'z': ph.add("T"); ph.add("S"); break;
                default: break;
            }
            i++;
        }
        StringBuilder sb = new StringBuilder();
        for (String p : ph) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(p);
        }
        return sb.toString();
    }

    private static String shortVowel(char c) {
        switch (c) {
            case 'a': return "AA";
            case 'e': return "EH";
            case 'i': return "IH";
            case 'o': return "AO";
            case 'u': return "UH";
            case 'ä': return "EH";
            case 'ö': return "ER";
            case 'ü': return "UW";
            default: return "IY"; // y
        }
    }

    private static String longVowel(char c) {
        switch (c) {
            case 'a': return "AA";
            case 'e': return "EY";
            case 'i': return "IY";
            case 'o': return "OW";
            case 'u': return "UW";
            case 'ä': return "EY";
            case 'ö': return "ER";
            case 'ü': return "UW";
            default: return "IY";
        }
    }
}
