package com.linguacart.glossary;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class GlossaryServiceTest {

    private GlossaryService glossaryService;

    @BeforeEach
    void setUp() {
        glossaryService = new GlossaryService();
        glossaryService.initialize();
    }

    @Test
    @DisplayName("Loads bundled sample glossary terms successfully")
    void testSampleGlossaryLoaded() {
        assertTrue(glossaryService.size() >= 10);
        assertEquals(Optional.of("munyu"), glossaryService.getShona("salt"));
        assertEquals(Optional.of("shuga"), glossaryService.getShona("sugar"));
        assertEquals(Optional.of("mukaka"), glossaryService.getShona("milk"));
        assertEquals(Optional.of("mahedhifoni"), glossaryService.getShona("headphones"));
    }

    @Test
    @DisplayName("Key normalization handles case, leading/trailing spaces and multiple spaces")
    void testKeyNormalization() {
        assertEquals("salt", GlossaryService.normalizeKey("  SaLt  "));
        assertEquals("table salt", GlossaryService.normalizeKey("  Table   SALT  "));

        // Case insensitive lookup
        assertEquals(Optional.of("munyu"), glossaryService.getShona("SALT"));
        assertEquals(Optional.of("munyu"), glossaryService.getShona("  salt "));
        assertEquals(Optional.of("munyu wetafura"), glossaryService.getShona("TABLE   SALT"));
    }

    @Test
    @DisplayName("Reverse lookup maps reviewed Shona terms back to English")
    void testReverseLookup() {
        assertEquals(Optional.of("salt"), glossaryService.getEnglish("munyu"));
        assertEquals(Optional.of("sugar"), glossaryService.getEnglish("shuga"));
        assertEquals(Optional.of("milk"), glossaryService.getEnglish("mukaka"));

        // Unknown Shona term
        assertEquals(Optional.empty(), glossaryService.getEnglish("chisingazivikanwe"));
    }

    @Test
    @DisplayName("applyGlossary prioritizes longer phrases over single words to avoid partial shadowing")
    void testLongestPhraseFirstReplacement() {
        // "table salt" must match before "salt"
        String input = "High quality table salt from the sea";
        String output = glossaryService.applyGlossary(input);

        assertTrue(output.contains("munyu wetafura"), "Should replace 'table salt' with 'munyu wetafura'");
        assertFalse(output.contains("table munyu"), "Should NOT partially shadow as 'table munyu'");
    }

    @Test
    @DisplayName("applyGlossary respects word boundaries and does not replace subwords")
    void testWordBoundaryRespect() {
        // "salt" must NOT match inside "salted"
        String input = "Salted peanuts in a pouch";
        String output = glossaryService.applyGlossary(input);

        assertTrue(output.startsWith("Salted"), "'salted' should not be modified because 'salt' is a subword");
    }

    @Test
    @DisplayName("Can load custom JSON array of glossary objects")
    void testLoadCustomJsonArray() throws Exception {
        GlossaryService customService = new GlossaryService("non_existent_path.json");
        String json = """
                [
                  {"english": "computer", "shona": "kombiyuta", "domain": "tech", "status": "approved"},
                  {"english": "mouse", "shona": "mbeva", "domain": "tech", "status": "approved"}
                ]
                """;

        customService.loadFromJson(json);
        assertEquals(2, customService.size());
        assertEquals(Optional.of("kombiyuta"), customService.getShona("computer"));
        assertEquals(Optional.of("mbeva"), customService.getShona("mouse"));
        assertEquals(Optional.of("computer"), customService.getEnglish("kombiyuta"));
    }

    @Test
    @DisplayName("Can load custom JSON map of term pairs")
    void testLoadCustomJsonMap() throws Exception {
        GlossaryService customService = new GlossaryService("non_existent_path.json");
        String json = """
                {
                  "laptop": "rotombonhare",
                  "battery": "bhatiri"
                }
                """;

        customService.loadFromJson(json);
        assertEquals(2, customService.size());
        assertEquals(Optional.of("rotombonhare"), customService.getShona("laptop"));
        assertEquals(Optional.of("bhatiri"), customService.getShona("battery"));
    }

    @Test
    @DisplayName("Programmatic put, clear and contains methods")
    void testProgrammaticPutAndClear() {
        GlossaryService service = new GlossaryService("none");
        assertEquals(0, service.size());

        service.put("tea", "tii");
        assertTrue(service.containsEnglish("tea"));
        assertTrue(service.containsShona("tii"));
        assertEquals(Optional.of("tii"), service.getShona("tea"));

        service.clear();
        assertEquals(0, service.size());
        assertFalse(service.containsEnglish("tea"));
    }
}
