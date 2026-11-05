package com.linguacart.search;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenizerTest {

    @Test
    void lowercasesStemsAndDropsStopWords() {
        assertEquals(List.of("iodized", "table", "salt"), Tokenizer.tokens("The iodized table salts"));
    }

    @Test
    void normalisesQuantities() {
        assertEquals(List.of("flour", "2kg"), Tokenizer.tokens("Flour 2 kg"));
        assertEquals(List.of("water", "5l"), Tokenizer.tokens("Water 5 litres"));
    }

    @Test
    void stemRules() {
        assertEquals("cook", Tokenizer.stem("cooking"));
        assertEquals("ring", Tokenizer.stem("ring"));
        assertEquals("glass", Tokenizer.stem("glass"));
        assertEquals("bag", Tokenizer.stem("bags"));
        assertEquals("gas", Tokenizer.stem("gas"));
    }

    @Test
    void normalizeQueryRemovesPunctuationAndCollapsesSpaces() {
        assertEquals("munyu 1 kg", Tokenizer.normalizeQuery("  Munyu,   1-kg!! "));
        assertEquals("", Tokenizer.normalizeQuery("?!"));
        assertEquals("", Tokenizer.normalizeQuery(null));
    }

    @Test
    void cleanTranslationStripsArticleAndTrailingPunctuation() {
        assertEquals("salt", Tokenizer.cleanTranslation("The Salt."));
        assertEquals("a1", Tokenizer.cleanTranslation("A1 ?! "));
        assertEquals("", Tokenizer.cleanTranslation(null));
    }

    @Test
    void stripsShonaPrefixOnlyWhenTheRestIsACatalogueWord() {
        Set<String> vocab = Set.of("samsung", "salt");
        assertEquals("samsung", Tokenizer.stripShonaPrefix("yesamsung", vocab));
        assertEquals("salt", Tokenizer.stripShonaPrefix("salt", vocab));
        assertEquals("yeabc", Tokenizer.stripShonaPrefix("yeabc", vocab));
        assertEquals("ne", Tokenizer.stripShonaPrefix("ne", vocab));
    }
}
