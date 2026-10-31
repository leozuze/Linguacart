package com.linguacart.api;

import com.linguacart.dto.TranslateRequest;
import com.linguacart.dto.TranslateResponse;
import com.linguacart.service.TranslationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for product-field translation.
 *
 * <p>This controller is intentionally thin — all translation logic lives in
 * {@link TranslationService}. The controller's sole job is request validation
 * and HTTP delegation.
 */
@RestController
@RequestMapping("/api/v1")
public class TranslationController {

    private final TranslationService translationService;

    public TranslationController(TranslationService translationService) {
        this.translationService = translationService;
    }

    @PostMapping("/translate")
    public TranslateResponse translate(@Valid @RequestBody TranslateRequest request) {
        return translationService.translate(request);
    }
}