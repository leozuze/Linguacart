package com.linguacart.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorSearchServiceTest {

    @Test
    void cosineIgnoresVectorLength() {
        VectorSearchService v = new VectorSearchService(List.of(new float[] {2, 0}, new float[] {0, 3}));
        double[] s = v.scores(new float[] {10, 0});
        assertEquals(1.0, s[0], 1e-6);
        assertEquals(0.0, s[1], 1e-6);
    }

    @Test
    void ordersByCloseness() {
        VectorSearchService v = new VectorSearchService(List.of(new float[] {1, 1}, new float[] {1, 0}));
        double[] s = v.scores(new float[] {1, 0.1f});
        assertTrue(s[1] > s[0]);
    }

    @Test
    void rejectsMismatchedDimensions() {
        assertThrows(IllegalArgumentException.class,
                () -> new VectorSearchService(List.of(new float[] {1, 0}, new float[] {1, 0, 0})));
        VectorSearchService v = new VectorSearchService(List.of(new float[] {1, 0}));
        assertThrows(IllegalArgumentException.class, () -> v.scores(new float[] {1, 0, 0}));
    }

    @Test
    void emptyCatalogueGivesNoScores() {
        assertEquals(0, new VectorSearchService(List.of()).scores(new float[] {1}).length);
    }

    @Test
    void zeroVectorScoresZero() {
        VectorSearchService v = new VectorSearchService(List.of(new float[] {0, 0}));
        assertEquals(0.0, v.scores(new float[] {1, 0})[0], 1e-12);
    }
}
