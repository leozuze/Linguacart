package com.linguacart.protection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates that protected tokens in product text (prices, currencies, quantities, units,
 * model numbers, brand names, allergen and safety warnings) survive neural model translation unchanged.
 * <p>
 * If any protected token is missing, altered, duplicated, or hallucinated, the field is reverted
 * to the original English text, marked as notTranslated, and logged.
 *
 * <h3>Two Protection Layers:</h3>
 * <ul>
 *   <li><b>Layer 1:</b> The request's {@code protectedFields} map (price, quantity, unit, brand, model)
 *       is never taken from model output and is always returned verbatim as received.</li>
 *   <li><b>Layer 2:</b> Protected tokens embedded within free-text fields (title, description) are
 *       extracted from English and verified as a multiset in the translated output. Extra/hallucinated
 *       protected tokens not present in English cause immediate field reversion.</li>
 * </ul>
 *
 * <h3>Known Limitations:</h3>
 * <ul>
 *   <li><b>Worded numbers:</b> Numbers expressed in English words (e.g. "two kilograms", "five hundred grams")
 *       are not extracted as numeric quantities in v1; numeric digits are expected.</li>
 *   <li><b>Fractions:</b> Vulgar fractions (e.g. "1 ½ kg", "3/4 L") are not parsed as quantities; decimal
 *       or integer formats (e.g. "1.5 kg") are required.</li>
 *   <li><b>Unlisted brands:</b> Brands not provided in the request's {@code brand} field or present in
 *       the configured {@code knownBrands} list are treated as standard text.</li>
 *   <li><b>Unit conversion:</b> Semantic unit conversion (e.g. converting USD to ZWL or kg to lbs) is
 *       explicitly disallowed and treated as corruption.</li>
 *   <li><b>Zero-token fields:</b> Text containing no protected tokens passes validation unchanged.</li>
 * </ul>
 */
@Component
public class ProtectedFieldChecker {

    private static final Logger log = LoggerFactory.getLogger(ProtectedFieldChecker.class);

    // Multi-pack pattern: e.g., 2 x 250 ml, 2×250ml, 3 x 500g
    private static final Pattern MULTIPACK_PATTERN = Pattern.compile(
            "\\b(\\d+)\\s*([xX×])\\s*(\\d+(?:\\.\\d+)?)\\s*([a-zA-Z]+)?\\b"
    );

