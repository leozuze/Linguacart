package com.linguacart.dto;

import jakarta.validation.constraints.NotBlank;

public record FeedbackRequest(
        @NotBlank String type,
        String productId,
        String field,
        String comment
) {}