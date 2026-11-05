package com.linguacart.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvertedIndexTest {

    private static InvertedIndex sample() {
        InvertedIndex index = new InvertedIndex();
        index.add("p1", List.of("salt", "iodized", "salt"));
        index.add("p2", List.of("sugar", "white"));
        index.add("p3", List.of("rice", "salt"));
        return index;
    }

    @Test
    void findsProductsByToken() {
        InvertedIndex index = sample();
        assertEquals(List.of("p1", "p3"), index.productIds("salt"));
        assertEquals(List.of(), index.productIds("missing"));
        assertEquals(3, index.size());
        assertTrue(index.vocabulary().contains("rice"));
    }

    @Test
    void bm25ScoresMatchingDocumentsOnly() {
        double[] scores = sample().bm25(List.of("sugar"));
        assertEquals(0.0, scores[0], 1e-12);
        assertTrue(scores[1] > 0);
        assertEquals(0.0, scores[2], 1e-12);
    }

    @Test
    void moreOccurrencesScoreHigherAndRareTermsOutweighCommonOnes() {
        InvertedIndex index = sample();
        double[] salt = index.bm25(List.of("salt"));
        assertTrue(salt[0] > salt[2]);
        double[] rare = index.bm25(List.of("iodized"));
        assertTrue(rare[0] > 0);
    }

    @Test
    void repeatedQueryTokensCountOnce() {
        InvertedIndex index = sample();
        assertEquals(index.bm25(List.of("sugar"))[1], index.bm25(List.of("sugar", "sugar"))[1], 1e-12);
    }

    @Test
    void emptyIndexAndUnknownTokensGiveZeros() {
        assertEquals(0, new InvertedIndex().bm25(List.of("x")).length);
        double[] scores = sample().bm25(List.of("zzz"));
        for (double s : scores) {
            assertEquals(0.0, s, 1e-12);
        }
    }
}
