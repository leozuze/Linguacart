package com.linguacart.protection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NegationDetectorTest {

    @Test
    @DisplayName("the contract's required sentence is detected")
    void requiredSentence() {
        assertTrue(NegationDetector.containsNegation(
                "This item cannot be shipped to your selected delivery location. "
                        + "Please choose a different delivery location."));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Not waterproof. Runs small.",
            "Currently unavailable.",
            "Out of stock",
            "Don't miss this",
            "It isn\u2019t cheap",
            "Ships without charger",
            "Never opens",
            "No returns"
    })
    @DisplayName("negation and unavailability wording is detected")
    void detected(String text) {
        assertTrue(NegationDetector.containsNegation(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Add to cart", "Iodized table salt, 1 kg", "Free delivery", "Note this", "Nothing"})
    @DisplayName("ordinary text is not flagged")
    void notDetected(String text) {
        assertFalse(NegationDetector.containsNegation(text));
    }

    @Test
    @DisplayName("null is not flagged")
    void nullText() {
        assertFalse(NegationDetector.containsNegation(null));
    }
}