    // Price pattern: currency symbol or code followed by digits
    // e.g., $2.99, $1,299, ₹1,299.00, Rs. 499, Rs.499, USD 10, USD 10.50, £15.99, €49.00
    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "(?:([\\$₹£€¥]|(?:USD|EUR|GBP|INR|ZWL|Rs)\\b|Rs\\.)\\s*(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)|" +
            "(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)\\s*(?:(USD|EUR|GBP|INR|ZWL)\\b|(Rs\\.|Rs)))"
    );

    // Quantity + Unit pattern: e.g., 500 g, 500g, 1 kg, 1.5 L, 250 ml, 5 kgs
    private static final Pattern QUANTITY_UNIT_PATTERN = Pattern.compile(
            "\\b(\\d+(?:\\.\\d+)?)\\s*(kg|kgs|g|mg|ml|mL|cl|fl\\s*oz|oz|ozs|lb|lbs|l|L|cm|mm|m|km|inch|inches|ft|count|ct|pack|pk|pcs|pieces?|capsules|tablets|tabs)\\b",
            Pattern.CASE_INSENSITIVE
    );

    // Model number pattern: contains uppercase letters, hyphens, and digits (e.g. WH-1000XM5, BX-100, SM-G998B)
    private static final Pattern MODEL_NUMBER_PATTERN = Pattern.compile(
            "\\b(?=[A-Za-z0-9-]*\\d)(?=[A-Za-z0-9-]*[A-Z])[A-Z0-9]{1,10}(?:-[A-Z0-9]{1,15})+\\b"
    );

    // Extended allergen and safety phrases: milk, soy, wheat, gluten, eggs, fish, peanuts, tree nuts, sesame, sulphites
    // plus "Contains:" variants and case-insensitive English detection.
    public static final List<String> DEFAULT_SAFETY_PHRASES = List.of(
            // General safety phrases
            "Keep out of reach of children",
            "Warning:",
            "Warning",
            "For external use only",
            "Not for medicinal use",
            "Choking hazard",
            "Do not consume",
            "Hypoallergenic",
            "Flammable",
            // Allergen phrases: "Contains" and "Contains:" variants
            "Contains nuts", "Contains: nuts",
            "Contains tree nuts", "Contains: tree nuts",
            "Contains peanuts", "Contains: peanuts",
            "Contains milk", "Contains: milk",
            "Contains soy", "Contains: soy",
            "Contains soya", "Contains: soya",
            "Contains wheat", "Contains: wheat",
            "Contains gluten", "Contains: gluten",
            "Contains eggs", "Contains: eggs", "Contains egg", "Contains: egg",
            "Contains fish", "Contains: fish",
            "Contains shellfish", "Contains: shellfish",
            "Contains crustaceans", "Contains: crustaceans",
            "Contains sesame", "Contains: sesame",
            "Contains sulphites", "Contains: sulphites",
            "Contains sulfites", "Contains: sulfites",
            // "May contain" and "May contain traces of" variants
            "May contain traces of",
            "May contain nuts", "May contain peanuts",
            "May contain milk", "May contain soy",
            "May contain wheat", "May contain gluten",
            "May contain eggs", "May contain fish",
            "May contain shellfish", "May contain sesame",
            "May contain sulphites", "May contain sulfites"
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

    @Autowired
    public ProtectedFieldChecker(
            @Value("${linguacart.protection.known-brands:}") String configuredBrands,
            @Value("${linguacart.protection.safety-phrases:}") String configuredPhrases
    ) {
        Set<String> brands = new HashSet<>(DEFAULT_KNOWN_BRANDS);
        if (configuredBrands != null && !configuredBrands.isBlank()) {
            for (String b : configuredBrands.split(",")) {
                if (!b.isBlank()) brands.add(b.trim());
            }
        }

        List<String> phrases = new ArrayList<>(DEFAULT_SAFETY_PHRASES);
        if (configuredPhrases != null && !configuredPhrases.isBlank()) {
            for (String p : configuredPhrases.split(",")) {
                if (!p.isBlank()) phrases.add(p.trim());
            }
        }

        this.knownBrands = Collections.unmodifiableSet(brands);
        this.safetyPhrases = Collections.unmodifiableList(phrases);
    }

    public ProtectedFieldChecker(Set<String> knownBrands, List<String> safetyPhrases) {
        this.knownBrands = knownBrands != null ? Collections.unmodifiableSet(new HashSet<>(knownBrands)) : Set.of();
        this.safetyPhrases = safetyPhrases != null ? Collections.unmodifiableList(new ArrayList<>(safetyPhrases)) : List.of();
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

        String requestModel = sanitizedProtected.get("model");
        String requestPrice = sanitizedProtected.get("price");

        Map<String, String> resolvedFields = new LinkedHashMap<>();
        List<String> notTranslated = new ArrayList<>();
        Map<String, List<String>> failedTokensByField = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : originalFields.entrySet()) {
            String fieldName = entry.getKey();
            String originalEn = entry.getValue();
            String translated = translatedFields != null ? translatedFields.get(fieldName) : null;

            FieldCheckResult checkResult = checkField(fieldName, originalEn, translated, effectiveBrands, requestModel, requestPrice);
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
     * Validates that all protected tokens in originalEn survive inside translatedText,
     * and that translatedText contains no extra/hallucinated protected tokens (multiset verification).
     */
    public FieldCheckResult checkField(
            String fieldName,
            String originalEn,
            String translatedText,
            Set<String> brandNames,
            String literalModel,
            String literalPrice
    ) {
        if (originalEn == null) {
            return new FieldCheckResult(fieldName, translatedText, true, List.of());
        }

        // Blank or null model output must revert to original English
        if (translatedText == null || translatedText.isBlank()) {
            log.warn("Protected field check failed for field '{}': model output is blank or null", fieldName);
            return new FieldCheckResult(fieldName, originalEn, false, List.of("<blank_translation>"));
        }

        List<ExtractedToken> sourceTokens = extractProtectedTokens(originalEn, brandNames, literalModel, literalPrice);
        List<ExtractedToken> targetTokens = extractProtectedTokens(translatedText, brandNames, literalModel, literalPrice);

        // Multiset comparison: every source token must match a distinct target token,
        // and no target token may be left unmatched (extra/hallucinated).
        boolean[] targetMatched = new boolean[targetTokens.size()];
        List<String> failedTokens = new ArrayList<>();

        for (ExtractedToken s : sourceTokens) {
            boolean found = false;
            for (int j = 0; j < targetTokens.size(); j++) {
                if (!targetMatched[j] && tokensMatch(s, targetTokens.get(j))) {
                    targetMatched[j] = true;
                    found = true;
                    break;
                }
            }
            if (!found) {
                failedTokens.add(s.rawValue());
            }
        }

        // Detect extra/invented tokens in translated output
        for (int j = 0; j < targetTokens.size(); j++) {
            if (!targetMatched[j]) {
                ExtractedToken extra = targetTokens.get(j);
                failedTokens.add("+" + extra.rawValue() + " (invented)");
            }
        }

        if (!failedTokens.isEmpty()) {
            return new FieldCheckResult(fieldName, originalEn, false, Collections.unmodifiableList(failedTokens));
        }

        return new FieldCheckResult(fieldName, translatedText, true, List.of());
    }

    public FieldCheckResult checkField(String fieldName, String originalEn, String translatedText, Set<String> brandNames) {
        return checkField(fieldName, originalEn, translatedText, brandNames, null, null);
    }

    public FieldCheckResult checkField(String fieldName, String originalEn, String translatedText, String brandName) {
        Set<String> brands = new HashSet<>(knownBrands);
        if (brandName != null && !brandName.isBlank()) {
            brands.add(brandName.trim());
        }
        return checkField(fieldName, originalEn, translatedText, brands, null, null);
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

    private List<ExtractedToken> extractProtectedTokens(
            String text,
            Set<String> brandNames,
            String literalModel,
            String literalPrice
    ) {
        List<ExtractedToken> tokens = new ArrayList<>();
        List<Span> occupiedSpans = new ArrayList<>();

        // 1. Allergen & Safety phrases (case-insensitive detection in text)
        for (String phrase : safetyPhrases) {
            Pattern p = Pattern.compile("\\b" + Pattern.quote(phrase) + "\\b", Pattern.CASE_INSENSITIVE);
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

        // 2b. Literal Price from request protectedFields
        if (literalPrice != null && !literalPrice.isBlank()) {
            Pattern lpPattern = Pattern.compile(Pattern.quote(literalPrice.trim()));
            Matcher m = lpPattern.matcher(text);
            while (m.find()) {
                if (!overlaps(occupiedSpans, m.start(), m.end())) {
                    tokens.add(new ExtractedToken(TokenType.PRICE, literalPrice.trim(), literalPrice.trim()));
                    occupiedSpans.add(new Span(m.start(), m.end()));
                }
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

        // 4. Quantities + Units (e.g. 500 g, 500g, 1 kg, 1.5 L, 5 kgs)
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

        // 5b. Literal Model from request protectedFields (handles unhyphenated models e.g. "A15", "XM5")
        if (literalModel != null && !literalModel.isBlank()) {
            Pattern lmPattern = Pattern.compile("\\b" + Pattern.quote(literalModel.trim()) + "\\b");
            Matcher m = lmPattern.matcher(text);
            while (m.find()) {
                if (!overlaps(occupiedSpans, m.start(), m.end())) {
                    tokens.add(new ExtractedToken(TokenType.MODEL_NUMBER, literalModel.trim(), literalModel.trim()));
                    occupiedSpans.add(new Span(m.start(), m.end()));
                }
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

    private boolean tokensMatch(ExtractedToken s, ExtractedToken t) {
        if (s.type() != t.type()) {
            return false;
        }

        return switch (s.type()) {
            case SAFETY_ALLERGEN -> {
                // Must survive verbatim from English (whitespace normalized)
                String sNorm = normalizeWhitespace(s.rawValue());
                String tNorm = normalizeWhitespace(t.rawValue());
                yield sNorm.equals(tNorm);
            }
            case MODEL_NUMBER -> {
                // Strict exact case-sensitive match
                yield s.rawValue().equals(t.rawValue());
            }
            case BRAND -> {
                // Strict exact case-sensitive match
                yield s.rawValue().equals(t.rawValue());
            }
            case PRICE -> priceTokensMatch(s.rawValue(), t.rawValue());
            case MULTIPACK -> multipackTokensMatch(s.rawValue(), t.rawValue());
            case QUANTITY_UNIT -> quantityUnitTokensMatch(s.rawValue(), t.rawValue());
        };
    }

    private boolean priceTokensMatch(String sRaw, String tRaw) {
        Matcher sM = PRICE_PATTERN.matcher(sRaw);
        Matcher tM = PRICE_PATTERN.matcher(tRaw);
        if (!sM.matches() || !tM.matches()) {
            return sRaw.trim().equals(tRaw.trim());
        }

        String sCurr = getCurrency(sM);
        String tCurr = getCurrency(tM);
        if (!currenciesMatch(sCurr, tCurr)) {
            return false;
        }

        String sNum = getNumber(sM);
        String tNum = getNumber(tM);

        // Parse integer digits and decimal part
        String sInt = getIntegerDigits(sNum);
        String tInt = getIntegerDigits(tNum);
        String sDec = getDecimalDigits(sNum);
        String tDec = getDecimalDigits(tNum);

        // $1,299 (sDec="") cannot match $1.299 (tDec="299")
        return sInt.equals(tInt) && sDec.equals(tDec);
    }

    private String getCurrency(Matcher m) {
        String p = m.group(1);
        if (p != null) return p.trim();
        String s = m.group(4);
        if (s != null) return s.trim();
        String alt = m.group(5);
        return alt != null ? alt.trim() : "";
    }

    private String getNumber(Matcher m) {
        String n = m.group(2);
        if (n != null) return n.trim();
        String s = m.group(3);
        return s != null ? s.trim() : "";
    }

    private boolean currenciesMatch(String c1, String c2) {
        if (c1.equalsIgnoreCase(c2)) return true;
        boolean c1Rs = c1.equalsIgnoreCase("Rs.") || c1.equalsIgnoreCase("Rs");
        boolean c2Rs = c2.equalsIgnoreCase("Rs.") || c2.equalsIgnoreCase("Rs");
        return c1Rs && c2Rs;
    }

    private String getIntegerDigits(String num) {
        int dotIdx = num.indexOf('.');
        String intPart = dotIdx >= 0 ? num.substring(0, dotIdx) : num;
        return intPart.replace(",", "");
    }

    private String getDecimalDigits(String num) {
        int dotIdx = num.indexOf('.');
        return dotIdx >= 0 ? num.substring(dotIdx + 1) : "";
    }

    private boolean multipackTokensMatch(String sRaw, String tRaw) {
        Matcher sM = MULTIPACK_PATTERN.matcher(sRaw);
        Matcher tM = MULTIPACK_PATTERN.matcher(tRaw);
        if (!sM.matches() || !tM.matches()) {
            return sRaw.trim().equalsIgnoreCase(tRaw.trim());
        }

        String sCount = sM.group(1);
        String tCount = tM.group(1);
        String sQty = sM.group(3);
        String tQty = tM.group(3);
        String sUnit = sM.group(4) != null ? sM.group(4) : "";
        String tUnit = tM.group(4) != null ? tM.group(4) : "";

        return sCount.equals(tCount) && sQty.equals(tQty) && sUnit.equalsIgnoreCase(tUnit);
    }

    private boolean quantityUnitTokensMatch(String sRaw, String tRaw) {
        Matcher sM = QUANTITY_UNIT_PATTERN.matcher(sRaw);
        Matcher tM = QUANTITY_UNIT_PATTERN.matcher(tRaw);
        if (!sM.matches() || !tM.matches()) {
            return sRaw.trim().equalsIgnoreCase(tRaw.trim());
        }

        String sNum = sM.group(1);
        String tNum = tM.group(1);
        String sUnit = sM.group(2);
        String tUnit = tM.group(2);

        // Numbers must be exact (e.g. 500 == 500; 1 != 10)
        // Units must be exact case-insensitively (e.g. g == g, L == l; kg != kgs, g != mg)
        return sNum.equals(tNum) && sUnit.equalsIgnoreCase(tUnit);
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
