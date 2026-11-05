package com.linguacart.api;

import com.linguacart.dto.SegmentDtos;
import com.linguacart.service.SegmentTranslationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/v1/translate, segment-based contract v0.3 (docs/07-API-CONTRACT.md).
 * All logic lives in {@link SegmentTranslationService}.
 */
@RestController
@RequestMapping("/api/v1")
public class TranslationController {

    private final SegmentTranslationService translationService;

    public TranslationController(SegmentTranslationService translationService) {
        this.translationService = translationService;
    }

    @PostMapping("/translate")
    public SegmentDtos.Response translate(@Valid @RequestBody SegmentDtos.Request request) {
        return translationService.translate(request);
    }
}
