package com.linguacart.protection;

import java.util.regex.Pattern;

/**
 * Detects negation and unavailability wording in English text
 * (docs/07-API-CONTRACT.md, section "Negation and unavailability").
 *
 * <p>Java cannot yet verify that a Shona translation keeps a negation, so the segment service keeps
 * such segments in English (reason {@code negation_mismatch}) until a Shona speaker supplies verified
 * negation markers. This is deliberately over-cautious: "No. 1 seller" also matches.
 */
public final class NegationDetector {

    private static final Pattern CUE = Pattern.compile(
            "\\b(?:not|no|never|cannot|without|unavailable|out of stock)\\b|\\w+n['\u2019]t\\b",
            Pattern.CASE_INSENSITIVE);

    private NegationDetector() {}

    public static boolean containsNegation(String text) {
        return text != null && CUE.matcher(text).find();
    }
}
