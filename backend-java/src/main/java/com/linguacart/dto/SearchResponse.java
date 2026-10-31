package com.linguacart.dto;

import java.util.List;

public record SearchResponse(
        String query,
        List<ProductResult> results
) {}