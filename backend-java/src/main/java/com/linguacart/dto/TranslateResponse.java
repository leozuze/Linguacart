package com.linguacart.dto;

import java.util.List;
import java.util.Map;

public record TranslateResponse(
        Map<String, String> translations,
        Map<String, String> protectedFields,
        String status,
        List<String> notTranslated
) {}