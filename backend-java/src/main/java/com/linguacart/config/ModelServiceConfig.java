package com.linguacart.config;

import com.linguacart.client.HttpModelServiceClient;
import com.linguacart.client.MockModelServiceClient;
import com.linguacart.client.ModelServiceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ModelServiceConfig {

    @Bean
    @ConditionalOnProperty(name = "linguacart.model-service.mode", havingValue = "mock", matchIfMissing = true)
    public ModelServiceClient mockModelServiceClient() {
        return new MockModelServiceClient();
    }

    @Bean
    @ConditionalOnProperty(name = "linguacart.model-service.mode", havingValue = "http")
    public ModelServiceClient httpModelServiceClient(
            @Value("${linguacart.model-service.base-url:http://127.0.0.1:8001}") String baseUrl,
            @Value("${linguacart.model-service.api-key:}") String apiKey,
            @Value("${linguacart.model-service.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${linguacart.model-service.translate-read-timeout-ms:120000}") long translateReadTimeoutMs,
            @Value("${linguacart.model-service.embed-read-timeout-ms:15000}") long embedReadTimeoutMs,
            @Value("${linguacart.model-service.max-retries:2}") int maxRetries,
            @Value("${linguacart.model-service.retry-backoff-ms:300}") long retryBackoffMs
    ) {
        return new HttpModelServiceClient(baseUrl, apiKey, connectTimeoutMs,
                translateReadTimeoutMs, embedReadTimeoutMs, maxRetries, retryBackoffMs);
    }
}