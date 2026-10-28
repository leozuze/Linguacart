package com.linguacart.client;

import java.util.List;

/**
 * Client interface for interacting with the Python model service (NLLB translation and E5 embeddings).
 */
public interface ModelServiceClient {

    /**
     * Translates a single text from source language to target language.
     *
     * @param text the text to translate
     * @param sourceLanguage language code, e.g. "eng_Latn"
     * @param targetLanguage language code, e.g. "sna_Latn"
     * @return the translated text
     */
    String translate(String text, String sourceLanguage, String targetLanguage);

    /**
     * Generates normalized embeddings for the given list of texts with the specified prefix.
     *
     * @param texts list of texts to embed
     * @param prefix "query" or "passage"
     * @return list of float arrays representing the embedding vectors
     */
    List<float[]> embed(List<String> texts, String prefix);

    /**
     * Helper to embed a single text.
     */
    default float[] embedSingle(String text, String prefix) {
        List<float[]> result = embed(List.of(text), prefix);
        if (result == null || result.isEmpty()) {
            throw new ModelServiceException("Model service returned empty embeddings");
        }
        return result.get(0);
    }

    /**
     * Checks if the model service is healthy and reachable.
     *
     * @return true if healthy, false otherwise
     */
    boolean isHealthy();
}
