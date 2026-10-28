package com.linguacart.protection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates that protected tokens in product text (prices, currencies, quantities, units,
 * model numbers, brand names, allergen and safety warnings) survive neural model translation unchanged.
 * <p>
 * If any protected token is missing, altered, or corrupted, the field is reverted to the original
 * English text, marked as notTranslated, and logged.
 */
@Component
public class ProtectedFieldChecker {

    private static final Logger log = LoggerFactory.getLogger(ProtectedFieldChecker.class);

    // Multi-pack pattern: e.g., 2 x 250 ml, 2×250ml, 3 x 500g
    private static final Pattern MULTIPACK_PATTERN = Pattern.compile(
            "\\b(\\d+)\\s*([xX×])\\s*(\\d+(?:\\.\\d+)?)\\s*([a-zA-Z]+)?\\b"
    );

    // Price pattern: currency symbol or code followed by digits
    // e.g., $2.99, ₹1,299.00, Rs. 499, Rs.499, USD 10, USD 10.50, £15.99, €49.00
    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "(?:([\\$₹£€¥]|(?:USD|EUR|GBP|INR|ZWL|Rs)\\b|Rs\\.)\\s*(\\d{1,3}(?:,\\d{3})*(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)|" +
            "(\\d+(?:\\.\\d{1,2})?)\\s*(?:(USD|EUR|GBP|INR|ZWL)\\b|(Rs\\.|Rs)))"
    );

    // Quantity + Unit pattern: e.g., 500 g, 500g, 1 kg, 1.5 L, 250 ml
    private static final Pattern QUANTITY_UNIT_PATTERN = Pattern.compile(
            "\\b(\\d+(?:\\.\\d+)?)\\s*(kg|g|mg|ml|mL|cl|fl\\s*oz|oz|lb|lbs|l|L|cm|mm|m|km|inch|inches|ft|count|ct|pack|pk|pcs|pieces?|capsules|tablets|tabs)\\b",
            Pattern.CASE_INSENSITIVE
    );

    // Model number pattern: contains uppercase letters, hyphens, and digits (e.g. WH-1000XM5, BX-100, SM-G998B)
    // Negative/positive lookaheads ensure words like 'gluten-free' or 'eco-friendly' are not treated as model numbers.
    private static final Pattern MODEL_NUMBER_PATTERN = Pattern.compile(
            "\\b(?=[A-Za-z0-9-]*\\d)(?=[A-Za-z0-9-]*[A-Z])[A-Z0-9]{1,10}(?:-[A-Z0-9]{1,15})+\\b"
    );

    // Default allergen and safety phrases requiring verbatim survival
    public static final List<String> DEFAULT_SAFETY_PHRASES = List.of(
            "Contains nuts",
            "May contain traces of",
            "Keep out of reach of children",
            "Warning:",
            "Warning",
            "For external use only",
            "Not for medicinal use",
            "Choking hazard",
            "Do not consume",
            "Contains milk",
            "Contains peanuts",
            "Contains soy",
            "Contains gluten",
            "Contains eggs",
            "Contains wheat",
            "Contains fish",
            "Contains shellfish",
            "Contains tree nuts"
    );

    // Default known brand names
    public static final Set<String> DEFAULT_KNOWN_BRANDS = Set.of(
            "Sony", "Apple", "Samsung", "Dell", "HP", "Lenovo", "Logitech",
            "Philips", "BrandX", "Anker", "Bose", "Sennheiser", "Canon", "Nikon",
            "LG", "Asus", "Acer", "Nike", "Adidas", "Puma"
    );

    private final Set<String> knownBrands;
    private final List<String> safetyPhrases;

    public ProtectedFieldChecker() {
        this(DEFAULT_KNOWN_BRANDS, DEFAULT_SAFETY_PHRASES);
    }

    public ProtectedFieldChecker(Set<String> knownBrands, List<String> safetyPhrases) {
        this.knownBrands = knownBrands != null ? new HashSet<>(knownBrands) : new HashSet<>();
        this.safetyPhrases = safetyPhrases != null ? new ArrayList<>(safetyPhrases) : new ArrayList<>();
    }

    /**
     * Checks all translated fields against the English original fields and enforces Layer 1 protectedFields.
     */
    public ProtectionResult verifyTranslation(
            Map<String, String> originalFields,
            Map<String, String> translatedFields,
            Map<String, String> protectedFields
    ) {
        Map<String, String> sanitizedProtected = sanitizeProtectedFields(protectedFields);

        if (originalFields == null || originalFields.isEmpty()) {
            return new ProtectionResult(Map.of(), sanitizedProtected, List.of(), Map.of());
        }

        Set<String> effectiveBrands = new HashSet<>(knownBrands);
        String requestBrand = sanitizedProtected.get("brand");
        if (requestBrand != null && !requestBrand.isBlank()) {
            effectiveBrands.add(requestBrand.trim());
        }

        Map<String, String> resolvedFields = new LinkedHashMap<>();
        List<String> notTranslated = new ArrayList<>();
        Map<String, List<String>> failedTokensByField = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : originalFields.entrySet()) {
            String fieldName = entry.getKey();
            String originalEn = entry.getValue();
            String translated = translatedFields != null ? translatedFields.get(fieldName) : null;

            FieldCheckResult checkResult = checkField(fieldName, originalEn, translated, effectiveBrands);
            resolvedFields.put(fieldName, checkResult.finalContent());

            if (checkResult.isReverted()) {
                notTranslated.add(fieldName);
                failedTokensByField.put(fieldName, checkResult.failedTokens());
                for (String failedToken : checkResult.failedTokens()) {
                    log.warn("Protected field check failed for field '{}': missing or altered token '{}'",
                            fieldName, failedToken);
                }
            }
        }

        return new ProtectionResult(
                Collections.unmodifiableMap(resolvedFields),
                sanitizedProtected,
                Collections.unmodifiableList(notTranslated),
                Collections.unmodifiableMap(failedTokensByField)
        );
    }

    /**
     * Validates that all protected tokens in originalEn survive inside translatedText.
     */
    public FieldCheckResult checkField(
            String fieldName,
            String originalEn,
            String translatedText,
            Set<String> brandNames
    ) {
        if (originalEn == null) {
            return new FieldCheckResult(fieldName, translatedText, true, List.of());
        }

        // Blank or null model output must revert to original English
        if (translatedText == null || translatedText.isBlank()) {
            log.warn("Protected field check failed for field '{}': model output is blank or null", fieldName);
            return new FieldCheckResult(fieldName, originalEn, false, List.of("<blank_translation>"));
        }

        List<ExtractedToken> tokens = extractProtectedTokens(originalEn, brandNames);
        if (tokens.isEmpty()) {
            return new FieldCheckResult(fieldName, translatedText, true, List.of());
        }

        List<String> failedTokens = new ArrayList<>();
        String normalizedTranslated = normalizeWhitespace(translatedText);

        for (ExtractedToken token : tokens) {
            if (!verifyTokenSurvives(token, translatedText, normalizedTranslated)) {
                failedTokens.add(token.rawValue());
            }
        }

        if (!failedTokens.isEmpty()) {
            return new FieldCheckResult(fieldName, originalEn, false, Collections.unmodifiableList(failedTokens));
        }

        return new FieldCheckResult(fieldName, translatedText, true, List.of());
    }

    public FieldCheckResult checkField(String fieldName, String originalEn, String translatedText, String brandName) {
        Set<String> brands = new HashSet<>(knownBrands);
        if (brandName != null && !brandName.isBlank()) {
            brands.add(brandName.trim());
        }
        return checkField(fieldName, originalEn, translatedText, brands);
    }

    /**
     * Layer 1: Returns a defensive, immutable copy of request protectedFields.
     */
    public Map<String, String> sanitizeProtectedFields(Map<String, String> requestProtectedFields) {
        if (requestProtectedFields == null || requestProtectedFields.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(requestProtectedFields));
    }

    private List<ExtractedToken> extractProtectedTokens(String text, Set<String> brandNames) {
        List<ExtractedToken> tokens = new ArrayList<>();
        List<Span> occupiedSpans = new ArrayList<>();

        // 1. Allergen & Safety phrases
        for (String phrase : safetyPhrases) {
            Pattern p = Pattern.compile("\\b" + Pattern.quote(phrase), Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(text);
            while (m.find()) {
                if (!overlaps(occupiedSpans, m.start(), m.end())) {
                    String matchedText = text.substring(m.start(), m.end());
                    tokens.add(new ExtractedToken(TokenType.SAFETY_ALLERGEN, matchedText, phrase));
                    occupiedSpans.add(new Span(m.start(), m.end()));
                }
            }
        }

        // 2. Prices
        Matcher priceMatcher = PRICE_PATTERN.matcher(text);
        while (priceMatcher.find()) {
            if (!overlaps(occupiedSpans, priceMatcher.start(), priceMatcher.end())) {
                String raw = priceMatcher.group();
                tokens.add(new ExtractedToken(TokenType.PRICE, raw, raw));
                occupiedSpans.add(new Span(priceMatcher.start(), priceMatcher.end()));
            }
        }

        // 3. Multi-pack quantities (e.g. 2 x 250 ml, 2×250ml)
        Matcher multiMatcher = MULTIPACK_PATTERN.matcher(text);
        while (multiMatcher.find()) {
            if (!overlaps(occupiedSpans, multiMatcher.start(), multiMatcher.end())) {
                String raw = multiMatcher.group();
                tokens.add(new ExtractedToken(TokenType.MULTIPACK, raw, raw));
                occupiedSpans.add(new Span(multiMatcher.start(), multiMatcher.end()));
            }
        }

        // 4. Quantities + Units (e.g. 500 g, 500g, 1 kg, 1.5 L)
        Matcher qtyMatcher = QUANTITY_UNIT_PATTERN.matcher(text);
        while (qtyMatcher.find()) {
            if (!overlaps(occupiedSpans, qtyMatcher.start(), qtyMatcher.end())) {
                String raw = qtyMatcher.group();
                tokens.add(new ExtractedToken(TokenType.QUANTITY_UNIT, raw, raw));
                occupiedSpans.add(new Span(qtyMatcher.start(), qtyMatcher.end()));
            }
        }

        // 5. Model numbers (e.g. WH-1000XM5, BX-100)
        Matcher modelMatcher = MODEL_NUMBER_PATTERN.matcher(text);
        while (modelMatcher.find()) {
            if (!overlaps(occupiedSpans, modelMatcher.start(), modelMatcher.end())) {
                String raw = modelMatcher.group();
                tokens.add(new ExtractedToken(TokenType.MODEL_NUMBER, raw, raw));
                occupiedSpans.add(new Span(modelMatcher.start(), modelMatcher.end()));
            }
        }

        // 6. Brand names
        if (brandNames != null) {
            for (String brand : brandNames) {
                if (brand == null || brand.isBlank()) continue;
                Pattern brandPattern = Pattern.compile("\\b" + Pattern.quote(brand) + "\\b");
                Matcher m = brandPattern.matcher(text);
                while (m.find()) {
                    if (!overlaps(occupiedSpans, m.start(), m.end())) {
                        tokens.add(new ExtractedToken(TokenType.BRAND, brand, brand));
                        occupiedSpans.add(new Span(m.start(), m.end()));
                    }
                }
            }
        }

        return tokens;
    }

    private boolean verifyTokenSurvives(ExtractedToken token, String rawTranslated, String normalizedTranslated) {
        return switch (token.type()) {
            case SAFETY_ALLERGEN -> {
                // Must keep English safety phrase verbatim (whitespace normalized)
                String normalizedPhrase = normalizeWhitespace(token.normalizedValue());
                yield normalizedTranslated.contains(normalizedPhrase);
            }
            case MODEL_NUMBER -> {
                // Strict exact case-sensitive whole-word match
                Pattern pattern = Pattern.compile("\\b" + Pattern.quote(token.rawValue()) + "\\b");
                yield pattern.matcher(rawTranslated).find();
            }
            case BRAND -> {
                // Strict exact case-sensitive whole-word match
                Pattern pattern = Pattern.compile("\\b" + Pattern.quote(token.rawValue()) + "\\b");
                yield pattern.matcher(rawTranslated).find();
            }
            case PRICE -> verifyPriceSurvives(token.rawValue(), rawTranslated);
            case MULTIPACK -> verifyMultipackSurvives(token.rawValue(), rawTranslated);
            case QUANTITY_UNIT -> verifyQuantityUnitSurvives(token.rawValue(), rawTranslated);
        };
    }

    private boolean verifyPriceSurvives(String rawPrice, String text) {
        // Parse raw price
        Matcher m = PRICE_PATTERN.matcher(rawPrice);
        if (!m.matches()) {
            return text.contains(rawPrice);
        }

        String currPrefix = m.group(1);
        String num = m.group(2);
        String numSuffix = m.group(3);
        String currSuffix = m.group(4);

        if (currPrefix != null && num != null) {
            String currency = currPrefix.trim();
            String currencyPattern;
            if (currency.equalsIgnoreCase("Rs.") || currency.equalsIgnoreCase("Rs")) {
                currencyPattern = "(?:Rs\\.|Rs)";
            } else {
                currencyPattern = Pattern.quote(currency);
            }
            // Thousands comma is optional in translation, but decimal point and digits must be exact
            String numWithComma = num;
            String numWithoutComma = num.replace(",", "");
            String regex = currencyPattern + "\\s*(?:" + Pattern.quote(numWithComma) + "|" + Pattern.quote(numWithoutComma) + ")(?!\\d|\\.\\d)";
            return Pattern.compile(regex).matcher(text).find();
        } else if (numSuffix != null && currSuffix != null) {
            String currency = currSuffix.trim();
            String currencyPattern;
            if (currency.equalsIgnoreCase("Rs.") || currency.equalsIgnoreCase("Rs")) {
                currencyPattern = "(?:Rs\\.|Rs)";
            } else {
                currencyPattern = "\\b" + Pattern.quote(currency) + "\\b";
            }
            String numWithComma = numSuffix;
            String numWithoutComma = numSuffix.replace(",", "");
            String regex = "(?<!\\d|\\.)(?:" + Pattern.quote(numWithComma) + "|" + Pattern.quote(numWithoutComma) + ")\\s*" + currencyPattern;
            return Pattern.compile(regex).matcher(text).find();
        }

        return text.contains(rawPrice);
    }

    private boolean verifyMultipackSurvives(String rawMultipack, String text) {
        Matcher m = MULTIPACK_PATTERN.matcher(rawMultipack);
        if (!m.matches()) {
            return text.contains(rawMultipack);
        }

        String count = m.group(1);
        String qty = m.group(3);
        String unit = m.group(4);

        String unitPart = (unit != null && !unit.isEmpty())
                ? "\\s*" + Pattern.quote(unit) + "\\b"
                : "";

        // Accepts 2 x 250 ml, 2×250ml, 2x250ml
        String regex = "\\b" + Pattern.quote(count) + "\\s*[xX×]\\s*" + Pattern.quote(qty) + unitPart;
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    private boolean verifyQuantityUnitSurvives(String rawQtyUnit, String text) {
        Matcher m = QUANTITY_UNIT_PATTERN.matcher(rawQtyUnit);
        if (!m.matches()) {
            return text.contains(rawQtyUnit);
        }

        String number = m.group(1);
        String unit = m.group(2);

        // Allows space or no space between number and unit: 500 g or 500g; 1 kg or 1kg
        // Negative lookbehind ensures '1 kg' does not match inside '10 kg' or '11kg'
        String regex = "(?<!\\d|\\.)" + Pattern.quote(number) + "\\s*" + Pattern.quote(unit) + "\\b";
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    private static String normalizeWhitespace(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\\s\\u00A0\\u202F]+", " ").trim();
    }

    private static boolean overlaps(List<Span> spans, int start, int end) {
        for (Span s : spans) {
            if (Math.max(s.start(), start) < Math.min(s.end(), end)) {
                return true;
            }
        }
        return false;
    }

    private record Span(int start, int end) {}

    private record ExtractedToken(TokenType type, String rawValue, String normalizedValue) {}

    private enum TokenType {
        SAFETY_ALLERGEN,
        PRICE,
        MULTIPACK,
        QUANTITY_UNIT,
        MODEL_NUMBER,
        BRAND
    }
}
