package com.linguacart.protection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure check that a translated page segment keeps the English segment's numbers, prices, dates and counts.
 * It complements {@link ProtectedFieldChecker} (units, model numbers, brands, allergen text) and enforces
 * the v0.3 contract rule "every protected value appears unchanged and no extra numbers are introduced".
 *
 * <p>All comparisons are multisets: a value that appears twice in English must appear twice in the output.
 *
 * <p>Known limits (v1):
 * <ul>
 *   <li>Numbers must survive as digits. A Shona number word ("mashanu") counts as a change and the segment
 *       stays English. This can be relaxed once a Shona speaker confirms a numeral table.</li>
 *   <li>Whitespace inside a price, date or count is ignored ("$ 50" equals "$50"). Thousands commas are
 *       ignored for plain numbers only.</li>
 *   <li>Failure descriptions contain tokens only, never the full segment text.</li>
 * </ul>
 */
public final class SegmentGuard {

    /** 1 | 1,092 | 1,092.75 | 4.3 (a comma followed by a space ends the number). */
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:,\\d{3})*(?:\\.\\d+)?");

    /** Currency symbol or code followed by an amount: $2.99, INR 1,092.75, INR133,185.25, ZiG 500. */
    private static final Pattern PRICE = Pattern.compile(
            "(?:[\u20B9$\u00A3\u20AC\u00A5]|(?<![A-Za-z])(?:INR|USD|EUR|GBP|ZWL|ZiG|Rs)\\.?)"
                    + "\\s?\\d+(?:,\\d{3})*(?:\\.\\d+)?");

    private static final String MONTH =
            "(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|June?|July?|Aug(?:ust)?"
                    + "|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)";

    /** Fri, Nov 13 | Nov 13 - 15 | November 3 */
    private static final Pattern DATE = Pattern.compile(
            "\\b(?:(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)[a-z]{0,6}\\.?,?\\s)?" + MONTH + "\\.?\\s\\d{1,2}"
                    + "(?:\\s?[-\u2013]\\s?\\d{1,2})?\\b");

    /** 17.1K | 10K+ | 2M */
    private static final Pattern COUNT = Pattern.compile(
            "(?<![A-Za-z0-9])\\d[\\d,.]*[KkMm]\\+?(?![A-Za-z])");

    private SegmentGuard() {}

    /**
     * @return an empty list when the output keeps every protected value, otherwise short descriptions
     *         such as {@code "number missing: 13"} or {@code "price invented: $5.00"}
     */
    public static List<String> check(String source, String output) {
        List<String> failures = new ArrayList<>();
        if (source == null || source.isBlank()) {
            return failures;
        }
        String out = output == null ? "" : output;

        compare("price", tokens(PRICE, source, true, false), tokens(PRICE, out, true, false), failures);
        compare("date", tokens(DATE, source, true, false), tokens(DATE, out, true, false), failures);
        compare("count", tokens(COUNT, source, true, false), tokens(COUNT, out, true, false), failures);
        compare("number", tokens(NUMBER, source, false, true), tokens(NUMBER, out, false, true), failures);
        return failures;
    }

    private static List<String> tokens(Pattern pattern, String text, boolean stripSpaces, boolean stripCommas) {
        List<String> found = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            String token = m.group();
            if (stripSpaces) {
                token = token.replaceAll("\\s+", "");
            }
            if (stripCommas) {
                token = token.replace(",", "");
            }
            found.add(token);
        }
        return found;
    }

    private static void compare(String label, List<String> source, List<String> output, List<String> failures) {
        Map<String, Integer> balance = new TreeMap<>();
        for (String s : source) {
            balance.merge(s, 1, Integer::sum);
        }
        for (String o : output) {
            balance.merge(o, -1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : balance.entrySet()) {
            if (e.getValue() > 0) {
                failures.add(label + " missing: " + e.getKey());
            } else if (e.getValue() < 0) {
                failures.add(label + " invented: " + e.getKey());
            }
        }
    }
}
