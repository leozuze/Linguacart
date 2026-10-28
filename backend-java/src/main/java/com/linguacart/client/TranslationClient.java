package com.linguacart.client;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dedicated client for translation operations, delegating to the configured ModelServiceClient.
 */
@Component
public class TranslationClient {

    private final ModelServiceClient modelServiceClient;

    public TranslationClient(ModelServiceClient modelServiceClient) {
        this.modelServiceClient = modelServiceClient;
    }

    /**
     * Translates a single text.
     */
    public String translate(String text, String sourceLanguage, String targetLanguage) {
        return modelServiceClient.translate(text, sourceLanguage, targetLanguage);
    }

    /**
     * Translates a map of named fields (e.g., title, description).
     */
    public Map<String, String> translateFields(Map<String, String> fields, String sourceLanguage, String targetLanguage) {
        if (fields == null || fields.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> translated = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            translated.put(entry.getKey(), modelServiceClient.translate(entry.getValue(), sourceLanguage, targetLanguage));
        }
        return translated;
    }
}
