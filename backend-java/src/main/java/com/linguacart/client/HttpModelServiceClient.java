package com.linguacart.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * HTTP implementation of ModelServiceClient for the Python FastAPI service.
 * Wire format source of truth: docs/model-service-openapi.json and docs/08-INTEGRATION-GUIDE.md.
 * All Python-specific JSON shapes stay inside this class.
 *
 * Retry policy: "maxRetries" is the TOTAL number of attempts. Connection failures and 5xx are retried.
 * 4xx answers, malformed answers and read timeouts are NOT retried (a translate call can legitimately
 * take a long time, and retrying it would multiply the wait).
 */
public class HttpModelServiceClient implements ModelServiceClient {

    private static final Logger log = LoggerFactory.getLogger(HttpModelServiceClient.class);

    /** The Python service accepts at most 64 items per request; stay well below. */
    static final int EMBED_CHUNK_SIZE = 32;

    private final RestClient translateClient;
    private final RestClient embedClient;
    private final int maxRetries;
    private final long retryBackoffMs;

    public HttpModelServiceClient(String baseUrl,
                                  String apiKey,
                                  long connectTimeoutMs,
                                  long translateReadTimeoutMs,
                                  long embedReadTimeoutMs,
                                  int maxRetries,
                                  long retryBackoffMs) {
        this.translateClient = buildClient(baseUrl, apiKey, connectTimeoutMs, translateReadTimeoutMs);
        this.embedClient = buildClient(baseUrl, apiKey, connectTimeoutMs, embedReadTimeoutMs);
        this.maxRetries = Math.max(1, maxRetries);
        this.retryBackoffMs = Math.max(0, retryBackoffMs);
    }

    /** Used by tests: one RestClient for every operation. */
    public HttpModelServiceClient(RestClient restClient, int maxRetries, long retryBackoffMs) {
        this.translateClient = restClient;
        this.embedClient = restClient;
        this.maxRetries = Math.max(1, maxRetries);
        this.retryBackoffMs = Math.max(0, retryBackoffMs);
    }

    private static RestClient buildClient(String baseUrl, String apiKey, long connectTimeoutMs, long readTimeoutMs) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory);

        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("X-API-Key", apiKey);
        }
        return builder.build();
    }

    @Override
    public String translate(String text, String sourceLanguage, String targetLanguage) {
        if (text == null) {
            return null;
        }
        return executeWithRetry(() -> {
            TranslatePayload payload = new TranslatePayload(sourceLanguage, targetLanguage, List.of(text));
            TranslateResult result = translateClient.post()
                    .uri("/translate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(TranslateResult.class);

            if (result == null
                    || result.translations() == null
                    || result.translations().size() != 1
                    || result.translations().get(0) == null) {
                throw new ModelServiceException("Model service returned an unexpected translation response");
            }
            return result.translations().get(0);
        }, "translate");
    }

    @Override
    public List<float[]> embed(List<String> texts, String prefix) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (!"query".equals(prefix) && !"passage".equals(prefix)) {
            throw new IllegalArgumentException("prefix must be 'query' or 'passage'");
        }
        List<float[]> all = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += EMBED_CHUNK_SIZE) {
            List<String> chunk = texts.subList(from, Math.min(texts.size(), from + EMBED_CHUNK_SIZE));
            all.addAll(embedChunk(chunk, prefix));
        }
        return all;
    }

    private List<float[]> embedChunk(List<String> chunk, String prefix) {
        return executeWithRetry(() -> {
            List<EmbedInput> inputs = new ArrayList<>(chunk.size());
            for (String text : chunk) {
                // The Python service adds the E5 "query: " / "passage: " prefixes itself.
                inputs.add(new EmbedInput(prefix, text));
            }
            EmbedResult result = embedClient.post()
                    .uri("/embed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new EmbedPayload(inputs))
                    .retrieve()
                    .body(EmbedResult.class);

            if (result == null
                    || result.embeddings() == null
                    || result.embeddings().size() != chunk.size()) {
                throw new ModelServiceException("Model service returned an unexpected embedding response");
            }
            return result.embeddings();
        }, "embed");
    }

    @Override
    public boolean isHealthy() {
        try {
            HealthResult result = embedClient.get()
                    .uri("/health")
                    .retrieve()
                    .body(HealthResult.class);
            // "degraded" means one of the two models failed to load.
            return result != null && "ok".equalsIgnoreCase(result.status());
        } catch (Exception e) {
            log.warn("Model service health check failed ({})", e.getClass().getSimpleName());
            return false;
        }
    }

    private <T> T executeWithRetry(Callable<T> action, String operationName) {
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return action.call();
            } catch (Exception e) {
                lastException = e;
                if (!isRetryable(e)) {
                    throw asModelServiceException(e, operationName);
                }
                // Log the exception type only: messages can echo request text.
                log.warn("Model service '{}' failed on attempt {}/{} ({})",
                        operationName, attempt, maxRetries, e.getClass().getSimpleName());
            }
            if (attempt < maxRetries) {
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
                lastException);
    }

    private static boolean isRetryable(Exception e) {
        if (e instanceof ModelServiceException) {
            return false;
        }
        if (e instanceof HttpClientErrorException) {
            return false;
        }
        return !isReadTimeout(e);
    }

    private static boolean isReadTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpConnectTimeoutException) {
                return false; // connecting timed out: worth retrying
            }
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static ModelServiceException asModelServiceException(Exception e, String operationName) {
        if (e instanceof ModelServiceException mse) {
            return mse;
        }
        if (e instanceof HttpClientErrorException h) {
            // A 4xx from Python means our request was wrong: report as 502, never retry.
            return new ModelServiceException(
                    "Model service rejected the '" + operationName + "' request (HTTP "
                            + h.getStatusCode().value() + ")",
                    e, HttpStatus.BAD_GATEWAY);
        }
        return new ModelServiceException("Model service timed out during '" + operationName + "'", e);
    }

    // Python model service wire contract (docs/model-service-openapi.json)
    record TranslatePayload(String sourceLanguage, String targetLanguage, List<String> texts) {}
    record TranslateResult(List<String> translations) {}
    record EmbedInput(String type, String text) {}
    record EmbedPayload(List<EmbedInput> inputs) {}
    record EmbedResult(List<float[]> embeddings) {}
    record HealthResult(String status) {}
}