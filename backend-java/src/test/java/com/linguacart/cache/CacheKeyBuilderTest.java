package com.linguacart.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CacheKeyBuilderTest {

    @Test
    @DisplayName("same inputs produce the same key")
    void deterministicKey() {
        String k1 = CacheKeyBuilder.buildKey("hello", "title", "eng_Latn", "sna_Latn", "nllb-v1");
        String k2 = CacheKeyBuilder.buildKey("hello", "title", "eng_Latn", "sna_Latn", "nllb-v1");
        assertEquals(k1, k2);
    }

    @Test
    @DisplayName("key is 64-character lowercase hex (SHA-256)")
    void keyFormat() {
        String key = CacheKeyBuilder.buildKey("text", "desc", "eng_Latn", "sna_Latn", "v1");
        assertEquals(64, key.length());
        assertTrue(key.matches("[0-9a-f]{64}"), "Key must be lowercase hex");
    }

    @Test
    @DisplayName("different text produces different key")
    void differentTextDifferentKey() {
        String k1 = CacheKeyBuilder.buildKey("a", "title", "eng_Latn", "sna_Latn", "v1");
        String k2 = CacheKeyBuilder.buildKey("b", "title", "eng_Latn", "sna_Latn", "v1");
        assertNotEquals(k1, k2);
    }

    @Test
    @DisplayName("different modelVersion produces different key (invalidates old entries)")
    void differentModelVersionDifferentKey() {
        String k1 = CacheKeyBuilder.buildKey("hello", "title", "eng_Latn", "sna_Latn", "v1");
        String k2 = CacheKeyBuilder.buildKey("hello", "title", "eng_Latn", "sna_Latn", "v2");
        assertNotEquals(k1, k2, "Model version change must invalidate cache entries");
    }

    @Test
    @DisplayName("different targetLanguage produces different key")
    void differentTargetLanguageDifferentKey() {
        String k1 = CacheKeyBuilder.buildKey("hello", "title", "eng_Latn", "sna_Latn", "v1");
        String k2 = CacheKeyBuilder.buildKey("hello", "title", "eng_Latn", "fra_Latn", "v1");
        assertNotEquals(k1, k2);
    }

    @Test
    @DisplayName("length-prefix prevents cross-component collisions")
    void noCrossComponentCollision() {
        // "ab" + "c" vs "a" + "bc" — must not collide
        String k1 = CacheKeyBuilder.buildKey("ab", "c", "eng_Latn", "sna_Latn", "v1");
        String k2 = CacheKeyBuilder.buildKey("a", "bc", "eng_Latn", "sna_Latn", "v1");
        assertNotEquals(k1, k2, "Length-prefix must prevent cross-component collision");
    }

    @Test
    @DisplayName("null components are handled gracefully (treated as empty)")
    void nullComponentsHandled() {
        // Should not throw; null treated as empty string
        assertDoesNotThrow(() -> {
            String key = CacheKeyBuilder.buildKey(null, null, null, null, null);
            assertNotNull(key);
            assertEquals(64, key.length());
        });
    }
}
