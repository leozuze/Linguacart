package com.linguacart.dto;

public record ProductResult(
        String productId,
        String titleEn,
        String titleSn,
        String price,
        double score,
        String sourceUrl
) {}