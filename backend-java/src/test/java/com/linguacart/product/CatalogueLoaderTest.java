package com.linguacart.product;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogueLoaderTest {

    private final CatalogueLoader loader = new CatalogueLoader("does/not/exist.jsonl");

    @Test
    void parsesValidRowsAndSkipsBadOnes() {
        List<ProductRecord> products = loader.parseLines(List.of(
                "{\"id\":\"p1\",\"title\":\"Table salt\",\"description\":\"Fine\",\"brand\":\"X\",\"category\":\"food\","
                        + "\"price\":\"$1.00\",\"currency\":\"usd\",\"quantity\":\"1\",\"unit\":\"Litre\"}",
                "garbage",
                "{\"title\":\"No id\"}",
                "{\"id\":\"p1\",\"title\":\"Duplicate id\"}",
                ""));
        assertEquals(1, products.size());
        ProductRecord p = products.get(0);
        assertEquals("p1", p.id());
        assertEquals("USD", p.currency());
        assertEquals("l", p.unit());
        assertTrue(p.passageText().contains("Table salt"));
    }

    @Test
    void missingFileGivesEmptyList() {
        assertTrue(loader.load().isEmpty());
    }
}
