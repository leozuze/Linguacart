package com.linguacart.protection;

import java.util.List;

/**
 * Result of validating protected tokens for a single field.
 *
 * @param fieldName the name of the field (e.g. "title", "description")
 * @param finalContent the resulting text (translated if valid, reverted to English if corrupted)
 * @param translated true if translation passed validation; false if reverted to English
 * @param failedTokens list of protected tokens that failed validation (empty if passed)
 */
public record FieldCheckResult(
        String fieldName,
        String finalContent,
        boolean translated,
        List<String> failedTokens
) {
    public boolean isReverted() {
        return !translated;
    }
}
