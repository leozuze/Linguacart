package com.linguacart.protection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProtectedFieldCheckerTest {

    private final ProtectedFieldChecker checker = new ProtectedFieldChecker();

    // -------------------------------------------------------------
    // 1. Individual token types: passes when preserved, fails when corrupted
    // -------------------------------------------------------------

    @Test
    @DisplayName("Price $2.99 passes when preserved, fails when decimal comma or value altered")
    void testPriceDollar() {
        String en = "Table salt for $2.99 only";

        // Preserved
        FieldCheckResult res1 = checker.checkField("desc", en, "Munyu weTafura unoita $2.99 chete", "BrandX");
        assertTrue(res1.translated());
        assertEquals("Munyu weTafura unoita $2.99 chete", res1.finalContent());

        // Corrupted decimal: $2,99
        FieldCheckResult res2 = checker.checkField("desc", en, "Munyu weTafura unoita $2,99 chete", "BrandX");
        assertTrue(res2.isReverted());
        assertEquals(en, res2.finalContent());
        assertTrue(res2.failedTokens().contains("$2.99"));

        // Value altered: $3.00
        FieldCheckResult res3 = checker.checkField("desc", en, "Munyu weTafura unoita $3.00 chete", "BrandX");
        assertTrue(res3.isReverted());
        assertEquals(en, res3.finalContent());

        // Missing currency symbol: 2.99
        FieldCheckResult res4 = checker.checkField("desc", en, "Munyu weTafura unoita 2.99 chete", "BrandX");
        assertTrue(res4.isReverted());
        assertEquals(en, res4.finalContent());
    }

    @Test
    @DisplayName("Price ₹1,299.00 passes with or without thousands comma, fails on corruption")
    void testPriceRupee() {
        String en = "Sony headphones ₹1,299.00 today";

        // Preserved with comma
        FieldCheckResult res1 = checker.checkField("title", en, "Sony mahedhifoni ₹1,299.00 nhasi", "Sony");
        assertTrue(res1.translated());

        // Preserved without thousands separator comma
        FieldCheckResult res2 = checker.checkField("title", en, "Sony mahedhifoni ₹1299.00 nhasi", "Sony");
        assertTrue(res2.translated());

        // Corrupted cents: ₹1,299.50
        FieldCheckResult res3 = checker.checkField("title", en, "Sony mahedhifoni ₹1,299.50 nhasi", "Sony");
        assertTrue(res3.isReverted());
        assertTrue(res3.failedTokens().contains("₹1,299.00"));

        // Currency replaced: $1,299.00
        FieldCheckResult res4 = checker.checkField("title", en, "Sony mahedhifoni $1,299.00 nhasi", "Sony");
        assertTrue(res4.isReverted());
    }

    @Test
    @DisplayName("Price Rs. 499 and USD 10 pass when preserved and fail when corrupted")
    void testPriceRsAndUsd() {
        String enRs = "Special price Rs. 499 only";
        assertTrue(checker.checkField("desc", enRs, "Mutengo Rs. 499 chete", "").translated());
        assertTrue(checker.checkField("desc", enRs, "Mutengo Rs.499 chete", "").translated());
        assertTrue(checker.checkField("desc", enRs, "Mutengo Rs. 500 chete", "").isReverted());

        String enUsd = "Price is USD 10 total";
        assertTrue(checker.checkField("desc", enUsd, "Mutengo i USD 10 pamwe", "").translated());
        assertTrue(checker.checkField("desc", enUsd, "Mutengo i EUR 10 pamwe", "").isReverted());
        assertTrue(checker.checkField("desc", enUsd, "Mutengo i USD 20 pamwe", "").isReverted());
    }

    @Test
    @DisplayName("Quantity + Unit 500 g passes preserved, fails when unit or number corrupted")
    void testQuantityUnitGrams() {
        String en = "Iodized salt 500 g pouch";

        // Spaced and unspaced pass
        assertTrue(checker.checkField("title", en, "Munyu 500 g bhegi", "").translated());
        assertTrue(checker.checkField("title", en, "Munyu 500g bhegi", "").translated());

        // Unit corrupted to kg
        FieldCheckResult unitCorrupted = checker.checkField("title", en, "Munyu 500 kg bhegi", "");
        assertTrue(unitCorrupted.isReverted());
        assertEquals(en, unitCorrupted.finalContent());
        assertTrue(unitCorrupted.failedTokens().contains("500 g"));

        // Number corrupted to 600 g
        FieldCheckResult numCorrupted = checker.checkField("title", en, "Munyu 600 g bhegi", "");
        assertTrue(numCorrupted.isReverted());
        assertEquals(en, numCorrupted.finalContent());
    }

    @Test
    @DisplayName("Multi-pack 2 x 250 ml and 2×250ml pass preserved, fail when count or qty altered")
    void testMultiPack() {
        String en = "Shampoo pack 2 x 250 ml bottles";

        // Preserved with x or ×
        assertTrue(checker.checkField("title", en, "Sipo 2 x 250 ml mabhodhoro", "").translated());
        assertTrue(checker.checkField("title", en, "Sipo 2×250ml mabhodhoro", "").translated());

        // Corrupted volume: 2 x 500 ml
        FieldCheckResult volCorrupted = checker.checkField("title", en, "Sipo 2 x 500 ml mabhodhoro", "");
        assertTrue(volCorrupted.isReverted());
        assertEquals(en, volCorrupted.finalContent());

        // Corrupted count: 3 x 250 ml
        FieldCheckResult countCorrupted = checker.checkField("title", en, "Sipo 3 x 250 ml mabhodhoro", "");
        assertTrue(countCorrupted.isReverted());
        assertEquals(en, countCorrupted.finalContent());
    }

    @Test
    @DisplayName("Quantity + Unit 1.5 L passes preserved, fails when quantity or unit altered")
    void testQuantityUnitVolume() {
        String en = "Cooking oil 1.5 L bottle";
        assertTrue(checker.checkField("title", en, "Mafuta 1.5 L bhodhoro", "").translated());
        assertTrue(checker.checkField("title", en, "Mafuta 1.5L bhodhoro", "").translated());

        assertTrue(checker.checkField("title", en, "Mafuta 2.5 L bhodhoro", "").isReverted());
        assertTrue(checker.checkField("title", en, "Mafuta 1.5 ml bhodhoro", "").isReverted());
    }

    @Test
    @DisplayName("Model number WH-1000XM5 and BX-100 pass preserved, fail on case or digit change")
    void testModelNumbers() {
        String en = "Wireless Headphones WH-1000XM5 and adapter BX-100";

        // Preserved exact case
        assertTrue(checker.checkField("title", en, "Mahedhifoni WH-1000XM5 neadaputa BX-100", "").translated());

        // Corrupted model digit: WH-1000XM6
        FieldCheckResult digitCorrupted = checker.checkField("title", en, "Mahedhifoni WH-1000XM6 neadaputa BX-100", "");
        assertTrue(digitCorrupted.isReverted());
        assertTrue(digitCorrupted.failedTokens().contains("WH-1000XM5"));

        // Corrupted case: wh-1000xm5 (case-sensitive requirement)
        FieldCheckResult caseCorrupted = checker.checkField("title", en, "Mahedhifoni wh-1000xm5 neadaputa BX-100", "");
        assertTrue(caseCorrupted.isReverted());
        assertTrue(caseCorrupted.failedTokens().contains("WH-1000XM5"));

        // Corrupted second model: BX-101
        FieldCheckResult secondCorrupted = checker.checkField("title", en, "Mahedhifoni WH-1000XM5 neadaputa BX-101", "");
        assertTrue(secondCorrupted.isReverted());
        assertTrue(secondCorrupted.failedTokens().contains("BX-100"));
    }

    @Test
    @DisplayName("Brand name passes preserved, fails on case change or alteration")
    void testBrandNames() {
        String en = "Sony WH-1000XM5 premium sound";

        // Preserved brand Sony
        assertTrue(checker.checkField("title", en, "Sony WH-1000XM5 ruzha rwakanaka", "Sony").translated());

        // Replaced brand
        FieldCheckResult replaced = checker.checkField("title", en, "Panasonic WH-1000XM5 ruzha rwakanaka", "Sony");
        assertTrue(replaced.isReverted());
        assertTrue(replaced.failedTokens().contains("Sony"));

        // Lowercase brand
        FieldCheckResult lower = checker.checkField("title", en, "sony WH-1000XM5 ruzha rwakanaka", "Sony");
        assertTrue(lower.isReverted());
        assertTrue(lower.failedTokens().contains("Sony"));
    }

    @Test
    @DisplayName("Allergen phrase Contains nuts must survive verbatim; fails when translated to Shona")
    void testAllergenPhrases() {
        String en = "Delicious breakfast bar. Contains nuts and milk.";

        // Preserved verbatim
        FieldCheckResult preserved = checker.checkField("desc", en, "Chikafu chemangwanani chinonaka. Contains nuts and milk.", "");
        assertTrue(preserved.translated());

        // Translated to Shona: "Ina nzungu"
        FieldCheckResult translated = checker.checkField("desc", en, "Chikafu chemangwanani chinonaka. Ina nzungu nemukaka.", "");
        assertTrue(translated.isReverted());
        assertEquals(en, translated.finalContent());
        assertTrue(translated.failedTokens().contains("Contains nuts"));
    }

    @Test
    @DisplayName("Safety warnings survive verbatim; fails when translated or missing")
    void testSafetyWarnings() {
        String en = "Warning: Keep out of reach of children. Choking hazard.";

        // Verbatim
        assertTrue(checker.checkField("desc", en, "Warning: Keep out of reach of children. Choking hazard.", "").translated());

        // Translated to Shona
        FieldCheckResult shona = checker.checkField("desc", en, "Yambiro: Chengetedza kure nevana vadiki.", "");
        assertTrue(shona.isReverted());
        assertEquals(en, shona.finalContent());
    }

    // -------------------------------------------------------------
    // 2. Multiple tokens in one field: single corruption causes reversion
    // -------------------------------------------------------------

    @Test
    @DisplayName("Field with several tokens where only one is corrupted reverts completely to English")
    void testMultipleTokensSingleCorruption() {
        String en = "BrandX BX-100 Noise Cancelling Headphones, 1 kg, $2.99. Warning: Contains nuts.";

        // Only price is corrupted: $2.99 -> $2,99
        String corruptedOutput = "BrandX BX-100 Mahedhifoni, 1 kg, $2,99. Warning: Contains nuts.";

        FieldCheckResult result = checker.checkField("title", en, corruptedOutput, "BrandX");

        assertTrue(result.isReverted());
        assertEquals(en, result.finalContent());
        assertEquals(List.of("$2.99"), result.failedTokens());
    }

    // -------------------------------------------------------------
    // 3. Blank or null model output
    // -------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(value = {
            "null",
            "''",
            "'   '",
            "'\t\n'"
    }, nullValues = {"null"})
    @DisplayName("Blank or null model output immediately reverts to original English")
    void testBlankOrNullModelOutput(String blankOutput) {
        String en = "Organic table salt, 1 kg, $2.99";
        FieldCheckResult result = checker.checkField("title", en, blankOutput, "BrandX");

        assertTrue(result.isReverted());
        assertEquals(en, result.finalContent());
        assertTrue(result.failedTokens().contains("<blank_translation>"));
    }

    // -------------------------------------------------------------
    // 4. Decisions on lookalike and similar tokens
    // -------------------------------------------------------------

    @Test
    @DisplayName("Similar tokens: unit spacing 1 kg vs 1kg is accepted because factual quantity and unit are preserved")
    void testSimilarTokens_unitSpacing_1kgVs1Kg_acceptedBecauseFactualQuantityAndUnitPreserved() {
        String en = "Table salt, 1 kg pouch";
        String sn = "Munyu weTafura, 1kg bhegi";

        FieldCheckResult result = checker.checkField("title", en, sn, "");
        assertTrue(result.translated(), "1 kg vs 1kg must be accepted as equivalent factual unit representation");
        assertEquals(sn, result.finalContent());
    }

    @Test
    @DisplayName("Similar tokens: lookalike characters WH-1000XM5 vs WH-1000XMS are rejected because model altered")
    void testSimilarTokens_lookalikeCharacters_WH1000XM5VsWH1000XMS_rejectedBecauseModelAltered() {
        String en = "Headphones WH-1000XM5 black";
        String sn = "Mahedhifoni WH-1000XMS matema";

        FieldCheckResult result = checker.checkField("title", en, sn, "");
        assertTrue(result.isReverted(), "Lookalike character 5 vs S must be rejected");
        assertEquals(en, result.finalContent());
    }

    @Test
    @DisplayName("Similar tokens: quantity altered 1 kg vs 10 kg is rejected because quantity value changed")
    void testSimilarTokens_quantityAltered_1kgVs10kg_rejectedBecauseQuantityValueChanged() {
        String en = "Basmati rice, 1 kg bag";
        String sn = "Mupunga, 10 kg bhegi";

        FieldCheckResult result = checker.checkField("title", en, sn, "");
        assertTrue(result.isReverted(), "1 kg must never match 10 kg");
        assertEquals(en, result.finalContent());
    }

    @Test
    @DisplayName("Similar tokens: price punctuation $2.99 vs $2,99 is rejected because decimal separator corrupted")
    void testSimilarTokens_pricePunctuation_2_99Vs2Comma99_rejectedBecauseDecimalSeparatorCorrupted() {
        String en = "Toothpaste $2.99";
        String sn = "Mshonga wemazino $2,99";

        FieldCheckResult result = checker.checkField("desc", en, sn, "");
        assertTrue(result.isReverted(), "$2.99 must never match $2,99");
        assertEquals(en, result.finalContent());
    }

    // -------------------------------------------------------------
    // 5. Field with no protected tokens
    // -------------------------------------------------------------

    @Test
    @DisplayName("Field with no protected tokens passes untouched")
    void testFieldWithNoProtectedTokens() {
        String en = "Delicious fresh natural breakfast meal";
        String sn = "Chikafu chemangwanani chakanaka uye chitsva";

        FieldCheckResult result = checker.checkField("description", en, sn, "");
        assertTrue(result.translated());
        assertEquals(sn, result.finalContent());
        assertTrue(result.failedTokens().isEmpty());
    }

    // -------------------------------------------------------------
    // 6. Layer 1: protectedFields map returned exactly as sent
    // -------------------------------------------------------------

    @Test
    @DisplayName("Layer 1: protectedFields map values are returned as sent even if model output differs")
    void testProtectedFieldsMapReturnedExactlyAsSent() {
        Map<String, String> originalFields = Map.of(
                "title", "BrandX BX-100 Salt 500 g $2.99",
                "description", "Delicious table salt."
        );

        // Simulated translated output where title passes but model attempted to fabricate protected fields
        Map<String, String> translatedFields = Map.of(
                "title", "BrandX BX-100 Munyu 500 g $2.99",
                "description", "Munyu weTafura unonaka."
        );

        Map<String, String> requestProtected = Map.of(
                "price", "$2.99",
                "quantity", "1",
                "unit", "kg",
                "brand", "BrandX",
                "model", "BX-100"
        );

        ProtectionResult result = checker.verifyTranslation(originalFields, translatedFields, requestProtected);

        assertFalse(result.hasReversions());
        assertTrue(result.notTranslated().isEmpty());

        // Verify protectedFields map is exactly what was sent
        assertEquals(requestProtected, result.protectedFields());
        assertEquals("$2.99", result.protectedFields().get("price"));
        assertEquals("1", result.protectedFields().get("quantity"));
        assertEquals("kg", result.protectedFields().get("unit"));
        assertEquals("BrandX", result.protectedFields().get("brand"));
        assertEquals("BX-100", result.protectedFields().get("model"));

        // Verify title is translated
        assertEquals("BrandX BX-100 Munyu 500 g $2.99", result.resolvedFields().get("title"));
    }

    @Test
    @DisplayName("verifyTranslation reverts only the failing field and keeps valid fields translated")
    void testVerifyTranslationPerFieldReversion() {
        Map<String, String> originalFields = Map.of(
                "title", "BrandX Salt 500 g $2.99",
                "description", "Healthy table salt with no additives."
        );

        // Title has corrupted unit (500 g -> 500 kg), description is valid
        Map<String, String> translatedFields = Map.of(
                "title", "BrandX Munyu 500 kg $2.99",
                "description", "Munyu weTafura une hutano usina zvinowedzerwa."
        );

        Map<String, String> requestProtected = Map.of("brand", "BrandX");

        ProtectionResult result = checker.verifyTranslation(originalFields, translatedFields, requestProtected);

        assertTrue(result.hasReversions());
        assertEquals(List.of("title"), result.notTranslated());

        // Title reverted to original English
        assertEquals("BrandX Salt 500 g $2.99", result.resolvedFields().get("title"));

        // Description preserved translated
        assertEquals("Munyu weTafura une hutano usina zvinowedzerwa.", result.resolvedFields().get("description"));
    }
}
