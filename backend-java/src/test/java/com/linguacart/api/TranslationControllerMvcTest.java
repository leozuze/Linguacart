package com.linguacart.api;

import com.linguacart.dto.SegmentDtos.Request;
import com.linguacart.dto.SegmentDtos.Response;
import com.linguacart.dto.SegmentDtos.Result;
import com.linguacart.service.SegmentTranslationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MockMvc contract test for POST /api/v1/translate (docs/07-API-CONTRACT.md v0.3). */
@WebMvcTest(TranslationController.class)
class TranslationControllerMvcTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    SegmentTranslationService translationService;

    private static final String VALID = """
            {
              "sourceLanguage": "eng_Latn",
              "targetLanguage": "sna_Latn",
              "site": "amazon.com",
              "pageType": "listing",
              "segments": [
                { "id": "s0", "text": "Add to cart", "kind": "ui_label" },
                { "id": "s4", "text": "Not waterproof. Runs small.", "kind": "review" }
              ]
            }
            """;

    private void expectBadRequest(String body) throws Exception {
        mockMvc.perform(post("/api/v1/translate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("valid request returns the v0.3 shape; not_translated results omit text and source")
    void validRequest() throws Exception {
        Response stub = new Response("partial", List.of("s4"), List.of(
                Result.translated("s0", "nllb", "machine", "[sn] Add to cart"),
                Result.notTranslated("s4", "negation_mismatch")), Map.of());
        when(translationService.translate(any(Request.class))).thenReturn(stub);

        mockMvc.perform(post("/api/v1/translate").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("partial"))
                .andExpect(jsonPath("$.notTranslated[0]").value("s4"))
                .andExpect(jsonPath("$.results[0].id").value("s0"))
                .andExpect(jsonPath("$.results[0].status").value("translated"))
                .andExpect(jsonPath("$.results[0].source").value("nllb"))
                .andExpect(jsonPath("$.results[0].translationType").value("machine"))
                .andExpect(jsonPath("$.results[0].text").value("[sn] Add to cart"))
                .andExpect(jsonPath("$.results[0].reasons").doesNotExist())
                .andExpect(jsonPath("$.results[1].status").value("not_translated"))
                .andExpect(jsonPath("$.results[1].reasons[0]").value("negation_mismatch"))
                .andExpect(jsonPath("$.results[1].text").doesNotExist())
                .andExpect(jsonPath("$.results[1].source").doesNotExist())
                .andExpect(jsonPath("$.protectedFields").doesNotExist());
    }

    @Test
    @DisplayName("status failed is still HTTP 200 so the extension can keep English")
    void failedIsStill200() throws Exception {
        Response stub = new Response("failed", List.of("s0", "s4"), List.of(
                Result.notTranslated("s0", "model_error"),
                Result.notTranslated("s4", "model_error")), Map.of());
        when(translationService.translate(any(Request.class))).thenReturn(stub);

        mockMvc.perform(post("/api/v1/translate").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("failed"));
    }

    @Test
    @DisplayName("protectedFields are returned when the request carried them")
    void protectedFieldsReturned() throws Exception {
        Response stub = new Response("translated", List.of(), List.of(
                Result.translated("s0", "nllb", "machine", "x")), Map.of("price", "$2.99"));
        when(translationService.translate(any(Request.class))).thenReturn(stub);

        mockMvc.perform(post("/api/v1/translate").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(jsonPath("$.protectedFields.price").value("$2.99"));
    }

    @Test
    @DisplayName("400 when segments are missing or empty")
    void segmentsMissing() throws Exception {
        expectBadRequest("{\"sourceLanguage\":\"eng_Latn\",\"targetLanguage\":\"sna_Latn\"}");
        expectBadRequest("{\"sourceLanguage\":\"eng_Latn\",\"targetLanguage\":\"sna_Latn\",\"segments\":[]}");
    }

    @Test
    @DisplayName("400 on the old fields-map request shape")
    void oldShapeRejected() throws Exception {
        expectBadRequest("{\"sourceLanguage\":\"eng_Latn\",\"targetLanguage\":\"sna_Latn\",\"fields\":{\"title\":\"x\"}}");
    }

    @Test
    @DisplayName("400 on wrong languages")
    void wrongLanguages() throws Exception {
        expectBadRequest("{\"sourceLanguage\":\"sna_Latn\",\"targetLanguage\":\"eng_Latn\","
                + "\"segments\":[{\"id\":\"a\",\"text\":\"x\"}]}");
    }

    @Test
    @DisplayName("400 on empty id, empty text, or unknown kind")
    void badSegment() throws Exception {
        String head = "{\"sourceLanguage\":\"eng_Latn\",\"targetLanguage\":\"sna_Latn\",\"segments\":[";
        expectBadRequest(head + "{\"id\":\"\",\"text\":\"x\"}]}");
        expectBadRequest(head + "{\"id\":\"a\",\"text\":\"   \"}]}");
        expectBadRequest(head + "{\"id\":\"a\",\"text\":\"x\",\"kind\":\"banner\"}]}");
    }

    @Test
    @DisplayName("400 on duplicate segment ids")
    void duplicateIds() throws Exception {
        expectBadRequest("{\"sourceLanguage\":\"eng_Latn\",\"targetLanguage\":\"sna_Latn\",\"segments\":["
                + "{\"id\":\"a\",\"text\":\"x\"},{\"id\":\"a\",\"text\":\"y\"}]}");
    }

    @Test
    @DisplayName("400 on more than 50 segments")
    void tooManySegments() throws Exception {
        StringBuilder sb = new StringBuilder(
                "{\"sourceLanguage\":\"eng_Latn\",\"targetLanguage\":\"sna_Latn\",\"segments\":[");
        for (int i = 0; i < 51; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":\"s").append(i).append("\",\"text\":\"x\"}");
        }
        sb.append("]}");
        expectBadRequest(sb.toString());
    }

    @Test
    @DisplayName("400 on text longer than 2000 characters")
    void textTooLong() throws Exception {
        expectBadRequest("{\"sourceLanguage\":\"eng_Latn\",\"targetLanguage\":\"sna_Latn\",\"segments\":["
                + "{\"id\":\"a\",\"text\":\"" + "x".repeat(2001) + "\"}]}");
    }

    @Test
    @DisplayName("400 on malformed JSON")
    void malformedJson() throws Exception {
        expectBadRequest("{not json");
    }
}
