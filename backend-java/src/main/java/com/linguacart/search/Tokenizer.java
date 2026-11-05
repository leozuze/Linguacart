package com.linguacart.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text helpers for search. A direct port of the reference pipeline in
 * research/evaluation/run_dev_eval.py (docs/08-INTEGRATION-GUIDE.md, section 4).
 */
public final class Tokenizer {

    private static final Set<String> STOP = Set.of("the", "a", "an", "of", "for", "and", "with", "to", "in",
            "is", "are", "it", "on", "at", "by", "or", "from", "this", "that", "your", "you", "can", "be");
    private static final Map<String, String> UNIT_NORM = Map.of(
            "litre", "l", "litres", "l", "liter", "l", "liters", "l");
    private static final Pattern QTY = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(kg|g|ml|litres?|liters?|l|gb|mah|cm)\\b");
    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");
    private static final String[] SHONA_PREFIXES = {"dze", "ye", "we", "re", "ze", "ne"};

    private Tokenizer() {}

    /** Light stemming: drops a trailing "ing" and a plural "s". */
    public static String stem(String t) {
        if (t.length() > 5 && t.endsWith("ing")) {
            return t.substring(0, t.length() - 3);
        }
        if (t.length() > 3 && t.endsWith("s") && !t.endsWith("ss")) {
            return t.substring(0, t.length() - 1);
        }
        return t;
    }

    /** Lowercase words (stemmed, no stopwords) followed by normalised quantities such as "1kg" or "2l". */
    public static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        String t = text.toLowerCase(Locale.ROOT);
        List<String> quantities = new ArrayList<>();
        Matcher q = QTY.matcher(t);
        while (q.find()) {
            String unit = q.group(2);
            quantities.add(q.group(1) + UNIT_NORM.getOrDefault(unit, unit));
        }
        t = QTY.matcher(t).replaceAll(" ");
        Matcher w = WORD.matcher(t);
        while (w.find()) {
            String word = w.group();
            if (!STOP.contains(word)) {
                out.add(stem(word));
            }
        }
        out.addAll(quantities);
        return out;
    }

    /** Lowercase, every character except a-z, 0-9 and whitespace becomes a space, spaces collapsed. */
    public static String normalizeQuery(String query) {
        if (query == null) {
            return "";
        }
        String s = query.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s]", " ");
        return s.trim().replaceAll("\\s+", " ");
    }

    /** Cleans a machine-translated query: lowercase, no leading the/a/an, no trailing punctuation. */
    public static String cleanTranslation(String text) {
        if (text == null) {
            return "";
        }
        String t = text.trim().toLowerCase(Locale.ROOT).replaceAll("^(the|a|an)\\s+", "");
        int start = 0;
        int end = t.length();
        while (start < end && " .,!?;:".indexOf(t.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && " .,!?;:".indexOf(t.charAt(end - 1)) >= 0) {
            end--;
        }
        return t.substring(start, end);
    }

    /** "yesamsung" becomes "samsung" when "samsung" is a catalogue word and the prefix is a Shona linker. */
    public static String stripShonaPrefix(String token, Set<String> vocabulary) {
        if (vocabulary.contains(token)) {
            return token;
        }
        for (String prefix : SHONA_PREFIXES) {
            if (token.startsWith(prefix) && token.length() - prefix.length() >= 3
                    && vocabulary.contains(token.substring(prefix.length()))) {
                return token.substring(prefix.length());
            }
        }
        return token;
    }
}
