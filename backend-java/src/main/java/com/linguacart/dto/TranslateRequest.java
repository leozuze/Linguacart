package com.linguacart.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.Map;

public record TranslateRequest(
        @NotBlank String sourceLanguage,
        @NotBlank String targetLanguage,
        @NotEmpty Map<String, String> fields,
        Map<String, String> protectedFields
) {
    public TranslateRequest {
        if (protectedFields == null) {
            protectedFields = Map.of();
        }
    }
}