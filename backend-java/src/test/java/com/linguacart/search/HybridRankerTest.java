package com.linguacart.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridRankerTest {

    @Test
    void minMaxScalesToZeroOne() {
        assertArrayEquals(new double[] {0.0, 0.5, 1.0}, HybridRanker.minMax(new double[] {2, 4, 6}), 1e-12);
    }

    @Test
    void minMaxOfConstantsIsAllZeros() {
        assertArrayEquals(new double[] {0, 0, 0}, HybridRanker.minMax(new double[] {3, 3, 3}), 1e-12);
        assertEquals(0, HybridRanker.minMax(new double[0]).length);
    }

    @Test
    void combinesVectorAndKeywordScores() {
        HybridRanker ranker = new HybridRanker(0.6, 0.4, 0.4);
        HybridRanker.Scores s = ranker.combine(new double[] {0.1, 0.9}, null, new double[] {5, 0});
        assertEquals(0.4, s.total()[0], 1e-12);
        assertEquals(0.6, s.total()[1], 1e-12);
    }

    @Test
    void translatedQueryContributesToTheVectorPart() {
        HybridRanker ranker = new HybridRanker(1.0, 0.0, 0.4);
        HybridRanker.Scores s = ranker.combine(new double[] {1, 0}, new double[] {0, 1}, new double[] {0, 0});
        // original favours doc 0 (weight 0.4), translated favours doc 1 (weight 0.6)
        assertTrue(s.total()[1] > s.total()[0]);
    }

    @Test
    void rejectsBadWeights() {
        assertThrows(IllegalArgumentException.class, () -> new HybridRanker(-1, 1, 0.4));
        assertThrows(IllegalArgumentException.class, () -> new HybridRanker(0, 0, 0.4));
        assertThrows(IllegalArgumentException.class, () -> new HybridRanker(0.6, 0.4, 1.5));
    }
}
