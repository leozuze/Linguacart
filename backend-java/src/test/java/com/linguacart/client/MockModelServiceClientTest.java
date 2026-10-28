package com.linguacart.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MockModelServiceClientTest {

    private final MockModelServiceClient client = new MockModelServiceClient();

    @Test
    @DisplayName("translate returns text prefixed with [sn]")
    void testTranslate() {
        String result = client.translate("Iodized table salt", "eng_Latn", "sna_Latn");
        assertEquals("[sn] Iodized table salt", result);
    }

    @Test
    @DisplayName("translate returns null when input is null")
    void testTranslateNull() {
        assertNull(client.translate(null, "eng_Latn", "sna_Latn"));
    }

    @Test
    @DisplayName("embed returns 768-dimensional L2-normalized vectors")
    void testEmbedNormalizedVectors() {
        List<String> texts = List.of("salt", "sugar");
        List<float[]> embeddings = client.embed(texts, "query");

        assertEquals(2, embeddings.size());
        for (float[] vector : embeddings) {
            assertEquals(768, vector.length);

            // Compute L2 norm: sqrt(sum(v_i^2))
            double sumSquares = 0.0;
            for (float v : vector) {
                sumSquares += v * v;
            }
            double norm = Math.sqrt(sumSquares);
            assertEquals(1.0, norm, 1e-4, "Vector must be L2-normalized");
        }
    }

    @Test
    @DisplayName("embed generates deterministic vectors for identical inputs")
    void testEmbedDeterminism() {
        List<float[]> first = client.embed(List.of("salt"), "query");
        List<float[]> second = client.embed(List.of("salt"), "query");

        assertArrayEquals(first.get(0), second.get(0), 1e-6f);
    }

    @Test
    @DisplayName("embedSingle returns single vector")
    void testEmbedSingle() {
        float[] vector = client.embedSingle("salt", "query");
        assertNotNull(vector);
        assertEquals(768, vector.length);
    }

    @Test
    @DisplayName("isHealthy returns true")
    void testIsHealthy() {
        assertTrue(client.isHealthy());
    }
}
