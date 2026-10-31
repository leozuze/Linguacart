package com.linguacart.api;

import com.linguacart.dto.TranslateRequest;
import com.linguacart.dto.TranslateResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class TranslationController {

    @PostMapping("/translate")
    public TranslateResponse translate(@Valid @RequestBody TranslateRequest request) {
        // STUB: fake Shona so the extension developer can integrate now.
        Map<String, String> translations = new HashMap<>();
        request.fields().forEach((name, text) -> translations.put(name, "[sn] " + text));
        return new TranslateResponse(translations, request.protectedFields(), "translated", List.of());
    }
}