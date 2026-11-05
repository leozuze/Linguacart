package com.linguacart.protection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the shared masking cases (tests/shared/mask_cases.json, written by the extension developer)
 * against Java's protection layer. Java does not mask; instead it must DETECT any change to the
 * protected values listed in each case. For every value we (1) change its last digit and (2) delete it,
 * and require that either {@link SegmentGuard} or {@link ProtectedFieldChecker} rejects the output.
 * The unchanged input must pass both.
 */
class MaskCasesTest {

    private final ProtectedFieldChecker checker = new ProtectedFieldChecker();

    private boolean rejected(String input, String output) {
        boolean guard = !SegmentGuard.check(input, output).isEmpty();
        boolean checked = checker.verifyTranslation(Map.of("s", input), Map.of("s", output), Map.of()).hasReversions();
        return guard || checked;
    }

    @Test
    @DisplayName("every protected value in tests/shared/mask_cases.json is guarded")
    void everyProtectedValueIsGuarded() throws Exception {
        Path file = Path.of("..", "tests", "shared", "mask_cases.json");
        assertTrue(Files.exists(file), "tests/shared/mask_cases.json not found: merge the extension branch");

        JsonNode cases = new ObjectMapper().readTree(file.toFile());
        List<String> problems = new ArrayList<>();

        for (JsonNode c : cases) {
            String name = c.get("name").asText();
            String input = c.get("input").asText();

            if (rejected(input, input)) {
                problems.add("unchanged input was rejected: " + name);
            }
            if (c.path("roundTripOnly").asBoolean(false)) {
                continue;
            }

            Iterator<Map.Entry<String, JsonNode>> labels = c.path("values").fields();
            while (labels.hasNext()) {
                Map.Entry<String, JsonNode> entry = labels.next();
                String label = entry.getKey();
                if (label.equals("QUERY")) {
                    continue; // the shopper's own search text has no digits to corrupt
                }
                for (JsonNode valueNode : entry.getValue()) {
                    String value = valueNode.asText();
                    String changed = changeLastDigit(value);
                    if (changed != null) {
                        String out = input.replaceFirst(Pattern.quote(value), Matcher.quoteReplacement(changed));
                        if (!rejected(input, out)) {
                            problems.add("changed " + label + " '" + value + "' not detected: " + name);
                        }
                    }
                    String removed = input.replaceFirst(Pattern.quote(value), "");
                    if (!rejected(input, removed)) {
                        problems.add("removed " + label + " '" + value + "' not detected: " + name);
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), "Protection gaps found:\n" + String.join("\n", problems));
    }

    private static String changeLastDigit(String value) {
        for (int i = value.length() - 1; i >= 0; i--) {
            char d = value.charAt(i);
            if (Character.isDigit(d)) {
                char changed = d == '9' ? '8' : (char) (d + 1);
                return value.substring(0, i) + changed + value.substring(i + 1);
            }
        }
        return null;
    }
}
