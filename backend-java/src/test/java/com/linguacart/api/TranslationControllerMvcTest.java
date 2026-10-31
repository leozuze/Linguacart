package com.linguacart.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linguacart.dto.TranslateRequest;
import com.linguacart.dto.TranslateResponse;
import com.linguacart.service.TranslationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MockMvc contract test for {@code POST /api/v1/translate}.
 *
 * <p>Verifies the final HTTP and JSON contract: status codes, response field names,
 * protectedFields echo, status string values, notTranslated list, and that
 * {@code status="failed"} still returns HTTP 200 (so the extension can display English).
 */
@WebMvcTest(TranslationController.class)
class TranslationControllerMvcTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    TranslationService translationService;

    // -------------------------------------------------------------------------
    // 1. Happy path — all fields translated
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /translate returns correct JSON shape with status=translated and empty notTranslated")
    void translateReturnsCorrectJsonShape() throws Exception {
        Map<String, String> protectedIn = Map.of("price", "$2.99", "brand", "BrandX");
        TranslateResponse stubResponse = new TranslateResponse(
                Map.of("title", "[sn] Table salt 500 g", "description", "[sn] Healthy salt"),
                protectedIn,
                "translated",
                List.of()
        );
        when(translationService.translate(any(TranslateRequest.class))).thenReturn(stubResponse);

        String body = """
                {
                  "sourceLanguage": "eng_Latn",
                  "targetLanguage": "sna_Latn",
                  "fields": { "title": "Table salt 500 g", "description": "Healthy salt" },
                  "protectedFields": { "price": "$2.99", "brand": "BrandX" }
                }
                """;

        mockMvc.perform(post("/api/v1/translate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // Required top-level keys
                .andExpect(jsonPath("$.translations").exists())
                .andExpect(jsonPath("$.protectedFields").exists())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.notTranslated").exists())
                // Status
                .andExpect(jsonPath("$.status").value("translated"))
                // notTranslated empty
                .andExpect(jsonPath("$.notTranslated", hasSize(0)))
                // Translation values
                .andExpect(jsonPath("$.translations.title").value("[sn] Table salt 500 g"))
                .andExpect(jsonPath("$.translations.description").value("[sn] Healthy salt"))
                // protectedFields echoed exactly
                .andExpect(jsonPath("$.protectedFields.price").value("$2.99"))
                .andExpect(jsonPath("$.protectedFields.brand").value("BrandX"));
    }

    // -------------------------------------------------------------------------
    // 2. status=failed still returns HTTP 200
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("status=failed (all fields reverted) still returns HTTP 200, not 5xx")
    void translateAllFailedReturnsHttp200() throws Exception {
        TranslateResponse failedResponse = new TranslateResponse(
                Map.of("title", "Salt", "description", "Table salt"),
                Map.of(),
                "failed",
                List.of("title", "description")
        );
        when(translationService.translate(any(TranslateRequest.class))).thenReturn(failedResponse);

        String body = """
                {
                  "sourceLanguage": "eng_Latn",
                  "targetLanguage": "sna_Latn",
                  "fields": { "title": "Salt", "description": "Table salt" },
                  "protectedFields": {}
                }
                """;

        mockMvc.perform(post("/api/v1/translate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())        // NOT 5xx — contract requirement
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.notTranslated", hasSize(2)))
                // English text returned for all reverted fields
                .andExpect(jsonPath("$.translations.title").value("Salt"))
                .andExpect(jsonPath("$.translations.description").value("Table salt"));
    }

    // -------------------------------------------------------------------------
    // 3. status=partial
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("status=partial: notTranslated lists only the reverted field name(s)")
    void translatePartialReturnsPartialStatus() throws Exception {
        TranslateResponse partialResponse = new TranslateResponse(
                Map.of("title", "Salt $2.99", "description", "[sn] Healthy salt"),
                Map.of(),
                "partial",
                List.of("title")
        );
        when(translationService.translate(any(TranslateRequest.class))).thenReturn(partialResponse);

        String body = """
                {
                  "sourceLanguage": "eng_Latn",
                  "targetLanguage": "sna_Latn",
                  "fields": { "title": "Salt $2.99", "description": "Healthy salt" },
                  "protectedFields": {}
                }
                """;

        mockMvc.perform(post("/api/v1/translate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("partial"))
                .andExpect(jsonPath("$.notTranslated", hasSize(1)))
                .andExpect(jsonPath("$.notTranslated[0]").value("title"))
                // Reverted field carries English text
                .andExpect(jsonPath("$.translations.title").value("Salt $2.99"));
    }

    // -------------------------------------------------------------------------
    // 4. Validation: empty fields map → 400
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /translate: empty fields map returns 400")
    void emptyFieldsReturns400() throws Exception {
        String body = """
                {
                  "sourceLanguage": "eng_Latn",
                  "targetLanguage": "sna_Latn",
                  "fields": {},
                  "protectedFields": {}
                }
                """;

        mockMvc.perform(post("/api/v1/translate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // 5. Validation: missing sourceLanguage → 400
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /translate: missing sourceLanguage returns 400")
    void missingSourceLanguageReturns400() throws Exception {
        String body = """
                {
                  "targetLanguage": "sna_Latn",
                  "fields": { "title": "Salt" }
                }
                """;

        mockMvc.perform(post("/api/v1/translate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // 6. protectedFields null → defaults to empty map, request succeeds
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /translate: null protectedFields defaults to empty map, request succeeds")
    void nullProtectedFieldsDefaultsToEmptyMap() throws Exception {
        TranslateResponse resp = new TranslateResponse(
                Map.of("title", "[sn] Fresh fruit"),
                Map.of(),
                "translated",
                List.of()
        );
        when(translationService.translate(any(TranslateRequest.class))).thenReturn(resp);

        String body = """
                {
                  "sourceLanguage": "eng_Latn",
                  "targetLanguage": "sna_Latn",
                  "fields": { "title": "Fresh fruit" }
                }
                """;

        mockMvc.perform(post("/api/v1/translate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.protectedFields").isMap());
    }

    // -------------------------------------------------------------------------
    // 7. protectedFields echoed unchanged (full set)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /translate: protectedFields returned exactly as sent")
    void protectedFieldsEchoedUnchanged() throws Exception {
        Map<String, String> sent = Map.of(
                "price", "$2.99", "quantity", "1", "unit", "kg",
                "brand", "BrandX", "model", "BX-100"
        );
        TranslateResponse resp = new TranslateResponse(
                Map.of("title", "[sn] BrandX BX-100 Salt 1 kg $2.99"),
                sent,
                "translated",
                List.of()
        );
        when(translationService.translate(any(TranslateRequest.class))).thenReturn(resp);

        String body = objectMapper.writeValueAsString(
                new TranslateRequest("eng_Latn", "sna_Latn",
                        Map.of("title", "BrandX BX-100 Salt 1 kg $2.99"), sent));

        mockMvc.perform(post("/api/v1/translate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.protectedFields.price").value("$2.99"))
                .andExpect(jsonPath("$.protectedFields.quantity").value("1"))
                .andExpect(jsonPath("$.protectedFields.unit").value("kg"))
                .andExpect(jsonPath("$.protectedFields.brand").value("BrandX"))
                .andExpect(jsonPath("$.protectedFields.model").value("BX-100"));
    }
}
