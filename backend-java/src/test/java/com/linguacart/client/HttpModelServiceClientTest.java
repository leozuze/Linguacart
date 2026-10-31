package com.linguacart.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class HttpModelServiceClientTest {

    private static final String BASE = "http://localhost:8001";

    private MockRestServiceServer mockServer;
    private HttpModelServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new HttpModelServiceClient(builder.build(), 3, 10);
    }

    private static String embeddingsJson(int n) {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add("[0.5, 0.5]");
        }
        return "{\"embeddings\": [" + String.join(",", rows) + "]}";
    }

    @Test
    @DisplayName("translate sends the batch-style body and unwraps the single translation")
    void translateSuccess() {
        mockServer.expect(requestTo(BASE + "/translate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.texts[0]").value("salt"))
                .andExpect(jsonPath("$.texts[1]").doesNotExist())
                .andExpect(jsonPath("$.sourceLanguage").value("eng_Latn"))
                .andExpect(jsonPath("$.targetLanguage").value("sna_Latn"))
                .andExpect(jsonPath("$.text").doesNotExist())
                .andRespond(withSuccess("{\"translations\": [\"munyu\"]}", MediaType.APPLICATION_JSON));

        assertEquals("munyu", client.translate("salt", "eng_Latn", "sna_Latn"));
        mockServer.verify();
    }

    @Test
    @DisplayName("translate supports the Shona to English direction used for search queries")
    void translateShonaToEnglish() {
        mockServer.expect(requestTo(BASE + "/translate"))
                .andExpect(jsonPath("$.sourceLanguage").value("sna_Latn"))
                .andExpect(jsonPath("$.targetLanguage").value("eng_Latn"))
                .andRespond(withSuccess("{\"translations\": [\"the salt\"]}", MediaType.APPLICATION_JSON));

        assertEquals("the salt", client.translate("munyu", "sna_Latn", "eng_Latn"));
        mockServer.verify();
    }

    @Test
    @DisplayName("embed sends typed inputs and never adds E5 prefixes itself")
    void embedSuccess() {
        mockServer.expect(requestTo(BASE + "/embed"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.inputs[0].type").value("query"))
                .andExpect(jsonPath("$.inputs[0].text").value("salt"))
                .andExpect(jsonPath("$.prefix").doesNotExist())
                .andExpect(jsonPath("$.texts").doesNotExist())
                .andRespond(withSuccess("{\"embeddings\": [[0.1, 0.2, 0.3]]}", MediaType.APPLICATION_JSON));

        List<float[]> embeddings = client.embed(List.of("salt"), "query");
        assertEquals(1, embeddings.size());
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f}, embeddings.get(0), 1e-4f);
        mockServer.verify();
    }

    @Test
    @DisplayName("embed splits large lists into chunks of 32 and keeps the order")
    void embedChunks() {
        List<String> texts = IntStream.range(0, 40).mapToObj(i -> "t" + i).toList();

        mockServer.expect(requestTo(BASE + "/embed"))
                .andExpect(jsonPath("$.inputs[0].type").value("passage"))
                .andExpect(jsonPath("$.inputs[31].text").value("t31"))
                .andExpect(jsonPath("$.inputs[32]").doesNotExist())
                .andRespond(withSuccess(embeddingsJson(32), MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(BASE + "/embed"))
                .andExpect(jsonPath("$.inputs[0].text").value("t32"))
                .andExpect(jsonPath("$.inputs[7].text").value("t39"))
                .andRespond(withSuccess(embeddingsJson(8), MediaType.APPLICATION_JSON));

        assertEquals(40, client.embed(texts, "passage").size());
        mockServer.verify();
    }

    @Test
    @DisplayName("embed rejects an unknown prefix")
    void embedBadPrefix() {
        assertThrows(IllegalArgumentException.class, () -> client.embed(List.of("salt"), "document"));
    }

    @Test
    @DisplayName("retries on a 5xx answer and succeeds if a retry succeeds")
    void retrySuccess() {
        mockServer.expect(requestTo(BASE + "/translate")).andRespond(withServerError());
        mockServer.expect(requestTo(BASE + "/translate"))
                .andRespond(withSuccess("{\"translations\": [\"munyu\"]}", MediaType.APPLICATION_JSON));

        assertEquals("munyu", client.translate("salt", "eng_Latn", "sna_Latn"));
        mockServer.verify();
    }

    @Test
    @DisplayName("exhausting retries throws ModelServiceException")
    void retriesExhausted() {
        mockServer.expect(requestTo(BASE + "/translate")).andRespond(withServerError());
        mockServer.expect(requestTo(BASE + "/translate")).andRespond(withServerError());
        mockServer.expect(requestTo(BASE + "/translate")).andRespond(withServerError());

        assertThrows(ModelServiceException.class, () -> client.translate("salt", "eng_Latn", "sna_Latn"));
        mockServer.verify();
    }

    @Test
    @DisplayName("a 422 answer is NOT retried and is reported as 502")
    void clientErrorNotRetried() {
        mockServer.expect(requestTo(BASE + "/translate"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\": \"bad request\"}"));

        ModelServiceException ex = assertThrows(ModelServiceException.class,
                () -> client.translate("salt", "eng_Latn", "sna_Latn"));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
        mockServer.verify();
    }

    @Test
    @DisplayName("an empty translations list is rejected without retrying")
    void emptyTranslationsRejected() {
        mockServer.expect(requestTo(BASE + "/translate"))
                .andRespond(withSuccess("{\"translations\": []}", MediaType.APPLICATION_JSON));

        assertThrows(ModelServiceException.class, () -> client.translate("salt", "eng_Latn", "sna_Latn"));
        mockServer.verify();
    }

    @Test
    @DisplayName("isHealthy is true only when status is ok")
    void healthOk() {
        mockServer.expect(requestTo(BASE + "/health"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"status\": \"ok\", \"translation_model\": \"loaded\", \"embedding_model\": \"loaded\"}",
                        MediaType.APPLICATION_JSON));

        assertTrue(client.isHealthy());
        mockServer.verify();
    }

    @Test
    @DisplayName("isHealthy is false when the service reports degraded")
    void healthDegraded() {
        mockServer.expect(requestTo(BASE + "/health"))
                .andRespond(withSuccess(
                        "{\"status\": \"degraded\", \"translation_model\": \"loaded\", \"embedding_model\": \"unavailable\"}",
                        MediaType.APPLICATION_JSON));

        assertFalse(client.isHealthy());
        mockServer.verify();
    }

    @Test
    @DisplayName("isHealthy is false on a server error")
    void healthServerError() {
        mockServer.expect(requestTo(BASE + "/health")).andRespond(withServerError());

        assertFalse(client.isHealthy());
        mockServer.verify();
    }
}