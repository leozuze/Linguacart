package com.linguacart.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * HTTP implementation of ModelServiceClient communicating with the Python FastAPI model service.
 */
public class HttpModelServiceClient implements ModelServiceClient {

    private static final Logger log = LoggerFactory.getLogger(HttpModelServiceClient.class);

    private final RestClient restClient;
    private final int maxRetries;
    private final long retryBackoffMs;

    public HttpModelServiceClient(String baseUrl,
                                  String apiKey,
                                  long connectTimeoutMs,
                                  long readTimeoutMs,
                                  int maxRetries,
                                  long retryBackoffMs) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory);

        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + apiKey)
                   .defaultHeader("X-API-Key", apiKey);
        }

        this.restClient = builder.build();
        this.maxRetries = Math.max(1, maxRetries);
        this.retryBackoffMs = Math.max(0, retryBackoffMs);
    }

    public HttpModelServiceClient(RestClient restClient, int maxRetries, long retryBackoffMs) {
        this.restClient = restClient;
        this.maxRetries = Math.max(1, maxRetries);
        this.retryBackoffMs = Math.max(0, retryBackoffMs);
    }

    @Override
    public String translate(String text, String sourceLanguage, String targetLanguage) {
        return executeWithRetry(() -> {
            TranslatePayload payload = new TranslatePayload(text, sourceLanguage, targetLanguage);
            TranslateResult result = restClient.post()
                    .uri("/translate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(TranslateResult.class);

            if (result == null || result.translation() == null) {
                throw new ModelServiceException("Model service returned empty translation");
            }
            return result.translation();
        }, "translate");
    }

    @Override
    public List<float[]> embed(List<String> texts, String prefix) {
        return executeWithRetry(() -> {
            EmbedPayload payload = new EmbedPayload(texts, prefix);
            EmbedResult result = restClient.post()
                    .uri("/embed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(EmbedResult.class);

            if (result == null || result.embeddings() == null) {
                throw new ModelServiceException("Model service returned empty embeddings");
            }
            return result.embeddings();
        }, "embed");
    }

    @Override
    public boolean isHealthy() {
        try {
            HealthResult result = restClient.get()
                    .uri("/health")
                    .retrieve()
                    .body(HealthResult.class);

            return result != null && "ok".equalsIgnoreCase(result.status());
        } catch (Exception e) {
            log.warn("Model service health check failed: {}", e.getMessage());
            return false;
        }
    }

    private <T> T executeWithRetry(Callable<T> action, String operationName) {
        int attempts = 0;
        Exception lastException = null;

        while (attempts < maxRetries) {
            attempts++;
            try {
                return action.call();
            } catch (RestClientException | ModelServiceException e) {
                lastException = e;
                log.warn("Model service '{}' failed on attempt {}/{}: {}",
                        operationName, attempts, maxRetries, e.getMessage());
            } catch (Exception e) {
                lastException = e;
                log.warn("Unexpected error in model service '{}' on attempt {}/{}: {}",
                        operationName, attempts, maxRetries, e.getMessage());
            }

            if (attempts < maxRetries) {
                try {
                    Thread.sleep(retryBackoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ModelServiceException("Interrupted during retry of " + operationName, ie);
                }
            }
        }

        throw new ModelServiceException(
                "Model service call failed after " + maxRetries + " attempts: " + operationName,
                lastException
        );
    }

    // Python model service request/response wire contracts
    record TranslatePayload(String text, String sourceLanguage, String targetLanguage) {}
    record TranslateResult(String translation) {}
    record EmbedPayload(List<String> texts, String prefix) {}
    record EmbedResult(List<float[]> embeddings, Integer dimension) {}
    record HealthResult(String status) {}
}
