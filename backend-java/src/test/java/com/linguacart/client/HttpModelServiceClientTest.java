package com.linguacart.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class HttpModelServiceClientTest {

    private MockRestServiceServer mockServer;
    private HttpModelServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8001");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        client = new HttpModelServiceClient(restClient, 3, 10);
    }

    @Test
    @DisplayName("translate sends correct JSON body and returns translation")
    void testTranslateSuccess() {
        mockServer.expect(requestTo("http://localhost:8001/translate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.text").value("salt"))
                .andExpect(jsonPath("$.sourceLanguage").value("eng_Latn"))
                .andExpect(jsonPath("$.targetLanguage").value("sna_Latn"))
                .andRespond(withSuccess("{\"translation\": \"munyu\"}", MediaType.APPLICATION_JSON));

        String result = client.translate("salt", "eng_Latn", "sna_Latn");
        assertEquals("munyu", result);
        mockServer.verify();
    }

    @Test
    @DisplayName("embed sends correct JSON body and returns embeddings")
    void testEmbedSuccess() {
        mockServer.expect(requestTo("http://localhost:8001/embed"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.texts[0]").value("salt"))
                .andExpect(jsonPath("$.prefix").value("query"))
                .andRespond(withSuccess("{\"embeddings\": [[0.1, 0.2, 0.3]], \"dimension\": 3}", MediaType.APPLICATION_JSON));

        List<float[]> embeddings = client.embed(List.of("salt"), "query");
        assertEquals(1, embeddings.size());
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f}, embeddings.get(0), 1e-4f);
        mockServer.verify();
    }

    @Test
    @DisplayName("retries on server error and succeeds if retry succeeds")
    void testRetrySuccess() {
        // First attempt fails with 500
        mockServer.expect(requestTo("http://localhost:8001/translate"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        // Second attempt succeeds
        mockServer.expect(requestTo("http://localhost:8001/translate"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"translation\": \"munyu\"}", MediaType.APPLICATION_JSON));

        String result = client.translate("salt", "eng_Latn", "sna_Latn");
        assertEquals("munyu", result);
        mockServer.verify();
    }

    @Test
    @DisplayName("exhausting retries throws ModelServiceException")
    void testRetriesExhausted() {
        mockServer.expect(requestTo("http://localhost:8001/translate")).andRespond(withServerError());
        mockServer.expect(requestTo("http://localhost:8001/translate")).andRespond(withServerError());
        mockServer.expect(requestTo("http://localhost:8001/translate")).andRespond(withServerError());

        assertThrows(ModelServiceException.class, () ->
                client.translate("salt", "eng_Latn", "sna_Latn")
        );
        mockServer.verify();
    }

    @Test
    @DisplayName("isHealthy returns true on 200 OK with status ok")
    void testIsHealthyTrue() {
        mockServer.expect(requestTo("http://localhost:8001/health"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"status\": \"ok\"}", MediaType.APPLICATION_JSON));

        assertTrue(client.isHealthy());
        mockServer.verify();
    }

    @Test
    @DisplayName("isHealthy returns false on server error")
    void testIsHealthyFalseOnError() {
        mockServer.expect(requestTo("http://localhost:8001/health"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        assertFalse(client.isHealthy());
        mockServer.verify();
    }
}
