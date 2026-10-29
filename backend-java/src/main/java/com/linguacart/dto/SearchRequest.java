package com.linguacart.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record SearchRequest(
        @NotBlank String query,
        String language,
        @Min(1) @Max(50) Integer topK
) {
    public SearchRequest {
        if (language == null || language.isBlank()) {
            language = "sna_Latn";
        }
        if (topK == null) {
            topK = 10;
        }
    }
}