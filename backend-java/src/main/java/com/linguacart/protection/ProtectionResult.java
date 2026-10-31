package com.linguacart.protection;

import java.util.List;
import java.util.Map;

/**
 * Result of validating all fields in a translation request.
 *
 * @param resolvedFields map of field names to final text (translated or reverted)
 * @param protectedFields exact original protected fields map, never modified
 * @param notTranslated list of field names that failed validation and were reverted
 * @param failedTokensByField map of field name to list of tokens that failed validation
 */
public record ProtectionResult(
        Map<String, String> resolvedFields,
        Map<String, String> protectedFields,
        List<String> notTranslated,
        Map<String, List<String>> failedTokensByField
) {
    public boolean hasReversions() {
        return notTranslated != null && !notTranslated.isEmpty();
    }
}
