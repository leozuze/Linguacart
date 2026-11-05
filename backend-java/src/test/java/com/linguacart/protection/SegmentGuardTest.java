package com.linguacart.protection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentGuardTest {

    private static boolean passes(String source, String output) {
        return SegmentGuard.check(source, output).isEmpty();
    }

    @Test
    @DisplayName("identical text and text with a translated prefix pass")
    void unchangedPasses() {
        assertTrue(passes("INR 1,111.97 delivery Fri, Nov 13", "INR 1,111.97 delivery Fri, Nov 13"));
        assertTrue(passes("INR 1,111.97 delivery Fri, Nov 13", "[sn] INR 1,111.97 delivery Fri, Nov 13"));
    }

    @Test
    @DisplayName("a changed digit in a price fails")
    void priceDigitChanged() {
        assertFalse(passes("INR 1,111.97 delivery", "INR 1,111.98 delivery"));
    }

    @Test
    @DisplayName("INR price with no space is protected, and a change fails")
    void inrWithoutSpace() {
        assertTrue(passes("INR133,185.25", "[sn] INR133,185.25"));
        assertFalse(passes("INR133,185.25", "INR133,185.26"));
    }

    @Test
    @DisplayName("$1,299 turning into $1.299 fails (thousand-fold change)")
    void commaBecomesDot() {
        assertFalse(passes("$1,299", "$1.299"));
    }

    @Test
    @DisplayName("an invented number fails")
    void inventedNumber() {
        assertFalse(passes("Free delivery", "Free delivery 5"));
    }

    @Test
    @DisplayName("a dropped number fails")
    void droppedNumber() {
        assertFalse(passes("Only 3 left in stock", "Only left in stock"));
    }

    @Test
    @DisplayName("numbers are compared as a multiset: a dropped repeat fails")
    void droppedRepeat() {
        assertFalse(passes("Pack of 3, 4 or 5", "Pack of 3, 4"));
    }

    @Test
    @DisplayName("a changed or translated date fails")
    void dateChanged() {
        assertFalse(passes("delivery Fri, Nov 13", "delivery Fri, Dec 13"));
        assertFalse(passes("delivery Fri, Nov 13", "kutumira Chishanu, 13"));
    }

    @Test
    @DisplayName("'Market 5 stars' is not mistaken for a date")
    void marketIsNotADate() {
        assertTrue(passes("Market 5 stars", "Msika 5 stars"));
    }

    @Test
    @DisplayName("counts such as 17.1K and 10K+ must survive exactly")
    void counts() {
        assertFalse(passes("(17.1K)", "(17.2K)"));
        assertFalse(passes("10K+ bought", "10 bought"));
        assertTrue(passes("(17.1K)", "(17.1K)"));
    }

    @Test
    @DisplayName("whitespace inside a price and thousands commas in plain numbers are tolerated")
    void tolerated() {
        assertTrue(passes("$50", "$ 50"));
        assertTrue(passes("over 90,000 results", "over 90000 results"));
    }

    @Test
    @DisplayName("a Shona number word counts as a change (documented v1 limit)")
    void numberWordCountsAsChange() {
        assertFalse(passes("Only 3 left", "Dzasara dzitatu"));
    }

    @Test
    @DisplayName("null output fails when the source has numbers; null source passes")
    void nulls() {
        assertFalse(passes("Only 3 left", null));
        assertTrue(passes(null, "anything"));
    }
}
