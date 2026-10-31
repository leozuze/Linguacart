package com.linguacart.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Builds a collision-resistant cache key for translation results.
 *
 * <p>Each component is length-prefixed before joining so that no two distinct
 * component tuples can produce the same flat string (e.g. "ab" + "c" vs "a" + "bc").
 * The resulting string is SHA-256 hex-encoded to keep keys at a fixed, compact size.
 *
 * <p>The {@code modelVersion} component is intentionally included so that a config
 * change (e.g. swapping NLLB-200-600M for NLLB-200-1.3B) automatically invalidates
 * all old cache entries without requiring an explicit flush.
 */
public final class CacheKeyBuilder {

    private static final String DELIMITER = "|";

    private CacheKeyBuilder() {}

    /**
     * Builds a SHA-256 hex key from the five translation-identity components.
     *
     * @param text           the source text to translate
     * @param fieldType      the field type (e.g. "title", "description")
     * @param sourceLanguage e.g. "eng_Latn"
     * @param targetLanguage e.g. "sna_Latn"
     * @param modelVersion   e.g. "nllb-200-600M" — from {@code linguacart.cache.model-version}
     * @return 64-character lowercase hex SHA-256 digest
     */
    public static String buildKey(String text,
                                  String fieldType,
                                  String sourceLanguage,
                                  String targetLanguage,
                                  String modelVersion) {
        // Length-prefixed join prevents cross-component collisions
        String raw = lengthPrefix(text)
                + DELIMITER + lengthPrefix(fieldType)
                + DELIMITER + lengthPrefix(sourceLanguage)
                + DELIMITER + lengthPrefix(targetLanguage)
                + DELIMITER + lengthPrefix(modelVersion);

        return sha256Hex(raw);
    }

    private static String lengthPrefix(String value) {
        String safe = value == null ? "" : value;
        return safe.length() + ":" + safe;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JVM spec; this cannot happen in practice.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
