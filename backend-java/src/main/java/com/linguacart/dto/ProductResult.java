package com.linguacart.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One search hit. {@code titleSn} is null (and omitted from the JSON) when no Shona title was produced
 * or when the title failed a protection check; the extension then shows {@code titleEn}.
 * {@code matchedOn} lists why the product matched, e.g. "glossary:munyu", "keyword", "vector".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductResult(
        String productId,
        String titleEn,
        String titleSn,
        String price,
        double score,
        String sourceUrl,
        java.util.List<String> matchedOn
) {}
