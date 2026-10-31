package com.linguacart.api;

import com.linguacart.client.ModelServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(GlobalExceptionHandlerTest.TestErrorTriggerController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("400 validation error on blank search query")
    void testValidationErrorOnSearch() throws Exception {
        mockMvc.perform(post("/api/v1/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("400 validation error on empty translate fields")
    void testValidationErrorOnTranslate() throws Exception {
        mockMvc.perform(post("/api/v1/translate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceLanguage\":\"eng_Latn\",\"targetLanguage\":\"sna_Latn\",\"fields\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("400 validation error on malformed JSON body")
    void testMalformedJson() throws Exception {
        mockMvc.perform(post("/api/v1/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message").value("Malformed or missing request body"));
    }

    @Test
    @DisplayName("503 model service unavailable error")
    void testModelServiceUnavailable503() throws Exception {
        mockMvc.perform(get("/test/errors/model-service-503"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("model_service_unavailable"))
                .andExpect(jsonPath("$.message").value("Python model service connection timeout"));
    }

    @Test
    @DisplayName("502 model service bad gateway error")
    void testModelServiceBadGateway502() throws Exception {
        mockMvc.perform(get("/test/errors/model-service-502"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("model_service_unavailable"))
                .andExpect(jsonPath("$.message").value("Bad gateway from downstream model service"));
    }

    @Test
    @DisplayName("500 internal server error")
    void testInternalError500() throws Exception {
        mockMvc.perform(get("/test/errors/internal-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("internal_error"))
                .andExpect(jsonPath("$.message").value("An internal error occurred: Unexpected database or server glitch"));
    }

    @RestController
    @RequestMapping("/test/errors")
    static class TestErrorTriggerController {

        @GetMapping("/model-service-503")
        public void triggerModelService503() {
            throw new ModelServiceException("Python model service connection timeout", HttpStatus.SERVICE_UNAVAILABLE);
        }

        @GetMapping("/model-service-502")
        public void triggerModelService502() {
            throw new ModelServiceException("Bad gateway from downstream model service", HttpStatus.BAD_GATEWAY);
        }

        @GetMapping("/internal-error")
        public void triggerInternalError() {
            throw new RuntimeException("Unexpected database or server glitch");
        }
    }
}
